package cn.inktodo;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import java.io.*;
import java.util.*;

/** Verifies independent selection, persistence, default recovery and unified image rendering. */
public final class ScreensaverFontRunner extends Instrumentation {
    int count;StringBuilder log=new StringBuilder();String path;
    void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;log.append("PASS ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);path=args==null?"":args.getString("font","");start();}
    @Override public void onStart(){Bundle out=new Bundle();Context app=getTargetContext();SharedPreferences prefs=app.getSharedPreferences("settings",0);
        String oldFile=prefs.getString(ScreensaverFont.FILE,""),oldName=prefs.getString(ScreensaverFont.NAME,"默认宋体"),ui=prefs.getString("font_file","");FontStore.Choice imported=null;boolean keep=false;
        try{
            prefs.edit().remove(ScreensaverFont.FILE).remove(ScreensaverFont.NAME).commit();
            check(ScreensaverFont.load(app).face.equals(SnapshotImage.headingTypeface()),"empty selection restores device Songti rather than body font");
            if(path.isEmpty())throw new IllegalArgumentException("Provide -e font pointing to a device TTF");
            try(InputStream in=new FileInputStream(path)){imported=FontStore.copy(app,in,new File(path).getName());}
            prefs.edit().putString(ScreensaverFont.FILE,imported.file).putString(ScreensaverFont.NAME,imported.name).commit();
            FontStore.Choice selected=ScreensaverFont.load(app);check(selected.file.equals(imported.file),"imported snapshot font persists independently");
            check(ui.equals(prefs.getString("font_file","")),"snapshot font import leaves task body font unchanged");
            SnapshotModel model=SnapshotModel.create(Collections.emptyList(),Collections.emptySet(),"today","","",Collections.emptyMap(),0,java.time.LocalDate.now(),java.time.ZoneId.systemDefault());
            Bitmap first=SnapshotImage.create(1264,1680,model,0,selected.face),second=SnapshotImage.create(1264,1680,model,0,SnapshotImage.headingTypeface());
            check(!first.sameAs(second),"imported font changes heading date and empty-state image");first.recycle();second.recycle();
            prefs.edit().putString(ScreensaverFont.FILE,"font-0000.ttf").commit();check(ScreensaverFont.load(app).file.isEmpty(),"missing font falls back to default Songti");
            prefs.edit().remove(ScreensaverFont.FILE).remove(ScreensaverFont.NAME).commit();check(ScreensaverFont.load(app).name.equals("默认宋体"),"restore-default clears custom choice");
            prefs.edit().putString(ScreensaverFont.FILE,imported.file).putString(ScreensaverFont.NAME,imported.name).commit();keep=true;
            ScreensaverController.requestNow(app);
            out.putString("stream",log+"SUCCESS "+count+" snapshot font assertions; imported font retained for user\n");
        }catch(Throwable e){out.putString("stream",log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{if(!keep){prefs.edit().putString(ScreensaverFont.FILE,oldFile).putString(ScreensaverFont.NAME,oldName).commit();FontStore.discard(app,imported,oldFile);}}
        finish(keep?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
    }
}
