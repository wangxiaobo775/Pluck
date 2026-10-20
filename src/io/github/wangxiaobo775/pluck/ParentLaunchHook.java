package io.github.wangxiaobo775.pluck;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * ActivityTaskManagerService.startActivityAsUser from a clone: send the launch
 * to the main user when the clone cannot handle it itself.
 *
 *  - explicit target not installed in this clone       -> main user
 *  - share / dial / call / http(s) VIEW                -> main user
 *    (clones have no browser, dialer or messages app; same set as Motorola's
 *    CLONE_TO_PARENT filters)
 *  - any other implicit intent                         -> main user only if
 *    nothing in the clone resolves it
 *
 * The last rule keeps an app's own deep links (myapp://...) inside the
 * clone. Before, every implicit VIEW went to the main user, so a clone's own
 * link opened the main user's copy of the same app.
 *
 * The forwarded intent gets the clone as its content user hint (what
 * IntentForwarderActivity does), so content:// URIs in it still point at the
 * clone's files and the main-user app can read them through the URI grant.
 *
 * Not covered: launches from PendingIntents (notification taps) go through
 * ActivityStartController.startActivityInPackage, not this method.
 */
final class ParentLaunchHook extends SafeHook {
    private static final Set<String> MAIN_USER_ACTIONS = Set.of(
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
            Intent.ACTION_SENDTO,
            Intent.ACTION_DIAL,
            Intent.ACTION_CALL);

    ParentLaunchHook() {
        super("parent-launch");
    }

    @Override
    void before(MethodHookParam param) throws ReflectiveOperationException {
        // startActivityAsUser(caller, pkg, feature, Intent, resolvedType, ..., Bundle options, int userId[, boolean])
        Class<?>[] types = ((Method) param.method).getParameterTypes();
        int intentAt = -1;
        int userAt = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Intent.class) {
                intentAt = i;
            } else if (types[i] == Bundle.class && i + 1 < types.length && types[i + 1] == int.class) {
                userAt = i + 1;
            }
        }
        if (intentAt < 0 || userAt < 0) {
            return;
        }
        Object[] args = param.args;
        Intent intent = (Intent) args[intentAt];
        int userId = (Integer) args[userAt];
        if (intent == null || !Clones.isClone(userId)) {
            return;
        }
        String resolvedType = types.length > intentAt + 1 && types[intentAt + 1] == String.class
                ? (String) args[intentAt + 1] : null;
        if (!belongsToMainUser(intent, resolvedType, userId)) {
            return;
        }
        Intent moved = new Intent(intent); // the caller may hold on to the original
        XposedHelpers.callMethod(moved, "prepareToLeaveUser", userId);
        args[intentAt] = moved;
        args[userAt] = Clones.PARENT;
        Log.count("parent launch", describe(moved));
    }

    private static boolean belongsToMainUser(Intent intent, String resolvedType, int userId)
            throws ReflectiveOperationException {
        ComponentName component = intent.getComponent();
        String pkg = component != null ? component.getPackageName() : intent.getPackage();
        if (pkg != null) {
            return !Pm.installed(pkg, userId);
        }
        String action = intent.getAction();
        if (action != null && MAIN_USER_ACTIONS.contains(action)) {
            return true;
        }
        if (Intent.ACTION_VIEW.equals(action) && isWeb(intent.getData())) {
            return true;
        }
        return !Pm.resolves(intent, resolvedType, userId);
    }

    private static boolean isWeb(Uri data) {
        String scheme = data == null ? null : data.getScheme();
        return "https".equals(scheme) || "http".equals(scheme);
    }

    /** Enough to tell launches apart in the log, without logging full URLs. */
    private static String describe(Intent intent) {
        ComponentName component = intent.getComponent();
        if (component != null) {
            return component.getPackageName();
        }
        Uri data = intent.getData();
        return intent.getAction() + (data == null ? "" : " " + data.getScheme() + ":");
    }
}
