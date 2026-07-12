package com.inventoryart.event;

import com.inventoryart.audit.AuditService;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/sales-events")
public class SalesEventController {
  private final SalesEventRepository events;
  private final CurrentUserService current;
  private final AuditService audit;

  public SalesEventController(
      SalesEventRepository events, CurrentUserService current, AuditService audit) {
    this.events = events;
    this.current = current;
    this.audit = audit;
  }

  @GetMapping
  public List<Response> list(@RequestParam(defaultValue = "false") boolean includeDisabled) {
    UUID tenantId = current.tenantId();
    List<SalesEvent> result =
        includeDisabled
            ? events.findAllByTenantIdOrderByNameAsc(tenantId)
            : events.findAllByTenantIdAndEnabledOrderByNameAsc(tenantId, true);
    return result.stream().map(Response::from).toList();
  }

  @GetMapping("/{id}")
  public Response get(@PathVariable UUID id) {
    return Response.from(
        events
            .findByIdAndTenantId(id, current.tenantId())
            .orElseThrow(() -> new NotFoundException("Sales event")));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Response create(@Valid @RequestBody CreateRequest request) {
    UUID tenantId = current.tenantId();
    String name = request.name().trim();
    validateDates(request.startDate(), request.endDate());
    ensureUnique(tenantId, name, null);
    SalesEvent event =
        events.save(
            new SalesEvent(
                UUID.randomUUID(), tenantId, name, request.startDate(), request.endDate()));
    audit.record(
        tenantId,
        "SALES_EVENT_CREATE",
        "SALES_EVENT",
        event.getId(),
        "SUCCESS",
        Map.of("name", name));
    return Response.from(event);
  }

  @PutMapping("/{id}")
  @Transactional
  public Response update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
    UUID tenantId = current.tenantId();
    SalesEvent event =
        events
            .findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new NotFoundException("Sales event"));
    String name = request.name().trim();
    validateDates(request.startDate(), request.endDate());
    ensureUnique(tenantId, name, id);
    event.update(name, request.startDate(), request.endDate(), request.enabled());
    audit.record(
        tenantId, "SALES_EVENT_UPDATE", "SALES_EVENT", id, "SUCCESS", Map.of("name", name));
    return Response.from(event);
  }

  @PostMapping("/{id}/enabled")
  @Transactional
  public Response enabled(@PathVariable UUID id, @RequestBody EnabledRequest request) {
    UUID tenantId = current.tenantId();
    SalesEvent event =
        events
            .findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new NotFoundException("Sales event"));
    event.setEnabled(request.enabled());
    audit.record(
        tenantId,
        request.enabled() ? "SALES_EVENT_ENABLE" : "SALES_EVENT_DISABLE",
        "SALES_EVENT",
        id,
        "SUCCESS",
        Map.of());
    return Response.from(event);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  public void delete(@PathVariable UUID id) {
    UUID tenantId = current.tenantId();
    SalesEvent event =
        events
            .findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new NotFoundException("Sales event"));
    if (events.isReferenced(tenantId, id)) {
      throw new BusinessException(
          "SALES_EVENT_IN_USE",
          "This sales event has linked orders, imports, or inventory sales and cannot be deleted; disable it instead",
          HttpStatus.CONFLICT);
    }
    events.delete(event);
    audit.record(
        tenantId,
        "SALES_EVENT_DELETE",
        "SALES_EVENT",
        id,
        "SUCCESS",
        Map.of("name", event.getName()));
  }

  private void ensureUnique(UUID tenantId, String name, UUID currentId) {
    events
        .findByTenantIdAndNameIgnoreCase(tenantId, name)
        .filter(existing -> !existing.getId().equals(currentId))
        .ifPresent(
            existing -> {
              throw new BusinessException(
                  "DUPLICATE_EVENT_NAME", "Sales event name already exists");
            });
  }

  private void validateDates(LocalDate startDate, LocalDate endDate) {
    if (endDate.isBefore(startDate)) {
      throw new BusinessException(
          "INVALID_EVENT_DATE_RANGE", "Event end date must be on or after its start date");
    }
  }

  public record CreateRequest(
      @NotBlank @Size(max = 240) String name,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate) {}

  public record UpdateRequest(
      @NotBlank @Size(max = 240) String name,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      boolean enabled) {}

  public record EnabledRequest(boolean enabled) {}

  public record Response(
      UUID id,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt) {
    static Response from(SalesEvent event) {
      return new Response(
          event.getId(),
          event.getName(),
          event.getStartDate(),
          event.getEndDate(),
          event.isEnabled(),
          event.getCreatedAt(),
          event.getUpdatedAt());
    }
  }
}
