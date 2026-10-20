package com.local.cloneui;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Entry point of the root process, run through app_process:
 *
 * <pre>
 *   su -c 'app_process -Djava.class.path=APK /system/bin com.local.cloneui.RootMain serve'
 *   ... RootMain trim [--dry-run] [USER...]
 *   ... RootMain untrim USER...
 * </pre>
 *
 * serve: one JSON request per stdin line, one JSON reply per stdout line,
 * until stdin closes (the page went away). Only the process that started us
 * holds the pipes, so there is no socket for anybody else to reach.
 */
public final class RootMain {
    private RootMain() {
    }

    public static void main(String[] args) {
        int code;
        try {
            if (args.length == 0 || args[0].equals("serve")) {
                code = serve();
            } else {
                code = Backend.cli(args);
            }
        } catch (Throwable t) {
            Backend.log("exit: " + t);
            t.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    private static int serve() throws IOException {
        // Replies own fd 1. Anything else that prints goes to stderr, so a
        // stray line can never be read as a reply.
        PrintStream replies = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        System.setOut(System.err);
        if (android.os.Process.myUid() != 0) {
            replies.println(Backend.error("后台非 Root 权限（uid " + android.os.Process.myUid() + "）"));
            return 1;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JSONObject reply;
            try {
                reply = Backend.handle(new JSONObject(line));
            } catch (JSONException e) {
                reply = Backend.error("请求解析失败");
            }
            replies.println(reply.toString());
        }
        return 0;
    }
}
