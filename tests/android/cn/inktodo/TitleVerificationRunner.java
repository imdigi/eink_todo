package cn.inktodo;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/** Changes only the title preference to fixtures, then restores the exact previous value. */
public final class TitleVerificationRunner extends Instrumentation {
    int count;StringBuilder log=new StringBuilder();Activity activity;
    void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;log.append("PASS ").append(name).append('\n');}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();SharedPreferences prefs=getTargetContext().getSharedPreferences("settings",0);
        boolean existed=prefs.contains("title_markdown");String original=prefs.getString("title_markdown",TitleMarkdown.DEFAULT);
        try{
            Spanned rendered=(Spanned)TitleMarkdown.render("# 一级\n## 二级\n### 三级\n*斜体* **粗体** ~~删除~~\n末行");
            check(rendered.toString().equals("一级\n二级\n三级\n斜体 粗体 删除\n末行"),"headings and line breaks render without Markdown markers");
            RelativeSizeSpan[] sizes=rendered.getSpans(0,rendered.length(),RelativeSizeSpan.class);
            check(sizes.length==3 && sizes[0].getSizeChange()>sizes[1].getSizeChange() && sizes[1].getSizeChange()>sizes[2].getSizeChange(),"three heading levels have descending sizes");
            check(rendered.getSpans(0,rendered.length(),StrikethroughSpan.class).length==1,"strikethrough rendered");
            boolean italic=false,bold=false;for(StyleSpan span:rendered.getSpans(0,rendered.length(),StyleSpan.class)){italic|=span.getStyle()==Typeface.ITALIC;bold|=span.getStyle()==Typeface.BOLD;}
            check(italic&&bold,"italic and bold spans rendered");
            Spanned nested=(Spanned)TitleMarkdown.render("**粗体 *斜体***");
            check(nested.getSpans(0,nested.length(),StyleSpan.class).length==2,"nested formatting retained");
            check(TitleMarkdown.render("\\*文字\\*\n`*代码*`").toString().equals("*文字*\n*代码*"),"escaped delimiters and inline code preserved");
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            open();setInput("# 今日专注\n*慢慢来* ~~旧计划~~");click("保存");
            check(prefs.getString("title_markdown","").equals("# 今日专注\n*慢慢来* ~~旧计划~~"),"save persists original Markdown source");
            final String[] title={""};runOnMainSync(()->title[0]=((TextView)activity.getWindow().getDecorView().findViewWithTag("app_title")).getText().toString());
            check(title[0].startsWith("今日专注\n慢慢来 旧计划"),"header displays rendered multiline title");
            open();setInput("不会保存");click("取消");
            check(prefs.getString("title_markdown","").startsWith("# 今日专注"),"cancel leaves saved title intact");
            open();click("恢复默认");click("取消");
            check(prefs.getString("title_markdown","").startsWith("# 今日专注"),"reset remains unsaved until confirmation");
            runOnMainSync(()->activity.finish());waitForIdleSync();
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(()->title[0]=((TextView)activity.getWindow().getDecorView().findViewWithTag("app_title")).getText().toString());
            check(title[0].startsWith("今日专注\n慢慢来 旧计划"),"fresh activity restores custom title");
            result.putString("stream","\n"+log+"SUCCESS "+count+" title assertions\n");
        }catch(Throwable e){result.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{if(existed)prefs.edit().putString("title_markdown",original).commit();else prefs.edit().remove("title_markdown").commit();if(activity!=null)runOnMainSync(()->activity.finish());}
        finish(result.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    void open(){runOnMainSync(()->activity.getWindow().getDecorView().findViewWithTag("app_title").performClick());waitForIdleSync();}
    AccessibilityNodeInfo find(String text,String type)throws Exception{
        long end=android.os.SystemClock.uptimeMillis()+6000;
        while(android.os.SystemClock.uptimeMillis()<end){AccessibilityNodeInfo found=walk(getUiAutomation().getRootInActiveWindow(),text,type);if(found!=null)return found;android.os.SystemClock.sleep(150);}
        throw new IllegalStateException("Missing title editor control: "+text+" / "+type);
    }
    AccessibilityNodeInfo walk(AccessibilityNodeInfo n,String text,String type){
        if(n==null)return null;
        if("cn.inktodo.app".contentEquals(String.valueOf(n.getPackageName())) && (text==null || text.contentEquals(String.valueOf(n.getText()))) && (type==null || type.contentEquals(String.valueOf(n.getClassName()))))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=walk(n.getChild(i),text,type);if(found!=null)return found;}return null;
    }
    void click(String label)throws Exception{if(!find(label,"android.widget.Button").performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Cannot click "+label);waitForIdleSync();}
    void setInput(String value)throws Exception{Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);if(!find(null,"android.widget.EditText").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))throw new AssertionError("Cannot edit title");waitForIdleSync();}
}
