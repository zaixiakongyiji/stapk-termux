package com.termux.app.stapk;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 保存单次脚本执行的调试记录。
 * 在 executeScriptSync 中构建，用于 UI 展示和 debug.log 落盘。
 */
public class DebugRecord {

    // 不可执行脚本的特定退出码
    public static final int EXIT_CODE_NOT_EXECUTABLE = -127;

    // Bootstrap 状态枚举
    public enum BootstrapStatus {
        UNKNOWN("unknown"),
        READY("ready"),
        NOT_READY("not_ready"),
        ERROR("error");

        private final String value;

        BootstrapStatus(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /** 脚本名称（如 "stapk-start"） */
    public final String scriptName;

    /** 完整命令行（含参数） */
    public final String commandLine;

    /** 执行开始时间戳（毫秒） */
    public final long startTimeMs;

    /** 执行结束时间戳（毫秒） */
    public final long endTimeMs;

    /** 耗时（毫秒） */
    public final long durationMs;

    /** 退出码（-127 表示不可执行） */
    public final int exitCode;

    /** stdout 末尾 N 行（stderr 已合并） */
    public final String stdoutTail;

    /** stderr 说明（已合并到 stdout） */
    public final String stderrNote;

    /** 对应日志文件路径 */
    public final String logFilePath;

    /** 脚本是否可执行 */
    public final boolean scriptExecutable;

    /** Bootstrap 状态 */
    public final BootstrapStatus bootstrapStatus;

    /** debug.log 写入是否成功 */
    public final boolean logWriteSuccess;

    public DebugRecord(String scriptName, String commandLine, long startTimeMs, long endTimeMs,
                       int exitCode, String stdoutTail, String logFilePath,
                       boolean scriptExecutable, BootstrapStatus bootstrapStatus,
                       boolean logWriteSuccess) {
        this.scriptName = scriptName;
        this.commandLine = commandLine;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.durationMs = endTimeMs - startTimeMs;
        this.exitCode = exitCode;
        this.stdoutTail = stdoutTail;
        this.stderrNote = "stderr: 已合并到 stdout";
        this.logFilePath = logFilePath;
        this.scriptExecutable = scriptExecutable;
        this.bootstrapStatus = bootstrapStatus;
        this.logWriteSuccess = logWriteSuccess;
    }

    /**
     * 生成用于 UI 展示的文本。
     */
    public String toDisplayString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        StringBuilder sb = new StringBuilder();

        sb.append("脚本名: ").append(scriptName).append("\n");
        sb.append("命令行: ").append(commandLine).append("\n");
        sb.append("开始时间: ").append(sdf.format(new Date(startTimeMs))).append("\n");
        sb.append("结束时间: ").append(sdf.format(new Date(endTimeMs))).append("\n");
        sb.append("耗时: ").append(durationMs).append(" ms\n");
        sb.append("退出码: ").append(exitCode);
        if (exitCode == EXIT_CODE_NOT_EXECUTABLE) {
            sb.append(" (不可执行)");
        }
        sb.append("\n");
        sb.append("脚本可执行: ").append(scriptExecutable ? "是" : "否").append("\n");
        sb.append("Bootstrap 状态: ").append(bootstrapStatus.getValue()).append("\n");
        sb.append("日志文件: ").append(logFilePath).append("\n");
        sb.append("落盘状态: ").append(logWriteSuccess ? "成功" : "失败").append("\n");
        sb.append("\n");
        sb.append("--- stdout (末尾) ---\n");
        sb.append(stdoutTail != null ? stdoutTail : "(空)");
        sb.append("\n");
        sb.append("--- stderr ---\n");
        sb.append(stderrNote);

        return sb.toString();
    }

    /**
     * 生成用于 debug.log 落盘的文本。
     */
    public String toLogString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        StringBuilder sb = new StringBuilder();

        sb.append("========================================\n");
        sb.append("执行记录 - ").append(sdf.format(new Date(startTimeMs))).append("\n");
        sb.append("========================================\n");
        sb.append("脚本名: ").append(scriptName).append("\n");
        sb.append("命令行: ").append(commandLine).append("\n");
        sb.append("开始时间: ").append(sdf.format(new Date(startTimeMs))).append("\n");
        sb.append("结束时间: ").append(sdf.format(new Date(endTimeMs))).append("\n");
        sb.append("耗时: ").append(durationMs).append(" ms\n");
        sb.append("退出码: ").append(exitCode);
        if (exitCode == EXIT_CODE_NOT_EXECUTABLE) {
            sb.append(" (不可执行)");
        }
        sb.append("\n");
        sb.append("脚本可执行: ").append(scriptExecutable ? "是" : "否").append("\n");
        sb.append("Bootstrap 状态: ").append(bootstrapStatus.getValue()).append("\n");
        sb.append("日志文件: ").append(logFilePath).append("\n");
        sb.append("落盘状态: ").append(logWriteSuccess ? "成功" : "失败").append("\n");
        sb.append("\n");
        sb.append("--- stdout (末尾) ---\n");
        sb.append(stdoutTail != null ? stdoutTail : "(空)");
        sb.append("\n");
        sb.append("--- stderr ---\n");
        sb.append(stderrNote);
        sb.append("\n\n");

        return sb.toString();
    }
}
