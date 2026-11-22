package cn.inktodo;
import android.app.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Exercise actual production widgets under a filter that clears anti-aliasing. No account/UI changes. */
public final class RenderingVerificationRunner extends Instrumentation {
    int count;StringBuilder log=new StringBuilder();
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);count++;log.append("PASS ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();final Throwable[] error={null};
        runOnMainSync(()->{try{
            DrawFilter incoming=new PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG,0);
            TextView label=new InkRendering.Label(getTargetContext());label.setText("平滑文字 1234567890");label.setTextSize(22);label.setTextColor(Color.BLACK);
            check(draw(label,incoming,false)>20,"production text keeps intermediate edge shades under no-AA filter");
            CountButton count=new CountButton(getTargetContext(),"示例",28,true);count.setBackgroundColor(Color.BLACK);
            check(draw(count,incoming,true)>10,"production selected count keeps antialiased edge shades");
            InkRendering.Background rounded=new InkRendering.Background();rounded.setColor(Color.BLACK);rounded.setCornerRadius(11);rounded.setBounds(0,0,333,91);
            Bitmap b=Bitmap.createBitmap(333,91,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.WHITE);c.setDrawFilter(incoming);rounded.draw(c);
            check(levels(b,0)>10,"rounded background keeps smooth corner coverage under no-AA filter");
            check(c.getDrawFilter()==incoming,"background restores caller's drawing filter");b.recycle();
            float density=getTargetContext().getResources().getDisplayMetrics().density;int center=48,y=Math.round(16*density),step=Math.round(3*density);
            Bitmap arrow=Bitmap.createBitmap(96,Math.max(64,Math.round(40*density)),Bitmap.Config.ARGB_8888);Canvas ac=new Canvas(arrow);ac.drawColor(Color.WHITE);
            TreeToggle folded=new TreeToggle(getTargetContext(),true);folded.layout(0,0,96,arrow.getHeight());folded.draw(ac);
            check(ink(arrow,center-step,y)&&!ink(arrow,center+step,y),"collapsed disclosure points left");
            ac.drawColor(Color.WHITE);TreeToggle expanded=new TreeToggle(getTargetContext(),false);expanded.layout(0,0,96,arrow.getHeight());expanded.draw(ac);
            check(ink(arrow,center,y+step)&&!ink(arrow,center,y-step),"expanded disclosure points down");arrow.recycle();
        }catch(Throwable e){error[0]=e;}});
        if(error[0]==null)out.putString("stream","\n"+log+"SUCCESS "+count+" rendering assertions\n");else out.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(error[0]));
        finish(error[0]==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
    }
    int draw(TextView view,DrawFilter incoming,boolean countOnly){
        Bitmap image=Bitmap.createBitmap(500,100,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);canvas.setDrawFilter(incoming);
        view.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));view.layout(0,0,500,100);view.draw(canvas);
        check(canvas.getDrawFilter()==incoming,"widget restores caller's drawing filter");int n=levels(image,countOnly?350:0);image.recycle();return n;
    }
    int levels(Bitmap b,int startX){Set<Integer> values=new HashSet<>();for(int y=0;y<b.getHeight();y++)for(int x=startX;x<b.getWidth();x++)values.add(b.getPixel(x,y)&0xffffff);return values.size();}
    boolean ink(Bitmap b,int x,int y){for(int yy=y-1;yy<=y+1;yy++)for(int xx=x-1;xx<=x+1;xx++)if(xx>=0&&yy>=0&&xx<b.getWidth()&&yy<b.getHeight()&&Color.red(b.getPixel(xx,yy))<100)return true;return false;}
}
