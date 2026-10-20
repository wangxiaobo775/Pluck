package io.github.wangxiaobo775.pluck;

import android.os.Binder;

import de.robv.android.xposed.XposedHelpers;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which users are clone profiles. Replaces the hard-coded 15..19 range, so a
 * clone created later gets the same treatment without a rebuild.
 *
 * Answers come from UserManagerService and are cached per user id. User ids
 * are not reused while system_server is up, so the cache never goes stale.
 */
final class Clones {
    /** Every clone here hangs off the main user, same as Motorola's AppCloneHelper. */
    static final int PARENT = 0;

    private static final String CLONE_TYPE = "android.os.usertype.profile.CLONE";
    private static final ConcurrentHashMap<Integer, Boolean> CACHE = new ConcurrentHashMap<>();
    private static volatile Class<?> userManagerService;

    private Clones() {}

    static void init(ClassLoader systemServer) {
        userManagerService = XposedHelpers.findClass("com.android.server.pm.UserManagerService", systemServer);
    }

    static boolean isClone(int userId) {
        if (userId <= PARENT) {
            return false; // main user, USER_ALL, USER_CURRENT and other pseudo ids
        }
        Boolean known = CACHE.get(userId);
        if (known != null) {
            return known;
        }
        Object info = userInfo(userId);
        if (info == null) {
            return false; // service not up yet; ask again next time
        }
        boolean clone = CLONE_TYPE.equals(XposedHelpers.getObjectField(info, "userType"));
        CACHE.put(userId, clone);
        return clone;
    }

    /** Clone profile ids under {@code parent}. Empty while UserManagerService is not up. */
    static int[] ofParent(int parent) {
        Object ums = service();
        if (ums == null) {
            return new int[0];
        }
        long token = Binder.clearCallingIdentity();
        try {
            int[] ids = (int[]) XposedHelpers.callMethod(ums, "getProfileIds", parent, false);
            return Arrays.stream(ids).filter(id -> id != parent && isClone(id)).toArray();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static Object userInfo(int userId) {
        Object ums = service();
        if (ums == null) {
            return null;
        }
        // NoChecks: callers sit on arbitrary binder threads, often an app's.
        return XposedHelpers.callMethod(ums, "getUserInfoNoChecks", userId);
    }

    private static Object service() {
        Class<?> cls = userManagerService;
        return cls == null ? null : XposedHelpers.callStaticMethod(cls, "getInstance");
    }
}
