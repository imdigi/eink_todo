package cn.inktodo;

import android.app.*;
import android.os.Bundle;
import android.graphics.*;
import android.provider.MediaStore;
import java.util.*;
import org.json.JSONObject;

/** Writes and removes only its own sample gallery exports; never sets a system image. */
public final class ScreensaverVerificationRunner extends Instrumentation {
    private int count;private final StringBuilder log=new StringBuilder();
    private void check(boolean ok,String name){if(!ok)throw new AssertionError(name);count++;log.append("PASS ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();List<ScreensaverExport.Published> created=new ArrayList<>();
        try {
            List<Core.Task> tasks=Collections.singletonList(new Core.Task(new JSONObject().put("id","fixture").put("project_id","demo").put("content","屏保导出验证示例")));
            Bitmap source=SnapshotImage.create(1264,1680,"验证",tasks,Collections.singletonMap("demo","示例"),System.currentTimeMillis(),0);
            byte[] bytes=ScreensaverExport.encode(source);source.recycle();
            ScreensaverExport.validate(bytes,1264,1680);check(true,"encoded image remains valid after preview bitmap is recycled");
            check(bytes.length>0 && bytes.length<10*1024*1024,"encoded image fits SDK size limit");
            check((bytes[25]&255)!=6,"PNG encoded without alpha channel");
            try{ScreensaverExport.validate(new byte[]{1,2,3},1264,1680);throw new AssertionError("corrupt accepted");}catch(java.io.IOException expected){check(true,"corrupt image rejected");}
            try{ScreensaverExport.validate(bytes,640,480);throw new AssertionError("wrong dimensions");}catch(java.io.IOException expected){check(true,"wrong dimensions rejected");}
            Bitmap white=Bitmap.createBitmap(1264,1680,Bitmap.Config.ARGB_8888);white.eraseColor(Color.WHITE);
            try{ScreensaverExport.encode(white);throw new AssertionError("white accepted");}catch(java.io.IOException expected){check(true,"blank white image rejected before publication");}finally{white.recycle();}
            ScreensaverExport.Published first=ScreensaverExport.publish(getTargetContext(),bytes,1264,1680,"InkTodoExportVerification");created.add(first);
            check(!first.path.contains("/Android/data/") && first.path.contains("/Pictures/InkTodoExportVerification/"),"published image is outside app-private Android/data");
            Bitmap read=BitmapFactory.decodeFile(first.path);check(read!=null && read.getWidth()==1264 && read.getHeight()==1680,"published file can be decoded from raw path");if(read!=null)read.recycle();
            if(android.os.Build.VERSION.SDK_INT>=29){
                try(android.database.Cursor cursor=getTargetContext().getContentResolver().query(first.uri,new String[]{MediaStore.Images.Media.IS_PENDING},null,null,null)){
                    check(cursor!=null && cursor.moveToFirst() && cursor.getInt(0)==0,"MediaStore image is fully published before submission");
                }
                Bitmap thumbnail=getTargetContext().getContentResolver().loadThumbnail(first.uri,new android.util.Size(256,256),null);
                check(thumbnail!=null && thumbnail.getWidth()>0,"system media provider can generate a thumbnail");if(thumbnail!=null)thumbnail.recycle();
            }
            long length=new java.io.File(first.path).length();
            ScreensaverExport.Published second=ScreensaverExport.publish(getTargetContext(),bytes,1264,1680,"InkTodoExportVerification");created.add(second);
            check(first.path.equals(second.path) && first.uri.equals(second.uri),"consecutive exports reuse the same file and MediaStore entry");
            check(new java.io.File(first.path).length()==length,"replacement preserves complete decodable image");
            Bitmap changed=Bitmap.createBitmap(1264,1680,Bitmap.Config.ARGB_8888);changed.eraseColor(Color.LTGRAY);byte[] replacement=ScreensaverExport.encode(changed);changed.recycle();
            ScreensaverExport.publish(getTargetContext(),replacement,1264,1680,"InkTodoExportVerification");
            check(Arrays.equals(replacement,java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(first.path))),"atomic overwrite changes content at the same path");
            try{ScreensaverExport.publish(getTargetContext(),new byte[]{1,2,3},1264,1680,"InkTodoExportVerification");throw new AssertionError("invalid overwrite accepted");}catch(java.io.IOException expected){}
            check(Arrays.equals(replacement,java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(first.path))),"invalid replacement leaves last complete file intact");
            check(!new java.io.File(new java.io.File(first.path).getParentFile(),".InkTodo-current.pending.png").exists(),"temporary file removed after atomic publication");
            ScreensaverExport.Published legacy=fixture(bytes,"InkTodo-"+(System.currentTimeMillis()-60000)+"-"+UUID.randomUUID()+".png");created.add(legacy);
            ScreensaverExport.Published protectedImage=fixture(bytes,"InkTodo-"+(System.currentTimeMillis()-60000)+"-"+UUID.randomUUID()+".png");created.add(protectedImage);
            ScreensaverExport.Published unrelated=fixture(bytes,"user-photo.png");created.add(unrelated);
            Set<String> keep=new HashSet<>();keep.add(protectedImage.path);
            check(new ScreensaverRetention(getTargetContext(),"InkTodoExportVerification").prune(keep,System.currentTimeMillis())==1,"migration deletes only unprotected app-owned historical export");
            check(!new java.io.File(legacy.path).exists(),"old timestamped export is removed");
            check(new java.io.File(first.path).isFile()&&new java.io.File(protectedImage.path).isFile(),"current and protected images survive cleanup");
            check(new java.io.File(unrelated.path).isFile(),"unrelated image in same folder survives cleanup");
            result.putString("stream","\n"+log+"SUCCESS "+count+" screensaver export assertions\n");
        }catch(Throwable e){result.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{for(ScreensaverExport.Published image:created)try{getTargetContext().getContentResolver().delete(image.uri,null,null);}catch(Exception ignored){}}
        finish(result.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    ScreensaverExport.Published fixture(byte[] data,String name)throws Exception{
        android.content.ContentResolver resolver=getTargetContext().getContentResolver();android.content.ContentValues values=new android.content.ContentValues();
        values.put("_display_name",name);values.put("mime_type","image/png");values.put("relative_path","Pictures/InkTodoExportVerification/");values.put("is_pending",1);
        android.net.Uri uri=resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
        try(java.io.OutputStream out=resolver.openOutputStream(uri)){out.write(data);}
        values.clear();values.put("is_pending",0);resolver.update(uri,values,null,null);
        try(android.database.Cursor cursor=resolver.query(uri,new String[]{"_data"},null,null,null)){if(!cursor.moveToFirst())throw new AssertionError("fixture path unavailable");return new ScreensaverExport.Published(uri,cursor.getString(0));}
    }

}
