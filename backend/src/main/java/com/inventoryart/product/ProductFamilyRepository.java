package com.inventoryart.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductFamilyRepository extends JpaRepository<ProductFamily, UUID> {
  Optional<ProductFamily> findByIdAndTenantId(UUID id, UUID tenantId);

  List<ProductFamily> findAllByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);

  @Query(
      "select f from ProductFamily f where f.tenantId=:tenantId and (:q='' or lower(f.name) like lower(concat('%',:q,'%')) or lower(coalesce(f.category,'')) like lower(concat('%',:q,'%')) or lower(coalesce(f.artistName,'')) like lower(concat('%',:q,'%')) or exists (select p.id from Product p where p.tenantId=f.tenantId and p.familyId=f.id and lower(p.sku) like lower(concat('%',:q,'%'))))")
  Page<ProductFamily> search(UUID tenantId, String q, Pageable pageable);
}
