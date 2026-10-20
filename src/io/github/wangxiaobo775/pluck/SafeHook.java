package io.github.wangxiaobo775.pluck;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Base for every hook. A throwable from module code is counted and logged,
 * never thrown into system_server. Subclasses only write the happy path.
 */
abstract class SafeHook extends XC_MethodHook {
    /** Also the switch name under /data/system/sysguard/off/. */
    final String name;

    SafeHook(String name) {
        this.name = name;
    }

    void before(MethodHookParam param) throws Throwable {}

    void after(MethodHookParam param) throws Throwable {}

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) {
        try {
            before(param);
        } catch (Throwable t) {
            Log.count(name + " error", String.valueOf(t));
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) {
        try {
            after(param);
        } catch (Throwable t) {
            Log.count(name + " error", String.valueOf(t));
        }
    }

    /** Replace the call with a fixed result. */
    static final class Constant extends SafeHook {
        private final Object value;

        Constant(String name, Object value) {
            super(name);
            this.value = value;
        }

        @Override
        void before(MethodHookParam param) {
            param.setResult(value);
        }
    }
}
