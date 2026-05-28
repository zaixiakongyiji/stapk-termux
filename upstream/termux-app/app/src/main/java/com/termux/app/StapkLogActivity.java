package com.termux.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Activity to display stAPK log files.
 * Shows init, start, update, and backup logs with tab-like switching.
 */
public class StapkLogActivity extends Activity {

    private static final String STAPK_LOGS_DIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.stapk/logs";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView mLogContent;
    private String mCurrentLog = "start";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stapk_log);

        mLogContent = findViewById(R.id.log_content);

        Button btnInit = findViewById(R.id.btn_log_init);
        Button btnStart = findViewById(R.id.btn_log_start);
        Button btnUpdate = findViewById(R.id.btn_log_update);
        Button btnBackup = findViewById(R.id.btn_log_backup);
        Button btnCopy = findViewById(R.id.btn_log_copy);
        Button btnRefresh = findViewById(R.id.btn_log_refresh);

        btnInit.setOnClickListener(v -> { mCurrentLog = "init"; loadLogAsync("init.log"); });
        btnStart.setOnClickListener(v -> { mCurrentLog = "start"; loadLogAsync("start.log"); });
        btnUpdate.setOnClickListener(v -> { mCurrentLog = "update"; loadLogAsync("update.log"); });
        btnBackup.setOnClickListener(v -> { mCurrentLog = "backup"; loadLogAsync("backup.log"); });
        btnRefresh.setOnClickListener(v -> loadLogAsync(mCurrentLog + ".log"));
        btnCopy.setOnClickListener(v -> copyLog());

        loadLogAsync("start.log");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void loadLogAsync(String filename) {
        mLogContent.setText(R.string.stapk_log_loading);
        new Thread(() -> {
            String content = readLogFile(filename);
            mHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                mLogContent.setText(content);
            });
        }).start();
    }

    private String readLogFile(String filename) {
        File logFile = new File(STAPK_LOGS_DIR, filename);
        if (!logFile.exists() || !logFile.canRead()) {
            return getString(R.string.stapk_log_empty);
        }

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            return getString(R.string.stapk_log_read_failed, e.getMessage());
        }

        return content.length() > 0 ? content.toString() : getString(R.string.stapk_log_empty);
    }

    private void copyLog() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("stAPK Log", mLogContent.getText());
        cm.setPrimaryClip(clip);
        Toast.makeText(this, R.string.stapk_log_copied, Toast.LENGTH_SHORT).show();
    }
}
