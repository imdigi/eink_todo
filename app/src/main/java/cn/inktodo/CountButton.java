package cn.inktodo;

import android.content.Context;
import android.graphics.*;
import android.widget.Button;

/** Native label plus a separate right-aligned, subdued count. */
final class CountButton extends InkRendering.ButtonView {
    private final Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final String number;
    private final int inset;
    CountButton(Context context, String name, int count, boolean selected) {
        this(context,name,count,selected,Typeface.DEFAULT);
    }
    CountButton(Context context, String name, int count, boolean selected,Typeface face) {
        super(context);
        number = String.valueOf(count); inset = Math.round(10 * getResources().getDisplayMetrics().density);
        countPaint.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
        countPaint.setColor(selected ? Color.WHITE : 0xff666666);
        countPaint.setTypeface(face==Typeface.DEFAULT?Typeface.create("sans-serif-medium",Typeface.NORMAL):Typeface.create(face,Typeface.BOLD));
        setTypeface(face);
        setText(name);setAllCaps(false);setTextColor(selected ? Color.WHITE : Color.BLACK);
        setTextSize(17);setMaxLines(3);setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));setMinimumHeight(getMinHeight());setMinWidth(0);setMinimumWidth(0);
        setPadding(inset,Math.round(6*getResources().getDisplayMetrics().density),inset*2+(int)Math.ceil(countPaint.measureText(number)),Math.round(6*getResources().getDisplayMetrics().density));
        setStateListAnimator(null);setContentDescription(name+"，"+number+"项");
        setElevation(0);setTranslationZ(0);setOutlineProvider(null);
        getPaint().setAntiAlias(true);getPaint().setSubpixelText(true);
    }
    @Override protected void onDraw(Canvas canvas) {
        DrawFilter previous=canvas.getDrawFilter();
        try{
            canvas.setDrawFilter(InkRendering.SMOOTH);super.onDraw(canvas);
            Paint.FontMetrics metrics=countPaint.getFontMetrics();
            canvas.drawText(number,getWidth()-inset-countPaint.measureText(number),(getHeight()-metrics.ascent-metrics.descent)/2f,countPaint);
        }finally{canvas.setDrawFilter(previous);}
    }
}
