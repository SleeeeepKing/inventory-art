package com.inventoryart.product;

import com.inventoryart.audit.AuditService;
import com.inventoryart.common.PageResponse;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.inventory.InventoryService;
import com.inventoryart.inventory.MovementType;
import com.inventoryart.security.CurrentUserService;
import com.inventoryart.storage.FileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
@Validated
public class ProductController {
  private final ProductRepository products;
  private final ProductFamilyRepository families;
  private final InventoryService inventory;
  private final CurrentUserService current;
  private final AuditService audit;
  private final FileService files;
  private final ProductSalesService sales;

  public ProductController(
      ProductRepository products,
      ProductFamilyRepository families,
      InventoryService inventory,
      CurrentUserService current,
      AuditService audit,
      FileService files,
      ProductSalesService sales) {
    this.products = products;
    this.families = families;
    this.inventory = inventory;
    this.current = current;
    this.audit = audit;
    this.files = files;
    this.sales = sales;
  }

  @GetMapping
  public PageResponse<Response> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Boolean lowStock,
      @RequestParam(required = false) @Size(max = 100) List<@Size(max = 160) String> categories,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    List<String> categoryFilter = normalizeCategories(categories);
    var productsPage =
        products.search(
            current.tenantId(),
            blankToEmpty(q),
            enabled,
            lowStock,
            categoryFilter.isEmpty(),
            categoryFilter.isEmpty() ? List.of("") : categoryFilter,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
    var summaries = sales.summarize(productsPage.getContent());
    Map<UUID, ProductFamily> familyMap = familyMap(productsPage.getContent());
    return PageResponse.of(
        productsPage.map(
            product ->
                response(
                    product,
                    product.getFamilyId() == null ? null : familyMap.get(product.getFamilyId()),
                    summaries.getOrDefault(product.getId(), ProductSalesService.Summary.EMPTY))));
  }

  @GetMapping("/{id}")
  public Response get(@PathVariable UUID id) {
    Product product =
        products
            .findByIdAndTenantId(id, current.tenantId())
            .orElseThrow(() -> new NotFoundException("Product"));
    return response(product, family(product));
  }

  @GetMapping("/categories")
  public List<String> categories() {
    return products.findCategories(current.tenantId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Response create(@Valid @RequestBody Request req) {
    UUID tenant = current.tenantId();
    if (req.name() == null || req.name().isBlank()) {
      throw new BusinessException("VALIDATION_ERROR", "Product name is required");
    }
    if (products.existsByTenantIdAndSkuIgnoreCase(tenant, req.sku()))
      throw new BusinessException("DUPLICATE_SKU", "SKU already exists");
    ProductFamily family =
        families.save(
            new ProductFamily(
                UUID.randomUUID(),
                tenant,
                req.name(),
                req.category(),
                req.artistName(),
                req.description()));
    int threshold =
        req.lowStockThreshold() == null
            ? ProductFamilyService.DEFAULT_LOW_STOCK_THRESHOLD
            : req.lowStockThreshold();
    Product p =
        products.save(
            new Product(
                UUID.randomUUID(),
                tenant,
                family.getId(),
                req.variantName(),
                req.sku(),
                req.name(),
                req.category(),
                req.artistName(),
                req.description(),
                req.costPrice(),
                req.salePrice() == null ? BigDecimal.ZERO : req.salePrice(),
                req.currency() == null ? "EUR" : req.currency(),
                threshold));
    int initialStock =
        req.initialStock() == null
            ? ProductFamilyService.DEFAULT_INITIAL_STOCK
            : req.initialStock();
    if (initialStock > 0)
      inventory.apply(
          tenant,
          p.getId(),
          initialStock,
          MovementType.INITIAL,
          "Initial stock",
          null,
          current.userId());
    audit.record(
        tenant,
        "PRODUCT_CREATE",
        "PRODUCT",
        p.getId(),
        "SUCCESS",
        java.util.Map.of("sku", p.getSku()));
    return response(p);
  }

  @PutMapping("/{id}")
  @Transactional
  public Response update(@PathVariable UUID id, @Valid @RequestBody Request req) {
    Product p =
        products
            .findByIdAndTenantId(id, current.tenantId())
            .orElseThrow(() -> new NotFoundException("Product"));
    products
        .findByTenantIdAndSkuIgnoreCase(current.tenantId(), req.sku())
        .filter(other -> !other.getId().equals(id))
        .ifPresent(
            other -> {
              throw new BusinessException("DUPLICATE_SKU", "SKU already exists");
            });
    if (req.version() != null && req.version() != p.getVersion()) {
      throw new BusinessException(
          "VERSION_CONFLICT",
          "Product was changed by another request",
          org.springframework.http.HttpStatus.CONFLICT);
    }
    int threshold =
        req.lowStockThreshold() == null ? p.getLowStockThreshold() : req.lowStockThreshold();
    boolean enabled = req.enabled() == null ? p.isEnabled() : req.enabled();
    ProductFamily family = family(p);
    if (req.name() != null && !req.name().isBlank()) {
      family.update(req.name(), req.category(), req.artistName(), req.description());
      p.update(
          req.sku(),
          family.getName(),
          family.getCategory(),
          family.getArtistName(),
          family.getDescription(),
          req.costPrice() == null ? p.getCostPrice() : req.costPrice(),
          req.salePrice() == null ? p.getSalePrice() : req.salePrice(),
          req.currency() == null ? p.getCurrency() : req.currency(),
          threshold,
          enabled);
    }
    p.updateVariant(req.sku(), req.variantName(), threshold, enabled);
    audit.record(
        current.tenantId(),
        "PRODUCT_UPDATE",
        "PRODUCT",
        id,
        "SUCCESS",
        java.util.Map.of("sku", p.getSku()));
    return response(p, family);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  public void disable(@PathVariable UUID id) {
    Product p =
        products
            .findByIdAndTenantId(id, current.tenantId())
            .orElseThrow(() -> new NotFoundException("Product"));
    p.update(
        p.getSku(),
        p.getName(),
        p.getCategory(),
        p.getArtistName(),
        p.getDescription(),
        p.getCostPrice(),
        p.getSalePrice(),
        p.getCurrency(),
        p.getLowStockThreshold(),
        false);
    audit.record(
        current.tenantId(), "PRODUCT_DISABLE", "PRODUCT", id, "SUCCESS", java.util.Map.of());
  }

  private String blankToEmpty(String s) {
    return s == null || s.isBlank() ? "" : s.trim();
  }

  private List<String> normalizeCategories(List<String> categories) {
    if (categories == null) return List.of();
    return categories.stream()
        .map(this::blankToEmpty)
        .filter(category -> !category.isEmpty())
        .map(category -> category.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }

  private Response response(Product p) {
    return response(p, family(p));
  }

  private Response response(Product p, ProductFamily family) {
    return response(
        p,
        family,
        sales
            .summarize(java.util.List.of(p))
            .getOrDefault(p.getId(), ProductSalesService.Summary.EMPTY));
  }

  private Response response(Product p, ProductFamily family, ProductSalesService.Summary summary) {
    String imageUrl =
        family == null
            ? files.productImageUrl(p.getId(), p.getImageObjectKey())
            : files.productFamilyImageUrl(family.getId(), family.getImageObjectKey());
    return Response.from(p, family, imageUrl, summary);
  }

  private ProductFamily family(Product product) {
    if (product.getFamilyId() == null) return null;
    return families
        .findByIdAndTenantId(product.getFamilyId(), current.tenantId())
        .orElseThrow(() -> new NotFoundException("Product family"));
  }

  private Map<UUID, ProductFamily> familyMap(List<Product> productList) {
    List<UUID> ids =
        productList.stream()
            .map(Product::getFamilyId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    if (ids.isEmpty()) return Map.of();
    return families.findAllByTenantIdAndIdIn(current.tenantId(), ids).stream()
        .collect(java.util.stream.Collectors.toMap(ProductFamily::getId, family -> family));
  }

  public record Request(
      @NotBlank @Size(max = 100) String sku,
      @Size(max = 160) String variantName,
      @Size(max = 240) String name,
      @Size(max = 160) String category,
      @Size(max = 160) String artistName,
      @Size(max = 5000) String description,
      @DecimalMin("0.0") BigDecimal costPrice,
      @DecimalMin("0.0") BigDecimal salePrice,
      @Pattern(regexp = "[A-Za-z]{3}") String currency,
      @Min(0) Integer lowStockThreshold,
      @Min(0) Integer initialStock,
      Boolean enabled,
      @Min(0) Long version) {}

  public record Response(
      UUID id,
      UUID familyId,
      String variantName,
      String sku,
      String name,
      String category,
      String artistName,
      String description,
      String imageUrl,
      BigDecimal costPrice,
      BigDecimal salePrice,
      String currency,
      int currentStock,
      int lowStockThreshold,
      boolean enabled,
      long totalUnitsSold,
      java.time.LocalDate lastSaleDate,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    public static Response from(Product p) {
      return from(p, null, null, ProductSalesService.Summary.EMPTY);
    }

    public static Response from(Product p, String imageUrl) {
      return from(p, null, imageUrl, ProductSalesService.Summary.EMPTY);
    }

    public static Response from(
        Product p, ProductFamily family, String imageUrl, ProductSalesService.Summary summary) {
      return new Response(
          p.getId(),
          p.getFamilyId(),
          p.getVariantName(),
          p.getSku(),
          family == null ? p.getName() : family.getName(),
          family == null ? p.getCategory() : family.getCategory(),
          family == null ? p.getArtistName() : family.getArtistName(),
          family == null ? p.getDescription() : family.getDescription(),
          imageUrl,
          p.getCostPrice(),
          p.getSalePrice(),
          p.getCurrency(),
          p.getCurrentStock(),
          p.getLowStockThreshold(),
          p.isEnabled(),
          summary.unitsSold(),
          summary.lastSaleDate(),
          p.getVersion(),
          p.getCreatedAt(),
          p.getUpdatedAt());
    }
  }
}
