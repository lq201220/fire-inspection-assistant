package cn.fireassistant;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import static cn.fireassistant.Store.*;

@Service
public class FileService {
    private final Store db;private final AuthService auth;private final TaskService tasks;private final Path root;
    FileService(Store db,AuthService auth,TaskService tasks,@Value("${app.uploads}")String directory)throws IOException{this.db=db;this.auth=auth;this.tasks=tasks;root=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(root);}
    Map<String,Object> metadata(String id,boolean lock){var rows=db.jdbc.queryForList("SELECT * FROM file_attachment WHERE id=?"+(lock?" FOR UPDATE":""),id);if(rows.isEmpty())throw Api.error(404,"附件不存在");var raw=rows.get(0);return map("id",raw.get("id"),"taskId",raw.get("task_id"),"uploaderId",raw.get("uploader_id"),"name",raw.get("original_name"),"storedName",raw.get("stored_name"),"contentType",raw.get("content_type"),"size",raw.get("byte_size"),"purpose",raw.get("purpose"),"boundId",raw.get("bound_id"),"createdAt",raw.get("created_at").toString());}
    public Map<String,Object> get(String id){var file=metadata(id,false);tasks.task(file.get("taskId").toString());if(file.get("boundId")==null&&!file.get("uploaderId").equals(auth.current().get("id")))throw Api.error(404,"附件尚未提交");return file;}
    Path path(Map<String,Object> file){Path path=root.resolve(file.get("storedName").toString()).normalize();if(!path.startsWith(root))throw Api.error(400,"附件无法读取，请重试");return path;}
    @Transactional(rollbackFor=Exception.class) public Map<String,Object> upload(MultipartFile file,String taskId,String purpose,boolean onlyPhoto)throws IOException{
        if(!Set.of("issue","rectification","recheck").contains(purpose))throw Api.error(400,"无效附件用途");auth.require(purpose.equals("recheck")?new String[]{"RECHECKER"}:purpose.equals("issue")?new String[]{"INSPECTOR"}:new String[]{"INSPECTOR","RECHECKER"});var task=tasks.task(taskId);TaskService.status(task,purpose.equals("issue")?"进行中":"整改中");
        if(file.isEmpty()||file.getSize()>10L*1024*1024)throw Api.error(400,"文件不能为空且不能超过 10MB");byte[] bytes=file.getBytes();String type,extension;
        if(!onlyPhoto&&bytes.length>=5&&new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")){type="application/pdf";extension="pdf";}else{
            try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw Api.error(400,"只支持真实 PNG/JPEG 图片或整改/复查 PDF 材料");var reader=readers.next();try{reader.setInput(input);String format=reader.getFormatName().toLowerCase();if(!Set.of("png","jpeg","jpg").contains(format))throw Api.error(400,"照片只支持 PNG/JPEG");int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||width>5000||height>5000||(long)width*height>16000000)throw Api.error(400,"图片尺寸超限");type=format.equals("png")?"image/png":"image/jpeg";extension=format.equals("png")?"png":"jpg";}finally{reader.dispose();}}
        }
        String id=id("file"),stored=id+"."+extension;String original=Optional.ofNullable(file.getOriginalFilename()).orElse(stored).replaceAll("[\\\\/\\r\\n]","_");if(original.length()>200)original=original.substring(original.length()-200);
        Path target=root.resolve(stored);Files.write(target,bytes,StandardOpenOption.CREATE_NEW);
        try{db.jdbc.update("INSERT INTO file_attachment(id,task_id,uploader_id,original_name,stored_name,content_type,byte_size,purpose) VALUES(?,?,?,?,?,?,?,?)",id,taskId,auth.current().get("id"),original,stored,type,bytes.length,purpose);return metadata(id,false);}catch(RuntimeException ex){Files.deleteIfExists(target);throw ex;}
    }
    void bind(List<String> ids,String taskId,String purpose,String boundId){for(String id:ids){var file=metadata(id,true);if(!file.get("taskId").equals(taskId)||!file.get("purpose").equals(purpose)||!file.get("uploaderId").equals(auth.current().get("id"))||(file.get("boundId")!=null&&!file.get("boundId").equals(boundId)))throw Api.error(400,"附件归属或用途不正确");db.jdbc.update("UPDATE file_attachment SET bound_id=? WHERE id=?",boundId,id);}}
    @Transactional(rollbackFor=Exception.class) public void removeTemporary(String id)throws IOException{var file=metadata(id,true);tasks.task(file.get("taskId").toString());if(!file.get("uploaderId").equals(auth.current().get("id"))||file.get("boundId")!=null)throw Api.error(403,"只能删除自己尚未提交的附件");db.jdbc.update("DELETE FROM file_attachment WHERE id=?",id);Files.deleteIfExists(path(file));}
}
