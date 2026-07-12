package com.inventoryart.product;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, UUID> {
  Optional<Product> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<Product> findByTenantIdAndSkuIgnoreCase(UUID tenantId, String sku);

  List<Product> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

  boolean existsByTenantIdAndSkuIgnoreCase(UUID tenantId, String sku);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Product p where p.id=:id and p.tenantId=:tenantId")
  Optional<Product> findLocked(UUID id, UUID tenantId);

  @Query(
      "select p from Product p where p.tenantId=:tenantId and (:enabled is null or p.enabled=:enabled) and (:lowStock=false or p.currentStock<=p.lowStockThreshold) and (:q='' or lower(p.sku) like lower(concat('%',:q,'%')) or lower(p.name) like lower(concat('%',:q,'%')) or lower(coalesce(p.category,'')) like lower(concat('%',:q,'%')) or lower(coalesce(p.artistName,'')) like lower(concat('%',:q,'%')))")
  Page<Product> search(
      UUID tenantId, String q, Boolean enabled, boolean lowStock, Pageable pageable);
}
