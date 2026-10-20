package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-only stub. The real class comes from the Xposed framework at runtime. */
public abstract class XC_MethodHook {
    public XC_MethodHook() {}

    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() { throw new UnsupportedOperationException(); }

        public void setResult(Object result) { throw new UnsupportedOperationException(); }

        public Throwable getThrowable() { throw new UnsupportedOperationException(); }

        public boolean hasThrowable() { throw new UnsupportedOperationException(); }

        public void setThrowable(Throwable throwable) { throw new UnsupportedOperationException(); }
    }

    public class Unhook {
        public void unhook() {}
    }
}
