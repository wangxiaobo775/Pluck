package com.local.cloneui;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * The page's link to the root backend: one `su` → app_process RootMain
 * per page session, talked to over its stdin/stdout. Closing stdin ends it.
 *
 * Root comes from KernelSU's grant to this app (superuser list). Without the
 * grant, exec of su fails or the process exits before answering the ping.
 */
final class RootShell {
    static final String NO_ROOT = "无 Root 权限：请在 KernelSU 超级用户列表中授权「毫毛」后重开本页";

    private final String apk;
    private Process proc;
    private Writer in;
    private BufferedReader out;

    RootShell(String apk) {
        this.apk = apk;
    }

    /** One request line in, one reply line out. Never throws; errors are replies. */
    synchronized String call(String line) {
        if (!ensure()) {
            return error(NO_ROOT);
        }
        try {
            in.write(line.replace('\n', ' '));
            in.write('\n');
            in.flush();
            String reply = next();
            if (reply != null) {
                return reply;
            }
        } catch (IOException ignored) {
        }
        // The request was sent; do not repeat it (an automatic add would go
        // into the next clone). Start fresh on the next call.
        stop();
        return error("后台进程中断，请刷新查看结果");
    }

    synchronized void stop() {
        closeQuietly();
        proc = null;
        in = null;
        out = null;
    }

    private boolean ensure() {
        if (proc != null && proc.isAlive()) {
            return true;
        }
        stop();
        String cmd = "mkdir -p " + Backend.STATE + "; exec /system/bin/app_process"
                + " -Djava.class.path=" + Backend.quote(apk)
                + " /system/bin --nice-name=clone-ui-root com.local.cloneui.RootMain serve"
                + " 2>>" + Backend.STATE + "/clone-ui.err";
        try {
            // -M: the global mount namespace. This app's own namespace has
            // app-data isolation: /data/data shows only this app, so the
            // launcher's database is not there.
            proc = new ProcessBuilder("/system/bin/su", "-M", "-c", cmd)
                    .redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")))
                    .start();
            in = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
            out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            in.write("{\"op\":\"ping\"}\n");
            in.flush();
            String reply = next();
            if (reply != null && reply.contains("\"ok\":true")) {
                return true;
            }
        } catch (IOException ignored) {
        }
        stop();
        return false;
    }

    /** The next reply line; anything not starting with '{' is skipped. */
    private String next() throws IOException {
        String line;
        while ((line = out.readLine()) != null) {
            if (line.startsWith("{")) {
                return line;
            }
        }
        return null;
    }

    private void closeQuietly() {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }
    }

    static String error(String message) {
        return Backend.error(message).toString();
    }
}
