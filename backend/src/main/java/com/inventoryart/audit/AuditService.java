package com.inventoryart.audit;

import com.inventoryart.security.CurrentUser;
import com.inventoryart.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {
  private static final Set<String> SENSITIVE =
      Set.of("password", "token", "authorization", "cookie", "cardnumber", "cvv");
  private final AuditLogRepository logs;
  private final CurrentUserService current;

  public AuditService(AuditLogRepository logs, CurrentUserService current) {
    this.logs = logs;
    this.current = current;
  }

  @Transactional
  public void record(
      UUID tenant,
      String action,
      String type,
      UUID resource,
      String result,
      Map<String, ?> metadata) {
    CurrentUser actor = current.get();
    HttpServletRequest req = request();
    Map<String, Object> safe = new LinkedHashMap<>();
    if (metadata != null)
      metadata.forEach(
          (k, v) -> {
            if (SENSITIVE.stream().noneMatch(s -> k.toLowerCase().contains(s)))
              safe.put(k, sanitize(v, 0));
          });
    logs.save(
        new AuditLog(
            tenant,
            actor.userId(),
            actor.role(),
            action,
            type,
            resource,
            result,
            req == null ? null : ip(req),
            req == null ? null : truncate(req.getHeader("User-Agent"), 500),
            safe));
  }

  private Object sanitize(Object value, int depth) {
    if (value == null) return null;
    if (depth >= 4) return truncate(String.valueOf(value), 2000);
    if (value instanceof Number || value instanceof Boolean) return value;
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.entrySet().stream()
          .limit(100)
          .forEach(
              entry -> {
                String key = String.valueOf(entry.getKey());
                if (SENSITIVE.stream().noneMatch(s -> key.toLowerCase().contains(s))) {
                  result.put(key, sanitize(entry.getValue(), depth + 1));
                }
              });
      return result;
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().limit(100).map(item -> sanitize(item, depth + 1)).toList();
    }
    return truncate(String.valueOf(value), 2000);
  }

  private HttpServletRequest request() {
    var attrs = RequestContextHolder.getRequestAttributes();
    return attrs instanceof ServletRequestAttributes s ? s.getRequest() : null;
  }

  private String ip(HttpServletRequest r) {
    String f = r.getHeader("X-Forwarded-For");
    return f == null ? r.getRemoteAddr() : f.split(",")[0].trim();
  }

  private String truncate(String s, int n) {
    return s == null ? null : s.substring(0, Math.min(n, s.length()));
  }
}
