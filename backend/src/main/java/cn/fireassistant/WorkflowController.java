package cn.fireassistant;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class WorkflowController {
    private final WorkflowService flow;
    WorkflowController(WorkflowService flow){this.flow=flow;}
    @PostMapping("/tasks/{id}/issues")Map<String,Object> create(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(flow.saveIssue(id,null,body));}
    @GetMapping("/issues/{id}")Map<String,Object> issue(@PathVariable String id){return Api.ok(flow.issue(id));}
    @PatchMapping("/issues/{id}")Map<String,Object> edit(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(flow.saveIssue(flow.issue(id).get("taskId").toString(),id,body));}
    @PostMapping("/issues/{id}/review")Map<String,Object> review(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(flow.reviewIssue(id,body));}
    @PostMapping("/tasks/{id}/review")Map<String,Object> finishReview(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(flow.finishReview(id,body));}
    @PostMapping("/issues/{id}/rectifications")Map<String,Object> material(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(flow.rectify(id,body));}
    @PostMapping("/issues/{id}/recheck")Map<String,Object> recheck(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(flow.recheck(id,body));}
    @PostMapping("/tasks/{id}/close")Map<String,Object> close(@PathVariable String id,@RequestBody Map<String,Object> body){return Api.ok(flow.close(id,body));}
}
