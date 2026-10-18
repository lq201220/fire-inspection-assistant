package cn.fireassistant;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.fireassistant.Store.*;

@Service
public class AdminService {
    private final Store db;private final AuthService auth;
    static final Set<String> ROLES=Set.of("ADMIN","INSPECTOR","REVIEWER","RECHECKER");
    AdminService(Store db,AuthService auth){this.db=db;this.auth=auth;}
    public List<Map<String,Object>> users(){auth.require("ADMIN");return db.jdbc.queryForList("SELECT * FROM sys_user WHERE org_id=? ORDER BY created_at,id",auth.current().get("orgId")).stream().map(auth::account).toList();}
    @Transactional public Map<String,Object> createUser(Map<String,Object> body){
        auth.require("ADMIN");String role=text(body,"role",20);if(!ROLES.contains(role))throw Api.error(400,"无效角色");String password=text(body,"password",128);if(password.length()<6)throw Api.error(400,"密码至少六位");
        if(body.containsKey("enabled")&&!(body.get("enabled") instanceof Boolean))throw Api.error(400,"请选择有效的账号状态");boolean enabled=body.containsKey("enabled")?(Boolean)body.get("enabled"):true;
        String id=id("user");db.jdbc.update("INSERT INTO sys_user(id,username,password_hash,name,role,org_id,enabled) VALUES(?,?,?,?,?,?,?)",id,text(body,"username",80),auth.hashPassword(password),text(body,"name",80),role,auth.current().get("orgId"),enabled);db.log(auth.current(),null,"创建用户",map("userId",id));return auth.accountById(id);
    }
    @Transactional public Map<String,Object> updateUser(String id,Map<String,Object> body){
        auth.require("ADMIN");var user=auth.accountById(id);if(!user.get("orgId").equals(auth.current().get("orgId")))throw Api.error(404,"用户不存在");
        String name=body.containsKey("name")?text(body,"name",80):user.get("name").toString(),role=body.containsKey("role")?text(body,"role",20):user.get("role").toString();if(!ROLES.contains(role))throw Api.error(400,"无效角色");
        if(body.containsKey("enabled")&&!(body.get("enabled") instanceof Boolean))throw Api.error(400,"请选择有效的账号状态");boolean enabled=body.containsKey("enabled")?(Boolean)body.get("enabled"):Boolean.TRUE.equals(user.get("enabled"));
        if(id.equals(auth.current().get("id"))&&(!enabled||!role.equals("ADMIN")))throw Api.error(400,"不能停用或降级当前管理员");
        if(!role.equals(user.get("role"))&&db.jdbc.queryForObject("SELECT COUNT(*) FROM inspect_task WHERE status<>? AND (inspector_id=? OR reviewer_id=? OR rechecker_id=?)",Integer.class,"已完成",id,id,id)>0)throw Api.error(409,"该用户仍有未完成任务，不能改变角色");
        db.jdbc.update("UPDATE sys_user SET name=?,role=?,enabled=?,token_version=token_version+1 WHERE id=?",name,role,enabled,id);
        if(body.containsKey("password")){String password=text(body,"password",128);if(password.length()<6)throw Api.error(400,"密码至少六位");db.jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?",auth.hashPassword(password),id);}
        db.log(auth.current(),null,"更新用户",map("userId",id));return auth.accountById(id);
    }
    public List<Map<String,Object>> units(){return db.list("supervised_unit","WHERE org_id=? ORDER BY updated_at DESC,id",auth.current().get("orgId"));}
    public Map<String,Object> unit(String id){var unit=db.one("supervised_unit",id);if(!auth.current().get("orgId").equals(unit.get("orgId")))throw Api.error(404,"单位不存在");return unit;}
    @Transactional public Map<String,Object> saveUnit(String id,Map<String,Object> body){auth.require("ADMIN");var unit=id==null?map("id",Store.id("unit"),"orgId",auth.current().get("orgId"),"active",true):unit(id);for(String field:List.of("name","type","address","people","buildings","risk"))if(id==null||body.containsKey(field))unit.put(field,text(body,field,field.equals("address")?300:120));if(body.containsKey("active")){if(!(body.get("active") instanceof Boolean))throw Api.error(400,"请选择有效的档案状态");unit.put("active",body.get("active"));}unit.put("updated",now());unit.put("updatedAt",now());var columns=map("org_id",unit.get("orgId"),"active",unit.get("active"));if(id==null)db.insert("supervised_unit",unit,columns);else db.save("supervised_unit",unit,columns);db.log(auth.current(),null,id==null?"创建单位":"更新单位",map("unitId",unit.get("id")));return unit;}
    Map<String,Object> assigned(String id,String role){var user=auth.accountById(id);if(!user.get("orgId").equals(auth.current().get("orgId"))||!user.get("role").equals(role)||!Boolean.TRUE.equals(user.get("enabled")))throw Api.error(400,"分配人员无效或角色不匹配");return user;}
}
