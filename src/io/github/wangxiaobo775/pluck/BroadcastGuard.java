package io.github.wangxiaobo775.pluck;

import android.content.Intent;
import android.os.DeadObjectException;

/**
 * ActivityThread$ApplicationThread.scheduleRegisteredReceiver in
 * system_server: drop a delivery whose receiver binder is already dead.
 *
 * SameProcessApplicationThread posts this onto android.fg. On this firmware
 * the DeadObjectException from performReceive is not caught there, and
 * KillApplicationHandler takes system_server down. Seen on the 2026-10-07
 * package-install crashes and during Play Store commits.
 */
final class BroadcastGuard extends SafeHook {
    private static final int CAUSE_DEPTH = 4;

    BroadcastGuard() {
        super("broadcast-guard");
    }

    @Override
    void after(MethodHookParam param) {
        Throwable thrown = param.getThrowable();
        if (thrown == null || !causedByDeadObject(thrown)) {
            return;
        }
        param.setResult(null);
        Object intent = param.args.length > 1 ? param.args[1] : null;
        Log.count("drop dead broadcast",
                intent instanceof Intent ? "action=" + ((Intent) intent).getAction() : null);
    }

    private static boolean causedByDeadObject(Throwable t) {
        for (int depth = 0; t != null && depth < CAUSE_DEPTH; depth++) {
            if (t instanceof DeadObjectException) {
                return true;
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return false;
    }
}
