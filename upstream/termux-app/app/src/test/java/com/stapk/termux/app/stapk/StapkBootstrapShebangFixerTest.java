package com.stapk.termux.app.stapk;

import com.stapk.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class StapkBootstrapShebangFixerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void repairPrefixScripts_shouldRewriteLegacyTermuxShebang() throws Exception {
        File prefixDir = temporaryFolder.newFolder("usr");
        File binDir = new File(prefixDir, "bin");
        Assert.assertTrue(binDir.mkdirs());

        File npmScript = new File(binDir, "npm");
        Files.write(npmScript.toPath(), (
                "#!/data/data/com.termux/files/usr/bin/env node\n" +
                        "require('../lib/cli.js')(process)\n"
        ).getBytes(StandardCharsets.UTF_8));

        int repairedCount = StapkBootstrapShebangFixer.repairPrefixScripts(prefixDir);
        String content = new String(Files.readAllBytes(npmScript.toPath()), StandardCharsets.UTF_8);

        Assert.assertEquals(1, repairedCount);
        Assert.assertTrue(content.startsWith("#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/env node\n"));
    }

    @Test
    public void repairPrefixScripts_shouldIgnoreAlreadyFixedScripts() throws Exception {
        File prefixDir = temporaryFolder.newFolder("usr");
        File binDir = new File(prefixDir, "bin");
        Assert.assertTrue(binDir.mkdirs());

        File npmScript = new File(binDir, "npm");
        String original = "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/env node\nrequire('../lib/cli.js')(process)\n";
        Files.write(npmScript.toPath(), original.getBytes(StandardCharsets.UTF_8));

        int repairedCount = StapkBootstrapShebangFixer.repairPrefixScripts(prefixDir);
        String content = new String(Files.readAllBytes(npmScript.toPath()), StandardCharsets.UTF_8);

        Assert.assertEquals(0, repairedCount);
        Assert.assertEquals(original, content);
    }
}
