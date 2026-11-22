package cn.inktodo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.util.*;

final class Store extends SQLiteOpenHelper {
    Store(Context context, boolean demo) { this(context, demo ? "demo.db" : "todoist.db"); }
    Store(Context context, String name) { super(context, name, null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks(id TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        db.execSQL("CREATE TABLE operations(uuid TEXT PRIMARY KEY, task_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, state TEXT NOT NULL, title TEXT NOT NULL, error TEXT NOT NULL, created INTEGER NOT NULL)");
        createUndoTables(db);
    }
    private void createUndoTables(SQLiteDatabase db){
        db.execSQL("CREATE TABLE completion_undo(id TEXT PRIMARY KEY,title TEXT NOT NULL)");
        db.execSQL("CREATE TABLE completion_undo_members(uuid TEXT PRIMARY KEY,group_id TEXT NOT NULL)");
        db.execSQL("CREATE INDEX completion_undo_group ON completion_undo_members(group_id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion==1&&newVersion==2){createUndoTables(db);return;}
        throw new IllegalStateException("Unsupported schema");
    }
    static final class UndoAction {
        final String id,title;final int completed,deferred;
        UndoAction(String id,String title,int completed,int deferred){this.id=id;this.title=title;this.completed=completed;this.deferred=deferred;}
    }
    static final class Snapshot {
        final List<Core.Task> tasks;
        final Map<String, String> projects;
        final List<Core.Operation> operations;
        final String cursor;
        final String inboxId;
        final long lastSync;
        Snapshot(List<Core.Task> tasks, Map<String, String> projects, List<Core.Operation> operations, String cursor, long lastSync, String inboxId) {
            this.tasks = tasks; this.projects = projects; this.operations = operations; this.cursor = cursor; this.lastSync = lastSync;
            this.inboxId = inboxId;
        }
        String projectName(String id) { return !inboxId.isEmpty() && inboxId.equals(id) ? "收件箱" : projects.getOrDefault(id, ""); }
        Map<String,String> taskProjectNames() {
            Map<String,String> names = new LinkedHashMap<>(projects);
            if (!inboxId.isEmpty()) names.put(inboxId, "收件箱");
            return names;
        }
        Set<String> hidden() {
            Set<String> ids = new HashSet<>(); for (Core.Operation op : operations) if (op.pending()) ids.add(op.taskId); return ids;
        }
        int pending() { int n = 0; for (Core.Operation op : operations) if (op.pending()) n++; return n; }
    }
    synchronized Snapshot snapshot() throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        List<Core.Task> tasks = new ArrayList<>(); Map<String, String> projects = new LinkedHashMap<>();
        String inboxId = "";
        try (Cursor c = db.rawQuery("SELECT payload FROM tasks", null)) {
            while (c.moveToNext()) tasks.add(new Core.Task(new JSONObject(c.getString(0))));
        }
        try (Cursor c = db.rawQuery("SELECT id, payload FROM projects ORDER BY id", null)) {
            while (c.moveToNext()) { JSONObject j = new JSONObject(c.getString(1));
                if (!Core.truth(j, "is_deleted") && !Core.truth(j, "is_archived")) {
                    if (Core.isInboxProject(j)) inboxId = c.getString(0);
                    else projects.put(c.getString(0), j.optString("name", "项目"));
                } }
        }
        return new Snapshot(tasks, projects, operations(db), meta(db, "cursor", "*"), Long.parseLong(meta(db, "last_sync", "0")), inboxId);
    }
    private List<Core.Operation> operations(SQLiteDatabase db) {
        List<Core.Operation> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT uuid, task_id, kind, state, title, error FROM operations ORDER BY created, rowid", null)) {
            while (c.moveToNext()) out.add(new Core.Operation(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5)));
        }
        return out;
    }
    synchronized boolean enqueue(Core.Task task, String kind) throws Exception {
        Snapshot s = snapshot(); Map<String, Core.Task> all = new HashMap<>(); for (Core.Task t : s.tasks) all.put(t.id, t);
        if (!all.containsKey(task.id)) return false;
        for (Core.Operation op : s.operations) {
            if (op.taskId.equals(task.id) || (op.pending() && (Core.descendant(task.id, op.taskId, all) || Core.descendant(op.taskId, task.id, all)))) return false;
        }
        ContentValues v = new ContentValues(); v.put("uuid", UUID.randomUUID().toString()); v.put("task_id", task.id);
        v.put("kind", kind); v.put("state", "queued"); v.put("title", task.content); v.put("error", ""); v.put("created", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("operations", null, v); return true;
    }
    synchronized List<Core.Operation> claim() {
        SQLiteDatabase db = getWritableDatabase(); List<Core.Operation> out = new ArrayList<>();
        db.beginTransaction();
        try {
            Set<String> held=new HashSet<>();
            try(Cursor cursor=db.rawQuery("SELECT uuid FROM completion_undo_members",null)){while(cursor.moveToNext())held.add(cursor.getString(0));}
            for (Core.Operation op : operations(db)) if (op.pending() && !held.contains(op.uuid) && out.size() < 100) {
                ContentValues v = new ContentValues(); v.put("state", "sending");
                db.update("operations", v, "uuid=?", new String[]{op.uuid});
                out.add(new Core.Operation(op.uuid, op.taskId, op.kind, "sending", op.title, op.error));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return out;
    }
    synchronized Core.GroupClose enqueueGroupClose(String parentId,String scope,java.time.LocalDate day,java.time.ZoneId zone)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            Snapshot s=snapshot();Core.GroupClose plan=Core.groupClose(s.tasks,s.hidden(),parentId,scope,day,zone);
            Map<String,Core.Task> all=new HashMap<>();for(Core.Task task:s.tasks)all.put(task.id,task);
            for(Core.Task task:plan.targets)for(Core.Operation op:s.operations){
                if(op.taskId.equals(task.id)||(op.pending()&&(Core.descendant(task.id,op.taskId,all)||Core.descendant(op.taskId,task.id,all))))throw new IllegalStateException("本组部分任务已有操作，请先查看操作记录");
            }
            for(Core.Task task:plan.targets){
                ContentValues v=new ContentValues();v.put("uuid",UUID.randomUUID().toString());v.put("task_id",task.id);v.put("kind","close");v.put("state","queued");v.put("title",task.content);v.put("error","");v.put("created",System.currentTimeMillis());
                db.insertOrThrow("operations",null,v);
            }
            db.setTransactionSuccessful();return plan;
        }finally{db.endTransaction();}
    }
    synchronized boolean discard(String uuid) {
        String group="";
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT group_id FROM completion_undo_members WHERE uuid=?",new String[]{uuid})){if(cursor.moveToFirst())group=cursor.getString(0);}
        if(!group.isEmpty())return cancelUndo(group);
        return getWritableDatabase().delete("operations", "uuid=? AND state IN ('queued','failed')", new String[]{uuid}) > 0;
    }
    synchronized UndoAction enqueueUndo(Core.Task task)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(!enqueue(task,"close")){db.setTransactionSuccessful();return null;}
            UndoAction action=holdUndo(db,task.content,Collections.singletonList(task.id),1,0);
            db.setTransactionSuccessful();return action;
        }finally{db.endTransaction();}
    }
    synchronized UndoAction enqueueGroupUndo(Core.Task parent,String scope,java.time.LocalDate day,java.time.ZoneId zone)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            Core.GroupClose plan=enqueueGroupClose(parent.id,scope,day,zone);
            List<String> ids=new ArrayList<>();for(Core.Task target:plan.targets)ids.add(target.id);
            UndoAction action=ids.isEmpty()?new UndoAction("",parent.content,0,plan.deferred):holdUndo(db,parent.content,ids,plan.completed,plan.deferred);
            db.setTransactionSuccessful();return action;
        }finally{db.endTransaction();}
    }
    private UndoAction holdUndo(SQLiteDatabase db,String title,List<String> tasks,int completed,int deferred){
        String id=UUID.randomUUID().toString();ContentValues group=new ContentValues();group.put("id",id);group.put("title",title);db.insertOrThrow("completion_undo",null,group);
        for(String task:tasks){
            String uuid="";try(Cursor cursor=db.rawQuery("SELECT uuid FROM operations WHERE task_id=? AND kind='close' AND state='queued'",new String[]{task})){if(cursor.moveToFirst())uuid=cursor.getString(0);}
            if(uuid.isEmpty())throw new IllegalStateException("完成操作状态已变化");
            ContentValues member=new ContentValues();member.put("uuid",uuid);member.put("group_id",id);db.insertOrThrow("completion_undo_members",null,member);
        }
        return new UndoAction(id,title,completed,deferred);
    }
    synchronized boolean hasUndo(String id){
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT 1 FROM completion_undo WHERE id=?",new String[]{id})){return cursor.moveToFirst();}
    }
    synchronized boolean cancelUndo(String id){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(!hasUndo(id))return false;
            try(Cursor cursor=db.rawQuery("SELECT 1 FROM operations JOIN completion_undo_members USING(uuid) WHERE group_id=? AND state!='queued' LIMIT 1",new String[]{id})){if(cursor.moveToFirst())return false;}
            db.delete("operations","uuid IN (SELECT uuid FROM completion_undo_members WHERE group_id=?)",new String[]{id});
            releaseUndoRows(db,id);db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    private boolean releaseUndoRows(SQLiteDatabase db,String id){
        db.delete("completion_undo_members","group_id=?",new String[]{id});return db.delete("completion_undo","id=?",new String[]{id})>0;
    }
    synchronized boolean releaseUndo(String id){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{boolean released=releaseUndoRows(db,id);db.setTransactionSuccessful();return released;}finally{db.endTransaction();}
    }
    synchronized int releaseAllUndo(){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("completion_undo_members",null,null);int count=db.delete("completion_undo",null,null);db.setTransactionSuccessful();return count;}finally{db.endTransaction();}
    }
    synchronized void merge(JSONObject response, List<Core.Operation> sent) throws Exception {
        String newCursor = response.optString("sync_token", "");
        if (newCursor.isEmpty() || response.optJSONArray("items") == null || response.optJSONArray("projects") == null)
            throw new java.io.IOException("同步响应不完整，保留本地数据后重试");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            List<Core.Result> results = Core.results(response, sent);
            Map<String, Core.Operation> byId = new HashMap<>(); for (Core.Operation op : sent) byId.put(op.uuid, op);
            for (Core.Result result : results) {
                if (result.state.equals("ok")) {
                    Core.Operation op = byId.get(result.uuid);
                    Map<String, Core.Task> all = new HashMap<>(); for (Core.Task t : snapshot().tasks) all.put(t.id, t);
                    Core.Task parent = all.get(op.taskId);
                    boolean removeChildren = op.kind.equals("delete") || (parent != null && !parent.recurring);
                    for (Core.Task t : all.values()) if (t.id.equals(op.taskId) || (removeChildren && Core.descendant(t.id, op.taskId, all)))
                        db.delete("tasks", "id=?", new String[]{t.id});
                    db.delete("operations", "uuid=?", new String[]{result.uuid});
                } else {
                    ContentValues v = new ContentValues(); v.put("state", result.state); v.put("error", result.error);
                    db.update("operations", v, "uuid=?", new String[]{result.uuid});
                }
            }
            if (Core.truth(response, "full_sync")) { db.delete("tasks", null, null); db.delete("projects", null, null); }
            for (JSONObject j : Core.objects(response.optJSONArray("items"))) {
                Core.Task t = new Core.Task(j); if (t.id.isEmpty()) throw new java.io.IOException("任务缺少编号");
                if (t.checked || t.deleted) db.delete("tasks", "id=?", new String[]{t.id}); else put(db, "tasks", t.id, j);
            }
            for (JSONObject j : Core.objects(response.optJSONArray("projects"))) {
                String id = j.optString("id", ""); if (id.isEmpty()) throw new java.io.IOException("项目缺少编号");
                if (Core.truth(j, "is_deleted")) db.delete("projects", "id=?", new String[]{id}); else put(db, "projects", id, j);
            }
            putMeta(db, "cursor", newCursor); putMeta(db, "last_sync", String.valueOf(System.currentTimeMillis()));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private void put(SQLiteDatabase db, String table, String id, JSONObject j) {
        ContentValues v = new ContentValues(); v.put("id", id); v.put("payload", j.toString());
        db.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static String meta(SQLiteDatabase db, String k, String fallback) {
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k=?", new String[]{k})) { return c.moveToFirst() ? c.getString(0) : fallback; }
    }
    private static void putMeta(SQLiteDatabase db, String k, String value) {
        ContentValues v = new ContentValues(); v.put("k", k); v.put("v", value); db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized void reset() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { for (String t : new String[]{"completion_undo_members","completion_undo","tasks", "projects", "operations", "meta"}) db.delete(t, null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    synchronized void seedDemo() throws Exception {
        if (!snapshot().cursor.equals("*")) { seedDemoInbox(); return; }
        java.time.LocalDate today = java.time.LocalDate.now();
        org.json.JSONArray tasks = new org.json.JSONArray();
        String[] titles = {"读完今天的二十页", "整理这周的阅读笔记", "下楼散步半小时", "给植物浇水", "预约牙齿检查", "准备下周的分享", "挑选下一本想读的书", "周末去图书馆"};
        for (int i = 0; i < titles.length; i++) {
            JSONObject j = new JSONObject().put("id", "demo-" + i).put("content", titles[i]).put("description", "这是演示任务，操作不会影响你的 Todoist。")
                .put("project_id", i % 2 == 0 ? "reading" : "life").put("priority", i == 1 ? 4 : 1).put("child_order", i);
            if (i < 6) j.put("due", new JSONObject().put("date", today.plusDays(i == 1 ? -1 : i < 4 ? 0 : 1).toString()).put("is_recurring", i == 0).put("string", i == 0 ? "每天" : ""));
            tasks.put(j);
        }
        merge(new JSONObject().put("sync_token", "demo").put("full_sync", true).put("items", tasks)
            .put("projects", new org.json.JSONArray().put(new JSONObject().put("id", "reading").put("name", "阅读"))
                .put(new JSONObject().put("id", "life").put("name", "生活"))), Collections.emptyList());
        seedDemoInbox();
    }
    private void seedDemoInbox() throws Exception {
        if (!snapshot().inboxId.isEmpty()) {
            SQLiteDatabase db=getWritableDatabase();
            String[] ids={"demo-inbox-task-1","demo-inbox-task-2"};
            for(String id:ids)try(Cursor c=db.rawQuery("SELECT payload FROM tasks WHERE id=?",new String[]{id})) {
                if(c.moveToFirst()) { JSONObject j=new JSONObject(c.getString(0));if(j.optString("description","").isEmpty()) {
                    j.put("description",id.endsWith("1")?"先收集想法，稍后再安排时间和项目。":"补充背景、参考链接和需要注意的细节。");put(db,"tasks",id,j);
                } }
            }
            return;
        }
        org.json.JSONArray tasks = new org.json.JSONArray()
            .put(new JSONObject().put("id", "demo-inbox-task-1").put("project_id", "demo-inbox").put("content", "把想到的事情先记在这里").put("description","先收集想法，稍后再安排时间和项目。"))
            .put(new JSONObject().put("id", "demo-inbox-task-2").put("project_id", "demo-inbox").put("content", "稍后再把这些待办理清楚，分配到对应的项目中").put("description","补充背景、参考链接和需要注意的细节。"));
        merge(new JSONObject().put("sync_token", "demo").put("items", tasks)
            .put("projects", new org.json.JSONArray().put(new JSONObject().put("id", "demo-inbox").put("name", "Inbox").put("inbox_project", true))), Collections.emptyList());
    }
    synchronized void demoSync() throws Exception {
        List<Core.Operation> sent = claim(); JSONObject statuses = new JSONObject(); org.json.JSONArray changes = new org.json.JSONArray();
        Map<String, Core.Task> all = new HashMap<>(); for (Core.Task t : snapshot().tasks) all.put(t.id, t);
        for (Core.Operation op : sent) {
            statuses.put(op.uuid, "ok"); Core.Task t = all.get(op.taskId);
            if (t != null && t.recurring && op.kind.equals("close")) changes.put(new JSONObject().put("id", t.id).put("content", t.content)
                .put("description", t.description).put("project_id", t.projectId).put("priority", t.priority)
                .put("parent_id",t.parentId).put("child_order",t.order)
                .put("due", new JSONObject().put("date", java.time.LocalDate.now().plusDays(1).toString()).put("is_recurring", true).put("string", "每天")));
        }
        merge(new JSONObject().put("sync_token", "demo").put("items", changes).put("projects", new org.json.JSONArray()).put("sync_status", statuses), sent);
    }
}
