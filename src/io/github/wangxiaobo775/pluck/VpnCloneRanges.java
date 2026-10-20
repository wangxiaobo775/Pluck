package io.github.wangxiaobo775.pluck;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Vpn.createUserAndRestrictedProfilesRanges(userId, allowed, disallowed):
 * when the VPN belongs to the main user, add every clone profile too.
 *
 * Samsung already does the same thing here for its own Dual Messenger
 * profile. Every place that builds a VPN's uid ranges goes through this
 * method: agentConnect (VPN comes up), setVpnForcedLocked (always-on
 * "block connections without VPN") and updateAppExclusionList. So clones
 * are routed into the VPN the moment it connects, with no gap, and an
 * always-on lockdown also blocks the clones while the VPN is down.
 *
 * Per-app allow/deny lists are passed through; addUserToRanges resolves the
 * package names inside each clone and clears the calling identity itself.
 */
final class VpnCloneRanges extends SafeHook {
    private static volatile Method addUserToRanges;

    VpnCloneRanges() {
        super("vpn-clones");
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
        for (int id : clones) {
            addUserToRanges.invoke(param.thisObject, ranges, id, args[1], args[2]);
        }
        Log.count("vpn ranges include clones", Arrays.toString(clones));
    }
}
