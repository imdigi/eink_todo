package cn.inktodo;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import org.json.*;
import java.util.*;

/** Isolated database tests on Android's real SQLite implementation; no network calls. */
public final class VerificationRunner extends Instrumentation {
    private int count;
    private final StringBuilder log = new StringBuilder();
    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name); count++; log.append("PASS ").append(name).append('\n');
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private JSONObject task(String id, String parent, boolean recurring) throws Exception {
        JSONObject j = new JSONObject().put("id", id).put("content", id).put("project_id", "p");
        if (!parent.isEmpty()) j.put("parent_id", parent);
        if (recurring) j.put("due", new JSONObject().put("date", "2026-11-21").put("is_recurring", true));
        return j;
    }
    private JSONObject response(String cursor, boolean full, JSONArray items) throws Exception {
        return new JSONObject().put("sync_token", cursor).put("full_sync", full).put("items", items)
            .put("projects", new JSONArray().put(new JSONObject().put("id", "p").put("name", "测试项目")));
    }
    @Override public void onStart() {
        Context c = getTargetContext(); Store store = new Store(c, "verification.db"); Bundle result = new Bundle();
        try {
            store.reset(); JSONObject parent = task("parent", "", false), child = task("child", "parent", false), daily = task("daily", "", true);
            store.merge(response("first", true, new JSONArray().put(parent).put(child).put(daily)), Collections.emptyList());
            check(store.snapshot().tasks.size() == 3 && store.snapshot().cursor.equals("first"), "full sync persists snapshot and cursor");
            Core.Task p = new Core.Task(parent), t = new Core.Task(daily);
            check(store.enqueue(p, "delete"), "delete queues durably");
            check(!store.enqueue(new Core.Task(child), "close"), "pending parent prevents conflicting child action");
            check(!store.enqueue(p, "delete"), "double tap cannot create duplicate action");
            String uuid = store.snapshot().operations.get(0).uuid; store.close(); store = new Store(c, "verification.db");
            check(store.snapshot().operations.get(0).uuid.equals(uuid), "offline operation survives database reopen");
            check(store.discard(uuid) && store.snapshot().operations.isEmpty(), "unsent operation can be cancelled");
            check(store.enqueue(t, "close"), "recurring completion queues");
            List<Core.Operation> sent = store.claim(); String recurringUuid = sent.get(0).uuid;
            check(!store.discard(recurringUuid), "possibly transmitted action cannot be cancelled");
            store.close(); store = new Store(c, "verification.db");
            check(store.claim().get(0).uuid.equals(recurringUuid), "process restart retries exact UUID");
            check(store.enqueue(new Core.Task(child), "close"), "new UI operation can arrive during sync");
            JSONObject nextDaily = task("daily", "", true); nextDaily.getJSONObject("due").put("date", "2026-11-22");
            JSONObject ack = response("second", false, new JSONArray().put(nextDaily)).put("sync_status", new JSONObject().put(recurringUuid, "ok"));
            store.merge(ack, sent);
            check(store.snapshot().operations.size() == 1 && store.snapshot().operations.get(0).taskId.equals("child"), "in-flight response preserves newly queued action");
            check(store.snapshot().tasks.size() == 3, "recurring completion retains next occurrence and other tasks");
            check(store.snapshot().tasks.stream().anyMatch(x -> x.id.equals("daily") && x.due.equals("2026-11-22")), "next recurring due date is merged");
            sent = store.claim(); uuid = sent.get(0).uuid;
            JSONObject rejected = response("third", false, new JSONArray()).put("sync_status", new JSONObject().put(uuid, new JSONObject().put("http_code", 403).put("error", "forbidden")));
            store.merge(rejected, sent);
            check(store.snapshot().operations.get(0).state.equals("failed") && store.snapshot().hidden().isEmpty(), "rejected command restores visible task");
            check(store.claim().isEmpty(), "permanent rejection is not auto-resubmitted");
            check(store.discard(uuid), "failed record can be removed");
            store.enqueue(p, "delete"); sent = store.claim(); uuid = sent.get(0).uuid;
            store.merge(response("fourth", false, new JSONArray()).put("sync_status", new JSONObject().put(uuid, "ok")), sent);
            check(store.snapshot().tasks.size() == 1 && store.snapshot().tasks.get(0).id.equals("daily"), "confirmed parent deletion removes descendants");
            try { store.merge(new JSONObject().put("sync_token", "bad"), Collections.emptyList()); throw new AssertionError("missing arrays"); }
            catch (java.io.IOException expected) { check(store.snapshot().cursor.equals("fourth"), "incomplete response leaves cursor unchanged"); }
            try { store.merge(response("bad-full", true, new JSONArray().put("not-an-object")), Collections.emptyList()); throw new AssertionError("malformed"); }
            catch (IllegalArgumentException expected) { check(store.snapshot().cursor.equals("fourth") && store.snapshot().tasks.size() == 1, "malformed full sync rolls back deletion and cursor"); }
            store.enqueue(t, "close"); sent = store.claim(); uuid = sent.get(0).uuid;
            store.merge(response("fifth", false, new JSONArray()), sent);
            check(store.snapshot().operations.get(0).state.equals("sending") && store.claim().get(0).uuid.equals(uuid), "missing command status retains uncertainty and UUID");
            JSONObject inbox = new JSONObject().put("id", "ib").put("name", "Renamed inbox").put("inbox_project", true);
            JSONObject namedInbox = new JSONObject().put("id", "p").put("name", "Inbox");
            store.merge(response("inbox-fixture",true,new JSONArray().put(task("inbox-task","",false).put("project_id","ib").put("description","任务背景\n第二行说明")))
                .put("projects",new JSONArray().put(inbox).put(namedInbox)),Collections.emptyList());
            check(store.snapshot().inboxId.equals("ib") && !store.snapshot().projects.containsKey("ib"),"inbox is excluded from project navigation");
            check(store.snapshot().projects.containsKey("p"),"unflagged project named Inbox remains a project");
            check(store.snapshot().projectName("ib").equals("收件箱") && store.snapshot().taskProjectNames().containsKey("ib"),"inbox task labels and snapshot metadata retained");
            store.close();store=new Store(c,"verification.db");
            check(store.snapshot().inboxId.equals("ib"),"inbox classification survives reopen without refetch");
            check(store.snapshot().tasks.get(0).description.equals("任务背景\n第二行说明"),"synced task description survives database reopen");
            store.merge(response("inbox-update",false,new JSONArray()).put("projects",new JSONArray()
                .put(new JSONObject().put("id","ib").put("name","Another name").put("is_inbox_project",true))),Collections.emptyList());
            check(store.snapshot().inboxId.equals("ib") && !store.snapshot().projects.containsKey("ib"),"incremental alternate inbox flag keeps classification");
            Context secureTest = c.createDeviceProtectedStorageContext(); TokenVault vault = new TokenVault(secureTest);
            vault.save("verification-token-never-sent");
            check(vault.load().equals("verification-token-never-sent"), "Android Keystore AES-GCM roundtrip");
            check(!secureTest.getSharedPreferences("vault", 0).getString("data", "").contains("verification-token"), "stored credential is ciphertext");
            vault.clear(); check(vault.load().isEmpty(), "credential can be removed");
            result.putString("stream", "\n" + log + "SUCCESS " + count + " Android assertions\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "\n" + log + "FAIL " + e + "\n" + android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result);
        } finally { store.close(); c.deleteDatabase("verification.db"); }
    }
}
