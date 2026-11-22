package cn.inktodo;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;

/** One public image per folder; atomic replacement prevents readers seeing a partial PNG. */
final class ScreensaverExport {
    private static final int LIMIT=10*1024*1024;
    static final String NAME="InkTodo-current.png";
    static boolean current(String path){return NAME.equals(new File(path).getName());}
    static final class Published {
        final Uri uri; final String path;
        Published(Uri uri,String path){this.uri=uri;this.path=path;}
    }
    static byte[] encode(Bitmap source) throws IOException {
        if(source==null || source.isRecycled())throw new IOException("预览图片已失效，请重新生成");
        Bitmap opaque=source.copy(Bitmap.Config.ARGB_8888,false);
        if(opaque==null)throw new IOException("无法准备屏保图片");
        try {
            opaque.setHasAlpha(false);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!opaque.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IOException("图片编码失败");
            byte[] data=bytes.toByteArray();validate(data,source.getWidth(),source.getHeight());return data;
        } finally {opaque.recycle();}
    }
    static void validate(byte[] data,int width,int height) throws IOException {
        if(data==null || data.length==0 || data.length>LIMIT)throw new IOException("图片大小无效");
        Bitmap image=BitmapFactory.decodeByteArray(data,0,data.length);
        if(image==null)throw new IOException("图片无法解码");
        try {
            if(image.getWidth()!=width || image.getHeight()!=height)throw new IOException("图片尺寸校验失败");
            boolean ink=false;
            for(int y=0;y<height && !ink;y+=4)for(int x=0;x<width;x+=4) {
                int pixel=image.getPixel(x,y);
                if(Color.alpha(pixel)>0 && Color.red(pixel)<220 && Color.green(pixel)<220 && Color.blue(pixel)<220){ink=true;break;}
            }
            if(!ink)throw new IOException("检测到空白图片，已取消设置");
        } finally {image.recycle();}
    }
    static Published publish(Context context,byte[] data,int width,int height) throws Exception {
        return publish(context,data,width,height,"InkTodo");
    }
    static synchronized Published publish(Context context,byte[] data,int width,int height,String folder) throws Exception {
        if(!folder.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid image directory");
        validate(data,width,height);
        ContentResolver resolver=context.getContentResolver();
        File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),folder);
        Uri collection=Build.VERSION.SDK_INT>=29?MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri=null;String path=null;boolean inserted=false;File temp=null;
        String[] columns=Build.VERSION.SDK_INT>=29?new String[]{"_id","_data","owner_package_name"}:new String[]{"_id","_data"};
        try(android.database.Cursor cursor=resolver.query(collection,columns,Build.VERSION.SDK_INT>=29?"relative_path=? AND _display_name=?":"_data=?",Build.VERSION.SDK_INT>=29?new String[]{"Pictures/"+folder+"/",NAME}:new String[]{new File(dir,NAME).getAbsolutePath()},null)){
            if(cursor!=null&&cursor.moveToFirst()){
                uri=ContentUris.withAppendedId(collection,cursor.getLong(0));path=cursor.getString(1);
                boolean owned=Build.VERSION.SDK_INT>=29?context.getPackageName().equals(cursor.getString(2)):context.getSharedPreferences("screensaver-owned-exports",0).contains(uri.toString());
                if(!owned)throw new IOException("同名图片不属于本应用，请移走后重试");
            }
        }
        try {
            if(uri==null){
                if(new File(dir,NAME).exists())throw new IOException("同名图片已存在，请移走后重试");
                ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,NAME);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
                if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/"+folder+"/");values.put(MediaStore.Images.Media.IS_PENDING,1);}
                else {if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建相册目录，请允许存储权限");values.put(MediaStore.Images.Media.DATA,new File(dir,NAME).getAbsolutePath());}
                uri=resolver.insert(collection,values);if(uri==null)throw new IOException("无法创建相册图片");inserted=true;
                try(OutputStream out=resolver.openOutputStream(uri,"w")){if(out==null)throw new IOException("无法写入相册图片");out.write(data);out.flush();}
                try(android.database.Cursor cursor=resolver.query(uri,new String[]{"_data"},null,null,null)){if(cursor!=null&&cursor.moveToFirst())path=cursor.getString(0);}
            } else {
                // Same directory and atomic rename: the previous complete image remains until the new one is ready.
                temp=new File(dir,".InkTodo-current.pending.png");
                try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);out.flush();out.getFD().sync();}
                android.system.Os.rename(temp.getAbsolutePath(),path);temp=null;
            }
            ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.SIZE,data.length);ready.put(MediaStore.Images.Media.DATE_MODIFIED,System.currentTimeMillis()/1000);
            if(Build.VERSION.SDK_INT>=29)ready.put(MediaStore.Images.Media.IS_PENDING,0);
            if(resolver.update(uri,ready,null,null)!=1)throw new IOException("图片发布失败");
            try(android.database.Cursor cursor=resolver.query(uri,new String[]{"_data"},null,null,null)){if(cursor!=null&&cursor.moveToFirst())path=cursor.getString(0);}
            if(path==null||!dir.getAbsolutePath().equals(new File(path).getParent())||!new File(path).isFile())throw new IOException("无法取得系统可读的图片路径");
            byte[] saved;
            try(InputStream in=resolver.openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                if(in==null)throw new IOException("已保存的图片无法读取");byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IOException("图片读取异常");out.write(buffer,0,n);}saved=out.toByteArray();
            }
            if(!java.util.Arrays.equals(data,saved))throw new IOException("图片保存后校验不一致");validate(saved,width,height);
            if(Build.VERSION.SDK_INT<29)context.getSharedPreferences("screensaver-owned-exports",0).edit().putBoolean(uri.toString(),true).apply();
            return new Published(uri,path);
        }catch(Exception error){if(temp!=null)temp.delete();if(inserted&&uri!=null)try{resolver.delete(uri,null,null);}catch(Exception ignored){}throw error;}
    }
}
