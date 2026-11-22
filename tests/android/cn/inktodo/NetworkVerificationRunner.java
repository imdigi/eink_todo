package cn.inktodo;

import android.app.*;
import android.os.*;
import java.util.concurrent.atomic.*;

/** Injects local network readiness and time; never changes Wi-Fi or accesses cloud tasks. */
public final class NetworkVerificationRunner extends Instrumentation {
    int checks;StringBuilder log=new StringBuilder();NetworkSync gate;
    AtomicBoolean online=new AtomicBoolean();AtomicLong now=new AtomicLong(1000);
    AtomicInteger probes=new AtomicInteger(),requests=new AtomicInteger();
    interface Checked{void run()throws Exception;}
    void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;log.append("PASS ").append(name).append('\n');}
    void main(Checked test)throws Exception{Throwable[] failure={null};runOnMainSync(()->{try{test.run();}catch(Throwable t){failure[0]=t;}});if(failure[0]!=null)throw new Exception(failure[0]);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();
        try{
            main(()->{
                gate=new NetworkSync(new Handler(Looper.getMainLooper()),()->{probes.incrementAndGet();return online.get();},now::get,new NetworkSync.Listener(){public void changed(){}public void ready(){requests.incrementAndGet();}});
                gate.start();check(!gate.ensureReady(false)&&gate.state()==NetworkSync.State.WAITING,"wake while offline waits before any sync request");
                check(requests.get()==0,"network readiness checks do not send a request");
            });
            int initial=probes.get();SystemClock.sleep(1150);
            check(probes.get()>initial&&requests.get()==0,"one-second timer checks locally without invoking sync");
            main(()->{
                now.set(15999);gate.checkNow();check(gate.state()==NetworkSync.State.WAITING,"wait remains active just before fifteen seconds");
                now.set(16000);gate.checkNow();check(gate.state()==NetworkSync.State.OFFLINE,"fifteen-second deadline changes state to offline");
                gate.ensureReady(false);check(gate.state()==NetworkSync.State.OFFLINE,"automatic checks do not restart an expired wait");
            });
            int stopped=probes.get();SystemClock.sleep(1150);check(probes.get()==stopped,"timeout stops one-second polling");
            main(()->{
                online.set(true);gate.checkNow();check(requests.get()==1&&gate.state()==NetworkSync.State.IDLE,"network-restored notification resumes exactly one pending sync");
                gate.checkNow();check(requests.get()==1,"repeated network notifications cannot duplicate a request");
                check(gate.ensureReady(false)&&requests.get()==1,"already-ready network allows caller to sync without callback duplication");
                online.set(false);gate.ensureReady(false);gate.stop();online.set(true);gate.checkNow();
                check(requests.get()==1&&gate.state()==NetworkSync.State.IDLE,"leaving the app cancels waiting and ignores network recovery");
                online.set(false);check(!gate.ensureReady(false)&&gate.state()==NetworkSync.State.IDLE,"offline background operations cannot start a polling loop");
                gate.start();gate.ensureReady(false);now.addAndGet(15000);gate.checkNow();gate.ensureReady(true);
                check(gate.state()==NetworkSync.State.WAITING,"manual retry can start a new bounded wait after timeout");
                online.set(true);gate.checkNow();check(requests.get()==2,"manual wait also resumes once when the network is ready");
            });
            out.putString("stream","\n"+log+"SUCCESS "+checks+" network wait assertions\n");
        }catch(Throwable t){out.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(t));}
        finally{if(gate!=null)try{main(()->gate.stop());}catch(Exception ignored){}}
        finish(out.getString("stream","").contains("FAIL ")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
    }
}
