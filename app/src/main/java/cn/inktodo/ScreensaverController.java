package cn.inktodo;

import android.content.*;
import android.graphics.*;
import android.os.Build;
import android.view.WindowManager;
import java.time.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Refresh the last configured view from cached data. Never polls Todoist or changes refresh mode. */
final class ScreensaverController {
    interface Source {Store.Snapshot read(boolean demo)throws Exception;}
    interface Setter {boolean apply(String path,boolean showHint);}
    enum Outcome {UPDATED,UNCHANGED,DISABLED,STALE,RETRY_LATER,FAILED}
    private static ScreensaverController instance;
    private static final ScheduledExecutorService WORK=Executors.newSingleThreadScheduledExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"screensaver-updates"));
    final Context context;final SharedPreferences prefs;private final Source source;private final Setter setter;
    private final ScreensaverRetention retention;private final String folder;
    private final Object gate=new Object();private long epoch;private ScheduledFuture<?> pending;
    ScreensaverController(Context context,SharedPreferences prefs,Source source,Setter setter){this(context,prefs,source,setter,"InkTodo");}
    ScreensaverController(Context context,SharedPreferences prefs,Source source,Setter setter,String folder){this.context=context.getApplicationContext();this.prefs=prefs;this.source=source;this.setter=setter;this.folder=folder;this.retention=new ScreensaverRetention(context,folder);}
    static synchronized ScreensaverController get(Context c){
        if(instance==null){
            Context app=c.getApplicationContext();
            instance=new ScreensaverController(app,app.getSharedPreferences("screensaver",0),demo->{Store store=new Store(app,demo);try{android.database.sqlite.SQLiteDatabase db=store.getReadableDatabase();db.beginTransactionNonExclusive();try{Store.Snapshot snapshot=store.snapshot();db.setTransactionSuccessful();return snapshot;}finally{db.endTransaction();}}finally{store.close();}},(path,hint)->OnyxScreen.setScreensaver(app,path,hint));
            BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){instance.schedule(0);}};
            IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_DATE_CHANGED);filter.addAction(Intent.ACTION_TIME_CHANGED);filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            if(Build.VERSION.SDK_INT>=33)app.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else app.registerReceiver(receiver,filter);
            instance.scheduleCleanup();
        }return instance;
    }
    static void request(Context c){get(c).schedule(750);}
    static void requestNow(Context c){get(c).schedule(0);}
    boolean configured(){return prefs.getBoolean("configured",false)||!prefs.getString("last_path","").isEmpty();}
    boolean automatic(){return prefs.getBoolean("auto_update",true);}
    void setAutomatic(boolean value){synchronized(gate){epoch++;if(pending!=null)pending.cancel(false);prefs.edit().putBoolean("auto_update",value).apply();}if(value)schedule(0);}
    private void schedule(long delay){
        if(!OnyxScreen.sdkAvailable()||!OnyxScreen.isBoox()||!configured()||!automatic())return;
        synchronized(gate){long version=++epoch;if(pending!=null)pending.cancel(false);pending=WORK.schedule(()->refresh(version),delay,TimeUnit.MILLISECONDS);}
    }
    Outcome refreshNow(){synchronized(gate){epoch++;if(pending!=null)pending.cancel(false);}return refresh(currentEpoch());}
    private long currentEpoch(){synchronized(gate){return epoch;}}
    void settingsChanged(){
        if(!OnyxScreen.sdkAvailable()||!OnyxScreen.isBoox()||!configured())return;
        WORK.execute(()->refresh(currentEpoch(),true));
    }
    private Outcome refresh(long version){return refresh(version,false);}
    private Outcome refresh(long version,boolean force){
        ScreensaverExport.Published image=null;String signature="";
        try{
            if(!configured()||(!force&&!automatic()))return Outcome.DISABLED;
            boolean demo=prefs.getBoolean("source_demo",false);String filter=prefs.getString("filter","today"),project=prefs.getString("project","");
            Store.Snapshot snapshot=source.read(demo);
            SnapshotModel model=ScreensaverProjects.model(context,demo,snapshot,filter,project);
            FontStore.Choice choice=ScreensaverFont.load(context);String font=choice.file;
            Point size=new Point();((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
            signature=model.fingerprint(size.x,size.y,font);
            synchronized(gate){
                if(!force&&version!=epoch)return Outcome.STALE;
                if(!force&&prefs.getInt("delivery_version",0)==2&&!prefs.getBoolean("needs_submit",false)&&signature.equals(prefs.getString("last_hash",""))&&ScreensaverExport.current(prefs.getString("last_path",""))&&new File(prefs.getString("last_path","")).isFile())return Outcome.UNCHANGED;
                if(!force&&signature.equals(prefs.getString("failed_hash",""))&&System.currentTimeMillis()-prefs.getLong("failed_at",0)<60000)return Outcome.RETRY_LATER;
            }
            Bitmap bitmap=SnapshotImage.create(size.x,size.y,model,snapshot.lastSync,choice.face);
            byte[] data;try{data=ScreensaverExport.encode(bitmap);}finally{bitmap.recycle();}
            synchronized(gate){
                if(!force&&(version!=epoch||!automatic()))return Outcome.STALE;
                prefs.edit().putBoolean("needs_submit",true).commit();
                image=ScreensaverExport.publish(context,data,size.x,size.y,folder);
                ScreensaverExport.Published transfer=ScreensaverExport.publishImport(context,data,size.x,size.y,folder);
                scheduleCleanup();
                if(!setter.apply(transfer.path,false))throw new java.io.IOException("文石未接受屏保更新请求");
                save(image,signature,transfer.path);return Outcome.UPDATED;
            }
        }catch(Exception|OutOfMemoryError error){
            // Do not delete a submitted image or the previously configured screen on failures.
            synchronized(gate){if(force||version==epoch)prefs.edit().putString("last_error","自动更新暂未完成，将在后续同步时重试").putString("failed_hash",signature).putLong("failed_at",System.currentTimeMillis()).apply();}
            return Outcome.FAILED;
        }
    }
    boolean submitManual(byte[] data,SnapshotModel model,boolean demo,int width,int height,String font)throws Exception{
        synchronized(gate){
            epoch++;if(pending!=null)pending.cancel(false);
            prefs.edit().putBoolean("needs_submit",true).commit();
            ScreensaverExport.Published image=ScreensaverExport.publish(context,data,width,height,folder);
            ScreensaverExport.Published transfer=ScreensaverExport.publishImport(context,data,width,height,folder);
            scheduleCleanup();
            if(!setter.apply(transfer.path,true))return false;
            prefs.edit().putBoolean("configured",true).putBoolean("source_demo",demo).putString("filter",model.filter).putString("project",model.project).apply();
            save(image,model.fingerprint(width,height,font),transfer.path);
        }schedule(750);return true;
    }
    private void save(ScreensaverExport.Published image,String signature,String importPath){
        prefs.edit().putInt("delivery_version",2).putString("last_import_path",importPath).putString("last_uri",image.uri.toString()).putString("last_path",image.path).putString("last_hash",signature).putLong("last_submitted",System.currentTimeMillis()).remove("last_error").remove("failed_hash").remove("failed_at").remove("previous_path").remove("previous_uri").remove("needs_submit").apply();
        scheduleCleanup();
    }
    Set<String> protectedImages(){
        Set<String> keep=new HashSet<>();keep.add(prefs.getString("last_path",""));
        String imported=prefs.getString("last_import_path","");
        if(!imported.isEmpty()){keep.add(imported);keep.add(ScreensaverRetention.systemPath(imported));}
        SharedPreferences photo=context.getSharedPreferences("photo-screensaver-backup",0);
        if(photo.getBoolean("active",false)){String photoPath=photo.getString("photo_path","");keep.add(photoPath);if(!photoPath.isEmpty())keep.add(ScreensaverRetention.systemPath(photoPath));}
        return keep;
    }
    private void scheduleCleanup(){
        synchronized(gate){
            WORK.schedule(()->{
                synchronized(gate){
                    retention.prune(protectedImages(),System.currentTimeMillis());
                }
            },ScreensaverRetention.GRACE_MS+500,TimeUnit.MILLISECONDS);
        }
    }

}
