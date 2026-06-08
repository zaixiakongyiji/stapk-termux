package com.stapk.termux.app.stapk;

import com.stapk.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class StapkBootstrapShebangFixer {

    private static final String LEGACY_SHEBANG_PREFIX = "#!/data/data/com.termux/files/usr/bin/";
    private static final String CURRENT_SHEBANG_PREFIX = "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/";
    private static final int MAX_TEXT_SCRIPT_SIZE_BYTES = 256 * 1024;

    private StapkBootstrapShebangFixer() {
    }

    public static int repairPrefixScripts(File prefixDir) {
        if (prefixDir == null || !prefixDir.isDirectory()) {
            return 0;
        }

        int repairedCount = 0;
        repairedCount += repairDirectory(new File(prefixDir, "bin"));
        repairedCount += repairDirectory(new File(prefixDir, "lib/node_modules/npm/bin"));
        repairedCount += repairDirectory(new File(prefixDir, "lib/node_modules/corepack/shims"));
        return repairedCount;
    }

    private static int repairDirectory(File directory) {
        if (!directory.isDirectory()) {
            return 0;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }

        int repairedCount = 0;
        for (File file : files) {
            if (repairFile(file)) {
                repairedCount++;
            }
        }

        return repairedCount;
    }

    static boolean repairFile(File file) {
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > MAX_TEXT_SCRIPT_SIZE_BYTES) {
            return false;
        }

        String content;
        try {
            content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }

        if (!content.startsWith(LEGACY_SHEBANG_PREFIX)) {
            return false;
        }

        int lineEndIndex = content.indexOf('\n');
        String firstLine = lineEndIndex >= 0 ? content.substring(0, lineEndIndex) : content;
        String remaining = lineEndIndex >= 0 ? content.substring(lineEndIndex) : "";
        String repairedFirstLine = CURRENT_SHEBANG_PREFIX + firstLine.substring(LEGACY_SHEBANG_PREFIX.length());

        try {
            Files.write(file.toPath(), (repairedFirstLine + remaining).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }

        file.setExecutable(true, false);
        return true;
    }
}
