package cn.inktodo;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
/** Test APK only: a device-owned system font, exposed only while verifying the file picker. */
public final class FontFixtureProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    public String getType(Uri uri){return "font/ttf";}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        MatrixCursor c=new MatrixCursor(new String[]{android.provider.OpenableColumns.DISPLAY_NAME});c.addRow(new Object[]{uri.getLastPathSegment()});return c;
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"Roboto-Regular.ttf".equals(uri.getLastPathSegment()))throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File("/system/fonts/Roboto-Regular.ttf"),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
