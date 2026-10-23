package top.niunaijun.blackbox.core.system.am;

import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Blaze addition (not upstream): which display each guest package opens on. Blaze Phone owns a VirtualDisplay per live
 * guest; the launch intent carries its id in {@link #EXTRA} once, and every new task that guest starts afterwards
 * (in this server process) opens on the same display, so nothing of the guest ever shows on the user's screen.
 */
public final class GuestDisplays {
    public static final String EXTRA = "blaze.phone.launch_display";
    private static final Map<String, Integer> sDisplays = new HashMap<>();

    private GuestDisplays() {}

    /** Reads and strips the display extra from a launch intent (the guest never sees it). */
    public static synchronized void take(Intent intent, String packageName) {
        if (intent == null || !intent.hasExtra(EXTRA)) return;
        int id = intent.getIntExtra(EXTRA, -1);
        intent.removeExtra(EXTRA);
        if (packageName == null) return;
        if (id > 0) sDisplays.put(packageName, id); else sDisplays.remove(packageName);
    }

    public static synchronized void forget(String packageName) { sDisplays.remove(packageName); }

    public static synchronized int displayOf(String packageName) {
        Integer id = packageName == null ? null : sDisplays.get(packageName);
        return id == null ? -1 : id;
    }

    /** ActivityOptions that open on the guest's display, or null when it has none (upstream behaviour). */
    public static Bundle options(String packageName) {
        int id = displayOf(packageName);
        if (id <= 0) return null;
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchDisplayId(id);
        return o.toBundle();
    }
}
