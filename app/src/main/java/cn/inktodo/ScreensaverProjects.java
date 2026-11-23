package cn.inktodo;

import android.content.*;
import java.time.*;

/** Project IDs are scoped to the cached account source; selection never accepts free-form names. */
final class ScreensaverProjects {
    static String key(boolean demo){return demo?"screensaver_important_demo":"screensaver_important_real";}
    static SnapshotModel model(Context context,boolean demo,Store.Snapshot snapshot,String filter,String project){
        String important=context.getSharedPreferences("settings",0).getString(key(demo),"");
        if(snapshot.projects.containsKey(important)){filter="today";project="";}
        return SnapshotModel.create(snapshot.tasks,snapshot.hidden(),filter,project,snapshot.inboxId,snapshot.taskProjectNames(),snapshot.pending(),LocalDate.now(),ZoneId.systemDefault())
            .withImportant(snapshot.tasks,snapshot.hidden(),important,snapshot.projects);
    }
}
