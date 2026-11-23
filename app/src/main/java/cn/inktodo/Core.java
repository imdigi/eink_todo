package cn.inktodo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Platform-independent task semantics shared by the UI and protocol tests. */
public final class Core {
    private Core() {}
    public static final class Task {
        public final String id, content, description, projectId, parentId, due, dueText;
        public final int priority, order;
        public final boolean recurring, checked, deleted;
        public Task(JSONObject j) {
            id = j.optString("id", "");
            content = j.optString("content", "");
            description = j.optString("description", "");
            projectId = j.optString("project_id", "");
            parentId = clean(j, "parent_id");
            JSONObject d = j.optJSONObject("due");
            due = d == null ? "" : d.optString("date", "");
            dueText = d == null ? "" : d.optString("string", "");
            recurring = d != null && truth(d, "is_recurring");
            priority = j.optInt("priority", 1);
            order = j.optInt("child_order", j.optInt("order", 0));
            checked = truth(j, "checked") || truth(j, "is_completed");
            deleted = truth(j, "is_deleted");
        }
    }
    public static String clean(JSONObject j, String k) {
        return j.isNull(k) ? "" : j.optString(k, "");
    }
    public static boolean truth(JSONObject j, String k) {
        Object v = j.opt(k);
        return Boolean.TRUE.equals(v) || "1".equals(String.valueOf(v));
    }
    public static boolean isInboxProject(JSONObject project) {
        return truth(project, "inbox_project") || truth(project, "is_inbox_project");
    }
    public static String normalizeFilter(String filter, String project, String inboxId) {
        if ("overdue".equals(filter)) return "today";
        if ("project".equals(filter)) {
            return project.isEmpty() || (!inboxId.isEmpty() && inboxId.equals(project)) ? "inbox" : "project";
        }
        return "today".equals(filter) || "overdue".equals(filter) || "inbox".equals(filter) ? filter : "inbox";
    }
    public static LocalDate dueDay(String due, ZoneId zone) {
        if (due == null || due.isEmpty()) return null;
        try {
            if (due.length() == 10) return LocalDate.parse(due);
            try { return OffsetDateTime.parse(due).atZoneSameInstant(zone).toLocalDate(); }
            catch (DateTimeParseException ignored) { return LocalDateTime.parse(due).toLocalDate(); }
        } catch (DateTimeParseException e) { return null; }
    }
    public static boolean descendant(String id, String ancestor, Map<String, Task> tasks) {
        Set<String> seen = new HashSet<>();
        while (!id.isEmpty() && seen.add(id)) {
            if (id.equals(ancestor)) return true;
            Task t = tasks.get(id);
            id = t == null ? "" : t.parentId;
        }
        return false;
    }
    public static List<Task> visible(Collection<Task> source, Set<String> hidden,
                                     String filter, String project, LocalDate today, ZoneId zone) {
        if (!filter.equals("inbox") && !filter.equals("project") && !filter.equals("today") && !filter.equals("overdue")) return Collections.emptyList();
        Map<String, Task> all = new HashMap<>();
        for (Task t : source) all.put(t.id, t);
        List<Task> out = new ArrayList<>();
        for (Task t : source) {
            if (t.checked || t.deleted) continue;
            boolean blocked = false;
            for (String id : hidden) if (descendant(t.id, id, all)) { blocked = true; break; }
            if (blocked) continue;
            LocalDate day = dueDay(t.due, zone);
            if (filter.equals("today") && (day == null || day.isAfter(today))) continue;
            if (filter.equals("overdue") && (day == null || !day.isBefore(today))) continue;
            if (filter.equals("project") && !project.equals(t.projectId)) continue;
            if (filter.equals("inbox") && (project.isEmpty() || !project.equals(t.projectId))) continue;
            out.add(t);
        }
        if(filter.equals("project") || filter.equals("inbox")) {
            List<Task> ordered=new ArrayList<>();
            for(TreeRow row:tree(source,hidden,out,Collections.emptySet(),true))ordered.add(row.task);
            return ordered;
        }
        out.sort(Comparator.comparingInt((Task t) -> filter.equals("today") && !today.equals(dueDay(t.due, zone)) ? 1 : 0).thenComparing((Task t) -> {
            LocalDate d = dueDay(t.due, zone);
            return d == null ? LocalDate.MAX : d;
        }).thenComparing(Comparator.comparingInt((Task t) -> t.priority).reversed())
            .thenComparingInt(t -> t.order).thenComparing(t -> t.id));
        return out;
    }
    public static final class TreeRow {
        public final Task task;
        public final int depth, childCount, matchingDescendants;
        public final boolean context, collapsed, parent;
        TreeRow(Task task,int depth,int children,int descendants,boolean context,boolean collapsed,boolean parent){
            this.task=task;this.depth=depth;this.childCount=children;this.matchingDescendants=descendants;this.context=context;this.collapsed=collapsed;this.parent=parent;
        }
    }
    private static final class Branch {
        final Task task;final int depth,parent;
        int children,descendants;
        Branch(Task task,int depth,int parent){this.task=task;this.depth=depth;this.parent=parent;}
    }
    /** A filtered forest plus the active ancestors needed to explain each matching task.
     * Context rows never add siblings or count as date matches. Every task is emitted at most once. */
    public static List<TreeRow> tree(Collection<Task> source,Set<String> hidden,List<Task> matching,Set<String> collapsed,boolean manualOrder){
        Map<String,Task> all=new HashMap<>();for(Task t:source)all.put(t.id,t);
        Map<String,Task> available=new HashMap<>();
        for(Task t:source){
            if(t.checked || t.deleted || t.id.isEmpty())continue;
            boolean blocked=false;for(String id:hidden)if(descendant(t.id,id,all)){blocked=true;break;}
            if(!blocked)available.put(t.id,t);
        }
        Set<String> parents=new HashSet<>();for(Task t:available.values()){
            Task p=available.get(t.parentId);if(p!=null&&!p.id.equals(t.id)&&p.projectId.equals(t.projectId))parents.add(p.id);
        }
        Set<String> matches=new HashSet<>();Map<String,Task> included=new HashMap<>();Map<String,Integer> rank=new HashMap<>();
        for(int i=0;i<matching.size();i++){
            Task task=available.get(matching.get(i).id);if(task==null)continue;
            matches.add(task.id);Set<String> seen=new HashSet<>();String project=task.projectId;
            while(task!=null && project.equals(task.projectId) && seen.add(task.id)){
                included.put(task.id,task);rank.put(task.id,Math.min(rank.getOrDefault(task.id,Integer.MAX_VALUE),i));task=available.get(task.parentId);
            }
        }
        Comparator<Task> order=Comparator.comparingInt((Task t)->t.order).thenComparing(t->t.id);
        Comparator<Task> rootsOrder=manualOrder?order:Comparator.comparingInt((Task t)->rank.getOrDefault(t.id,Integer.MAX_VALUE)).thenComparing(order);
        List<Task> roots=new ArrayList<>(),sorted=new ArrayList<>(included.values());Map<String,List<Task>> children=new HashMap<>();
        for(Task t:sorted){Task parent=included.get(t.parentId);
            if(parent==null || parent.id.equals(t.id) || !parent.projectId.equals(t.projectId))roots.add(t);
            else children.computeIfAbsent(parent.id,k->new ArrayList<>()).add(t);
        }
        roots.sort(rootsOrder);sorted.sort(rootsOrder);for(List<Task> siblings:children.values())siblings.sort(order);
        List<Branch> flat=new ArrayList<>();Set<String> visited=new HashSet<>();
        List<Task> starts=new ArrayList<>(roots);starts.addAll(sorted);
        for(Task start:starts){
            if(visited.contains(start.id))continue;
            Deque<Branch> stack=new ArrayDeque<>();stack.push(new Branch(start,0,-1));
            while(!stack.isEmpty()){
                Branch node=stack.pop();if(!visited.add(node.task.id))continue;
                int position=flat.size();flat.add(node);if(node.parent>=0)flat.get(node.parent).children++;
                List<Task> next=children.getOrDefault(node.task.id,Collections.emptyList());
                for(int i=next.size()-1;i>=0;i--)stack.push(new Branch(next.get(i),node.depth+1,position));
            }
        }
        for(int i=flat.size()-1;i>=0;i--){Branch node=flat.get(i);if(node.parent>=0)flat.get(node.parent).descendants+=node.descendants+(matches.contains(node.task.id)?1:0);}
        List<TreeRow> out=new ArrayList<>();int skippedBelow=-1;
        for(Branch node:flat){
            if(skippedBelow>=0 && node.depth>skippedBelow)continue;
            skippedBelow=-1;boolean folded=node.children>0 && collapsed.contains(node.task.id);
            out.add(new TreeRow(node.task,node.depth,node.children,node.descendants,!matches.contains(node.task.id),folded,parents.contains(node.task.id)));
            if(folded)skippedBelow=node.depth;
        }
        return out;
    }
    public static final class GroupClose {
        public final List<Task> targets;
        public final int completed, deferred;
        GroupClose(List<Task> targets,int completed,int deferred){this.targets=Collections.unmodifiableList(targets);this.completed=completed;this.deferred=deferred;}
    }
    /** Select non-overlapping commands whose entire completion effect is within the date group.
     * Never close the clicked grouping parent; retain intermediate parents with out-of-scope children. */
    public static GroupClose groupClose(Collection<Task> source,Set<String> hidden,String parentId,String scope,LocalDate day,ZoneId zone){
        if(!scope.equals("today:due_today")&&!scope.equals("today:overdue"))throw new IllegalArgumentException("Unknown date group");
        Map<String,Task> all=new HashMap<>();for(Task t:source)all.put(t.id,t);
        Map<String,Task> available=new HashMap<>();
        for(Task t:source){
            if(t.checked||t.deleted)continue;
            boolean blocked=false;for(String id:hidden)if(descendant(t.id,id,all)){blocked=true;break;}
            if(!blocked)available.put(t.id,t);
        }
        Task parent=available.get(parentId);if(parent==null)return new GroupClose(new ArrayList<>(),0,0);
        List<Task> matches=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Task t:available.values()){
            LocalDate due=dueDay(t.due,zone);boolean matching=scope.equals("today:due_today")?day.equals(due):due!=null&&due.isBefore(day);
            if(!t.id.equals(parentId)&&t.projectId.equals(parent.projectId)&&matching&&descendant(t.id,parentId,available)){matches.add(t);ids.add(t.id);}
        }
        Set<String> safe=new HashSet<>();
        for(Task t:matches){boolean allowed=true;
            for(Task child:available.values())if(!child.id.equals(t.id)&&descendant(child.id,t.id,available)){
                if(t.recurring||!ids.contains(child.id)){allowed=false;break;}
            }
            if(allowed)safe.add(t.id);
        }
        List<Task> targets=new ArrayList<>();Set<String> covered=new HashSet<>();
        for(TreeRow row:tree(available.values(),Collections.emptySet(),matches,Collections.emptySet(),true)){
            Task t=row.task;if(!safe.contains(t.id)||covered.contains(t.id))continue;
            targets.add(t);for(Task child:available.values())if(descendant(child.id,t.id,available))covered.add(child.id);
        }
        return new GroupClose(targets,covered.size(),ids.size()-covered.size());
    }
    public static Map<String,Integer> counts(Collection<Task> source, Set<String> hidden, String inboxId, LocalDate today, ZoneId zone) {
        Map<String,Task> all=new HashMap<>();for(Task t:source)all.put(t.id,t);
        Map<String,Integer> counts=new TreeMap<>();counts.put("inbox",0);counts.put("today",0);counts.put("due_today",0);counts.put("overdue",0);
        for(Task t:source) {
            if(t.checked || t.deleted)continue;
            boolean blocked=false;for(String id:hidden)if(descendant(t.id,id,all)){blocked=true;break;}
            if(blocked)continue;
            String key="project:"+t.projectId;counts.put(key,counts.getOrDefault(key,0)+1);
            if(!inboxId.isEmpty() && inboxId.equals(t.projectId))counts.put("inbox",counts.get("inbox")+1);
            LocalDate day=dueDay(t.due,zone);
            if(day!=null && !day.isAfter(today))counts.put("today",counts.get("today")+1);
            if(today.equals(day))counts.put("due_today",counts.get("due_today")+1);
            else if(day!=null && day.isBefore(today))counts.put("overdue",counts.get("overdue")+1);
        }
        return counts;
    }
    public static JSONObject command(String uuid, String taskId, String kind) throws Exception {
        if (!kind.equals("close") && !kind.equals("delete")) throw new IllegalArgumentException("Unknown action");
        return new JSONObject().put("uuid", uuid).put("type", "item_" + kind)
            .put("args", new JSONObject().put("id", taskId));
    }
    public static JSONObject addArgs(String content, LocalDate day, String inbox) throws Exception {
        String title=content.trim();
        if(title.isEmpty() || title.length()>500)throw new IllegalArgumentException("请输入 1–500 字的任务内容");
        JSONObject args=new JSONObject().put("content",title).put("due",new JSONObject().put("date",day.toString()).put("is_recurring",false));
        if(!inbox.isEmpty())args.put("project_id",inbox);
        return args;
    }
    public static JSONObject command(Operation op)throws Exception{
        if(!op.kind.equals("add"))return command(op.uuid,op.taskId,op.kind);
        return new JSONObject().put("uuid",op.uuid).put("temp_id",op.taskId).put("type","item_add").put("args",new JSONObject(op.args));
    }
    public static final class Operation {
        public final String uuid, taskId, kind, state, title, error, args;
        public Operation(String uuid, String taskId, String kind, String state, String title, String error) {
            this(uuid,taskId,kind,state,title,error,"");
        }
        public Operation(String uuid,String taskId,String kind,String state,String title,String error,String args){
            this.args=args;
            this.uuid = uuid; this.taskId = taskId; this.kind = kind;
            this.state = state; this.title = title; this.error = error;
        }
        public boolean pending() { return !state.equals("failed"); }
    }
    public static final class Result {
        public final String uuid, state, error;
        public Result(String uuid, String state, String error) {
            this.uuid = uuid; this.state = state; this.error = error;
        }
    }
    public static List<Result> results(JSONObject response, List<Operation> sent) {
        JSONObject statuses = response.optJSONObject("sync_status");
        List<Result> out = new ArrayList<>();
        for (Operation op : sent) {
            Object status = statuses == null ? null : statuses.opt(op.uuid);
            if ("ok".equals(status)) { out.add(new Result(op.uuid, "ok", "")); continue; }
            if (status instanceof JSONObject) {
                JSONObject e = (JSONObject) status;
                int code = e.optInt("http_code", 0);
                String tag = e.optString("error_tag", "");
                // Missing tasks are already absent on the server: converge to that state.
                if (!op.kind.equals("add") && (code == 404 || tag.equals("ITEM_NOT_FOUND"))) {
                    out.add(new Result(op.uuid, "ok", ""));
                } else {
                    String msg = e.optString("error", "操作未被服务器接受") + (tag.isEmpty() ? "" : " (" + tag + ")");
                    // A temporary or unclassified response must retain the original UUID.
                    out.add(new Result(op.uuid, code >= 400 && code < 500 && code != 429 ? "failed" : "sending", msg));
                }
            } else out.add(new Result(op.uuid, "sending", "未收到操作确认，将用相同编号重试"));
        }
        return out;
    }
    public static List<JSONObject> objects(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) throw new IllegalArgumentException("Malformed resource array");
            out.add(j);
        }
        return out;
    }
}
