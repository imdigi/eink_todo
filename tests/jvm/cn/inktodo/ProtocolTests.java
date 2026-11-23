package cn.inktodo;

import org.json.*;
import java.time.*;
import java.util.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class ProtocolTests {
    private static int checks;
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; System.out.println("PASS " + label); }
    static Core.Task task(String id, String day, int priority) throws Exception {
        JSONObject t = new JSONObject().put("id", id).put("content", id).put("project_id", "p").put("priority", priority);
        if (!day.isEmpty()) t.put("due", new JSONObject().put("date", day)); return new Core.Task(t);
    }
    static String response() { return "{\"sync_token\":\"next\",\"items\":[],\"projects\":[]}"; }
    static Core.Task branch(String id,String parent,int order,String day)throws Exception{
        JSONObject j=new JSONObject().put("id",id).put("content",id).put("project_id","p").put("parent_id",parent).put("child_order",order);
        if(!day.isEmpty())j.put("due",new JSONObject().put("date",day));return new Core.Task(j);
    }
    static String ids(List<Core.TreeRow> rows){StringJoiner out=new StringJoiner(",");for(Core.TreeRow r:rows)out.add(r.task.id);return out.toString();}
    public static void main(String[] args) throws Exception {
        long retryNow=100000;
        SyncRetry retry=new SyncRetry();
        retry.started(retryNow);retry.failed("同步失败，请检查网络后重试",retryNow+100);
        check(!retry.blocked(retryNow+1000,true).isEmpty(),"manual retries retain a short double-tap guard");
        check(retry.blocked(retryNow+3000,true).isEmpty(),"manual network retry bypasses automatic cooldown after three seconds");
        check(!retry.blocked(retryNow+3000,false).isEmpty(),"automatic network retry keeps its cooldown");
        check(retry.automaticDue(retryNow+60100),"automatic retry becomes due without waiting the normal ten-minute interval");
        check(retry.message(retryNow+3000).contains("检查网络"),"network failure reason remains available during waiting");
        retry.failed(500,0,retryNow+100);
        check(retry.blocked(retryNow+3000,true).isEmpty()&&retry.message(retryNow+3000).equals("Todoist 暂时不可用，请稍后重试"),"manual service failure retry is allowed and clearly described");
        retry.failed(429,retryNow+10000,retryNow);
        check(retry.message(retryNow+1).equals("同步过于频繁，请在 10 秒后重试"),"rate-limit countdown rounds up remaining seconds");
        check(!retry.blocked(retryNow+9999,true).isEmpty()&&!retry.blocked(retryNow+9999,false).isEmpty(),"both manual and automatic sync respect server Retry-After");
        check(retry.blocked(retryNow+10000,true).isEmpty(),"explicit short server wait is not extended to sixty seconds");
        retry.failed(429,0,retryNow);
        check(retry.serverUntil()==retryNow+60000,"rate limit without Retry-After uses a safe sixty-second fallback");
        retry.failed(429,retryNow+120000,retryNow);
        check(!retry.blocked(retryNow+60000,true).isEmpty(),"server waits longer than a minute are preserved");
        retry.failed(503,retryNow+20000,retryNow);
        check(!retry.blocked(retryNow+19000,true).isEmpty()&&retry.blocked(retryNow+20000,true).isEmpty(),"service-unavailable Retry-After is also mandatory");
        retry.failed(401,0,retryNow);
        check(retry.message(retryNow).equals("授权已失效，请在设置中重新设置"),"expired authorization uses the agreed wording");
        check(!retry.blocked(retryNow+120000,true).isEmpty()&&!retry.automaticDue(retryNow+120000),"expired authorization requires resetting credentials instead of timed retry");
        retry.reset();check(retry.blocked(retryNow,true).isEmpty()&&retry.message(retryNow).isEmpty(),"resetting authorization clears previous failure state");
        retry.started(retryNow);retry.completed(true,false,retryNow+100);
        check(retry.blocked(retryNow+3000,true).isEmpty()&&!retry.blocked(retryNow+3000,false).isEmpty(),"unconfirmed operations allow manual retry while delaying automatic retry");
        retry.completed(false,false,retryNow+3100);
        check(retry.message(retryNow+3100).isEmpty()&&!retry.automaticDue(retryNow+999999),"successful sync clears failure and retry schedule");
        retry.failedNetwork(retryNow);check(!retry.blocked(retryNow+1000,false).isEmpty(),"network error retains normal retry cooldown before recovery");
        retry.networkRecovered();check(retry.blocked(retryNow+1000,false).isEmpty()&&retry.message(retryNow+1000).isEmpty(),"network recovery clears only the network failure cooldown and stale reason");
        retry.failed(429,retryNow+90000,retryNow);retry.networkRecovered();check(!retry.blocked(retryNow+1000,false).isEmpty(),"network recovery cannot bypass server rate limits");
        retry.failed(503,retryNow+90000,retryNow);retry.networkRecovered();check(!retry.blocked(retryNow+1000,true).isEmpty(),"network recovery cannot bypass service Retry-After");
        retry.failed(401,0,retryNow);retry.networkRecovered();check(!retry.blocked(retryNow+120000,true).isEmpty(),"network recovery cannot clear expired authorization");
        retry.failed("同步响应异常，请稍后重试",retryNow);retry.networkRecovered();check(!retry.blocked(retryNow+1000,false).isEmpty(),"network recovery preserves non-network failure cooldowns");
        retry.reset();
        SyncRetry restored=new SyncRetry();restored.restoreServerWait(retryNow+90000,true);
        check(!restored.blocked(retryNow,true).isEmpty(),"persisted server wait survives process recreation");
        restored=new SyncRetry();restored.restoreServerWait(retryNow+90000,false);
        check(restored.message(retryNow).startsWith("Todoist 暂时不可用"),"restored service wait is not mislabeled as rate limiting");
        check(SolarTerm.name(LocalDate.of(2026,11,21)).equals("立冬"),"day before light snow retains previous solar term");
        check(SolarTerm.name(LocalDate.of(2026,11,22)).equals("小雪"),"light snow applies for the whole transition day");
        check(SolarTerm.name(LocalDate.of(2026,11,23)).equals("小雪"),"ordinary day keeps current solar term");
        check(SolarTerm.name(LocalDate.of(2027,1,1)).equals("冬至"),"solar term lookup crosses calendar years");
        check(SolarTerm.name(LocalDate.of(2024,2,29)).equals("雨水"),"leap day solar term is calculated without fixed annual dates");
        check(SolarTerm.date(LocalDate.of(2026,1,5)).equals("01月05日"),"snapshot generation date uses padded month and day");
        ZoneId sh = ZoneId.of("Asia/Shanghai"); LocalDate today = LocalDate.of(2026, 11, 21);
        check(Core.dueDay("2026-11-20T18:00:00Z", sh).equals(today), "UTC due time converts to local day");
        check(Core.dueDay("2026-11-21T08:30:00", sh).equals(today), "floating due time retains local day");
        check(Core.dueDay("bad-date", sh) == null && Core.dueDay("", sh) == null, "invalid and absent dates are safe");
        List<Core.Task> tasks = Arrays.asList(task("old", "2026-11-20", 1), task("normal", "2026-11-21", 1), task("high", "2026-11-21", 4), task("future", "2026-11-22", 1), task("none", "", 1));
        List<Core.Task> all = Core.visible(tasks, Collections.emptySet(), "project", "p", today, sh);
        check(all.get(0).id.equals("future") && all.get(4).id.equals("old"), "project uses manual order with stable ID tie break, not due-day sorting");
        List<Core.Task> grouped=Core.visible(tasks, Collections.emptySet(), "today", "", today, sh);
        check(grouped.size()==3,"today includes overdue but excludes future and undated");
        check(grouped.get(0).id.equals("high") && grouped.get(1).id.equals("normal") && grouped.get(2).id.equals("old"),"today group precedes overdue while priority ordering is retained");
        check(Core.visible(tasks, Collections.emptySet(), "overdue", "", today, sh).size() == 1, "overdue excludes future tasks");
        check(Core.visible(tasks, Collections.emptySet(), "project", "other", today, sh).isEmpty(), "project isolation");
        Core.Task parent = task("parent", "", 1);
        Core.Task child = new Core.Task(new JSONObject().put("id", "child").put("parent_id", "parent").put("project_id", "p"));
        check(Core.visible(Arrays.asList(parent, child), Collections.singleton("parent"), "project", "p", today, sh).isEmpty(), "pending parent hides descendant");
        Map<String, Core.Task> cyclic = new HashMap<>(); cyclic.put("a", new Core.Task(new JSONObject().put("id", "a").put("parent_id", "b")));
        cyclic.put("b", new Core.Task(new JSONObject().put("id", "b").put("parent_id", "a")));
        check(!Core.descendant("a", "missing", cyclic), "malformed parent cycle terminates");
        Core.Task root=branch("root","",1,""),kid1=branch("kid1","root",1,"2026-11-21"),kid2=branch("kid2","root",2,"2026-11-20"),futureKid=branch("futureKid","root",3,"2026-11-22"),grand=branch("grand","kid1",1,"2026-11-21"),second=branch("second","",2,"2026-11-19");
        List<Core.Task> family=Arrays.asList(futureKid,kid2,second,grand,root,kid1);
        List<Core.TreeRow> tree=Core.tree(family,Collections.emptySet(),family,Collections.emptySet(),true);
        check(ids(tree).equals("root,kid1,grand,kid2,futureKid,second"),"project preorder keeps entire subtree before next root and siblings in child_order");
        check(tree.get(0).depth==0 && tree.get(1).depth==1 && tree.get(2).depth==2,"nested task depths are explicit");
        check(tree.get(0).childCount==3 && tree.get(0).matchingDescendants==4,"parent counts unfinished descendants without counting itself");
        check(ids(Core.tree(family,Collections.emptySet(),family,Collections.singleton("root"),true)).equals("root,second"),"collapsed descendants cannot reappear as orphan roots");
        check(ids(Core.tree(family,Collections.emptySet(),family,Collections.singleton("kid1"),true)).equals("root,kid1,kid2,futureKid,second"),"nested collapse hides only its own descendants");
        List<Core.Task> dayMatches=Arrays.asList(kid1,grand);
        List<Core.TreeRow> dayTree=Core.tree(family,Collections.emptySet(),dayMatches,Collections.emptySet(),false);
        check(ids(dayTree).equals("root,kid1,grand"),"today tree includes matching tasks and ancestors without overdue or future siblings");
        check(dayTree.get(0).context && !dayTree.get(1).context && dayTree.get(0).matchingDescendants==2,"undated parent is context only and counts matching descendants");
        List<Core.TreeRow> overdueTree=Core.tree(family,Collections.emptySet(),Arrays.asList(second,kid2),Collections.emptySet(),false);
        check(ids(overdueTree).equals("second,root,kid2"),"date view roots retain filtered date/priority ranking while children remain grouped");
        check(overdueTree.get(1).context && overdueTree.get(1).matchingDescendants==1,"overdue group includes only its own child subset");
        check(ids(Core.tree(family,Collections.emptySet(),dayMatches,Collections.singleton("root"),false)).equals("root"),"context parents can collapse without losing filtered counts");
        check(Core.tree(family,Collections.singleton("root"),dayMatches,Collections.emptySet(),false).isEmpty(),"pending parent hides both context and matching descendants");
        Core.Task missing=branch("orphan","absent",1,"2026-11-21");
        List<Core.TreeRow> orphan=Core.tree(Collections.singleton(missing),Collections.emptySet(),Collections.singletonList(missing),Collections.emptySet(),true);
        check(orphan.size()==1 && orphan.get(0).depth==0,"missing parent keeps child accessible instead of losing it");
        List<Core.Task> loop=Arrays.asList(branch("loop-a","loop-b",1,""),branch("loop-b","loop-a",2,""));
        check(Core.tree(loop,Collections.emptySet(),loop,Collections.emptySet(),true).size()==2,"cyclic parent data terminates and emits each task once");
        check(Core.tree(loop,Collections.emptySet(),loop,Collections.singleton("loop-a"),true).size()==1,"collapsed malformed cycle does not resurrect hidden children");
        check(new Core.Task(new JSONObject().put("id","rest").put("order",7)).order==7,"REST order field supported when child_order is absent");
        check(Core.visible(family,Collections.emptySet(),"project","p",today,sh).get(0).id.equals("root"),"project visible list is hierarchy ordered despite children due before parent");
        check(Core.visible(family,Collections.emptySet(),"inbox","p",today,sh).get(0).id.equals("root"),"inbox retains hierarchy as a single-project view");
        Core.GroupClose closeToday=Core.groupClose(family,Collections.emptySet(),"root","today:due_today",today,sh);
        check(closeToday.targets.size()==1&&closeToday.targets.get(0).id.equals("kid1")&&closeToday.completed==2,"group close targets only matching child subtree, never grouping parent");
        Core.GroupClose closeOld=Core.groupClose(family,Collections.emptySet(),"root","today:overdue",today,sh);
        check(closeOld.targets.size()==1&&closeOld.targets.get(0).id.equals("kid2")&&closeOld.completed==1,"overdue bulk excludes today and future siblings");
        List<Core.Task> outside=new ArrayList<>(family);outside.add(branch("futureGrand","kid1",2,"2026-11-22"));
        Core.GroupClose limited=Core.groupClose(outside,Collections.emptySet(),"root","today:due_today",today,sh);
        check(limited.targets.size()==1&&limited.targets.get(0).id.equals("grand")&&limited.completed==1&&limited.deferred==1,"intermediate parent with future descendants stays open while safe matching leaf completes");
        Core.Task repeating=new Core.Task(new JSONObject().put("id","kid1").put("project_id","p").put("parent_id","root").put("child_order",1).put("due",new JSONObject().put("date","2026-11-21").put("is_recurring",true)));
        List<Core.Task> repeatFamily=new ArrayList<>(family);repeatFamily.remove(kid1);repeatFamily.add(repeating);
        Core.GroupClose repeatPlan=Core.groupClose(repeatFamily,Collections.emptySet(),"root","today:due_today",today,sh);
        check(repeatPlan.targets.size()==1&&repeatPlan.targets.get(0).id.equals("grand")&&repeatPlan.deferred==1,"bulk completes recurring parent's matching children without rescheduling that parent");
        Core.Task repeatingLeaf=new Core.Task(new JSONObject().put("id","recLeaf").put("project_id","p").put("parent_id","root").put("child_order",4).put("due",new JSONObject().put("date","2026-11-21").put("is_recurring",true)));
        List<Core.Task> withLeaf=new ArrayList<>(family);withLeaf.add(repeatingLeaf);
        check(Core.groupClose(withLeaf,Collections.emptySet(),"root","today:due_today",today,sh).completed==3,"recurring leaf remains eligible for normal recurring-aware completion");
        check(Core.groupClose(family,Collections.singleton("kid1"),"root","today:due_today",today,sh).targets.isEmpty(),"pending child subtree cannot be completed again via group checkbox");
        check(Core.groupClose(family,Collections.emptySet(),"missing","today:due_today",today,sh).targets.isEmpty(),"missing grouping parent is harmless");
        check(dayTree.get(0).parent && !dayTree.get(2).parent,"parent roles include out-of-date children but leaves stay ordinary tasks");
        Map<String,String> projectNames=Collections.singletonMap("p","示例项目");
        SnapshotModel screen=SnapshotModel.create(family,Collections.emptySet(),"today","","",projectNames,0,today,sh);
        check(screen.sections.size()==2&&screen.sections.get(0).name.equals("今天")&&screen.sections.get(1).name.equals("逾期"),"screensaver always preserves today and overdue headings");
        check(screen.sections.get(0).count==2&&screen.sections.get(1).count==2,"screensaver section counts exclude parent context");
        check(ids(screen.sections.get(0).rows).equals("root,kid1,grand")&&screen.sections.get(0).rows.get(2).depth==2,"screensaver fully expands filtered task hierarchy");
        String fingerprint=screen.fingerprint(1264,1680,"");
        check(fingerprint.equals(SnapshotModel.create(family,Collections.emptySet(),"today","","",projectNames,0,today,sh).fingerprint(1264,1680,"")),"unchanged content has stable fingerprint without sync timestamps");
        List<Core.Task> added=new ArrayList<>(family);added.add(branch("newToday","",3,"2026-11-21"));
        check(!fingerprint.equals(SnapshotModel.create(added,Collections.emptySet(),"today","","",projectNames,0,today,sh).fingerprint(1264,1680,"")),"new matching item changes screensaver fingerprint");
        check(!fingerprint.equals(SnapshotModel.create(family,Collections.singleton("kid1"),"today","","",projectNames,1,today,sh).fingerprint(1264,1680,"")),"pending completion changes screensaver content immediately");
        List<Core.Task> removed=new ArrayList<>(family);removed.remove(kid2);
        check(!fingerprint.equals(SnapshotModel.create(removed,Collections.emptySet(),"today","","",projectNames,0,today,sh).fingerprint(1264,1680,"")),"removed matching item changes screensaver fingerprint");
        List<Core.Task> edited=new ArrayList<>(family);edited.remove(kid1);edited.add(new Core.Task(new JSONObject().put("id","kid1").put("content","改过的标题").put("description","改过的描述").put("parent_id","root").put("project_id","p").put("child_order",1).put("due",new JSONObject().put("date","2026-11-21"))));
        check(!fingerprint.equals(SnapshotModel.create(edited,Collections.emptySet(),"today","","",projectNames,0,today,sh).fingerprint(1264,1680,"")),"edited title and description change screensaver fingerprint");
        check(!fingerprint.equals(screen.fingerprint(1264,1680,"custom.ttf"))&&!fingerprint.equals(screen.fingerprint(1680,1264,"")),"font and orientation changes invalidate screensaver image");
        check(!fingerprint.equals(SnapshotModel.create(family,Collections.emptySet(),"today","","",projectNames,0,today.plusDays(1),sh).fingerprint(1264,1680,"")),"date rollover updates section membership without task edits");
        SnapshotModel emptyScreen=SnapshotModel.create(Collections.emptyList(),Collections.emptySet(),"today","","",projectNames,0,today,sh);
        check(emptyScreen.sections.size()==2&&emptyScreen.sections.get(0).count==0&&emptyScreen.sections.get(1).count==0,"empty screensaver still has both date groups");
        Map<String,String> goalProjects=new LinkedHashMap<>(projectNames);goalProjects.put("g","个人提升");
        List<Core.Task> goalsSource=new ArrayList<>(family);
        Core.Task goal1=new Core.Task(new JSONObject().put("id","g1").put("project_id","g").put("content","持续阅读").put("child_order",2).put("description","不在屏保展示"));
        Core.Task goal2=new Core.Task(new JSONObject().put("id","g2").put("project_id","g").put("content","练好英语").put("child_order",1).put("due",new JSONObject().put("date",today.plusDays(7).toString())));
        Core.Task goalChild=new Core.Task(new JSONObject().put("id","gc").put("project_id","g").put("parent_id","g1").put("content","子任务"));
        Core.Task goalOrphan=new Core.Task(new JSONObject().put("id","orphan").put("project_id","g").put("parent_id","absent").put("content","父级缺失也不冒充主任务"));
        goalsSource.addAll(Arrays.asList(goal1,goal2,goalChild,goalOrphan,new Core.Task(new JSONObject().put("id","gd").put("project_id","g").put("checked",1)),new Core.Task(new JSONObject().put("id","gx").put("project_id","g").put("is_deleted",1))));
        SnapshotModel withGoals=SnapshotModel.create(goalsSource,Collections.emptySet(),"today","","",goalProjects,0,today,sh).withImportant(goalsSource,Collections.emptySet(),"g",goalProjects);
        check(withGoals.hasImportant()&&withGoals.goals.size()==2,"important project includes only active unfinished top-level tasks, excluding children and orphan children");
        check(withGoals.goals.get(0).id.equals("g2")&&withGoals.goals.get(1).id.equals("g1"),"important goals follow project order regardless of future or absent deadline");
        String goalHash=withGoals.fingerprint(1264,1680,"");
        List<Core.Task> editedGoals=new ArrayList<>(goalsSource);editedGoals.remove(goal1);editedGoals.add(new Core.Task(new JSONObject().put("id","g1").put("project_id","g").put("content","持续阅读").put("child_order",2).put("description","修改隐藏描述").put("due",new JSONObject().put("date",today.plusDays(20).toString()))));
        check(!goalHash.equals(SnapshotModel.create(editedGoals,Collections.emptySet(),"today","","",goalProjects,0,today,sh).withImportant(editedGoals,Collections.emptySet(),"g",goalProjects).fingerprint(1264,1680,"")),"goal description updates invalidate important panel image");
        editedGoals=new ArrayList<>(goalsSource);
        editedGoals.remove(goalChild);editedGoals.add(new Core.Task(new JSONObject().put("id","gc").put("project_id","g").put("parent_id","g1").put("content","修改子任务")));
        check(goalHash.equals(SnapshotModel.create(editedGoals,Collections.emptySet(),"today","","",goalProjects,0,today,sh).withImportant(editedGoals,Collections.emptySet(),"g",goalProjects).fingerprint(1264,1680,"")),"undated child changes do not affect root-only important panel");
        List<Core.Task> titledGoals=new ArrayList<>(goalsSource);titledGoals.remove(goal1);titledGoals.add(new Core.Task(new JSONObject().put("id","g1").put("project_id","g").put("content","更新阅读目标").put("child_order",2)));
        check(!goalHash.equals(SnapshotModel.create(titledGoals,Collections.emptySet(),"today","","",goalProjects,0,today,sh).withImportant(titledGoals,Collections.emptySet(),"g",goalProjects).fingerprint(1264,1680,"")),"server-downloaded goal title updates invalidate image");
        check(withGoals.withImportant(goalsSource,Collections.singleton("g1"),"g",goalProjects).goals.size()==1,"pending parent completion hides goal immediately");
        SnapshotModel baseGoals=SnapshotModel.create(goalsSource,Collections.emptySet(),"today","","",goalProjects,0,today,sh);
        check(!baseGoals.withImportant(goalsSource,Collections.emptySet(),"missing",goalProjects).hasImportant()&&!baseGoals.withImportant(goalsSource,Collections.emptySet(),"",goalProjects).hasImportant(),"missing project and disabled selection retain single-column model");
        Map<String,String> renamedProjects=new LinkedHashMap<>(goalProjects);renamedProjects.put("g","成长计划");
        check(!goalHash.equals(baseGoals.withImportant(goalsSource,Collections.emptySet(),"g",renamedProjects).fingerprint(1264,1680,"")),"important project rename invalidates heading");
        List<Core.Task> datedGoals=new ArrayList<>(goalsSource);datedGoals.add(new Core.Task(new JSONObject().put("id","today-goal").put("project_id","g").put("content","今日目标").put("due",new JSONObject().put("date",today.toString()))));
        SnapshotModel dedupGoals=SnapshotModel.create(datedGoals,Collections.emptySet(),"today","","",goalProjects,0,today,sh).withImportant(datedGoals,Collections.emptySet(),"g",goalProjects);
        check(dedupGoals.goals.size()==2&&dedupGoals.sections.get(0).rows.stream().anyMatch(row->row.task.id.equals("today-goal")),"goals shown in date groups are not duplicated in important panel");
        Map<String,String> emptyProject=new HashMap<>();emptyProject.put("empty","空项目");check(baseGoals.withImportant(goalsSource,Collections.emptySet(),"empty",emptyProject).hasImportant()&&baseGoals.withImportant(goalsSource,Collections.emptySet(),"empty",emptyProject).goals.isEmpty(),"valid empty project retains panel for clear empty state");
        check(Core.truth(new JSONObject().put("flag", 1), "flag") && Core.truth(new JSONObject().put("flag", true), "flag"), "numeric and boolean sync flags");
        Core.Task completed = new Core.Task(new JSONObject().put("id", "done").put("checked", 1));
        check(Core.visible(Collections.singleton(completed), Collections.emptySet(), "project", "", today, sh).isEmpty(), "completed tasks are not visible");
        check(Core.visible(tasks, Collections.emptySet(), "inbox", "p", today, sh).size() == 5, "inbox uses exact project ID and includes all its due dates");
        check(Core.visible(tasks, Collections.emptySet(), "inbox", "missing", today, sh).isEmpty(), "inbox excludes other projects");
        check(Core.visible(tasks, Collections.emptySet(), "inbox", "", today, sh).isEmpty(), "unsynced inbox never falls back to all tasks");
        check(Core.visible(tasks, Collections.emptySet(), "all", "", today, sh).isEmpty(), "removed all view cannot expose all tasks");
        check(Core.isInboxProject(new JSONObject().put("inbox_project", true)), "sync inbox metadata recognized");
        check(Core.isInboxProject(new JSONObject().put("is_inbox_project", true)), "alternate inbox metadata recognized");
        check(!Core.isInboxProject(new JSONObject().put("name", "Inbox")), "ordinary project named Inbox is not reclassified");
        check(Core.normalizeFilter("all", "", "ib").equals("inbox"), "legacy all view migrates to inbox");
        check(Core.normalizeFilter("project", "ib", "ib").equals("inbox"), "legacy inbox project selection migrates to view");
        check(Core.normalizeFilter("project", "p", "ib").equals("project"), "ordinary project selection preserved");
        check(Core.normalizeFilter("overdue","","ib").equals("today"),"old overdue selection migrates to combined today view");
        Map<String,Integer> totals=Core.counts(tasks,Collections.emptySet(),"p",today,sh);
        check(totals.get("inbox")==5 && totals.get("today")==3 && totals.get("due_today")==2 && totals.get("overdue")==1 && totals.get("project:p")==5,"combined today count equals today plus overdue");
        Map<String,Integer> pendingTotals=Core.counts(tasks,Collections.singleton("normal"),"p",today,sh);
        check(pendingTotals.get("today")==2 && pendingTotals.get("due_today")==1 && pendingTotals.get("inbox")==4,"pending operation updates merged and subgroup counts immediately");
        check(Core.counts(Arrays.asList(parent,child),Collections.singleton("parent"),"p",today,sh).get("inbox")==0,"pending parent excludes descendant from counts");
        List<Core.Task> mixed=new ArrayList<>(tasks);mixed.add(new Core.Task(new JSONObject().put("id","other").put("project_id","q").put("due",new JSONObject().put("date","2026-11-21"))));
        Map<String,Integer> cross=Core.counts(mixed,Collections.emptySet(),"p",today,sh);
        check(cross.get("today")==4 && cross.get("due_today")==3 && cross.get("inbox")==5 && cross.get("project:q")==1,"today counter spans projects without expanding inbox");
        check(Core.visible(Collections.singleton(task("only-old","2026-11-20",1)),Collections.emptySet(),"today","",today,sh).size()==1,"combined view retains overdue when today group is empty");
        check(Core.visible(Collections.singleton(task("only-today","2026-11-21",1)),Collections.emptySet(),"today","",today,sh).size()==1,"combined view retains today when overdue group is empty");
        check(Core.counts(Collections.singleton(completed),Collections.emptySet(),"",today,sh).get("today")==0,"completed tasks excluded from navigation counts");
        check(new Core.Task(new JSONObject().put("id","desc").put("description","背景\nhttps://example.com")).description.equals("背景\nhttps://example.com"),"task descriptions preserve newlines and links");
        Core.Operation op = new Core.Operation("fixed-uuid", "recurring", "close", "sending", "daily", "");
        List<Core.Operation> sent = Collections.singletonList(op);
        JSONObject close = Core.command(op.uuid, op.taskId, op.kind);
        check(close.getString("type").equals("item_close") && close.getString("uuid").equals("fixed-uuid"), "close uses official recurring-aware command and UUID");
        check(Core.command("d", "t", "delete").getString("type").equals("item_delete"), "delete uses official command");
        check(Core.results(new JSONObject().put("sync_status", new JSONObject().put(op.uuid, "ok")), sent).get(0).state.equals("ok"), "command acknowledgement mapping");
        check(Core.results(new JSONObject(), sent).get(0).state.equals("sending"), "missing acknowledgement preserves uncertainty");
        for (int code : new int[]{400, 403, 429, 500, 404}) {
            JSONObject error = new JSONObject().put("http_code", code).put("error", "test");
            String expected = code == 404 ? "ok" : code == 429 || code == 500 ? "sending" : "failed";
            check(Core.results(new JSONObject().put("sync_status", new JSONObject().put(op.uuid, error)), sent).get(0).state.equals(expected), "command HTTP " + code + " classification");
        }
        JSONObject newArgs=Core.addArgs("  记下灵感  ",today,"inbox-id");
        Core.Operation add=new Core.Operation("add-uuid","temp-task","add","queued","记下灵感","",newArgs.toString());
        JSONObject addCommand=Core.command(add);
        check(addCommand.getString("type").equals("item_add")&&addCommand.getString("temp_id").equals("temp-task"),"create uses official item_add and stable temp id");
        check(addCommand.getJSONObject("args").getString("content").equals("记下灵感")&&newArgs.getString("project_id").equals("inbox-id"),"create trims text and targets inbox");
        check(newArgs.getJSONObject("due").getString("date").equals(today.toString())&&!newArgs.getJSONObject("due").getBoolean("is_recurring"),"create uses explicit local calendar date without recurring time");
        check(!Core.addArgs("task",today,"").has("project_id"),"missing cached inbox uses API default inbox");
        try{Core.addArgs("  ",today,"");throw new AssertionError("empty accepted");}catch(IllegalArgumentException expected){check(true,"blank create is rejected before queueing");}
        JSONObject missingProject=new JSONObject().put("sync_status",new JSONObject().put(add.uuid,new JSONObject().put("http_code",404).put("error_tag","ITEM_NOT_FOUND")));
        check(Core.results(missingProject,Collections.singletonList(add)).get(0).state.equals("failed"),"create 404 remains an error rather than completion acknowledgement");
        new ApiClient((token,bytes)->{
            String form=new String(bytes,StandardCharsets.UTF_8);String commands="";
            for(String pair:form.split("&")){String[] kv=pair.split("=",2);if(kv[0].equals("commands"))commands=URLDecoder.decode(kv[1],"UTF-8");}
            JSONObject transmittedAdd=new JSONArray(commands).getJSONObject(0);
            check(transmittedAdd.getString("type").equals("item_add")&&transmittedAdd.getString("uuid").equals(add.uuid)&&transmittedAdd.getJSONObject("args").getString("content").equals("记下灵感"),"sync transport sends creation args losslessly");
            return new ApiClient.Reply(200,response(),null);
        }).sync("test-token","*",Collections.singletonList(add));
        final List<String> transmitted = new ArrayList<>();
        ApiClient client = new ApiClient((token, bytes) -> {
            check(token.equals("test-token"), "transport gets credential separately");
            String form = new String(bytes, StandardCharsets.UTF_8); Map<String,String> fields = new HashMap<>();
            for (String pair : form.split("&")) { String[] kv = pair.split("=", 2); fields.put(kv[0], URLDecoder.decode(kv[1], "UTF-8")); }
            check(fields.get("sync_token").equals("cursor+/="), "sync cursor is form encoded losslessly");
            check(new JSONArray(fields.get("resource_types")).length() == 2, "only tasks and projects requested");
            JSONArray commands = new JSONArray(fields.get("commands")); transmitted.add(commands.getJSONObject(0).getString("uuid"));
            return new ApiClient.Reply(200, response(), null);
        });
        client.sync("test-token", "cursor+/=", sent); client.sync("test-token", "cursor+/=", sent);
        check(transmitted.size() == 2 && transmitted.get(0).equals(transmitted.get(1)), "network retry retains recurring-task UUID");
        try { new ApiClient((t,b) -> new ApiClient.Reply(200, "{\"sync_token\":\"x\"}", null)).sync("x", "*", sent); throw new AssertionError("incomplete"); }
        catch (java.io.IOException expected) { check(true, "incomplete response cannot advance cache cursor"); }
        try { new ApiClient((t,b) -> new ApiClient.Reply(401, "secret-error", null)).sync("x", "*", sent); throw new AssertionError("auth"); }
        catch (ApiClient.Failure expected) { check(expected.code == 401 && !expected.getMessage().contains("secret-error"), "auth errors do not expose response payload"); }
        try { new ApiClient((t,b) -> new ApiClient.Reply(429, "", "120")).sync("x", "*", sent); throw new AssertionError("limit"); }
        catch (ApiClient.Failure expected) { check(expected.retryAt > System.currentTimeMillis() + 119000, "Retry-After is respected"); }
        try { Core.objects(new JSONArray().put("invalid")); throw new AssertionError("malformed"); }
        catch (IllegalArgumentException expected) { check(true, "malformed resource array fails closed"); }
        System.out.println("SUCCESS " + checks + " assertions");
    }
}
