package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit photography utility: save real configuration, pause auto updates, later restore on request. */
public final class PhotoScreensaverRunner extends Instrumentation {
    String mode;
    @Override public void onCreate(Bundle args){super.onCreate(args);mode=args==null?"demo":args.getString("mode","demo");start();}
    JSONObject task(String id,String parent,int offset,String title,String description)throws Exception{
        JSONObject value=new JSONObject().put("id",id).put("parent_id",parent).put("project_id","photo-daily").put("content",title).put("description",description).put("child_order",id.equals("reading")?1:2);
        if(offset!=99)value.put("due",new JSONObject().put("date",LocalDate.now().plusDays(offset).toString()));return value;
    }
    SnapshotModel demoModel()throws Exception{
        List<Core.Task> tasks=Arrays.asList(
            new Core.Task(task("parent","",99,"读书与日课","")),
            new Core.Task(task("reading","parent",0,"读完《人间草木》的一章","摘录一段喜欢的文字，留待慢慢回味。")),
            new Core.Task(task("notes","parent",0,"整理本周的阅读笔记","把散落的思绪，慢慢整理成篇。")),
            new Core.Task(task("practice","parent",-1,"补上昨日的练字","静心写一页，不必求快。")));
        return SnapshotModel.create(tasks,Collections.emptySet(),"today","","",Collections.singletonMap("photo-daily","日常生活"),0,LocalDate.now(),ZoneId.systemDefault());
    }
    void restorePreferences(SharedPreferences target,JSONObject values)throws Exception{
        SharedPreferences.Editor edit=target.edit().clear();Iterator<String> keys=values.keys();
        while(keys.hasNext()){
            String key=keys.next();Object value=values.get(key);
            if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Number)edit.putLong(key,((Number)value).longValue());
            else edit.putString(key,String.valueOf(value));
        }
        if(!edit.commit())throw new IllegalStateException("Cannot restore saved screensaver preferences");
    }
    @Override public void onStart(){Bundle out=new Bundle();Store store=null;
        try{
            Context app=getTargetContext();SharedPreferences prefs=app.getSharedPreferences("screensaver",0),backup=app.getSharedPreferences("photo-screensaver-backup",0),settings=app.getSharedPreferences("settings",0);
            ScreensaverController controller=ScreensaverController.get(app);SnapshotModel model;long synced;
            JSONObject original;
            if(mode.equals("demo")){
                if(!backup.getBoolean("active",false)){
                    if(!backup.edit().putString("original",new JSONObject(prefs.getAll()).toString()).putBoolean("active",true).commit())throw new IllegalStateException("Cannot save original configuration");
                }
                original=new JSONObject(backup.getString("original","{}"));controller.setAutomatic(false);
                model=demoModel();synced=System.currentTimeMillis();
            }else if(mode.equals("restore")){
                if(!backup.getBoolean("active",false))throw new IllegalStateException("No active photography session to restore");
                original=new JSONObject(backup.getString("original","{}"));controller.setAutomatic(false);
                store=new Store(app,false);android.database.sqlite.SQLiteDatabase db=store.getReadableDatabase();db.beginTransactionNonExclusive();
                Store.Snapshot snapshot;try{snapshot=store.snapshot();db.setTransactionSuccessful();}finally{db.endTransaction();}
                model=SnapshotModel.create(snapshot.tasks,snapshot.hidden(),original.optString("filter","today"),original.optString("project",""),snapshot.inboxId,snapshot.taskProjectNames(),snapshot.pending(),LocalDate.now(),ZoneId.systemDefault());synced=snapshot.lastSync;
            }else throw new IllegalArgumentException("Expected mode=demo or mode=restore");
            Point size=new Point();((WindowManager)app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
            FontStore.Choice face=ScreensaverFont.load(app);String font=face.file;
            Bitmap bitmap=SnapshotImage.create(size.x,size.y,model,synced,face.face);ScreensaverExport.Published image,transfer;
            try{byte[] data=ScreensaverExport.encode(bitmap);image=ScreensaverExport.publish(app,data,size.x,size.y);transfer=ScreensaverExport.publishImport(app,data,size.x,size.y,"InkTodo");}finally{bitmap.recycle();}
            CountDownLatch result=new CountDownLatch(1);StringBuilder messages=new StringBuilder();
            getUiAutomation().setOnAccessibilityEventListener(event->{
                if(event.getEventType()!=AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED||!String.valueOf(event.getPackageName()).startsWith("com.onyx"))return;
                String text=event.getText().toString();if(text.contains("保存成功")||text.contains("屏保")||text.contains("失败")){synchronized(messages){messages.append(text).append('\n');}result.countDown();}
            });
            if(!OnyxScreen.setScreensaver(app,transfer.path,false))throw new IllegalStateException("BOOX did not accept image");
            if(mode.equals("restore")){
                restorePreferences(prefs,original);
                prefs.edit().putBoolean("configured",true).putBoolean("source_demo",false).putString("filter",model.filter).putString("project",model.project)
                    .putString("last_path",image.path).putString("last_uri",image.uri.toString()).putString("last_hash",model.fingerprint(size.x,size.y,font)).putLong("last_submitted",System.currentTimeMillis())
                    .remove("last_error").remove("failed_hash").remove("failed_at").commit();
                controller.setAutomatic(original.optBoolean("auto_update",true));backup.edit().putBoolean("active",false).putString("restored_path",image.path).commit();
            }else backup.edit().putString("photo_path",image.path).commit();
            boolean observed=result.await(20,TimeUnit.SECONDS);
            out.putString("stream",mode.toUpperCase(Locale.ROOT)+" image submitted\nIMAGE_PATH="+image.path+"\nSIZE="+size.x+"x"+size.y+"\nSYSTEM_RESULT_OBSERVED="+observed+"\n"+messages+"\nAutomatic updates "+(mode.equals("demo")?"paused until explicit restore":"restored to original preference")+"\n");
            finish(Activity.RESULT_OK,out);
        }catch(Throwable error){out.putString("stream",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,out);}
        finally{if(store!=null)store.close();}
    }
}
