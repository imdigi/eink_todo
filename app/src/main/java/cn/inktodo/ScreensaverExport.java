package cn.inktodo;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.util.UUID;

/** Publish a complete, immutable image outside Android/data before handing its path to BOOX. */
final class ScreensaverExport {
    private static final int LIMIT=10*1024*1024;
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
        validate(data,width,height);
        ContentResolver resolver=context.getContentResolver();
        String name="InkTodo-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".png";
        ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        Uri uri=null;File legacy=null,temp=null;
        try {
            if(Build.VERSION.SDK_INT>=29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/InkTodo");values.put(MediaStore.Images.Media.IS_PENDING,1);
                uri=resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
                if(uri==null)throw new IOException("无法创建相册图片");
                try(OutputStream out=resolver.openOutputStream(uri,"w")) {
                    if(out==null)throw new IOException("无法写入相册图片");out.write(data);out.flush();
                }
                ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);
                if(resolver.update(uri,ready,null,null)!=1)throw new IOException("图片发布失败");
            } else {
                File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"InkTodo");
                if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("无法创建相册目录，请允许存储权限");
                legacy=new File(dir,name);temp=new File(dir,"."+name+".pending");
                try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);out.flush();out.getFD().sync();}
                if(!temp.renameTo(legacy))throw new IOException("无法完成图片保存");
                values.put(MediaStore.Images.Media.DATA,legacy.getAbsolutePath());uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                if(uri==null)throw new IOException("无法将图片加入相册");
            }
            byte[] saved;
            try(InputStream in=resolver.openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                if(in==null)throw new IOException("已保存的图片无法读取");byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IOException("图片读取异常");out.write(buffer,0,n);}saved=out.toByteArray();
            }
            if(!java.util.Arrays.equals(data,saved))throw new IOException("图片保存后校验不一致");validate(saved,width,height);
            String path="";
            try(android.database.Cursor cursor=resolver.query(uri,new String[]{MediaStore.Images.Media.DATA},null,null,null)) {
                if(cursor!=null && cursor.moveToFirst())path=cursor.getString(0);
            }
            if(path==null || path.isEmpty() || path.contains("/Android/data/") || !new File(path).isFile())throw new IOException("无法取得系统可读的图片路径");
            return new Published(uri,path);
        } catch(Exception error) {
            if(uri!=null)try{resolver.delete(uri,null,null);}catch(Exception ignored){}
            if(temp!=null)temp.delete();if(legacy!=null)legacy.delete();throw error;
        }
    }
}
