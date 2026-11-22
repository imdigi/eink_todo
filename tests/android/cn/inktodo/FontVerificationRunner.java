package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;

/** Exercises real document import callbacks; restores original font and all other preferences. */
public final class FontVerificationRunner extends Instrumentation {
    int count;StringBuilder log=new StringBuilder();Activity activity;ActivityMonitor picker;
    void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;log.append("PASS ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();SharedPreferences prefs=getTargetContext().getSharedPreferences("settings",0);
        java.util.Map<String,?> original=prefs.getAll();FontStore.Choice copy=null;
        java.util.Set<String> filesBefore=new java.util.HashSet<>();File[] before=FontStore.directory(getTargetContext()).listFiles();if(before!=null)for(File f:before)filesBefore.add(f.getName());
        try{
            try{FontStore.copy(getTargetContext(),new ByteArrayInputStream(new byte[40]),"fake.ttf");throw new AssertionError("invalid accepted");}catch(IOException expected){check(true,"invalid TTF rejected");}
            try{FontStore.copy(getTargetContext(),new ByteArrayInputStream(new byte[40]),"fake.otf");throw new AssertionError("extension accepted");}catch(IOException expected){check(true,"unsupported extension rejected");}
            check(FontStore.load(getTargetContext(),"../bad.ttf","bad").file.isEmpty(),"invalid stored path safely falls back");
            try(InputStream in=new FileInputStream("/system/fonts/Roboto-Regular.ttf")){copy=FontStore.copy(getTargetContext(),in,"Roboto-Regular.ttf");}
            check(copy.face!=null && new File(FontStore.directory(getTargetContext()),copy.file).length()>0,"real TTF copied to private immutable file");
            check(FontStore.load(getTargetContext(),copy.file,copy.name).file.equals(copy.file),"private font reloads without source URI");
            final FontStore.Choice fixture=copy;
            final Throwable[] error={null};runOnMainSync(()->{try{
                CountButton b=new CountButton(getTargetContext(),"示例",28,true,fixture.face);b.setBackgroundColor(Color.BLACK);b.layout(0,0,500,100);
                Bitmap image=Bitmap.createBitmap(500,100,Bitmap.Config.ARGB_8888);b.draw(new Canvas(image));int white=0;
                for(int y=0;y<100;y++)for(int x=350;x<500;x++)if(image.getPixel(x,y)==Color.WHITE)white++;
                check(white>10,"selected count draws visible white glyphs with custom font");image.recycle();
            }catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new RuntimeException(error[0]);
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click("设置");
            picker=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){
                if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()))return new ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.parse("content://cn.inktodo.tests.font/Roboto-Regular.ttf")));
                return null;
            }};addMonitor(picker);
            click("导入 TTF…");waitText("当前字体：Roboto-Regular.ttf");
            check(picker.getHits()==1,"TTF import opens Android document picker");
            String saved=prefs.getString("font_file","");
            check(saved.equals((original.containsKey("font_file")?original.get("font_file"):"")),"import remains preview-only before Save");
            click("取消");check(prefs.getString("font_file","").equals(saved),"cancel preserves active font");
            click("设置");click("导入 TTF…");waitText("当前字体：Roboto-Regular.ttf");click("保存");
            String imported=prefs.getString("font_file","");check(!imported.isEmpty()&&!imported.equals(saved),"Save commits imported font selection");
            runOnMainSync(()->activity.finish());android.os.SystemClock.sleep(250);
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click("设置");waitText("当前字体：Roboto-Regular.ttf");check(true,"fresh activity restores imported font");
            click("系统默认");waitText("当前字体：系统默认");click("取消");check(prefs.getString("font_file","").equals(imported),"cancelled reset keeps imported font");
            click("设置");click("系统默认");click("保存");check(prefs.getString("font_file","bad").isEmpty(),"Save restores system font");
            result.putString("stream","\n"+log+"SUCCESS "+count+" font assertions\n");
        }catch(Throwable e){result.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{
            if(picker!=null)removeMonitor(picker);if(activity!=null)runOnMainSync(()->activity.finish());
            SharedPreferences.Editor edit=prefs.edit().clear();for(java.util.Map.Entry<String,?> item:original.entrySet()){
                Object v=item.getValue();String key=item.getKey();if(v instanceof String)edit.putString(key,(String)v);else if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);else if(v instanceof Integer)edit.putInt(key,(Integer)v);else if(v instanceof Long)edit.putLong(key,(Long)v);else if(v instanceof Float)edit.putFloat(key,(Float)v);
            }edit.commit();File[] after=FontStore.directory(getTargetContext()).listFiles();if(after!=null)for(File f:after)if(!filesBefore.contains(f.getName()))f.delete();
        }
        finish(result.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    AccessibilityNodeInfo walk(AccessibilityNodeInfo n,String text){if(n==null)return null;if("cn.inktodo.app".contentEquals(String.valueOf(n.getPackageName()))&&text.contentEquals(String.valueOf(n.getText())))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=walk(n.getChild(i),text);if(found!=null)return found;}return null;}
    AccessibilityNodeInfo waitText(String text)throws Exception{long end=android.os.SystemClock.uptimeMillis()+12000;while(android.os.SystemClock.uptimeMillis()<end){AccessibilityNodeInfo n=walk(getUiAutomation().getRootInActiveWindow(),text);if(n!=null)return n;android.os.SystemClock.sleep(150);}throw new IllegalStateException("Missing font control: "+text);}
    void click(String text)throws Exception{AccessibilityNodeInfo n=waitText(text);while(n!=null&&!n.isClickable())n=n.getParent();if(n==null||!n.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Cannot click "+text);android.os.SystemClock.sleep(250);}
}
