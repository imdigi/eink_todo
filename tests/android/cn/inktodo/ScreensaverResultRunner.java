package cn.inktodo;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.concurrent.*;

/** Observe only screensaver-related toast results while the authorized UI test applies the image. */
public final class ScreensaverResultRunner extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle out=new Bundle();StringBuilder messages=new StringBuilder();CountDownLatch done=new CountDownLatch(1);
        try{
            getUiAutomation().setOnAccessibilityEventListener(event -> {
                if(event.getEventType()!=AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)return;
                String pkg=String.valueOf(event.getPackageName());
                if(!pkg.equals("cn.inktodo.app") && !pkg.startsWith("com.onyx"))return;
                String text=event.getText().toString();
                if(text.contains("屏保") || text.contains("保存成功") || text.contains("设置成功") || text.contains("失败")) {
                    synchronized(messages){messages.append(pkg).append(": ").append(text).append('\n');}
                    if(!pkg.equals("cn.inktodo.app"))done.countDown();
                }
            });
            startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Bundle status=new Bundle();status.putString("stream","Listening for screensaver result\n");sendStatus(1,status);
            click("设置");click("工具与账号");click("待办屏保预览…");click("设为系统屏保");
            boolean received=done.await(55,TimeUnit.SECONDS);
            android.os.SystemClock.sleep(800);
            synchronized(messages){out.putString("stream","\n"+messages+"System result observed: "+received+"\n");}
            finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}
    }
    private void click(String label) throws Exception {
        long until=android.os.SystemClock.uptimeMillis()+10000;
        while(android.os.SystemClock.uptimeMillis()<until){
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)){
                if(!"cn.inktodo.app".contentEquals(String.valueOf(node.getPackageName())) || !label.contentEquals(String.valueOf(node.getText())))continue;
                AccessibilityNodeInfo target=node;
                while(target!=null && !target.isClickable())target=target.getParent();
                if(target!=null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                    android.os.SystemClock.sleep(500);return;
                }
            }
            android.os.SystemClock.sleep(200);
        }
        throw new IllegalStateException("App control unavailable: "+label);
    }
}
