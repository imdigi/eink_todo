package cn.inktodo;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.View;
import java.util.*;
import org.json.*;
import com.onyx.android.sdk.api.device.epd.EpdController;

/** Device-scoped tests. Never changes a task, system screensaver, front light, or app credentials. */
public final class EinkVerificationRunner extends Instrumentation {
    private final StringBuilder log = new StringBuilder();
    private int checks;
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; log.append("PASS ").append(message).append('\n'); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle out = new Bundle();
        try {
            List<Core.Task> tasks = new ArrayList<>();
            for (int i=0; i<24; i++) tasks.add(new Core.Task(new JSONObject().put("id", "sample-"+i).put("content", "阅读并整理今天的笔记 · 示例任务 "+(i+1))
                .put("project_id", "sample").put("due", new JSONObject().put("date", "2026-11-21").put("is_recurring", i==0))));
            Map<String,String> projects = Collections.singletonMap("sample", "阅读计划");
            Bitmap portrait = SnapshotImage.create(1264,1680,"今日",tasks,projects,System.currentTimeMillis(),2);
            check(portrait.getWidth()==1264 && portrait.getHeight()==1680,"portrait snapshot dimensions");
            check(portrait.getPixel(0,0)==Color.WHITE,"snapshot white background");
            java.io.File dir = getTargetContext().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
            check(dir!=null && (dir.exists() || dir.mkdirs()),"app-scoped image storage without broad permission");
            java.io.File file = new java.io.File(dir,"ink-snapshot-verification.png");
            try(java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) { check(portrait.compress(Bitmap.CompressFormat.PNG,100,stream),"snapshot PNG encoding"); }
            portrait.recycle();
            Bitmap horizontal = SnapshotImage.create(1680,1264,"一个较长的项目名称用于检查标题换行和布局",tasks,projects,System.currentTimeMillis(),0);
            check(horizontal.getWidth()==1680,"landscape snapshot layout"); horizontal.recycle();
            Bitmap empty = SnapshotImage.create(1264,1680,"空项目",Collections.emptyList(),projects,0,0); check(empty!=null,"empty and unsynced snapshot");empty.recycle();
            Intent intent = new Intent(Intent.ACTION_MAIN).setClassName("cn.inktodo.app","cn.inktodo.MainActivity");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(intent); waitForIdleSync();
            final Throwable[] error = new Throwable[1];
            runOnMainSync(() -> {
                try {
                    View root = activity.getWindow().getDecorView(); OnyxScreen screen = new OnyxScreen();
                    if (OnyxScreen.isBoox()) {
                        screen.configure(root,true,8);
                        log.append("SDK status: ").append(screen.description()).append('\n');
                        check(screen.active(),"view-scoped SDK call returned success");
                        log.append("Firmware view mode: ").append(EpdController.getViewDefaultUpdateMode(root)).append('\n');
                        int before=screen.changeRequests(); int originalFull=screen.fullRequests();
                        screen.changed(root); check(screen.changeRequests()==before+1,"content change dispatches SDK update");
                        for(int i=1;i<8;i++) screen.changed(root);
                        check(screen.fullRequests()==originalFull+1,"app counter dispatches GC on eighth content change");
                        screen.changed(root);check(screen.fullRequests()==originalFull+1,"post-GC counter restarts without extra full refresh");
                        int full=screen.fullRequests(); check(screen.clean(root) && screen.fullRequests()==full+1,"manual GC full-refresh dispatched");
                        screen.configure(root,false,8); check(!screen.active(),"enhancement can be disabled");
                        screen.configure(root,true,8); check(screen.active(),"enhancement can be re-enabled");
                    } else {
                        screen.configure(root,true,8);check(!screen.active(),"non-BOOX fallback");check(!screen.clean(root),"non-BOOX redraw fallback");
                    }
                } catch(Throwable e) { error[0]=e; }
            });
            if(error[0]!=null) throw new RuntimeException(error[0]);
            out.putString("stream","\n"+log+"SUCCESS "+checks+" eink assertions\n");finish(Activity.RESULT_OK,out);
        } catch(Throwable e) { out.putString("stream","\n"+log+"FAIL "+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out); }
    }
}
