package com.inventoryart.product;

import com.inventoryart.audit.AuditService;
import com.inventoryart.common.PageResponse;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.inventory.InventoryService;
import com.inventoryart.inventory.MovementType;
import com.inventoryart.security.CurrentUserService;
import com.inventoryart.storage.FileService;
import com.inventoryart.tenant.TenantRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductFamilyService {
  public static final int DEFAULT_INITIAL_STOCK = 999;
  public static final int DEFAULT_LOW_STOCK_THRESHOLD = 5;

  private final ProductFamilyRepository families;
  private final ProductRepository products;
  private final TenantRepository tenants;
  private final InventoryService inventory;
  private final CurrentUserService current;
  private final AuditService audit;
  private final FileService files;
  private final ProductSalesService sales;

  public ProductFamilyService(
      ProductFamilyRepository families,
      ProductRepository products,
      TenantRepository tenants,
      InventoryService inventory,
      CurrentUserService current,
      AuditService audit,
      FileService files,
      ProductSalesService sales) {
    this.families = families;
    this.products = products;
    this.tenants = tenants;
    this.inventory = inventory;
    this.current = current;
    this.audit = audit;
    this.files = files;
    this.sales = sales;
  }

  @Transactional(readOnly = true)
  public PageResponse<FamilyResponse> list(String q, int page, int size) {
    UUID tenantId = current.tenantId();
    var result =
        families.search(
            tenantId,
            q == null || q.isBlank() ? "" : q.trim(),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
    List<UUID> familyIds = result.getContent().stream().map(ProductFamily::getId).toList();
    Map<UUID, List<Product>> variants = variantsByFamily(tenantId, familyIds);
    Map<UUID, ProductSalesService.Summary> summaries =
        sales.summarize(variants.values().stream().flatMap(List::stream).toList());
    return PageResponse.of(
        result.map(
            family ->
                response(family, variants.getOrDefault(family.getId(), List.of()), summaries)));
  }

  @Transactional(readOnly = true)
  public FamilyResponse get(UUID id) {
    ProductFamily family = required(id);
    List<Product> variants = products.findAllByTenantIdAndFamilyId(current.tenantId(), id);
    return response(family, variants, sales.summarize(variants));
  }

  @Transactional
  public FamilyResponse create(ProductFamilyController.CreateRequest request) {
    UUID tenantId = current.tenantId();
    validateUniqueSkus(tenantId, request.variants());
    ProductFamily family =
        families.save(
            new ProductFamily(
                UUID.randomUUID(),
                tenantId,
                request.name(),
                request.category(),
                request.artistName(),
                request.description()));
    List<Product> created = createVariants(family, request.variants());
    audit.record(
        tenantId,
        "PRODUCT_FAMILY_CREATE",
        "PRODUCT_FAMILY",
        family.getId(),
        "SUCCESS",
        Map.of("variantCount", created.size()));
    return response(family, created, Map.of());
  }

  @Transactional
  public FamilyResponse update(UUID id, ProductFamilyController.UpdateRequest request) {
    ProductFamily family = required(id);
    if (family.getVersion() != request.version()) {
      throw new BusinessException(
          "VERSION_CONFLICT", "Product family was changed by another request", HttpStatus.CONFLICT);
    }
    family.update(request.name(), request.category(), request.artistName(), request.description());
    List<Product> variants = products.findAllByTenantIdAndFamilyId(current.tenantId(), id);
    for (Product product : variants) {
      product.update(
          product.getSku(),
          family.getName(),
          family.getCategory(),
          family.getArtistName(),
          family.getDescription(),
          product.getCostPrice(),
          product.getSalePrice(),
          product.getCurrency(),
          product.getLowStockThreshold(),
          product.isEnabled());
    }
    audit.record(
        current.tenantId(),
        "PRODUCT_FAMILY_UPDATE",
        "PRODUCT_FAMILY",
        id,
        "SUCCESS",
        Map.of("variantCount", variants.size()));
    return response(family, variants, sales.summarize(variants));
  }

  @Transactional
  public FamilyResponse addVariants(UUID id, ProductFamilyController.AddVariantsRequest request) {
    ProductFamily family = required(id);
    validateUniqueSkus(current.tenantId(), request.variants());
    List<Product> created = createVariants(family, request.variants());
    audit.record(
        current.tenantId(),
        "PRODUCT_VARIANTS_CREATE",
        "PRODUCT_FAMILY",
        id,
        "SUCCESS",
        Map.of("variantCount", created.size()));
    List<Product> all = products.findAllByTenantIdAndFamilyId(current.tenantId(), id);
    return response(family, all, sales.summarize(all));
  }

  private List<Product> createVariants(
      ProductFamily family, List<ProductFamilyController.VariantCreateRequest> requests) {
    String currency =
        tenants
            .findById(family.getTenantId())
            .orElseThrow(() -> new NotFoundException("Tenant"))
            .getDefaultCurrency();
    List<Product> created = new ArrayList<>();
    for (ProductFamilyController.VariantCreateRequest request : requests) {
      int threshold =
          request.lowStockThreshold() == null
              ? DEFAULT_LOW_STOCK_THRESHOLD
              : request.lowStockThreshold();
      int initialStock =
          request.initialStock() == null ? DEFAULT_INITIAL_STOCK : request.initialStock();
      Product product =
          products.save(
              new Product(
                  UUID.randomUUID(),
                  family.getTenantId(),
                  family.getId(),
                  request.variantName(),
                  request.sku(),
                  family.getName(),
                  family.getCategory(),
                  family.getArtistName(),
                  family.getDescription(),
                  null,
                  BigDecimal.ZERO,
                  currency,
                  threshold));
      product.updateVariant(
          request.sku(),
          request.variantName(),
          threshold,
          request.enabled() == null || request.enabled());
      if (initialStock > 0) {
        inventory.apply(
            family.getTenantId(),
            product.getId(),
            initialStock,
            MovementType.INITIAL,
            "Initial stock",
            null,
            current.userId());
      }
      audit.record(
          family.getTenantId(),
          "PRODUCT_CREATE",
          "PRODUCT",
          product.getId(),
          "SUCCESS",
          Map.of("sku", product.getSku(), "familyId", family.getId()));
      created.add(product);
    }
    return List.copyOf(created);
  }

  private void validateUniqueSkus(
      UUID tenantId, List<ProductFamilyController.VariantCreateRequest> requests) {
    Set<String> requestSkus = new HashSet<>();
    for (ProductFamilyController.VariantCreateRequest request : requests) {
      String sku = request.sku().trim().toUpperCase(Locale.ROOT);
      if (!requestSkus.add(sku) || products.existsByTenantIdAndSkuIgnoreCase(tenantId, sku)) {
        throw new BusinessException("DUPLICATE_SKU", "SKU already exists", HttpStatus.CONFLICT);
      }
    }
  }

  private ProductFamily required(UUID id) {
    return families
        .findByIdAndTenantId(id, current.tenantId())
        .orElseThrow(() -> new NotFoundException("Product family"));
  }

  private Map<UUID, List<Product>> variantsByFamily(UUID tenantId, List<UUID> familyIds) {
    if (familyIds.isEmpty()) return Map.of();
    Map<UUID, List<Product>> result = new HashMap<>();
    for (Product product : products.findAllByTenantIdAndFamilyIdIn(tenantId, familyIds)) {
      result.computeIfAbsent(product.getFamilyId(), ignored -> new ArrayList<>()).add(product);
    }
    return result;
  }

  private FamilyResponse response(
      ProductFamily family,
      List<Product> variants,
      Map<UUID, ProductSalesService.Summary> summaries) {
    List<VariantResponse> variantResponses =
        variants.stream()
            .sorted(java.util.Comparator.comparing(Product::getSku))
            .map(
                product -> {
                  ProductSalesService.Summary summary =
                      summaries.getOrDefault(product.getId(), ProductSalesService.Summary.EMPTY);
                  return new VariantResponse(
                      product.getId(),
                      product.getVariantName(),
                      product.getSku(),
                      product.getCurrentStock(),
                      product.getLowStockThreshold(),
                      product.isEnabled(),
                      summary.unitsSold(),
                      summary.lastSaleDate(),
                      product.getVersion(),
                      product.getCreatedAt(),
                      product.getUpdatedAt());
                })
            .toList();
    return new FamilyResponse(
        family.getId(),
        family.getName(),
        family.getCategory(),
        family.getArtistName(),
        family.getDescription(),
        files.productFamilyImageUrl(family.getId(), family.getImageObjectKey()),
        family.getVersion(),
        family.getCreatedAt(),
        family.getUpdatedAt(),
        variantResponses);
  }

  public record FamilyResponse(
      UUID id,
      String name,
      String category,
      String artistName,
      String description,
      String imageUrl,
      long version,
      java.time.Instant createdAt,
      java.time.Instant updatedAt,
      List<VariantResponse> variants) {}

  public record VariantResponse(
      UUID id,
      String variantName,
      String sku,
      int currentStock,
      int lowStockThreshold,
      boolean enabled,
      long totalUnitsSold,
      java.time.LocalDate lastSaleDate,
      long version,
      java.time.Instant createdAt,
      java.time.Instant updatedAt) {}
}
