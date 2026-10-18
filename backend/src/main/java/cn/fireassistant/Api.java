package cn.fireassistant;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class HealthController {
    private final JdbcTemplate jdbc;
    HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/api/health") Map<String,Object> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Api.ok(Map.of("status", "pong", "database", "connected"));
    }
}

public final class Api {
    private Api() {}
    public static Map<String,Object> ok(Object data) { return Map.of("code",0,"message","成功","data", data); }
    public static ResponseStatusException error(int status,String text) { return new ResponseStatusException(HttpStatus.valueOf(status),text); }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    org.springframework.http.ResponseEntity<?> business(ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(Map.of("code",ex.getStatusCode().value(),"message",ex.getReason()==null?"请求失败":ex.getReason(),"data",Map.of()));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    org.springframework.http.ResponseEntity<?> input(Exception ex) { return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code",400,"message","提交内容有误，请检查后重试","data",Map.of())); }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    org.springframework.http.ResponseEntity<?> duplicate(Exception ex) { return org.springframework.http.ResponseEntity.status(409).body(Map.of("code",409,"message","账号或同名记录已存在，请修改后重试","data",Map.of())); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    org.springframework.http.ResponseEntity<?> size(Exception ex) { return org.springframework.http.ResponseEntity.status(413).body(Map.of("code",413,"message","附件超过大小限制（单文件 10MB）","data",Map.of())); }
    @ExceptionHandler(Exception.class)
    org.springframework.http.ResponseEntity<?> unexpected(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Unhandled API exception",ex);
        return org.springframework.http.ResponseEntity.status(500).body(Map.of("code",500,"message","暂时无法完成操作，请稍后重试","data",Map.of()));
    }
}
