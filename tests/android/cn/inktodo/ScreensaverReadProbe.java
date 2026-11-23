package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.WindowManager;
import java.time.*;
import java.util.concurrent.*;

/** Explicit device probe: replaces screensaver with a fresh real cached snapshot. No task writes. */
public final class ScreensaverReadProbe extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();FileObserver observer=null;Store store=null;ScreensaverController controller=null;boolean automatic=false;String diagnostic="";
        try{
            Context app=getTargetContext();SharedPreferences settings=app.getSharedPreferences("settings",0),prefs=app.getSharedPreferences("screensaver",0);
            controller=ScreensaverController.get(app);automatic=controller.automatic();
            diagnostic="configured="+controller.configured()+"; automatic="+automatic+"; demo="+prefs.getBoolean("source_demo",false)+"; filter="+prefs.getString("filter","")+"; needs_submit="+prefs.getBoolean("needs_submit",false)+"\n";
            diagnostic+="refresh outcome="+controller.refreshNow()+"; error="+prefs.getString("last_error","")+"\n";
            store=new Store(app,false);Store.Snapshot snapshot=store.snapshot();
            SnapshotModel model=ScreensaverProjects.model(app,false,snapshot,prefs.getString("filter","today"),prefs.getString("project",""));
            Point size=new Point();((WindowManager)app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
            FontStore.Choice face=ScreensaverFont.load(app);String font=face.file;
            Bitmap bitmap=SnapshotImage.create(size.x,size.y,model,snapshot.lastSync,face.face);ScreensaverExport.Published image;
            try{image=ScreensaverExport.publishImport(app,ScreensaverExport.encode(bitmap),size.x,size.y,"InkTodo");}finally{bitmap.recycle();}
            CountDownLatch closed=new CountDownLatch(1);StringBuilder events=new StringBuilder();
            observer=new FileObserver(image.path,FileObserver.OPEN|FileObserver.CLOSE_NOWRITE){@Override public void onEvent(int event,String path){synchronized(events){events.append(event).append(',');}if((event&FileObserver.CLOSE_NOWRITE)!=0)closed.countDown();}};
            observer.startWatching();boolean accepted=OnyxScreen.setScreensaver(app,image.path,false);boolean read=closed.await(8,TimeUnit.SECONDS);
            out.putString("stream",diagnostic+"SDK accepted="+accepted+"; source read-close observed="+read+"; events="+events+"\nIMAGE_PATH="+image.path+"\n");

            finish(Activity.RESULT_OK,out);
        }catch(Throwable error){out.putString("stream",diagnostic+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,out);}
        finally{if(observer!=null)observer.stopWatching();if(store!=null)store.close();}
    }
}
