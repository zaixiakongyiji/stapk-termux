# stAPK 前台运行时改造 Implementation Plan

> **For agentic workers / 给执行代理：** REQUIRED SUB-SKILL: 优先使用 `superpowers:subagent-driven-development`，或使用 `superpowers:executing-plans` 逐任务执行。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标 / Goal：** 把 SillyTavern 从当前的 `nohup` 脱管后台进程切换为 `TermuxService` 托管的前台运行时，确保用户切到浏览器或其他 App 后，本地 `localhost` 服务仍保持可响应。

**架构 / Architecture：** 新增一个长期运行的 `stapk-runtime` 脚本，作为 `TermuxService` 里的后台 `TermuxTask` 执行入口。`StapkControlActivity` 不再调用 `stapk-start` 去 `nohup` 拉起进程，也不再维护独立的 `StapkForegroundService` 通知，而是直接通过 `ACTION_SERVICE_EXECUTE` 把 `stapk-runtime` 交给 `TermuxService` 托管。`stapk-stop` 和 `stapk-status` 优先围绕托管运行时的 PID 文件工作，同时保留对旧版 `node server.js` 脱管模式的兼容清理。

**技术栈 / Tech Stack：** Android Java、Termux `TermuxService` / `TermuxTask`、Shell 脚本、JUnit 4、Robolectric、Gradle

---

## 文件结构

### 新建文件

- `upstream/termux-app/app/src/main/java/com/stapk/termux/app/stapk/StapkRuntimeController.java`
  - 负责构造并发送 `TermuxService` 托管启动 Intent，隔离 `StapkControlActivity` 里的运行时调度逻辑。
- `upstream/termux-app/app/src/main/java/com/stapk/termux/app/stapk/StapkStatusSnapshot.java`
  - 负责解析 `stapk-status` 输出的 JSON，统一承载 `status`、`runtime_managed`、`runtime_pid` 等字段。
- `upstream/termux-app/app/src/test/java/com/stapk/termux/app/stapk/StapkRuntimeControllerTest.java`
  - 覆盖启动 Intent 的 action、component、script path、workdir、前后台模式等约束。
- `upstream/termux-app/app/src/test/java/com/stapk/termux/app/stapk/StapkStatusSnapshotTest.java`
  - 覆盖托管运行时 JSON 解析、字段缺省值处理、异常 JSON 兜底。
- `upstream/termux-app/app/src/main/assets/stapk/stapk-runtime`
  - 作为长期运行的托管脚本，负责 wake lock、日志、PID 文件、子进程清理。

### 修改文件

- `upstream/termux-app/app/src/main/java/com/stapk/termux/app/StapkControlActivity.java`
  - 启动流程从 `executeScriptInBackground("stapk-start")` 切换为 `StapkRuntimeController.start(this)`。
  - 状态解析改为 `StapkStatusSnapshot.fromJson(...)`。
  - 删除对 `StapkForegroundService` 的显示/隐藏控制。
- `upstream/termux-app/app/src/main/assets/stapk/stapk-status`
  - 以托管运行时 PID 文件为第一信号源，扩展输出字段。
- `upstream/termux-app/app/src/main/assets/stapk/stapk-stop`
  - 优先停止托管运行时包装脚本，再兜底清理遗留 `node` 进程和旧 `start.lock`。
- `upstream/termux-app/app/src/main/assets/stapk/stapk-start`
  - 保留兼容入口，但不再作为 GUI 主启动链路；补充提示和向后兼容说明。
- `upstream/termux-app/app/src/main/AndroidManifest.xml`
  - 移除 `StapkForegroundService` 注册。
- `README.md`
  - 更新“自动处理后台运行”的实现描述，改成基于 `TermuxService` 前台托管。

### 删除文件

- `upstream/termux-app/app/src/main/java/com/stapk/termux/app/stapk/StapkForegroundService.java`
  - 删除旧的独立通知服务，避免保活模型双轨并存。

---

### Task 1: 抽取托管运行时控制器

**Files:**
- Create: `upstream/termux-app/app/src/main/java/com/stapk/termux/app/stapk/StapkRuntimeController.java`
- Test: `upstream/termux-app/app/src/test/java/com/stapk/termux/app/stapk/StapkRuntimeControllerTest.java`

- [ ] **Step 1: 先写失败的启动 Intent 单测**

```java
package com.stapk.termux.app.stapk;

import android.content.Intent;

import com.stapk.termux.app.TermuxService;
import com.stapk.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;

public class StapkRuntimeControllerTest {

    @Test
    public void buildStartIntent_shouldTargetManagedRuntimeScript() {
        Intent intent = StapkRuntimeController.buildStartIntent();

        Assert.assertEquals(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, intent.getAction());
        Assert.assertEquals(TermuxService.class.getName(), intent.getComponent().getClassName());
        Assert.assertEquals(StapkRuntimeController.RUNTIME_SCRIPT_PATH, intent.getData().getPath());
        Assert.assertTrue(intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, false));
        Assert.assertEquals(StapkRuntimeController.SILLY_TAVERN_DIR,
                intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR));
    }

    @Test
    public void buildStartIntent_shouldUseStableCommandMetadata() {
        Intent intent = StapkRuntimeController.buildStartIntent();

        Assert.assertEquals("SillyTavern",
                intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL));
        Assert.assertEquals("由 stAPK 托管的酒馆运行时",
                intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION));
    }
}
```

- [ ] **Step 2: 运行单测，确认当前实现还不存在**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.stapk.termux.app.stapk.StapkRuntimeControllerTest"`

Expected: FAIL，提示 `StapkRuntimeController` 不存在或 `buildStartIntent()` 未定义。

- [ ] **Step 3: 用最小实现补上运行时控制器**

```java
package com.stapk.termux.app.stapk;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.stapk.termux.app.TermuxService;
import com.stapk.termux.shared.termux.TermuxConstants;

public final class StapkRuntimeController {

    public static final String RUNTIME_SCRIPT_PATH =
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/stapk-runtime";
    public static final String SILLY_TAVERN_DIR =
            TermuxConstants.TERMUX_HOME_DIR_PATH + "/SillyTavern";

    private StapkRuntimeController() {}

    public static Intent buildStartIntent() {
        Uri executableUri = new Uri.Builder()
                .scheme(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
                .path(RUNTIME_SCRIPT_PATH)
                .build();

        Intent intent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executableUri);
        intent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxService.class.getName());
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, true);
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR, SILLY_TAVERN_DIR);
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "SillyTavern");
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, "由 stAPK 托管的酒馆运行时");
        return intent;
    }

    public static void start(Context context) {
        Intent intent = buildStartIntent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
```

- [ ] **Step 4: 重新运行单测，确认启动 Intent 合同成立**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.stapk.termux.app.stapk.StapkRuntimeControllerTest"`

Expected: PASS，输出 `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交这一小步**

```bash
git add app/src/main/java/com/stapk/termux/app/stapk/StapkRuntimeController.java app/src/test/java/com/stapk/termux/app/stapk/StapkRuntimeControllerTest.java
git commit -m "feat: 抽取 stAPK 托管运行时控制器"
```

---

### Task 2: 引入状态快照模型，替代 Activity 里的字符串硬解析

**Files:**
- Create: `upstream/termux-app/app/src/main/java/com/stapk/termux/app/stapk/StapkStatusSnapshot.java`
- Test: `upstream/termux-app/app/src/test/java/com/stapk/termux/app/stapk/StapkStatusSnapshotTest.java`

- [ ] **Step 1: 先写失败的状态解析单测**

```java
package com.stapk.termux.app.stapk;

import org.junit.Assert;
import org.junit.Test;

public class StapkStatusSnapshotTest {

    @Test
    public void fromJson_shouldParseManagedRuntimeFields() {
        String json = "{\n" +
                "  \"status\": \"running\",\n" +
                "  \"runtime_managed\": true,\n" +
                "  \"runtime_pid\": \"3210\",\n" +
                "  \"node_pid\": \"6543\",\n" +
                "  \"port_listening\": true,\n" +
                "  \"sillytavern_version\": \"1.13.0\",\n" +
                "  \"sillytavern_commit\": \"abc1234\"\n" +
                "}";

        StapkStatusSnapshot snapshot = StapkStatusSnapshot.fromJson(json);

        Assert.assertEquals("running", snapshot.status);
        Assert.assertTrue(snapshot.runtimeManaged);
        Assert.assertEquals("3210", snapshot.runtimePid);
        Assert.assertEquals("6543", snapshot.nodePid);
        Assert.assertTrue(snapshot.portListening);
        Assert.assertEquals("1.13.0", snapshot.version);
        Assert.assertEquals("abc1234", snapshot.commit);
    }

    @Test
    public void fromJson_shouldFallbackToUnknownSnapshotForInvalidJson() {
        StapkStatusSnapshot snapshot = StapkStatusSnapshot.fromJson("not-json");

        Assert.assertEquals("unknown", snapshot.status);
        Assert.assertFalse(snapshot.runtimeManaged);
        Assert.assertFalse(snapshot.portListening);
    }
}
```

- [ ] **Step 2: 运行单测，确认当前还没有状态模型**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.stapk.termux.app.stapk.StapkStatusSnapshotTest"`

Expected: FAIL，提示 `StapkStatusSnapshot` 不存在。

- [ ] **Step 3: 实现 JSON 快照模型**

```java
package com.stapk.termux.app.stapk;

import org.json.JSONObject;

public final class StapkStatusSnapshot {

    public final String status;
    public final boolean runtimeManaged;
    public final String runtimePid;
    public final String nodePid;
    public final boolean portListening;
    public final String version;
    public final String commit;

    private StapkStatusSnapshot(String status, boolean runtimeManaged, String runtimePid,
                                String nodePid, boolean portListening, String version, String commit) {
        this.status = status;
        this.runtimeManaged = runtimeManaged;
        this.runtimePid = runtimePid;
        this.nodePid = nodePid;
        this.portListening = portListening;
        this.version = version;
        this.commit = commit;
    }

    public static StapkStatusSnapshot fromJson(String json) {
        try {
            JSONObject object = new JSONObject(json);
            return new StapkStatusSnapshot(
                    object.optString("status", "unknown"),
                    object.optBoolean("runtime_managed", false),
                    object.optString("runtime_pid", ""),
                    object.optString("node_pid", ""),
                    object.optBoolean("port_listening", false),
                    object.optString("sillytavern_version", "unknown"),
                    object.optString("sillytavern_commit", "unknown")
            );
        } catch (Exception ignored) {
            return new StapkStatusSnapshot("unknown", false, "", "", false, "unknown", "unknown");
        }
    }
}
```

- [ ] **Step 4: 重新跑解析单测**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.stapk.termux.app.stapk.StapkStatusSnapshotTest"`

Expected: PASS，输出 `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交这一小步**

```bash
git add app/src/main/java/com/stapk/termux/app/stapk/StapkStatusSnapshot.java app/src/test/java/com/stapk/termux/app/stapk/StapkStatusSnapshotTest.java
git commit -m "test: 为 stAPK 状态快照补充解析单测"
```

---

### Task 3: 新增 `stapk-runtime` 托管脚本，并让状态/停止脚本优先围绕它工作

**Files:**
- Create: `upstream/termux-app/app/src/main/assets/stapk/stapk-runtime`
- Modify: `upstream/termux-app/app/src/main/assets/stapk/stapk-status`
- Modify: `upstream/termux-app/app/src/main/assets/stapk/stapk-stop`
- Modify: `upstream/termux-app/app/src/main/assets/stapk/stapk-start`

- [ ] **Step 1: 新增长期运行的 `stapk-runtime` 包装脚本**

```bash
#!/data/data/com.stapk.termux/files/usr/bin/bash
# stAPK Runtime - 在 TermuxService 托管下长期运行 SillyTavern
set -euo pipefail

STAPK_HOME="$HOME/.stapk"
STAPK_STATE="$STAPK_HOME/state"
STAPK_LOGS="$STAPK_HOME/logs"
ST_DIR="$HOME/SillyTavern"
LOG_FILE="$STAPK_LOGS/start.log"
RUNTIME_PID_FILE="$STAPK_STATE/runtime-task.pid"
CHILD_PID_FILE="$STAPK_STATE/runtime-child.pid"

mkdir -p "$STAPK_STATE" "$STAPK_LOGS"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG_FILE"; }

cleanup() {
    local exit_code=$?
    if [ -n "${CHILD_PID:-}" ] && kill -0 "$CHILD_PID" 2>/dev/null; then
        kill "$CHILD_PID" 2>/dev/null || true
        wait "$CHILD_PID" 2>/dev/null || true
    fi
    termux-wake-unlock 2>/dev/null || log "WARNING: termux-wake-unlock failed"
    rm -f "$RUNTIME_PID_FILE" "$CHILD_PID_FILE"
    log "Managed runtime exited with code $exit_code"
    exit "$exit_code"
}

trap cleanup EXIT INT TERM

if [ ! -f "$STAPK_HOME/state/initialized" ]; then
    echo "ERROR_NOT_INITIALIZED"
    exit 1
fi

if [ ! -f "$ST_DIR/start.sh" ]; then
    echo "ERROR_START_SH_MISSING"
    exit 1
fi

tr -d '\r' < "$ST_DIR/start.sh" > "$ST_DIR/start.sh.tmp" && mv "$ST_DIR/start.sh.tmp" "$ST_DIR/start.sh" 2>/dev/null || true
termux-wake-lock 2>/dev/null || log "WARNING: termux-wake-lock failed"

cat > "$PREFIX/bin/xdg-open" <<'XEOF'
#!/data/data/com.stapk.termux/files/usr/bin/bash
exit 0
XEOF
chmod +x "$PREFIX/bin/xdg-open"

echo "$$" > "$RUNTIME_PID_FILE"
cd "$ST_DIR"
export NODE_ENV=production
log "Starting managed SillyTavern runtime"
bash start.sh >> "$LOG_FILE" 2>&1 &
CHILD_PID=$!
echo "$CHILD_PID" > "$CHILD_PID_FILE"
wait "$CHILD_PID"
```

- [ ] **Step 2: 改造 `stapk-stop`，优先停止托管运行时，再兜底清理遗留 `node`**

```bash
RUNTIME_PID_FILE="$STAPK_HOME/state/runtime-task.pid"
CHILD_PID_FILE="$STAPK_HOME/state/runtime-child.pid"

TARGET_RUNTIME_PID=""
if [ -f "$RUNTIME_PID_FILE" ]; then
    TARGET_RUNTIME_PID="$(cat "$RUNTIME_PID_FILE" 2>/dev/null || echo "")"
fi

if [ -n "$TARGET_RUNTIME_PID" ] && kill -0 "$TARGET_RUNTIME_PID" 2>/dev/null; then
    log "Stopping managed runtime PID $TARGET_RUNTIME_PID"
    kill "$TARGET_RUNTIME_PID" 2>/dev/null || true
fi

TARGET_NODE_PIDS="$(pgrep -f "node .*server.js" 2>/dev/null || echo "")"
TARGET_CHILD_PID=""
if [ -f "$CHILD_PID_FILE" ]; then
    TARGET_CHILD_PID="$(cat "$CHILD_PID_FILE" 2>/dev/null || echo "")"
fi

PIDS_TO_KILL="$TARGET_RUNTIME_PID $TARGET_CHILD_PID $TARGET_NODE_PIDS $TARGET_PID"
PIDS_TO_KILL=$(echo "$PIDS_TO_KILL" | tr ' ' '\n' | sort -u | grep -v '^$' || echo "")

rm -f "$RUNTIME_PID_FILE" "$CHILD_PID_FILE" "$LOCK_FILE"
```

- [ ] **Step 3: 改造 `stapk-status`，输出托管运行时字段，并以它为第一信号源**

```bash
RUNTIME_PID_FILE="$STAPK_STATE/runtime-task.pid"
CHILD_PID_FILE="$STAPK_STATE/runtime-child.pid"

RUNTIME_PID=""
RUNTIME_MANAGED="false"
if [ -f "$RUNTIME_PID_FILE" ]; then
    RUNTIME_PID="$(cat "$RUNTIME_PID_FILE" 2>/dev/null || echo "")"
    if [ -n "$RUNTIME_PID" ] && kill -0 "$RUNTIME_PID" 2>/dev/null; then
        RUNTIME_MANAGED="true"
    else
        rm -f "$RUNTIME_PID_FILE"
        RUNTIME_PID=""
    fi
fi

CHILD_PID=""
if [ -f "$CHILD_PID_FILE" ]; then
    CHILD_PID="$(cat "$CHILD_PID_FILE" 2>/dev/null || echo "")"
fi

if [ "$INITIALIZED" = "false" ]; then
    STATUS="not_initialized"
elif [ "$RUNTIME_MANAGED" = "true" ] && [ "$PORT_LISTENING" = "true" ]; then
    STATUS="running"
elif [ "$RUNTIME_MANAGED" = "true" ]; then
    STATUS="starting"
elif [ "$NODE_RUNNING" = "true" ] && [ "$PORT_LISTENING" = "true" ]; then
    STATUS="running"
elif [ "$NODE_RUNNING" = "true" ]; then
    STATUS="starting"
else
    STATUS="stopped"
fi

cat <<EOF
{
  "status": "$STATUS",
  "initialized": $INITIALIZED,
  "runtime_managed": $RUNTIME_MANAGED,
  "runtime_pid": "${RUNTIME_PID:-}",
  "runtime_child_pid": "${CHILD_PID:-}",
  "node_running": $NODE_RUNNING,
  "node_pid": "${NODE_PID:-}",
  "port": $PORT,
  "port_listening": $PORT_LISTENING,
  "sillytavern_version": "$ST_VERSION",
  "sillytavern_commit": "$ST_COMMIT",
  "node_version": "$NODE_VER",
  "npm_version": "$NPM_VER",
  "git_version": "$GIT_VER"
}
EOF
```

- [ ] **Step 4: 把 `stapk-start` 降级成兼容入口，而不是 GUI 的主启动路径**

```bash
echo "DEPRECATED_DIRECT_START"
echo "请通过 stAPK 控制面板启动托管运行时"
exec "$PREFIX/bin/stapk-runtime"
```

- [ ] **Step 5: 手工验证脚本合同并提交**

Run:

```bash
cd upstream/termux-app
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/termux-app_debug_universal.apk
adb shell run-as com.stapk.termux cat files/home/.stapk/state/runtime-task.pid
adb shell run-as com.stapk.termux cat files/home/.stapk/state/runtime-child.pid
adb shell run-as com.stapk.termux cat files/home/.stapk/logs/start.log
```

Expected:
- `runtime-task.pid` 和 `runtime-child.pid` 都存在且为活跃 PID。
- `start.log` 里出现 `Starting managed SillyTavern runtime`。

```bash
git add app/src/main/assets/stapk/stapk-runtime app/src/main/assets/stapk/stapk-status app/src/main/assets/stapk/stapk-stop app/src/main/assets/stapk/stapk-start
git commit -m "feat: 改为 TermuxService 托管的酒馆运行时"
```

---

### Task 4: 重接控制面板启动链路，移除旧前台通知服务

**Files:**
- Modify: `upstream/termux-app/app/src/main/java/com/stapk/termux/app/StapkControlActivity.java`
- Modify: `upstream/termux-app/app/src/main/AndroidManifest.xml`
- Delete: `upstream/termux-app/app/src/main/java/com/stapk/termux/app/stapk/StapkForegroundService.java`

- [ ] **Step 1: 在 `StapkControlActivity` 中接入 `StapkRuntimeController` 和 `StapkStatusSnapshot`**

```java
import com.stapk.termux.app.stapk.StapkRuntimeController;
import com.stapk.termux.app.stapk.StapkStatusSnapshot;
```

```java
private void processStatusResult(String json) {
    StapkStatusSnapshot snapshot = StapkStatusSnapshot.fromJson(json);

    switch (snapshot.status) {
        case "running":
            mIsRunning = true;
            mIsStarting = false;
            mNeedsInit = false;
            setStatusUI(getString(R.string.stapk_status_running));
            setVersionUI(snapshot.version, snapshot.commit);
            setIndicatorColor(Color.parseColor("#FF4CAF50"));
            mBtnStart.setVisibility(View.GONE);
            mBtnStop.setVisibility(View.VISIBLE);
            mBtnOpen.setEnabled(true);
            break;
        case "starting":
            mIsRunning = false;
            mIsStarting = true;
            setStatusUI(getString(R.string.stapk_status_starting));
            setIndicatorColor(Color.parseColor("#FFFF9800"));
            mBtnStart.setVisibility(View.GONE);
            mBtnStop.setVisibility(View.VISIBLE);
            mBtnOpen.setEnabled(false);
            break;
        case "not_initialized":
            mNeedsInit = true;
            setStatusUI(getString(R.string.stapk_msg_init_needed));
            setIndicatorColor(Color.parseColor("#FFFF5252"));
            mBtnStart.setVisibility(View.VISIBLE);
            mBtnStart.setText(R.string.stapk_btn_init);
            mBtnStop.setVisibility(View.GONE);
            mBtnOpen.setEnabled(false);
            break;
        default:
            mIsRunning = false;
            mIsStarting = false;
            mNeedsInit = false;
            setStatusUI(getString(R.string.stapk_status_stopped));
            setVersionUI(snapshot.version, snapshot.commit);
            setIndicatorColor(Color.parseColor("#FF555555"));
            mBtnStart.setVisibility(View.VISIBLE);
            mBtnStart.setText(R.string.stapk_btn_start);
            mBtnStop.setVisibility(View.GONE);
            mBtnOpen.setEnabled(false);
            break;
    }
}
```

- [ ] **Step 2: 把启动按钮从旧脚本回调切到托管运行时**

```java
private void onStartClicked() {
    if (mIsRunning) {
        Toast.makeText(this, R.string.stapk_msg_already_running, Toast.LENGTH_SHORT).show();
        return;
    }

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

    try {
        StapkRuntimeController.start(this);
        Toast.makeText(this, R.string.stapk_msg_start_success, Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
        Logger.logError(LOG_TAG, "Failed to start managed runtime: " + e.getMessage());
        Toast.makeText(this, R.string.stapk_msg_start_failed, Toast.LENGTH_LONG).show();
    }

    runStapkStatus();
}
```

- [ ] **Step 3: 删除 `showForegroundNotification()` / `hideForegroundNotification()` 及其所有调用**

```java
// 删除以下内容：
// private static final String STAPK_FOREGROUND_CHANNEL_ID = "stapk_foreground";
// private static final int STAPK_FOREGROUND_NOTIFICATION_ID = 1400;
// private boolean mNotificationShown = false;
// private NotificationManager mNotificationManager;
// private void showForegroundNotification() { ... }
// private void hideForegroundNotification() { ... }
```

- [ ] **Step 4: 移除旧服务文件和清单注册**

```xml
<!-- 从 AndroidManifest.xml 删除这一段 -->
<service
    android:name=".app.stapk.StapkForegroundService"
    android:exported="false" />
```

```bash
git rm app/src/main/java/com/stapk/termux/app/stapk/StapkForegroundService.java
```

- [ ] **Step 5: 跑单测和 Debug 构建，再提交**

Run:

```bash
cd upstream/termux-app
.\gradlew.bat :app:testDebugUnitTest --tests "com.stapk.termux.app.stapk.StapkRuntimeControllerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.stapk.termux.app.stapk.StapkStatusSnapshotTest"
.\gradlew.bat :app:assembleDebug
```

Expected: 两组单测和 `assembleDebug` 都成功。

```bash
git add app/src/main/java/com/stapk/termux/app/StapkControlActivity.java app/src/main/AndroidManifest.xml
git commit -m "refactor: 控制面板切换到托管运行时启动链路"
```

---

### Task 5: 做真机回归并补文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README 的后台运行说明**

```markdown
- **图形化控制面板** — 启动、停止、打开 Web UI、查看日志，全是按钮操作
- **托管后台运行** — SillyTavern 由 Termux 前台服务托管，不依赖脱管 `nohup` 进程
```

```markdown
2. Java 层通过 `TermuxService` 启动托管运行时脚本（`stapk-runtime`）
3. `stapk-runtime` 在 Termux 环境中运行 `bash start.sh`
4. `TermuxService` 保持前台通知与任务生命周期，UI 定时轮询状态并更新显示
```

- [ ] **Step 2: 做浏览器切后台回归**

Run:

```bash
adb logcat -c
adb logcat -s TermuxService StapkControl > output/runtime-logcat.txt
```

Manual verification:
- 在 stAPK 中点击“启动”。
- 打开浏览器访问 `http://127.0.0.1:8000`。
- 切到其他 App，等待 3 到 5 分钟，再切回浏览器继续操作酒馆页面。
- 再次切回 stAPK，确认状态仍为“运行中”。

Expected:
- 页面持续可响应，不需要靠切回 stAPK 或挂小窗恢复。
- `runtime-logcat.txt` 中可以看到 `TermuxService` 持续存在，没有旧 `StapkForegroundService` 的日志。

- [ ] **Step 3: 做停止回归**

Manual verification:
- 在 stAPK 中点击“停止”。
- 确认浏览器页面刷新后不可访问。
- 运行下面的命令确认 PID 文件和进程都清干净。

Run:

```bash
adb shell run-as com.stapk.termux ls files/home/.stapk/state
adb shell run-as com.stapk.termux sh -c "pgrep -f 'node .*server.js' || true"
```

Expected:
- `runtime-task.pid` / `runtime-child.pid` 不再存在。
- `pgrep` 无输出。

- [ ] **Step 4: 检查兼容路径没有被破坏**

Manual verification:
- 重新启动应用并执行一次初始化后的“启动 -> 停止 -> 再启动”。
- 在高级入口打开 Termux，手动运行 `stapk-status`，确认 JSON 里含有 `runtime_managed` 字段。

Expected:
- GUI 和 CLI 状态输出一致。
- 旧 `start.lock` 遗留不会导致“已在运行”的假阳性。

- [ ] **Step 5: 提交 README 和最终回归结果**

```bash
git add README.md
git commit -m "docs: 更新托管后台运行说明"
```

---

## 自检

- **规格覆盖：** 计划覆盖了运行托管方式、停止链路、状态链路、旧通知服务移除、真机后台切换回归和 README 更新。
- **占位词扫描：** 本计划没有保留 `TODO`、`TBD`、`implement later`、`类似 Task N` 之类的占位描述。
- **类型一致性：** 计划统一使用 `StapkRuntimeController`、`StapkStatusSnapshot`、`runtime-task.pid`、`runtime-child.pid` 这组命名，后续任务不再切换叫法。
