package cn.fireassistant;

import com.fasterxml.jackson.databind.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class TaskTests {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Value("${app.demo-password}")String password;
    String token(String name)throws Exception{return result(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name,"password",password)))).andExpect(status().isOk())).get("token").asText();}
    JsonNode result(ResultActions request)throws Exception{return json.readTree(request.andReturn().getResponse().getContentAsString()).get("data");}
    JsonNode write(String method,String path,String token,Map<String,Object> body,int status)throws Exception{var request=switch(method){case "PUT"->put(path);case "PATCH"->patch(path);default->post(path);};return result(mvc.perform(request.header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(status)));}
    JsonNode create(String admin)throws Exception{return write("POST","/api/tasks",admin,Map.of("unitId","unit-01","source","日常抽查","risk","中风险关注","due",OffsetDateTime.now().plusDays(1).toString(),"focus",List.of("核对现场设施"),"inspectorId","user-inspector","reviewerId","user-reviewer","recheckerId","user-rechecker"),200);}
    JsonNode detail(String id,String token)throws Exception{return result(mvc.perform(get("/api/tasks/"+id).header("Authorization","Bearer "+token)).andExpect(status().isOk()));}
    @Test void phaseCCreateTemplateSnapshotAndDataScope()throws Exception{
        String admin=token("admin"),officer=token("tianbo");var created=create(admin);String id=created.get("id").asText();var task=detail(id,officer);assertEquals("待检查",task.get("status").asText());assertEquals(6,task.get("checklist").size());assertFalse(task.get("checklist").get(0).get("basis").asText().isBlank());assertEquals("snapshot-2026-v1",task.get("checklist").get(0).get("basisVersion").asText());
        jdbc.update("INSERT INTO sys_user(id,username,password_hash,name,role,org_id) SELECT 'other-inspector','other-inspector',password_hash,'其他检查员','INSPECTOR',org_id FROM sys_user WHERE id='user-inspector'");String other=token("other-inspector");mvc.perform(get("/api/tasks/"+id).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        write("POST","/api/tasks",officer,Map.of(),403);
    }
    @Test void phaseCFourSourcesHaveFrozenTypeTemplatesAndDistinctFocus()throws Exception{
        String admin=token("admin");String[] sources={"设施异常触发","日常抽查","历史隐患复查","专项检查"};int[] counts={6,4,2,2};var focus=new HashSet<String>();
        for(int index=0;index<sources.length;index++){
            var created=write("POST","/api/tasks",admin,Map.of("unitId","unit-0"+(index+1),"source",sources[index],"risk","低风险","due",OffsetDateTime.now().plusDays(1).toString(),"inspectorId","user-inspector","reviewerId","user-reviewer","recheckerId","user-rechecker"),200);String id=created.get("id").asText();var original=detail(id,admin);assertEquals(counts[index],original.get("checklist").size());focus.add(original.get("focus").toString());
            for(var item:original.get("checklist")){assertEquals(sources[index],item.get("source").asText());assertFalse(item.get("applicableScene").asText().isBlank());}
            write("PATCH","/api/units/unit-0"+(index+1),admin,Map.of("type","其他场所"),200);assertEquals(original.get("checklist"),detail(id,admin).get("checklist"));
        }
        assertEquals(4,focus.size());
    }
    @Test void phaseCVersionStateAndOnsitePersistence()throws Exception{
        String admin=token("admin"),officer=token("tianbo"),reviewer=token("zhangsan");String id=create(admin).get("id").asText();write("POST","/api/tasks/"+id+"/start",reviewer,Map.of("version",0),403);write("POST","/api/tasks/"+id+"/start",officer,Map.of("version",0),200);write("POST","/api/tasks/"+id+"/start",officer,Map.of("version",0),409);
        write("PUT","/api/tasks/"+id+"/site-check",officer,Map.of("version",1.5,"type","学校","height",18.2,"floors",4,"people",300),409);
        write("PUT","/api/tasks/"+id+"/site-check",officer,Map.of("version",1,"type","学校","height",18.2,"floors",4,"people",300),200);var task=detail(id,officer);assertEquals(18.2,task.at("/site/height").asDouble());assertEquals(4,task.at("/site/floors").asInt());assertEquals(2,task.get("version").asInt());
        write("PATCH","/api/tasks/"+id,admin,Map.of("version",2,"focus",List.of("违规编辑")),409);write("PUT","/api/tasks/"+id+"/site-check",officer,Map.of("version",2,"type","学校","height",-1,"floors",4,"people",300),400);
    }
    @Test void phaseCSubmitMustSaveSiteResolvePendingAndExplainNotApplicable()throws Exception{
        String officer=token("tianbo");String id=create(token("admin")).get("id").asText();write("POST","/api/tasks/"+id+"/start",officer,Map.of("version",0),200);write("POST","/api/tasks/"+id+"/submit-review",officer,Map.of("version",1),400);write("PUT","/api/tasks/"+id+"/site-check",officer,Map.of("version",1,"type","学校","height",20,"floors",4,"people",200),200);
        var task=detail(id,officer);String item=task.get("checklist").get(0).get("id").asText();write("PUT","/api/tasks/"+id+"/checklist/"+item,officer,Map.of("version",2,"status","na"),400);write("PUT","/api/tasks/"+id+"/checklist/"+item,officer,Map.of("version",2,"status","pending"),200);write("POST","/api/tasks/"+id+"/submit-review",officer,Map.of("version",3),400);
        int version=3;for(var checklist:task.get("checklist")){write("PUT","/api/tasks/"+id+"/checklist/"+checklist.get("id").asText(),officer,Map.of("version",version++,"status","normal"),200);}write("POST","/api/tasks/"+id+"/submit-review",officer,Map.of("version",version),200);assertEquals("待审核",detail(id,officer).get("status").asText());
    }
    @Test void phaseCAbnormalItemWithoutIssueCannotSubmit()throws Exception{
        String officer=token("tianbo");String id=create(token("admin")).get("id").asText();write("POST","/api/tasks/"+id+"/start",officer,Map.of("version",0),200);write("PUT","/api/tasks/"+id+"/site-check",officer,Map.of("version",1,"type","学校","height",20,"floors",4,"people",200),200);var task=detail(id,officer);int version=2,index=0;for(var item:task.get("checklist"))write("PUT","/api/tasks/"+id+"/checklist/"+item.get("id").asText(),officer,Map.of("version",version++,"status",index++==0?"abnormal":"normal"),200);write("POST","/api/tasks/"+id+"/submit-review",officer,Map.of("version",version),400);assertEquals("进行中",detail(id,officer).get("status").asText());
    }
}
