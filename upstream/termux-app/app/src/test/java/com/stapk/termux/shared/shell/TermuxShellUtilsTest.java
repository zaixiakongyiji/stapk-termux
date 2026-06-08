package com.stapk.termux.shared.shell;

import com.stapk.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TermuxShellUtilsTest {

    @Test
    public void addTermuxCommandEnvironment_shouldIncludeLibraryPathForBackgroundTasks() {
        List<String> entries = new ArrayList<>();

        TermuxShellUtils.addTermuxCommandEnvironment(entries, false, TermuxConstants.TERMUX_HOME_DIR_PATH);

        Assert.assertTrue(entries.contains("LD_LIBRARY_PATH=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib"));
    }
}
