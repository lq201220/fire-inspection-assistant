package cn.fireassistant;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TaskController {
    private final TaskService tasks;
    TaskController(TaskService tasks){this.tasks=tasks;}
    @GetMapping("/state")Map<String,Object> state(){return Api.ok(tasks.state());}
    @GetMapping("/checklist-options")Map<String,Object> checklistOptions(@RequestParam String unitId,@RequestParam String source){return Api.ok(tasks.checklistOptions(unitId,source));}
    @PostMapping("/checklist-items")Map<String,Object> saveChecklistItem(@RequestBody Map<String,Object> body){return Api.ok(tasks.saveChecklistItem(body));}
    @DeleteMapping("/checklist-items/{id}")Map<String,Object> deleteChecklistItem(@PathVariable String id,@RequestParam(required=false) String unitId,@RequestParam(required=false) String source){tasks.deleteChecklistItem(id,unitId,source);return Api.ok(Map.of());}
    @GetMapping("/dashboard")Map<String,Object> dashboard(){return Api.ok(tasks.dashboard());}
    @GetMapping("/units/{id}")Map<String,Object> unit(@PathVariable String id){return Api.ok(tasks.unitDetail(id));}
    @GetMapping("/tasks")Map<String,Object> list(@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="全部")String status){String query=q.toLowerCase();return Api.ok(tasks.tasks().stream().filter(t->status.equals("全部")||t.get("status").equals(status)).filter(t->(t.get("id")+" "+tasks.unitDetail(t.get("unitId").toString()).get("name")+" "+t.get("source")).toLowerCase().contains(query)).toList());}
    @GetMapping("/tasks/{id}")Map<String,Object> task(@PathVariable String id){return Api.ok(tasks.detail(id));}
    @PostMapping("/tasks")Map<String,Object> create(@RequestBody Map<String,Object> body){return Api.ok(tasks.create(body));}
    @PatchMapping("/tasks/{id}")Map<String,Object> edit(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(tasks.edit(id,body));}
    @PostMapping("/tasks/{id}/assign")Map<String,Object> assign(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(tasks.edit(id,body));}
    @PostMapping("/tasks/{id}/start")Map<String,Object> start(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(tasks.start(id,body));}
    @PutMapping("/tasks/{id}/site-check")Map<String,Object> site(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(tasks.site(id,body));}
    @PutMapping("/tasks/{id}/checklist/{itemId}")Map<String,Object> checklist(@PathVariable String id,@PathVariable String itemId,@RequestBody Map<String,Object> body){return Api.ok(tasks.checklist(id,itemId,body));}
    @PostMapping("/tasks/{id}/submit-review")Map<String,Object> submit(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(tasks.submit(id,body));}
    @GetMapping("/risk-events")Map<String,Object> risks(){var state=tasks.state();return Api.ok(state.get("events"));}
    @GetMapping("/tasks/{id}/history")Map<String,Object> history(@PathVariable String id){return Api.ok(tasks.history(id));}
}
