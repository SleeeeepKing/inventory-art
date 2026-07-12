package com.inventoryart.audit;

import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
  @Query(
      "select a from AuditLog a where (:tenantId is null or a.tenantId=:tenantId) and (:action is null or a.action=:action)")
  Page<AuditLog> search(UUID tenantId, String action, Pageable pageable);
}
