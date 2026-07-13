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

  List<Product> findAllByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);

  List<Product> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

  boolean existsByTenantIdAndSkuIgnoreCase(UUID tenantId, String sku);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Product p where p.id=:id and p.tenantId=:tenantId")
  Optional<Product> findLocked(UUID id, UUID tenantId);

  @Query(
      "select p from Product p where p.tenantId=:tenantId and (:enabled is null or p.enabled=:enabled) and (:lowStock is null or (:lowStock=true and p.currentStock<=p.lowStockThreshold) or (:lowStock=false and p.currentStock>p.lowStockThreshold)) and (:categoriesEmpty=true or lower(p.category) in :categories) and (:q='' or lower(p.sku) like lower(concat('%',:q,'%')) or lower(p.name) like lower(concat('%',:q,'%')) or lower(coalesce(p.category,'')) like lower(concat('%',:q,'%')) or lower(coalesce(p.artistName,'')) like lower(concat('%',:q,'%')))")
  Page<Product> search(
      UUID tenantId,
      String q,
      Boolean enabled,
      Boolean lowStock,
      boolean categoriesEmpty,
      List<String> categories,
      Pageable pageable);

  @Query(
      "select distinct p.category from Product p where p.tenantId=:tenantId and p.category is not null and p.category<>'' order by p.category")
  List<String> findCategories(UUID tenantId);
}
