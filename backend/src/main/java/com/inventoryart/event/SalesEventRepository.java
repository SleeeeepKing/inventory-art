package com.inventoryart.event;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesEventRepository extends JpaRepository<SalesEvent, UUID> {
  List<SalesEvent> findAllByTenantIdAndEnabledOrderByNameAsc(UUID tenantId, boolean enabled);

  List<SalesEvent> findAllByTenantIdOrderByNameAsc(UUID tenantId);

  List<SalesEvent> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

  Optional<SalesEvent> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<SalesEvent> findByIdAndTenantIdAndEnabledTrue(UUID id, UUID tenantId);

  Optional<SalesEvent> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

  @Query(
      value =
          """
        select exists (
            select 1 from orders where tenant_id=:tenantId and event_id=:eventId
            union all
            select 1 from inventory_sale_batches where tenant_id=:tenantId and event_id=:eventId
            union all
            select 1 from sales_event_expenses where tenant_id=:tenantId and event_id=:eventId
        )
        """,
      nativeQuery = true)
  boolean isReferenced(@Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId);
}
