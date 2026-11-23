package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.widget.*;
import java.lang.reflect.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.json.*;

/** Isolated project picker, snapshot selection and rendering tests; no real task writes. */
public final class ImportantProjectRunner extends Instrumentation {
    Activity activity;Store fixture;Object originalStore;boolean originalDemo;String originalFilter,originalProject;
    int checks;StringBuilder log=new StringBuilder();
    interface Checked{void run()throws Exception;}
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;log.append("PASS ").append(name).append('\n');}
    Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void field(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    void call(String name)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(activity);}
    void main(Checked code)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{code.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new Exception(error[0]);}
    AlertDialog settings()throws Exception{call("settings");return (AlertDialog)field("settingsDialog");}
    Spinner picker(AlertDialog dialog){return dialog.getWindow().getDecorView().findViewWithTag("screensaver_important_project");}
    JSONObject task(String id,String parent,String title,String description,int order)throws Exception{return new JSONObject().put("id",id).put("project_id","goals").put("parent_id",parent).put("content",title).put("description",description).put("child_order",order);}
    void merge(JSONArray items,JSONArray projects)throws Exception{fixture.merge(new JSONObject().put("sync_token","fixture").put("items",items).put("projects",projects),Collections.emptyList());}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();Context app=getTargetContext();SharedPreferences prefs=app.getSharedPreferences("settings",0);
        SharedPreferences enginePrefs=app.getSharedPreferences("important-project-engine-verification",0);
        String key=ScreensaverProjects.key(true),old=prefs.getString(key,""),real=prefs.getString(ScreensaverProjects.key(false),"");
        try{
            fixture=new Store(app,"important-project-verification.db");fixture.reset();
            JSONArray projects=new JSONArray().put(new JSONObject().put("id","goals").put("name","个人提升"))
                .put(new JSONObject().put("id","inbox").put("name","Inbox").put("inbox_project",true))
                .put(new JSONObject().put("id","archived").put("name","归档项目").put("is_archived",true))
                .put(new JSONObject().put("id","deleted").put("name","删除项目").put("is_deleted",true));
            JSONArray tasks=new JSONArray().put(task("g1","","持续阅读，建立自己的知识体系","当前阶段：每晚读十页，写下一个收获。",1))
                .put(task("g2","","练好英语，让表达更从容","当前阶段：每周完成两次口语练习。",2))
                .put(task("g3","","定期复盘，把所学用到生活里","",3))
                .put(task("child","g1","不应出现在右侧的子任务","子任务说明",1))
                .put(new JSONObject().put("id","day").put("project_id","inbox").put("content","整理今天的工作计划").put("due",new JSONObject().put("date",LocalDate.now().toString())))
                .put(new JSONObject().put("id","late").put("project_id","inbox").put("content","回复昨日的邮件").put("due",new JSONObject().put("date",LocalDate.now().minusDays(1).toString())));
            merge(tasks,projects);prefs.edit().remove(key).commit();
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long until=android.os.SystemClock.uptimeMillis()+45000;while(((java.util.concurrent.atomic.AtomicBoolean)field("BUSY")).get()&&android.os.SystemClock.uptimeMillis()<until)android.os.SystemClock.sleep(100);
            main(()->{
                originalStore=field("store");originalDemo=(Boolean)field("demo");originalFilter=(String)field("filter");originalProject=(String)field("project");
                field("store",fixture);field("demo",true);field("filter","today");field("project","");
                AlertDialog dialog=settings();Spinner choices=picker(dialog);
                check(choices.getCount()==2&&choices.getItemAtPosition(1).equals("个人提升"),"picker includes only existing active project and disable option, excluding inbox archived and deleted");
                choices.setSelection(1);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });waitForIdleSync();
            check(prefs.getString(key,"").isEmpty(),"cancel leaves important selection unchanged");
            main(()->{AlertDialog dialog=settings();picker(dialog).setSelection(1);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});waitForIdleSync();
            check(prefs.getString(key,"").equals("goals"),"Save stores selected confirmed project ID");
            check(prefs.getString(ScreensaverProjects.key(false),"").equals(real),"demo project selection cannot overwrite real account selection");
            main(()->{AlertDialog dialog=settings();check(picker(dialog).getSelectedItemPosition()==1,"reopening settings restores selected project");dialog.dismiss();});
            Store.Snapshot snapshot=fixture.snapshot();SnapshotModel model=ScreensaverProjects.model(app,true,snapshot,"today","");
            check(model.hasImportant()&&model.goals.size()==3,"configured snapshot loads only three root goals from selected project");
            check(ScreensaverProjects.model(app,true,snapshot,"project","goals").filter.equals("today"),"important mode uses date groups even when previewing from project view");
            check(model.goals.get(0).description.contains("十页"),"goal descriptions are retained for stage reminders");
            Bitmap sample=SnapshotImage.create(1264,1680,model,0,ScreensaverFont.load(app).face);ScreensaverExport.validate(ScreensaverExport.encode(sample),1264,1680);
            File sampleFile=new File(app.getExternalFilesDir("Pictures"),"important-project-sample.png");try(FileOutputStream file=new FileOutputStream(sampleFile)){sample.compress(Bitmap.CompressFormat.PNG,100,file);}sample.recycle();
            check(true,"two-column sample renders a valid nonblank image with stage descriptions");
            enginePrefs.edit().clear().putBoolean("configured",true).putBoolean("auto_update",true).putBoolean("source_demo",true).putString("filter","today").commit();
            int[] submissions={0};ScreensaverController engine=new ScreensaverController(app,enginePrefs,demo->fixture.snapshot(),(path,hint)->{submissions[0]++;return true;},"InkTodoImportantVerification");
            check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED,"automatic controller submits configured two-column image");
            String hash=model.fingerprint(1264,1680,"");merge(new JSONArray().put(task("g1","","持续阅读，建立自己的知识体系","当前阶段：改为每天整理一段读书笔记。",1)),new JSONArray());snapshot=fixture.snapshot();
            check(!hash.equals(ScreensaverProjects.model(app,true,snapshot,"today","").fingerprint(1264,1680,"")),"server-delivered stage description updates invalidate snapshot");
            check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&submissions[0]==2,"cloud description change regenerates and resubmits right panel");
            check(engine.refreshNow()==ScreensaverController.Outcome.UNCHANGED&&submissions[0]==2,"unchanged important project avoids duplicate SDK submission");
            merge(new JSONArray(),new JSONArray().put(new JSONObject().put("id","goals").put("name","个人提升").put("is_archived",true)));snapshot=fixture.snapshot();
            check(!ScreensaverProjects.model(app,true,snapshot,"today","").hasImportant(),"archived selected project automatically falls back to single column");
            main(()->{AlertDialog dialog=settings();check(picker(dialog).getCount()==1&&picker(dialog).getSelectedItemPosition()==0,"unavailable selected project is not offered in picker");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});waitForIdleSync();check(prefs.getString(key,"bad").isEmpty(),"Save can explicitly disable important panel");
            out.putString("stream",log+"SUCCESS "+checks+" important project assertions\nSAMPLE_PATH="+sampleFile.getAbsolutePath()+"\n");
        }catch(Throwable error){out.putString("stream",log+"FAIL "+android.util.Log.getStackTraceString(error));}
        finally{
            prefs.edit().putString(key,old).commit();enginePrefs.edit().clear().commit();
            try{android.net.Uri collection=android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);java.util.List<android.net.Uri> owned=new ArrayList<>();
                try(android.database.Cursor cursor=app.getContentResolver().query(collection,new String[]{"_id","owner_package_name"},"relative_path=?",new String[]{"Pictures/InkTodoImportantVerification/"},null)){while(cursor!=null&&cursor.moveToNext())if(app.getPackageName().equals(cursor.getString(1)))owned.add(ContentUris.withAppendedId(collection,cursor.getLong(0)));}
                for(android.net.Uri uri:owned)app.getContentResolver().delete(uri,null,null);
            }catch(Exception ignored){}
            if(activity!=null)try{main(()->{if(originalStore!=null){field("store",originalStore);field("demo",originalDemo);field("filter",originalFilter);field("project",originalProject);}activity.finish();});}catch(Exception ignored){}
            if(fixture!=null)fixture.close();app.deleteDatabase("important-project-verification.db");
        }
        finish(out.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
    }
}
