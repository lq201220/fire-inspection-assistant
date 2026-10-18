package cn.fireassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Configuration
class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http,AuthService auth,ObjectMapper json,CorsConfigurationSource cors)throws Exception{
        return http.csrf(csrf->csrf.disable()).cors(config->config.configurationSource(cors)).sessionManagement(config->config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(config->config.requestMatchers("/api/health","/api/auth/login").permitAll().anyRequest().authenticated())
            .exceptionHandling(config->config.authenticationEntryPoint((req,res,ex)->write(res,json,401,"请先登录")).accessDeniedHandler((req,res,ex)->write(res,json,403,"无权访问")))
            .addFilterBefore(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
                    if(req.getRequestURI().equals("/api/auth/login")||req.getRequestURI().equals("/api/health")||req.getMethod().equals("OPTIONS")){chain.doFilter(req,res);return;}
                    String bearer=req.getHeader("Authorization");if(bearer!=null&&bearer.startsWith("Bearer ")){
                        try{var user=auth.authenticate(bearer.substring(7));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_"+user.get("role")))));}
                        catch(ResponseStatusException ex){write(res,json,ex.getStatusCode().value(),ex.getReason());return;}
                    }chain.doFilter(req,res);
                }
            },UsernamePasswordAuthenticationFilter.class).build();
    }
    static void write(HttpServletResponse res,ObjectMapper json,int code,String message)throws IOException{res.setStatus(code);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getWriter(),Map.of("code",code,"message",message,"data",Map.of()));}
    @Bean CorsConfigurationSource cors(@Value("${app.cors-origins}")String origins){var config=new CorsConfiguration();config.setAllowedOrigins(Arrays.asList(origins.split(",")));config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));config.setAllowedHeaders(List.of("Authorization","Content-Type"));config.setExposedHeaders(List.of("Content-Disposition"));var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/api/**",config);return source;}
}
