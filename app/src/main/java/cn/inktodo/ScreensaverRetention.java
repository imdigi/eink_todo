package cn.inktodo;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/** Automatic deletion is limited to owned exports; historical BOOX copies require system consent. */
final class ScreensaverRetention {
    static final long GRACE_MS=10000;
    private static final Pattern GENERATED=Pattern.compile("InkTodo-[0-9]{13}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png");
    final Context context;final String folder;
    ScreensaverRetention(Context context){this(context,"InkTodo");}
    ScreensaverRetention(Context context,String folder){this.context=context.getApplicationContext();this.folder=folder;}
    static boolean generated(String name){return name!=null&&GENERATED.matcher(name).matches();}
    private void report(Exception error){context.getSharedPreferences("screensaver",0).edit().putString("cleanup_error","屏保旧图清理未完成，请在待办屏保中重试").apply();}
    static String systemPath(String source){return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),new File(source).getName()).getAbsolutePath();}
    List<Uri> legacyCopies(Set<String> protectedPaths)throws Exception{
        List<Uri> result=new ArrayList<>();ContentResolver resolver=context.getContentResolver();
        Uri collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath();
        try(Cursor cursor=resolver.query(collection,new String[]{"_id","_display_name","_data","owner_package_name"},"relative_path=?",new String[]{"Pictures/"},null)){
            if(cursor==null)return result;
            while(cursor.moveToNext()){
                String name=cursor.getString(1),path=cursor.getString(2);
                if(path==null||!dir.equals(new File(path).getParent())||protectedPaths.contains(path))continue;
                if(!generated(name)&&!name.equals(ScreensaverExport.NAME)&&!name.equals("ink-todo-screensaver.png"))continue;
                if(generated(name)&&created(name)>System.currentTimeMillis()-GRACE_MS)continue;
                if(context.getPackageName().equals(cursor.getString(3)))continue;
                result.add(ContentUris.withAppendedId(collection,cursor.getLong(0)));
            }
        }return result;
    }
    private long created(String name){try{return Long.parseLong(name.substring(8,21));}catch(Exception error){return Long.MAX_VALUE;}}
    int prune(Set<String> protectedPaths,long now){
        int removed=pruneFolder(folder,protectedPaths,now);
        if(folder.equals("InkTodo"))removed+=pruneFolder("",protectedPaths,now);
        return removed;
    }
    private int pruneFolder(String directory,Set<String> protectedPaths,long now){
        ContentResolver resolver=context.getContentResolver();int removed=0;
        String dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),directory).getAbsolutePath();
        Uri collection=Build.VERSION.SDK_INT>=29?MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] columns=Build.VERSION.SDK_INT>=29?new String[]{"_id","_display_name","_data","relative_path","owner_package_name","is_pending"}:new String[]{"_id","_display_name","_data"};
        List<Uri> candidates=new ArrayList<>();
        try(Cursor cursor=resolver.query(collection,columns,Build.VERSION.SDK_INT>=29?"relative_path=?":"_data LIKE ?",new String[]{Build.VERSION.SDK_INT>=29?ScreensaverExport.relativePath(directory):dir+"/%"},null)){
            if(cursor==null)return 0;
            while(cursor.moveToNext()){
                String name=cursor.getString(1),path=cursor.getString(2);Uri uri=ContentUris.withAppendedId(collection,cursor.getLong(0));
                if(!generated(name)||path==null||!dir.equals(new File(path).getParent())||protectedPaths.contains(path)||created(name)>now-GRACE_MS)continue;
                if(Build.VERSION.SDK_INT>=29&&(!context.getPackageName().equals(cursor.getString(4))||cursor.getInt(5)!=0))continue;
                // On legacy Android, only explicitly recorded exports are eligible for deletion.
                if(Build.VERSION.SDK_INT<29&&!context.getSharedPreferences("screensaver-owned-exports",0).contains(uri.toString()))continue;
                candidates.add(uri);
            }
        }catch(Exception error){report(error);return 0;}
        for(Uri uri:candidates)try{removed+=resolver.delete(uri,null,null);context.getSharedPreferences("screensaver-owned-exports",0).edit().remove(uri.toString()).apply();}catch(Exception error){report(error);}
        return removed;
    }
}
