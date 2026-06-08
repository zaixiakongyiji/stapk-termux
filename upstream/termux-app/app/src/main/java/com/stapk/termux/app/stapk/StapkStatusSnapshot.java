package com.stapk.termux.app.stapk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            return new StapkStatusSnapshot(
                    readString(json, "status", "unknown"),
                    readBoolean(json, "runtime_managed", false),
                    readString(json, "runtime_pid", ""),
                    readString(json, "node_pid", ""),
                    readBoolean(json, "port_listening", false),
                    readString(json, "sillytavern_version", "unknown"),
                    readString(json, "sillytavern_commit", "unknown")
            );
        } catch (Exception ignored) {
            return new StapkStatusSnapshot("unknown", false, "", "", false, "unknown", "unknown");
        }
    }

    private static String readString(String json, String key, String defaultValue) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return defaultValue;
    }

    private static boolean readBoolean(String json, String key, boolean defaultValue) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return defaultValue;
    }
}
