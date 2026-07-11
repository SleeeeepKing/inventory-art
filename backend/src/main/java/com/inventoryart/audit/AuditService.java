package com.inventoryart.audit;

import com.inventoryart.security.CurrentUser;
import com.inventoryart.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;

@Service
public class AuditService {
    private static final Set<String> SENSITIVE=Set.of("password","token","authorization","cookie","cardnumber","cvv");
    private final AuditLogRepository logs;private final CurrentUserService current;
    public AuditService(AuditLogRepository logs,CurrentUserService current){this.logs=logs;this.current=current;}
    @Transactional
    public void record(UUID tenant,String action,String type,UUID resource,String result,Map<String,?> metadata){
        CurrentUser actor=current.get();HttpServletRequest req=request();Map<String,Object> safe=new LinkedHashMap<>();if(metadata!=null)metadata.forEach((k,v)->{if(SENSITIVE.stream().noneMatch(s->k.toLowerCase().contains(s)))safe.put(k,String.valueOf(v));});
        logs.save(new AuditLog(tenant,actor.userId(),actor.role(),action,type,resource,result,req==null?null:ip(req),req==null?null:truncate(req.getHeader("User-Agent"),500),safe));
    }
    private HttpServletRequest request(){var attrs=RequestContextHolder.getRequestAttributes();return attrs instanceof ServletRequestAttributes s?s.getRequest():null;}
    private String ip(HttpServletRequest r){String f=r.getHeader("X-Forwarded-For");return f==null?r.getRemoteAddr():f.split(",")[0].trim();}private String truncate(String s,int n){return s==null?null:s.substring(0,Math.min(n,s.length()));}
}
