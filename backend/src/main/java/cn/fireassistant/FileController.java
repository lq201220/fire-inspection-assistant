package cn.fireassistant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/files")
public class FileController {
    private final FileService files;
    FileController(FileService files){this.files=files;}
    @PostMapping("/photos")Map<String,Object> photo(@RequestParam MultipartFile file,@RequestParam String taskId,@RequestParam(defaultValue="issue")String purpose)throws Exception{return Api.ok(files.upload(file,taskId,purpose,true));}
    @PostMapping("/attachments")Map<String,Object> attachment(@RequestParam MultipartFile file,@RequestParam String taskId,@RequestParam String purpose)throws Exception{return Api.ok(files.upload(file,taskId,purpose,false));}
    @GetMapping({"/photos/{id}","/attachments/{id}"})ResponseEntity<?> download(@PathVariable String id){var file=files.get(id);var path=files.path(file);if(!Files.isRegularFile(path))throw Api.error(404,"附件文件不存在");return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.get("contentType").toString())).header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.get("name").toString(),StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(path));}
    @DeleteMapping("/{id}")Map<String,Object> remove(@PathVariable String id)throws Exception{files.removeTemporary(id);return Api.ok(Map.of());}
}
