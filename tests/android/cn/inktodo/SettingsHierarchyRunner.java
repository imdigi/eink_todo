package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;

/** Exercises fixed-size hierarchical settings and immediate persistence, restoring device preferences. */
public class SettingsHierarchyRunner extends Instrumentation {
    Activity activity;ActivityMonitor picker;int checks;StringBuilder log=new StringBuilder();
    interface Checked{void run()throws Exception;}
    void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;log.append("PASS ").append(label).append('\n');}
    Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    void main(Checked code)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{code.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new Exception(error[0]);waitForIdleSync();}
    View decor(){return activity.getWindow().getDecorView();}
    boolean isOpen()throws Exception{Object sheet=field("settingsPage");if(sheet==null)return false;Field active=sheet.getClass().getDeclaredField("active");active.setAccessible(true);return active.getBoolean(sheet);}
    void close()throws Exception{Object sheet=field("settingsPage");Method method=sheet.getClass().getDeclaredMethod("close");method.setAccessible(true);method.invoke(sheet);}
    void open()throws Exception{Method method=MainActivity.class.getDeclaredMethod("settings");method.setAccessible(true);method.invoke(activity);}
    void page(String name)throws Exception{Object sheet=field("settingsPage");Method method=sheet.getClass().getDeclaredMethod("navigate",String.class);method.setAccessible(true);method.invoke(sheet,name);}
    <T extends View>T view(String tag)throws Exception{return decor().findViewWithTag(tag);}
    Rect bounds()throws Exception{View shell=view("settings_shell");return new Rect(shell.getLeft(),shell.getTop(),shell.getRight(),shell.getBottom());}
    boolean hasText(View node,String text){if(node instanceof TextView&&text.equals(((TextView)node).getText().toString()))return true;if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++)if(hasText(((ViewGroup)node).getChildAt(i),text))return true;return false;}
    void click(String label)throws Exception{
        long end=android.os.SystemClock.uptimeMillis()+12000;
        while(android.os.SystemClock.uptimeMillis()<end){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)){
            if(!label.contentEquals(String.valueOf(node.getText()))&&!label.contentEquals(String.valueOf(node.getContentDescription()))&&!String.valueOf(node.getText()).startsWith(label+"   ›"))continue;while(node!=null&&!node.isClickable())node=node.getParent();if(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){android.os.SystemClock.sleep(200);return;}}
            android.os.SystemClock.sleep(100);
        }throw new AssertionError("Missing control: "+label);
    }
    void waitImport()throws Exception{long until=android.os.SystemClock.uptimeMillis()+10000;while(field("fontImportResult")!=null&&android.os.SystemClock.uptimeMillis()<until)android.os.SystemClock.sleep(100);waitForIdleSync();}
    static void restore(SharedPreferences prefs,Map<String,?> data){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:data.entrySet()){Object v=entry.getValue();String key=entry.getKey();if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);else if(v instanceof Integer)edit.putInt(key,(Integer)v);else if(v instanceof Long)edit.putLong(key,(Long)v);else if(v instanceof Float)edit.putFloat(key,(Float)v);else if(v instanceof String)edit.putString(key,(String)v);}edit.commit();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();Context app=getTargetContext();SharedPreferences prefs=app.getSharedPreferences("settings",0),saver=app.getSharedPreferences("screensaver",0);
        Map<String,?> original=prefs.getAll(),saverOriginal=saver.getAll();Set<String> before=new HashSet<>();File[] existing=FontStore.directory(app).listFiles();if(existing!=null)for(File file:existing)before.add(file.getName());
        Store fixture=null;Object originalStore=null;boolean originalDemo=false;
        try{
            // Prevent fixture UI changes from submitting a real wallpaper; restore it after verification.
            saver.edit().putBoolean("configured",false).putString("last_path","").commit();
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long until=android.os.SystemClock.uptimeMillis()+45000;while(((java.util.concurrent.atomic.AtomicBoolean)field("BUSY")).get()&&android.os.SystemClock.uptimeMillis()<until)android.os.SystemClock.sleep(100);
            fixture=new Store(app,"settings-hierarchy-verification.db");fixture.seedDemo();
            org.json.JSONArray scrollTasks=new org.json.JSONArray();for(int i=0;i<24;i++)scrollTasks.put(new org.json.JSONObject().put("id","settings-scroll-"+i).put("project_id","reading").put("content","保留滚动位置验证 "+i).put("description","设置页面返回后应保持当前位置").put("child_order",20+i));
            fixture.merge(new org.json.JSONObject().put("sync_token","fixture").put("items",scrollTasks).put("projects",new org.json.JSONArray()),Collections.emptyList());originalStore=field("store");originalDemo=(Boolean)field("demo");Store isolated=fixture;
            main(()->{set("store",isolated);set("demo",true);set("filter","project");set("project","reading");Method build=MainActivity.class.getDeclaredMethod("build");build.setAccessible(true);build.invoke(activity);});
            main(()->{((ScrollView)field("taskScroll")).scrollTo(0,180);((Map<String,Set<String>>)field("branchFolds")).put("preserve-fixture",new HashSet<>(Collections.singleton("folded")));});
            main(()->open());
            Rect[] initial={null};main(()->{initial[0]=bounds();check(initial[0].height()>300,"full-page settings allocates the screen content height");check(!hasText(decor(),"保存")&&!hasText(decor(),"取消"),"settings home has no global Save or Cancel action");check(((View)field("root")).getForeground()==null,"full-page settings does not require a background overlay");check(view("settings_entry:display")!=null&&view("settings_entry:screensaver")!=null,"home offers feature entries instead of main tabs");});
            for(String name:new String[]{"display","sync","screensaver","eink","tools","account","root"}){main(()->page(name));main(()->check(initial[0].equals(bounds()),"full-page bounds on page: "+name));}
            main(()->check(initial[0].equals(bounds()),"returning from all six feature pages preserves screen bounds"));
            main(()->{page("display");activity.onBackPressed();check(isOpen()&&((TextView)view("settings_heading")).getText().toString().equals("设置"),"Android Back navigates from feature page to settings home");});
            main(()->page("display"));main(()->{CompoundButton square=view("settings_toggle:square_buttons");square.performClick();check(prefs.getBoolean("square_buttons",true)==square.isChecked(),"display toggle persists without saving");});
            main(()->{Spinner size=view("settings_font_size");size.setSelection(2);});main(()->check(prefs.getInt("font",0)==26,"task size is saved immediately"));
            main(()->page("sync"));main(()->{Spinner interval=view("settings_sync_interval");interval.setSelection(2);});main(()->check(prefs.getInt("interval",0)==15,"sync interval is saved immediately"));
            main(()->page("screensaver"));main(()->{Spinner project=view("screensaver_important_project");check(project.getCount()>1,"important project list uses cached demo projects");project.setSelection(1);});
            main(()->check(!prefs.getString(ScreensaverProjects.key(true),"").isEmpty(),"important project selection persists without a save button"));
            main(()->{page("root");view("settings_back").performClick();});main(()->{check(!isOpen(),"settings root Back returns directly to dashboard");check(field("filter").equals("project")&&field("project").equals("reading"),"return preserves original project view");check(((Map<?,?>)field("branchFolds")).containsKey("preserve-fixture"),"return preserves task expansion state");check(((View)field("root")).getForeground()==null,"dashboard has no overlay after returning");});main(()->check(((ScrollView)field("taskScroll")).getScrollY()==180,"return preserves dashboard scroll position"));main(()->open());main(()->{page("sync");});main(()->{Spinner interval=view("settings_sync_interval");check(interval.getSelectedItemPosition()==2,"reopened page restores immediate settings");});
            picker=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()))return new ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.parse("content://cn.inktodo.tests.font/Roboto-Regular.ttf")));return null;}};addMonitor(picker);
            for(boolean snapshot:new boolean[]{false,true}){
                String key=snapshot?ScreensaverFont.FILE:"font_file",name=snapshot?ScreensaverFont.NAME:"font_name",old=prefs.getString(key,"");
                main(()->page(snapshot?"screensaver":"display"));click(snapshot?"导入屏保字体 TTF…":"导入 TTF…");waitImport();
                check(old.equals(prefs.getString(key,"")),"font import remains preview-only until explicit use (snapshot="+snapshot+")");click("取消");check(old.equals(prefs.getString(key,"")),"cancelled font preview retains original selection (snapshot="+snapshot+")");
                click(snapshot?"导入屏保字体 TTF…":"导入 TTF…");waitImport();click("使用此字体");check(!prefs.getString(key,"").isEmpty()&&!old.equals(prefs.getString(key,"")),"confirmed font is applied immediately (snapshot="+snapshot+")");
                click(snapshot?"恢复默认宋体":"系统默认");check(prefs.getString(key,"bad").isEmpty(),"default font restores immediately (snapshot="+snapshot+")");
            }
            main(()->{page("screensaver");});click("待办屏保预览…");click("取消");main(()->check(isOpen(),"closing wallpaper preview preserves settings navigation"));
            main(()->check(initial[0].equals(bounds()),"font and wallpaper previews return to same settings screen bounds"));
            out.putString("stream",log+"SUCCESS "+checks+" hierarchical settings assertions\n");
        }catch(Throwable error){out.putString("stream",log+"FAIL "+android.util.Log.getStackTraceString(error));}
        finally{
            if(picker!=null)removeMonitor(picker);Object oldStore=originalStore;boolean oldDemo=originalDemo;
            if(activity!=null)try{main(()->{if(isOpen())close();if(oldStore!=null){set("store",oldStore);set("demo",oldDemo);}activity.finish();});}catch(Exception ignored){}
            restore(prefs,original);restore(saver,saverOriginal);File[] after=FontStore.directory(app).listFiles();if(after!=null)for(File file:after)if(!before.contains(file.getName()))file.delete();
            if(fixture!=null)fixture.close();app.deleteDatabase("settings-hierarchy-verification.db");
        }
        finish(out.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
    }
}
