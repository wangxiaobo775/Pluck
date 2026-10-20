package io.github.wangxiaobo775.pluck;

import android.content.Intent;
import android.content.IntentFilter;

import de.robv.android.xposed.XposedHelpers;

import java.util.Arrays;

/** Cross-profile intent filters between the clones and the main user. */
final class CloneShare {
    private CloneShare() {}

    /**
     * UserManagerService.isCrossProfileIntentFilterAccessible(source, target, adding):
     * allow when either side is a clone. Stock clone profiles have access
     * control level 10, which only the system uid passes, so the stock
     * clone-to-parent web/share/camera filters never applied to normal apps.
     */
    static final class Access extends SafeHook {
        Access() {
            super("cross-profile");
        }

        @Override
        void before(MethodHookParam param) {
            Object[] args = param.args;
            if (args.length >= 2 && args[0] instanceof Integer && args[1] instanceof Integer
                    && (Clones.isClone((Integer) args[0]) || Clones.isClone((Integer) args[1]))) {
                param.setResult(true);
            }
        }
    }

    /**
     * Forward alipays:/alipay: links from every clone to the main user. The
     * stock clone filters do not cover these schemes.
     *
     * Runs on every boot. PackageManagerService drops a filter that already
     * exists (same filter, owner, target and flags), so repeating it is free,
     * and a clone created later gets it on the next boot. This replaces the
     * old one-shot /data/system/clone-share-filters.done marker.
     */
    static final class AlipayForwarding implements Runnable {
        /** FLAG_IS_PACKAGE_FOR_FILTER | FLAG_ALLOW_CHAINED_RESOLUTION, as before. */
        private static final int FLAGS = 0x18;
        private static final int ATTEMPTS = 60;

        static void start() {
            Thread thread = new Thread(new AlipayForwarding(), "SysGuard-alipay-filters");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            Throwable last = null;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                try {
                    Thread.sleep(1000);
                    int[] clones = Clones.ofParent(Clones.PARENT);
                    if (clones.length == 0 || Pm.service() == null) {
                        continue; // user or package service not up yet
                    }
                    for (int id : clones) {
                        Pm.addCrossProfileIntentFilter(filter(), "android", id, Clones.PARENT, FLAGS);
                    }
                    Log.i("alipay forward filters ensured for users " + Arrays.toString(clones));
                    return;
                } catch (Throwable t) {
                    last = t;
                }
            }
            if (last != null) {
                Log.e("alipay forward filters", last);
            } else {
                Log.i("alipay forward filters: no clone users");
            }
        }

        private static IntentFilter filter() {
            IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            filter.addCategory(Intent.CATEGORY_BROWSABLE);
            filter.addDataScheme("alipays");
            filter.addDataScheme("alipay");
            return filter;
        }
    }

    /**
     * UserManagerService.createUserInternalUnchecked: when the new user is a
     * clone, give it the alipay filters now rather than on the next boot.
     * Runs off the binder thread, after UserManagerService has let go of its
     * locks and the user is fully set up.
     */
    static final class NewClone extends SafeHook {
        NewClone() {
            super("alipay-filters");
        }

        @Override
        void after(MethodHookParam param) {
            Object info = param.getResult();
            if (info == null) {
                return;
            }
            int id = XposedHelpers.getIntField(info, "id");
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    if (Clones.isClone(id)) {
                        Pm.addCrossProfileIntentFilter(AlipayForwarding.filter(), "android", id, Clones.PARENT,
                                AlipayForwarding.FLAGS);
                        Log.i("alipay forward filters ensured for new clone " + id);
                    }
                } catch (Throwable t) {
                    Log.e("alipay forward filters for new user " + id, t);
                }
            }, "SysGuard-alipay-new-user");
            thread.setDaemon(true);
            thread.start();
        }
    }
}
