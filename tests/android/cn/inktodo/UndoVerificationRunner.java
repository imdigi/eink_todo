package cn.inktodo;

import android.app.*;
import android.content.*;
import android.database.sqlite.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated completion commands and real three-second UI windows; no test command reaches Todoist. */
public final class UndoVerificationRunner extends Instrumentation {
    Store fixture,second;Activity activity;Object originalStore;boolean originalDemo;String originalFilter,originalProject;SyncRetry policy;
    int checks;StringBuilder log=new StringBuilder();
    void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;log.append("PASS ").append(label).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    JSONObject item(String id,String parent,Integer due,boolean repeat)throws Exception{
        String title=id.equals("root")?"周末计划":id.equals("a")?"整理阅读笔记":id.equals("b")?"给植物浇水":id;
        JSONObject task=new JSONObject().put("id",id).put("content",title).put("project_id","p").put("parent_id",parent).put("child_order",id.hashCode());
        if(due!=null)task.put("due",new JSONObject().put("date",LocalDate.now().plusDays(due).toString()).put("is_recurring",repeat));return task;
    }
    void seed()throws Exception{
        fixture.reset();fixture.merge(new JSONObject().put("sync_token","fixture").put("full_sync",true)
            .put("projects",new JSONArray().put(new JSONObject().put("id","p").put("name","撤回示例")))
            .put("items",new JSONArray().put(item("root","",null,false)).put(item("child","root",0,false)).put(item("child2","root",0,false))
                .put(item("old","root",-1,false)).put(item("future","root",1,false)).put(item("a","",0,false)).put(item("b","",0,false)).put(item("repeat","",0,true))),Collections.emptyList());
    }
    Core.Task task(String id)throws Exception{for(Core.Task t:fixture.snapshot().tasks)if(t.id.equals(id))return t;throw new IllegalStateException(id);}
    List<String> uuids(List<Core.Operation> ops){List<String> ids=new ArrayList<>();for(Core.Operation op:ops)ids.add(op.uuid);return ids;}
    void migration()throws Exception{
        String name="undo-legacy-verification.db";getTargetContext().deleteDatabase(name);
        SQLiteOpenHelper legacy=new SQLiteOpenHelper(getTargetContext(),name,null,1){
            public void onCreate(SQLiteDatabase db){
                db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY,payload TEXT NOT NULL)");db.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY,payload TEXT NOT NULL)");db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
                db.execSQL("CREATE TABLE operations(uuid TEXT PRIMARY KEY,task_id TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,state TEXT NOT NULL,title TEXT NOT NULL,error TEXT NOT NULL,created INTEGER NOT NULL)");
            }
            public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
        };
        SQLiteDatabase db=legacy.getWritableDatabase();db.execSQL("INSERT INTO tasks VALUES(?,?)",new Object[]{"legacy",item("legacy","",0,false).toString()});
        db.execSQL("INSERT INTO meta VALUES('cursor','legacy-cursor')");db.execSQL("INSERT INTO operations VALUES('original-uuid','legacy','close','sending','legacy','',1)");legacy.close();
        Store upgraded=new Store(getTargetContext(),name);
        try{Store.Snapshot s=upgraded.snapshot();check(s.tasks.size()==1&&s.cursor.equals("legacy-cursor")&&s.operations.get(0).uuid.equals("original-uuid"),"schema upgrade preserves cached tasks, cursor and in-flight UUID");check(upgraded.getReadableDatabase().getVersion()==2&&upgraded.releaseAllUndo()==0,"schema upgrade adds undo tables without inventing pending undo actions");}
        finally{upgraded.close();getTargetContext().deleteDatabase(name);}
    }
    interface Checked{void run()throws Exception;}
    void main(Checked code)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{code.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void field(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    void call(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    void complete(String id)throws Exception{Method m=MainActivity.class.getDeclaredMethod("act",Core.Task.class,String.class);m.setAccessible(true);m.invoke(activity,task(id),"close");}
    Store.UndoAction active()throws Exception{return (Store.UndoAction)field("activeUndo");}
    int[] actionPositions()throws Exception{
        ViewGroup footer=((View)field("root")).findViewWithTag("sidebar_footer");int[] settings=new int[2],sync=new int[2];footer.getChildAt(0).getLocationOnScreen(settings);((View)field("syncButton")).getLocationOnScreen(sync);
        return new int[]{settings[0],settings[1],sync[0],sync[1],((View)field("status")).getHeight()};
    }
    void undoClick()throws Exception{PopupWindow popup=(PopupWindow)field("undoPopup");check(popup!=null&&popup.isShowing(),"undo action is a visible interactive popup");popup.getContentView().findViewWithTag("completion_undo_action").performClick();}
    void waitActive(String title)throws Exception{
        long until=android.os.SystemClock.uptimeMillis()+6500;
        while(android.os.SystemClock.uptimeMillis()<until){boolean[] matches={false};main(()->{Store.UndoAction a=active();matches[0]=title==null?a==null:a!=null&&a.title.equals(title);});if(matches[0])return;android.os.SystemClock.sleep(50);}
        throw new AssertionError("Undo window did not advance to "+title);
    }
    void sample()throws Exception{
        int[] xy=new int[2],size=new int[2];main(()->{View bar=((PopupWindow)field("undoPopup")).getContentView();bar.getLocationOnScreen(xy);size[0]=bar.getWidth();size[1]=bar.getHeight();});
        Bitmap image=getUiAutomation().takeScreenshot();Bitmap crop=Bitmap.createBitmap(image,xy[0],xy[1],size[0],size[1]);
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir("Pictures"),"completion-undo-sample.png"))){crop.compress(Bitmap.CompressFormat.PNG,100,out);}crop.recycle();image.recycle();
    }
    void ui()throws Exception{
        seed();activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long end=android.os.SystemClock.uptimeMillis()+45000;while(((AtomicBoolean)field("BUSY")).get()&&android.os.SystemClock.uptimeMillis()<end)android.os.SystemClock.sleep(100);
        check(!((AtomicBoolean)field("BUSY")).get(),"initial account read finishes before isolated UI injection");
        main(()->{
            originalStore=field("store");originalDemo=(Boolean)field("demo");originalFilter=(String)field("filter");originalProject=(String)field("project");
            field("store",fixture);field("demo",true);field("filter","project");field("project","p");policy=(SyncRetry)field("DEMO_RETRY");policy.reset();call("build");
        });android.os.SystemClock.sleep(150);final int[][] anchored={null};
        main(()->{
            anchored[0]=actionPositions();policy.failed(429,System.currentTimeMillis()+120000,System.currentTimeMillis());
            complete("a");complete("b");check(active().title.equals("整理阅读笔记"),"multiple completions queue instead of replacing the first prompt");
            check(fixture.claim().isEmpty(),"manual or automatic sync cannot claim either queued undo action");
        });android.os.SystemClock.sleep(150);main(()->check(Arrays.equals(anchored[0],actionPositions()),"sidebar buttons and status height stay fixed when completion counts and longer failure text appear"));sample();
        main(()->{undoClick();check(active().title.equals("给植物浇水"),"clicking undo advances immediately to the next queued prompt");check(!fixture.snapshot().hidden().contains("a")&&fixture.snapshot().hidden().contains("b"),"undo restores exactly the selected task while the next action remains pending");undoClick();check(active()==null&&fixture.snapshot().operations.isEmpty(),"undoing both prompts leaves no completion commands");policy.reset();call("render");});
        android.os.SystemClock.sleep(150);main(()->{check(Arrays.equals(anchored[0],actionPositions()),"sidebar buttons remain anchored after pending counts and failure text clear");policy.failed(429,System.currentTimeMillis()+120000,System.currentTimeMillis());});
        main(()->{complete("a");complete("b");});long start=android.os.SystemClock.uptimeMillis();waitActive("给植物浇水");
        check(android.os.SystemClock.uptimeMillis()-start>=2750,"first queued prompt remains for approximately three seconds");
        main(()->{List<Core.Operation> sent=fixture.claim();check(sent.size()==1&&sent.get(0).taskId.equals("a"),"expired first action becomes sendable while the second stays held");});
        android.os.SystemClock.sleep(2100);main(()->check(active()!=null&&active().title.equals("给植物浇水"),"second queued prompt receives its own full display interval"));waitActive(null);
        check(fixture.claim().size()==2,"both actions become sendable after their individual windows end");seed();
        main(()->{call("render");Method m=MainActivity.class.getDeclaredMethod("completeGroup",Core.Task.class,String.class);m.setAccessible(true);m.invoke(activity,task("root"),"today:due_today");check(active().title.equals("周末计划")&&active().completed==2,"scoped parent completion offers one parent-level undo prompt");undoClick();check(fixture.snapshot().operations.isEmpty(),"parent-level UI undo cancels the entire scoped child batch");complete("a");check(fixture.snapshot().pending()==1,"completion is saved durably before the prompt appears");call("commitUndoQueue");check(active()==null&&fixture.claim().size()==1,"leaving the UI closes prompts and releases completion without dropping it");});
    }
    @Override public void onStart(){Bundle out=new Bundle();
        try{
            migration();fixture=new Store(getTargetContext(),"undo-verification.db");seed();second=new Store(getTargetContext(),"undo-verification.db");
            Store.UndoAction parent=fixture.enqueueUndo(task("root"));check(second.claim().isEmpty(),"another database helper cannot send a parent within its undo window");
            check(Core.visible(fixture.snapshot().tasks,fixture.snapshot().hidden(),"project","p",LocalDate.now(),ZoneId.systemDefault()).stream().noneMatch(t->t.id.equals("root")||t.parentId.equals("root")),"parent completion optimistically hides its subtree");
            check(fixture.cancelUndo(parent.id)&&fixture.snapshot().tasks.size()==8&&fixture.snapshot().operations.isEmpty(),"parent undo restores the whole unchanged subtree without cloud writes");
            Store.UndoAction a=fixture.enqueueUndo(task("a")),child=fixture.enqueueUndo(task("child"));
            check(fixture.cancelUndo(child.id)&&fixture.snapshot().hidden().equals(Collections.singleton("a")),"child undo leaves unrelated completion untouched");fixture.cancelUndo(a.id);
            Store.UndoAction group=fixture.enqueueGroupUndo(task("root"),"today:due_today",LocalDate.now(),ZoneId.systemDefault());
            check(group.completed==2&&fixture.snapshot().hidden().equals(new HashSet<>(Arrays.asList("child","child2"))),"date-group parent holds only this group's matching child commands");
            a=fixture.enqueueUndo(task("a"));check(fixture.cancelUndo(group.id)&&fixture.snapshot().hidden().equals(Collections.singleton("a")),"scoped parent undo atomically restores its batch and preserves unrelated dates and actions");fixture.cancelUndo(a.id);
            group=fixture.enqueueGroupUndo(task("root"),"today:due_today",LocalDate.now(),ZoneId.systemDefault());String member=fixture.snapshot().operations.get(0).uuid;
            check(fixture.discard(member)&&fixture.snapshot().operations.isEmpty(),"operation-record cancellation also respects the parent batch boundary");
            Store.UndoAction repeat=fixture.enqueueUndo(task("repeat"));String due=task("repeat").due;
            check(fixture.cancelUndo(repeat.id)&&task("repeat").due.equals(due),"undo of a recurring task never advances its due date");
            repeat=fixture.enqueueUndo(task("repeat"));String uuid=fixture.snapshot().operations.get(0).uuid;check(fixture.releaseUndo(repeat.id)&&!fixture.cancelUndo(repeat.id),"expired completion cannot be cancelled as an unsent undo action");
            check(uuids(fixture.claim()).equals(Collections.singletonList(uuid))&&uuids(second.claim()).equals(Collections.singletonList(uuid)),"retries after the undo interval retain the original completion UUID");fixture.demoSync();check(!task("repeat").due.equals(due),"recurring completion advances only after the undo window and acknowledgement");
            seed();a=fixture.enqueueUndo(task("a"));uuid=fixture.snapshot().operations.get(0).uuid;second.close();second=null;fixture.close();fixture=new Store(getTargetContext(),"undo-verification.db");
            check(fixture.claim().isEmpty(),"interrupted undo remains protected when the database is reopened");check(fixture.releaseAllUndo()==1&&fixture.claim().get(0).uuid.equals(uuid),"startup recovery releases interrupted windows without losing or replacing the command");
            ui();out.putString("stream","\n"+log+"SUCCESS "+checks+" completion undo assertions\n");
        }catch(Throwable e){out.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{
            if(activity!=null)try{main(()->{call("commitUndoQueue");if(originalStore!=null){field("store",originalStore);field("demo",originalDemo);field("filter",originalFilter);field("project",originalProject);}activity.finish();});}catch(Exception ignored){}
            if(policy!=null)policy.reset();if(second!=null)second.close();if(fixture!=null)fixture.close();getTargetContext().deleteDatabase("undo-verification.db");getTargetContext().deleteDatabase("undo-legacy-verification.db");
        }
        finish(out.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
    }
}
