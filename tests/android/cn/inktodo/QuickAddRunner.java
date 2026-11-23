package cn.inktodo;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated creation/migration and real dialog/keyboard tests; never writes account tasks. */
public final class QuickAddRunner extends Instrumentation {
    int count;StringBuilder log=new StringBuilder();Activity activity;Store fixture;Object oldStore;boolean oldDemo;
    interface Work{void run()throws Exception;}
    void check(boolean yes,String label){if(!yes)throw new AssertionError(label);count++;log.append("PASS ").append(label).append('\n');}
    void main(Work work)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);waitForIdleSync();}
    Field field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    void invoke(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    JSONObject reply()throws Exception{return new JSONObject().put("sync_token","fixture").put("items",new JSONArray()).put("projects",new JSONArray());}
    JSONObject ack(Core.Operation op,String id)throws Exception{return reply().put("sync_status",new JSONObject().put(op.uuid,"ok")).put("temp_id_mapping",new JSONObject().put(op.taskId,id));}
    void seed(Store store)throws Exception{store.reset();store.merge(reply().put("projects",new JSONArray().put(new JSONObject().put("id","ib").put("name","Inbox").put("inbox_project",true))),Collections.emptyList());}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Context app=getTargetContext();Bundle out=new Bundle();Store store=null;AtomicBoolean busy=null;
        SharedPreferences saver=app.getSharedPreferences("screensaver",0);Map<String,?> saverBefore=saver.getAll();
        SharedPreferences settings=app.getSharedPreferences("settings",0);Map<String,?> settingsBefore=settings.getAll();
        try{
            app.deleteDatabase("quick-add-verification.db");store=new Store(app,"quick-add-verification.db");seed(store);LocalDate day=LocalDate.now();
            try{store.addTask("  ",day);throw new AssertionError("blank accepted");}catch(IllegalArgumentException expected){check(store.snapshot().operations.isEmpty(),"blank input never creates an operation");}
            String temp=store.addTask("  应急记录  ",day);Store.Snapshot snapshot=store.snapshot();Core.Operation first=snapshot.operations.get(0);
            check(snapshot.tasks.size()==1&&snapshot.tasks.get(0).content.equals("应急记录"),"queued creation appears immediately with trimmed content");
            check(snapshot.tasks.get(0).projectId.equals("ib")&&snapshot.tasks.get(0).due.equals(day.toString()),"creation belongs to inbox and today's date");
            check(snapshot.hidden().isEmpty(),"pending creation remains visible");
            check(!store.enqueue(snapshot.tasks.get(0),"close"),"temporary task cannot be completed before acknowledgement");
            store.close();store=new Store(app,"quick-add-verification.db");check(store.snapshot().operations.get(0).uuid.equals(first.uuid),"creation survives process/database reopen");
            List<Core.Operation> sent=store.claim();String args=sent.get(0).args;
            check(!store.discard(first.uuid),"possibly sent creation cannot be discarded unsafely");
            store.merge(reply(),sent);check(store.claim().get(0).uuid.equals(first.uuid)&&store.claim().get(0).args.equals(args),"uncertain creation retries identical UUID and args");
            store.merge(reply().put("sync_status",new JSONObject().put(first.uuid,"ok")),sent);
            check(store.snapshot().operations.size()==1&&store.snapshot().tasks.get(0).id.equals(temp),"missing ID mapping retains draft and retry identity");
            store.merge(ack(first,"server-created"),sent);
            check(store.snapshot().operations.isEmpty()&&store.snapshot().tasks.size()==1&&store.snapshot().tasks.get(0).id.equals("server-created"),"acknowledgement replaces temporary row with real ID without duplicates");
            check(store.enqueue(store.snapshot().tasks.get(0),"delete"),"confirmed creation supports ordinary operations");seed(store);
            temp=store.addTask("server wins",day);sent=store.claim();Core.Operation op=sent.get(0);
            JSONObject serverItem=new JSONObject(op.args).put("id","real").put("content","server content");
            store.merge(ack(op,"real").put("full_sync",true).put("items",new JSONArray().put(serverItem)),sent);
            check(store.snapshot().tasks.size()==1&&store.snapshot().tasks.get(0).content.equals("server content"),"full sync uses authoritative returned item");
            String during=store.addTask("created while request in flight",day);
            store.merge(reply().put("full_sync",true),Collections.emptyList());
            check(store.snapshot().tasks.stream().anyMatch(t->t.id.equals(during)),"full sync preserves creation queued during network request");
            op=store.claim().get(0);store.merge(reply().put("sync_status",new JSONObject().put(op.uuid,new JSONObject().put("http_code",400).put("error","invalid"))),Collections.singletonList(op));
            check(store.claim().isEmpty()&&store.snapshot().tasks.size()==1,"rejected creation retains visible draft without automatic resubmission");
            check(store.discard(op.uuid)&&store.snapshot().tasks.isEmpty(),"removing failed creation removes only local draft");
            store.addTask("cancel",day);check(store.discard(store.snapshot().operations.get(0).uuid)&&store.snapshot().tasks.isEmpty(),"unsent creation can be cancelled");
            store.reset();store.seedDemo();int before=store.snapshot().tasks.size();store.addTask("demo creation",day);store.demoSync();
            check(store.snapshot().tasks.size()==before+1&&store.snapshot().operations.isEmpty(),"demo creation converges locally without network");
            store.close();store=null;app.deleteDatabase("quick-add-migration.db");
            SQLiteDatabase legacy=app.openOrCreateDatabase("quick-add-migration.db",0,null);
            legacy.execSQL("CREATE TABLE operations(uuid TEXT PRIMARY KEY, task_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, state TEXT NOT NULL, title TEXT NOT NULL, error TEXT NOT NULL, created INTEGER NOT NULL)");
            legacy.execSQL("INSERT INTO operations VALUES('old-uuid','old-task','close','queued','legacy','','1')");legacy.setVersion(2);legacy.close();
            Store upgraded=new Store(app,"quick-add-migration.db");try(android.database.Cursor c=upgraded.getReadableDatabase().rawQuery("SELECT uuid,args FROM operations",null)){
                check(c.moveToFirst()&&c.getString(0).equals("old-uuid")&&c.getString(1).isEmpty(),"v2 migration preserves existing queued actions");
            }upgraded.close();
            saver.edit().putBoolean("configured",false).commit();
            activity=startActivitySync(new Intent().setClassName(app,"cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            busy=(AtomicBoolean)field("BUSY").get(null);long end=android.os.SystemClock.uptimeMillis()+45000;
            while(busy.get()&&android.os.SystemClock.uptimeMillis()<end)android.os.SystemClock.sleep(100);
            if(!busy.compareAndSet(false,true))throw new AssertionError("sync busy");
            fixture=new Store(app,"quick-add-ui.db");seed(fixture);oldStore=field("store").get(activity);oldDemo=field("demo").getBoolean(activity);
            main(()->{field("store").set(activity,fixture);field("demo").setBoolean(activity,true);invoke("build");View plus=activity.findViewById(android.R.id.content).findViewWithTag("add_task");check(plus!=null,"dashboard shows accessible add icon");plus.performClick();});
            EditText input=(EditText)getUiAutomationInput();
            main(()->{check(input.hasFocus(),"dialog focuses task input automatically");check(input.getText().length()==0,"dialog starts empty");});
            android.os.SystemClock.sleep(1200);
            main(()->check(input.getRootWindowInsets()!=null&&input.getRootWindowInsets().isVisible(WindowInsets.Type.ime()),"opening dialog automatically shows real software keyboard"));
            main(()->{((Button)input.getRootView().findViewById(android.R.id.button1)).performClick();check(input.getError()!=null&&fixture.snapshot().operations.isEmpty(),"empty submit keeps dialog open with validation");});
            main(()->{input.setText("只在隔离库中添加");((Button)input.getRootView().findViewById(android.R.id.button1)).performClick();check(fixture.snapshot().tasks.size()==1&&fixture.snapshot().operations.size()==1,"one submit creates exactly one local task");check(field("filter").get(activity).equals("today"),"submit returns to today to show created task");});
            main(()->invoke("addTask"));EditText cancelledInput=(EditText)getUiAutomationInput();
            main(()->{cancelledInput.setText("取消的内容");((Button)cancelledInput.getRootView().findViewById(android.R.id.button2)).performClick();check(fixture.snapshot().operations.size()==1,"cancel never queues another task");});
            out.putString("stream",log+"SUCCESS "+count+" quick add assertions\n");
        }catch(Throwable error){out.putString("stream",log+"FAIL "+android.util.Log.getStackTraceString(error));}
        finally{
            if(activity!=null)try{main(()->{if(oldStore!=null){field("store").set(activity,oldStore);field("demo").setBoolean(activity,oldDemo);}activity.finish();});}catch(Exception ignored){}
            if(busy!=null)busy.set(false);SettingsHierarchyRunner.restore(settings,settingsBefore);SettingsHierarchyRunner.restore(saver,saverBefore);
            if(store!=null)store.close();if(fixture!=null)fixture.close();for(String name:new String[]{"quick-add-verification.db","quick-add-migration.db","quick-add-ui.db"})app.deleteDatabase(name);
        }
        finish(out.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
    }
    View getUiAutomationInput()throws Exception{
        // Dialog roots are separate windows; find the focused editor via WindowManagerGlobal.
        Class<?> global=Class.forName("android.view.WindowManagerGlobal");Object manager=global.getMethod("getInstance").invoke(null);Field roots=global.getDeclaredField("mViews");roots.setAccessible(true);
        for(Object root:(List<?>)roots.get(manager)){View input=((View)root).findViewWithTag("add_task_input");if(input!=null)return input;}
        throw new AssertionError("task dialog editor missing");
    }
}
