package cn.fireassistant;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BasicController {
    private final AuthService auth;private final AdminService admin;
    BasicController(AuthService auth,AdminService admin){this.auth=auth;this.admin=admin;}
    @PostMapping("/auth/login")Map<String,Object> login(@RequestBody Map<String,Object> body){return Api.ok(auth.login(body));}
    @GetMapping("/auth/me")Map<String,Object> me(){return Api.ok(auth.current());}
    @PostMapping("/auth/logout")Map<String,Object> logout(){auth.logout();return Api.ok(Map.of());}
    @GetMapping("/users")Map<String,Object> users(){return Api.ok(admin.users());}
    @PostMapping("/users")Map<String,Object> createUser(@RequestBody Map<String,Object> body){return Api.ok(admin.createUser(body));}
    @PatchMapping("/users/{id}")Map<String,Object> updateUser(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(admin.updateUser(id,body));}
    @PostMapping("/users/{id}/reset-password")Map<String,Object> password(@PathVariable String id,@RequestBody Map<String,Object> body){if(!body.containsKey("password"))throw Api.error(400,"请提供新密码");return Api.ok(admin.updateUser(id,Store.map("password",body.get("password"))));}
    @GetMapping("/units")Map<String,Object> units(@RequestParam(defaultValue="")String q){var query=q.toLowerCase();return Api.ok(admin.units().stream().filter(u->(u.get("name")+" "+u.get("type")+" "+u.get("address")).toLowerCase().contains(query)).toList());}
    @PostMapping("/units")Map<String,Object> createUnit(@RequestBody Map<String,Object> body){return Api.ok(admin.saveUnit(null,body));}
    @PatchMapping("/units/{id}")Map<String,Object> updateUnit(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(admin.saveUnit(id,body));}
    @DeleteMapping("/units/{id}")Map<String,Object> deactivate(@PathVariable String id){return Api.ok(admin.saveUnit(id,Map.of("active",false)));}
}
