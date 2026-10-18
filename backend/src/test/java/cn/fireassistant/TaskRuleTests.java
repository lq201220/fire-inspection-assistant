package cn.fireassistant;

import com.fasterxml.jackson.databind.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class TaskRuleTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired Store db;
    @Value("${app.demo-password}") String password;
    String token;
    JsonNode request(String method,String path,Map<String,Object> body,int status)throws Exception {
        var req=switch(method){case "DELETE"->delete(path);case "PATCH"->patch(path);case "PUT"->put(path);default->post(path);};
        if(token!=null)req.header("Authorization","Bearer "+token);
        var result=mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(status));
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
    Map<String,Object> taskBody()throws Exception {
        token=request("POST","/api/auth/login",Map.of("username","admin","password",password),200).at("/data/token").asText();
        String unit=request("POST","/api/units",Map.of("name","回滚规则测试","type","学校","address","测试地址","people","100人","buildings","1栋","risk","高风险关注","active",true),200).at("/data/id").asText();
        return new HashMap<>(Map.of("unitId",unit,"source","日常抽查","due",OffsetDateTime.now().plusDays(1).toString(),"inspectorId","user-inspector","reviewerId","user-reviewer","recheckerId","user-rechecker"));
    }
    @Test void rejectPastDeadlineOnCreateAndEdit()throws Exception {
        var body=taskBody();body.put("due",OffsetDateTime.now().minusDays(1).toString());
        assertTrue(request("POST","/api/tasks",body,400).get("message").asText().contains("检查期限"));
        body.put("due",OffsetDateTime.now().plusDays(1).toString());
        var task=request("POST","/api/tasks",body,200).get("data");
        request("PATCH","/api/tasks/"+task.get("id").asText(),Map.of("version",0,"due",OffsetDateTime.now().minusMinutes(1).toString()),400);
        var detail=mvc.perform(get("/api/tasks/"+task.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
        assertEquals(task.get("due"),json.readTree(detail.getResponse().getContentAsString()).at("/data/due"));
        request("PATCH","/api/tasks/"+task.get("id").asText(),Map.of("version",0,"due",OffsetDateTime.now().plusDays(2).toString()),200);
    }
    @Test void inheritUnitRiskAndPreserveTaskSnapshot()throws Exception {
        var body=taskBody();var task=request("POST","/api/tasks",body,200).get("data");
        assertEquals("高风险关注",task.get("risk").asText());
        request("PATCH","/api/units/"+body.get("unitId"),Map.of("risk","低风险"),200);
        mvc.perform(get("/api/tasks/"+task.get("id").asText()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.risk").value("高风险关注"));
    }
    @Test void allowExplicitTaskRiskOverride()throws Exception {
        var body=taskBody();body.put("risk","中风险关注");
        assertEquals("中风险关注",request("POST","/api/tasks",body,200).at("/data/risk").asText());
    }
    String login(String username)throws Exception {token=null;token=request("POST","/api/auth/login",Map.of("username",username,"password",password),200).at("/data/token").asText();return token;}
    JsonNode read(String path,int status)throws Exception {var result=mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().is(status));return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");}
    void stage(String id,String status){var task=db.one("inspect_task",id);task.put("status",status);db.saveTask(task);}
    @Test void downstreamVisibilityAndActionableCountsFollowStage()throws Exception {
        var body=taskBody();body.put("source","设施异常触发");String unitId=body.get("unitId").toString();List<String> ids=new ArrayList<>();
        for(int i=0;i<10;i++)ids.add(request("POST","/api/tasks",body,200).at("/data/id").asText());
        String id=ids.get(0);for(String upstream:ids)stage(upstream,"进行中");stage(id,"待审核");
        db.jdbc.update("INSERT INTO file_attachment(id,task_id,uploader_id,original_name,stored_name,content_type,byte_size,purpose,bound_id) VALUES(?,?,?,?,?,?,?,?,?)","visibility-test-file",ids.get(1),"user-inspector","test.png","visibility-test-file.png","image/png",1,"issue","visibility-test-issue");
        login("zhangsan");var state=read("/api/state",200);assertEquals(1,state.get("tasks").findValuesAsText("unitId").stream().filter(unitId::equals).count());
        assertEquals(1,state.get("events").findValuesAsText("unitId").stream().filter(unitId::equals).count());
        read("/api/tasks/"+ids.get(1),404);read("/api/tasks/"+ids.get(1)+"/history",404);
        mvc.perform(get("/api/files/photos/visibility-test-file").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        assertTrue(read("/api/dashboard",200).get("priorityTasks").findValuesAsText("id").contains(id));
        assertEquals(1,read("/api/units/"+unitId,200).get("tasks").size());
        login("lisi");assertEquals(0,read("/api/units/"+unitId,200).get("tasks").size());read("/api/tasks/"+id,404);
        login("tianbo");assertEquals(10,read("/api/units/"+unitId,200).get("tasks").size());
        assertFalse(read("/api/dashboard",200).get("priorityTasks").findValuesAsText("id").contains(id));
        stage(id,"进行中");login("zhangsan");read("/api/tasks/"+id,404);
        stage(id,"待审核");assertEquals(id,read("/api/tasks/"+id,200).get("id").asText());
        stage(id,"整改中");login("lisi");assertEquals(id,read("/api/tasks/"+id,200).get("id").asText());
        assertTrue(read("/api/dashboard",200).get("priorityTasks").findValuesAsText("id").contains(id));
        login("zhangsan");assertFalse(read("/api/dashboard",200).get("priorityTasks").findValuesAsText("id").contains(id));
        stage(id,"已完成");login("lisi");assertEquals(id,read("/api/tasks/"+id,200).get("id").asText());
        assertFalse(read("/api/dashboard",200).get("priorityTasks").findValuesAsText("id").contains(id));
        login("admin");assertEquals(10,read("/api/units/"+unitId,200).get("tasks").size());
    }
    @Test void selectedAndCustomItemsDriveInspectionSubmission()throws Exception {
        var body=taskBody();String unitId=body.get("unitId").toString();
        var options=read("/api/checklist-options?unitId="+unitId+"&source=日常抽查",200);
        body.put("checklistIds",List.of(options.get(0).get("id").asText(),options.get(1).get("id").asText()));
        body.put("customItems",List.of(Map.of("title","配电室器材","tip","核对灭火器数量和完好情况")));
        String id=request("POST","/api/tasks",body,200).at("/data/id").asText();
        var detail=read("/api/tasks/"+id,200);assertEquals(3,detail.get("checklist").size());
        assertTrue(detail.get("checklist").findValuesAsText("title").contains("配电室器材"));
        login("tianbo");request("POST","/api/tasks/"+id+"/start",Map.of("version",0),200);
        request("PUT","/api/tasks/"+id+"/site-check",Map.of("version",1,"type","学校","height",12,"floors",3,"people",100),200);
        request("POST","/api/tasks/"+id+"/submit-review",Map.of("version",2),400);
        int version=2;for(var item:detail.get("checklist"))request("PUT","/api/tasks/"+id+"/checklist/"+item.get("id").asText(),Map.of("version",version++,"status","normal","note","现场已核对"),200);
        assertEquals(100,read("/api/tasks/"+id,200).get("progress").asInt());
        request("POST","/api/tasks/"+id+"/submit-review",Map.of("version",version),200);
        login("zhangsan");assertEquals(3,read("/api/tasks/"+id,200).get("checklist").size());
    }
    @Test void validateSelectedItemsAndCustomOnlyChecklist()throws Exception {
        var body=taskBody();body.put("checklistIds",List.of());request("POST","/api/tasks",body,400);
        body.put("checklistIds",List.of("not-an-applicable-item"));request("POST","/api/tasks",body,400);
        body.put("checklistIds",List.of());body.put("customItems",List.of(Map.of("title","自定义检查","tip","")));request("POST","/api/tasks",body,400);
        body.put("customItems",List.of(Map.of("title","自定义检查","tip","检查具体要求")));
        String id=request("POST","/api/tasks",body,200).at("/data/id").asText();assertEquals(1,read("/api/tasks/"+id,200).get("checklist").size());
        login("tianbo");read("/api/checklist-options?unitId="+body.get("unitId")+"&source=日常抽查",403);
    }
    @Test void savedCustomItemsPersistAndCanBeSelectedLater()throws Exception {
        var body=taskBody();var item=Map.<String,Object>of("unitId",body.get("unitId"),"title","回滚测试独立保存","tip","核对器材数量和有效期");
        var saved=request("POST","/api/checklist-items",item,200).get("data");String savedId=saved.get("id").asText();
        assertEquals(savedId,request("POST","/api/checklist-items",item,200).at("/data/id").asText());
        assertTrue(read("/api/checklist-options?unitId="+body.get("unitId")+"&source=日常抽查",200).findValuesAsText("id").contains(savedId));
        request("POST","/api/checklist-items",Map.of("unitId",body.get("unitId"),"title",item.get("title"),"tip","不同要求"),409);
        body.put("checklistIds",List.of(savedId));String task=request("POST","/api/tasks",body,200).at("/data/id").asText();
        var checklist=read("/api/tasks/"+task,200).get("checklist");assertEquals(1,checklist.size());assertEquals(item.get("title"),checklist.get(0).get("title").asText());
        login("tianbo");request("POST","/api/checklist-items",item,403);
    }
    @Test void deletingSavedItemPreservesDispatchedChecklistAndScope()throws Exception {
        var body=taskBody();String item=request("POST","/api/checklist-items",Map.of("unitId",body.get("unitId"),"title","可删除验收项目","tip","项目检查要求"),200).at("/data/id").asText();
        body.put("checklistIds",List.of(item));String task=request("POST","/api/tasks",body,200).at("/data/id").asText();
        login("tianbo");request("DELETE","/api/checklist-items/"+item,Map.of(),403);
        login("admin");db.jdbc.update("INSERT INTO saved_checklist_item(id,org_id,unit_type,title,tip) VALUES(?,?,?,?,?)","foreign-saved-item","其他机构","学校","其他机构项目","要求");
        request("DELETE","/api/checklist-items/foreign-saved-item",Map.of(),404);
        request("DELETE","/api/checklist-items/"+item,Map.of(),200);
        assertFalse(read("/api/checklist-options?unitId="+body.get("unitId")+"&source=日常抽查",200).findValuesAsText("id").contains(item));
        assertEquals("可删除验收项目",read("/api/tasks/"+task,200).at("/checklist/0/title").asText());
        request("POST","/api/tasks",body,400);request("DELETE","/api/checklist-items/"+item,Map.of(),404);
    }
    @Test void deletingDefaultItemPersistsWithinOrganizationAndType()throws Exception {
        var body=taskBody();String unit=body.get("unitId").toString(),optionsPath="/api/checklist-options?unitId="+unit+"&source=日常抽查";
        var initial=read(optionsPath,200);String item=initial.get(0).get("id").asText(),title=initial.get(0).get("title").asText();
        body.put("checklistIds",List.of(item));String task=request("POST","/api/tasks",body,200).at("/data/id").asText();
        String path="/api/checklist-items/"+item+"?unitId="+unit+"&source=日常抽查";
        login("tianbo");request("DELETE",path,Map.of(),403);login("admin");
        db.jdbc.update("INSERT INTO removed_checklist_template(org_id,unit_type,template_id) VALUES(?,?,?)","其他机构","学校",item);
        assertTrue(read(optionsPath,200).findValuesAsText("id").contains(item));
        request("DELETE",path,Map.of(),200);assertFalse(read(optionsPath,200).findValuesAsText("id").contains(item));
        assertFalse(read(optionsPath.replace("日常抽查","专项检查"),200).findValuesAsText("id").contains(item));
        assertEquals(title,read("/api/tasks/"+task,200).at("/checklist/0/title").asText());
        request("POST","/api/tasks",body,400);request("DELETE",path,Map.of(),404);
        body.remove("checklistIds");String next=request("POST","/api/tasks",body,200).at("/data/id").asText();
        int defaults=0;for(var option:initial)if(!option.path("custom").asBoolean())defaults++;
        assertEquals(defaults-1,read("/api/tasks/"+next,200).get("checklist").size());
        var other=Map.of("id",unit+"-other","name","其他类型","type","住宅小区","orgId",db.one("supervised_unit",unit).get("orgId"),"active",true);
        db.insert("supervised_unit",other,Map.of("org_id",db.one("supervised_unit",unit).get("orgId")));
        assertTrue(read("/api/checklist-options?unitId="+unit+"-other&source=日常抽查",200).size()>0);
    }
}
