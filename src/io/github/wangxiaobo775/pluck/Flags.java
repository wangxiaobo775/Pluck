package io.github.wangxiaobo775.pluck;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Switches and optional settings read from /data/system/sysguard.
 *
 *   touch /data/system/sysguard/disable            whole module does nothing
 *   touch /data/system/sysguard/off/<hook-name>    skip one hook (names in MainHook)
 *   echo pkg > /data/system/sysguard/preferred-ime override default IME fallback
 *
 * system_server can read /data/system, so a soft reboot is enough to apply a
 * change. No reinstall, no pm.
 */
final class Flags {
    static final File DIR = new File("/data/system/sysguard");
    static final File DISABLE = new File(DIR, "disable");
    static final File PREFERRED_IME = new File(DIR, "preferred-ime");

    private Flags() {}

    static boolean disabled() {
        return DISABLE.exists();
    }

    static boolean off(String hookName) {
        return new File(new File(DIR, "off"), hookName).exists();
    }

    static String preferredIme() {
        try {
            if (PREFERRED_IME.isFile()) {
                String s = new String(Files.readAllBytes(PREFERRED_IME.toPath()), StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
