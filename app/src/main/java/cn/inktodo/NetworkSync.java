package cn.inktodo;

import android.content.Context;
import android.net.*;
import android.os.Handler;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Wait locally for a validated default network; never probes Todoist or wakes the device. */
final class NetworkSync {
    enum State {IDLE,WAITING,OFFLINE}
    interface Listener {void changed();void ready();}
    static final long WAIT_MS=15000;
    private final Handler handler;private final BooleanSupplier online;private final LongSupplier clock;
    private final Listener listener;private ConnectivityManager manager;private boolean active,registered;
    private State state=State.IDLE;private long deadline;
    private final Runnable poll=this::checkNow;
    private final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){handler.post(poll);}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){handler.post(poll);}
        @Override public void onLost(Network network){handler.post(poll);}
    };
    NetworkSync(Context context,Handler handler,Listener listener){
        this(handler,()->validated((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)),SystemClock::elapsedRealtime,listener);
        manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
    NetworkSync(Handler handler,BooleanSupplier online,LongSupplier clock,Listener listener){this.handler=handler;this.online=online;this.clock=clock;this.listener=listener;}
    private static boolean validated(ConnectivityManager manager){
        if(manager==null)return false;
        NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    void start(){
        active=true;
        if(manager!=null&&!registered){manager.registerDefaultNetworkCallback(callback);registered=true;}
    }
    void stop(){
        active=false;handler.removeCallbacks(poll);state=State.IDLE;
        if(registered){manager.unregisterNetworkCallback(callback);registered=false;}
    }
    State state(){return state;}
    boolean ensureReady(boolean manual){
        if(online.getAsBoolean()){state=State.IDLE;handler.removeCallbacks(poll);return true;}
        if(!active)return false;
        if(state==State.IDLE||(manual&&state==State.OFFLINE)){
            state=State.WAITING;deadline=clock.getAsLong()+WAIT_MS;listener.changed();
            handler.removeCallbacks(poll);handler.postDelayed(poll,1000);
        }
        return false;
    }
    void checkNow(){
        handler.removeCallbacks(poll);
        if(!active||state==State.IDLE)return;
        if(online.getAsBoolean()){state=State.IDLE;listener.ready();listener.changed();return;}
        if(clock.getAsLong()>=deadline){if(state!=State.OFFLINE){state=State.OFFLINE;listener.changed();}return;}
        handler.postDelayed(poll,1000);
    }
}
