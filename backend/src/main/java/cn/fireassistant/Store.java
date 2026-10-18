package cn.fireassistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Store {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    static final Set<String> TABLES=Set.of("supervised_unit","inspect_task","checklist_item","issue_record","risk_event","review_record","rectification_record","recheck_record","operate_log");
    Store(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    static String id(String prefix){return prefix+"-"+UUID.randomUUID().toString().replace("-","");}
    static String now(){return OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();}
    static Map<String,Object> map(Object... pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    Map<String,Object> decode(String value){try{return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception ex){throw new IllegalStateException("Invalid stored document",ex);}}
    Map<String,Object> one(String table,String id){checkTable(table);var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE id=?",id);if(rows.isEmpty())throw Api.error(404,"记录不存在");return unpack(rows.get(0));}
    Map<String,Object> taskLock(String id){var rows=jdbc.queryForList("SELECT * FROM inspect_task WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw Api.error(404,"任务不存在");return unpack(rows.get(0));}
    List<Map<String,Object>> list(String table,String where,Object... args){checkTable(table);return jdbc.queryForList("SELECT * FROM "+table+" "+where,args).stream().map(this::unpack).toList();}
    private Map<String,Object> unpack(Map<String,Object> row){var result=decode(row.get("data").toString());result.put("id",row.get("id"));if(row.containsKey("version"))result.put("version",((Number)row.get("version")).intValue());return result;}
    void insert(String table,Map<String,Object> data,Map<String,Object> columns){checkTable(table);var values=new LinkedHashMap<>(columns);values.put("id",data.get("id"));values.put("data",encode(data));jdbc.update("INSERT INTO "+table+" ("+String.join(",",values.keySet())+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.values().toArray());}
    void save(String table,Map<String,Object> data,Map<String,Object> columns){checkTable(table);var values=new LinkedHashMap<>(columns);values.put("data",encode(data));var assignments=values.keySet().stream().map(key->key+"=?").toList();var args=new ArrayList<>(values.values());args.add(data.get("id"));if(jdbc.update("UPDATE "+table+" SET "+String.join(",",assignments)+" WHERE id=?",args.toArray())!=1)throw Api.error(404,"记录不存在");}
    void saveTask(Map<String,Object> task){task.put("version",((Number)task.get("version")).intValue()+1);task.put("updatedAt",now());save("inspect_task",task,map("status",task.get("status"),"version",task.get("version"),"inspector_id",task.get("inspectorId"),"reviewer_id",task.get("reviewerId"),"rechecker_id",task.get("recheckerId")));}
    void log(Map<String,Object> actor,String taskId,String action,Object detail){var record=map("id",id("log"),"taskId",taskId,"actorId",actor.get("id"),"actorName",actor.get("name"),"action",action,"detail",detail,"createdAt",now());insert("operate_log",record,map("org_id",actor.get("orgId"),"task_id",taskId,"actor_id",actor.get("id"),"action",action));}
    private void checkTable(String table){if(!TABLES.contains(table))throw new IllegalArgumentException("Unsupported domain table");}
    static String label(String key){return switch(key){
        case "username"->"登录账号";case "password"->"密码";case "name"->"名称";case "role"->"人员角色";
        case "type"->"类型";case "address"->"地址";case "people"->"人数";case "buildings"->"建筑与分区";
        case "risk"->"风险等级";case "unitId"->"监管单位";case "source"->"任务来源";case "due"->"检查期限";
        case "inspectorId"->"检查人员";case "reviewerId"->"审核人员";case "recheckerId"->"复查人员";
        case "title"->"项目名称";case "tip"->"检查要求";case "height"->"建筑高度";case "floors"->"建筑层数";
        case "status"->"检查结论";case "note"->"核验记录";case "location"->"所在位置";case "finding"->"现场事实";
        case "decision"->"审核结论";case "opinion"->"审核意见";case "requirements"->"整改要求";
        case "responsibleContact"->"责任联系人";case "rectificationDue"->"整改期限";case "provider"->"材料提供人";
        case "description"->"整改情况说明";case "conclusion"->"复查结论";case "focus"->"补充说明";
        case "itemId"->"检查项目";case "riskEventIds"->"关联线索";case "excludedRiskIds"->"核验排除线索";
        case "photoIds","fileIds"->"附件";default->"填写内容";
    };}
    static String text(Map<String,Object> body,String key,int max){Object value=body.get(key);if(!(value instanceof String))throw Api.error(400,"请填写"+label(key));String result=value.toString().trim();if(result.isEmpty())throw Api.error(400,"请填写"+label(key));if(result.length()>max)throw Api.error(400,label(key)+"不能超过"+max+"个字符");return result;}
    static String optional(Map<String,Object> body,String key,int max){Object value=body.get(key);if(value==null)return "";if(!(value instanceof String))throw Api.error(400,label(key)+"无效，请重新填写");if(value.toString().length()>max)throw Api.error(400,label(key)+"不能超过"+max+"个字符");return value.toString().trim();}
    static List<String> strings(Map<String,Object> body,String key){Object value=body.getOrDefault(key,List.of());if(!(value instanceof List<?> values))throw Api.error(400,label(key)+"无效，请重新选择");if(values.size()>10)throw Api.error(400,label(key)+"最多十项");return values.stream().map(item->{if(!(item instanceof String)||item.toString().length()>120)throw Api.error(400,label(key)+"有无效内容，请重新填写或选择");return item.toString();}).distinct().toList();}
    static void version(Map<String,Object> task,Map<String,Object> body){if(!(body.get("version") instanceof Number v)||v.doubleValue()!=((Number)task.get("version")).doubleValue())throw Api.error(409,"任务已被更新，请核对最新内容后重试");}
}
