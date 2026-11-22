package cn.inktodo;

/** Separates mandatory server waits from automatic retry delays and manual tap protection. */
final class SyncRetry {
    private long serverUntil,automaticUntil,lastStarted;
    private boolean authorizationBlocked,rateLimited;
    private String reason="";
    synchronized void restoreServerWait(long until,boolean limited) {
        if(until>serverUntil){serverUntil=until;if(reason.isEmpty()){rateLimited=limited;reason=limited?"同步过于频繁":"Todoist 暂时不可用，请稍后重试";}}
    }
    synchronized long serverUntil(){return serverUntil;}
    synchronized boolean rateLimited(){return rateLimited;}
    synchronized String blocked(long now,boolean manual) {
        if(authorizationBlocked)return reason;
        if(now<serverUntil)return message(now);
        if(!manual&&now<automaticUntil)return reason;
        if(manual&&lastStarted>0&&now>=lastStarted&&now-lastStarted<3000)
            return "请在 "+seconds(lastStarted+3000,now)+" 秒后再同步";
        return "";
    }
    synchronized void started(long now){lastStarted=now;}
    synchronized void completed(boolean uncertain,boolean rejected,long now) {
        serverUntil=0;authorizationBlocked=false;rateLimited=false;
        automaticUntil=uncertain?now+60000:0;
        reason=uncertain?"部分操作尚未确认，点击同步可重试":rejected?"有操作未被接受，请查看操作记录":"";
    }
    synchronized void failed(int status,long retryAfter,long now) {
        authorizationBlocked=status==401||status==403;rateLimited=status==429;
        serverUntil=authorizationBlocked?0:Math.max(retryAfter,rateLimited?now+60000:0);
        // Respect Retry-After exactly when present; only use 60 seconds as the 429 fallback.
        if(rateLimited&&retryAfter>now)serverUntil=retryAfter;
        automaticUntil=now+60000;
        reason=status==401?"授权已失效，请在设置中重新设置"
            :status==403?"账号或权限受限，请检查 Todoist 授权"
            :rateLimited?"同步过于频繁":"Todoist 暂时不可用，请稍后重试";
    }
    synchronized void failed(String message,long now) {
        authorizationBlocked=false;rateLimited=false;serverUntil=0;automaticUntil=now+60000;reason=message;
    }
    synchronized boolean automaticDue(long now) {
        return !authorizationBlocked&&automaticUntil>0&&now>=Math.max(automaticUntil,serverUntil);
    }
    synchronized String message(long now) {
        if(now<serverUntil)return (rateLimited?"同步过于频繁":"Todoist 暂时不可用")+"，请在 "+seconds(serverUntil,now)+" 秒后重试";
        return rateLimited?"等待已结束，点击同步可重试":reason;
    }
    synchronized void reset(){serverUntil=automaticUntil=lastStarted=0;authorizationBlocked=rateLimited=false;reason="";}
    private static long seconds(long until,long now){return Math.max(1,(until-now+999)/1000);}
}
