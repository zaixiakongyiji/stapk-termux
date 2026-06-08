package com.stapk.termux.app.stapk;

import android.content.Intent;

import com.stapk.termux.app.TermuxService;
import com.stapk.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
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
