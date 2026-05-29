package com.stapk.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.Switch;

import com.stapk.termux.R;
import com.stapk.termux.app.stapk.DebugRecord;
import com.stapk.termux.app.stapk.StapkForegroundService;
import com.stapk.termux.shared.file.FileUtils;
import com.stapk.termux.shared.logger.Logger;
import com.stapk.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * stAPK Control Panel Activity - The main launcher for stAPK Termux.
 *
 * Shows SillyTavern status and provides buttons for start, stop, open web UI,
 * update, view logs, and backup. The terminal is accessible via a hidden entry
 * (tap the version footer 7 times).
 */
public class StapkControlActivity extends Activity {

    private static final String LOG_TAG = "StapkControl";

    private static final String STAPK_ASSETS_DIR = "stapk";
    private static final String STAPK_BIN_DIR_PATH = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin";
    private static final int TERMINAL_TAP_COUNT_REQUIRED = 7;
    private static final long TERMINAL_TAP_TIMEOUT_MS = 2000;

    private static final int STATUS_POLL_INTERVAL_RUNNING_MS = 10000;
    private static final int STATUS_POLL_INTERVAL_STARTING_MS = 2000;
    private static final int STATUS_POLL_INTERVAL_STARTING_SLOW_MS = 5000;
    private static final long STARTING_PHASE_DURATION_MS = 30000;

    // Debug 相关常量
    private static final String STAPK_LOGS_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.stapk/logs";
    private static final String STAPK_DEBUG_LOG_PATH = STAPK_LOGS_DIR_PATH + "/debug.log";
    private static final int DEBUG_OUTPUT_TAIL_LINES = 200;

    // Payload assets 相关常量
    private static final String STAPK_PAYLOAD_ASSETS_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.stapk/assets";
    // 注意：aapt 会解压 .tar.gz → .tar，所以 APK 内的文件名是 SillyTavern.tar
    private static final String[] PAYLOAD_ASSET_FILES = {"payload-manifest.json", "SillyTavern.tar"};

    // 前台通知 - 防止后台被杀
    private static final String STAPK_FOREGROUND_CHANNEL_ID = "stapk_foreground";
    private static final int STAPK_FOREGROUND_NOTIFICATION_ID = 1400;

    private boolean mNotificationShown = false;
    private NotificationManager mNotificationManager;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private View mStatusIndicator;
    private TextView mStatusText;
    private TextView mVersionText;
    private Button mBtnStart;
    private Button mBtnStop;
    private Button mBtnOpen;
    private Button mBtnUpdate;
    private Button mBtnRollback;
    private Button mBtnLogs;
    private Button mBtnBackup;
    private Button mBtnRestore;
    private TextView mAppVersionFooter;

    // Debug UI
    private Switch mDebugToggle;
    private View mDebugPanel;
    private TextView mDebugContent;
    private Button mDebugCopy;

    // Debug 状态
    private boolean mDebugEnabled = false;
    private volatile DebugRecord mLastDebugRecord = null;

    private int mTerminalTapCount = 0;
    private long mLastTerminalTapTime = 0;

    // 用于在 Activity 销毁时通知后台线程尽早退出，避免线程泄漏
    private volatile boolean mDestroyed = false;
    // 防止 onResume 在 bootstrap 安装完成前调 runStapkStatus
    private volatile boolean mBootstrapReady = false;

    private boolean mIsRunning = false;
    private boolean mIsStarting = false;
    private boolean mIsUpdating = false;
    private boolean mNeedsInit = false;
    private long mStartTime = 0;

    private final Runnable mStatusPoller = new Runnable() {
        @Override
        public void run() {
            runStapkStatus();
            int interval = getPollInterval();
            mHandler.postDelayed(this, interval);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stapk_control);

        initViews();
        setupButtonListeners();

        // 首先确保 bootstrap 已安装（解压 bash/node/git 等基础环境）
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            onBootstrapReady();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mDestroyed) return;
        if (mBootstrapReady) startStatusPolling();
        checkRollbackAvailable();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void initViews() {
        mStatusIndicator = findViewById(R.id.status_indicator);
        mStatusText = findViewById(R.id.status_text);
        mVersionText = findViewById(R.id.version_text);
        mBtnStart = findViewById(R.id.btn_start);
        mBtnStop = findViewById(R.id.btn_stop);
        mBtnOpen = findViewById(R.id.btn_open);
        mBtnUpdate = findViewById(R.id.btn_update);
        mBtnRollback = findViewById(R.id.btn_rollback);
        mBtnLogs = findViewById(R.id.btn_logs);
        mBtnBackup = findViewById(R.id.btn_backup);
        mBtnRestore = findViewById(R.id.btn_restore);
        mAppVersionFooter = findViewById(R.id.app_version_footer);

        // Debug UI
        mDebugToggle = findViewById(R.id.debug_toggle);
        mDebugPanel = findViewById(R.id.debug_panel);
        mDebugContent = findViewById(R.id.debug_content);
        mDebugCopy = findViewById(R.id.debug_copy);
    }

    private void setupButtonListeners() {
        mBtnStart.setOnClickListener(v -> onStartClicked());
        mBtnStop.setOnClickListener(v -> onStopClicked());
        mBtnOpen.setOnClickListener(v -> onOpenClicked());
        mBtnUpdate.setOnClickListener(v -> onUpdateClicked());
        mBtnRollback.setOnClickListener(v -> onRollbackClicked());
        mBtnLogs.setOnClickListener(v -> onLogsClicked());
        mBtnBackup.setOnClickListener(v -> onBackupClicked());
        mBtnRestore.setOnClickListener(v -> onRestoreClicked());
        mAppVersionFooter.setOnClickListener(v -> onVersionFooterClicked());

        // Debug 开关监听
        mDebugToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mDebugEnabled = isChecked;
            updateDebugPanelVisibility();
        });

        // Debug 复制按钮
        mDebugCopy.setOnClickListener(v -> copyDebugRecord());
    }

    // ---- Script Deployment ----

    void onBootstrapReady() {
        mBootstrapReady = true;
        deployScriptsIfNeeded();
        startStatusPolling();
        runStapkStatus();
    }

    void deployScriptsIfNeeded() {
        String[] scripts = {"stapk-init", "stapk-start", "stapk-status", "stapk-stop",
                "stapk-update", "stapk-rollback", "stapk-open-url", "stapk-backup",
                "stapk-restore", "stapk-list-backups", "stapk-report"};

        File binDir = new File(STAPK_BIN_DIR_PATH);
        if (!binDir.isDirectory()) return;

        // 始终重新部署脚本，确保更新生效。脚本体积很小(<10KB)，同步执行无性能影响。
        // 必须在 startStatusPolling 之前完成，避免首次轮询时脚本尚未就绪。
        AssetManager assets = getAssets();
        try {
            for (String script : scripts) {
                File target = new File(binDir, script);
                try (InputStream in = assets.open(STAPK_ASSETS_DIR + "/" + script);
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                target.setExecutable(true, false);
            }
            Logger.logDebug(LOG_TAG, "stAPK scripts deployed to " + STAPK_BIN_DIR_PATH);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to deploy stAPK scripts: " + e.getMessage());
        }
    }

    /**
     * Deploy payload assets (payload-manifest.json, SillyTavern.tar) from APK assets
     * to $HOME/.stapk/assets/ for stapk-init to use.
     * <p>
     * File copy runs on a background thread to avoid blocking UI (~157MB payload).
     * onComplete is called on the main thread after deployment finishes (or immediately
     * if assets are already present).
     */
    private void deployPayloadAssets(Runnable onComplete) {
        File assetsDir = new File(STAPK_PAYLOAD_ASSETS_DIR_PATH);
        if (!assetsDir.isDirectory()) {
            assetsDir.mkdirs();
        }

        // Check if all payload assets are already deployed
        boolean allPresent = true;
        for (String file : PAYLOAD_ASSET_FILES) {
            if (!new File(assetsDir, file).exists()) {
                allPresent = false;
                break;
            }
        }
        if (allPresent) {
            onComplete.run();
            return;
        }

        // 在后台线程复制大文件，避免阻塞UI
        new Thread(() -> {
            AssetManager assets = getAssets();
            try {
                for (String file : PAYLOAD_ASSET_FILES) {
                    File target = new File(assetsDir, file);
                    if (target.exists()) continue;
                    try (InputStream in = assets.open(file);
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    Logger.logDebug(LOG_TAG, "Deployed payload asset: " + file);
                }
                Logger.logDebug(LOG_TAG, "Payload assets deployed to " + STAPK_PAYLOAD_ASSETS_DIR_PATH);
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to deploy payload assets: " + e.getMessage());
            }
            mHandler.post(onComplete);
        }).start();
    }

    // ---- Rollback Check ----

    private void checkRollbackAvailable() {
        String statePath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.stapk/state/last-update-failed-commit";
        boolean hasPendingRollback = new File(statePath).exists();
        if (hasPendingRollback) {
            mBtnRollback.setVisibility(View.VISIBLE);
        }
    }

    // ---- Status Polling ----

    void startStatusPolling() {
        mHandler.removeCallbacks(mStatusPoller);
        mHandler.post(mStatusPoller);
    }

    private int getPollInterval() {
        if (mIsStarting) {
            long elapsed = System.currentTimeMillis() - mStartTime;
            return elapsed < STARTING_PHASE_DURATION_MS
                    ? STATUS_POLL_INTERVAL_STARTING_MS
                    : STATUS_POLL_INTERVAL_STARTING_SLOW_MS;
        }
        return STATUS_POLL_INTERVAL_RUNNING_MS;
    }

    void runStapkStatus() {
        new Thread(() -> {
            String result = executeScriptSync("stapk-status");
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                processStatusResult(result);
            });
        }).start();
    }

    private void processStatusResult(String json) {
        if (json == null || json.isEmpty()) {
            setStatusUI(getString(R.string.stapk_status_unknown));
            return;
        }

        String status = extractJsonValue(json, "status");
        String version = extractJsonValue(json, "sillytavern_version");
        String commit = extractJsonValue(json, "sillytavern_commit");

        switch (status) {
            case "running":
                mIsRunning = true;
                mIsStarting = false;
                mNeedsInit = false;
                setStatusUI(getString(R.string.stapk_status_running));
                setVersionUI(version, commit);
                setIndicatorColor(Color.parseColor("#FF4CAF50"));
                mBtnStart.setVisibility(View.GONE);
                mBtnStop.setVisibility(View.VISIBLE);
                mBtnOpen.setEnabled(true);
                showForegroundNotification();
                break;
            case "starting":
                mIsRunning = false;
                mIsStarting = true;
                setStatusUI(getString(R.string.stapk_status_starting));
                setIndicatorColor(Color.parseColor("#FFFF9800"));
                mBtnStart.setVisibility(View.GONE);
                mBtnStop.setVisibility(View.VISIBLE);
                mBtnOpen.setEnabled(false);
                hideForegroundNotification();
                break;
            case "not_initialized":
                mNeedsInit = true;
                setStatusUI(getString(R.string.stapk_msg_init_needed));
                setIndicatorColor(Color.parseColor("#FFFF5252"));
                mBtnStart.setVisibility(View.VISIBLE);
                mBtnStart.setText(R.string.stapk_btn_init);
                mBtnStop.setVisibility(View.GONE);
                mBtnOpen.setEnabled(false);
                hideForegroundNotification();
                break;
            case "stopped":
            default:
                mIsRunning = false;
                mIsStarting = false;
                mNeedsInit = false;
                setStatusUI(getString(R.string.stapk_status_stopped));
                setVersionUI(version, commit);
                setIndicatorColor(Color.parseColor("#FF555555"));
                mBtnStart.setVisibility(View.VISIBLE);
                mBtnStart.setText(R.string.stapk_btn_start);
                mBtnStop.setVisibility(View.GONE);
                mBtnOpen.setEnabled(false);
                hideForegroundNotification();
                break;
        }
    }

    // ---- Foreground Notification (Service) ----

    private void showForegroundNotification() {
        if (mNotificationShown) return;
        mNotificationShown = true;
        Intent intent = new Intent(this, StapkForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void hideForegroundNotification() {
        if (!mNotificationShown) return;
        mNotificationShown = false;
        Intent intent = new Intent(this, StapkForegroundService.class);
        intent.setAction("STOP");
        startService(intent);
    }

    private void setStatusUI(String label) {
        mStatusText.setText(label);
    }

    private void setVersionUI(String version, String commit) {
        if (version != null && !version.isEmpty() && !"unknown".equals(version)) {
            mVersionText.setText(getString(R.string.stapk_version_format, version,
                    commit != null ? commit : "?"));
        }
    }

    private void setIndicatorColor(int color) {
        if (mStatusIndicator.getBackground() != null) {
            mStatusIndicator.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
        } else {
            mStatusIndicator.setBackgroundColor(color);
        }
    }

    // ---- Button Handlers ----

    private void onStartClicked() {
        if (mIsRunning) {
            Toast.makeText(this, R.string.stapk_msg_already_running, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if we need to initialize first
        if (mNeedsInit) {
            runInitialization();
            return;
        }

        mIsStarting = true;
        mStartTime = System.currentTimeMillis();
        setStatusUI(getString(R.string.stapk_status_starting));
        setIndicatorColor(Color.parseColor("#FFFF9800"));
        mBtnStart.setVisibility(View.GONE);
        mBtnStop.setVisibility(View.VISIBLE);
        mBtnOpen.setEnabled(false);

        executeScriptInBackground("stapk-start", 10, result -> {
            String trimmed = result != null ? result.trim() : "";
            if (trimmed.startsWith("STARTED:")) {
                showForegroundNotification();
                Toast.makeText(this, R.string.stapk_msg_start_success, Toast.LENGTH_SHORT).show();
            } else if (trimmed.contains("ALREADY_RUNNING")) {
                Toast.makeText(this, R.string.stapk_msg_already_running, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.stapk_msg_start_failed, Toast.LENGTH_LONG).show();
            }
            runStapkStatus();
        });
    }

    private void runInitialization() {
        setStatusUI(getString(R.string.stapk_status_initializing));
        setIndicatorColor(Color.parseColor("#FFFF9800"));
        mBtnStart.setEnabled(false);

        // 先在后台部署 payload，完成后再执行 stapk-init
        deployPayloadAssets(() -> {
            executeScriptInBackground("stapk-init", 120, result -> {
                mBtnStart.setEnabled(true);
                String trimmed = result != null ? result.trim() : "";
                if ("SUCCESS".equals(trimmed) || "ALREADY_INITIALIZED".equals(trimmed)) {
                    Toast.makeText(this, R.string.stapk_msg_init_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.stapk_msg_init_failed, trimmed), Toast.LENGTH_LONG).show();
                }
                runStapkStatus();
            });
        });
    }

    private void onStopClicked() {
        if (!mIsRunning) {
            Toast.makeText(this, R.string.stapk_msg_already_stopped, Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.stapk_confirm_stop_title)
                .setMessage(R.string.stapk_confirm_stop_message)
                .setPositiveButton(R.string.stapk_confirm_yes, (d, w) -> {
                    mIsRunning = false;
                    mIsStarting = false;
                    setStatusUI(getString(R.string.stapk_status_stopping));
                    setIndicatorColor(Color.parseColor("#FFFF9800"));
                    mBtnStop.setEnabled(false);
                    executeScriptInBackground("stapk-stop", result -> {
                        mBtnStop.setEnabled(true);
                        Toast.makeText(this, R.string.stapk_msg_stop_success, Toast.LENGTH_SHORT).show();
                        runStapkStatus();
                    });
                })
                .setNegativeButton(R.string.stapk_confirm_no, null)
                .show();
    }

    private void onOpenClicked() {
        executeScriptInBackground("stapk-open-url", result -> {
            if (result != null && result.startsWith("OPEN:")) {
                String url = result.substring(5).trim();
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.stapk_msg_no_browser, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, R.string.stapk_msg_no_browser, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void onUpdateClicked() {
        if (mIsRunning) {
            Toast.makeText(this, R.string.stapk_msg_stop_before_update, Toast.LENGTH_SHORT).show();
            return;
        }
        if (mIsUpdating) {
            Toast.makeText(this, R.string.stapk_msg_update_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.stapk_confirm_update_title)
                .setMessage(R.string.stapk_confirm_update_message)
                .setPositiveButton(R.string.stapk_confirm_yes, (d, w) -> {
                    mIsUpdating = true;
                    setStatusUI(getString(R.string.stapk_status_updating));
                    executeScriptInBackground("stapk-update", result -> {
                        mIsUpdating = false;
                        String trimmed = result != null ? result.trim() : "";
                        if (trimmed.startsWith("UPDATED:") || trimmed.startsWith("ALREADY_UP_TO_DATE")) {
                            mBtnRollback.setVisibility(View.GONE);
                            Toast.makeText(this, R.string.stapk_msg_update_success, Toast.LENGTH_SHORT).show();
                        } else {
                            mBtnRollback.setVisibility(View.VISIBLE);
                            Toast.makeText(this, R.string.stapk_msg_update_failed, Toast.LENGTH_LONG).show();
                        }
                        runStapkStatus();
                    });
                })
                .setNegativeButton(R.string.stapk_confirm_no, null)
                .show();
    }

    private void onRollbackClicked() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.stapk_confirm_rollback_title)
                .setMessage(R.string.stapk_confirm_rollback_message)
                .setPositiveButton(R.string.stapk_confirm_yes, (d, w) -> {
                    setStatusUI(getString(R.string.stapk_status_updating));
                    setIndicatorColor(Color.parseColor("#FFFF9800"));
                    mBtnRollback.setEnabled(false);
                    executeScriptInBackground("stapk-rollback", 60, result -> {
                        mBtnRollback.setEnabled(true);
                        String trimmed = result != null ? result.trim() : "";
                        if (trimmed.startsWith("ROLLED_BACK:")) {
                            mBtnRollback.setVisibility(View.GONE);
                            Toast.makeText(this, R.string.stapk_msg_rollback_success, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, R.string.stapk_msg_rollback_failed, Toast.LENGTH_LONG).show();
                        }
                        runStapkStatus();
                    });
                })
                .setNegativeButton(R.string.stapk_confirm_no, null)
                .show();
    }

    private void onLogsClicked() {
        Intent intent = new Intent(this, StapkLogActivity.class);
        startActivity(intent);
    }

    private void onBackupClicked() {
        executeScriptInBackground("stapk-backup", result -> {
            String trimmed = result != null ? result.trim() : "";
            if (trimmed.startsWith("SUCCESS:")) {
                // Format: SUCCESS:/path/to/file:size - path may contain ':' on some systems
                int firstColon = trimmed.indexOf(':');
                int secondColon = trimmed.indexOf(':', firstColon + 1);
                String path = secondColon > firstColon
                        ? trimmed.substring(firstColon + 1, secondColon)
                        : trimmed.substring(firstColon + 1);
                Toast.makeText(this, getString(R.string.stapk_msg_backup_saved, path), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.stapk_msg_backup_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void onRestoreClicked() {
        if (mIsRunning) {
            Toast.makeText(this, R.string.stapk_msg_stop_before_restore, Toast.LENGTH_SHORT).show();
            return;
        }

        executeScriptInBackground("stapk-list-backups", result -> {
            if (result == null || result.contains("NO_BACKUPS")) {
                Toast.makeText(this, R.string.stapk_msg_no_backups, Toast.LENGTH_SHORT).show();
                return;
            }

            // Parse backup lines: BACKUP|filename|size|datetime|path
            String[] lines = result.split("\n");
            java.util.List<String> paths = new java.util.ArrayList<>();
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("BACKUP|")) {
                    String[] parts = line.split("\\|", 5);
                    if (parts.length >= 5) {
                        labels.add(parts[1] + " (" + parts[3] + ")");
                        paths.add(parts[4]);
                    }
                }
            }

            if (paths.isEmpty()) {
                Toast.makeText(this, R.string.stapk_msg_no_backups, Toast.LENGTH_SHORT).show();
                return;
            }

            String[] labelArray = labels.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.stapk_select_backup)
                    .setItems(labelArray, (d, which) -> {
                        String selectedPath = paths.get(which);
                        confirmRestore(selectedPath);
                    })
                    .setNegativeButton(R.string.stapk_confirm_no, null)
                    .show();
        });
    }

    private void confirmRestore(String backupPath) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.stapk_confirm_restore_title)
                .setMessage(R.string.stapk_confirm_restore_message)
                .setPositiveButton(R.string.stapk_confirm_yes, (d, w) -> {
                    setStatusUI(getString(R.string.stapk_status_updating));
                    setIndicatorColor(Color.parseColor("#FFFF9800"));
                    executeScriptInBackground("stapk-restore", 120, result -> {
                        String trimmed = result != null ? result.trim() : "";
                        if (trimmed.startsWith("SUCCESS:")) {
                            Toast.makeText(this, R.string.stapk_msg_restore_success, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, getString(R.string.stapk_msg_restore_failed, trimmed), Toast.LENGTH_LONG).show();
                        }
                        runStapkStatus();
                    }, backupPath);
                })
                .setNegativeButton(R.string.stapk_confirm_no, null)
                .show();
    }

    private void onVersionFooterClicked() {
        long now = System.currentTimeMillis();
        if (now - mLastTerminalTapTime > TERMINAL_TAP_TIMEOUT_MS) {
            mTerminalTapCount = 0;
        }
        mTerminalTapCount++;
        mLastTerminalTapTime = now;

        if (mTerminalTapCount >= TERMINAL_TAP_COUNT_REQUIRED) {
            mTerminalTapCount = 0;
            showTerminalEntryDialog();
        }
    }

    private void showTerminalEntryDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.stapk_terminal_dialog_title)
                .setMessage(R.string.stapk_terminal_dialog_message)
                .setPositiveButton(R.string.stapk_terminal_entry, (d, w) -> {
                    Intent intent = new Intent(this, TermuxActivity.class);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.stapk_confirm_no, null)
                .show();
    }

    // ---- Script Execution Helpers ----

    private void executeScriptInBackground(String scriptName, ScriptCallback callback) {
        executeScriptInBackground(scriptName, 30, callback);
    }

    private void executeScriptInBackground(String scriptName, int timeoutSeconds, ScriptCallback callback) {
        executeScriptInBackground(scriptName, timeoutSeconds, callback, (String[]) null);
    }

    private void executeScriptInBackground(String scriptName, int timeoutSeconds, ScriptCallback callback, String... args) {
        new Thread(() -> {
            // Activity 已销毁，提前退出，避免线程泄漏
            if (mDestroyed) return;
            String result = executeScriptSync(scriptName, timeoutSeconds, args);
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                callback.onResult(result);
            });
        }).start();
    }

    private String executeScriptSync(String scriptName) {
        return executeScriptSync(scriptName, 30, (String[]) null);
    }

    private String executeScriptSync(String scriptName, int timeoutSeconds) {
        return executeScriptSync(scriptName, timeoutSeconds, (String[]) null);
    }

    private String executeScriptSync(String scriptName, int timeoutSeconds, String... args) {
        // Debug 采集：记录开始时间
        long startTimeMs = System.currentTimeMillis();

        String scriptPath = STAPK_BIN_DIR_PATH + "/" + scriptName;
        File script = new File(scriptPath);

        // 构建命令行字符串
        StringBuilder cmdBuilder = new StringBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        cmdBuilder.append("/bash ").append(scriptPath);
        if (args != null) {
            for (String arg : args) {
                if (arg != null) cmdBuilder.append(" ").append(arg);
            }
        }
        String commandLine = cmdBuilder.toString();

        // 检查脚本是否可执行
        if (!script.canExecute()) {
            Logger.logError(LOG_TAG, "Script not found or not executable: " + scriptPath);

            // Debug 采集：脚本不可执行
            long endTimeMs = System.currentTimeMillis();
            DebugRecord record = new DebugRecord(
                    scriptName, commandLine, startTimeMs, endTimeMs,
                    DebugRecord.EXIT_CODE_NOT_EXECUTABLE, "(脚本不可执行)",
                    getLogFilePathForScript(scriptName),
                    false, getBootstrapStatus(), true
            );
            updateDebugRecord(record);
            return null;
        }

        Process process = null;
        Thread readerThread = null;
        int exitCode = -1;
        String outputStr = "";
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash");
            command.add(scriptPath);
            if (args != null) {
                for (String arg : args) {
                    if (arg != null) command.add(arg);
                }
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin:/system/bin:/system/xbin");
            pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib");
            pb.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            pb.redirectErrorStream(true);

            process = pb.start();

            // 在独立线程中读取 stdout，避免阻塞 waitFor(timeout)
            // 否则如果进程 stdout 一直不关闭，readLine 永远不返回 null，waitFor 永远无法被调用
            final StringBuilder output = new StringBuilder();
            final Process proc = process; // 捕获为 effectively final 供 lambda 使用
            readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while (!mDestroyed && (line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException ignored) {
                    // reader 被关闭（进程被 destroy 后可能出现）
                }
            }, "stapk-stdout-" + scriptName);
            readerThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Logger.logError(LOG_TAG, "Script " + scriptName + " timed out after " + timeoutSeconds + "s");

                // Debug 采集：超时
                long endTimeMs = System.currentTimeMillis();
                // 等待 reader 线程最多 2 秒收集完已有输出
                try { readerThread.join(2000); } catch (InterruptedException ignored) {}
                outputStr = output.toString();
                String outputTail = getOutputTail(outputStr, DEBUG_OUTPUT_TAIL_LINES);
                DebugRecord record = new DebugRecord(
                        scriptName, commandLine, startTimeMs, endTimeMs,
                        -1, outputTail,
                        getLogFilePathForScript(scriptName),
                        true, getBootstrapStatus(), true
                );
                updateDebugRecord(record);
                return "ERROR_TIMEOUT";
            }
            exitCode = process.exitValue();
            // 等待 reader 线程收集完所有输出
            try { readerThread.join(5000); } catch (InterruptedException ignored) {}
            outputStr = output.toString();

            // Debug 采集：正常结束
            long endTimeMs = System.currentTimeMillis();
            String outputTail = getOutputTail(outputStr, DEBUG_OUTPUT_TAIL_LINES);
            DebugRecord record = new DebugRecord(
                    scriptName, commandLine, startTimeMs, endTimeMs,
                    exitCode, outputTail,
                    getLogFilePathForScript(scriptName),
                    true, getBootstrapStatus(), true
            );
            updateDebugRecord(record);

            return outputStr.trim();
        } catch (IOException | InterruptedException e) {
            if (process != null) process.destroyForcibly();
            // 确保 reader 线程也被中断并等待退出
            if (readerThread != null) {
                readerThread.interrupt();
                try { readerThread.join(2000); } catch (InterruptedException ignored) {}
            }
            Logger.logError(LOG_TAG, "Failed to execute " + scriptName + ": " + e.getMessage());

            // Debug 采集：异常
            long endTimeMs = System.currentTimeMillis();
            DebugRecord record = new DebugRecord(
                    scriptName, commandLine, startTimeMs, endTimeMs,
                    -1, "异常: " + e.getMessage(),
                    getLogFilePathForScript(scriptName),
                    true, getBootstrapStatus(), true
            );
            updateDebugRecord(record);
            return null;
        }
    }

    private interface ScriptCallback {
        void onResult(String result);
    }

    // ---- Debug Helpers ----

    /**
     * 获取脚本对应的日志文件路径。
     */
    private String getLogFilePathForScript(String scriptName) {
        switch (scriptName) {
            case "stapk-start":
                return STAPK_LOGS_DIR_PATH + "/start.log";
            case "stapk-init":
                return STAPK_LOGS_DIR_PATH + "/init.log";
            case "stapk-update":
                return STAPK_LOGS_DIR_PATH + "/update.log";
            case "stapk-backup":
                return STAPK_LOGS_DIR_PATH + "/backup.log";
            default:
                return "(无对应日志文件)";
        }
    }

    /**
     * 获取 Bootstrap 状态。
     */
    private DebugRecord.BootstrapStatus getBootstrapStatus() {
        File prefixDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!prefixDir.exists() || !prefixDir.isDirectory()) {
            return DebugRecord.BootstrapStatus.NOT_READY;
        }
        File bashFile = new File(STAPK_BIN_DIR_PATH + "/bash");
        if (bashFile.exists() && bashFile.canExecute()) {
            return DebugRecord.BootstrapStatus.READY;
        }
        return DebugRecord.BootstrapStatus.NOT_READY;
    }

    /**
     * 获取输出末尾 N 行。
     */
    private String getOutputTail(String output, int maxLines) {
        if (output == null || output.isEmpty()) {
            return "(空)";
        }
        String[] lines = output.split("\n");
        if (lines.length <= maxLines) {
            return output;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * 更新调试记录（UI + 落盘）。
     */
    private void updateDebugRecord(DebugRecord record) {
        mLastDebugRecord = record;

        // 落盘到 debug.log
        boolean writeSuccess = writeDebugLog(record);

        // 如果落盘失败，更新记录并重新落盘（记录落盘失败状态）
        if (!writeSuccess) {
            DebugRecord failedRecord = new DebugRecord(
                    record.scriptName, record.commandLine,
                    record.startTimeMs, record.endTimeMs,
                    record.exitCode, record.stdoutTail,
                    record.logFilePath, record.scriptExecutable,
                    record.bootstrapStatus, false
            );
            mLastDebugRecord = failedRecord;
        }

        // 更新 UI（在主线程）
        mHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            updateDebugPanelContent();
            if (!writeSuccess) {
                Toast.makeText(this, R.string.stapk_debug_log_write_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 写入 debug.log。
     */
    private boolean writeDebugLog(DebugRecord record) {
        try {
            // 确保日志目录存在
            File logsDir = new File(STAPK_LOGS_DIR_PATH);
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }

            // 追加写入
            String logContent = record.toLogString();
            com.stapk.termux.shared.models.errors.Error error = FileUtils.writeStringToFile(
                    "debug log", STAPK_DEBUG_LOG_PATH,
                    StandardCharsets.UTF_8, logContent, true
            );
            return error == null;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to write debug log: " + e.getMessage());
            return false;
        }
    }

    /**
     * 更新调试面板可见性。
     */
    private void updateDebugPanelVisibility() {
        if (mDebugEnabled) {
            mDebugPanel.setVisibility(View.VISIBLE);
            updateDebugPanelContent();
        } else {
            mDebugPanel.setVisibility(View.GONE);
        }
    }

    /**
     * 更新调试面板内容。
     */
    private void updateDebugPanelContent() {
        if (mLastDebugRecord != null) {
            mDebugContent.setText(mLastDebugRecord.toDisplayString());
        } else {
            mDebugContent.setText(R.string.stapk_debug_empty);
        }
    }

    /**
     * 复制调试记录到剪贴板。
     */
    private void copyDebugRecord() {
        if (mLastDebugRecord == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("stAPK Debug Record", mLastDebugRecord.toDisplayString());
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.stapk_debug_copied, Toast.LENGTH_SHORT).show();
    }

    // ---- JSON Helpers ----

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return "";

        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return "";

        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length()) return "";

        if (json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) return "";
            return json.substring(valueStart + 1, valueEnd);
        } else {
            int valueEnd = valueStart;
            while (valueEnd < json.length() && json.charAt(valueEnd) != ','
                    && json.charAt(valueEnd) != '}' && !Character.isWhitespace(json.charAt(valueEnd))) {
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd);
        }
    }
}
