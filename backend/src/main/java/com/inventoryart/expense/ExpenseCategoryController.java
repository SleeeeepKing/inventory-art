package com.inventoryart.expense;

import com.inventoryart.audit.AuditService;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
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
@RequestMapping("/api/v1/expense-categories")
public class ExpenseCategoryController {
  private final ExpenseCategoryRepository categories;
  private final CurrentUserService current;
  private final AuditService audit;

  public ExpenseCategoryController(
      ExpenseCategoryRepository categories, CurrentUserService current, AuditService audit) {
    this.categories = categories;
    this.current = current;
    this.audit = audit;
  }

  @GetMapping
  public List<Response> list(@RequestParam(defaultValue = "false") boolean includeDisabled) {
    UUID tenantId = current.tenantId();
    List<ExpenseCategory> result =
        includeDisabled
            ? categories.findAllByTenantIdOrderByNameAsc(tenantId)
            : categories.findAllByTenantIdAndEnabledOrderByNameAsc(tenantId, true);
    return result.stream().map(Response::from).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Response create(@Valid @RequestBody CreateRequest request) {
    UUID tenantId = current.tenantId();
    String name = request.name().trim();
    ensureUnique(tenantId, name, null);
    ExpenseCategory category = categories.save(new ExpenseCategory(tenantId, name));
    audit.record(
        tenantId,
        "EXPENSE_CATEGORY_CREATE",
        "EXPENSE_CATEGORY",
        category.getId(),
        "SUCCESS",
        Map.of("name", name));
    return Response.from(category);
  }

  @PutMapping("/{id}")
  @Transactional
  public Response update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
    UUID tenantId = current.tenantId();
    ExpenseCategory category = required(tenantId, id);
    if (category.getVersion() != request.version()) {
      throw new BusinessException(
          "VERSION_CONFLICT", "Expense category changed while editing", HttpStatus.CONFLICT);
    }
    String name = request.name().trim();
    ensureUnique(tenantId, name, id);
    Map<String, Object> before =
        Map.of("name", category.getName(), "enabled", category.isEnabled());
    category.update(name, request.enabled());
    categories.flush();
    audit.record(
        tenantId,
        "EXPENSE_CATEGORY_UPDATE",
        "EXPENSE_CATEGORY",
        id,
        "SUCCESS",
        Map.of("before", before, "after", Map.of("name", name, "enabled", request.enabled())));
    return Response.from(category);
  }

  private ExpenseCategory required(UUID tenantId, UUID id) {
    return categories
        .findByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("Expense category"));
  }

  private void ensureUnique(UUID tenantId, String name, UUID currentId) {
    categories
        .findByTenantIdAndNameIgnoreCase(tenantId, name)
        .filter(category -> !category.getId().equals(currentId))
        .ifPresent(
            category -> {
              throw new BusinessException(
                  "DUPLICATE_EXPENSE_CATEGORY", "Expense category already exists");
            });
  }

  public record CreateRequest(@NotBlank @Size(max = 160) String name) {}

  public record UpdateRequest(
      @NotBlank @Size(max = 160) String name, boolean enabled, @NotNull @Min(0) Long version) {}

  public record Response(
      UUID id, String name, boolean enabled, long version, Instant createdAt, Instant updatedAt) {
    static Response from(ExpenseCategory category) {
      return new Response(
          category.getId(),
          category.getName(),
          category.isEnabled(),
          category.getVersion(),
          category.getCreatedAt(),
          category.getUpdatedAt());
    }
  }
}
