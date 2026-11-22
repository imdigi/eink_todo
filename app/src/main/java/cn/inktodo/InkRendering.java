package cn.inktodo;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;

/** Keep edge coverage when BOOX supplies a draw filter that strips anti-aliasing.
 * Each drawing call restores its incoming filter; nothing changes outside this app. */
final class InkRendering {
    static final DrawFilter SMOOTH=new PaintFlagsDrawFilter(0,Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.FILTER_BITMAP_FLAG);
    static final class Label extends TextView {
        Label(Context c){super(c);}
        @Override protected void onDraw(Canvas c){DrawFilter previous=c.getDrawFilter();try{c.setDrawFilter(SMOOTH);super.onDraw(c);}finally{c.setDrawFilter(previous);}}
    }
    static class ButtonView extends Button {
        ButtonView(Context c){super(c);}
        @Override protected void onDraw(Canvas c){DrawFilter previous=c.getDrawFilter();try{c.setDrawFilter(SMOOTH);super.onDraw(c);}finally{c.setDrawFilter(previous);}}
    }
    static final class Input extends EditText {
        Input(Context c){super(c);}
        @Override protected void onDraw(Canvas c){DrawFilter previous=c.getDrawFilter();try{c.setDrawFilter(SMOOTH);super.onDraw(c);}finally{c.setDrawFilter(previous);}}
    }
    static final class Check extends CheckBox {
        Check(Context c){super(c);}
        @Override protected void onDraw(Canvas c){DrawFilter previous=c.getDrawFilter();try{c.setDrawFilter(SMOOTH);super.onDraw(c);}finally{c.setDrawFilter(previous);}}
    }
    static final class Background extends GradientDrawable {
        @Override public void draw(Canvas c){DrawFilter previous=c.getDrawFilter();try{c.setDrawFilter(SMOOTH);super.draw(c);}finally{c.setDrawFilter(previous);}}
    }
}
