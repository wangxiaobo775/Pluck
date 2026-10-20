package io.github.wangxiaobo775.pluck;

import android.app.Application;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.os.UserHandle;
import android.view.inputmethod.InputMethodInfo;

import de.robv.android.xposed.XposedHelpers;

import java.util.List;

/**
 * Clone apps type with the main user's keyboard (its dictionary and account),
 * instead of spawning a separate keyboard process per clone.
 *
 * History that shaped this:
 *  - 2026-10-07 18:10 rewriting InputMethodBindingController.mUserId made
 *    requestClientSessionLocked NPE and killed system_server. Only the
 *    UserHandle handed to bindServiceAsUser is changed now; the controller
 *    keeps its own clone user id. The old hook is in retired/.
 */
final class ImeHooks {
    private static final String DEFAULT_INPUT_METHOD = "default_input_method";
    /** Samsung Keyboard's IME id contains this. */
    private static final String SAMSUNG_KEYBOARD = "honeyboard";

    private ImeHooks() {}

    /** InputMethodManagerService.resolveImeUserIdLocked: a clone resolves to the main user. */
    static final class ParentUser extends SafeHook {
        ParentUser() {
            super("ime-parent");
        }

        @Override
        void after(MethodHookParam param) {
            Object result = param.getResult();
            if (result instanceof Integer && Clones.isClone((Integer) result)) {
                param.setResult(Clones.PARENT);
            }
        }
    }

    /**
     * Settings.Secure.getStringForUser(resolver, name, user): a clone reading
     * default_input_method gets the main user's value. This runs on every
     * settings read in system_server, so the cheap checks go first.
     */
    static final class Settings extends SafeHook {
        Settings() {
            super("ime-settings");
        }

        @Override
        void before(MethodHookParam param) {
            Object[] args = param.args;
            int userAt = args.length - 1;
            if (userAt < 1 || !(args[userAt] instanceof Integer) || !DEFAULT_INPUT_METHOD.equals(args[1])) {
                return;
            }
            if (Clones.isClone((Integer) args[userAt])) {
                args[userAt] = Clones.PARENT;
            }
        }
    }

    /**
     * InputMethodInfoUtils.getMostApplicableDefaultIME(list): when the system
     * re-picks a default keyboard and chooses Samsung Keyboard, prefer:
     *  1. /data/system/sysguard/preferred-ime if configured;
     *  2. the main user's current default_input_method;
     *  3. any user-installed (non-system) input method in the candidate list.
     */
    static final class DefaultChoice extends SafeHook {
        DefaultChoice() {
            super("ime-default");
        }

        @Override
        void after(MethodHookParam param) {
            InputMethodInfo picked = (InputMethodInfo) param.getResult();
            if (picked == null || !picked.getId().contains(SAMSUNG_KEYBOARD)) {
                return;
            }
            List<?> list = (List<?>) param.args[0];
            String preferred = Flags.preferredIme();
            if (preferred == null) {
                preferred = mainUserDefaultIme();
            }
            if (preferred != null && !preferred.contains(SAMSUNG_KEYBOARD)) {
                for (Object o : list) {
                    InputMethodInfo imi = (InputMethodInfo) o;
                    if (imi.getId().contains(preferred)) {
                        param.setResult(imi);
                        return;
                    }
                }
            }
            for (Object o : list) {
                InputMethodInfo imi = (InputMethodInfo) o;
                if (imi.getServiceInfo() != null
                        && imi.getServiceInfo().applicationInfo != null
                        && (imi.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    param.setResult(imi);
                    return;
                }
            }
        }

        private static String mainUserDefaultIme() {
            try {
                Application app = (Application) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (app != null) {
                    return (String) XposedHelpers.callStaticMethod(
                            android.provider.Settings.Secure.class,
                            "getStringForUser",
                            app.getContentResolver(),
                            DEFAULT_INPUT_METHOD,
                            Clones.PARENT);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /**
     * ContextImpl.bindServiceAsUser from InputMethodBindingController for a
     * clone: bind the main user's keyboard service instead. This is called for
     * every service binding in system_server; the class-name test filters
     * almost all of them before any user lookup.
     */
    static final class BindUser extends SafeHook {
        private static final UserHandle MAIN_USER = UserHandle.getUserHandleForUid(0);

        BindUser() {
            super("ime-bind");
        }

        @Override
        void before(MethodHookParam param) {
            Object[] args = param.args;
            ServiceConnection connection = null;
            int userAt = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof ServiceConnection) {
                    connection = (ServiceConnection) args[i];
                } else if (args[i] instanceof UserHandle) {
                    userAt = i;
                }
            }
            if (connection == null || userAt < 0
                    || !connection.getClass().getName().contains("InputMethodBindingController")) {
                return;
            }
            int userId = (Integer) XposedHelpers.callMethod(args[userAt], "getIdentifier");
            if (Clones.isClone(userId)) {
                args[userAt] = MAIN_USER;
                Log.count("clone ime bound to user 0", "from user " + userId);
            }
        }
    }

    /**
     * InputMethodManagerService.initializeImeLocked(invoker, token, controller):
     * when a clone's controller connects to the keyboard the main user already
     * initialized, skip. A second initializeInternal would replace the window
     * token the main user attached.
     */
    static final class InitOnce extends SafeHook {
        InitOnce() {
            super("ime-init-once");
        }

        @Override
        void before(MethodHookParam param) {
            Object[] args = param.args;
            if (args.length < 3 || args[0] == null || args[2] == null) {
                return;
            }
            if (!Clones.isClone(XposedHelpers.getIntField(args[2], "mUserId"))) {
                return;
            }
            Object mainMethod = currentMethod(param.thisObject, Clones.PARENT);
            if (mainMethod == null) {
                return;
            }
            IBinder main = binderOf(XposedHelpers.getObjectField(mainMethod, "mTarget"));
            IBinder incoming = binderOf(XposedHelpers.getObjectField(args[0], "mTarget"));
            if (main != null && main == incoming) {
                param.setResult(null);
                Log.count("skip duplicate ime init", null);
            }
        }
    }

    /**
     * InputMethodManagerService.requestClientSessionLocked(client, userId):
     * skip while that user's controller has no connected method. The stock
     * code calls getClass() on it and the NPE takes system_server down. The
     * next focus change asks again.
     */
    static final class SessionGuard extends SafeHook {
        SessionGuard() {
            super("ime-session");
        }

        @Override
        void before(MethodHookParam param) {
            Object[] args = param.args;
            if (args.length < 2 || !(args[1] instanceof Integer)) {
                return;
            }
            Object userData = XposedHelpers.callMethod(param.thisObject, "getUserData", args[1]);
            if (userData == null) {
                return;
            }
            Object controller = XposedHelpers.getObjectField(userData, "mBindingController");
            if (controller != null && XposedHelpers.getObjectField(controller, "mCurMethod") == null) {
                param.setResult(null);
                Log.count("skip null ime session", "user " + args[1]);
            }
        }
    }

    /** The IInputMethodInvoker connected for {@code userId}, or null. */
    private static Object currentMethod(Object imms, int userId) {
        Object userData = XposedHelpers.callMethod(imms, "getUserData", userId);
        if (userData == null) {
            return null;
        }
        Object controller = XposedHelpers.getObjectField(userData, "mBindingController");
        return controller == null ? null : XposedHelpers.getObjectField(controller, "mCurMethod");
    }

    private static IBinder binderOf(Object target) {
        return target instanceof IInterface ? ((IInterface) target).asBinder() : null;
    }
}
