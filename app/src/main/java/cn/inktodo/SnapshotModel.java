package cn.inktodo;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;

/** Expanded date-scoped content, independent of interactive folding and sync timestamps. */
final class SnapshotModel {
    static final class Section {
        final String name;final int count;final List<Core.TreeRow> rows;
        Section(String name,int count,List<Core.TreeRow> rows){this.name=name;this.count=count;this.rows=rows;}
    }
    final String title,filter,project;final LocalDate day;final int pending;
    final Map<String,String> names;final List<Section> sections;
    private SnapshotModel(String title,String filter,String project,LocalDate day,int pending,Map<String,String> names,List<Section> sections){
        this.title=title;this.filter=filter;this.project=project;this.day=day;this.pending=pending;this.names=new TreeMap<>(names);this.sections=sections;
    }
    static SnapshotModel create(Collection<Core.Task> source,Set<String> hidden,String filter,String project,String inbox,Map<String,String> names,int pending,LocalDate day,ZoneId zone){
        filter=Core.normalizeFilter(filter,project,inbox);
        List<Core.Task> matches=Core.visible(source,hidden,filter,filter.equals("inbox")?inbox:project,day,zone);
        String title=filter.equals("today")?"今天":filter.equals("inbox")?"收件箱":names.getOrDefault(project,"项目已归档或删除");
        List<Section> sections=new ArrayList<>();
        if(filter.equals("today")){
            for(boolean overdue:new boolean[]{false,true}){
                List<Core.Task> group=new ArrayList<>();for(Core.Task t:matches)if((!day.equals(Core.dueDay(t.due,zone)))==overdue)group.add(t);
                sections.add(new Section(overdue?"逾期":"今天",group.size(),Core.tree(source,hidden,group,Collections.emptySet(),false)));
            }
        }else sections.add(new Section("",matches.size(),Core.tree(source,hidden,matches,Collections.emptySet(),true)));
        return new SnapshotModel(title,filter,project,day,pending,names,sections);
    }
    static SnapshotModel legacy(String title,List<Core.Task> tasks,Map<String,String> names,int pending){
        return new SnapshotModel(title,"project","",LocalDate.now(),pending,names,Collections.singletonList(new Section("",tasks.size(),Core.tree(tasks,Collections.emptySet(),tasks,Collections.emptySet(),true))));
    }
    String fingerprint(int width,int height,String font)throws Exception{
        JSONObject root=new JSONObject().put("style",8).put("title",title).put("filter",filter).put("project",project).put("day",day.toString()).put("pending",pending).put("width",width).put("height",height).put("font",font);
        JSONArray groups=new JSONArray();for(Section section:sections){
            JSONArray rows=new JSONArray();for(Core.TreeRow row:section.rows){Core.Task t=row.task;
                rows.put(new JSONArray().put(t.id).put(t.content).put(t.description).put(t.due).put(t.recurring).put(t.priority).put(t.order).put(t.parentId).put(row.depth).put(row.context).put(row.parent).put(row.matchingDescendants).put(names.getOrDefault(t.projectId,"")));
            }groups.put(new JSONArray().put(section.name).put(section.count).put(rows));
        }root.put("sections",groups);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(root.toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
}
