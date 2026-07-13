package com.inventoryart.product;

import com.inventoryart.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/product-families")
@Validated
public class ProductFamilyController {
  private final ProductFamilyService service;

  public ProductFamilyController(ProductFamilyService service) {
    this.service = service;
  }

  @GetMapping
  public PageResponse<ProductFamilyService.FamilyResponse> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Boolean lowStock,
      @RequestParam(required = false) @Size(max = 100) List<@Size(max = 160) String> categories,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(q, enabled, lowStock, categories, page, size);
  }

  @GetMapping("/{id}")
  public ProductFamilyService.FamilyResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProductFamilyService.FamilyResponse create(@Valid @RequestBody CreateRequest request) {
    return service.create(request);
  }

  @PutMapping("/{id}")
  public ProductFamilyService.FamilyResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
    return service.update(id, request);
  }

  @PostMapping("/{id}/variants")
  @ResponseStatus(HttpStatus.CREATED)
  public ProductFamilyService.FamilyResponse addVariants(
      @PathVariable UUID id, @Valid @RequestBody AddVariantsRequest request) {
    return service.addVariants(id, request);
  }

  public record CreateRequest(
      @NotBlank @Size(max = 240) String name,
      @Size(max = 160) String category,
      @Size(max = 160) String artistName,
      @Size(max = 5000) String description,
      @NotNull @Size(min = 1, max = 50) List<@Valid VariantCreateRequest> variants) {}

  public record UpdateRequest(
      @NotBlank @Size(max = 240) String name,
      @Size(max = 160) String category,
      @Size(max = 160) String artistName,
      @Size(max = 5000) String description,
      @Min(0) long version) {}

  public record AddVariantsRequest(
      @NotNull @Size(min = 1, max = 50) List<@Valid VariantCreateRequest> variants) {}

  public record VariantCreateRequest(
      @NotBlank @Size(max = 160) String variantName,
      @NotBlank @Size(max = 100) String sku,
      @Min(0) Integer initialStock,
      @Min(0) Integer lowStockThreshold,
      Boolean enabled) {}
}
