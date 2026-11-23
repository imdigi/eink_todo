package cn.inktodo;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

/** Settings picker regression with a test-only document provider; restores snapshot font selection. */
public final class ScreensaverFontUiRunner extends Instrumentation {
    int count;StringBuilder log=new StringBuilder();Activity activity;ActivityMonitor picker;
    void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;log.append("PASS ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();SharedPreferences prefs=getTargetContext().getSharedPreferences("settings",0);
        String oldFile=prefs.getString(ScreensaverFont.FILE,""),oldName=prefs.getString(ScreensaverFont.NAME,"默认宋体"),body=prefs.getString("font_file","");
        java.util.Set<String> files=new java.util.HashSet<>();java.io.File[] before=FontStore.directory(getTargetContext()).listFiles();if(before!=null)for(java.io.File f:before)files.add(f.getName());
        try{
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            picker=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()))return new ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.parse("content://cn.inktodo.tests.font/Roboto-Regular.ttf")));return null;}};addMonitor(picker);
            click("设置");click("工具与账号");click("导入屏保字体 TTF…");waitText("屏保字体：Roboto-Regular.ttf");
            check(prefs.getString(ScreensaverFont.FILE,"").equals(oldFile),"snapshot picker previews without changing saved selection");
            click("取消");check(prefs.getString(ScreensaverFont.FILE,"").equals(oldFile),"cancel preserves snapshot font");
            click("设置");click("工具与账号");click("导入屏保字体 TTF…");waitText("屏保字体：Roboto-Regular.ttf");click("保存");
            String imported=prefs.getString(ScreensaverFont.FILE,"");check(!imported.isEmpty()&&!imported.equals(oldFile),"Save commits independent snapshot font");
            check(body.equals(prefs.getString("font_file","")),"snapshot settings leave body font unchanged");
            click("设置");click("工具与账号");click("恢复默认宋体");waitText("屏保字体：默认宋体");click("取消");
            check(prefs.getString(ScreensaverFont.FILE,"").equals(imported),"cancelled default reset keeps imported selection");
            click("设置");click("工具与账号");click("恢复默认宋体");click("保存");check(prefs.getString(ScreensaverFont.FILE,"bad").isEmpty(),"saved default reset restores Songti");
            check(picker.getHits()==2,"snapshot import uses Android document picker twice");
            result.putString("stream",log+"SUCCESS "+count+" snapshot font UI assertions\n");
        }catch(Throwable e){result.putString("stream",log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{
            if(picker!=null)removeMonitor(picker);if(activity!=null)runOnMainSync(()->activity.finish());
            prefs.edit().putString(ScreensaverFont.FILE,oldFile).putString(ScreensaverFont.NAME,oldName).commit();
            java.io.File[] after=FontStore.directory(getTargetContext()).listFiles();if(after!=null)for(java.io.File f:after)if(!files.contains(f.getName()))f.delete();ScreensaverController.requestNow(getTargetContext());
        }
        finish(result.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }    AccessibilityNodeInfo walk(AccessibilityNodeInfo n,String text){if(n==null)return null;if("cn.inktodo.app".contentEquals(String.valueOf(n.getPackageName()))&&text.contentEquals(String.valueOf(n.getText())))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=walk(n.getChild(i),text);if(found!=null)return found;}return null;}
    AccessibilityNodeInfo waitText(String text)throws Exception{long end=android.os.SystemClock.uptimeMillis()+12000;while(android.os.SystemClock.uptimeMillis()<end){AccessibilityNodeInfo n=walk(getUiAutomation().getRootInActiveWindow(),text);if(n!=null)return n;android.os.SystemClock.sleep(150);}throw new IllegalStateException("Missing font control: "+text);}
    void click(String text)throws Exception{AccessibilityNodeInfo n=waitText(text);while(n!=null&&!n.isClickable())n=n.getParent();if(n==null||!n.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Cannot click "+text);android.os.SystemClock.sleep(250);}
}
