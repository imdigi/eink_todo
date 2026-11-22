package cn.inktodo;

import android.content.Context;
import android.graphics.Typeface;
import java.io.*;
import java.util.*;

/** Validated TrueType files copied into app-private storage; external URI access is temporary. */
final class FontStore {
    static final int LIMIT=48*1024*1024;
    static final class Choice {
        final String file,name;final Typeface face;
        Choice(String file,String name,Typeface face){this.file=file;this.name=name;this.face=face;}
    }
    static Choice system(){return new Choice("","系统默认",Typeface.DEFAULT);}
    static File directory(Context c){return new File(c.getFilesDir(),"fonts");}
    static Choice load(Context c,String file,String name){
        if(file==null || !file.matches("font-[a-f0-9-]+\\.ttf"))return system();
        File f=new File(directory(c),file);
        try{validate(f);return new Choice(file,name,new Typeface.Builder(f).build());}catch(Exception e){return system();}
    }
    static Choice copy(Context c,InputStream source,String name)throws IOException{
        if(name==null || !name.toLowerCase(Locale.ROOT).endsWith(".ttf"))throw new IOException("请选择 TTF 格式的字体文件");
        File dir=directory(c);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建字体目录");
        File temp=File.createTempFile("import-",".tmp",dir);File ready=new File(dir,"font-"+UUID.randomUUID()+".ttf");
        try{
            try(FileOutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[8192];int total=0,n;
                while((n=source.read(buffer))!=-1){total+=n;if(total>LIMIT)throw new IOException("字体超过 48 MB，请选择较小的 TTF 文件");out.write(buffer,0,n);}
                out.getFD().sync();
            }
            validate(temp);Typeface face;
            try{face=new Typeface.Builder(temp).build();}catch(RuntimeException e){throw new IOException("字体无法加载，请换一个 TTF 文件",e);}
            if(!temp.renameTo(ready))throw new IOException("字体保存失败");
            // Reopen the immutable final file so a saved selection never refers to a temporary path.
            face=new Typeface.Builder(ready).build();
            String label=new File(name).getName().replaceAll("[\\p{Cntrl}]","");
            if(label.length()>100)label=label.substring(0,96)+".ttf";
            return new Choice(ready.getName(),label,face);
        }catch(Exception e){ready.delete();if(e instanceof IOException)throw (IOException)e;throw new IOException("字体无法加载，请换一个 TTF 文件",e);}
        finally{temp.delete();}
    }
    static void validate(File file)throws IOException{
        long length=file.length();if(length<28 || length>LIMIT)throw new IOException("字体文件为空、损坏或过大");
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            int signature=input.readInt();if(signature!=0x00010000 && signature!=0x74727565)throw new IOException("文件不是有效的 TrueType 字体");
            int tables=input.readUnsignedShort();if(tables<1 || tables>256 || 12L+tables*16L>length)throw new IOException("字体目录损坏");
            Set<Integer> tags=new HashSet<>();input.seek(12);
            for(int i=0;i<tables;i++){
                int tag=input.readInt();input.readInt();long offset=input.readInt()&0xffffffffL,size=input.readInt()&0xffffffffL;
                if(offset<12L+tables*16L || offset>length || size>length-offset)throw new IOException("字体数据不完整");tags.add(tag);
            }
            for(int tag:new int[]{0x636d6170,0x68656164,0x6d617870,0x68686561,0x686d7478,0x676c7966,0x6c6f6361})if(!tags.contains(tag))throw new IOException("TTF 缺少必要的字体数据");
        }
    }
    static void discard(Context c,Choice choice,String active){
        if(choice!=null && !choice.file.isEmpty() && !choice.file.equals(active))new File(directory(c),choice.file).delete();
    }
}
