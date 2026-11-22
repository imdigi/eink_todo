package cn.inktodo;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the real sync button against an isolated demo database, never fake cloud commands. */
public final class SyncVerificationRunner extends Instrumentation {
    Activity activity;Store fixture;Object originalStore;boolean originalDemo;String originalFilter,originalProject;
    SyncRetry policy;int checks;StringBuilder log=new StringBuilder();
    interface Checked{void run()throws Exception;}
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;log.append("PASS ").append(name).append('\n');}
    Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void field(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    void call(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    void main(Checked code)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{code.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    String status()throws Exception{return ((TextView)field("status")).getText().toString();}
    void idle()throws Exception{long until=android.os.SystemClock.uptimeMillis()+45000;while(((AtomicBoolean)field("BUSY")).get()&&android.os.SystemClock.uptimeMillis()<until)android.os.SystemClock.sleep(100);check(!((AtomicBoolean)field("BUSY")).get(),"sync request finishes before the next check");waitForIdleSync();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            fixture=new Store(getTargetContext(),"sync-ui-verification.db");fixture.reset();fixture.seedDemo();
            activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));idle();
            main(()->{
                originalStore=field("store");originalDemo=(Boolean)field("demo");originalFilter=(String)field("filter");originalProject=(String)field("project");
                field("store",fixture);field("demo",true);field("filter","today");field("project","");
                policy=(SyncRetry)field("DEMO_RETRY");policy.reset();call("build");
                long now=System.currentTimeMillis();policy.started(now-5000);policy.failed("同步失败，请检查网络后重试",now);call("render");
                check(status().contains("同步失败，请检查网络后重试"),"sidebar retains the network failure reason");
                check(((Button)field("syncButton")).isEnabled(),"sync button remains available after failure");
                ((Button)field("syncButton")).performClick();
            });idle();
            main(()->{
                check(fixture.snapshot().lastSync>0&&policy.message(System.currentTimeMillis()).isEmpty(),"manual button retries immediately during automatic network cooldown and clears error on success");
                policy.started(System.currentTimeMillis());long attempt=(Long)field("lastAttempt");((Button)field("syncButton")).performClick();
                check((Long)field("lastAttempt")==attempt,"three-second tap protection prevents a second sync request");
                long now=System.currentTimeMillis();policy.failed(429,now+90000,now);call("render");
                check(status().contains("同步过于频繁，请在 ")&&status().contains(" 秒后重试"),"sidebar shows the remaining server wait in plain language");
                ((Button)field("syncButton")).performClick();check((Long)field("lastAttempt")==attempt,"manual sync cannot bypass the server wait");
                policy.failed(401,0,now);call("render");check(status().contains("授权已失效，请在设置中重新设置"),"sidebar uses the agreed authorization message");
                policy.reset();policy.started(now-5000);policy.completed(true,false,now);call("render");
                check(status().contains("部分操作尚未确认，点击同步可重试"),"sidebar explains unconfirmed operations without technical terms");
                ((Button)field("syncButton")).performClick();
            });idle();
            check(policy.message(System.currentTimeMillis()).isEmpty(),"manual retry also clears the unconfirmed-operation reminder on success");
            result.putString("stream","\n"+log+"SUCCESS "+checks+" sync UI assertions\n");
        }catch(Throwable e){result.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(e));}
        finally{
            if(policy!=null)policy.reset();
            if(activity!=null)try{main(()->{if(originalStore!=null){field("store",originalStore);field("demo",originalDemo);field("filter",originalFilter);field("project",originalProject);}activity.finish();});}catch(Exception ignored){}
            if(fixture!=null)fixture.close();getTargetContext().deleteDatabase("sync-ui-verification.db");
        }
        finish(result.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
}
