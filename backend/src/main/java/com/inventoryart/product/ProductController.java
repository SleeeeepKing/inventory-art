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
  private final InventoryService inventory;
  private final CurrentUserService current;
  private final AuditService audit;
  private final FileService files;
  private final ProductSalesService sales;

  public ProductController(
      ProductRepository products,
      InventoryService inventory,
      CurrentUserService current,
      AuditService audit,
      FileService files,
      ProductSalesService sales) {
    this.products = products;
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
      @RequestParam(defaultValue = "false") boolean lowStock,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var productsPage =
        products.search(
            current.tenantId(),
            blankToEmpty(q),
            enabled,
            lowStock,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt")));
    var summaries = sales.summarize(productsPage.getContent());
    return PageResponse.of(
        productsPage.map(
            product ->
                response(
                    product,
                    summaries.getOrDefault(product.getId(), ProductSalesService.Summary.EMPTY))));
  }

  @GetMapping("/{id}")
  public Response get(@PathVariable UUID id) {
    return response(
        products
            .findByIdAndTenantId(id, current.tenantId())
            .orElseThrow(() -> new NotFoundException("Product")));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Response create(@Valid @RequestBody Request req) {
    UUID tenant = current.tenantId();
    if (products.existsByTenantIdAndSkuIgnoreCase(tenant, req.sku()))
      throw new BusinessException("DUPLICATE_SKU", "SKU already exists");
    Product p =
        products.save(
            new Product(
                UUID.randomUUID(),
                tenant,
                req.sku(),
                req.name(),
                req.category(),
                req.artistName(),
                req.description(),
                req.costPrice(),
                req.salePrice(),
                req.currency(),
                req.lowStockThreshold()));
    if (req.initialStock() > 0)
      inventory.apply(
          tenant,
          p.getId(),
          req.initialStock(),
          MovementType.INITIAL,
          null,
          null,
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
    p.update(
        req.sku(),
        req.name(),
        req.category(),
        req.artistName(),
        req.description(),
        req.costPrice(),
        req.salePrice(),
        req.currency(),
        req.lowStockThreshold(),
        req.enabled());
    audit.record(
        current.tenantId(),
        "PRODUCT_UPDATE",
        "PRODUCT",
        id,
        "SUCCESS",
        java.util.Map.of("sku", p.getSku()));
    return response(p);
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

  private Response response(Product p) {
    return response(
        p,
        sales
            .summarize(java.util.List.of(p))
            .getOrDefault(p.getId(), ProductSalesService.Summary.EMPTY));
  }

  private Response response(Product p, ProductSalesService.Summary summary) {
    return Response.from(p, files.productImageUrl(p.getId(), p.getImageObjectKey()), summary);
  }

  public record Request(
      @NotBlank @Size(max = 100) String sku,
      @NotBlank @Size(max = 240) String name,
      @Size(max = 160) String category,
      @Size(max = 160) String artistName,
      @Size(max = 5000) String description,
      @DecimalMin("0.0") BigDecimal costPrice,
      @NotNull @DecimalMin("0.0") BigDecimal salePrice,
      @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
      @Min(0) int lowStockThreshold,
      @Min(0) int initialStock,
      boolean enabled) {}

  public record Response(
      UUID id,
      String sku,
      String name,
      String category,
      String artistName,
      String description,
      String imageObjectKey,
      String imageUrl,
      BigDecimal costPrice,
      BigDecimal salePrice,
      String currency,
      int currentStock,
      int lowStockThreshold,
      boolean enabled,
      long totalUnitsSold,
      BigDecimal totalSalesRevenue,
      Instant lastSaleAt,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    public static Response from(Product p) {
      return from(p, null, ProductSalesService.Summary.EMPTY);
    }

    public static Response from(Product p, String imageUrl) {
      return from(p, imageUrl, ProductSalesService.Summary.EMPTY);
    }

    public static Response from(Product p, String imageUrl, ProductSalesService.Summary summary) {
      return new Response(
          p.getId(),
          p.getSku(),
          p.getName(),
          p.getCategory(),
          p.getArtistName(),
          p.getDescription(),
          p.getImageObjectKey(),
          imageUrl,
          p.getCostPrice(),
          p.getSalePrice(),
          p.getCurrency(),
          p.getCurrentStock(),
          p.getLowStockThreshold(),
          p.isEnabled(),
          summary.unitsSold(),
          summary.revenue(),
          summary.lastSaleAt(),
          p.getVersion(),
          p.getCreatedAt(),
          p.getUpdatedAt());
    }
  }
}
