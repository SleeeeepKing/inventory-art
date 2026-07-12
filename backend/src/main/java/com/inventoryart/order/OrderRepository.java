package com.inventoryart.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<SalesOrder, UUID> {
  boolean existsByTenantId(UUID tenantId);

  Optional<SalesOrder> findByIdAndTenantId(UUID id, UUID tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from SalesOrder o where o.id=:id and o.tenantId=:tenantId")
  Optional<SalesOrder> findLocked(UUID id, UUID tenantId);

  @Query(
      "select o from SalesOrder o where o.tenantId=:tenantId and (:eventId is null or o.eventId=:eventId) and o.orderDate>=:from and o.orderDate<:to and (:q='' or lower(o.orderNumber) like lower(concat('%',:q,'%')))")
  Page<SalesOrder> search(
      UUID tenantId, String q, UUID eventId, Instant from, Instant to, Pageable pageable);

  Page<SalesOrder> findAllByTenantId(UUID tenantId, Pageable pageable);
}
