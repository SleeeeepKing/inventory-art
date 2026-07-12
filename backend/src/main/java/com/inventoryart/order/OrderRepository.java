package com.inventoryart.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<SalesOrder,UUID>{
    boolean existsByTenantId(UUID tenantId);
    Optional<SalesOrder> findByIdAndTenantId(UUID id,UUID tenantId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from SalesOrder o where o.id=:id and o.tenantId=:tenantId") Optional<SalesOrder> findLocked(UUID id,UUID tenantId);
    @Query("select o from SalesOrder o where o.tenantId=:tenantId and (:status is null or o.status=:status) and (:source is null or o.source=:source) and (:channel is null or o.salesChannel=:channel) and (:eventId is null or o.eventId=:eventId) and o.orderDate>=:from and o.orderDate<:to and (:q='' or lower(o.orderNumber) like lower(concat('%',:q,'%')) or lower(coalesce(o.eventName,'')) like lower(concat('%',:q,'%')))")
    Page<SalesOrder> search(UUID tenantId,String q,OrderStatus status,OrderSource source,SalesChannel channel,UUID eventId,Instant from,Instant to,Pageable pageable);
    Page<SalesOrder> findAllByTenantId(UUID tenantId,Pageable pageable);
}
