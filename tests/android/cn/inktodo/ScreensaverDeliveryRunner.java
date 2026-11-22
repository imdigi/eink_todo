package cn.inktodo;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.MediaStore;
import org.json.*;
import java.time.LocalDate;
import java.util.Collections;

/** Explicit system screensaver regression: two isolated fixtures, then restore real cached data. */
public final class ScreensaverDeliveryRunner extends Instrumentation {
    String phase;
    @Override public void onCreate(Bundle args){super.onCreate(args);phase=args==null?"restore":args.getString("phase","restore");start();}
    @Override public void onStart(){Bundle out=new Bundle();Store store=null;
        try{
            Context app=getTargetContext();ScreensaverController engine;SharedPreferences prefs;
            if(phase.equals("restore")){
                prefs=app.getSharedPreferences("screensaver",0);
                prefs.edit().putBoolean("auto_update",true).putBoolean("source_demo",false).remove("last_hash").commit();
                engine=ScreensaverController.get(app);
            }else{
                if(!phase.equals("one")&&!phase.equals("two"))throw new IllegalArgumentException("Expected one, two or restore");
                prefs=app.getSharedPreferences("screensaver-delivery-verification",0);
                if(phase.equals("one"))prefs.edit().clear().commit();
                prefs.edit().putBoolean("configured",true).putBoolean("auto_update",true).putString("filter","today").commit();
                store=new Store(app,"screensaver-delivery-verification.db");store.reset();
                String title=phase.equals("one")?"屏保连续更新验证 · 第一轮":"屏保连续更新验证 · 第二轮，内容已改变";
                JSONArray tasks=new JSONArray().put(new JSONObject().put("id","fixture").put("project_id","sample").put("content",title).put("due",new JSONObject().put("date",LocalDate.now().toString())));
                store.merge(new JSONObject().put("sync_token","fixture").put("full_sync",true).put("projects",new JSONArray().put(new JSONObject().put("id","sample").put("name","隔离验证"))).put("items",tasks),Collections.emptyList());
                Store fixture=store;
                engine=new ScreensaverController(app,prefs,demo->fixture.snapshot(),(path,hint)->OnyxScreen.setScreensaver(app,path,false),"InkTodoDeliveryVerification");
            }
            ScreensaverController.Outcome result=engine.refreshNow();
            if(result!=ScreensaverController.Outcome.UPDATED)throw new AssertionError("Delivery failed: "+result+"; "+prefs.getString("last_error",""));
            SystemClock.sleep(1800);
            out.putString("stream","PHASE="+phase+"; outcome="+result+"; auto="+engine.automatic()+"\nSOURCE_PATH="+prefs.getString("last_path","")+"\nSDK_PATH="+prefs.getString("last_import_path","")+"\n");
            if(phase.equals("restore")){
                ContentResolver resolver=app.getContentResolver();android.net.Uri collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                try(android.database.Cursor cursor=resolver.query(collection,new String[]{"_id","owner_package_name"},"relative_path=?",new String[]{"Pictures/InkTodoDeliveryVerification/"},null)){
                    while(cursor!=null&&cursor.moveToNext())if(app.getPackageName().equals(cursor.getString(1)))resolver.delete(ContentUris.withAppendedId(collection,cursor.getLong(0)),null,null);
                }
                app.getSharedPreferences("screensaver-delivery-verification",0).edit().clear().commit();app.deleteDatabase("screensaver-delivery-verification.db");
            }
            finish(Activity.RESULT_OK,out);
        }catch(Throwable error){out.putString("stream",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,out);}
        finally{if(store!=null)store.close();}
    }
}
