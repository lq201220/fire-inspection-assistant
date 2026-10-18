package cn.fireassistant;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.fireassistant.Store.*;

@Service
public class WorkflowService {
    private final Store db;private final AuthService auth;private final TaskService tasks;private final FileService files;
    WorkflowService(Store db,AuthService auth,TaskService tasks,FileService files){this.db=db;this.auth=auth;this.tasks=tasks;this.files=files;}
    public Map<String,Object> issue(String id){var issue=db.one("issue_record",id);tasks.task(issue.get("taskId").toString());var result=new LinkedHashMap<>(issue);result.put("history",tasks.history(issue.get("taskId").toString()));return result;}
    @Transactional public Map<String,Object> saveIssue(String taskId,String id,Map<String,Object> body){
        var task=tasks.lock(taskId,body,"INSPECTOR");TaskService.status(task,"进行中");var issue=id==null?map("id",Store.id("issue"),"taskId",taskId,"unitId",task.get("unitId"),"createdAt",now(),"confirmed",false,"status","待审核"):db.one("issue_record",id);
        if(!issue.get("taskId").equals(taskId))throw Api.error(404,"问题不存在");if(!Set.of("待审核","已退回").contains(issue.get("status")))throw Api.error(409,"已确认问题不能覆盖原始事实");var previous=id==null?null:new LinkedHashMap<>(issue);
        String itemId=id==null?text(body,"itemId",120):issue.get("itemId").toString();var item=db.one("checklist_item",itemId);if(item.get("taskId").equals(taskId)&&Boolean.TRUE.equals(body.get("recordAbnormal"))&&!Objects.equals(item.get("status"),"abnormal")){tasks.checklist(taskId,itemId,map("version",task.get("version"),"status","abnormal","note",text(body,"finding",3000).substring(0,Math.min(text(body,"finding",3000).length(),1000))));task=tasks.task(taskId);item=db.one("checklist_item",itemId);}if(!item.get("taskId").equals(taskId)||!Objects.equals(item.get("status"),"abnormal"))throw Api.error(400,"问题必须关联本任务的异常项目");String type=text(body,"type",80);if(!Set.of("设施运行异常","管理履职异常","疏散条件异常","其他待核验事项").contains(type))throw Api.error(400,"无效问题类型");
        var photos=strings(body,"photoIds");var riskIds=strings(body,"riskEventIds");for(var riskId:riskIds){var risk=db.one("risk_event",riskId);if(!risk.get("taskId").equals(taskId)||(risk.get("issueId")!=null&&!risk.get("issueId").equals(issue.get("id"))))throw Api.error(400,"风险线索不存在或已关联其他问题");}
        issue.putAll(map("itemId",itemId,"type",type,"title",item.get("title")+" · "+type,"location",text(body,"location",200),"finding",text(body,"finding",3000),"photoIds",photos,"riskEventIds",riskIds,"updatedAt",now(),"createdBy",auth.current().get("name")));
        if(id==null)db.insert("issue_record",issue,map("task_id",taskId,"item_id",itemId,"status",issue.get("status")));else db.save("issue_record",issue,map("status",issue.get("status")));
        files.bind(photos,taskId,"issue",issue.get("id").toString());
        for(var risk:tasks.risks(taskId)){if(issue.get("id").equals(risk.get("issueId"))&&!riskIds.contains(risk.get("id"))){risk.put("issueId",null);saveRisk(risk);}}
        for(String riskId:riskIds){var risk=db.one("risk_event",riskId);risk.put("issueId",issue.get("id"));saveRisk(risk);}
        db.saveTask(task);db.log(auth.current(),taskId,id==null?"提交问题记录":"补充问题记录",map("issueId",issue.get("id"),"before",previous,"after",issue));return issue;
    }
    @Transactional public Map<String,Object> reviewIssue(String id,Map<String,Object> body){
        var issue=db.one("issue_record",id);String taskId=issue.get("taskId").toString();var task=tasks.lock(taskId,body,"REVIEWER");TaskService.status(task,"待审核");return applyReview(task,issue,body);
    }
    private Map<String,Object> applyReview(Map<String,Object> task,Map<String,Object> issue,Map<String,Object> body){
        String taskId=task.get("id").toString(),id=issue.get("id").toString();if(!issue.get("status").equals("待审核"))throw Api.error(409,"该问题不是待审核状态");String decision=text(body,"decision",20),opinion=text(body,"opinion",2000);if(!Set.of("confirm","return","exclude").contains(decision))throw Api.error(400,"无效审核结论");
        if(decision.equals("confirm")){issue.put("requirements",text(body,"requirements",2000));issue.put("responsibleContact",text(body,"responsibleContact",200));issue.put("rectificationDue",TaskService.date(text(body,"rectificationDue",80)));issue.put("confirmed",true);issue.put("status","整改中");var linked=tasks.risks(taskId).stream().filter(r->id.equals(r.get("issueId"))).toList();if(linked.isEmpty()){String riskId=Store.id("event"),risk=task.get("risk").toString();var event=map("id",riskId,"unitId",task.get("unitId"),"taskId",taskId,"issueId",id,"title",issue.get("title"),"source","现场核验","detail",opinion,"level",risk.contains("高")?"high":risk.contains("中")?"medium":"low","state","已确认","time",now(),"updatedAt",now());db.insert("risk_event",event,map("unit_id",task.get("unitId"),"task_id",taskId,"issue_id",id,"state","已确认"));issue.put("riskEventIds",List.of(riskId));}else for(var risk:linked){risk.put("state","已确认");risk.put("detail",opinion);saveRisk(risk);}}
        else if(decision.equals("return")){issue.put("status","已退回");task.put("status","进行中");}
        else{issue.put("status","已排除");issue.put("confirmed",false);for(var risk:tasks.risks(taskId))if(id.equals(risk.get("issueId"))){risk.put("state","已排除");risk.put("detail",opinion);saveRisk(risk);}}
        issue.put("reviewOpinion",opinion);issue.put("updatedAt",now());db.save("issue_record",issue,map("status",issue.get("status")));history("review_record",taskId,id,map("decision",decision,"opinion",opinion,"snapshot",issue,"requirements",issue.get("requirements"),"responsibleContact",issue.get("responsibleContact"),"rectificationDue",issue.get("rectificationDue")));
        db.saveTask(task);db.log(auth.current(),taskId,"人工审核问题",map("issueId",id,"decision",decision));return task;
    }
    @Transactional public Map<String,Object> finishReview(String taskId,Map<String,Object> body){
        var task=tasks.lock(taskId,body,"REVIEWER");TaskService.status(task,"待审核");String opinion=text(body,"opinion",2000);
        if(body.containsKey("issueReviews")){
            if(!(body.get("issueReviews") instanceof List<?> reviews)||reviews.size()>50)throw Api.error(400,"问题审核内容无效");
            var waiting=tasks.issues(taskId).stream().filter(i->i.get("status").equals("待审核")).map(i->i.get("id").toString()).collect(java.util.stream.Collectors.toSet());
            var submitted=new HashSet<String>();var decisions=new ArrayList<Map<String,Object>>();
            for(var raw:reviews){var decision=fields(raw);String id=text(decision,"issueId",120);if(!waiting.contains(id)||!submitted.add(id))throw Api.error(409,"待审核问题已改变，请核对最新内容");decisions.add(decision);}
            if(!submitted.equals(waiting))throw Api.error(400,"请填写全部问题的审核结论");
            for(var decision:decisions)applyReview(task,db.one("issue_record",decision.get("issueId").toString()),decision);
            if(task.get("status").equals("进行中")){db.log(auth.current(),taskId,"审核退回补充",map("opinion",opinion));return task;}
        }
        var issues=tasks.issues(taskId);if(issues.stream().anyMatch(i->Set.of("待审核","已退回").contains(i.get("status"))))throw Api.error(409,"请先逐项完成审核和补充材料");
        var excluded=strings(body,"excludedRiskIds");for(var id:excluded){var risk=db.one("risk_event",id);if(!risk.get("taskId").equals(taskId)||risk.get("issueId")!=null||!risk.get("state").equals("待核验"))throw Api.error(400,"只能排除本任务尚未关联问题的待核验线索");risk.put("state","已排除");risk.put("detail",opinion);saveRisk(risk);}
        if(tasks.risks(taskId).stream().anyMatch(r->r.get("state").equals("待核验")))throw Api.error(400,"仍有来源线索未核验，请关联问题或明确排除");boolean hasIssues=issues.stream().anyMatch(i->!i.get("status").equals("已排除"));task.put("status",hasIssues?"整改中":"已完成");task.put("reviewedAt",now());if(!hasIssues)task.put("closedAt",now());
        if(hasIssues)for(var risk:tasks.risks(taskId))if(risk.get("state").equals("已确认")){risk.put("state","整改中");saveRisk(risk);}
        history("review_record",taskId,null,map("decision",hasIssues?"rectify":"archive","opinion",opinion,"excludedRiskIds",excluded));db.saveTask(task);db.log(auth.current(),taskId,hasIssues?"审核完成进入整改":"无问题审核归档",map("opinion",opinion));return task;
    }
    @Transactional public Map<String,Object> rectify(String id,Map<String,Object> body){var issue=db.one("issue_record",id);String taskId=issue.get("taskId").toString();var task=tasks.lock(taskId,body,"INSPECTOR","RECHECKER");TaskService.status(task,"整改中");if(!issue.get("status").equals("整改中"))throw Api.error(409,"该问题无需继续整改");var record=map("description",text(body,"description",3000),"provider",text(body,"provider",200),"fileIds",strings(body,"fileIds"));String recordId=history("rectification_record",taskId,id,record);files.bind(strings(body,"fileIds"),taskId,"rectification",recordId);issue.put("materialSubmittedAt",now());db.save("issue_record",issue,Map.of());db.saveTask(task);db.log(auth.current(),taskId,"保存整改材料",map("issueId",id,"recordId",recordId));return task;}
    @Transactional public Map<String,Object> recheck(String id,Map<String,Object> body){
        var issue=db.one("issue_record",id);String taskId=issue.get("taskId").toString();var task=tasks.lock(taskId,body,"RECHECKER");TaskService.status(task,"整改中");if(!issue.get("status").equals("整改中"))throw Api.error(409,"该问题已关闭或未经确认");if(body.containsKey("material")){var material=fields(body.get("material"));material.put("version",task.get("version"));rectify(id,material);task=tasks.task(taskId);}String conclusion=text(body,"conclusion",20);if(!Set.of("pass","fail").contains(conclusion))throw Api.error(400,"复查结论必须合格或不合格");String finding=text(body,"finding",3000);
        var rectifications=db.list("rectification_record","WHERE issue_id=? ORDER BY created_at,id",id);if(rectifications.isEmpty())throw Api.error(400,"请先保存整改材料");var rechecks=db.list("recheck_record","WHERE issue_id=? ORDER BY created_at,id",id);if(!rechecks.isEmpty()){var last=rechecks.get(rechecks.size()-1);if(last.get("conclusion").equals("fail")&&last.get("materialId").equals(rectifications.get(rectifications.size()-1).get("id")))throw Api.error(400,"上次复查不合格，请先补充新的整改材料");}
        var record=map("conclusion",conclusion,"finding",finding,"fileIds",strings(body,"fileIds"),"round",rechecks.size()+1,"materialId",rectifications.get(rectifications.size()-1).get("id"));String recordId=history("recheck_record",taskId,id,record);files.bind(strings(body,"fileIds"),taskId,"recheck",recordId);
        if(conclusion.equals("pass")){issue.put("status","复查合格");issue.put("closedAt",now());for(var risk:tasks.risks(taskId))if(id.equals(risk.get("issueId"))){risk.put("state","复查合格");risk.put("detail",finding);saveRisk(risk);}}issue.put("updatedAt",now());db.save("issue_record",issue,map("status",issue.get("status")));aggregateClose(task);db.saveTask(task);db.log(auth.current(),taskId,conclusion.equals("pass")?"复查合格":"复查不合格",map("issueId",id,"round",rechecks.size()+1));return task;
    }
    @Transactional public Map<String,Object> close(String taskId,Map<String,Object> body){var task=tasks.task(taskId);if(task.get("status").equals("待审核"))return finishReview(taskId,body);task=tasks.lock(taskId,body,"RECHECKER");TaskService.status(task,"整改中");if(!aggregateClose(task))throw Api.error(409,"仍有问题或风险事项未闭环");db.saveTask(task);db.log(auth.current(),taskId,"闭环归档",Map.of());return task;}
    boolean aggregateClose(Map<String,Object> task){String id=task.get("id").toString();boolean complete=tasks.issues(id).stream().allMatch(i->Set.of("复查合格","已排除").contains(i.get("status")))&&tasks.risks(id).stream().noneMatch(tasks::activeRisk);if(complete){task.put("status","已完成");task.put("closedAt",now());}return complete;}
    private static Map<String,Object> fields(Object raw){if(!(raw instanceof Map<?,?> values))throw Api.error(400,"填写内容无效");var result=new LinkedHashMap<String,Object>();values.forEach((key,value)->result.put(String.valueOf(key),value));return result;}
    void saveRisk(Map<String,Object> risk){risk.put("updatedAt",now());db.save("risk_event",risk,map("issue_id",risk.get("issueId"),"state",risk.get("state")));}
    String history(String table,String taskId,String issueId,Map<String,Object> content){var record=new LinkedHashMap<>(content);String id=Store.id("record");record.putAll(map("id",id,"taskId",taskId,"issueId",issueId,"actorId",auth.current().get("id"),"actorName",auth.current().get("name"),"createdAt",now()));db.insert(table,record,map("task_id",taskId,"issue_id",issueId,"actor_id",auth.current().get("id")));return id;}
}
