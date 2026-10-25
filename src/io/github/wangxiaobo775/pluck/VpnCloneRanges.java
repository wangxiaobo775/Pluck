package io.github.wangxiaobo775.pluck;

import android.content.Context;
import android.provider.Settings;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Vpn.createUserAndRestrictedProfilesRanges(userId, allowed, disallowed):
 * when the VPN belongs to the main user, add each clone profile that has
 * per-clone VPN enabled (Settings.Global "cloneui_vpn_users").
 */
final class VpnCloneRanges extends SafeHook {
    private static volatile Method addUserToRanges;

    VpnCloneRanges() {
        super("vpn-clones");
    }

    private static Set<Integer> enabledVpnClones(Object vpnObj) {
        try {
            Context ctx = (Context) XposedHelpers.getObjectField(vpnObj, "mContext");
            if (ctx == null) {
                return null;
            }
            String raw = Settings.Global.getString(ctx.getContentResolver(), "cloneui_vpn_users");
            if (raw == null || "null".equals(raw)) {
                return null; // not configured yet: default to all clones
            }
            Set<Integer> out = new HashSet<>();
            for (String part : raw.trim().split("[\\s,]+")) {
                if (!part.isEmpty()) {
                    try {
                        out.add(Integer.parseInt(part));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    void after(MethodHookParam param) throws ReflectiveOperationException {
        Object[] args = param.args;
        Object ranges = param.getResult();
        if (!(args[0] instanceof Integer) || (Integer) args[0] != Clones.PARENT || !(ranges instanceof Set)) {
            return;
        }
        int[] clones = Clones.ofParent(Clones.PARENT);
        if (clones.length == 0) {
            return;
        }
        if (addUserToRanges == null) {
            addUserToRanges = param.thisObject.getClass().getMethod("addUserToRanges",
                    Set.class, int.class, List.class, List.class);
        }
        Set<Integer> allowedClones = enabledVpnClones(param.thisObject);
        List<Integer> added = new ArrayList<>();
        for (int id : clones) {
            if (allowedClones == null || allowedClones.contains(id)) {
                addUserToRanges.invoke(param.thisObject, ranges, id, args[1], args[2]);
                added.add(id);
            }
        }
        Log.count("vpn ranges include clones", added.toString());
    }
}
