package cn.fireassistant;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.fireassistant.Store.*;

@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    private final JwtEncoder encoder;private final JwtDecoder decoder;private final int hours;
    AuthService(JdbcTemplate jdbc,@Value("${app.jwt-secret}")String secret,@Value("${app.session-hours}")int hours){
        this.jdbc=jdbc;this.hours=hours;if(secret.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        var key=new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256");encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var dec=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();dec.setJwtValidator(JwtValidators.createDefaultWithIssuer("fire-assistant"));decoder=dec;
    }
    public String hashPassword(String password){return passwords.encode(password);}
    Map<String,Object> account(Map<String,Object> row){return map("id",row.get("id"),"username",row.get("username"),"name",row.get("name"),"role",row.get("role"),"orgId",row.get("org_id"),"unit",row.get("org_id"),"enabled",row.get("enabled"),"tokenVersion",row.get("token_version"));}
    Map<String,Object> accountById(String id){var rows=jdbc.queryForList("SELECT * FROM sys_user WHERE id=?",id);if(rows.isEmpty())throw Api.error(404,"用户不存在");return account(rows.get(0));}
    @Transactional public Map<String,Object> login(Map<String,Object> body){
        String username=text(body,"username",80),password=text(body,"password",128);var rows=jdbc.queryForList("SELECT * FROM sys_user WHERE username=?",username);
        if(rows.isEmpty()||!Boolean.TRUE.equals(rows.get(0).get("enabled"))||!passwords.matches(password,rows.get(0).get("password_hash").toString()))throw Api.error(401,"账号或密码错误，或账号已停用");
        var user=account(rows.get(0));String session=id("session");Instant expiration=Instant.now().plusSeconds(hours*3600L);
        jdbc.update("INSERT INTO auth_session(id,user_id,expires_at,token_version) VALUES(?,?,?,?)",session,user.get("id"),Timestamp.from(expiration),user.get("tokenVersion"));
        var claims=JwtClaimsSet.builder().issuer("fire-assistant").subject(user.get("id").toString()).id(session).issuedAt(Instant.now()).expiresAt(expiration).build();
        String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();return map("token",token,"expiresAt",expiration.toString(),"user",user);
    }
    Map<String,Object> authenticate(String token){
        try{Jwt jwt=decoder.decode(token);var rows=jdbc.queryForList("SELECT s.id AS session_id,s.expires_at,s.revoked,s.token_version AS session_version,u.* FROM auth_session s JOIN sys_user u ON u.id=s.user_id WHERE s.id=? AND u.id=?",jwt.getId(),jwt.getSubject());if(rows.isEmpty())throw Api.error(401,"登录已失效，请重新登录");var row=rows.get(0);
            if(!Boolean.TRUE.equals(row.get("enabled"))||Boolean.TRUE.equals(row.get("revoked"))||!row.get("session_version").equals(row.get("token_version"))||((Timestamp)row.get("expires_at")).toInstant().isBefore(Instant.now()))throw Api.error(401,"登录已失效，请重新登录");
            var user=account(row);user.put("sessionId",jwt.getId());return user;
        }catch(JwtException ex){throw Api.error(401,"登录已失效，请重新登录");}
    }
    public Map<String,Object> current(){Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(!(principal instanceof Map<?,?>))throw Api.error(401,"请先登录");@SuppressWarnings("unchecked")var user=(Map<String,Object>)principal;return user;}
    public void require(String... roles){if(!Arrays.asList(roles).contains(current().get("role").toString()))throw Api.error(403,"当前角色不能执行该操作");}
    @Transactional public void logout(){jdbc.update("UPDATE auth_session SET revoked=TRUE WHERE id=?",current().get("sessionId"));}
}
