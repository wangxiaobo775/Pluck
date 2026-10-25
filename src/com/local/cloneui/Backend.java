package com.local.cloneui;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.Security;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Root side of the 应用分身 page. Runs as uid 0 inside app_process
 * ({@link RootMain}), started by the page through su; never inside the
 * page's own process.
 *
 * <pre>
 *   list                      clone users, their apps, apps that can be added
 *   add / remove  user pkg    pm install-existing / uninstall --user, as uid 2000
 *   rename        user pkg t  custom label of the clone's desktop icon ("" clears it)
 *   open          user pkg    am start --user
 *   gms           user on     enable / disable Google Play services in that clone
 *   vpn           on          enable / disable clone VPN kill switch & routing watcher
 *   create                    a new empty clone (trimmed, GMS off)
 *   delete        user        remove a clone user with its apps and data
 *   edit          user …      rename a clone, set its avatar colour / text
 * </pre>
 *
 * Clone users are whatever `cmd user list -v` reports as profile.CLONE.
 * Per-device policy (packages never to add again) is in CONFIG.
 */
final class Backend {
    static final String STATE = "/data/local/tmp/clone";
    static final String LOG = STATE + "/clone-ui.log";
    static final String ADB = "/data/adb";
    static final String CONFIG = ADB + "/clone-ui.json";
    static final String VPN_LIB = ADB + "/clone-lib.sh";
    static final String VPN_SCRIPT = ADB + "/clone-vpn.sh";
    static final String VPN_SERVICE = ADB + "/service.d/clone-vpn.sh";
    static final String VPN_CHAIN = "clone_vpn_guard";
    static final long LOG_MAX = 262144;

    static final String LAUNCHER = "com.sec.android.app.launcher";
    static final String LAUNCHER_DATA = "/data/data/" + LAUNCHER;
    static final String HOME_ONLY_DB = LAUNCHER_DATA + "/databases/HomeOnly.db";
    static final String ONEUI_DB = LAUNCHER_DATA + "/databases/OneUI.db";
    static final String ICON_DB = LAUNCHER_DATA + "/databases/Icon.db";
    static final String HOMEUP_PREFS = "/data/data/com.samsung.android.app.homestar/shared_prefs/"
            + "com.samsung.android.app.homestar.v2.preferences.xml";
    static final String HOMEUP_LABEL_KEY = "home_icon-view_use_custom_label";

    static final Pattern PKG_RE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");
    static final Pattern USER_RE = Pattern.compile("\\bid=(\\d+), name=(.*?), type=profile\\.CLONE,");
    static final Pattern ANY_USER_RE = Pattern.compile("\\bid=(\\d+), name=");
    // Never offered and never touched: the module/manager package, the launcher.
    static final Set<String> SELF_PKGS = selfPkgs();
    static final Set<String> HIDDEN = buildHidden();
    // The launcher only reads its database again after a restart; give it this
    // long to come back before reading our row back.
    static final long LAUNCHER_SETTLE_MS = 600;
    static final int DEFAULT_MAX_CLONES = 10;
    static final String APP_VERSION = "3.1";
    static final String REPO_URL = "https://github.com/wangxiaobo775/Pluck";
    static final String RELEASE_API = "https://api.github.com/repos/wangxiaobo775/Pluck/releases/latest";

    private static Set<String> selfPkgs() {
        Set<String> s = new HashSet<>(Arrays.asList(
                "io.github.wangxiaobo775.pluck",
                "io.github.wangxiaobo775.cloneprofiles",
                "io.github.icepony.alwayscreateuser",
                "com.local.cloneui"));
        try {
            String cp = System.getProperty("java.class.path", "");
            File parent = new File(cp).getParentFile();
            if (parent != null) {
                String dir = parent.getName();
                int dash = dir.indexOf('-');
                String candidate = dash > 0 ? dir.substring(0, dash) : dir;
                if (PKG_RE.matcher(candidate).matches()) {
                    s.add(candidate);
                }
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private static Set<String> buildHidden() {
        Set<String> s = new HashSet<>(SELF_PKGS);
        s.add(LAUNCHER);
        s.add("android");
        return s;
    }

    private Backend() {
    }

    /** A failure whose message is shown in the page as is. */
    static class Fail extends Exception {
        private static final long serialVersionUID = 1L;

        Fail(String message) {
            super(message);
        }
    }

    static final class Timeout extends Fail {
        private static final long serialVersionUID = 1L;

        Timeout(String message) {
            super(message);
        }
    }

    // ---- small helpers -----------------------------------------------------

    static void log(String text) {
        try {
            new File(STATE).mkdirs();
            File f = new File(LOG);
            if (f.length() > LOG_MAX) {
                f.renameTo(new File(LOG + ".1"));
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(LOG, true), StandardCharsets.UTF_8)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss ", Locale.US).format(new Date()) + text + "\n");
            }
        } catch (IOException ignored) {
        }
    }

    static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    static boolean exists(String path) {
        return new File(path).exists();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Copy src over dst in place: dst keeps its inode, owner and SELinux label. */
    static void copyInto(String src, String dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    static final class Config {
        JSONObject blocked = new JSONObject();
        int maxClones = DEFAULT_MAX_CLONES;
        Set<String> keepExtra = new HashSet<>();
    }

    /**
     * Per-device policy from CONFIG. A missing file or key means the default.
     *
     *   blocked      {pkg: reason shown in the page}  never added to a clone
     *   max_clones   automatic adds create clones up to this many
     *   keep_extra   [pkg]  system packages trimming leaves in every clone,
     *                on top of TRIM_KEEP
     */
    static Config config() {
        Config c = new Config();
        JSONObject data;
        try {
            data = new JSONObject(readFile(CONFIG));
        } catch (IOException | JSONException e) {
            return c;
        }
        JSONObject blocked = data.optJSONObject("blocked");
        if (blocked != null) {
            c.blocked = blocked;
        }
        Object limit = data.opt("max_clones");
        if (limit instanceof Integer && (Integer) limit > 0) {
            c.maxClones = (Integer) limit;
        }
        JSONArray extra = data.optJSONArray("keep_extra");
        if (extra != null) {
            for (int i = 0; i < extra.length(); i++) {
                Object v = extra.opt(i);
                if (v instanceof String) {
                    c.keepExtra.add((String) v);
                }
            }
        }
        return c;
    }

    // ---- processes ---------------------------------------------------------

    static final class Out {
        final int code;
        final String text;

        Out(int code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    /** Run a command, stdout and stderr together, trimmed. */
    static Out run(long timeoutSec, String... args) throws Fail {
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        pb.environment().put("PATH", "/system/bin:/system/xbin");
        final Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new Fail("运行失败：" + args[0] + " " + e.getMessage());
        }
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            byte[] b = new byte[8192];
            try (InputStream s = p.getInputStream()) {
                int n;
                while ((n = s.read(b)) > 0) {
                    synchronized (buf) {
                        buf.write(b, 0, n);
                    }
                }
            } catch (IOException ignored) {
            }
        });
        reader.start();
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        boolean done;
        try {
            done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            done = false;
        }
        if (!done) {
            p.destroyForcibly();
            join(reader, 2000);
            throw new Timeout("命令超时：" + String.join(" ", args));
        }
        join(reader, 5000);
        String text;
        synchronized (buf) {
            text = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
        }
        return new Out(p.exitValue(), text);
    }

    private static void join(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static Out run(String... args) throws Fail {
        return run(60, args);
    }

    // Installs into other users must come from uid 2000; as root Samsung's
    // pm answers "not an approved installer" or fakes success.
    static Out pm(boolean asShell, String... args) throws Fail {
        if (asShell) {
            StringBuilder sb = new StringBuilder("PATH=/system/bin:/system/xbin; /system/bin/pm");
            for (String a : args) {
                sb.append(' ').append(quote(a));
            }
            return run(60, "/system/bin/su", "2000", "-c", sb.toString());
        }
        String[] full = new String[args.length + 1];
        full[0] = "/system/bin/pm";
        System.arraycopy(args, 0, full, 1, args.length);
        return run(60, full);
    }

    // ---- users and packages ------------------------------------------------

    static final class Clone {
        final int id;
        final String name;

        Clone(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** Every profile.CLONE user, in `cmd user list` order. */
    static List<Clone> cloneUsers() throws Fail {
        Out r = run(20, "/system/bin/cmd", "user", "list", "-v");
        if (r.code != 0) {
            throw new Fail(r.text.isEmpty() ? "读取用户列表失败" : r.text);
        }
        List<Clone> users = new ArrayList<>();
        Matcher m = USER_RE.matcher(r.text);
        while (m.find()) {
            users.add(new Clone(Integer.parseInt(m.group(1)), m.group(2)));
        }
        return users;
    }

    static Set<Integer> cloneIds() throws Fail {
        Set<Integer> ids = new TreeSet<>();
        for (Clone c : cloneUsers()) {
            ids.add(c.id);
        }
        return ids;
    }

    // ---- how a clone looks in the page: number, avatar colour and text ------
    // Kept here rather than in the user record: Android shows nothing of the
    // sort for a clone profile. "no" is fixed when the clone is first seen
    // (from its 克隆N name, else the lowest free number), so the order and the
    // default avatar survive a rename. Colour names are the page's palette.

    static final String META = STATE + "/clones.json";
    static final List<String> COLORS = Arrays.asList(
            "blue", "orange", "green", "purple", "pink", "teal", "red", "yellow");
    static final int NAME_MAX = 20;  // code points
    static final int TEXT_MAX = 2;   // code points on the avatar
    // A comma would break USER_RE on the `cmd user list -v` line.
    static final Pattern NAME_BAD = Pattern.compile("[\\p{Cntrl},]");

    static JSONObject readMeta() {
        try {
            return new JSONObject(readFile(META));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    static void writeMeta(JSONObject meta) throws Fail {
        File tmp = new File(META + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(meta.toString(2));
        } catch (IOException | JSONException e) {
            throw new Fail("写入失败：" + META + "（" + e.getMessage() + "）");
        }
        if (!tmp.renameTo(new File(META))) {
            throw new Fail("写入失败：" + META);
        }
    }

    /** Meta for exactly the clones in users, each with a number; saved if it changed. */
    static JSONObject syncMeta(List<Clone> users) throws Fail {
        JSONObject meta = readMeta();
        boolean changed = false;
        Set<String> live = new HashSet<>();
        for (Clone c : users) {
            live.add(String.valueOf(c.id));
        }
        List<String> gone = new ArrayList<>();
        for (java.util.Iterator<String> it = meta.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!live.contains(key) || meta.optJSONObject(key) == null) {
                gone.add(key);
            }
        }
        for (String key : gone) {
            meta.remove(key);
            changed = true;
        }
        Set<Integer> taken = new HashSet<>();
        List<Clone> unnumbered = new ArrayList<>();
        for (Clone c : users) {
            JSONObject m = meta.optJSONObject(String.valueOf(c.id));
            if (m != null && m.optInt("no", 0) > 0) {
                taken.add(m.optInt("no"));
            } else {
                unnumbered.add(c);
            }
        }
        unnumbered.sort((a, b) -> Integer.compare(a.id, b.id));
        // Clones still called 克隆N claim N first; the rest get the lowest free.
        for (int pass = 0; pass < 2; pass++) {
            for (Clone c : unnumbered) {
                String key = String.valueOf(c.id);
                if (meta.has(key) && meta.optJSONObject(key).optInt("no", 0) > 0) {
                    continue;
                }
                int no = 0;
                Matcher m = CLONE_NAME.matcher(c.name);
                if (pass == 0) {
                    if (!m.matches() || taken.contains(Integer.parseInt(m.group(1)))) {
                        continue;
                    }
                    no = Integer.parseInt(m.group(1));
                } else {
                    no = 1;
                    while (taken.contains(no)) {
                        no++;
                    }
                }
                taken.add(no);
                try {
                    JSONObject entry = meta.optJSONObject(key);
                    if (entry == null) {
                        entry = new JSONObject();
                        meta.put(key, entry);
                    }
                    entry.put("no", no);
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
                changed = true;
            }
        }
        if (changed) {
            writeMeta(meta);
        }
        return meta;
    }

    static int codePoints(String s) {
        return s.codePointCount(0, s.length());
    }

    // ---- the same avatar as the desktop badge ------------------------------
    // SysGuard, when One UI Home is in its scope, draws a clone's badge from
    // Settings.Global cloneui_badges: {"11":{"color":"#fa7b17","text":"2"}}.
    // Without that scope the setting is just unused. The launcher bakes the
    // badge into Icon.db and keeps it in memory, so a change clears the clones'
    // cached icons and restarts it.

    static final String BADGES_KEY = "cloneui_badges";
    // Same order as COLORS and the page's PALETTE.
    static final List<String> COLOR_HEX = Arrays.asList(
            "#1d92ff", "#fa7b17", "#41af6a", "#a142f4", "#e52592", "#009688", "#e25142", "#eda912");

    static String badgesJson(JSONObject meta) throws JSONException {
        JSONObject all = new JSONObject();
        for (String key : new TreeSet<>(iterable(meta.keys()))) {
            JSONObject m = meta.optJSONObject(key);
            int no = m == null ? 0 : m.optInt("no", 0);
            if (no <= 0) {
                continue;
            }
            int color = m.has("color") ? COLORS.indexOf(m.optString("color")) : -1;
            String hex = COLOR_HEX.get(color >= 0 ? color : (no - 1) % COLOR_HEX.size());
            String text = m.optString("text", "");
            all.put(key, new JSONObject().put("color", hex).put("text", text.isEmpty() ? String.valueOf(no) : text));
        }
        return all.toString();
    }

    private static List<String> iterable(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    /** Publish the badges; on a change, drop the clones' cached icons. */
    static void syncBadges(JSONObject meta) {
        try {
            String json = badgesJson(meta);
            Out cur = run(20, "/system/bin/settings", "get", "global", BADGES_KEY);
            if (cur.code == 0 && cur.text.equals(json)) {
                return;
            }
            Out put = run(20, "/system/bin/settings", "put", "global", BADGES_KEY, json);
            if (put.code != 0) {
                log("badges: " + put.text);
                return;
            }
            log("badges " + json);
            List<String> ids = iterable(meta.keys());
            if (ids.isEmpty() || !exists(ICON_DB)) {
                return;
            }
            String in = "profile_id IN (" + String.join(",", ids) + ")";
            editLauncher((home, icon) -> {
                if (icon != null) {
                    icon.execSQL("DELETE FROM icon WHERE " + in);
                }
            });
        } catch (Exception e) {
            log("badges: " + e.getMessage());
        }
    }

    /**
     * Change a clone's name and/or avatar. Only the keys present are touched:
     * name (string), color (palette name, or null for the default), text
     * (up to two characters, "" for the default: the clone's number).
     */
    static JSONObject opEdit(JSONObject req) throws Exception {
        int user = checkUser(req.isNull("user") ? null : req.opt("user"));
        String name = null;
        if (req.has("name")) {
            Object v = req.opt("name");
            name = v instanceof String ? ((String) v).trim() : "";
            if (name.isEmpty() || codePoints(name) > NAME_MAX || NAME_BAD.matcher(name).find()) {
                throw new Fail("名称需为 1–" + NAME_MAX + " 个字符，且不含逗号");
            }
        }
        String color = null;
        if (req.has("color") && !req.isNull("color")) {
            color = req.optString("color");
            if (!COLORS.contains(color)) {
                throw new Fail("未知颜色");
            }
        }
        String text = null;
        if (req.has("text")) {
            Object v = req.opt("text");
            text = v instanceof String ? ((String) v).trim() : "";
            if (codePoints(text) > TEXT_MAX || NAME_BAD.matcher(text).find()) {
                throw new Fail("角标最多 " + TEXT_MAX + " 个字符");
            }
        }
        if (name != null) {
            Out r = pm(false, "rename-user", String.valueOf(user), name);
            boolean renamed = false;
            for (Clone c : cloneUsers()) {
                renamed |= c.id == user && c.name.equals(name);
            }
            if (!renamed) {
                throw new Fail(r.text.isEmpty() ? "修改名称失败" : r.text);
            }
            log("rename clone " + user + " " + name);
        }
        if (req.has("color") || req.has("text")) {
            JSONObject meta = syncMeta(cloneUsers());
            JSONObject entry = meta.optJSONObject(String.valueOf(user));
            if (entry == null) {
                entry = new JSONObject();
                meta.put(String.valueOf(user), entry);
            }
            if (req.has("color")) {
                if (color == null) {
                    entry.remove("color");
                } else {
                    entry.put("color", color);
                }
            }
            if (text != null) {
                if (text.isEmpty()) {
                    entry.remove("text");
                } else {
                    entry.put("text", text);
                }
            }
            writeMeta(meta);
            syncBadges(meta);
            log("avatar " + user + " " + entry.optString("color", "-") + " " + entry.optString("text", "-"));
        }
        return new JSONObject().put("ok", true);
    }

    static String checkPkg(Object pkg) throws Fail {
        if (!(pkg instanceof String) || !PKG_RE.matcher((String) pkg).matches()) {
            throw new Fail("包名不正确");
        }
        return (String) pkg;
    }

    static int checkUser(Object user) throws Fail {
        if (!(user instanceof Integer) || !cloneIds().contains(user)) {
            throw new Fail("只能管理分身用户");
        }
        return (Integer) user;
    }

    static List<String> packagesFor(int user, boolean thirdParty) throws Fail {
        Out r = thirdParty
                ? pm(false, "list", "packages", "-3", "--user", String.valueOf(user))
                : pm(false, "list", "packages", "--user", String.valueOf(user));
        if (r.code != 0) {
            throw new Fail(r.text.isEmpty() ? "读取应用列表失败" : r.text);
        }
        List<String> pkgs = new ArrayList<>();
        for (String line : r.text.split("\n")) {
            if (line.startsWith("package:")) {
                pkgs.add(line.substring("package:".length()).trim());
            }
        }
        return pkgs;
    }

    static int launcherUid() throws Fail {
        try {
            return Os.stat(LAUNCHER_DATA).st_uid;
        } catch (Exception e) {
            throw new Fail("找不到桌面的数据目录");
        }
    }

    /** {pkg: pkg/activity} of every launcher activity in the main user. */
    static Map<String, String> launcherComponents() throws Fail {
        Out r = run(40, "/system/bin/cmd", "package", "query-activities",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "--user", "0");
        Map<String, String> found = new TreeMap<>();
        if (r.code != 0) {
            return found;
        }
        String pendingName = null;
        for (String line : r.text.split("\n")) {
            String text = line.trim();
            if (text.startsWith("Activity #")) {
                pendingName = null;
            } else if (text.startsWith("name=")) {
                pendingName = text.substring(5);
            } else if (text.startsWith("packageName=") && pendingName != null) {
                String pkg = text.substring("packageName=".length());
                if (!found.containsKey(pkg) && !pkg.contains("/")) {
                    found.put(pkg, pkg + "/" + pendingName);
                }
                pendingName = null;
            }
        }
        return found;
    }

    static boolean packageInstalled(int user, String pkg) throws Fail {
        Out r = pm(false, "path", "--user", String.valueOf(user), pkg);
        return r.code == 0 && r.text.contains("package:");
    }

    // ---- Google Play services per clone -------------------------------------
    // Motorola installs GMS into every clone but disables it
    // (AppCloneHelper.updateWhitelistAppsInstallState): apps linking against it
    // still load, and no GMS process runs. Same default here; the page turns it
    // on per clone for apps that need Google push or sign-in. It is about half
    // of what an idle clone keeps in memory (~76 MB of ~135 MB, 2026-10-11).

    static final String GMS = "com.google.android.gms";

    /** true enabled, false disabled, null not installed in that user. */
    static Boolean gmsEnabled(int user) throws Fail {
        if (listed(user, "-e")) {
            return true;
        }
        return listed(user, "-d") ? false : null;
    }

    private static boolean listed(int user, String flag) throws Fail {
        Out r = pm(false, "list", "packages", flag, "--user", String.valueOf(user), GMS);
        for (String line : r.text.split("\n")) {
            if (line.trim().equals("package:" + GMS)) {
                return true;
            }
        }
        return false;
    }

    static void setGms(int user, boolean on) throws Fail {
        if (gmsEnabled(user) == null) {
            throw new Fail("该分身未安装 Google 服务");
        }
        Out r = on
                ? pm(false, "enable", "--user", String.valueOf(user), GMS)
                : pm(false, "disable-user", "--user", String.valueOf(user), GMS);
        if (!Boolean.valueOf(on).equals(gmsEnabled(user))) {
            throw new Fail(r.text.isEmpty() ? "切换 Google 服务状态失败" : r.text);
        }
        log("gms " + user + " " + (on ? "on" : "off"));
    }

    static JSONObject opGms(Object userArg, Object onArg) throws Exception {
        int user = checkUser(userArg);
        if (!(onArg instanceof Boolean)) {
            throw new Fail("开关参数不正确");
        }
        setGms(user, (Boolean) onArg);
        return new JSONObject().put("ok", true);
    }

    // ---- Embedded VPN kill switch & routing watcher ------------------------

    private static void extractAsset(String assetName, String dstPath, int mode) throws Fail {
        String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty() || !new File(cp).isFile()) {
            throw new Fail("找不到当前安装包路径");
        }
        byte[] content;
        try (ZipFile zip = new ZipFile(cp)) {
            ZipEntry entry = zip.getEntry("assets/" + assetName);
            if (entry == null) {
                throw new Fail("安装包内缺少 assets/" + assetName);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                }
            }
            content = buf.toByteArray();
        } catch (IOException e) {
            throw new Fail("读取内置脚本失败：" + e.getMessage());
        }
        try {
            File dst = new File(dstPath);
            if (dst.isFile()) {
                byte[] existing = Files.readAllBytes(dst.toPath());
                if (Arrays.equals(existing, content)) {
                    Os.chmod(dstPath, mode);
                    return;
                }
            }
            File parent = dst.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            File tmp = new File(dstPath + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(content);
            }
            Os.chown(tmp.getAbsolutePath(), 0, 0);
            Os.chmod(tmp.getAbsolutePath(), mode);
            if (!tmp.renameTo(dst)) {
                throw new Fail("无法写入 " + dstPath);
            }
        } catch (Exception e) {
            if (e instanceof Fail) {
                throw (Fail) e;
            }
            throw new Fail("部署脚本失败：" + e.getMessage());
        }
    }

    static void ensureVpnScripts() throws Fail {
        new File(ADB + "/service.d").mkdirs();
        new File(STATE).mkdirs();
        extractAsset("clone-lib.sh", VPN_LIB, 0644);
        extractAsset("clone-vpn.sh", VPN_SCRIPT, 0755);
        if (!exists(CONFIG)) {
            extractAsset("clone-ui.json", CONFIG, 0644);
        }
    }

    static boolean vpnGuardEnabled() {
        try {
            Out r4 = run(10, "/system/bin/nsenter", "-t", "1", "-m", "--",
                    "/system/bin/iptables", "-w", "-C", "OUTPUT", "-j", VPN_CHAIN);
            Out r6 = run(10, "/system/bin/nsenter", "-t", "1", "-m", "--",
                    "/system/bin/ip6tables", "-w", "-C", "OUTPUT", "-j", VPN_CHAIN);
            if (r4.code == 0 && r6.code == 0) {
                return true;
            }
        } catch (Fail ignored) {
        }
        return exists(VPN_SERVICE);
    }

    static void setVpnGuard(boolean on) throws Fail {
        ensureVpnScripts();
        if (on) {
            extractAsset("service-clone-vpn.sh", VPN_SERVICE, 0755);
            run(20, "/system/bin/nsenter", "-t", "1", "-m", "--", "/system/bin/sh", VPN_SERVICE);
            if (!cloneIds().isEmpty()) {
                Out r = run(30, "/system/bin/nsenter", "-t", "1", "-m", "--", "/system/bin/sh", VPN_SCRIPT);
                if (r.code != 0 && !vpnGuardEnabled()) {
                    throw new Fail(r.text.isEmpty() ? "启用 VPN 断网保护失败" : r.text);
                }
            }
            log("vpn guard on");
        } else {
            new File(VPN_SERVICE).delete();
            Out r = run(30, "/system/bin/nsenter", "-t", "1", "-m", "--", "/system/bin/sh", VPN_SCRIPT, "--off");
            if (vpnGuardEnabled()) {
                throw new Fail(r.text.isEmpty() ? "关闭 VPN 断网保护失败" : r.text);
            }
            log("vpn guard off");
        }
    }

    static JSONObject opVpn(Object onArg) throws Exception {
        if (!(onArg instanceof Boolean)) {
            throw new Fail("开关参数不正确");
        }
        setVpnGuard((Boolean) onArg);
        return new JSONObject().put("ok", true).put("vpn_guard", vpnGuardEnabled());
    }

    static String resolveComponent(String pkg) throws Fail {
        Out r = run("/system/bin/cmd", "package", "resolve-activity", "--brief",
                "--user", "0",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                pkg);
        for (String line : r.text.split("\n")) {
            line = line.trim();
            if (line.startsWith(pkg + "/")) {
                return line;
            }
        }
        return null;
    }

    static JSONObject listState() throws Exception {
        List<Clone> users = cloneUsers();
        List<Integer> ids = new ArrayList<>();
        for (Clone c : users) {
            ids.add(c.id);
        }
        // Each pm call is a separate process and binder round trip; run them
        // side by side instead of one after another.
        Map<Integer, Set<String>> third = new HashMap<>();
        Map<Integer, Boolean> gms = new HashMap<>();
        Map<String, String> components;
        ExecutorService pool = Executors.newFixedThreadPool(2 * ids.size() + 2);
        try {
            Map<Integer, Future<List<String>>> jobs = new LinkedHashMap<>();
            Map<Integer, Future<Boolean>> gmsJobs = new LinkedHashMap<>();
            List<Integer> all = new ArrayList<>();
            all.add(0);
            all.addAll(ids);
            for (int uid : all) {
                jobs.put(uid, pool.submit(() -> packagesFor(uid, true)));
            }
            for (int uid : ids) {
                gmsJobs.put(uid, pool.submit(() -> gmsEnabled(uid)));
            }
            Future<Map<String, String>> componentsJob = pool.submit(Backend::launcherComponents);
            for (Map.Entry<Integer, Future<List<String>>> e : jobs.entrySet()) {
                third.put(e.getKey(), new HashSet<>(unwrap(e.getValue())));
            }
            for (Map.Entry<Integer, Future<Boolean>> e : gmsJobs.entrySet()) {
                gms.put(e.getKey(), unwrap(e.getValue()));
            }
            components = unwrap(componentsJob);
        } finally {
            pool.shutdownNow();
        }
        // If the module/manager APK was installed without --user 0 (e.g. from
        // Vector manager), remove it from clone users so only user 0 has the icon.
        for (Clone c : users) {
            Set<String> cPkgs = third.get(c.id);
            if (cPkgs == null) {
                continue;
            }
            for (String selfPkg : SELF_PKGS) {
                if (cPkgs.remove(selfPkg)) {
                    try {
                        pm(true, "uninstall", "--user", String.valueOf(c.id), selfPkg);
                        dropIcon(c.id, selfPkg);
                        log("removed self " + selfPkg + " from clone " + c.id);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        Map<String, String[]> installed = homeRows(ids);
        JSONArray candidates = new JSONArray();
        for (Map.Entry<String, String> e : components.entrySet()) {
            if (HIDDEN.contains(e.getKey())) {
                continue;
            }
            candidates.put(new JSONObject()
                    .put("pkg", e.getKey())
                    .put("component", e.getValue())
                    .put("system", !third.get(0).contains(e.getKey())));
        }
        JSONObject meta = syncMeta(users);
        syncBadges(meta);
        boolean vpnGuard = vpnGuardEnabled();
        if (vpnGuard) {
            try {
                ensureVpnScripts();
            } catch (Exception ignored) {
            }
        }
        JSONArray clones = new JSONArray();
        for (Clone c : users) {
            JSONObject look = meta.optJSONObject(String.valueOf(c.id));
            JSONArray apps = new JSONArray();
            for (String pkg : new TreeSet<>(third.get(c.id))) {
                if (HIDDEN.contains(pkg)) {
                    continue;
                }
                String[] row = installed.get(c.id + "/" + pkg);
                String component = row != null ? row[0] : components.get(pkg);
                String title = row != null ? row[1] : null;
                apps.put(new JSONObject()
                        .put("pkg", pkg)
                        .put("component", component == null ? JSONObject.NULL : component)
                        .put("title", title == null ? JSONObject.NULL : title));
            }
            Boolean g = gms.get(c.id);
            clones.put(new JSONObject().put("user", c.id).put("name", c.name).put("apps", apps)
                    .put("gms", g == null ? JSONObject.NULL : g)
                    .put("no", look == null ? 0 : look.optInt("no", 0))
                    .put("color", look == null || !look.has("color") ? JSONObject.NULL : look.optString("color"))
                    .put("text", look == null || !look.has("text") ? JSONObject.NULL : look.optString("text")));
        }
        return new JSONObject()
                .put("ok", true)
                .put("version", APP_VERSION)
                .put("clones", clones)
                .put("candidates", candidates)
                .put("blocked", config().blocked)
                .put("custom_labels", customLabelsOn())
                .put("vpn_guard", vpnGuard);
    }

    private static <T> T unwrap(Future<T> f) throws Exception {
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    // ---- Samsung launcher database -----------------------------------------
    // The launcher keeps its desktop in SQLite and only rereads it on start, so
    // every edit is: stop the launcher, back up, edit, restore ownership. The
    // launcher comes back on its own as the home app.

    static SQLiteDatabase openRead(String path) {
        return SQLiteDatabase.openDatabase(path, null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    }

    // WAL flag: without it Android switches the launcher's database out of WAL.
    static SQLiteDatabase openWrite(String path) {
        return SQLiteDatabase.openDatabase(path, null,
                SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS
                        | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
    }

    static boolean launcherQuiet() throws Fail {
        run("/system/bin/am", "force-stop", LAUNCHER);
        for (int i = 0; i < 25; i++) {
            Out r = run("/system/bin/pidof", LAUNCHER);
            if (r.code != 0 || r.text.isEmpty()) {
                return true;
            }
            sleep(40);
        }
        return false;
    }

    /** Active One UI Home workspace database: HomeOnly.db or OneUI.db. */
    static String homeDb() {
        if (exists(ONEUI_DB) && (!exists(HOME_ONLY_DB) || dbMtime(ONEUI_DB) > dbMtime(HOME_ONLY_DB))) {
            try (SQLiteDatabase db = openRead(ONEUI_DB);
                    Cursor c = db.rawQuery("SELECT 1 FROM item_group LIMIT 1", null)) {
                if (c.moveToFirst()) {
                    return ONEUI_DB;
                }
            } catch (Exception ignored) {
            }
        }
        return HOME_ONLY_DB;
    }

    private static long dbMtime(String path) {
        return Math.max(new File(path).lastModified(), new File(path + "-wal").lastModified());
    }

    /** Launcher owns its files again; -wal/-shm we created get the database's label. */
    static void ownLauncherFiles() {
        int uid;
        try {
            uid = launcherUid();
        } catch (Fail e) {
            return;
        }
        for (String path : new String[] {homeDb(), ICON_DB}) {
            byte[] label = null;
            try {
                label = Os.getxattr(path, "security.selinux");
            } catch (Exception ignored) {
            }
            for (String suffix : new String[] {"", "-wal", "-shm"}) {
                String full = path + suffix;
                if (!exists(full)) {
                    continue;
                }
                try {
                    Os.chown(full, uid, uid);
                    Os.chmod(full, 0660);
                    if (label != null && !suffix.isEmpty()) {
                        Os.setxattr(full, "security.selinux", label, 0);
                    }
                } catch (Exception e) {
                    log("own " + full + ": " + e.getMessage());
                }
            }
        }
    }

    static void checkpoint(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            c.moveToFirst();
        }
    }

    static void checkpointDb(String path) {
        if (!exists(path)) {
            return;
        }
        SQLiteDatabase db = openWrite(path);
        try {
            checkpoint(db);
        } finally {
            db.close();
        }
        ownLauncherFiles();
    }

    interface Mutator {
        void apply(SQLiteDatabase home, SQLiteDatabase icon) throws Exception;
    }

    static void editLauncher(Mutator mutator) throws Exception {
        if (!launcherQuiet()) {
            throw new Fail("桌面仍在运行，修改图标失败");
        }
        String homeDb = homeDb();
        checkpointDb(homeDb);
        checkpointDb(ICON_DB);
        List<String[]> backups = new ArrayList<>();
        for (String path : new String[] {homeDb, ICON_DB}) {
            if (exists(path)) {
                String dest = STATE + "/" + new File(path).getName() + ".bak-cloneui";
                copyInto(path, dest);
                backups.add(new String[] {path, dest});
            }
        }
        SQLiteDatabase home = openWrite(homeDb);
        SQLiteDatabase icon = exists(ICON_DB) ? openWrite(ICON_DB) : null;
        try {
            home.beginTransaction();
            if (icon != null) {
                icon.beginTransaction();
            }
            try {
                mutator.apply(home, icon);
                home.setTransactionSuccessful();
                if (icon != null) {
                    icon.setTransactionSuccessful();
                }
            } finally {
                if (icon != null) {
                    icon.endTransaction();
                }
                home.endTransaction();
            }
            checkpoint(home);
            if (icon != null) {
                checkpoint(icon);
            }
        } catch (Exception e) {
            home.close();
            home = null;
            if (icon != null) {
                icon.close();
                icon = null;
            }
            for (String[] b : backups) {
                for (String suffix : new String[] {"-wal", "-shm"}) {
                    new File(b[0] + suffix).delete();
                }
                copyInto(b[1], b[0]);
            }
            throw e;
        } finally {
            if (home != null) {
                home.close();
            }
            if (icon != null) {
                icon.close();
            }
            ownLauncherFiles();
        }
    }

    private static boolean nonZero(Cursor c, int col) {
        return !c.isNull(col) && c.getInt(col) != 0;
    }

    /**
     * (columns, rows) of a desktop page.
     *
     * The page row in item_group carries the grid when the launcher set one;
     * otherwise use the furthest cell any desktop icon occupies.
     */
    static int[] gridSize(SQLiteDatabase home) {
        try (Cursor c = home.rawQuery(
                "SELECT MAX(grid_x), MAX(grid_y) FROM item_group WHERE type='page'", null)) {
            if (c.moveToFirst() && nonZero(c, 0) && nonZero(c, 1)) {
                return new int[] {c.getInt(0), c.getInt(1)};
            }
        }
        try (Cursor c = home.rawQuery(
                "SELECT MAX(position_x + MAX(spanX, 1)), MAX(position_y + MAX(spanY, 1)) "
                        + "FROM item WHERE container_type=0 AND position_x >= 0 AND position_y >= 0", null)) {
            if (c.moveToFirst() && nonZero(c, 0) && nonZero(c, 1)) {
                return new int[] {c.getInt(0), c.getInt(1)};
            }
        }
        return new int[] {4, 6}; // empty desktop: One UI's default
    }

    /** {page, x, y} of the first free desktop cell, or null. */
    static long[] freeCell(SQLiteDatabase home) {
        int[] grid = gridSize(home);
        List<Long> pages = new ArrayList<>();
        try (Cursor c = home.rawQuery("SELECT id FROM item_group WHERE type='page' ORDER BY rank", null)) {
            while (c.moveToNext()) {
                pages.add(c.getLong(0));
            }
        }
        Map<Long, Set<Long>> occupied = new HashMap<>();
        for (long page : pages) {
            occupied.put(page, new HashSet<>());
        }
        try (Cursor c = home.rawQuery(
                "SELECT container_id, position_x, position_y, spanX, spanY FROM item WHERE container_type=0", null)) {
            while (c.moveToNext()) {
                if (c.isNull(0) || c.isNull(1) || c.isNull(2)) {
                    continue;
                }
                Set<Long> cells = occupied.get(c.getLong(0));
                int x = c.getInt(1);
                int y = c.getInt(2);
                if (cells == null || x < 0 || y < 0) {
                    continue;
                }
                int sx = c.isNull(3) || c.getInt(3) <= 0 ? 1 : c.getInt(3);
                int sy = c.isNull(4) || c.getInt(4) <= 0 ? 1 : c.getInt(4);
                for (int dx = 0; dx < sx; dx++) {
                    for (int dy = 0; dy < sy; dy++) {
                        cells.add(cell(x + dx, y + dy));
                    }
                }
            }
        }
        for (long page : pages) {
            for (int y = 0; y < grid[1]; y++) {
                for (int x = 0; x < grid[0]; x++) {
                    if (!occupied.get(page).contains(cell(x, y))) {
                        return new long[] {page, x, y};
                    }
                }
            }
        }
        return null;
    }

    private static long cell(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    static String likePkg(String pkg) {
        String escaped = pkg.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return escaped + "/%";
    }

    static final String ICON_WHERE = "profile_id=? AND component LIKE ? ESCAPE '\\'";

    /** {id, homeup_extend_data} of the clone's desktop icon, or null. */
    static String[] iconRow(int user, String pkg) {
        String homeDb = homeDb();
        if (!exists(homeDb)) {
            return null;
        }
        SQLiteDatabase db = openRead(homeDb);
        try (Cursor c = db.rawQuery("SELECT id, homeup_extend_data FROM item WHERE " + ICON_WHERE,
                new String[] {String.valueOf(user), likePkg(pkg)})) {
            return c.moveToFirst() ? new String[] {c.getString(0), c.getString(1)} : null;
        } finally {
            db.close();
        }
    }

    /** {"user/pkg": {component, custom label or null}} for clone icons on the desktop. */
    static Map<String, String[]> homeRows(List<Integer> users) {
        Map<String, String[]> rows = new HashMap<>();
        String homeDb = homeDb();
        if (users.isEmpty() || !exists(homeDb)) {
            return rows;
        }
        StringBuilder marks = new StringBuilder();
        String[] args = new String[users.size()];
        for (int i = 0; i < users.size(); i++) {
            marks.append(i == 0 ? "?" : ",?");
            args[i] = String.valueOf(users.get(i));
        }
        SQLiteDatabase db = openRead(homeDb);
        try (Cursor c = db.rawQuery("SELECT profile_id, component, homeup_extend_data FROM item "
                + "WHERE profile_id IN (" + marks + ") AND component IS NOT NULL", args)) {
            while (c.moveToNext()) {
                String component = c.getString(1);
                String pkg = component.split("/", 2)[0];
                String label = customLabel(c.getString(2));
                String key = c.getInt(0) + "/" + pkg;
                String[] current = rows.get(key);
                if (current == null || (label != null && current[1] == null)) {
                    rows.put(key, new String[] {component, label});
                }
            }
        } finally {
            db.close();
        }
        return rows;
    }

    /** Put the clone's icon on the first free cell unless it is already there. */
    static boolean placeIcon(int user, String pkg, String component) throws Exception {
        if (iconRow(user, pkg) != null) {
            return true;
        }
        if (component == null) {
            return false;
        }
        String[] where = {String.valueOf(user), likePkg(pkg)};
        editLauncher((home, icon) -> {
            try (Cursor c = home.rawQuery("SELECT 1 FROM item WHERE " + ICON_WHERE, where)) {
                if (c.moveToFirst()) {
                    return;
                }
            }
            long[] spot = freeCell(home);
            if (spot == null) {
                return;
            }
            home.execSQL("INSERT INTO item (type, title, component, profile_id, "
                    + "position_x, position_y, container_type, container_id, "
                    + "app_widget_id, options, color, restored, hidden, spanX, spanY, rank, "
                    + "item_position, alpha, scale, angle, extend_style, homeup_extend_data) "
                    + "VALUES ('app', NULL, ?, ?, ?, ?, 0, ?, "
                    + "-1, 0, -1, 0, 0, -1, -1, 0, '{}', 1, 1, 0, 0, '{}')",
                    new Object[] {component, user, spot[1], spot[2], spot[0]});
        });
        sleep(LAUNCHER_SETTLE_MS);
        return iconRow(user, pkg) != null;
    }

    // Names: One UI Home already has a per-icon custom label (Home Up's "edit
    // label" in the long-press menu). It lives in the icon's own row, in
    // homeup_extend_data.customLabel, so it is per user by construction and
    // survives the launcher rebuilding its icon cache. Writing it here is the
    // same thing the long-press menu does. It shows only while Home Up's
    // home_icon-view_use_custom_label switch is on.

    static String customLabel(String extendJson) {
        try {
            Object value = new JSONObject(extendJson == null ? "{}" : extendJson).opt("customLabel");
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean customLabelsOn() {
        try {
            return readFile(HOMEUP_PREFS).contains("name=\"" + HOMEUP_LABEL_KEY + "\" value=\"true\"");
        } catch (IOException e) {
            return false;
        }
    }

    /** Set (or with label == null, clear) the custom label of the clone's icon. */
    static boolean setLabel(int user, String pkg, String label) throws Exception {
        String[] where = {String.valueOf(user), likePkg(pkg)};
        editLauncher((home, icon) -> {
            List<String[]> rows = new ArrayList<>();
            try (Cursor c = home.rawQuery("SELECT id, homeup_extend_data FROM item WHERE " + ICON_WHERE, where)) {
                while (c.moveToNext()) {
                    rows.add(new String[] {c.getString(0), c.getString(1)});
                }
            }
            for (String[] row : rows) {
                JSONObject data;
                try {
                    data = new JSONObject(row[1] == null ? "{}" : row[1]);
                } catch (JSONException e) {
                    data = new JSONObject();
                }
                if (label == null) {
                    data.remove("customLabel");
                } else {
                    data.put("customLabel", label);
                }
                home.execSQL("UPDATE item SET homeup_extend_data=? WHERE id=?",
                        new Object[] {data.toString(), row[0]});
            }
        });
        sleep(LAUNCHER_SETTLE_MS);
        String[] row = iconRow(user, pkg);
        return row != null && Objects.equals(customLabel(row[1]), label);
    }

    static void dropIcon(int user, String pkg) {
        String[] where = {String.valueOf(user), likePkg(pkg)};
        try {
            editLauncher((home, icon) -> {
                home.execSQL("DELETE FROM item WHERE " + ICON_WHERE, where);
                if (icon != null) {
                    icon.execSQL("DELETE FROM icon WHERE profile_id=? AND component_name LIKE ? ESCAPE '\\'", where);
                }
            });
        } catch (Exception e) {
            log("drop icon " + user + " " + pkg + ": " + e.getMessage());
        }
    }

    /**
     * Icons of users that no longer exist. The launcher keeps those rows (it
     * only skips them while loading), and a later clone given the same user
     * id would inherit them.
     */
    static void dropOrphanIcons() {
        try {
            Out r = run(20, "/system/bin/cmd", "user", "list", "-v");
            Set<Integer> live = new TreeSet<>();
            Matcher m = ANY_USER_RE.matcher(r.text);
            while (m.find()) {
                live.add(Integer.parseInt(m.group(1)));
            }
            if (r.code != 0 || !live.contains(0)) {
                return; // could not read the list: leave the launcher alone
            }
            StringBuilder ids = new StringBuilder();
            for (int id : live) {
                ids.append(ids.length() == 0 ? "" : ",").append(id);
            }
            String keep = "profile_id NOT IN (" + ids + ")";
            try (SQLiteDatabase db = openRead(homeDb());
                    Cursor c = db.rawQuery("SELECT COUNT(*) FROM item WHERE " + keep, null)) {
                if (c.moveToFirst() && c.getInt(0) == 0) {
                    return; // nothing to drop: don't restart the launcher
                }
            }
            editLauncher((home, icon) -> {
                home.execSQL("DELETE FROM item WHERE " + keep);
                if (icon != null) {
                    icon.execSQL("DELETE FROM icon WHERE " + keep);
                }
            });
        } catch (Exception e) {
            log("drop orphan icons: " + e.getMessage());
        }
    }

    // ---- trimming a clone to the packages it needs -------------------------
    // Motorola installs only a short allowlist into a clone user
    // (whitelist-app-clone.xml). This device's allowlist mode is "implicit", so a
    // clone gets every system package nobody listed: about 410, most of them
    // Samsung services that start, schedule jobs and get broadcasts in every
    // clone for nothing. Trimming uninstalls those for that clone only; the main
    // user is not touched, and `untrim` puts them back.
    //
    // Always kept, whatever this list says:
    //   - packages with a system appId (< 10000): platform core, shared uids
    //     (except the few named in TRIM_ALSO)
    //   - runtime resource overlays: removing one changes resources for the
    //     clone's apps
    //   - third-party apps
    static final Set<String> TRIM_KEEP = new HashSet<>(Arrays.asList(
            // Motorola's whitelist-app-clone.xml, in this firmware's names
            "com.samsung.android.permissioncontroller",
            "com.android.printspooler",
            "com.google.android.webview",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.samsung.android.packageinstaller",
            "com.samsung.android.providers.contacts",
            // Providers and services apps call from inside their own user
            "com.android.externalstorage",
            "com.android.providers.calendar",
            "com.android.providers.contactkeys",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.providers.media",
            "com.android.providers.userdictionary",
            "com.sec.android.provider.badge",
            "com.android.intentresolver",            // share sheet
            "com.google.android.photopicker",
            "com.android.credentialmanager",          // passkeys / sign-in sheets
            "com.android.certinstaller",
            "com.android.cameraextensions",
            "com.android.htmlviewer",
            "com.android.pacprocessor",
            "com.android.proxyhandler",
            "com.android.vpndialogs",
            "com.samsung.android.app.soundpicker",
            "com.samsung.android.secsoundpicker",
            "com.android.systemui",
            // Mainline modules and their shims
            "com.google.android.configupdater",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.google.android.modulemetadata",
            // Keyboard fallback when SysGuard's ime hooks are off
            "com.samsung.android.honeyboard",
            "com.samsung.android.sdk.handwriting",
            // Apps in a clone load the updatable GPU driver from their own user
            "com.qualcomm.qti.gpudrivers.canoe.api36",
            "com.samsung.gamedriver.sm8850",
            // OAID for Chinese apps; payment and DRM helpers apps bind to
            "com.samsung.android.deviceidservice",
            "com.unionpay.tsmservice",
            "com.sec.android.app.billing",
            "com.samsung.drmboost",
            // Location providers
            "com.samsung.android.location",
            "com.baidu.location.fused",
            "com.sec.clocationservice",
            "com.sec.location.nfwlocationprivacy",
            // Per-app network control (China firmware)
            "com.sec.android.app.firewall",
            // Knox plumbing behind Samsung's own profiles
            "com.samsung.android.container",
            "com.samsung.android.knox.kpecore",
            "com.samsung.android.knox.sandbox"));
    static final Pattern OVERLAY_NAME = Pattern.compile(
            "auto_generated.*rro|(^|\\.)overlay(\\.|$)|\\.resources?$|\\.res$|\\.(gpu|game)drivers?(\\.|$)");
    // Removed although their appId is a system one (shared uid): each kept a
    // process running in every clone and no clone app uses them. Samsung's
    // own Secure Folder leaves out sm_cn and sm.provider too. (2026-10-11)
    static final Set<String> TRIM_ALSO = new HashSet<>(Arrays.asList(
            "com.samsung.android.forest",             // Digital Wellbeing (uid 1000)
            "com.samsung.android.sm_cn",              // Device care, China (uid 1000)
            "com.samsung.android.sm.provider",        // its data provider (uid 1000)
            "com.samsung.android.applock",            // App lock (uid 1000)
            "com.samsung.android.scpm",               // Samsung cloud policy (appId 5009)
            // Only meaningful in the main user (second pass, 2026-10-11).
            // Telephony, fingerprint payment (SOTER, IFAA, authfw), Knox, DRM,
            // TTS, accessibility, themes and camera services stay.
            // Factory and test tools, *# codes
            "com.sec.android.app.factorykeystring",
            "com.sec.android.app.hwmoduletest",
            "com.sec.android.app.servicemodeapp",
            "com.sec.android.app.uwbtest",
            "com.sec.android.app.wlantest",
            "com.sec.facatfunction",
            "com.sec.factory.camera",
            "com.sem.factoryapp",
            "com.samsung.android.providers.factory",
            "com.sec.android.RilServiceModeApp",
            "com.sec.android.app.parser",
            "com.sec.imslogger",
            "com.sec.automation",
            "com.sec.modem.settings",
            "com.samsung.gpuwatchapp",
            // Firmware update, setup, find my mobile, emergency mode
            "com.wssyncmldm",
            "com.sec.android.soagent",
            "com.sec.android.app.SecSetupWizard",
            "com.samsung.android.smartswitchassistant",
            "com.samsung.android.fmm",
            "com.samsung.android.emergency",
            "com.sec.android.emergencylauncher",
            // Samsung Gallery's vision services (no Gallery in a clone)
            "com.samsung.faceservice",
            "com.samsung.storyservice",
            "com.samsung.petservice",
            "com.samsung.ipservice",
            "com.samsung.cmh",
            // Bixby wake-up, S Pen air command, diagnostics, battery health
            "com.samsung.android.bixby.wakeup",
            "com.samsung.android.aircommandmanager",
            "com.sec.android.diagmonagent",
            "com.samsung.android.dqagent",
            "com.samsung.sait.sohservice",
            // Cross-device: Link to Windows, calls on other devices, car key
            "com.samsung.android.mdx.kit",
            "com.samsung.android.mdecservice",
            "com.samsung.android.dck.timesync",
            // Misc
            "com.samsung.hongbaoassistant",
            "com.samsung.sightcare",
            "com.android.DeviceAsWebcam",
            "com.android.dynsystem",
            "com.samsung.android.stickercenter",
            "com.samsung.android.app.dressroom",
            "com.sec.android.app.quicktool",
            // Camera post-processing / filter / diagnostic daemons (uid 1000)
            "com.samsung.android.globalpostprocmgr",
            "com.samsung.android.gimbaltrackingkit",
            "com.samsung.android.vtcamerasettings",
            "com.samsung.android.provider.filterprovider",
            "com.sec.android.iaft",
            // Shared-UID packages also omitted by Samsung Secure Folder (user 150)
            "com.samsung.android.scloud",
            "com.samsung.android.themecenter",
            "com.sec.android.sdhms",
            "com.samsung.android.beaconmanager",
            "com.sec.android.app.bluetoothagent",
            "com.samsung.android.svcagent",
            "com.sec.bcservice",
            "com.sec.usbsettings",
            "com.samsung.aasaservice",
            "com.samsung.android.bbc.bbcagent",
            "com.samsung.android.da.daagent",
            "com.android.stk",
            "com.android.stk2",
            "com.samsung.android.app.telephonyui",
            "com.samsung.sec.android.application.csc",
            "com.sec.app.RilErrorNotifier",
            // Gallery AI / Wi-Fi AI / display / Quick Share daemons (main user only)
            "com.samsung.android.photoremasterservice",
            "com.samsung.android.motionphoto.app",
            "com.samsung.android.vexfwk.service",
            "com.samsung.android.wifi.ai",
            "com.samsung.android.wifi.intelligence",
            "com.sec.mhs.smarttethering",
            "com.samsung.android.smartface",
            "com.samsung.android.brightnessbackupservice",
            "com.sec.android.smartfpsadjuster",
            "com.qti.snapdragon.qdcm_ff",
            "com.samsung.android.app.sharelive",
            "com.samsung.chn.apps.devicemanagement"));
    static final String TRIM_PAUSE = "0.2"; // seconds between uninstalls; each one is a broadcast in system_server
    static final Pattern SYSTEM_PKG_LINE = Pattern.compile("^package:(\\S+) uid:(\\d+)");
    static final Pattern OVERLAY_LINE = Pattern.compile("^\\s*\\[.\\]\\s*(\\S+)", Pattern.MULTILINE);

    static String trimRecord(int user) {
        return STATE + "/trimmed-" + user + ".txt";
    }

    /** {pkg: appId} of system packages installed for user. */
    static Map<String, Integer> systemPackages(int user) throws Fail {
        Out r = pm(false, "list", "packages", "-s", "-U", "--user", String.valueOf(user));
        Map<String, Integer> found = new HashMap<>();
        for (String line : r.text.split("\n")) {
            Matcher m = SYSTEM_PKG_LINE.matcher(line.trim());
            if (m.find()) {
                found.put(m.group(1), Integer.parseInt(m.group(2)) % 100000);
            }
        }
        return found;
    }

    static Set<String> overlayPackages(int user) throws Fail {
        Out r = run("/system/bin/cmd", "overlay", "list", "--user", String.valueOf(user));
        Set<String> found = new HashSet<>();
        Matcher m = OVERLAY_LINE.matcher(r.text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    static List<String> trimPlan(int user) throws Fail {
        Set<String> keep = new HashSet<>(TRIM_KEEP);
        keep.addAll(config().keepExtra);
        Set<String> overlays = overlayPackages(user);
        List<String> plan = new ArrayList<>();
        for (Map.Entry<String, Integer> e : systemPackages(user).entrySet()) {
            String pkg = e.getKey();
            if ((e.getValue() >= 10000 || TRIM_ALSO.contains(pkg))
                    && !keep.contains(pkg)
                    && !overlays.contains(pkg)
                    && !OVERLAY_NAME.matcher(pkg).find()
                    && !HIDDEN.contains(pkg)) {
                plan.add(pkg);
            }
        }
        Collections.sort(plan);
        return plan;
    }

    /**
     * Run `pm COMMAND --user N PKG` for each pkg in one uid-2000 shell, one at
     * a time. Callers check the outcome by listing packages afterwards: pm's
     * exit status is not reliable, and a timeout may stop it halfway.
     */
    static void shellLoop(String command, int user, List<String> pkgs) throws Fail {
        StringBuilder list = new StringBuilder();
        for (String p : pkgs) {
            list.append(' ').append(quote(p));
        }
        String script = "PATH=/system/bin:/system/xbin; for p in" + list + "; do "
                + "/system/bin/pm " + command + " --user " + user + " \"$p\" >/dev/null 2>&1; "
                + "sleep " + TRIM_PAUSE + "; done";
        try {
            run(60 + 2L * pkgs.size(), "/system/bin/su", "2000", "-c", script);
        } catch (Timeout e) {
            log("pm " + command + " --user " + user + ": timed out");
        }
    }

    static List<String> trimUser(int user, boolean dryRun) throws Fail {
        return trimUser(user, dryRun, Collections.emptySet());
    }

    /** As above, leaving out the packages in skip. */
    static List<String> trimUser(int user, boolean dryRun, Set<String> skip) throws Fail {
        List<String> plan = trimPlan(user);
        plan.removeAll(skip);
        if (dryRun || plan.isEmpty()) {
            return plan;
        }
        shellLoop("uninstall", user, plan);
        Map<String, Integer> left = systemPackages(user);
        if (left.isEmpty()) {
            throw new Fail("读取用户 " + user + " 包列表失败");
        }
        List<String> done = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String p : plan) {
            (left.containsKey(p) ? kept : done).add(p);
        }
        // Packages that refuse pm uninstall --user (e.g. com.osp.app.signin,
        // com.samsung.android.fmm, com.sec.enterprise.knox.cloudmdm.smdms):
        // disable and force-stop them as root so they cannot run in the clone user.
        if (!kept.isEmpty()) {
            StringBuilder dis = new StringBuilder("export PATH=/system/bin:/system/xbin");
            for (String p : kept) {
                dis.append("; /system/bin/cmd package disable-user --user ").append(user).append(' ').append(quote(p)).append(" >/dev/null 2>&1");
                dis.append("; /system/bin/am force-stop --user ").append(user).append(' ').append(quote(p)).append(" >/dev/null 2>&1");
            }
            try {
                run(30, "/system/bin/sh", "-c", dis.toString());
            } catch (Timeout e) {
                log("cmd package disable-user --user " + user + ": timed out");
            }
        }
        appendLines(trimRecord(user), done, true);
        log("trim " + user + ": removed " + done.size() + ", disabled " + kept);
        return done;
    }

    static List<String> untrimUser(int user) throws Fail {
        Set<String> pkgs = new TreeSet<>();
        try {
            for (String line : readFile(trimRecord(user)).split("\n")) {
                if (!line.trim().isEmpty()) {
                    pkgs.add(line.trim());
                }
            }
        } catch (IOException e) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>(pkgs);
        shellLoop("install-existing", user, list);
        // Re-enable any packages that were disabled when uninstall was refused
        for (String p : Arrays.asList(
                "com.osp.app.signin",
                "com.samsung.android.fmm",
                "com.sec.enterprise.knox.cloudmdm.smdms")) {
            run("/system/bin/cmd", "package", "enable", "--user", String.valueOf(user), p);
        }
        Map<String, Integer> present = systemPackages(user);
        List<String> restored = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String p : list) {
            (present.containsKey(p) ? restored : missing).add(p);
        }
        appendLines(trimRecord(user), missing, false);
        log("untrim " + user + ": restored " + restored.size() + " of " + list.size());
        return restored;
    }

    private static void appendLines(String path, List<String> lines, boolean append) throws Fail {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(path, append), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                w.write(line + "\n");
            }
        } catch (IOException e) {
            throw new Fail("写入失败：" + path + "（" + e.getMessage() + "）");
        }
    }

    // ---- choosing and creating clones --------------------------------------
    // Motorola's addAppClone: use the first clone that does not have the app yet;
    // when every clone has it, create one more. Same here, capped by max_clones.

    static final String CLONE_TYPE = "android.os.usertype.profile.CLONE";
    static final Pattern CLONE_NAME = Pattern.compile("克隆(\\d+)");
    static final Pattern CREATED = Pattern.compile("created user id (\\d+)");

    /** {user, 1 if created now else 0}: the first clone without pkg, or a new one. */
    static int[] pickClone(String pkg) throws Fail {
        List<Clone> users = cloneUsers();
        JSONObject meta = syncMeta(users);
        users.sort((a, b) -> {
            JSONObject ma = meta.optJSONObject(String.valueOf(a.id));
            JSONObject mb = meta.optJSONObject(String.valueOf(b.id));
            int na = ma != null && ma.optInt("no", 0) > 0 ? ma.optInt("no") : 1000 + a.id;
            int nb = mb != null && mb.optInt("no", 0) > 0 ? mb.optInt("no") : 1000 + b.id;
            return na != nb ? Integer.compare(na, nb) : Integer.compare(a.id, b.id);
        });
        for (Clone c : users) {
            if (!packageInstalled(c.id, pkg)) {
                return new int[] {c.id, 0};
            }
        }
        int limit = config().maxClones;
        if (users.size() >= limit) {
            throw new Fail(users.size() + " 个分身均已安装该应用（上限 " + limit
                    + "，可在 clone-ui.json 修改 max_clones）");
        }
        return new int[] {createClone(users), 1};
    }

    static int createClone(List<Clone> users) throws Fail {
        // Next number after every clone's number and every 克隆N name, so a
        // renamed clone's number is not handed out again.
        JSONObject meta = syncMeta(users);
        int highest = 0;
        for (Clone c : users) {
            Matcher m = CLONE_NAME.matcher(c.name);
            if (m.matches()) {
                highest = Math.max(highest, Integer.parseInt(m.group(1)));
            }
            JSONObject look = meta.optJSONObject(String.valueOf(c.id));
            if (look != null) {
                highest = Math.max(highest, look.optInt("no", 0));
            }
        }
        String name = "克隆" + (highest + 1);
        Out r = run(120, "/system/bin/pm", "create-user", "--profileOf", "0",
                "--user-type", CLONE_TYPE, name);
        Matcher m = CREATED.matcher(r.text);
        if (!m.find()) {
            throw new Fail(r.text.isEmpty() ? "创建分身失败" : r.text);
        }
        int uid = Integer.parseInt(m.group(1));
        log("created clone " + uid + " " + name);
        // Trim and disable GMS BEFORE starting the user so ~220 Samsung/GMS
        // apps never receive LOCKED_BOOT_COMPLETED / BOOT_COMPLETED.
        List<String> tried = trimPlan(uid);
        trimUser(uid, false);
        try {
            setGms(uid, false); // off by default; the page turns it on per clone
        } catch (Fail e) {
            log("gms off " + uid + ": " + e.getMessage());
        }
        run(120, "/system/bin/am", "start-user", "-w", String.valueOf(uid));
        run("/system/bin/settings", "put", "--user", String.valueOf(uid), "secure", "user_setup_complete", "1");
        // Some packages arrive in a new clone when the user first starts
        // (seen: Samsung Contacts and its overlay); one more pass for those.
        trimUser(uid, false, new HashSet<>(tried));
        return uid;
    }

    /** An empty clone on request (the page's 新建分身), capped by max_clones. */
    static JSONObject opCreate() throws Exception {
        List<Clone> users = cloneUsers();
        int limit = config().maxClones;
        if (users.size() >= limit) {
            throw new Fail("已有 " + users.size() + " 个分身（上限 " + limit + "，可在 clone-ui.json 修改 max_clones）");
        }
        int uid = createClone(users);
        log("create " + uid);
        return new JSONObject().put("ok", true).put("user", uid);
    }

    /**
     * Remove a clone user with all its apps and data. Only profile.CLONE users
     * (checkUser): never the main user or Secure Folder (150). The launcher
     * drops the profile's icons itself; the VPN watcher rereads the clone list.
     */
    static JSONObject opDelete(Object userArg) throws Exception {
        int user = checkUser(userArg);
        Out r = run(120, "/system/bin/pm", "remove-user", "--wait", String.valueOf(user));
        if (cloneIds().contains(user)) {
            throw new Fail(r.text.isEmpty() ? "删除分身失败" : r.text);
        }
        new File(trimRecord(user)).delete();
        dropOrphanIcons();
        log("delete " + user);
        return new JSONObject().put("ok", true);
    }

    // ---- operations --------------------------------------------------------

    static JSONObject opAdd(Object userArg, Object pkgArg) throws Exception {
        String pkg = checkPkg(pkgArg);
        String reason = config().blocked.optString(pkg, "");
        if (!reason.isEmpty()) {
            throw new Fail(reason);
        }
        if (HIDDEN.contains(pkg)) {
            throw new Fail("该应用不可安装至分身");
        }
        if (!new HashSet<>(packagesFor(0, false)).contains(pkg)) {
            throw new Fail("主用户未安装该应用");
        }
        boolean created = false;
        int user;
        if (userArg == null) {
            int[] pick = pickClone(pkg);
            user = pick[0];
            created = pick[1] == 1;
        } else {
            user = checkUser(userArg);
        }
        Out r = pm(true, "install-existing", "--user", String.valueOf(user), pkg);
        if (!packageInstalled(user, pkg)) {
            throw new Fail(r.text.isEmpty() ? "安装失败" : r.text);
        }
        String component = resolveComponent(pkg);
        boolean placed = false;
        if (component != null) {
            try {
                placed = placeIcon(user, pkg, component);
            } catch (Exception e) {
                log("icon " + user + " " + pkg + ": " + e.getMessage());
            }
        }
        log("add " + user + " " + pkg + " icon=" + placed + " created=" + created);
        return new JSONObject().put("ok", true).put("user", user).put("created", created).put("icon", placed);
    }

    static JSONObject opRemove(Object userArg, Object pkgArg) throws Exception {
        int user = checkUser(userArg);
        String pkg = checkPkg(pkgArg);
        if (HIDDEN.contains(pkg)) {
            throw new Fail("该应用不可移除");
        }
        Out r = pm(true, "uninstall", "--user", String.valueOf(user), pkg);
        if (packageInstalled(user, pkg)) {
            throw new Fail(r.text.isEmpty() ? "卸载失败" : r.text);
        }
        dropIcon(user, pkg);
        log("remove " + user + " " + pkg);
        return new JSONObject().put("ok", true);
    }

    static JSONObject opRename(Object userArg, Object pkgArg, Object titleArg) throws Exception {
        int user = checkUser(userArg);
        String pkg = checkPkg(pkgArg);
        if (!(titleArg instanceof String)) {
            throw new Fail("名称不正确");
        }
        String title = ((String) titleArg).trim();
        boolean control = false;
        for (int i = 0; i < title.length(); i++) {
            if (title.charAt(i) < 32) {
                control = true;
            }
        }
        if (title.codePointCount(0, title.length()) > 60 || control) {
            throw new Fail("名称最多 60 个字符");
        }
        if (!packageInstalled(user, pkg)) {
            throw new Fail("该分身未安装此应用");
        }
        if (!placeIcon(user, pkg, resolveComponent(pkg))) {
            throw new Fail("桌面无此分身图标");
        }
        if (!setLabel(user, pkg, title.isEmpty() ? null : title)) {
            throw new Fail("修改桌面名称失败");
        }
        log("rename " + user + " " + pkg + " " + (title.isEmpty() ? "cleared" : "set"));
        return new JSONObject().put("ok", true).put("shown", customLabelsOn());
    }

    static JSONObject opOpen(Object userArg, Object pkgArg) throws Exception {
        int user = checkUser(userArg);
        String pkg = checkPkg(pkgArg);
        if (!packageInstalled(user, pkg)) {
            throw new Fail("该分身未安装此应用");
        }
        Out r = run("/system/bin/am", "start", "--user", String.valueOf(user),
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "-p", pkg);
        if (r.code != 0 || r.text.contains("Error") || r.text.contains("Exception")) {
            throw new Fail(r.text.isEmpty() ? "启动失败" : r.text);
        }
        return new JSONObject().put("ok", true);
    }

    private static String httpGet(String urlStr) throws Fail {
        if (exists("/system/bin/curl")) {
            try {
                Out r = run(15, "/system/bin/curl", "-fsSL", "--max-time", "12",
                        "-H", "Accept: application/vnd.github+json",
                        "-A", "Pluck/" + APP_VERSION, urlStr);
                if (r.code == 0 && !r.text.isEmpty()) {
                    return r.text;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            try {
                if (Security.getProvider("AndroidOpenSSL") == null) {
                    Class<?> cls = Class.forName("com.android.org.conscrypt.OpenSSLProvider");
                    Security.insertProviderAt((Provider) cls.getDeclaredConstructor().newInstance(), 1);
                }
            } catch (Throwable ignored) {
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Pluck/" + APP_VERSION);
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new Fail("检查更新失败 (HTTP " + status + ")");
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }
        } catch (Fail e) {
            throw e;
        } catch (Exception e) {
            throw new Fail("连接 GitHub 失败，请检查网络");
        }
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("[^0-9]+");
        String[] pb = b.replaceFirst("^[vV]", "").split("[^0-9]+");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length && !pa[i].isEmpty() ? Integer.parseInt(pa[i]) : 0;
            int nb = i < pb.length && !pb[i].isEmpty() ? Integer.parseInt(pb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    static JSONObject opCheckUpdate() throws Exception {
        String raw = httpGet(RELEASE_API);
        JSONObject rel;
        try {
            rel = new JSONObject(raw);
        } catch (JSONException e) {
            throw new Fail("解析版本信息失败");
        }
        String tag = rel.optString("tag_name", "").trim();
        if (tag.isEmpty()) {
            throw new Fail("未获取到最新版本信息");
        }
        String latest = tag.replaceFirst("^[vV]", "");
        boolean hasUpdate = compareVersions(latest, APP_VERSION) > 0;
        String htmlUrl = rel.optString("html_url", REPO_URL + "/releases");
        String downloadUrl = htmlUrl;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a != null && a.optString("name", "").endsWith(".apk")) {
                    String dl = a.optString("browser_download_url", "");
                    if (!dl.isEmpty()) {
                        downloadUrl = dl;
                        break;
                    }
                }
            }
        }
        return new JSONObject()
                .put("ok", true)
                .put("current", APP_VERSION)
                .put("latest", latest)
                .put("tag", tag)
                .put("has_update", hasUpdate)
                .put("notes", rel.optString("body", "").trim())
                .put("url", htmlUrl)
                .put("download_url", downloadUrl);
    }

    static JSONObject opOpenUrl(Object urlArg) throws Exception {
        if (!(urlArg instanceof String)) {
            throw new Fail("链接无效");
        }
        String url = ((String) urlArg).trim();
        if (!url.startsWith("https://github.com/wangxiaobo775/Pluck")) {
            throw new Fail("仅支持打开项目 GitHub 链接");
        }
        Out r = run("/system/bin/am", "start", "--user", "0",
                "-a", "android.intent.action.VIEW", "-d", url);
        if (r.code != 0 || r.text.contains("Error") || r.text.contains("Exception")) {
            throw new Fail("无法调起浏览器");
        }
        return new JSONObject().put("ok", true);
    }

    static JSONObject handle(JSONObject req) {
        String op = req.optString("op", "");
        Object user = req.isNull("user") ? null : req.opt("user");
        Object pkg = req.opt("pkg");
        try {
            switch (op) {
                case "ping":
                    return new JSONObject().put("ok", true);
                case "list":
                    return listState();
                case "add":
                    return opAdd(user, pkg);
                case "remove":
                    return opRemove(user, pkg);
                case "rename":
                    return opRename(user, pkg, req.opt("title"));
                case "open":
                    return opOpen(user, pkg);
                case "gms":
                    return opGms(user, req.opt("on"));
                case "vpn":
                    return opVpn(req.opt("on"));
                case "create":
                    return opCreate();
                case "delete":
                    return opDelete(user);
                case "edit":
                    return opEdit(req);
                case "check_update":
                    return opCheckUpdate();
                case "open_url":
                    return opOpenUrl(req.opt("url"));
                default:
                    return error("未知操作");
            }
        } catch (Fail e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log(op + ": " + e);
            return error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    static JSONObject error(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Maintenance from a root shell, outside the page:
     *
     *   trim [--dry-run] [USER...]   default: every clone
     *   untrim USER...
     *   vpn [on|off|status]
     */
    static int cli(String[] argv) throws Fail {
        String cmd = argv[0];
        if (cmd.equals("vpn")) {
            String sub = argv.length > 1 ? argv[1] : "status";
            if (sub.equals("on")) {
                setVpnGuard(true);
            } else if (sub.equals("off")) {
                setVpnGuard(false);
            } else if (!sub.equals("status")) {
                System.err.println("usage: vpn [on|off|status]");
                return 2;
            }
            System.out.println("vpn_guard: " + (vpnGuardEnabled() ? "on" : "off"));
            return 0;
        }
        boolean dryRun = false;
        List<Integer> users = new ArrayList<>();
        for (int i = 1; i < argv.length; i++) {
            if (argv[i].equals("--dry-run")) {
                dryRun = true;
            } else if (argv[i].matches("\\d+")) {
                users.add(Integer.parseInt(argv[i]));
            }
        }
        if (cmd.equals("trim")) {
            Set<Integer> clones = cloneIds();
            for (int user : users.isEmpty() ? new ArrayList<>(clones) : users) {
                if (!clones.contains(user)) {
                    System.err.println(user + " is not a clone profile");
                    return 1;
                }
                List<String> pkgs = trimUser(user, dryRun);
                System.out.println("user " + user + ": " + (dryRun ? "would remove " : "removed ") + pkgs.size());
                if (dryRun) {
                    System.out.println("  " + String.join(" ", pkgs));
                }
            }
            return 0;
        }
        if (cmd.equals("untrim") && !users.isEmpty()) {
            for (int user : users) {
                System.out.println("user " + user + ": restored " + untrimUser(user).size());
            }
            return 0;
        }
        System.err.println("usage: trim [--dry-run] [USER...] | untrim USER... | vpn [on|off|status]");
        return 2;
    }
}
