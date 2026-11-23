package cn.inktodo;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService IMAGE_IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final SyncRetry REAL_RETRY=new SyncRetry(),DEMO_RETRY=new SyncRetry();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private TokenVault vault;
    private SharedPreferences prefs;
    private String token = "", error = "", filter = "today", project = "";
    private boolean demo, resumed, destroyed, split;
    private long lastAttempt;
    private NetworkSync network;private boolean networkManual;
    private LinearLayout root, tasksBox, sideBox;
    private ScrollView taskScroll;
    private boolean resetTaskScroll = true;
    private TextView status, heading, count, appTitle;
    private Button syncButton;
    private LinearLayout compactNav;
    private Map<String,Integer> navCounts = Collections.emptyMap();
    private String compactSignature = "";
    private TextView dateLabel;
    private AlertDialog settingsDialog;
    private FontStore.Choice uiFont=FontStore.system();
    private java.util.function.Consumer<FontStore.Choice> fontImportResult;
    private final OnyxScreen eink = new OnyxScreen();
    private String taskSignature = "", sideSignature = "";
    private final Map<String,Set<String>> branchFolds=new HashMap<>();
    private final ArrayDeque<Store.UndoAction> completionUndos=new ArrayDeque<>();
    private Store undoOwner;private Store.UndoAction activeUndo;private PopupWindow undoPopup;
    private final Runnable expireUndo=()->finishUndo(false);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            render();
            long interval = prefs.getInt("interval", 10) * 60000L;
            long now=System.currentTimeMillis();
            if (now - lastAttempt >= interval || retry().automaticDue(now)) sync(false);
            handler.postDelayed(this, 30000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE); vault = new TokenVault(this);
        String savedTitle=prefs.getString("title_markdown","");
        if("墨待办".equals(savedTitle)||"未了".equals(savedTitle)||"未了之事，一勾即了".equals(savedTitle))prefs.edit().putString("title_markdown",TitleMarkdown.DEFAULT).apply();
        REAL_RETRY.restoreServerWait(prefs.getLong("sync_server_until",0),prefs.getBoolean("sync_rate_limited",true));
        demo = prefs.getBoolean("demo", false);
        try { token = vault.load(); } catch (Exception e) { error = "凭据无法解密，请在设置重新连接"; }
        network=new NetworkSync(getApplicationContext(),handler,new NetworkSync.Listener(){
            public void changed(){if(resumed&&!destroyed)render();}
            public void ready(){if(resumed&&!destroyed){retry().networkRecovered();boolean manual=networkManual;networkManual=false;sync(manual);}}
        });
        openStore(); build();
    }
    private void openStore() {
        commitUndoQueue();
        store = new Store(getApplicationContext(), demo);
        try { store.releaseAllUndo();if (demo) store.seedDemo(); } catch (Exception e) { error = "无法读取本地任务"; }
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); build(); }
    @Override protected void onResume() {
        if(!filter.equals("today")||!project.isEmpty()){filter="today";project="";resetTaskScroll=true;}
        super.onResume();if(!branchFolds.isEmpty()){branchFolds.clear();taskSignature="";}resumed = true; network.start();networkManual=false;retry().networkRecovered();screenFlag(); render(); sync(false); handler.removeCallbacks(tick); handler.postDelayed(tick, 30000);
        configureEink();
        root.post(() -> { if (resumed && !destroyed) eink.clean(root); });
    }
    @Override protected void onPause() { super.onPause(); resumed = false;network.stop();networkManual=false; handler.removeCallbacks(tick);if(commitUndoQueue()){render();sync(false);}eink.release(root);ScreensaverController.requestNow(getApplicationContext()); }
    @Override protected void onDestroy() { if(network!=null)network.stop();commitUndoQueue();destroyed = true; handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int font() { return prefs.getInt("font", 22); }
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(android.content.pm.PackageManager.NameNotFoundException ignored){return "";}}
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String s, int size, boolean bold) {
        TextView v = new InkRendering.Label(this); v.setText(s); v.setTextColor(Color.BLACK); v.setTextSize(size);
        v.setTypeface(uiFont.face,bold?Typeface.BOLD:Typeface.NORMAL);v.getPaint().setAntiAlias(true);v.getPaint().setSubpixelText(true);v.setIncludeFontPadding(true);return v;
    }
    private void applyFontTree(View view){
        if(view instanceof TextView){TextView label=(TextView)view;int style=label.getTypeface()==null?Typeface.NORMAL:label.getTypeface().getStyle();boolean title="app_title".equals(view.getTag())||"title_preview".equals(view.getTag());if(!"screensaver_font_preview".equals(view.getTag()))label.setTypeface(title?SnapshotImage.headingTypeface():uiFont.face,style);}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)applyFontTree(group.getChildAt(i));}
    }
    private final class FontDialogBuilder extends AlertDialog.Builder {
        FontDialogBuilder(Context context){super(context);}
        @Override public AlertDialog show(){AlertDialog dialog=super.show();applyFontTree(dialog.getWindow().getDecorView());return dialog;}
    }
    private GradientDrawable border(boolean selected) {
        GradientDrawable d = new InkRendering.Background(); d.setColor(selected ? Color.BLACK : Color.WHITE); d.setStroke(dp(1), Color.BLACK); d.setCornerRadius(prefs.getBoolean("square_buttons",true)?0:dp(3)); return d;
    }
    private Button button(String s, View.OnClickListener action) {
        Button b = new InkRendering.ButtonView(this); b.setText(s); b.setTextSize(16); b.setTextColor(Color.BLACK); b.setAllCaps(false);
        b.setTypeface(uiFont.face);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6)); b.setBackground(border(false)); b.setStateListAnimator(null);
        b.setElevation(0);b.setTranslationZ(0);b.setOutlineProvider(null);
        b.setOnClickListener(action); return b;
    }
    private void addButton(LinearLayout l, Button b, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(weight > 0 ? 0 : -2, -2, weight);
        p.setMargins(dp(3), dp(3), dp(3), dp(3)); l.addView(b, p);
    }
    private void line(LinearLayout l) { View v = new View(this); v.setBackgroundColor(Color.BLACK); l.addView(v, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void build() {
        String fontFile=prefs.getString("font_file","");
        if(!uiFont.file.equals(fontFile))uiFont=FontStore.load(this,fontFile,prefs.getString("font_name","自选字体"));
        taskSignature = ""; sideSignature = ""; compactSignature = ""; compactNav = null; resetTaskScroll = true;
        split = prefs.getBoolean("split", true) && getResources().getConfiguration().screenWidthDp >= 600;
        root = column(); root.setBackgroundColor(Color.WHITE); root.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout top = row(); top.setGravity(Gravity.TOP);top.setPadding(0,0,0,dp(12));
        appTitle = text("", 27, false); appTitle.setTag("app_title");
        appTitle.setMinHeight(dp(48)); appTitle.setGravity(Gravity.CENTER_VERTICAL);
        appTitle.setPadding(0, dp(6), dp(8), dp(6)); appTitle.setFocusable(true);
        appTitle.setTooltipText("点击编辑顶部文字"); appTitle.setOnClickListener(v -> editTitle()); updateAppTitle();
        ScrollView titleArea=new ScrollView(this){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int limit=dp(Math.max(100,getResources().getConfiguration().screenHeightDp/3));
                if(MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED)limit=Math.min(limit,MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST));
            }
        };
        titleArea.setFillViewport(false);titleArea.setOverScrollMode(View.OVER_SCROLL_NEVER);titleArea.addView(appTitle);
        top.addView(titleArea, new LinearLayout.LayoutParams(0, -2, 1));root.addView(top);
        status = text("", 12, false); status.setTextColor(0xff777777);
        status.setPadding(dp(4), dp(6), dp(4), dp(2));status.setIncludeFontPadding(false);status.setGravity(Gravity.TOP);status.setMaxLines(1);status.setEllipsize(TextUtils.TruncateAt.END);line(root);
        LinearLayout footer=column();footer.setTag("sidebar_footer");
        syncButton=button("同步",v -> sync(true));
        for(Button action:new Button[]{button("设置",v -> settings()),syncButton}){
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
            params.setMargins(dp(3),dp(3),dp(3),dp(3));footer.addView(action,params);
        }
        footer.addView(status,new LinearLayout.LayoutParams(-1,dp(26)));
        if (!demo && token.isEmpty()) {
            LinearLayout welcome = column(); welcome.setGravity(Gravity.CENTER_VERTICAL); welcome.setPadding(dp(12), dp(18), dp(12), dp(18));
            welcome.addView(text("把今天的事，\n安静地放在眼前。", 29, true));
            TextView note = text("连接 Todoist 后，这里会展示你的任务。\n\n免费账号可用；Token 只在本机加密保存。\n也可以先用演示模式体验分栏和勾选。", 18, false);
            note.setPadding(0, dp(24), 0, dp(24)); welcome.addView(note);
            addButton(welcome, button("连接 Todoist", v -> connect()), 0);
            addButton(welcome, button("先体验演示模式", v -> switchDemo(true)), 0);
            ScrollView sc = new ScrollView(this); sc.addView(welcome); root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(footer);
            TextView legal = text("独立第三方应用，非 Todoist 官方产品。", 12, false); root.addView(legal);
            tasksBox = null; taskScroll = null; sideBox = null; setContentView(root); render(); return;
        }
        LinearLayout body = row(); body.setGravity(Gravity.TOP); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        sideBox = null;
        if (split) {
            LinearLayout sidebarColumn = column(); sidebarColumn.setPadding(0, 0, dp(10), 0);
            sideBox = column(); sideBox.setPadding(0, dp(12), 0, 0);
            ScrollView nav = new ScrollView(this); nav.setFillViewport(true); nav.addView(sideBox);
            int sidebar = Math.min(210, Math.max(150, getResources().getConfiguration().screenWidthDp / 4));
            sidebarColumn.addView(nav, new LinearLayout.LayoutParams(-1, 0, 1));
            sidebarColumn.addView(footer, new LinearLayout.LayoutParams(-1, -2));
            body.addView(sidebarColumn, new LinearLayout.LayoutParams(dp(sidebar), -1));
            View divider = new View(this); divider.setBackgroundColor(Color.BLACK); body.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));
        }
        LinearLayout content = column(); content.setPadding(split ? dp(14) : 0, dp(12), 0, 0);
        body.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        if (!split) {
            compactNav = row(); content.addView(compactNav);
        }
        LinearLayout summary = row(); heading = text("", 24, true); summary.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        count = text("", 14, false); summary.addView(count); content.addView(summary);
        dateLabel = text("", 14, false);
        dateLabel.setPadding(0, dp(3), 0, dp(8)); content.addView(dateLabel);
        taskScroll = new ScrollView(this); taskScroll.setFillViewport(true); taskScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        taskScroll.setVerticalScrollBarEnabled(true); taskScroll.setScrollbarFadingEnabled(false);
        tasksBox = column(); taskScroll.addView(tasksBox);
        content.addView(taskScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!split) content.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root); screenFlag(); configureEink(); render();
    }
    private void updateAppTitle(){
        appTitle.setTypeface(SnapshotImage.headingTypeface(),Typeface.NORMAL);appTitle.setIncludeFontPadding(false);
        CharSequence rendered=TitleMarkdown.render(prefs.getString("title_markdown",TitleMarkdown.DEFAULT));
        if(demo){android.text.SpannableStringBuilder marked=new android.text.SpannableStringBuilder(rendered);marked.append(" · 演示");rendered=marked;}
        appTitle.setText(rendered);
        appTitle.setContentDescription(rendered+"，点击编辑顶部文字");
    }
    private void editTitle(){
        LinearLayout form=column();form.setPadding(dp(20),dp(6),dp(20),dp(8));
        TextView hint=text("支持 # 一级标题、## 二级标题、### 三级标题\n*斜体*、**粗体**、~~删除线~~、`代码`；回车换行。",13,false);
        hint.setTextColor(0xff777777);form.addView(hint);
        EditText input=new InkRendering.Input(this);input.setTag("title_input");input.setTextSize(18);input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);input.setMaxLines(6);input.setHorizontallyScrolling(false);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        input.setText(prefs.getString("title_markdown",TitleMarkdown.DEFAULT));form.addView(input,new LinearLayout.LayoutParams(-1,-2));
        TextView label=text("预览",13,false);label.setPadding(0,dp(10),0,dp(4));label.setTextColor(0xff777777);form.addView(label);
        TextView preview=text("",27,false);preview.setTag("title_preview");preview.setTypeface(SnapshotImage.headingTypeface());preview.setIncludeFontPadding(false);
        ScrollView previewArea=new ScrollView(this);previewArea.addView(preview);form.addView(previewArea,new LinearLayout.LayoutParams(-1,dp(120)));
        preview.setText(TitleMarkdown.render(input.getText().toString()));
        input.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){preview.setText(TitleMarkdown.render(s.toString()));}
            public void afterTextChanged(android.text.Editable s){}
        });
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new FontDialogBuilder(this).setTitle("编辑顶部文字").setView(scroll)
            .setNegativeButton("取消",null).setNeutralButton("恢复默认",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d -> {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> input.setText(TitleMarkdown.DEFAULT));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String source=input.getText().toString();
                if(TitleMarkdown.render(source).toString().trim().isEmpty()){input.setError("请输入可显示的文字");return;}
                prefs.edit().putString("title_markdown",source).apply();updateAppTitle();dialog.dismiss();refreshChanged(appTitle);
            });
        });dialog.show();applyFontTree(dialog.getWindow().getDecorView());
    }
    private void configureEink() { eink.configure(root, prefs.getBoolean("eink", false), prefs.getInt("gc_interval", 8)); }
    private void refreshChanged(View view) {
        View owner = root;
        view.post(() -> { if (resumed && !destroyed && owner == root) eink.changed(view); });
    }
    private void choose(String f, String p) {
        filter = f; project = p; resetTaskScroll = true; prefs.edit().putString("filter", f).putString("project", p).apply(); render();
    }
    private String viewName(Store.Snapshot s) {
        if (filter.equals("today")) return "今日事，今日了";
        if (filter.equals("project")) return s.projects.getOrDefault(project, "项目已归档或删除"); return "收件箱";
    }
    private void render() {
        if (destroyed || status == null) return;
        try {
            Store.Snapshot s = store.snapshot();
            ScreensaverController.request(getApplicationContext());
            String normalized = Core.normalizeFilter(filter, project, s.inboxId);
            if (!filter.equals(normalized)) {
                filter = normalized; project = ""; resetTaskScroll = true;
                prefs.edit().putString("filter", filter).putString("project", project).apply();
            }
            String time = s.lastSync == 0 ? "尚未同步" : "同步 " + Instant.ofEpochMilli(s.lastSync).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            String activeStatus=BUSY.get()?"同步中…":!demo&&network!=null&&network.state()==NetworkSync.State.WAITING?"等待网络…":!demo&&network!=null&&network.state()==NetworkSync.State.OFFLINE?"网络未连接":"";
            String st = !activeStatus.isEmpty()?activeStatus:demo?"演示模式":time;
            if(s.pending()>0)st+="\n待确认 "+s.pending()+" 项";
            String syncMessage=retry().message(System.currentTimeMillis());
            String detail=!activeStatus.isEmpty()?"":error.isEmpty()?syncMessage:error;
            String fullStatus=st+(detail.isEmpty()?"":"\n"+detail);
            String summary=!activeStatus.isEmpty()?activeStatus:detail.isEmpty()?(s.pending()>0?"待确认 "+s.pending()+" 项":st):detail;
            setText(status,summary);status.setContentDescription(fullStatus);status.setTooltipText(fullStatus);
            syncButton.setEnabled(demo || !token.isEmpty());
            if (tasksBox == null) return;
            setText(dateLabel, LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)));
            setText(heading, viewName(s));
            List<Core.Task> visible = Core.visible(s.tasks, s.hidden(), filter, filter.equals("inbox") ? s.inboxId : project, LocalDate.now(), ZoneId.systemDefault());
            setText(count, visible.size() + " 项");
            navCounts = Core.counts(s.tasks, s.hidden(), s.inboxId, LocalDate.now(), ZoneId.systemDefault());
            renderSidebar(s);
            renderCompactNav();
            List<Core.Task> shown = visible;
            Map<String,List<Core.TreeRow>> trees=new LinkedHashMap<>();
            if(filter.equals("today")){
                for(boolean overdue:new boolean[]{false,true}){
                    List<Core.Task> group=new ArrayList<>();for(Core.Task task:shown)if((!LocalDate.now().equals(Core.dueDay(task.due,ZoneId.systemDefault())))==overdue)group.add(task);
                    String scope=overdue?"today:overdue":"today:due_today";trees.put(scope,taskTree(s,group,scope,false));
                }
            }else{String scope="project:"+(filter.equals("inbox")?s.inboxId:project);trees.put(scope,taskTree(s,shown,scope,true));}
            StringBuilder signature = new StringBuilder(filter).append(project).append(font()).append(LocalDate.now());
            for(Map.Entry<String,List<Core.TreeRow>> entry:trees.entrySet()){
                signature.append(entry.getKey());
                for(Core.TreeRow node:entry.getValue()){
                    Core.Task t=node.task;
                    signature.append(t.id).append(t.content).append(t.description).append(t.due).append(t.recurring).append(t.priority).append(t.parentId).append(t.order).append(s.projectName(t.projectId));
                    signature.append(node.depth).append(node.childCount).append(node.matchingDescendants).append(node.context).append(node.collapsed).append(node.parent);
                }
            }
            for (Core.Operation op : s.operations) signature.append(op.uuid).append(op.state);
            if (!signature.toString().equals(taskSignature)) {
                int scrollPosition = resetTaskScroll ? 0 : taskScroll.getScrollY();
                resetTaskScroll = false;
                taskSignature = signature.toString(); tasksBox.removeAllViews();
                if (filter.equals("today")) {
                    todayGroups(shown,s,trees);
                } else if (shown.isEmpty()) {
                    TextView empty = text("这里暂时没有任务\n\n" + (s.pending() > 0 ? "部分任务正在等待同步，可在操作记录中查看。" : "可以切换左侧视图，或点“同步”获取最新任务。"), 20, false);
                    empty.setPadding(dp(4), dp(35), dp(4), dp(20)); tasksBox.addView(empty);
                }
                if (!filter.equals("today")) for(Map.Entry<String,List<Core.TreeRow>> entry:trees.entrySet())for(Core.TreeRow node:entry.getValue())taskRow(node,s,entry.getKey());
                ScrollView target = taskScroll;
                target.post(() -> { if (target == taskScroll) target.scrollTo(0, scrollPosition); });
                refreshChanged(tasksBox);
            }
            if (resetTaskScroll) { taskScroll.scrollTo(0, 0); resetTaskScroll = false; }
        } catch (Exception e) { setText(status, "本地数据暂不可用，请重新打开应用；不要清除应用数据。"); }
    }
    private List<Core.TreeRow> taskTree(Store.Snapshot snapshot,List<Core.Task> matches,String scope,boolean manual){
        Set<String> collapsed=branchFolds.getOrDefault((demo?"demo:":"real:")+scope,Collections.emptySet());
        return Core.tree(snapshot.tasks,snapshot.hidden(),matches,collapsed,manual);
    }
    private void toggleBranch(String scope,String id){
        String key=(demo?"demo:":"real:")+scope;
        Set<String> folded=branchFolds.computeIfAbsent(key,k->new HashSet<>());
        if(!folded.remove(id))folded.add(id);
        render();
    }
    private void todayGroups(List<Core.Task> tasks,Store.Snapshot snapshot,Map<String,List<Core.TreeRow>> trees) {
        LocalDate today=LocalDate.now();
        for(boolean overdue:new boolean[]{false,true}) {
            List<Core.Task> group=new ArrayList<>();
            for(Core.Task task:tasks)if((!today.equals(Core.dueDay(task.due,ZoneId.systemDefault())))==overdue)group.add(task);
            LinearLayout header=row();header.setPadding(0,dp(12),0,dp(8));
            header.addView(text(overdue?"逾期":"今天",18,true),new LinearLayout.LayoutParams(0,-2,1));
            TextView number=text(String.valueOf(group.size()),14,false);number.setTextColor(0xff777777);header.addView(number);tasksBox.addView(header);
            if(group.isEmpty()) {TextView empty=text("暂无任务",14,false);empty.setTextColor(0xff777777);empty.setPadding(0,dp(4),0,dp(14));tasksBox.addView(empty);}
            String scope=overdue?"today:overdue":"today:due_today";
            for(Core.TreeRow node:trees.get(scope))taskRow(node,snapshot,scope);
        }
    }
    private void renderSidebar(Store.Snapshot s) {
        if (sideBox == null) return;
        String sig = filter + project + s.inboxId + s.projects.toString() + navCounts; if (sig.equals(sideSignature)) return; sideSignature = sig;
        sideBox.removeAllViews(); TextView label = text("视图", 13, false); label.setPadding(dp(4), 0, 0, dp(6)); sideBox.addView(label);
        navItem("今天", "today", ""); navItem("收件箱", "inbox", "");
        View divider = new View(this); divider.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(0, dp(18), 0, 0); sideBox.addView(divider, dividerParams);
        TextView projects = text("项目", 13, false); projects.setPadding(dp(4), dp(22), 0, dp(6)); sideBox.addView(projects);
        for (Map.Entry<String, String> e : s.projects.entrySet()) navItem(e.getValue(), "project", e.getKey());
        if (s.projects.isEmpty()) sideBox.addView(text("同步后显示项目", 14, false));
        // The task-pane update also covers this interaction's GC interval.
        if (eink.active()) sideBox.postInvalidate();
    }
    private void navItem(String name, String f, String p) {
        Button b = countedButton(name,f,p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(3), 0, dp(3)); sideBox.addView(b, lp);
    }
    private Button countedButton(String name,String f,String p) {
        boolean selected=filter.equals(f) && (!f.equals("project") || project.equals(p));
        CountButton b=new CountButton(this,name,navCounts.getOrDefault(f.equals("project")?"project:"+p:f,0),selected,uiFont.face);
        b.setBackground(border(selected));b.setOnClickListener(v -> choose(f,p));return b;
    }
    private void renderCompactNav() {
        if(compactNav==null)return;
        String sig=filter+project+navCounts;if(sig.equals(compactSignature))return;compactSignature=sig;
        compactNav.removeAllViews();
        addButton(compactNav,countedButton("今天","today",""),1);
        addButton(compactNav,countedButton("收件箱","inbox",""),1);
        addButton(compactNav,button("项目",v -> projects()),1);
    }
    private void taskRow(Core.TreeRow node, Store.Snapshot s,String scope) {
        Core.Task t=node.task;int indent=Math.min(node.depth,4)*22;
        LinearLayout box = row(); box.setTag("task:"+t.id);box.setPadding(dp(indent), dp(12), 0, dp(12)); box.setGravity(Gravity.TOP);
        box.setBaselineAligned(false);
        boolean grouped=scope.startsWith("today:") && node.parent;
        CheckBox check = new InkRendering.Check(this); check.setText(""); check.setContentDescription("完成：" + t.content);
        check.setBackground(null); check.setStateListAnimator(null); check.setGravity(Gravity.TOP | Gravity.START);
        check.setPadding(0, dp(2), 0, 0); check.setMinHeight(0); check.setMinimumHeight(0);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.BLACK));
        if(grouped){check.setContentDescription("完成本组"+node.matchingDescendants+"项子任务："+t.content);check.setEnabled(node.matchingDescendants>0);if(node.matchingDescendants==0)check.setAlpha(.4f);}
        check.setOnClickListener(v -> { check.setChecked(false); check.jumpDrawablesToCurrentState();if(grouped)completeGroup(t,scope);else act(t, "close"); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(48), dp(48)); cp.setMargins(0, 0, dp(4), 0);
        box.addView(check, cp);
        LinearLayout words = column();LinearLayout titleLine=row();titleLine.setGravity(Gravity.TOP);
        TextView title = text(t.content, font(), node.parent);
        title.setMaxLines(4); title.setEllipsize(TextUtils.TruncateAt.END);titleLine.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if(node.childCount>0){
            View disclosure=new TreeToggle(this,node.collapsed);disclosure.setTag("toggle:"+t.id);
            disclosure.setContentDescription((node.collapsed?"展开子任务：":"收起子任务：")+t.content);
            disclosure.setOnClickListener(v -> toggleBranch(scope,t.id));titleLine.addView(disclosure,new LinearLayout.LayoutParams(dp(48),dp(32)));
            words.post(()->{android.graphics.Rect hit=new android.graphics.Rect(0,0,disclosure.getWidth(),disclosure.getHeight());words.offsetDescendantRectToMyCoords(disclosure,hit);hit.bottom=hit.top+dp(48);words.setTouchDelegate(new TouchDelegate(hit,disclosure));});
        }words.addView(titleLine);
        if(node.context || node.parent){
            String relation=(node.context?"关联父任务 · ":"")+(node.matchingDescendants>0?(grouped?"本组 ":"")+node.matchingDescendants+" 项子任务"+(node.collapsed?" · 已收起":""):(grouped?"本组暂无子任务":""));
            TextView hint=text(relation,13,false);hint.setTextColor(0xff666666);hint.setPadding(0,dp(4),0,0);words.addView(hint);
        }
        if (!t.description.trim().isEmpty()) {
            TextView description=text(t.description.trim(),15,false);description.setTextColor(0xff444444);
            description.setPadding(0,dp(5),0,0);description.setMaxLines(3);description.setEllipsize(TextUtils.TruncateAt.END);words.addView(description);
        }
        String meta = ""; LocalDate day = Core.dueDay(t.due, ZoneId.systemDefault());
        if (day != null) meta += (meta.isEmpty() ? "" : " · ") + (day.isBefore(LocalDate.now()) ? "逾期 " : "") + day.format(DateTimeFormatter.ofPattern("M/d"));
        if (t.recurring) meta += (meta.isEmpty()?"":" · ")+"重复"; if (t.priority > 1) meta += (meta.isEmpty()?"":" · ")+"P" + (5 - t.priority);
        for (Core.Operation op : s.operations) if (op.taskId.equals(t.id) && op.state.equals("failed")) { meta += (meta.isEmpty()?"":" · ")+"操作失败，见操作记录"; check.setEnabled(false); }
        String projectLabel=filter.equals("project")?"":s.projectName(t.projectId);
        if(!meta.isEmpty() || !projectLabel.isEmpty()) {
            LinearLayout metadata=row();metadata.setBaselineAligned(false);metadata.setPadding(0,dp(5),0,0);
            TextView due=text(meta,13,false);due.setTextColor(0xff666666);due.setMaxLines(2);due.setEllipsize(TextUtils.TruncateAt.END);
            metadata.addView(due,new LinearLayout.LayoutParams(0,-2,1));
            if(!projectLabel.isEmpty()) {
                TextView name=text(projectLabel,13,false);name.setTextColor(0xff666666);name.setGravity(Gravity.END);name.setMaxLines(1);name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(dp(8),0,0,0);metadata.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            }
            words.addView(metadata);
        }
        words.setOnClickListener(v -> detail(t, s)); words.setOnLongClickListener(v -> { detail(t, s); return true; });
        box.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        box.setOnClickListener(v -> detail(t,s));
        tasksBox.addView(box);
        View separator=new View(this);separator.setBackgroundColor(Color.BLACK);LinearLayout.LayoutParams rule=new LinearLayout.LayoutParams(-1,dp(1));rule.setMarginStart(dp(indent));tasksBox.addView(separator,rule);
    }
    private void detail(Core.Task t, Store.Snapshot s) {
        String info = s.projectName(t.projectId) + (t.due.isEmpty() ? "" : "\n到期：" + t.due)
            + (t.recurring ? "\n重复任务：完成后由 Todoist 安排下一次。" : "") + (t.description.isEmpty() ? "" : "\n\n" + t.description);
        new FontDialogBuilder(this).setTitle(t.content).setMessage(info).setPositiveButton("完成", (d, w) -> act(t, "close"))
            .setNeutralButton("删除…", (d, w) -> new FontDialogBuilder(this).setTitle("删除这项任务？")
                .setMessage(t.content + "\n\n其子任务也会删除；同步后无法在本应用撤销。")
                .setPositiveButton("删除任务", (x, y) -> act(t, "delete")).setNegativeButton("取消", null).show())
            .setNegativeButton("关闭", null).show();
    }
    private void act(Core.Task t, String kind) {
        try {
            if(kind.equals("close")){
                Store.UndoAction action=store.enqueueUndo(t);
                if(action==null){toast("这项任务或关联任务已有操作，请先查看操作记录");return;}
                render();offerUndo(action);return;
            }
            if (!store.enqueue(t, kind)) { toast("这项任务或关联任务已有操作，请先查看操作记录"); return; }
            render(); sync(false);
        } catch (Exception e) { toast("无法保存操作，任务未提交"); }
    }
    private void completeGroup(Core.Task parent,String scope){
        try{
            Store.UndoAction action=store.enqueueGroupUndo(parent,scope,LocalDate.now(),ZoneId.systemDefault());
            if(action.id.isEmpty()){toast("本组没有可单独完成的子任务；含其他日期子项的父级会保留");return;}
            render();offerUndo(action);
        }catch(IllegalStateException e){toast(e.getMessage());}
        catch(Exception e){toast("无法保存本组操作，未提交任务");}
    }
    private void offerUndo(Store.UndoAction action){
        if(undoOwner!=null&&undoOwner!=store)commitUndoQueue();
        undoOwner=store;completionUndos.addLast(action);showNextUndo();
    }
    private void showNextUndo(){
        if(activeUndo!=null||destroyed)return;
        while(!completionUndos.isEmpty()){
            Store.UndoAction action=completionUndos.removeFirst();if(!undoOwner.hasUndo(action.id))continue;activeUndo=action;
            LinearLayout bar=row();bar.setPadding(dp(12),dp(8),dp(12),dp(8));bar.setBackground(border(false));
            String message="已完成"+(action.completed>1?" "+action.completed+" 项":"")+" · "+action.title;
            TextView label=text(message,16,false);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);bar.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            Button undo=button("撤回",v->finishUndo(true));undo.setTag("completion_undo_action");undo.setContentDescription("撤回完成："+action.title);addButton(bar,undo,0);
            int width=Math.max(dp(240),(root.getWidth()>0?root.getWidth():getResources().getDisplayMetrics().widthPixels)-dp(32));
            undoPopup=new PopupWindow(bar,width,-2,false);undoPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));undoPopup.setElevation(0);undoPopup.setAnimationStyle(0);
            undoPopup.showAtLocation(root,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,dp(20));handler.postDelayed(expireUndo,3000);return;
        }
        undoOwner=null;
    }
    private void finishUndo(boolean cancel){
        if(activeUndo==null||undoOwner==null)return;
        try{
            boolean changed=cancel?undoOwner.cancelUndo(activeUndo.id):undoOwner.releaseUndo(activeUndo.id);
            if(cancel&&!changed)toast("这项操作已处理");
        }catch(Exception error){toast(cancel?"撤回失败，请重试":"无法更新操作状态，请稍后重试");if(!cancel)handler.postDelayed(expireUndo,1000);return;}
        handler.removeCallbacks(expireUndo);if(undoPopup!=null){undoPopup.dismiss();undoPopup=null;}activeUndo=null;
        render();showNextUndo();sync(false);
    }
    private boolean commitUndoQueue(){
        handler.removeCallbacks(expireUndo);if(undoPopup!=null){undoPopup.dismiss();undoPopup=null;}
        completionUndos.clear();activeUndo=null;Store owner=undoOwner;undoOwner=null;
        if(owner==null)return false;
        try{return owner.releaseAllUndo()>0;}catch(Exception error){this.error="完成操作已保留，将在下次打开时继续同步";return false;}
    }
    private SyncRetry retry(){return demo?DEMO_RETRY:REAL_RETRY;}
    private void resetRealRetry(){REAL_RETRY.reset();prefs.edit().remove("sync_server_until").remove("sync_rate_limited").apply();}
    private void sync(boolean manual) {
        if (!demo && token.isEmpty()) return;
        if(BUSY.get()){if(manual)toast("正在同步，请稍候");return;}
        if(!demo&&network!=null&&!network.ensureReady(manual)){if(resumed)networkManual|=manual;render();return;}
        networkManual=false;
        long now = System.currentTimeMillis();
        SyncRetry policy=retry();String blocked=policy.blocked(now,manual);
        if(!blocked.isEmpty()){if(manual)toast(blocked);render();return;}
        if (!BUSY.compareAndSet(false, true)){if(manual)toast("正在同步，请稍候");return;}
        policy.started(now);
        lastAttempt = now; error = ""; render();
        Store current = store; String credential = token; boolean isDemo = demo;
        IO.execute(() -> {
            boolean more = false;
            try {
                if (isDemo){current.demoSync();policy.completed(false,false,System.currentTimeMillis());}
                else {
                    List<Core.Operation> sent = current.claim();
                    JSONObject response = new ApiClient().sync(credential, current.snapshot().cursor, sent); current.merge(response, sent);
                    Store.Snapshot after = current.snapshot();
                    boolean failed = false, uncertain = false;
                    for (Core.Operation op : after.operations) { failed |= op.state.equals("failed"); uncertain |= op.state.equals("sending"); more |= op.state.equals("queued"); }
                    policy.completed(uncertain,failed,System.currentTimeMillis());
                }
            } catch (ApiClient.Failure e) {
                policy.failed(e.code,e.retryAt,System.currentTimeMillis());
            } catch (ApiClient.InvalidResponse e) {
                policy.failed(e.getMessage(),System.currentTimeMillis());
            } catch (java.io.IOException e) {
                policy.failedNetwork(System.currentTimeMillis());
            } catch (Exception e) {
                policy.failed("同步失败，请稍后重试",System.currentTimeMillis());
            } finally {
                if(!isDemo)prefs.edit().putLong("sync_server_until",policy.serverUntil()).putBoolean("sync_rate_limited",policy.rateLimited()).apply();
                BUSY.set(false);
            }
            final boolean again = more;
            ScreensaverController.requestNow(getApplicationContext());
            runOnUiThread(() -> { if (destroyed || current != store) return; render(); if (again && resumed) sync(false); });
        });
    }
    private void queue() {
        try {
            List<Core.Operation> ops = store.snapshot().operations;
            if (ops.isEmpty()) { toast("没有待确认或失败的操作"); return; }
            String[] labels = new String[ops.size()];
            for (int i = 0; i < ops.size(); i++) { Core.Operation op = ops.get(i); labels[i] = (op.kind.equals("close") ? "完成" : "删除") + " · " + op.title + "\n" + stateName(op.state); }
            new FontDialogBuilder(this).setTitle("操作记录").setItems(labels, (d, which) -> {
                Core.Operation op = ops.get(which); boolean canDiscard = !op.state.equals("sending");
                AlertDialog.Builder b = new FontDialogBuilder(this).setTitle(stateName(op.state))
                    .setMessage(op.title + "\n\n" + (op.error.isEmpty() ? "" : op.error + "\n\n")
                        + (canDiscard ? "尚未发送的操作可以撤销；失败记录可移除后重新操作。" : "请求可能已到达服务器，不能安全撤销。再次同步会复用原操作编号。"))
                    .setNegativeButton("关闭", null);
                if (canDiscard) b.setPositiveButton(op.state.equals("failed") ? "移除失败记录" : "撤销本地操作", (x, y) -> {
                    if (!store.discard(op.uuid)) toast("操作已开始发送，保留原编号等待确认"); render();
                });
                else b.setPositiveButton("再次同步", (x, y) -> sync(true)); b.show();
            }).setNegativeButton("关闭", null).show();
        } catch (Exception e) { toast("无法读取操作记录"); }
    }
    private String stateName(String state) { return state.equals("queued") ? "待发送" : state.equals("failed") ? "失败 · 需要处理" : "已发送或结果未明 · 待确认"; }
    private void projects() {
        try {
            Store.Snapshot s = store.snapshot(); List<String> ids = new ArrayList<>(s.projects.keySet());
            if (ids.isEmpty()) { toast("同步后显示项目"); return; }
            Map<String,Integer> totals=Core.counts(s.tasks,s.hidden(),s.inboxId,LocalDate.now(),ZoneId.systemDefault());
            String[] names = new String[ids.size()]; for (int i = 0; i < ids.size(); i++) names[i] = s.projects.get(ids.get(i))+"   "+totals.getOrDefault("project:"+ids.get(i),0);
            new FontDialogBuilder(this).setTitle("选择项目").setItems(names, (d, i) -> choose("project", ids.get(i))).setNegativeButton("取消", null).show();
        } catch (Exception e) { toast("无法读取项目"); }
    }
    private void settingSection(LinearLayout parent, String name) {
        TextView label=text(name,17,true);label.setPadding(0,dp(16),0,dp(8));parent.addView(label);
        View rule=new View(this);rule.setBackgroundColor(0xffbbbbbb);parent.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void settingRow(LinearLayout parent, String label, View control) {
        LinearLayout line=row();line.setPadding(0,dp(4),0,dp(4));
        line.addView(text(label,16,false),new LinearLayout.LayoutParams(0,-2,1));
        line.addView(control,new LinearLayout.LayoutParams(dp(136),dp(48)));parent.addView(line);
    }
    private CheckBox settingCheck(LinearLayout parent,String label,boolean value) {
        CheckBox check=new InkRendering.Check(this);check.setText(label);check.setTextSize(16);check.setTextColor(Color.BLACK);
        check.setTypeface(uiFont.face);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.BLACK));check.setChecked(value);
        check.setMinHeight(dp(48));check.setStateListAnimator(null);parent.addView(check);return check;
    }
    private void settingHint(LinearLayout parent,String message) {
        TextView hint=text(message,13,false);hint.setTextColor(0xff666666);hint.setPadding(0,dp(4),0,dp(8));parent.addView(hint);
    }
    private void settingAction(LinearLayout parent,Button action) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(dp(3),dp(3),dp(3),dp(3));parent.addView(action,params);
    }
    private void settings() {
        LinearLayout body=column();body.setPadding(dp(20),dp(4),dp(20),dp(8));
        LinearLayout tabs=row();Button displayTab=button("显示与同步",null),toolsTab=button("工具与账号",null);
        addButton(tabs,displayTab,1);addButton(tabs,toolsTab,1);body.addView(tabs);
        LinearLayout display=column(),tools=column();android.widget.FrameLayout pages=new android.widget.FrameLayout(this);
        pages.addView(display);pages.addView(tools);tools.setVisibility(View.GONE);
        ScrollView scroll=new ScrollView(this);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setScrollbarFadingEnabled(false);scroll.addView(pages);
        int contentHeight=Math.min(500,Math.max(220,getResources().getConfiguration().screenHeightDp-240));
        body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(contentHeight)));
        Runnable selectDisplay=() -> {display.setVisibility(View.VISIBLE);tools.setVisibility(View.GONE);scroll.scrollTo(0,0);displayTab.setBackground(border(true));displayTab.setTextColor(Color.WHITE);toolsTab.setBackground(border(false));toolsTab.setTextColor(Color.BLACK);};
        Runnable selectTools=() -> {display.setVisibility(View.GONE);tools.setVisibility(View.VISIBLE);scroll.scrollTo(0,0);toolsTab.setBackground(border(true));toolsTab.setTextColor(Color.WHITE);displayTab.setBackground(border(false));displayTab.setTextColor(Color.BLACK);};
        displayTab.setOnClickListener(v -> selectDisplay.run());toolsTab.setOnClickListener(v -> selectTools.run());selectDisplay.run();
        settingSection(display,"显示");
        Spinner font=spinner(new String[]{"20","22","26","30"});int[] fonts={20,22,26,30};font.setSelection(index(fonts,font()));settingRow(display,"任务字号",font);
        final FontStore.Choice[] selectedFont={uiFont};
        final AlertDialog[] owner={null};
        TextView fontName=text("当前字体："+uiFont.name,14,false);fontName.setTag("font_name");display.addView(fontName);
        LinearLayout fontActions=row();Button importFont=button("导入 TTF…",null),defaultFont=button("系统默认",null);
        addButton(fontActions,importFont,1);addButton(fontActions,defaultFont,1);display.addView(fontActions);
        TextView fontPreview=text("字体预览：今天，把重要的事做好。\n0123456789 · Regular / 粗体",17,false);fontPreview.setTag("font_preview");fontPreview.setPadding(0,dp(8),0,dp(8));display.addView(fontPreview);
        fontPreview.setText(TitleMarkdown.render("字体预览：今天，把重要的事做好。\n0123456789 · 常规 / **粗体** / *斜体*"));
        LinearLayout countPreview=row();display.addView(countPreview);
        Runnable updateFontPreview=() -> {
            fontName.setText("当前字体："+selectedFont[0].name);fontPreview.setTypeface(selectedFont[0].face);
            countPreview.removeAllViews();
            for(boolean selected:new boolean[]{false,true}){
                CountButton sample=new CountButton(this,selected?"选中":"未选中",28,selected,selectedFont[0].face);sample.setBackground(border(selected));sample.setClickable(false);sample.setFocusable(false);addButton(countPreview,sample,1);
            }
        };updateFontPreview.run();
        defaultFont.setOnClickListener(v -> {FontStore.discard(this,selectedFont[0],prefs.getString("font_file",""));selectedFont[0]=FontStore.system();updateFontPreview.run();});
        importFont.setOnClickListener(v -> {
            if(fontImportResult!=null){toast("正在导入字体，请稍候");return;}
            fontImportResult=choice -> {
                importFont.setEnabled(true);if(owner[0]!=null)owner[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if(choice==null)return;
                if(destroyed || owner[0]==null || !owner[0].isShowing()){FontStore.discard(this,choice,prefs.getString("font_file",""));return;}
                FontStore.discard(this,selectedFont[0],prefs.getString("font_file",""));selectedFont[0]=choice;updateFontPreview.run();
            };
            try{
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                importFont.setEnabled(false);owner[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);startActivityForResult(pick,91);
            }catch(ActivityNotFoundException e){java.util.function.Consumer<FontStore.Choice> callback=fontImportResult;fontImportResult=null;callback.accept(null);toast("未找到文件选择器，请先启用系统文件管理器");}
        });
        settingHint(display,"选择 .ttf 文件后先预览，点底部保存生效；取消不更换。字体会复制到本机，原文件之后可移除。建议使用常规或中等字重的中文黑体。");
        CheckBox square=settingCheck(display,"使用方角按钮",prefs.getBoolean("square_buttons",true));
        CheckBox awake=settingCheck(display,"保持屏幕唤醒",prefs.getBoolean("awake",false));
        CheckBox columns=settingCheck(display,"宽屏使用左右分栏",prefs.getBoolean("split",true));
        settingHint(display,"常亮适合常驻展示，会增加耗电。");
        settingSection(display,"同步");
        Spinner interval=spinner(new String[]{"5 分钟","10 分钟","15 分钟","30 分钟"});int[] mins={5,10,15,30};interval.setSelection(index(mins,prefs.getInt("interval",10)));settingRow(display,"自动同步间隔",interval);
        settingHint(display,"仅在前台自动同步；唤醒后恢复。也可使用首页右上角的同步按钮。");
        settingSection(display,"墨水屏");
        settingHint(display,"建议在文石应用引擎优化 → 个性化中选择“标准”刷新模式；“极速”会损失边缘灰阶。应用默认沿用系统刷新。");
        CheckBox enhanced=settingCheck(display,"启用墨水屏刷新优化",prefs.getBoolean("eink",false));enhanced.setEnabled(OnyxScreen.isBoox());
        Spinner gc=spinner(new String[]{"4 次","8 次","16 次"});int[] gcValues={4,8,16};gc.setSelection(index(gcValues,prefs.getInt("gc_interval",8)));gc.setEnabled(OnyxScreen.isBoox());settingRow(display,"清残影间隔",gc);
        settingHint(display,"按任务页面内容变化计数。手动清残影放在“工具与账号”中。");
        settingSection(tools,"工具");LinearLayout actions=row();
        addButton(actions,button("操作记录",v -> {settingsDialog.dismiss();queue();}),1);
        addButton(actions,button("清残影",v -> {settingsDialog.dismiss();root.post(() -> {if(!eink.clean(root))toast("文石增强未启用，已执行普通重绘");});}),1);tools.addView(actions);
        settingSection(tools,"待办屏保");
        settingAction(tools,button("待办屏保预览…",v -> {settingsDialog.dismiss();previewScreensaver();}));
        Store.Snapshot projectSnapshot;
        try{projectSnapshot=store.snapshot();}catch(Exception e){toast("无法读取项目，请同步后重试");return;}
        final List<String> importantIds=new ArrayList<>(),importantNames=new ArrayList<>();
        importantIds.add("");importantNames.add("不设置重要项目（单栏）");
        for(Map.Entry<String,String> entry:projectSnapshot.projects.entrySet()){importantIds.add(entry.getKey());importantNames.add(entry.getValue());}
        String savedImportant=prefs.getString(ScreensaverProjects.key(demo),"");
        Spinner importantProject=spinner(importantNames.toArray(new String[0]));importantProject.setTag("screensaver_important_project");
        importantProject.setSelection(Math.max(0,importantIds.indexOf(savedImportant)));settingRow(tools,"重要项目",importantProject);
        settingHint(tools,"选择已同步的项目后，锁屏左侧显示待办，右侧“常记之事”只显示未完成的主任务，显示描述，隐藏子任务和日期。已在左侧出现的任务不重复展示。保存后生效。");
        if(!savedImportant.isEmpty()&&!projectSnapshot.projects.containsKey(savedImportant))settingHint(tools,"原重要项目已归档或删除，当前使用单栏；可重新选择。");
        final FontStore.Choice[] saverFont={ScreensaverFont.load(this)};
        TextView saverFontName=text("屏保字体："+saverFont[0].name,14,false);tools.addView(saverFontName);
        TextView saverFontPreview=text("小雪 · 未了之事，次第而行\n今天 11月23日 · 阅读与日课",20,false);
        saverFontPreview.setTag("screensaver_font_preview");saverFontPreview.setPadding(0,dp(8),0,dp(12));tools.addView(saverFontPreview);
        Runnable updateSaverFont=()->{saverFontName.setText("屏保字体："+saverFont[0].name);saverFontPreview.setTypeface(saverFont[0].face);};updateSaverFont.run();
        LinearLayout saverFontActions=row();Button importSaverFont=button("导入屏保字体 TTF…",null),resetSaverFont=button("恢复默认宋体",null);
        addButton(saverFontActions,importSaverFont,1);addButton(saverFontActions,resetSaverFont,1);tools.addView(saverFontActions);
        resetSaverFont.setOnClickListener(v->{FontStore.discard(this,saverFont[0],prefs.getString(ScreensaverFont.FILE,""));saverFont[0]=ScreensaverFont.defaults();updateSaverFont.run();});
        importSaverFont.setOnClickListener(v->{
            if(fontImportResult!=null){toast("正在导入字体，请稍候");return;}
            fontImportResult=choice->{
                importSaverFont.setEnabled(true);if(owner[0]!=null)owner[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if(choice==null)return;
                if(destroyed||owner[0]==null||!owner[0].isShowing()){FontStore.discard(this,choice,prefs.getString(ScreensaverFont.FILE,""));return;}
                FontStore.discard(this,saverFont[0],prefs.getString(ScreensaverFont.FILE,""));saverFont[0]=choice;updateSaverFont.run();
            };
            try{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);importSaverFont.setEnabled(false);owner[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);startActivityForResult(pick,91);}
            catch(ActivityNotFoundException e){java.util.function.Consumer<FontStore.Choice> callback=fontImportResult;fontImportResult=null;callback.accept(null);toast("未找到文件选择器，请先启用系统文件管理器");}
        });
        settingHint(tools,"整张屏保统一使用此字体，与应用正文字体独立。导入后点击保存生效；恢复默认使用设备宋体。预览使用已保存的字体。");
        ScreensaverController saver=ScreensaverController.get(getApplicationContext());
        CheckBox autoSaver=settingCheck(tools,"随任务变化更新待办屏保",saver.automatic());autoSaver.setEnabled(saver.configured()&&OnyxScreen.sdkAvailable());
        settingHint(tools,"先设为系统屏保，再按最近设置的视图自动更新。休眠时展示最近已同步内容；可关闭自动更新。");
        String saverError=saver.prefs.getString("last_error","");if(!saverError.isEmpty())settingHint(tools,saverError);
        settingSection(tools,"账号");
        TextView account=text(demo?"演示模式":token.isEmpty()?"尚未连接 Todoist":"已连接 Todoist",16,true);account.setPadding(0,dp(10),0,dp(8));tools.addView(account);
        LinearLayout accountActions=row();addButton(accountActions,button("连接管理",v -> connect()),1);
        addButton(accountActions,button(demo?"返回真实账号":"进入演示模式",v -> switchDemo(!demo)),1);tools.addView(accountActions);
        settingHint(tools,demo?"演示数据与真实账号完全隔离。":"连接管理可更换账号凭据。");
        Button disconnectButton=button("断开并清除本机数据…",v -> disconnect());disconnectButton.setTextColor(0xff555555);settingAction(tools,disconnectButton);
        settingHint(tools,"未了 "+appVersion()+(OnyxScreen.sdkAvailable()?"":" · 无 SDK 对照版")+" · 独立第三方应用，非由 Todoist 创建、关联或支持。");
        settingsDialog=new FontDialogBuilder(this).setTitle("设置").setView(body).setPositiveButton("保存",(d,w) -> {
            String selectedImportant=importantIds.get(importantProject.getSelectedItemPosition());
            try{if(!selectedImportant.isEmpty()&&!store.snapshot().projects.containsKey(selectedImportant)){toast("该项目已不可用，请重新选择");return;}}catch(Exception e){toast("无法读取项目，请重试");return;}
            prefs.edit().putString(ScreensaverProjects.key(demo),selectedImportant).putInt("interval",mins[interval.getSelectedItemPosition()]).putInt("font",fonts[font.getSelectedItemPosition()])
                .putBoolean("awake",awake.isChecked()).putBoolean("split",columns.isChecked()).putBoolean("eink",enhanced.isChecked())
                .putBoolean("square_buttons",square.isChecked()).putString("font_file",selectedFont[0].file).putString("font_name",selectedFont[0].name)
                .putString(ScreensaverFont.FILE,saverFont[0].file).putString(ScreensaverFont.NAME,saverFont[0].name)
                .putInt("gc_interval",gcValues[gc.getSelectedItemPosition()]).apply();uiFont=selectedFont[0];saver.setAutomatic(autoSaver.isChecked());build();
        }).setNegativeButton("取消",null).create();
        owner[0]=settingsDialog;
        settingsDialog.setOnDismissListener(d -> {FontStore.discard(this,selectedFont[0],prefs.getString("font_file",""));FontStore.discard(this,saverFont[0],prefs.getString(ScreensaverFont.FILE,""));});
        settingsDialog.show();applyFontTree(settingsDialog.getWindow().getDecorView());settingsDialog.getWindow().setLayout(dp(Math.min(640,getResources().getConfiguration().screenWidthDp-24)),WindowManager.LayoutParams.WRAP_CONTENT);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=91)return;
        final java.util.function.Consumer<FontStore.Choice> callback=fontImportResult;
        if(callback==null)return;
        if(result!=RESULT_OK || data==null || data.getData()==null){fontImportResult=null;callback.accept(null);return;}
        final android.net.Uri uri=data.getData();
        IMAGE_IO.execute(()->{
            FontStore.Choice choice=null;String failure="";
            try{
                String name="导入字体.ttf";
                try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
                    if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))name=cursor.getString(0);
                }
                try(java.io.InputStream stream=getContentResolver().openInputStream(uri)){if(stream==null)throw new java.io.IOException("无法读取字体文件");choice=FontStore.copy(getApplicationContext(),stream,name);}
            }catch(Exception|OutOfMemoryError e){failure=e instanceof java.io.IOException?e.getMessage():"字体导入失败，请换一个有效的 TTF 文件";}
            final FontStore.Choice imported=choice;final String message=failure;
            runOnUiThread(()->{fontImportResult=null;callback.accept(imported);if(imported==null&&!destroyed)toast(message==null?"字体导入失败":message);});
        });
    }
    private void previewScreensaver() {
        if (!demo && token.isEmpty()) { toast("请先连接账号或进入演示模式"); return; }
        try {
            Store.Snapshot s = store.snapshot(); android.graphics.Point size = new android.graphics.Point();
            getWindowManager().getDefaultDisplay().getRealSize(size);
            SnapshotModel model=ScreensaverProjects.model(this,demo,s,filter,project);
            FontStore.Choice snapshotFont=ScreensaverFont.load(this);
            final boolean previewDemo=demo;final String previewFont=snapshotFont.file;
            android.graphics.Bitmap bitmap = SnapshotImage.create(size.x,size.y,model,s.lastSync,snapshotFont.face);
            ImageView preview = new ImageView(this); preview.setImageBitmap(bitmap); preview.setAdjustViewBounds(true); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout frame = column(); frame.setPadding(dp(12), dp(6), dp(12), dp(6));
            frame.addView(preview, new LinearLayout.LayoutParams(-1, Math.max(dp(180), size.y / 2)));
            AlertDialog.Builder b = new FontDialogBuilder(this).setTitle("待办屏保预览")
                .setMessage("快照会保留今天/逾期分类并展开父子任务，超出部分标注数量。设为屏保后，将按设置自动更新已同步的变化。图片会保存到相册 Pictures/InkTodo。")
                .setView(frame).setNegativeButton("取消", null);
            if (OnyxScreen.isBoox()) b.setPositiveButton("设为系统屏保", null);
            AtomicBoolean exporting=new AtomicBoolean(false);
            AlertDialog dialog = b.create();
            dialog.setOnDismissListener(d -> {if(!exporting.get()){preview.setImageDrawable(null);if(!bitmap.isRecycled())bitmap.recycle();}});
            dialog.setOnShowListener(d -> {
                Button apply=dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(apply==null)return;
                apply.setOnClickListener(v -> {
                    if(android.os.Build.VERSION.SDK_INT<29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},28);toast("允许存储权限后，请重新点击设为系统屏保");return;
                    }
                    if(!exporting.compareAndSet(false,true))return;
                    apply.setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);dialog.setCancelable(false);dialog.setMessage("正在保存并校验屏保图片，请稍候…");
                    IMAGE_IO.execute(() -> {
                        boolean submitted=false;String failure="";
                        try {byte[] data=ScreensaverExport.encode(bitmap);submitted=ScreensaverController.get(getApplicationContext()).submitManual(data,model,previewDemo,size.x,size.y,previewFont);}
                        catch(Exception | OutOfMemoryError error){failure=error instanceof java.io.IOException?error.getMessage():"图片保存失败，请检查可用空间和存储权限";}
                        final boolean accepted=submitted;final String message=failure;
                        runOnUiThread(() -> {
                            preview.setImageDrawable(null);if(!bitmap.isRecycled())bitmap.recycle();exporting.set(false);dialog.dismiss();
                            if(destroyed)return;
                            toast(!message.isEmpty()?message:accepted?"已提交屏保请求，请等待文石系统的处理结果":"系统未接受屏保设置，请重试");
                        });
                    });
                });
            });dialog.show();applyFontTree(dialog.getWindow().getDecorView());
        } catch (Exception | OutOfMemoryError e) { toast("无法生成屏保预览，请稍后重试"); }
    }
    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this); ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values){
            @Override public View getView(int position,View recycled,ViewGroup parent){View view=super.getView(position,recycled,parent);applyFontTree(view);return view;}
            @Override public View getDropDownView(int position,View recycled,ViewGroup parent){View view=super.getDropDownView(position,recycled,parent);applyFontTree(view);return view;}
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); s.setMinimumHeight(dp(48)); return s;
    }
    private int index(int[] a, int n) { for (int i = 0; i < a.length; i++) if (a[i] == n) return i; return 0; }
    private void connect() {
        if (BUSY.get()) { toast("请等待本次同步结束后再更换账号"); return; }
        if (settingsDialog != null) settingsDialog.dismiss();
        LinearLayout l = column(); l.setPadding(dp(22), dp(8), dp(22), dp(8));
        l.addView(text("在 Todoist 网页端：设置 → 集成 → 开发者，复制个人 API Token。\n\n更换 Token 会清除本机真实账号缓存及待同步操作；不会清除 Todoist 云端任务。", 16, false));
        EditText input = new InkRendering.Input(this); input.setSingleLine(true); input.setHint("粘贴个人 API Token");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        l.addView(input); AlertDialog dialog = new FontDialogBuilder(this).setTitle("连接 Todoist").setView(l).setPositiveButton("保存并连接", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> { dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (value.length() < 16 || !value.matches("[A-Za-z0-9_\\-]+")) { input.setError("请输入完整的个人 API Token"); return; }
                if (BUSY.get()) { toast("请等待同步结束"); return; }
                Runnable apply = () -> {
                    try {
                        vault.save(value); new Store(getApplicationContext(), false).reset(); token = value; demo = false;
                        prefs.edit().putBoolean("demo", false).apply(); openStore(); filter = "today"; project = "";
                        resetRealRetry(); error = ""; input.setText(""); dialog.dismiss(); build(); sync(true);
                    } catch (Exception e) { toast("无法保存凭据，请重试"); }
                };
                try {
                    Store.Snapshot real = new Store(getApplicationContext(), false).snapshot();
                    if (!token.isEmpty() || !real.operations.isEmpty()) new FontDialogBuilder(this).setTitle("更换连接并清除本地缓存？")
                        .setMessage("本机还有 " + real.pending() + " 项待确认操作。请先同步重要操作；继续会丢弃本地操作记录。")
                        .setPositiveButton("继续更换", (x, y) -> { if (BUSY.get()) toast("请等待同步结束"); else apply.run(); }).setNegativeButton("取消", null).show();
                    else apply.run();
                } catch (Exception e) { toast("无法读取本地账号状态"); }
            });
        }); dialog.show();applyFontTree(dialog.getWindow().getDecorView());
    }
    private void disconnect() {
        if (BUSY.get()) { toast("请等待同步结束"); return; }
        if (settingsDialog != null) settingsDialog.dismiss();
        new FontDialogBuilder(this).setTitle("断开并清除本机数据？").setMessage("移除 Token、缓存和所有待确认操作。云端任务不会被清除。")
            .setPositiveButton("清除本机数据", (d, w) -> {
                if (BUSY.get()) { toast("请等待同步结束"); return; }
                try { vault.clear(); new Store(getApplicationContext(), false).reset(); token = ""; demo = false; error = "";
                    prefs.edit().putBoolean("demo", false).apply(); openStore(); resetRealRetry(); build(); }
                catch (Exception e) { toast("无法清除本地数据"); }
            }).setNegativeButton("取消", null).show();
    }
    private void switchDemo(boolean value) {
        if (BUSY.get()) { toast("请等待同步结束"); return; }
        if (settingsDialog != null) settingsDialog.dismiss();
        demo = value; prefs.edit().putBoolean("demo", value).apply(); openStore(); error = ""; DEMO_RETRY.reset();
        filter = "today"; project = ""; build(); if (!demo) sync(false);
    }
    private void screenFlag() {
        if (prefs.getBoolean("awake", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void setText(TextView v, String s) { if (v != null && !v.getText().toString().equals(s)) v.setText(s); }
}
