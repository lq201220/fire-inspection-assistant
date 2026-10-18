package cn.fireassistant;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static cn.fireassistant.Store.*;

@Component
public class DemoData implements ApplicationRunner {
    private final Store db;private final AuthService auth;private final boolean enabled;private final String password;
    private final Map<String,Object> seed;
    @SuppressWarnings("unchecked")
    DemoData(Store db,AuthService auth,@Value("${app.demo-enabled}")boolean enabled,@Value("${app.demo-password}")String password)throws Exception{this.db=db;this.auth=auth;this.enabled=enabled;this.password=password;try(var input=new ClassPathResource("demo/seed.json").getInputStream()){seed=db.json.readValue(input,Map.class);}}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(String key){return (List<Map<String,Object>>)seed.get(key);}
    @SuppressWarnings("unchecked") List<Map<String,Object>> template(String type,String source){String example=switch(type){case "商业综合体"->"XF-260830-02";case "住宅小区"->"XF-260829-17";case "养老机构"->"XF-260827-06";default->"XF-260830-01";};var list=(List<Map<String,Object>>)((Map<?,?>)seed.get("checklists")).get(example);return list.stream().map(original->{var item=new LinkedHashMap<>(original);item.put("status",null);item.put("note","");item.put("basisVersion","snapshot-2026-v1");item.put("applicableScene",type);item.put("source",source);return (Map<String,Object>)item;}).toList();}
    @Override @Transactional public void run(ApplicationArguments args){if(!enabled||db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user",Integer.class)>0)return;if(password.length()<6)throw new IllegalArgumentException("DEMO_PASSWORD must have at least six characters");seed();}
    void seed(){
        String org="区消防救援大队";var names=Map.of("admin","系统管理员","inspector","田博","reviewer","张三","rechecker","李四");var roles=Map.of("admin","ADMIN","inspector","INSPECTOR","reviewer","REVIEWER","rechecker","RECHECKER");var logins=Map.of("admin","admin","inspector","tianbo","reviewer","zhangsan","rechecker","lisi");
        for(String key:List.of("admin","inspector","reviewer","rechecker"))db.jdbc.update("INSERT INTO sys_user(id,username,password_hash,name,role,org_id) VALUES(?,?,?,?,?,?)","user-"+key,logins.get(key),auth.hashPassword(password),names.get(key),roles.get(key),org);
        for(var original:rows("units")){var unit=new LinkedHashMap<>(original);unit.put("orgId",org);unit.put("active",true);unit.put("updated",now());unit.put("updatedAt",now());db.insert("supervised_unit",unit,map("org_id",org,"active",true));}
        for(var original:rows("tasks")){var task=new LinkedHashMap<>(original);task.putAll(map("orgId",org,"inspectorId","user-inspector","reviewerId","user-reviewer","recheckerId","user-rechecker","version",0,"createdAt",now(),"updatedAt",now(),"due",OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString(),"site",null));if(!task.get("status").equals("待检查"))task.put("site",map("type",db.one("supervised_unit",task.get("unitId").toString()).get("type"),"height",24.6,"floors",6,"people",2480,"checkedBy","田博","checkedAt",now()));db.insert("inspect_task",task,map("unit_id",task.get("unitId"),"org_id",org,"inspector_id","user-inspector","reviewer_id","user-reviewer","rechecker_id","user-rechecker","status",task.get("status"),"version",0));}
        @SuppressWarnings("unchecked")var checklists=(Map<String,List<Map<String,Object>>>)seed.get("checklists");
        checklists.forEach((taskId,items)->{for(var original:items){var item=new LinkedHashMap<>(original);item.put("id",taskId+"-"+original.get("id"));item.put("taskId",taskId);item.put("note","");item.put("basisVersion","snapshot-2026-v1");item.put("applicableScene",db.one("supervised_unit",db.one("inspect_task",taskId).get("unitId").toString()).get("type"));if(taskId.equals("XF-260829-17")&&original.get("id").equals("community-hydrant"))item.put("status","abnormal");db.insert("checklist_item",item,map("task_id",taskId));}});
        for(var original:rows("issues")){var issue=new LinkedHashMap<>(original);issue.put("itemId",issue.get("taskId")+"-"+issue.get("itemId"));issue.put("type","设施运行异常");issue.put("createdAt",now());issue.put("updatedAt",now());issue.put("photoIds",List.of());issue.put("riskEventIds",List.of("event-03"));issue.put("requirements","补齐缺失组件，恢复正常使用并提交现场照片");issue.put("rectificationDue",OffsetDateTime.now().plusDays(3).toString());db.insert("issue_record",issue,map("task_id",issue.get("taskId"),"item_id",issue.get("itemId"),"status",issue.get("status")));}
        for(var original:rows("events")){var event=new LinkedHashMap<>(original);event.put("time",now());event.put("updatedAt",now());event.put("source",event.get("id").equals("event-02")?"视频演示线索":"演示数据");event.put("issueId",event.get("id").equals("event-03")?"issue-01":null);db.insert("risk_event",event,map("unit_id",event.get("unitId"),"task_id",event.get("taskId"),"issue_id",event.get("issueId"),"state",event.get("state")));}
        var reviewer=auth.accountById("user-reviewer");var task=db.one("inspect_task","XF-260829-17");var review=map("id",id("review"),"taskId",task.get("id"),"issueId","issue-01","decision","confirm","opinion","现场问题已确认，进入整改复查","actorId",reviewer.get("id"),"actorName",reviewer.get("name"),"createdAt",now());db.insert("review_record",review,map("task_id",task.get("id"),"issue_id","issue-01","actor_id",reviewer.get("id")));
        db.log(auth.accountById("user-admin"),null,"初始化演示数据",map("units",4,"tasks",4));
    }
}
