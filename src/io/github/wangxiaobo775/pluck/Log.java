package io.github.wangxiaobo775.pluck;

import de.robv.android.xposed.XposedBridge;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** All module output goes through here, prefixed "SysGuard: " so one grep finds it. */
final class Log {
    private static final String PREFIX = "SysGuard: ";
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

    private Log() {}

    static void i(String message) {
        XposedBridge.log(PREFIX + message);
    }

    static void e(String where, Throwable t) {
        XposedBridge.log(PREFIX + where + " failed: " + t);
        XposedBridge.log(t);
    }

    /**
     * Count an event and log its 1st, 2nd, 4th, 8th ... occurrence.
     * Frequent events stay visible, with a running total, without flooding the log.
     */
    static void count(String event, String detail) {
        int n = COUNTS.computeIfAbsent(event, k -> new AtomicInteger()).incrementAndGet();
        if ((n & (n - 1)) == 0) {
            i(event + " #" + n + (detail == null ? "" : " " + detail));
        }
    }
}
