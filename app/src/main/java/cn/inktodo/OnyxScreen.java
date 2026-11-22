package cn.inktodo;

import android.content.Context;
import android.os.Build;
import android.view.View;
import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import com.onyx.android.sdk.api.device.screensaver.ScreenResourceManager;
import java.util.Locale;

/** Optional, view-scoped BOOX integration. No system refresh or front-light settings are changed. */
final class OnyxScreen {
    static boolean sdkAvailable(){return true;}
    private boolean active;
    private View configuredView;
    private String mode = "普通 Android 刷新";
    private int changes, fullRequests;
    private int interval = 8, changesSinceFull;
    static boolean isBoox() {
        String identity = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL).toLowerCase(Locale.ROOT);
        return identity.contains("onyx") || identity.contains("boox") || identity.contains("leaf5");
    }
    void configure(View root, boolean enabled, int interval) {
        release(root);
        if (!isBoox() || !enabled) return;
        active = false;
        try {
            this.interval = Math.max(1, interval);
            boolean regal = EpdController.supportRegal();
            UpdateMode update = regal ? UpdateMode.REGAL : UpdateMode.GU;
            active = EpdController.setViewDefaultUpdateMode(root, update);
            if (active) {
                configuredView = root;
                mode = "文字局刷请求 · 每 " + this.interval + " 次内容变化请求全刷";
            } else mode = "固件未接受刷新设置，已回退普通刷新";
        } catch (Throwable ignored) { active = false; mode = "文石接口不可用，已回退普通刷新"; }
    }
    void changed(View view) {
        if (!active) return;
        try {
            changes++;
            changesSinceFull++;
            if (changesSinceFull >= interval) clean(view);
            else EpdController.invalidate(view, UpdateMode.GU);
        }
        catch (Throwable ignored) { active = false; mode = "文石接口不可用，已回退普通刷新"; }
    }
    boolean clean(View view) {
        if (!active) { view.invalidate(); return false; }
        try { EpdController.repaintEveryThing(UpdateMode.GC); fullRequests++; changesSinceFull = 0; return true; }
        catch (Throwable ignored) { active = false; view.invalidate(); return false; }
    }
    void release(View view) {
        if (configuredView != null) try { EpdController.resetViewUpdateMode(configuredView); } catch (Throwable ignored) { }
        configuredView = null;
        active = false;
        mode = "普通 Android 刷新";
    }
    boolean active() { return active; }
    String description() { return mode; }
    int changeRequests() { return changes; }
    int fullRequests() { return fullRequests; }
    static boolean setScreensaver(Context context, String path) {
        return setScreensaver(context,path,true);
    }
    static boolean setScreensaver(Context context,String path,boolean showHint){
        if (!isBoox()) return false;
        try { return ScreenResourceManager.setScreensaver(context, path, showHint); }
        catch (Throwable ignored) { return false; }
    }
}
