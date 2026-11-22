package cn.inktodo;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Separate disclosure target; tapping it never completes or opens a task. */
final class TreeToggle extends View {
    private final boolean collapsed;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    TreeToggle(Context context,boolean collapsed){super(context);this.collapsed=collapsed;setFocusable(true);setClickable(true);}
    @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
    @Override protected void onDraw(Canvas canvas){
        DrawFilter previous=canvas.getDrawFilter();
        try{
            canvas.setDrawFilter(InkRendering.SMOOTH);float unit=getResources().getDisplayMetrics().density;
            paint.setColor(Color.BLACK);paint.setStrokeWidth(2*unit);paint.setStrokeCap(Paint.Cap.SQUARE);
            float x=getWidth()/2f,y=16*unit;
            if(collapsed){canvas.drawLine(x+2*unit,y-5*unit,x-3*unit,y,paint);canvas.drawLine(x-3*unit,y,x+2*unit,y+5*unit,paint);}
            else{canvas.drawLine(x-5*unit,y-2*unit,x,y+3*unit,paint);canvas.drawLine(x,y+3*unit,x+5*unit,y-2*unit,paint);}
        }finally{canvas.setDrawFilter(previous);}
    }
}
