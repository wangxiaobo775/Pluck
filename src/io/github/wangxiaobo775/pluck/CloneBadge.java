package io.github.wangxiaobo775.pluck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;

import de.robv.android.xposed.XposedHelpers;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Clone badges on One UI Home: the clone's avatar (colour and up to two
 * characters, set in the 应用分身 page) instead of the same blue dot on every
 * clone's icons.
 *
 * Runs in the launcher process only (scope: One UI Home), so notifications,
 * recents and Settings keep the stock badge. The page writes
 *   settings put global cloneui_badges '{"11":{"color":"#fa7b17","text":"2"}}'
 * and a user missing from that map keeps the stock badge. The launcher bakes
 * badged icons into Icon.db, so the page clears those rows when the map changes.
 *
 * Stock geometry (ic_dualapp_badge drawn over the whole icon): a circle centred
 * at 80.5% of the icon, radius 18.5% of it.
 */
final class CloneBadge {
    static final String LAUNCHER = "com.sec.android.app.launcher";
    static final String KEY = "cloneui_badges";
    private static final String APM = "android.app.ApplicationPackageManager";

    private static volatile String lastRaw;
    private static volatile Map<Integer, Look> looks = Collections.emptyMap();
    private static boolean installed;
    private static Method badgedDrawable;

    private CloneBadge() {}

    static final class Look {
        final int color;
        final String text;

        Look(int color, String text) {
            this.color = color;
            this.text = text;
        }
    }

    static synchronized void install(ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;
        Log.i("launcher process");
        try {
            badgedDrawable = XposedHelpers.findClass(APM, cl).getDeclaredMethod("getBadgedDrawable",
                    Drawable.class, Drawable.class, Rect.class, boolean.class);
            badgedDrawable.setAccessible(true);
        } catch (Throwable t) {
            Log.e("clone-badge getBadgedDrawable", t);
            return;
        }
        MainHook.hook(cl, APM, "getUserBadgedIcon", new IconHook());
        MainHook.hook(cl, APM, "getUserBadgeForDensity", new BadgeHook());
        MainHook.hook(cl, APM, "getUserBadgeForDensityNoBackground", new BadgeHook());
    }

    /** The look for this user, or null to leave the stock badge. */
    static Look lookFor(Object pm, UserHandle user) {
        if (user == null) {
            return null;
        }
        Context ctx = (Context) XposedHelpers.getObjectField(pm, "mContext");
        String raw = Settings.Global.getString(ctx.getContentResolver(), KEY);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (!raw.equals(lastRaw)) {
            Map<Integer, Look> parsed = new HashMap<>();
            try {
                JSONObject all = new JSONObject(raw);
                for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String key = it.next();
                    JSONObject o = all.optJSONObject(key);
                    if (o == null) {
                        continue;
                    }
                    parsed.put(Integer.parseInt(key),
                            new Look(Color.parseColor(o.optString("color", "#1d92ff")), o.optString("text", "")));
                }
            } catch (Exception e) {
                Log.count("clone-badge bad json", String.valueOf(e));
            }
            looks = parsed;
            lastRaw = raw;
            Log.i("clone-badge looks for users " + parsed.keySet());
        }
        return looks.get(userId(user));
    }

    /** UserHandle.getIdentifier() is hidden from the SDK. */
    static int userId(UserHandle user) {
        return XposedHelpers.getIntField(user, "mHandle");
    }

    /** getUserBadgedIcon(icon, user): the icon with our badge in the corner. */
    static final class IconHook extends SafeHook {
        IconHook() {
            super("clone-badge");
        }

        @Override
        void before(MethodHookParam param) throws Throwable {
            Drawable icon = (Drawable) param.args[0];
            Look look = lookFor(param.thisObject, (UserHandle) param.args[1]);
            if (icon == null || look == null) {
                return;
            }
            Drawable badge = new Badge(look, false, -1);
            param.setResult(badgedDrawable.invoke(param.thisObject, icon, badge, null, true));
            Log.count("clone-badge icon", "user " + userId((UserHandle) param.args[1]));
        }
    }

    /** getUserBadgeForDensity(user, dpi): a badge on its own, filling its bounds. */
    static final class BadgeHook extends SafeHook {
        BadgeHook() {
            super("clone-badge");
        }

        @Override
        void after(MethodHookParam param) {
            Drawable stock = (Drawable) param.getResult();
            if (stock == null) {
                return;
            }
            Look look = lookFor(param.thisObject, (UserHandle) param.args[0]);
            if (look != null) {
                param.setResult(new Badge(look, true, stock.getIntrinsicWidth()));
            }
        }
    }

    /** A filled circle in the clone's colour with its text in white. */
    static final class Badge extends Drawable {
        private final Look look;
        private final boolean full;
        private final int size;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Badge(Look look, boolean full, int size) {
            this.look = look;
            this.full = full;
            this.size = size;
            fill.setColor(look.color);
            ring.setColor(0xffffffff);
            text.setColor(0xffffffff);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            if (Typeface.DEFAULT != null) {
                text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float side = Math.min(b.width(), b.height());
            if (side <= 0) {
                return;
            }
            float cx;
            float cy;
            float r;
            if (full) {
                cx = b.exactCenterX();
                cy = b.exactCenterY();
                r = side / 2f;
            } else {
                cx = b.left + b.width() * 0.805f;
                cy = b.top + b.height() * 0.805f;
                r = side * 0.185f;
                // Thin white edge so the badge stays visible on an icon of the same colour.
                canvas.drawCircle(cx, cy, r * 1.06f, ring);
            }
            canvas.drawCircle(cx, cy, r, fill);
            String s = look.text;
            if (s == null || s.isEmpty()) {
                return;
            }
            int n = s.codePointCount(0, s.length());
            text.setTextSize(r * (n <= 1 ? 1.15f : 0.85f));
            float max = r * 1.55f;
            float w = text.measureText(s);
            if (w > max) {
                text.setTextSize(text.getTextSize() * max / w);
            }
            Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            ring.setAlpha(alpha);
            text.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {}

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
