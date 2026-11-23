package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Uses an isolated cache, preferences and fake system setter. Never replaces the real screensaver. */
public final class ScreensaverUpdateRunner extends Instrumentation {
    int checks;StringBuilder log=new StringBuilder();Store store;List<String> created=new ArrayList<>();
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;log.append("PASS ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    JSONObject item(String id,String parent,int offset,String content)throws Exception{
        return new JSONObject().put("id",id).put("parent_id",parent).put("project_id","sample").put("child_order",id.equals("today")?1:2).put("content",content).put("due",new JSONObject().put("date",LocalDate.now().plusDays(offset).toString()));
    }
    void merge(JSONArray items)throws Exception{store.merge(new JSONObject().put("sync_token","fixture").put("items",items).put("projects",new JSONArray()),Collections.emptyList());}
    @Override public void onStart(){Bundle out=new Bundle();SharedPreferences prefs=getTargetContext().getSharedPreferences("screensaver-update-verification",0);
        try{
            prefs.edit().clear().putBoolean("configured",true).putBoolean("auto_update",true).putString("filter","today").putString("project","").commit();
            store=new Store(getTargetContext(),"screensaver-update-verification.db");store.reset();
            store.merge(new JSONObject().put("sync_token","fixture").put("full_sync",true).put("projects",new JSONArray().put(new JSONObject().put("id","sample").put("name","示例计划")))
                .put("items",new JSONArray().put(new JSONObject().put("id","parent").put("project_id","sample").put("content","学习计划（父任务）").put("child_order",1))
                    .put(item("today","parent",0,"阅读今天的材料")).put(item("old","parent",-1,"补上昨天的练习")).put(item("future","parent",1,"准备明天的分享"))),Collections.emptyList());
            final int[] submissions={0};final boolean[] accept={true};
            ScreensaverController engine=new ScreensaverController(getTargetContext(),prefs,demo->store.snapshot(),(path,hint)->{submissions[0]++;created.add(path);return accept[0];},"InkTodoUpdateVerification");
            check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&submissions[0]==1,"initial configured screensaver is published and submitted");
            String first=prefs.getString("last_path","");byte[] original=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(first));check(new File(first).length()>0&&!first.contains("/Android/data/"),"automatic update uses complete public image");
            check(engine.refreshNow()==ScreensaverController.Outcome.UNCHANGED&&submissions[0]==1,"no-change refresh does not regenerate or resubmit");
            Thread.sleep(5);merge(new JSONArray());
            check(engine.refreshNow()==ScreensaverController.Outcome.UNCHANGED&&submissions[0]==1,"new sync timestamp alone does not regenerate image");
            merge(new JSONArray().put(item("new","",0,"新增的今日任务")));
            check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&submissions[0]==2&&first.equals(prefs.getString("last_path","")),"new server item replaces the same fixed image");
            check(!created.get(0).equals(created.get(1)),"changed content uses a fresh SDK import path to bypass BOOX cache");
            check(new File(first).getName().equals(ScreensaverExport.NAME),"gallery source keeps its fixed filename despite fresh SDK import paths");
            check(!Arrays.equals(original,java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(first))),"fixed path contains new content after a server update");
            merge(new JSONArray().put(item("new","",0,"修改后的任务标题").put("description","修改后的任务说明")));
            check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&submissions[0]==3,"edited title and description trigger update");
            merge(new JSONArray().put(new JSONObject().put("id","new").put("checked",1)));
            check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&submissions[0]==4,"completed item triggers update");
            merge(new JSONArray().put(new JSONObject().put("id","old").put("is_deleted",1)));
            check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&submissions[0]==5,"deleted overdue item triggers update");
            store.enqueue(store.snapshot().tasks.stream().filter(t->t.id.equals("today")).findFirst().get(),"close");
            check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&submissions[0]==6,"local pending completion refreshes before cloud acknowledgement");
            store.demoSync();check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&submissions[0]==7,"acknowledgement clears pending marker in snapshot");
            merge(new JSONArray().put(item("last","",0,"失败与重试验证")));String acceptedHash=prefs.getString("last_hash","");accept[0]=false;
            check(engine.refreshNow()==ScreensaverController.Outcome.FAILED&&prefs.getString("last_hash","").equals(acceptedHash),"rejected request does not mark content as submitted");
            int failures=submissions[0];check(engine.refreshNow()==ScreensaverController.Outcome.RETRY_LATER&&submissions[0]==failures,"failed content has retry cooldown instead of repeated exports");
            check(prefs.getBoolean("needs_submit",false),"rejected submission keeps the image dirty for retry");
            prefs.edit().putLong("failed_at",0).commit();accept[0]=true;check(engine.refreshNow()==ScreensaverController.Outcome.UPDATED&&!prefs.contains("last_error"),"retry succeeds and clears update error");
            engine.setAutomatic(false);merge(new JSONArray().put(item("disabled","",0,"关闭时不提交")));int count=submissions[0];
            check(engine.refreshNow()==ScreensaverController.Outcome.DISABLED&&submissions[0]==count,"disabled automatic update leaves current image unchanged");
            CountDownLatch forced=new CountDownLatch(2);
            ScreensaverController explicit=new ScreensaverController(getTargetContext(),prefs,demo->store.snapshot(),(path,hint)->{created.add(path);forced.countDown();return true;},"InkTodoUpdateVerification");
            explicit.settingsChanged();explicit.settingsChanged();
            check(forced.await(10,TimeUnit.SECONDS),"each settings modification submits SDK even when auto update is off and content repeats");
            prefs.edit().putBoolean("auto_update",true).commit();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            ScreensaverController slow=new ScreensaverController(getTargetContext(),prefs,demo->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("timeout");return store.snapshot();},(path,hint)->{throw new AssertionError("Stale request submitted");},"InkTodoUpdateVerification");
            ExecutorService worker=Executors.newSingleThreadExecutor();Future<ScreensaverController.Outcome> waiting=worker.submit(()->slow.refreshNow());
            try{check(entered.await(3,TimeUnit.SECONDS),"background refresh has started");slow.setAutomatic(false);release.countDown();check(waiting.get(5,TimeUnit.SECONDS)==ScreensaverController.Outcome.STALE,"disabling during generation prevents stale submission");}finally{release.countDown();worker.shutdownNow();}
            // Restore sample cache only; render a shareable fixture showing both sections and nested parents.
            store.reset();store.merge(new JSONObject().put("sync_token","fixture").put("full_sync",true).put("projects",new JSONArray().put(new JSONObject().put("id","sample").put("name","示例计划")))
                .put("items",new JSONArray().put(new JSONObject().put("id","parent").put("project_id","sample").put("content","学习计划（父任务）"))
                    .put(item("today","parent",0,"阅读今天的材料").put("description","记录关键观点，整理今天的阅读收获。"))
                    .put(item("grand","today",0,"整理阅读笔记（第三级）")).put(item("old","parent",-1,"补上昨天的练习")).put(item("future","parent",1,"准备明天的分享"))),Collections.emptyList());
            Store.Snapshot s=store.snapshot();SnapshotModel model=SnapshotModel.create(s.tasks,s.hidden(),"today","",s.inboxId,s.taskProjectNames(),0,LocalDate.now(),ZoneId.systemDefault());
            Bitmap preview=SnapshotImage.create(1264,1680,model,s.lastSync,Typeface.DEFAULT);ScreensaverExport.validate(ScreensaverExport.encode(preview),1264,1680);
            try(FileOutputStream file=new FileOutputStream(new File(getTargetContext().getExternalFilesDir("Pictures"),"screensaver-grouped-sample.png"))){preview.compress(Bitmap.CompressFormat.PNG,100,file);}preview.recycle();check(true,"expanded grouped snapshot renders valid nonblank portrait image");
            Bitmap landscape=SnapshotImage.create(1680,1264,model,s.lastSync,Typeface.DEFAULT);check(landscape.getWidth()==1680&&landscape.getHeight()==1264,"grouped snapshot also fits landscape dimensions");landscape.recycle();
            List<Core.Task> fontFixture=Arrays.asList(new Core.Task(new JSONObject().put("id","p").put("project_id","sample").put("content","FONT")),
                new Core.Task(item("a","p",0,"FONT")),new Core.Task(item("b","p",-1,"FONT")));
            SnapshotModel fontModel=SnapshotModel.create(fontFixture,Collections.emptySet(),"today","","",Collections.emptyMap(),0,LocalDate.now(),ZoneId.systemDefault());
            Bitmap styles=SnapshotImage.create(1264,1680,fontModel,0,Typeface.DEFAULT);List<Integer> parents=boxes(styles,68),children=boxes(styles,110);
            check(parents.size()==2&&children.size()==2,"both context parents and child rows have visible checkboxes in the snapshot");
            check(Math.abs(glyphHeight(styles,122,parents.get(0)-11)-glyphHeight(styles,164,children.get(0)-11))<=2,"parent and child task titles use the same rendered font size");
            check(ink(styles,68,0,1196,75)==0,"generous whitespace above solar term heading");
            check(ink(styles,68,80,490,210)>1000&&ink(styles,640,80,1196,210)==0,"large solar term heading is left aligned");
            check(ink(styles,68,210,350,260)>100,"date subtitle forms a compact group with the solar term");
            check(ink(styles,60,1600,1204,1680)==0,"removed bottom branding leaves a clean footer");styles.recycle();
            out.putString("stream","\n"+log+"SUCCESS "+checks+" automatic screensaver assertions\n");
        }catch(Throwable e){out.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{
            created.add(prefs.getString("last_path",""));
            for(String path:created)try{android.database.Cursor cursor=getTargetContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{MediaStore.Images.Media._ID},MediaStore.Images.Media.DATA+"=?",new String[]{path},null);if(cursor!=null)try{if(cursor.moveToFirst())getTargetContext().getContentResolver().delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0)),null,null);}finally{cursor.close();}}catch(Exception ignored){}
            if(store!=null)store.close();getTargetContext().deleteDatabase("screensaver-update-verification.db");prefs.edit().clear().commit();
        }
        finish(out.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
    }
    List<Integer> boxes(Bitmap image,int x){
        List<Integer> ys=new ArrayList<>();
        for(int y=180;y<image.getHeight()-70;y++){
            int ink=0;for(int k=3;k<29;k++){if(Color.red(image.getPixel(x+k,y))<220)ink++;if(Color.red(image.getPixel(x+k,y+32))<220)ink++;if(Color.red(image.getPixel(x,y+k))<220)ink++;if(Color.red(image.getPixel(x+32,y+k))<220)ink++;}
            if(ink>=95&&(ys.isEmpty()||y-ys.get(ys.size()-1)>35))ys.add(y);
        }return ys;
    }
    int glyphHeight(Bitmap image,int x,int y){int top=100,bottom=-1;for(int yy=0;yy<44;yy++)for(int xx=x;xx<x+190;xx++)if(Color.red(image.getPixel(xx,y+yy))<150){top=Math.min(top,yy);bottom=Math.max(bottom,yy);}return bottom>=top?bottom-top+1:0;}
    int ink(Bitmap image,int x1,int y1,int x2,int y2){int count=0;for(int y=y1;y<y2;y++)for(int x=x1;x<x2;x++)if(Color.red(image.getPixel(x,y))<220)count++;return count;}
}
