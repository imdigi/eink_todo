package cn.inktodo;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.CompoundButton;

/** Monochrome settings controls with no thumb animations, shadows or theme tint. */
final class SettingsWidgets {
    static final class Toggle extends CompoundButton {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Toggle(Context context){super(context);setBackgroundColor(Color.TRANSPARENT);setMinWidth(0);setMinimumWidth(0);setPadding(0,0,0,0);setStateListAnimator(null);}
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Switch";}
        @Override protected void onMeasure(int w,int h){setMeasuredDimension(resolveSize(dp(60),w),resolveSize(dp(48),h));}
        private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
        @Override protected void onDraw(Canvas canvas){
            DrawFilter old=canvas.getDrawFilter();canvas.setDrawFilter(InkRendering.SMOOTH);
            try{float u=getResources().getDisplayMetrics().density,cx=getWidth()/2f,cy=getHeight()/2f,r=12*u;
                paint.setColor(isEnabled()?Color.BLACK:0xff999999);paint.setStyle(isChecked()?Paint.Style.FILL:Paint.Style.STROKE);paint.setStrokeWidth(1.7f*u);
                canvas.drawRoundRect(cx-25*u,cy-r,cx+25*u,cy+r,r,r,paint);paint.setStyle(Paint.Style.FILL);
                paint.setColor(isChecked()?Color.WHITE:(isEnabled()?Color.BLACK:0xff999999));canvas.drawCircle(cx+(isChecked()?13:-13)*u,cy,7*u,paint);
            }finally{canvas.setDrawFilter(old);}
        }
    }
    static final class Rule extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Rule(Context context){super(context);setWillNotDraw(false);}
        @Override protected void onDraw(Canvas canvas){paint.setColor(0xffbbbbbb);paint.setStrokeWidth(1);canvas.drawLine(0,getHeight()/2f,getWidth(),getHeight()/2f,paint);}
    }
    static final class Step extends View {
        final boolean down;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Step(Context context,boolean down){super(context);this.down=down;setClickable(true);setFocusable(true);setContentDescription(down?"下一页":"上一页");}
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
        @Override protected void onDraw(Canvas canvas){float u=getResources().getDisplayMetrics().density,x=getWidth()/2f,y=getHeight()/2f,sign=down?1:-1;paint.setColor(isEnabled()?Color.BLACK:0xffbbbbbb);paint.setStrokeWidth(2*u);paint.setStrokeCap(Paint.Cap.SQUARE);canvas.drawLine(x-8*u,y-4*u*sign,x,y+4*u*sign,paint);canvas.drawLine(x,y+4*u*sign,x+8*u,y-4*u*sign,paint);}
    }
}
