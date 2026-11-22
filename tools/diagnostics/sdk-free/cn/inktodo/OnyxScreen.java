package cn.inktodo;
import android.content.Context;
import android.view.View;

/** Diagnostic replacement: no imports, references, initialization or calls to vendor libraries. */
final class OnyxScreen {
    static boolean sdkAvailable(){return false;}
    static boolean isBoox(){return false;}
    void configure(View root,boolean enabled,int interval){}
    void changed(View view){}
    boolean clean(View view){view.invalidate();return false;}
    void release(View view){}
    boolean active(){return false;}
    String description(){return "无文石 SDK 对照版";}
    int changeRequests(){return 0;}
    int fullRequests(){return 0;}
    static boolean setScreensaver(Context context,String path){return false;}
    static boolean setScreensaver(Context context,String path,boolean showHint){return false;}
}
