package io.github.wangxiaobo775.pluck;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.Binder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The in-process IPackageManager. Hidden API, so the methods are looked up
 * once by reflection. Every call runs as system_server: callers are often an
 * app's binder thread, and package visibility filtering would otherwise hide
 * packages that are really installed.
 */
final class Pm {
    private static final int MATCH_DEFAULT_ONLY = 0x00010000;

    private static volatile Method getApplicationInfo;
    private static volatile Method queryIntentActivities;
    private static volatile Method addCrossProfileIntentFilter;
    private static volatile Method getList;

    private Pm() {}

    /** The package manager, or null while system_server is still starting. */
    static Object service() throws ReflectiveOperationException {
        return Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
    }

    static boolean installed(String pkg, int userId) throws ReflectiveOperationException {
        Object pm = service();
        if (pm == null) {
            return true; // unknown: keep the launch where it was
        }
        if (getApplicationInfo == null) {
            getApplicationInfo = iface().getMethod("getApplicationInfo", String.class, long.class, int.class);
        }
        long token = Binder.clearCallingIdentity();
        try {
            return getApplicationInfo.invoke(pm, pkg, 0L, userId) != null;
        } catch (InvocationTargetException e) {
            if (String.valueOf(e.getCause()).contains("NameNotFound")) {
                return false;
            }
            throw e;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Whether a real activity in {@code userId} takes this intent. The
     * cross-profile IntentForwarderActivity does not count: it only means
     * "send it to another user".
     */
    static boolean resolves(Intent intent, String resolvedType, int userId) throws ReflectiveOperationException {
        Object pm = service();
        if (pm == null) {
            return true;
        }
        if (queryIntentActivities == null) {
            queryIntentActivities = iface().getMethod("queryIntentActivities",
                    Intent.class, String.class, long.class, int.class);
        }
        long token = Binder.clearCallingIdentity();
        try {
            Object slice = queryIntentActivities.invoke(pm, intent, resolvedType, (long) MATCH_DEFAULT_ONLY, userId);
            if (slice == null) {
                return false;
            }
            if (getList == null) {
                getList = slice.getClass().getMethod("getList");
            }
            for (Object o : (List<?>) getList.invoke(slice)) {
                ResolveInfo ri = (ResolveInfo) o;
                if (ri.activityInfo != null && !ri.activityInfo.name.endsWith("IntentForwarderActivity")) {
                    return true;
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static void addCrossProfileIntentFilter(IntentFilter filter, String owner, int sourceUser, int targetUser, int flags)
            throws ReflectiveOperationException {
        Object pm = service();
        if (pm == null) {
            throw new IllegalStateException("package manager not up");
        }
        if (addCrossProfileIntentFilter == null) {
            addCrossProfileIntentFilter = iface().getMethod("addCrossProfileIntentFilter",
                    IntentFilter.class, String.class, int.class, int.class, int.class);
        }
        long token = Binder.clearCallingIdentity();
        try {
            addCrossProfileIntentFilter.invoke(pm, filter, owner, sourceUser, targetUser, flags);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static Class<?> iface() throws ClassNotFoundException {
        return Class.forName("android.content.pm.IPackageManager");
    }
}
