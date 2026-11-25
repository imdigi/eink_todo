package cn.inktodo;
import android.app.*;
import android.content.*;
import android.os.*;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.util.*;

/** Explicit real cached wallpaper updates; no account writes and no demo wallpaper. */
public final class ScreensaverStorageRunner extends Instrumentation {
 int count;StringBuilder log=new StringBuilder();
 void check(boolean value,String message){if(!value)throw new AssertionError(message);count++;log.append("PASS ").append(message).append('\n');}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle out=new Bundle();Context app=getTargetContext();
  try{
   ScreensaverController controller=ScreensaverController.get(app);
   check(controller.configured()&&!controller.prefs.getBoolean("source_demo",false),"uses configured real cached wallpaper");
   boolean automatic=controller.automatic();controller.setAutomatic(true);
   String previous="";
   for(int i=0;i<3;i++){
    controller.prefs.edit().remove("last_hash").commit();
    check(controller.refreshNow()==ScreensaverController.Outcome.UPDATED,"real SDK accepts consecutive update "+i);
    String path=controller.prefs.getString("last_import_path","");
    check(new File(path).getParent().equals(android.os.Environment.getExternalStoragePublicDirectory("Pictures").getAbsolutePath()),"import uses final BOOX directory "+i);
    check(!previous.equals(path),"consecutive update has fresh cache identity "+i);previous=path;
    Uri images=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    try(android.database.Cursor c=app.getContentResolver().query(images,new String[]{"owner_package_name"},"_data=?",new String[]{path},null)){
     check(c!=null&&c.moveToFirst()&&app.getPackageName().equals(c.getString(0)),"active file remains app-owned after system submission "+i);
    }
    SystemClock.sleep(800);
   }
   SystemClock.sleep(ScreensaverRetention.GRACE_MS+1800);
   ContentResolver resolver=app.getContentResolver();Uri images=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
   int ownedImports=0;String current=controller.prefs.getString("last_import_path","");
   try(android.database.Cursor c=resolver.query(images,new String[]{"_display_name","_data","owner_package_name"},"relative_path=?",new String[]{"Pictures/"},null)){
    while(c!=null&&c.moveToNext())if(ScreensaverRetention.generated(c.getString(0))&&app.getPackageName().equals(c.getString(2))){ownedImports++;check(c.getString(1).equals(current),"only current app-owned root import survives delayed cleanup");}
   }
   check(ownedImports==1,"three real submissions converge to one app-owned active image");
   check(new File(current).isFile()&&new File(controller.prefs.getString("last_path","")).isFile(),"active system image and fixed source both survive cleanup");
   check(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(new File(current).toPath()),java.nio.file.Files.readAllBytes(new File(controller.prefs.getString("last_path","")).toPath())),"current system file bytes match complete source image");
   controller.setAutomatic(automatic);
   out.putString("stream",log+"SOURCE_PATH="+controller.prefs.getString("last_path","")+"\nSDK_PATH="+current+"\nSUCCESS "+count+" real screensaver storage assertions\n");
  }catch(Throwable error){out.putString("stream",log+"FAIL "+android.util.Log.getStackTraceString(error));}
  finish(out.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
 }
}
