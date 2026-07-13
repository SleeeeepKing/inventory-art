package com.inventoryart.expense;

import com.inventoryart.audit.AuditService;
import com.inventoryart.common.PageResponse;
import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import com.inventoryart.tenant.Tenant;
import com.inventoryart.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/v1/sales-events/{eventId}/expenses")
public class SalesEventExpenseController {
  private final SalesEventExpenseRepository expenses;
  private final ExpenseCategoryRepository categories;
  private final SalesEventRepository events;
  private final TenantRepository tenants;
  private final CurrentUserService current;
  private final AuditService audit;

  public SalesEventExpenseController(
      SalesEventExpenseRepository expenses,
      ExpenseCategoryRepository categories,
      SalesEventRepository events,
      TenantRepository tenants,
      CurrentUserService current,
      AuditService audit) {
    this.expenses = expenses;
    this.categories = categories;
    this.events = events;
    this.tenants = tenants;
    this.current = current;
    this.audit = audit;
  }

  @GetMapping
  @Transactional(readOnly = true)
  public PageResponse<Response> list(
      @PathVariable UUID eventId,
      @RequestParam(defaultValue = "false") boolean includeVoided,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) int size) {
    UUID tenantId = current.tenantId();
    requiredEvent(tenantId, eventId);
    var pageable =
        PageRequest.of(
            page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "expenseDate", "createdAt"));
    var result =
        includeVoided
            ? expenses.findAllByTenantIdAndEventId(tenantId, eventId, pageable)
            : expenses.findAllByTenantIdAndEventIdAndStatus(
                tenantId, eventId, ExpenseStatus.ACTIVE, pageable);
    Map<UUID, ExpenseCategory> categoryMap =
        result.getContent().stream()
            .map(SalesEventExpense::getCategoryId)
            .distinct()
            .map(id -> categories.findByIdAndTenantId(id, tenantId).orElse(null))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toMap(ExpenseCategory::getId, value -> value));
    return PageResponse.of(
        result.map(expense -> Response.from(expense, categoryMap.get(expense.getCategoryId()))));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Response create(@PathVariable UUID eventId, @Valid @RequestBody Request request) {
    UUID tenantId = current.tenantId();
    requiredEvent(tenantId, eventId);
    ExpenseCategory category = requiredCategory(tenantId, request.categoryId());
    if (!category.isEnabled()) {
      throw new BusinessException("EXPENSE_CATEGORY_DISABLED", "Expense category is disabled");
    }
    Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant"));
    SalesEventExpense expense =
        expenses.save(
            new SalesEventExpense(
                tenantId,
                eventId,
                category.getId(),
                request.amount(),
                tenant.getDefaultCurrency().toUpperCase(Locale.ROOT),
                request.expenseDate(),
                blankToNull(request.note()),
                current.userId()));
    audit.record(
        tenantId,
        "EVENT_EXPENSE_CREATE",
        "EVENT_EXPENSE",
        expense.getId(),
        "SUCCESS",
        snapshot(expense, category));
    return Response.from(expense, category);
  }

  @PutMapping("/{id}")
  @Transactional
  public Response update(
      @PathVariable UUID eventId,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateRequest request) {
    UUID tenantId = current.tenantId();
    requiredEvent(tenantId, eventId);
    SalesEventExpense expense = requiredExpense(tenantId, eventId, id);
    requireActiveVersion(expense, request.version());
    ExpenseCategory category = requiredCategory(tenantId, request.categoryId());
    if (!category.isEnabled() && !category.getId().equals(expense.getCategoryId())) {
      throw new BusinessException("EXPENSE_CATEGORY_DISABLED", "Expense category is disabled");
    }
    ExpenseCategory beforeCategory = requiredCategory(tenantId, expense.getCategoryId());
    Map<String, Object> before = snapshot(expense, beforeCategory);
    expense.update(
        category.getId(),
        request.amount(),
        request.expenseDate(),
        blankToNull(request.note()),
        current.userId());
    expenses.flush();
    audit.record(
        tenantId,
        "EVENT_EXPENSE_UPDATE",
        "EVENT_EXPENSE",
        id,
        "SUCCESS",
        Map.of("before", before, "after", snapshot(expense, category)));
    return Response.from(expense, category);
  }

  @PostMapping("/{id}/void")
  @Transactional
  public Response voidExpense(
      @PathVariable UUID eventId, @PathVariable UUID id, @Valid @RequestBody VoidRequest request) {
    UUID tenantId = current.tenantId();
    SalesEventExpense expense = requiredExpense(tenantId, eventId, id);
    requireActiveVersion(expense, request.version());
    ExpenseCategory category = requiredCategory(tenantId, expense.getCategoryId());
    Map<String, Object> before = snapshot(expense, category);
    expense.voidExpense(current.userId());
    expenses.flush();
    audit.record(
        tenantId, "EVENT_EXPENSE_VOID", "EVENT_EXPENSE", id, "SUCCESS", Map.of("before", before));
    return Response.from(expense, category);
  }

  private SalesEvent requiredEvent(UUID tenantId, UUID eventId) {
    return events
        .findByIdAndTenantId(eventId, tenantId)
        .orElseThrow(() -> new NotFoundException("Sales event"));
  }

  private ExpenseCategory requiredCategory(UUID tenantId, UUID categoryId) {
    return categories
        .findByIdAndTenantId(categoryId, tenantId)
        .orElseThrow(() -> new NotFoundException("Expense category"));
  }

  private SalesEventExpense requiredExpense(UUID tenantId, UUID eventId, UUID id) {
    return expenses
        .findByIdAndTenantIdAndEventId(id, tenantId, eventId)
        .orElseThrow(() -> new NotFoundException("Event expense"));
  }

  private void requireActiveVersion(SalesEventExpense expense, long version) {
    if (expense.getStatus() != ExpenseStatus.ACTIVE) {
      throw new BusinessException(
          "EVENT_EXPENSE_VOIDED", "Event expense is already voided", HttpStatus.CONFLICT);
    }
    if (expense.getVersion() != version) {
      throw new BusinessException(
          "VERSION_CONFLICT", "Event expense changed while editing", HttpStatus.CONFLICT);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private Map<String, Object> snapshot(SalesEventExpense expense, ExpenseCategory category) {
    return Map.of(
        "eventId", expense.getEventId(),
        "category", category.getName(),
        "amount", expense.getAmount(),
        "currency", expense.getCurrency(),
        "expenseDate", expense.getExpenseDate(),
        "note", expense.getNote() == null ? "" : expense.getNote());
  }

  public record Request(
      @NotNull UUID categoryId,
      @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
      @NotNull LocalDate expenseDate,
      @Size(max = 2000) String note) {}

  public record UpdateRequest(
      @NotNull UUID categoryId,
      @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
      @NotNull LocalDate expenseDate,
      @Size(max = 2000) String note,
      @NotNull @Min(0) Long version) {}

  public record VoidRequest(@NotNull @Min(0) Long version) {}

  public record Response(
      UUID id,
      UUID eventId,
      UUID categoryId,
      String categoryName,
      BigDecimal amount,
      String currency,
      LocalDate expenseDate,
      String note,
      String status,
      UUID createdBy,
      UUID updatedBy,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    static Response from(SalesEventExpense expense, ExpenseCategory category) {
      return new Response(
          expense.getId(),
          expense.getEventId(),
          expense.getCategoryId(),
          category == null ? null : category.getName(),
          expense.getAmount(),
          expense.getCurrency(),
          expense.getExpenseDate(),
          expense.getNote(),
          expense.getStatus().name(),
          expense.getCreatedBy(),
          expense.getUpdatedBy(),
          expense.getVersion(),
          expense.getCreatedAt(),
          expense.getUpdatedAt());
    }
  }
}
