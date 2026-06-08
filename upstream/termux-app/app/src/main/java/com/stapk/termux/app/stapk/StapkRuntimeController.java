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

    private StapkRuntimeController() {
    }

    public static Intent buildStartIntent() {
        Uri executableUri = Uri.parse(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE + ":" + RUNTIME_SCRIPT_PATH
        );

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
