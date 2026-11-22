package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.onyx.android.sdk.api.device.epd.*;
import java.io.*;
import java.util.*;

/** Sample-only screen comparing device rendering paths, without changing system settings. */
public final class RenderingDiagnosticRunner extends Instrumentation {
    Activity activity;LinearLayout panel;View[] samples=new View[4];UpdateOption original;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    View sample(){return new View(activity){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        @Override protected void onDraw(Canvas c){c.drawColor(Color.BLACK);p.setColor(0xffbdbdbd);p.setTextSize(28);c.drawText("1234567890",20,65,p);}
    };}
    @Override public void onStart(){Bundle out=new Bundle();StringBuilder log=new StringBuilder();
        try{
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(()->{
                panel=new LinearLayout(activity);panel.setOrientation(1);panel.setBackgroundColor(Color.WHITE);
                for(int i=0;i<4;i++){
                    TextView label=new TextView(activity);label.setText(new String[]{"Native hardware text","Software layer text","Direct bitmap text","Forced anti-alias draw filter"}[i]);label.setTextColor(Color.BLACK);panel.addView(label);
                    if(i<2){samples[i]=sample();if(i==1)samples[i].setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
                    else if(i==2){Bitmap b=Bitmap.createBitmap(600,100,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.BLACK);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);p.setTextSize(28);p.setColor(0xffbdbdbd);c.drawText("1234567890",20,65,p);
                        Set<Integer> levels=new HashSet<>();for(int y=0;y<100;y++)for(int x=0;x<600;x++)levels.add(b.getPixel(x,y)&0xffffff);log.append("Direct bitmap source colors ").append(levels.size()).append('\n');
                        ImageView image=new ImageView(activity);image.setImageBitmap(b);image.setScaleType(ImageView.ScaleType.FIT_XY);samples[i]=image;}
                    else{samples[i]=new View(activity){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);@Override protected void onDraw(Canvas c){DrawFilter previous=c.getDrawFilter();try{c.setDrawFilter(new PaintFlagsDrawFilter(0,Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG));c.drawColor(Color.BLACK);p.setColor(0xffbdbdbd);p.setTextSize(28);c.drawText("1234567890",20,65,p);}finally{c.setDrawFilter(previous);}}};}
                    panel.addView(samples[i],new LinearLayout.LayoutParams(600,100));
                }activity.setContentView(panel);
            });waitForIdleSync();
            original=EpdController.getAppScopeRefreshMode();log.append("Original app mode ").append(original).append('\n');
            for(UpdateMode mode:new UpdateMode[]{UpdateMode.GU,UpdateMode.GC,UpdateMode.GCC}){
                if(mode==UpdateMode.GCC)runOnMainSync(()->{EpdController.setAppScopeRefreshMode(UpdateOption.NORMAL);log.append("NORMAL app mode readback ").append(EpdController.getAppScopeRefreshMode()).append('\n');});
                runOnMainSync(()->{boolean accepted=EpdController.setViewDefaultUpdateMode(panel,mode);EpdController.invalidate(panel,mode);log.append("Requested ").append(mode).append(" accepted ").append(accepted).append(" readback ").append(EpdController.getViewDefaultUpdateMode(panel)).append('\n');});
                android.os.SystemClock.sleep(700);Bitmap screenshot=getUiAutomation().takeScreenshot();
                if(screenshot==null)throw new IOException("Screenshot unavailable");
                for(int i=0;i<4;i++){
                    final int[] xy=new int[2];final View v=samples[i];runOnMainSync(()->v.getLocationOnScreen(xy));
                    Set<Integer> levels=new HashSet<>();for(int y=xy[1];y<xy[1]+100;y++)for(int x=xy[0];x<xy[0]+600;x++)levels.add(screenshot.getPixel(x,y)&0xffffff);
                    log.append("Sample ").append(i).append(" unique colors ").append(levels.size()).append('\n');
                }
                int[] at=new int[2];runOnMainSync(()->panel.getLocationOnScreen(at));
                Bitmap crop=Bitmap.createBitmap(screenshot,0,at[1],600,Math.min(panel.getHeight(),650));
                File f=new File(getTargetContext().getExternalFilesDir("Pictures"),"render-diagnostic-"+mode+".png");try(FileOutputStream stream=new FileOutputStream(f)){crop.compress(Bitmap.CompressFormat.PNG,100,stream);}crop.recycle();screenshot.recycle();
            }
            out.putString("stream","\n"+log+"Diagnostic complete; no global setting changed\n");
        }catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{if(original!=null)runOnMainSync(()->EpdController.setAppScopeRefreshMode(original));if(panel!=null)runOnMainSync(()->EpdController.resetViewUpdateMode(panel));if(activity!=null)runOnMainSync(()->activity.finish());}
        finish(Activity.RESULT_OK,out);
    }
}
