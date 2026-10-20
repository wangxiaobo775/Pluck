package io.github.wangxiaobo775.pluck;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point (assets/xposed_init). Scope: "system", user 0 only; optionally
 * One UI Home for clone badges (CloneBadge).
 *
 * Two processes arrive here under the "android"/"system" package:
 *  - the real system_server, which has UserManagerService;
 *  - an empty "system" app process that ActivityManager keeps restarting.
 *    It has only framework-res and crashes installing BsohProvider. There we
 *    only skip provider installation.
 *
 * Every hook is installed on its own. One missing class or method (say after
 * an OTA) is logged as MISSING and the rest still go in. Each line says how
 * many methods were really hooked, so "x0" can no longer pass for "installed".
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String UMS = "com.android.server.pm.UserManagerService";
    private static final String IMMS = "com.android.server.inputmethod.InputMethodManagerService";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // One UI Home, only when it is in the module's scope: clone badges.
        // It cannot read /data/system/sysguard; untick it in the scope to turn this off.
        if (CloneBadge.LAUNCHER.equals(lpparam.packageName)) {
            CloneBadge.install(lpparam.classLoader);
            return;
        }
        if (!"android".equals(lpparam.packageName) && !"system".equals(lpparam.packageName)) {
            return;
        }
        if (Flags.disabled()) {
            Log.i("disabled by " + Flags.DISABLE);
            return;
        }
        if (isRealSystemServer(lpparam.classLoader)) {
            installSystemServer(lpparam.classLoader);
        } else {
            installEmptySystemProcess(lpparam.classLoader);
        }
    }

    private static boolean isRealSystemServer(ClassLoader cl) {
        try {
            Class.forName(UMS, false, cl);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void installSystemServer(ClassLoader cl) {
        Log.i("real system_server");
        Clones.init(cl);

        // Creating more clone profiles than the stock limit allows.
        SafeHook allow = new SafeHook.Constant("user-limits", true);
        hook(cl, UMS, "isCreationOverrideEnabled", allow);
        hook(cl, UMS, "canAddMoreProfilesToUser", allow);

        hook(cl, UMS, "isCrossProfileIntentFilterAccessible", new CloneShare.Access());
        hook(cl, "com.android.server.wm.ActivityTaskManagerService", "startActivityAsUser", new ParentLaunchHook());

        hook(cl, IMMS, "resolveImeUserIdLocked", new ImeHooks.ParentUser());
        hook(cl, "android.provider.Settings$Secure", "getStringForUser", new ImeHooks.Settings());
        hook(cl, "com.android.server.inputmethod.InputMethodInfoUtils", "getMostApplicableDefaultIME",
                new ImeHooks.DefaultChoice());
        hook(cl, "android.app.ContextImpl", "bindServiceAsUser", new ImeHooks.BindUser());
        hook(cl, IMMS, "initializeImeLocked", new ImeHooks.InitOnce());
        hook(cl, IMMS, "requestClientSessionLocked", new ImeHooks.SessionGuard());

        hook(cl, "android.app.ActivityThread$ApplicationThread", "scheduleRegisteredReceiver", new BroadcastGuard());
        hook(cl, "com.android.server.connectivity.Vpn", "createUserAndRestrictedProfilesRanges", new VpnCloneRanges());

        if (Flags.off("alipay-filters")) {
            Log.i("alipay-filters off");
        } else {
            CloneShare.AlipayForwarding.start();
        }
        hook(cl, UMS, "createUserInternalUnchecked", new CloneShare.NewClone());
    }

    private static void installEmptySystemProcess(ClassLoader cl) {
        Log.i("empty system process, skip provider install");
        SafeHook skip = new SafeHook.Constant("empty-system", null);
        hook(cl, "android.app.ActivityThread", "installContentProviders", skip);
        hook(cl, "android.app.ActivityThread", "installProvider", skip);
    }

    static void hook(ClassLoader cl, String className, String method, SafeHook callback) {
        String where = className.substring(className.lastIndexOf('.') + 1) + "." + method;
        if (Flags.off(callback.name)) {
            Log.i(callback.name + " off: " + where);
            return;
        }
        try {
            int n = XposedBridge.hookAllMethods(XposedHelpers.findClass(className, cl), method, callback).size();
            Log.i(callback.name + (n > 0 ? " hooked " : " MISSING ") + where + " x" + n);
        } catch (Throwable t) {
            Log.e(callback.name + " " + where, t);
        }
    }
}
