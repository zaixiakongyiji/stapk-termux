package com.stapk.termux.app.stapk;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StapkScriptAssetsTest {

    private static final Path STAPK_ASSETS_DIR = Paths.get("src", "main", "assets", "stapk");

    @Test
    public void stapkScripts_shouldUseLfLineEndings() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> stream = Files.list(STAPK_ASSETS_DIR)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .collect(Collectors.toList())) {
                if (containsCarriageReturn(path)) {
                    offenders.add(path.getFileName().toString());
                }
            }
        }

        Assert.assertTrue("脚本包含 CRLF 换行: " + offenders, offenders.isEmpty());
    }

    @Test
    public void stapkRuntime_shouldExportTermuxRuntimePaths() throws IOException {
        Path runtimeScript = STAPK_ASSETS_DIR.resolve("stapk-runtime");
        String content = new String(Files.readAllBytes(runtimeScript), StandardCharsets.UTF_8);

        Assert.assertTrue("stapk-runtime 缺少 PATH 导出",
                content.contains("export PATH=\"$PREFIX/bin:${PATH:-}\""));
        Assert.assertTrue("stapk-runtime 缺少 LD_LIBRARY_PATH 导出",
                content.contains("export LD_LIBRARY_PATH=\"$PREFIX/lib:${LD_LIBRARY_PATH:-}\""));
    }

    private static boolean containsCarriageReturn(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        for (byte value : bytes) {
            if (value == '\r') {
                return true;
            }
        }
        return false;
    }
}
