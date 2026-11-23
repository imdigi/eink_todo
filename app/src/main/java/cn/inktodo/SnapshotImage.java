package cn.inktodo;

import android.graphics.*;
import android.text.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Static expanded trees; reserve space for both date sections even when one overflows. */
final class SnapshotImage {
    private static final Typeface HEADING_FACE=headingFace();
    static Typeface headingTypeface(){return HEADING_FACE;}
    private static Typeface headingFace(){
        // Some BOOX firmware falls back to Chinese sans even for Typeface.SERIF.
        try {java.io.File font=new java.io.File("/system/fonts/NotoSerifCJK-Regular.ttc");if(font.isFile())return new Typeface.Builder(font).setTtcIndex(2).build();}catch(RuntimeException ignored){}
        return Typeface.SERIF;
    }
    private static StaticLayout layout(String text,TextPaint paint,int width,int lines){
        return StaticLayout.Builder.obtain(text,0,text.length(),paint,Math.max(40,width)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
    }
    static Bitmap create(int w,int h,String title,List<Core.Task> tasks,Map<String,String> projects,long synced,int pending){return create(w,h,title,tasks,projects,synced,pending,HEADING_FACE);}
    static Bitmap create(int w,int h,String title,List<Core.Task> tasks,Map<String,String> projects,long synced,int pending,Typeface face){return create(w,h,SnapshotModel.legacy(title,tasks,projects,pending),synced,face);}
    private static final class Row {
        final Core.TreeRow node;final StaticLayout title,description,meta;final String project;final int indent;final float height;
        Row(Core.TreeRow node,SnapshotModel model,int content,float unit,Typeface face){
            this.node=node;indent=Math.round(Math.min(node.depth,4)*42*unit);int width=content-indent-Math.round(58*unit);
            title=layout(node.task.content,paint(44*unit,face,node.parent),width,2);
            TextPaint descriptionPaint=paint(30*unit,face,false);descriptionPaint.setColor(0xff252525);
            description=!node.context&&!node.task.description.trim().isEmpty()?layout(node.task.description.trim(),descriptionPaint,width,1):null;
            Core.Task t=node.task;LocalDate date=Core.dueDay(t.due,ZoneId.systemDefault());
            String detail=date==null?"":(date.isBefore(model.day)?"逾期 ":"")+date.format(DateTimeFormatter.ofPattern("M/d"));
            if(t.recurring)detail+=(detail.isEmpty()?"":" · ")+"重复";
            if(t.priority>1)detail+=(detail.isEmpty()?"":" · ")+"P"+(5-t.priority);
            project=node.context||model.filter.equals("project")?"":model.names.getOrDefault(t.projectId,"");
            TextPaint metaPaint=paint(28*unit,face,false);metaPaint.setColor(0xff333333);
            int projectSpace=project.isEmpty()?0:Math.min(width/3,Math.round(metaPaint.measureText(project)))+Math.round(24*unit);
            meta=node.context||detail.isEmpty()&&project.isEmpty()?null:layout(detail.isEmpty()?" ":detail,metaPaint,width-projectSpace,1);
            height=title.getHeight()+(description==null?0:description.getHeight()+10*unit)+(meta==null?0:meta.getHeight()+8*unit)+(node.parent?16:28)*unit;
        }
    }
    private static TextPaint paint(float size,Typeface face,boolean bold){TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);p.setColor(Color.BLACK);p.setTextSize(size);p.setTextLocale(Locale.SIMPLIFIED_CHINESE);p.setTypeface(Typeface.create(face,bold?Typeface.BOLD:Typeface.NORMAL));return p;}
    private static void draw(Canvas c,StaticLayout words,float x,float y){c.save();c.translate(x,y);words.draw(c);c.restore();}
    static Bitmap create(int width,int height,SnapshotModel model,long synced,Typeface face){
        if(width<320||height<320||width>4096||height>4096)throw new IllegalArgumentException("Invalid image size");
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);c.drawColor(Color.WHITE);
        float unit=Math.max(.5f,Math.min(width,height)/1264f);int margin=Math.round(68*unit),content=width-2*margin;
        float headingUnit=unit*Math.min(1f,height/(1680*unit));
        TextPaint termPaint=paint(132*headingUnit,face,false);
        String term=SolarTerm.name(model.day);float y=80*headingUnit,termX=margin;
        // CJK collections reserve extreme vertical metrics for uncommon glyphs. Align visible ink,
        // not the collection's maximum bounds, so the header matches the approved composition.
        Rect bounds=new Rect();termPaint.getTextBounds(term,0,term.length(),bounds);
        for(int i=0;i<term.length();i++){String letter=term.substring(i,i+1);c.drawText(letter,termX,y-bounds.top,termPaint);termX+=termPaint.measureText(letter)+termPaint.getTextSize()*.18f;}
        y+=bounds.height()+16*headingUnit;
        TextPaint datePaint=paint(34*headingUnit,face,false);String date=SolarTerm.date(model.day);datePaint.getTextBounds(date,0,date.length(),bounds);c.drawText(date,margin,y-bounds.top,datePaint);y+=bounds.height()+52*headingUnit;
        Paint rule=new Paint(Paint.ANTI_ALIAS_FLAG);rule.setColor(0xff666666);rule.setStrokeWidth(Math.max(1,unit));
        List<List<Row>> groups=new ArrayList<>();float[] desired=new float[model.sections.size()],space=new float[desired.length];
        float budget=Math.max(0,height-88*unit-y);
        for(int i=0;i<desired.length;i++){
            SnapshotModel.Section section=model.sections.get(i);List<Row> rows=new ArrayList<>();groups.add(rows);
            desired[i]=section.name.isEmpty()?0:94*unit;
            if(section.rows.isEmpty())desired[i]+=60*unit;
            for(Core.TreeRow node:section.rows){Row row=new Row(node,model,content,unit,face);rows.add(row);desired[i]+=row.height;}
            desired[i]+=56*unit;space[i]=Math.min(desired[i],budget/desired.length);
        }
        float left=budget;for(float part:space)left-=part;
        for(int i=0;i<space.length;i++){float extra=Math.min(Math.max(0,desired[i]-space[i]),left);space[i]+=extra;left-=extra;}
        for(int group=0;group<groups.size();group++){
            SnapshotModel.Section section=model.sections.get(group);float bottom=y+space[group],rowY=y;
            if(!section.name.isEmpty()){
                c.drawText(section.name,margin,rowY+44*unit,paint(44*unit,face,false));TextPaint count=paint(28*unit,face,false);count.setColor(0xff333333);String n=section.count+" 项";c.drawText(n,width-margin-count.measureText(n),rowY+44*unit,count);c.drawLine(margin,rowY+64*unit,width-margin,rowY+64*unit,rule);rowY+=94*unit;
            }
            int shown=0;List<Row> rows=groups.get(group);
            if(rows.isEmpty()&&rowY+34*unit<bottom)c.drawText("暂无任务",margin,rowY+30*unit,paint(30*unit,face,false));
            for(int i=0;i<rows.size();i++){
                Row row=rows.get(i);float required=row.height;
                if(row.node.context){for(int j=i+1;j<rows.size();j++){Row child=rows.get(j);if(child.node.depth<=row.node.depth)break;required+=child.height;if(!child.node.context)break;}}
                if(rowY+required>bottom-34*unit)break;
                float x=margin+row.indent,textX=x+54*unit;
                rule.setColor(Color.BLACK);rule.setStyle(Paint.Style.STROKE);rule.setStrokeWidth(Math.max(1.5f,2*unit));c.drawRect(x,rowY+11*unit,x+32*unit,rowY+43*unit,rule);rule.setStyle(Paint.Style.FILL);rule.setStrokeWidth(Math.max(1,unit));rule.setColor(0xff666666);
                draw(c,row.title,textX,rowY);float next=rowY+row.title.getHeight();
                if(row.description!=null){next+=10*unit;draw(c,row.description,textX,next);next+=row.description.getHeight();}
                if(row.meta!=null){next+=8*unit;draw(c,row.meta,textX,next);if(!row.project.isEmpty()){TextPaint p=paint(28*unit,face,false);p.setColor(0xff333333);String name=TextUtils.ellipsize(row.project,p,(content-row.indent-58*unit)/3,TextUtils.TruncateAt.END).toString();c.drawText(name,width-margin-p.measureText(name),next-row.meta.getPaint().getFontMetrics().ascent,p);}}
                rowY+=row.height;if(!row.node.context)shown++;
            }
            if(shown<section.count)c.drawText("另有 "+(section.count-shown)+" 项，请唤醒查看",margin,bottom-9*unit,paint(28*unit,face,false));
            y=bottom;
        }
        return image;
    }
}
