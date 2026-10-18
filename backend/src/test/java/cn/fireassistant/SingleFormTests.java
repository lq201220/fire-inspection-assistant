package cn.fireassistant;

import com.fasterxml.jackson.databind.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc
class SingleFormTests {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Value("${app.demo-password}")String password;
 final List<String> created=new ArrayList<>();
 JsonNode call(String method,String path,String token,Map<String,Object> body,int code)throws Exception{var req=method.equals("GET")?get(path):method.equals("PUT")?put(path):post(path);if(token!=null)req.header("Authorization","Bearer "+token);return json.readTree(mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(code)).andReturn().getResponse().getContentAsString());}
 String token(String user)throws Exception{return call("POST","/api/auth/login",null,Map.of("username",user,"password",password),200).at("/data/token").asText();}
 JsonNode detail(String id,String token)throws Exception{return call("GET","/api/tasks/"+id,token,Map.of(),200).get("data");}
 JsonNode action(String path,String id,String token,Map<String,Object> fields,int code)throws Exception{var body=new LinkedHashMap<>(fields);body.put("version",detail(id,token).get("version").asInt());return call("POST",path,token,body,code);}
 String create()throws Exception{String id=call("POST","/api/tasks",token("admin"),Map.of("unitId","unit-01","source","日常抽查","due",OffsetDateTime.now().plusDays(2).toString(),"inspectorId","user-inspector","reviewerId","user-reviewer","recheckerId","user-rechecker"),200).at("/data/id").asText();created.add(id);return id;}
 void start(String id,String officer)throws Exception{action("/api/tasks/"+id+"/start",id,officer,Map.of(),200);}
 void normal(String id,String officer,String except)throws Exception{for(var item:detail(id,officer).get("checklist")){String itemId=item.get("id").asText();if(itemId.equals(except))continue;call("PUT","/api/tasks/"+id+"/checklist/"+itemId,officer,Map.of("version",detail(id,officer).get("version").asInt(),"status","normal"),200);}}
 Map<String,Object> issueBody(String item){return Map.of("itemId",item,"recordAbnormal",true,"type","疏散条件异常","location","教学楼一层东侧","finding","疏散通道堆放纸箱，需要清理","photoIds",List.of(),"riskEventIds",List.of());}
 Map<String,Object> review(String issue,String decision){return Map.of("issueId",issue,"decision",decision,"opinion","现场事实已核对","requirements","清理通道并保持畅通","responsibleContact","王老师","rectificationDue",OffsetDateTime.now().plusDays(3).toString());}
 @AfterEach void cleanup(){for(String id:created){for(String table:List.of("recheck_record","rectification_record","review_record","risk_event","issue_record","checklist_item","operate_log"))jdbc.update("DELETE FROM "+table+" WHERE task_id=?",id);jdbc.update("DELETE FROM inspect_task WHERE id=?",id);}}
 @Test void normalInspectionSubmitsWithoutSiteAndMissingListContainsAllItems()throws Exception{
  String officer=token("tianbo"),id=create();start(id,officer);var initial=detail(id,officer);var missing=action("/api/tasks/"+id+"/submit-review",id,officer,Map.of(),400).get("message").asText();for(var item:initial.get("checklist"))assertTrue(missing.contains(item.get("title").asText()));assertFalse(missing.contains("场所"));normal(id,officer,"");action("/api/tasks/"+id+"/submit-review",id,officer,Map.of(),200);assertTrue(detail(id,officer).get("site").isNull());assertEquals("待审核",detail(id,officer).get("status").asText());
 }
 @Test void abnormalAndIssueAreAtomicAndBatchReviewRollsBackThenCommits()throws Exception{
  String officer=token("tianbo"),reviewer=token("zhangsan"),id=create();start(id,officer);var items=detail(id,officer).get("checklist");String first=items.get(0).get("id").asText(),second=items.get(1).get("id").asText();int before=detail(id,officer).get("version").asInt();var invalid=new LinkedHashMap<>(issueBody(first));invalid.put("location","");action("/api/tasks/"+id+"/issues",id,officer,invalid,400);assertEquals(before,detail(id,officer).get("version").asInt());assertTrue(detail(id,officer).get("issues").isEmpty());assertTrue(detail(id,officer).get("checklist").get(0).get("status").isNull());
  String one=action("/api/tasks/"+id+"/issues",id,officer,issueBody(first),200).at("/data/id").asText(),two=action("/api/tasks/"+id+"/issues",id,officer,issueBody(second),200).at("/data/id").asText();for(var item:detail(id,officer).get("checklist")){String itemId=item.get("id").asText();if(itemId.equals(first)||itemId.equals(second))continue;call("PUT","/api/tasks/"+id+"/checklist/"+itemId,officer,Map.of("version",detail(id,officer).get("version").asInt(),"status","normal"),200);}
  action("/api/tasks/"+id+"/submit-review",id,officer,Map.of(),200);int original=detail(id,reviewer).get("version").asInt();var bad=new LinkedHashMap<>(review(two,"confirm"));bad.put("requirements","");action("/api/tasks/"+id+"/review",id,reviewer,Map.of("opinion","逐项审核","issueReviews",List.of(review(one,"confirm"),bad)),400);assertEquals(original,detail(id,reviewer).get("version").asInt());assertTrue(detail(id,reviewer).at("/history/reviews").isEmpty());for(var record:detail(id,reviewer).get("issues"))assertEquals("待审核",record.get("status").asText());
  action("/api/tasks/"+id+"/review",id,reviewer,Map.of("opinion","补充第二项事实后重审","issueReviews",List.of(review(one,"confirm"),review(two,"return"))),200);assertEquals("进行中",detail(id,officer).get("status").asText());assertEquals(2,detail(id,officer).at("/history/reviews").size());
 }
 @Test void batchReviewAndCombinedRecheckPreserveTransactionsAndHistory()throws Exception{
  String officer=token("tianbo"),reviewer=token("zhangsan"),rechecker=token("lisi"),id=create();start(id,officer);String item=detail(id,officer).get("checklist").get(0).get("id").asText();String issue=action("/api/tasks/"+id+"/issues",id,officer,issueBody(item),200).at("/data/id").asText();normal(id,officer,item);action("/api/tasks/"+id+"/submit-review",id,officer,Map.of(),200);
  var bad=new LinkedHashMap<>(review(issue,"confirm"));bad.put("responsibleContact","");int before=detail(id,reviewer).get("version").asInt();action("/api/tasks/"+id+"/review",id,reviewer,Map.of("opinion","确认问题并转整改","issueReviews",List.of(bad)),400);assertEquals(before,detail(id,reviewer).get("version").asInt());assertEquals("待审核",detail(id,reviewer).get("issues").get(0).get("status").asText());
  action("/api/tasks/"+id+"/review",id,reviewer,Map.of("opinion","确认问题并转整改","issueReviews",List.of(review(issue,"confirm"))),200);assertEquals("整改中",detail(id,reviewer).get("status").asText());
  var material=Map.of("provider","王老师","description","纸箱已清理，保持通道畅通","fileIds",List.of());int current=detail(id,rechecker).get("version").asInt();action("/api/issues/"+issue+"/recheck",id,rechecker,Map.of("conclusion","pass","finding","","material",material,"fileIds",List.of()),400);assertEquals(current,detail(id,rechecker).get("version").asInt());assertTrue(detail(id,rechecker).at("/history/rectifications").isEmpty());
  action("/api/issues/"+issue+"/recheck",id,rechecker,Map.of("conclusion","fail","finding","出口仍有少量堆物","material",material,"fileIds",List.of()),200);action("/api/issues/"+issue+"/recheck",id,rechecker,Map.of("conclusion","pass","finding","出口现已畅通","fileIds",List.of()),400);action("/api/issues/"+issue+"/recheck",id,rechecker,Map.of("conclusion","pass","finding","出口现已畅通","material",material,"fileIds",List.of()),200);var done=detail(id,rechecker);assertEquals("已完成",done.get("status").asText());assertEquals(2,done.at("/history/rectifications").size());assertEquals(2,done.at("/history/rechecks").size());
 }
}
