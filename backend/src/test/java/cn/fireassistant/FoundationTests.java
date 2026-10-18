package cn.fireassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FoundationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Value("${app.demo-password}")String password;
    String token(String username)throws Exception{return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/token").asText();}
    @Test void phaseAHealthAndRelationalSchema()throws Exception{
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pong")).andExpect(jsonPath("$.data.database").value("connected"));
        var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()",String.class);
        assertTrue(tables.containsAll(List.of("sys_user","auth_session","supervised_unit","inspect_task","checklist_item","issue_record","risk_event","file_attachment","review_record","rectification_record","recheck_record","operate_log")));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE",Integer.class)>=1);
    }
    @Test void phaseBLoginFailureAndFourRoles()throws Exception{
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"tianbo\",\"password\":\"wrongpass\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        for(var pair:Map.of("admin","ADMIN","tianbo","INSPECTOR","zhangsan","REVIEWER","lisi","RECHECKER").entrySet())mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token(pair.getKey()))).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(pair.getValue()));
    }
    @Test void phaseBLogoutInvalidatesServerSession()throws Exception{
        String token=token("tianbo");mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+token)).andExpect(status().isOk());mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void phaseBUnitMaintenanceAndPermissions()throws Exception{
        String officer=token("tianbo"),admin=token("admin");String body=json.writeValueAsString(Map.of("name","测试新增单位","type","学校","address","测试路1号","people","100人","buildings","教学楼1栋","risk","中风险关注"));
        mvc.perform(post("/api/units").header("Authorization","Bearer "+officer).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var created=mvc.perform(post("/api/units").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();String id=json.readTree(created.getResponse().getContentAsString()).at("/data/id").asText();
        mvc.perform(patch("/api/units/"+id).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"修改后单位\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("修改后单位"));
        mvc.perform(delete("/api/units/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM supervised_unit WHERE id=?",Integer.class,id));
    }
    @Test void phaseBAccountDisableRevokesOldTokenAndSelfProtection()throws Exception{
        String admin=token("admin");var result=mvc.perform(post("/api/users").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username","temporary-user","password",password,"name","测试用户","role","INSPECTOR")))).andExpect(status().isOk()).andReturn();String id=json.readTree(result.getResponse().getContentAsString()).at("/data/id").asText();String temporary=token("temporary-user");
        mvc.perform(patch("/api/users/"+id).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+temporary)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/users/user-admin").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users").header("Authorization","Bearer "+token("tianbo"))).andExpect(status().isForbidden());
    }
    @Test void phaseBCreateUserHonorsInitialState()throws Exception{
        String admin=token("admin");
        for(boolean enabled:List.of(false,true)){
            String username="initial-state-"+enabled;
            var created=mvc.perform(post("/api/users").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password,"name","初始状态测试","role","INSPECTOR","enabled",enabled)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(enabled)).andReturn();
            String id=json.readTree(created.getResponse().getContentAsString()).at("/data/id").asText();
            assertEquals(enabled,jdbc.queryForObject("SELECT enabled FROM sys_user WHERE id=?",Boolean.class,id));
            if(!enabled){
                mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password",password)))).andExpect(status().isUnauthorized());
                mvc.perform(patch("/api/users/"+id).header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
            }
            mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token(username))).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(username));
        }
    }
    @Test void phaseBExpiredDatabaseSessionIsRejected()throws Exception{
        String inspector=token("tianbo");jdbc.update("UPDATE auth_session SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE user_id='user-inspector'");
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+inspector)).andExpect(status().isUnauthorized());
    }
    @Test void phaseBRegulatoryOrganizationBoundary()throws Exception{
        String admin=token("admin");jdbc.update("INSERT INTO sys_user(id,username,password_hash,name,role,org_id) SELECT 'foreign-admin','foreign-admin',password_hash,'外部管理员','ADMIN','其他大队' FROM sys_user WHERE id='user-admin'");String foreign=token("foreign-admin");
        mvc.perform(patch("/api/units/unit-01").header("Authorization","Bearer "+foreign).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"越权修改\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/units").header("Authorization","Bearer "+foreign)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(patch("/api/users/foreign-admin").header("Authorization","Bearer "+admin).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isNotFound());
    }
}
