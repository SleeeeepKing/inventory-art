package com.inventoryart.audit;

import com.inventoryart.common.PageResponse;
import com.inventoryart.security.CurrentUserService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
  private final AuditLogRepository logs;
  private final CurrentUserService current;

  public AuditController(AuditLogRepository logs, CurrentUserService current) {
    this.logs = logs;
    this.current = current;
  }

  @GetMapping
  public PageResponse<Response> own(
      @RequestParam(required = false) String action,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return PageResponse.of(
        logs.search(
                current.tenantId(),
                action,
                PageRequest.of(
                    page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")))
            .map(Response::from));
  }

  public record Response(
      UUID id,
      UUID tenantId,
      UUID actorUserId,
      String actorRole,
      String action,
      String resourceType,
      UUID resourceId,
      String result,
      Map<String, Object> metadata,
      Instant createdAt) {
    static Response from(AuditLog a) {
      return new Response(
          a.getId(),
          a.getTenantId(),
          a.getActorUserId(),
          a.getActorRole() == null ? null : a.getActorRole().name(),
          a.getAction(),
          a.getResourceType(),
          a.getResourceId(),
          a.getResult(),
          a.getMetadata(),
          a.getCreatedAt());
    }
  }
}
