package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.LocalDate;
import java.util.*;

/** Inject an isolated, demo-only database. No test task ever reaches the real account. */
public final class HierarchyVerificationRunner extends Instrumentation {
    int checks;final StringBuilder log=new StringBuilder();Activity activity;Store fixture;
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;log.append("PASS ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    Object field(String name)throws Exception{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void field(String name,Object value)throws Exception{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    void build()throws Exception{Method m=activity.getClass().getDeclaredMethod("build");m.setAccessible(true);m.invoke(activity);}
    void main(Checked action)throws Exception{final Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new Exception(failure[0]);android.os.SystemClock.sleep(150);}
    interface Checked{void run()throws Exception;}
    JSONObject task(String id,String parent,int order,int due)throws Exception{
        JSONObject j=new JSONObject().put("id",id).put("content",new HashMap<String,String>(){{put("root","学习计划（父任务）");put("today","阅读今天的材料");put("grand","整理阅读笔记（第三级）");put("old","补上昨天的练习");put("future","准备明天的分享");put("second","独立任务");}}.get(id))
            .put("project_id","fixture-project").put("parent_id",parent).put("child_order",order);
        if(due!=99)j.put("due",new JSONObject().put("date",LocalDate.now().plusDays(due).toString()));return j;
    }
    View row(String id)throws Exception{return ((View)field("tasksBox")).findViewWithTag("task:"+id);}
    List<View> rows(String id)throws Exception{List<View> out=new ArrayList<>();collect((View)field("tasksBox"),"task:"+id,out);return out;}
    void collect(View v,String tag,List<View> out){if(tag.equals(v.getTag()))out.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),tag,out);}
    int[] at(View v){int[] xy=new int[2];v.getLocationOnScreen(xy);return xy;}
    View disclosure(View row){if(row instanceof TreeToggle)return row;if(row instanceof ViewGroup)for(int i=0;i<((ViewGroup)row).getChildCount();i++){View found=disclosure(((ViewGroup)row).getChildAt(i));if(found!=null)return found;}return null;}
    void toggle(View row){disclosure(row).performClick();}
    boolean checkPresent(View row){return ((ViewGroup)row).getChildAt(0) instanceof CheckBox;}
    void seed()throws Exception{
        fixture.reset();fixture.merge(new JSONObject().put("sync_token","fixture").put("full_sync",true)
            .put("projects",new JSONArray().put(new JSONObject().put("id","fixture-project").put("name","层级示例")))
            .put("items",new JSONArray().put(task("future","root",3,1)).put(task("old","root",2,-1)).put(task("grand","today",1,0))
                .put(task("root","",1,99)).put(task("today","root",1,0)).put(task("second","",2,0))),Collections.emptyList());
    }
    @Override public void onStart(){
        Bundle out=new Bundle();SharedPreferences hierarchy=getTargetContext().getSharedPreferences("hierarchy",0);
        String[] keys={"demo:project:fixture-project","demo:today:due_today","demo:today:overdue"};
        Map<String,Set<String>> saved=new HashMap<>();for(String key:keys)if(hierarchy.contains(key))saved.put(key,new HashSet<>(hierarchy.getStringSet(key,Collections.emptySet())));
        SharedPreferences.Editor clear=hierarchy.edit();for(String key:keys)clear.remove(key);clear.commit();
        hierarchy.edit().putStringSet("demo:project:fixture-project",Collections.singleton("root")).commit();
        try{
            fixture=new Store(getTargetContext(),"hierarchy-verification.db");seed();
            JSONObject sibling=new JSONObject().put("id","sibling").put("content","同组另一个子任务").put("project_id","fixture-project").put("parent_id","root").put("child_order",4).put("due",new JSONObject().put("date",LocalDate.now().toString()));
            fixture.merge(new JSONObject().put("sync_token","fixture").put("items",new JSONArray().put(sibling)).put("projects",new JSONArray()),Collections.emptyList());
            Core.Task conflict=null;for(Core.Task t:fixture.snapshot().tasks)if(t.id.equals("sibling"))conflict=t;
            fixture.enqueue(conflict,"close");List<Core.Operation> failed=fixture.claim();
            fixture.merge(new JSONObject().put("sync_token","fixture").put("items",new JSONArray()).put("projects",new JSONArray())
                .put("sync_status",new JSONObject().put(failed.get(0).uuid,new JSONObject().put("http_code",403).put("error","fixture failure"))),failed);
            try{fixture.enqueueGroupClose("root","today:due_today",LocalDate.now(),java.time.ZoneId.systemDefault());throw new AssertionError("Conflict accepted");}
            catch(IllegalStateException expected){check(fixture.snapshot().operations.size()==1,"batch conflict does not partially enqueue earlier siblings");}
            fixture.discard(failed.get(0).uuid);
            Core.GroupClose batch=fixture.enqueueGroupClose("root","today:due_today",LocalDate.now(),java.time.ZoneId.systemDefault());
            check(batch.completed==3&&fixture.snapshot().operations.size()==2,"batch enqueues minimal non-overlapping child commands atomically");
            List<Core.Operation> first=fixture.claim(),retry=fixture.claim();Set<String> ids1=new HashSet<>(),ids2=new HashSet<>();for(Core.Operation op:first){ids1.add(op.uuid);check(!op.taskId.equals("root")&&!op.taskId.equals("future")&&!op.taskId.equals("old"),"batch command excludes grouping parent and out-of-scope tasks");}for(Core.Operation op:retry)ids2.add(op.uuid);
            check(ids1.equals(ids2)&&ids1.size()==2,"batch retries preserve each command UUID");
            fixture.demoSync();Set<String> remaining=new HashSet<>();for(Core.Task t:fixture.snapshot().tasks)remaining.add(t.id);
            check(remaining.equals(new HashSet<>(Arrays.asList("root","old","future","second"))),"batch acknowledgement leaves parent and out-of-scope siblings untouched");seed();
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->{field("store",fixture);field("demo",true);field("filter","project");field("project","fixture-project");build();});
            main(()->{
                check(row("root")!=null && row("today")!=null && row("grand")!=null,"project defaults expanded despite older stored folds and renders three levels");
                int parent=at(row("root"))[1],kid=at(row("today"))[1],grand=at(row("grand"))[1],old=at(row("old"))[1],future=at(row("future"))[1],second=at(row("second"))[1];
                check(parent<kid && kid<grand && grand<old && old<future && future<second,"actual project rows retain preorder and sibling order");
                int x0=at(((ViewGroup)row("root")).getChildAt(0))[0],x1=at(((ViewGroup)row("today")).getChildAt(0))[0],x2=at(((ViewGroup)row("grand")).getChildAt(0))[0];
                check(x0<x1 && x1<x2 && Math.abs((x1-x0)-(x2-x1))<=1,"checkboxes visibly indent by one step per generation");
                check(at(disclosure(row("root")))[0]>x0+200,"disclosure target is on title right, not in left gutter");
                toggle(row("root"));
            });
            main(()->{check(row("today")==null&&row("grand")==null&&row("old")==null&&row("future")==null&&row("second")!=null,"collapse hides entire subtree and keeps unrelated root");check(((TextView)field("count")).getText().toString().equals("6 项"),"collapse does not change task total");build();});
            main(()->{check(row("today")==null,"collapsed branch survives page rebuild");toggle(row("root"));});
            main(()->check(row("grand")!=null,"expansion restores nested descendants"));
            shot("hierarchy-project-sample.png");
            main(()->{field("filter","today");field("project","");build();});
            main(()->{
                List<View> roots=rows("root");check(roots.size()==2,"today and overdue each include their own parent context");
                check(checkPresent(roots.get(0))&&checkPresent(roots.get(1))&&((ViewGroup)roots.get(0)).getChildAt(0).getContentDescription().toString().startsWith("完成本组"),"context parents expose scoped-child completion checkboxes");
                check(row("future")==null&&row("today")!=null&&row("old")!=null,"date groups exclude future siblings without losing matching children");
                check(at(roots.get(0))[1]<at(row("today"))[1]&&at(row("grand"))[1]<at(roots.get(1))[1]&&at(roots.get(1))[1]<at(row("old"))[1],"each matching child stays under its parent in the correct date group");
                check(((TextView)field("count")).getText().toString().equals("4 项"),"date total excludes context-only parent duplicates");
            });
            shot("hierarchy-today-sample.png");
            main(()->toggle(rows("root").get(0)));
            main(()->{check(row("today")==null&&row("grand")==null&&row("old")!=null,"folding today does not fold overdue group");field("filter","project");field("project","fixture-project");build();});
            main(()->check(row("future")!=null&&row("today")!=null,"project and date-group collapse states are independent"));
            check(fixture.snapshot().operations.isEmpty(),"disclosure actions never enqueue completion or deletion");
            main(()->{field("filter","today");field("project","");build();((ViewGroup)rows("root").get(0)).getChildAt(0).performClick();});
            main(()->check(row("today")==null&&row("grand")==null&&row("old")!=null,"actual group checkbox completes only today's matching children"));
            Set<String> live=new HashSet<>();for(Core.Task t:fixture.snapshot().tasks)live.add(t.id);
            check(live.contains("root")&&live.contains("future")&&live.contains("old")&&live.contains("second"),"group checkbox retains grouping parent and unrelated dates/tasks");
            out.putString("stream","\n"+log+"SUCCESS "+checks+" hierarchy UI assertions\n");
        }catch(Throwable e){out.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{
            if(activity!=null)runOnMainSync(()->activity.finish());if(fixture!=null)fixture.close();getTargetContext().deleteDatabase("hierarchy-verification.db");
            SharedPreferences.Editor restore=hierarchy.edit();for(String key:keys){if(saved.containsKey(key))restore.putStringSet(key,saved.get(key));else restore.remove(key);}restore.commit();
        }
        finish(out.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
    }
    void shot(String name)throws Exception{
        final int[] xy=new int[2],size=new int[2];main(()->{View box=(View)field("tasksBox");box.getLocationOnScreen(xy);size[0]=box.getWidth();size[1]=((View)field("taskScroll")).getHeight();});
        Bitmap full=getUiAutomation().takeScreenshot();if(full==null)throw new IOException("No screenshot");
        Bitmap crop=Bitmap.createBitmap(full,xy[0],xy[1],Math.min(size[0],full.getWidth()-xy[0]),Math.min(size[1],full.getHeight()-xy[1]));
        try(FileOutputStream stream=new FileOutputStream(new File(getTargetContext().getExternalFilesDir("Pictures"),name))){crop.compress(Bitmap.CompressFormat.PNG,100,stream);}crop.recycle();full.recycle();
    }
}
