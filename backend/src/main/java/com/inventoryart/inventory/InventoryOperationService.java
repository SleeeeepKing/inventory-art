package com.inventoryart.inventory;

import com.inventoryart.common.PageResponse;
import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.product.Product;
import com.inventoryart.product.ProductRepository;
import com.inventoryart.storage.FileService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryOperationService {
  private final NamedParameterJdbcTemplate jdbc;
  private final InventoryMovementRepository movements;
  private final InventorySaleBatchRepository batches;
  private final InventorySaleLineRepository lines;
  private final ProductRepository products;
  private final SalesEventRepository events;
  private final FileService files;

  public InventoryOperationService(
      NamedParameterJdbcTemplate jdbc,
      InventoryMovementRepository movements,
      InventorySaleBatchRepository batches,
      InventorySaleLineRepository lines,
      ProductRepository products,
      SalesEventRepository events,
      FileService files) {
    this.jdbc = jdbc;
    this.movements = movements;
    this.batches = batches;
    this.lines = lines;
    this.products = products;
    this.events = events;
    this.files = files;
  }

  @Transactional(readOnly = true)
  public PageResponse<OperationResponse> search(
      UUID tenantId,
      List<MovementType> requestedTypes,
      List<UUID> productIds,
      List<String> productCategories,
      UUID eventId,
      int page,
      int size) {
    List<MovementType> types = requestedTypes == null ? List.of() : requestedTypes;
    List<String> movementTypes =
        types.stream()
            .filter(
                type ->
                    type != MovementType.SALE
                        && type != MovementType.SALE_CORRECTION
                        && type != MovementType.SALE_REVERSAL)
            .map(Enum::name)
            .toList();
    boolean filterTypes = !types.isEmpty();
    boolean includeSales = !filterTypes || types.contains(MovementType.SALE);
    boolean includeMovements = !filterTypes || !movementTypes.isEmpty();
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", size)
            .addValue("offset", page * size);

    String productFilter = productFilter(productIds, productCategories, params, "p");
    String movementWhere =
        "m.tenant_id=:tenantId and m.sale_batch_id is null"
            + (includeMovements ? "" : " and false")
            + (movementTypes.isEmpty() ? "" : " and m.movement_type in (:movementTypes)")
            + (eventId == null ? "" : " and false")
            + productFilter;
    if (!movementTypes.isEmpty()) params.addValue("movementTypes", movementTypes);

    String saleLineFilter = productFilter(productIds, productCategories, params, "sp");
    String saleWhere =
        "b.tenant_id=:tenantId and b.status='ACTIVE'"
            + (includeSales ? "" : " and false")
            + (eventId == null ? "" : " and b.event_id=:eventId")
            + (saleLineFilter.isBlank()
                ? ""
                : " and exists (select 1 from inventory_sale_lines sl join products sp on sp.tenant_id=sl.tenant_id and sp.id=sl.product_id where sl.tenant_id=b.tenant_id and sl.sale_batch_id=b.id"
                    + saleLineFilter
                    + ")");
    if (eventId != null) params.addValue("eventId", eventId);

    String cte =
        """
          with operation_keys as (
            select 'MOVEMENT' kind,m.id,m.created_at sort_at
              from inventory_movements m
              join products p on p.tenant_id=m.tenant_id and p.id=m.product_id
             where %s
            union all
            select 'SALE' kind,b.id,b.updated_at sort_at
              from inventory_sale_batches b
             where %s
          )
          """
            .formatted(movementWhere, saleWhere);
    long total =
        jdbc.queryForObject(cte + "select count(*) from operation_keys", params, Long.class);
    List<Key> keys =
        jdbc.query(
            cte
                + "select kind,id,sort_at from operation_keys order by sort_at desc,id desc limit :limit offset :offset",
            params,
            (rs, row) ->
                new Key(
                    rs.getString("kind"),
                    rs.getObject("id", UUID.class),
                    rs.getTimestamp("sort_at").toInstant()));
    List<UUID> movementIds =
        keys.stream().filter(key -> key.kind().equals("MOVEMENT")).map(Key::id).toList();
    List<UUID> batchIds =
        keys.stream().filter(key -> key.kind().equals("SALE")).map(Key::id).toList();
    Map<UUID, InventoryMovement> movementMap =
        movements.findAllByTenantIdAndIdIn(tenantId, movementIds).stream()
            .collect(Collectors.toMap(InventoryMovement::getId, Function.identity()));
    Map<UUID, InventorySaleBatch> batchMap =
        batches.findAllByTenantIdAndIdIn(tenantId, batchIds).stream()
            .collect(Collectors.toMap(InventorySaleBatch::getId, Function.identity()));
    Map<UUID, List<InventorySaleLine>> linesByBatch = new HashMap<>();
    lines
        .findAllByTenantIdAndSaleBatchIdIn(tenantId, batchIds)
        .forEach(
            line ->
                linesByBatch
                    .computeIfAbsent(line.getSaleBatchId(), ignored -> new ArrayList<>())
                    .add(line));

    List<UUID> allProductIds = new ArrayList<>();
    movementMap.values().forEach(movement -> allProductIds.add(movement.getProductId()));
    linesByBatch
        .values()
        .forEach(batchLines -> batchLines.forEach(line -> allProductIds.add(line.getProductId())));
    Map<UUID, Product> productMap =
        products
            .findAllByTenantIdAndIdIn(tenantId, allProductIds.stream().distinct().toList())
            .stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));
    Map<UUID, SalesEvent> eventMap =
        events
            .findAllByTenantIdAndIdIn(
                tenantId,
                batchMap.values().stream().map(InventorySaleBatch::getEventId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(SalesEvent::getId, Function.identity()));

    Map<UUID, OperationResponse> responses = new LinkedHashMap<>();
    movementMap
        .values()
        .forEach(
            movement ->
                responses.put(
                    movement.getId(), movement(movement, productMap.get(movement.getProductId()))));
    batchMap
        .values()
        .forEach(
            batch ->
                responses.put(
                    batch.getId(),
                    sale(
                        batch,
                        linesByBatch.getOrDefault(batch.getId(), List.of()),
                        productMap,
                        eventMap.get(batch.getEventId()))));
    List<OperationResponse> content = keys.stream().map(key -> responses.get(key.id())).toList();
    int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
    return new PageResponse<>(content, page, size, total, totalPages, "sortAt: DESC");
  }

  private String productFilter(
      List<UUID> productIds,
      List<String> productCategories,
      MapSqlParameterSource params,
      String alias) {
    StringBuilder sql = new StringBuilder();
    if (productIds != null && !productIds.isEmpty()) {
      params.addValue("productIds", productIds);
      sql.append(" and ").append(alias).append(".id in (:productIds)");
    }
    if (productCategories != null && !productCategories.isEmpty()) {
      params.addValue("productCategories", productCategories);
      sql.append(" and exists (select 1 from product_families filtered_family where ")
          .append("filtered_family.tenant_id=")
          .append(alias)
          .append(".tenant_id and filtered_family.id=")
          .append(alias)
          .append(".family_id and filtered_family.category in (:productCategories))");
    }
    return sql.toString();
  }

  private OperationResponse movement(InventoryMovement movement, Product product) {
    return new OperationResponse(
        movement.getId(),
        "MOVEMENT",
        movement.getMovementType().name(),
        movement.getQuantity(),
        movement.getStockBefore(),
        movement.getStockAfter(),
        null,
        null,
        null,
        null,
        null,
        movement.getReference(),
        movement.getRemark(),
        movement.getCreatedAt(),
        movement.getCreatedAt(),
        0,
        product == null
            ? List.of()
            : List.of(item(product, null, Math.abs(movement.getQuantity()))));
  }

  private OperationResponse sale(
      InventorySaleBatch batch,
      List<InventorySaleLine> saleLines,
      Map<UUID, Product> productMap,
      SalesEvent event) {
    List<ItemResponse> items =
        saleLines.stream()
            .sorted(Comparator.comparing(line -> line.getProductId().toString()))
            .map(
                line -> item(productMap.get(line.getProductId()), line.getId(), line.getQuantity()))
            .toList();
    int units = items.stream().mapToInt(ItemResponse::quantity).sum();
    return new OperationResponse(
        batch.getId(),
        "SALE",
        "SALE",
        -units,
        null,
        null,
        batch.getId(),
        batch.getEventId(),
        event == null ? null : event.getName(),
        batch.getAttributedDate(),
        batch.getStatus().name(),
        null,
        null,
        batch.getCreatedAt(),
        batch.getUpdatedAt(),
        batch.getVersion(),
        items);
  }

  private ItemResponse item(Product product, UUID lineId, int quantity) {
    if (product == null) return new ItemResponse(lineId, null, null, null, null, null, 0, quantity);
    return new ItemResponse(
        lineId,
        product.getId(),
        product.getDisplayName(),
        product.getSku(),
        product.getCategory(),
        files.productFamilyImageUrl(product.getFamilyId(), product.getImageObjectKey()),
        product.getCurrentStock(),
        quantity);
  }

  private record Key(String kind, UUID id, Instant sortAt) {}

  public record ItemResponse(
      UUID id,
      UUID productId,
      String productName,
      String productSku,
      String productCategory,
      String productImageUrl,
      int currentStock,
      int quantity) {}

  public record OperationResponse(
      UUID id,
      String kind,
      String type,
      int quantity,
      Integer stockBefore,
      Integer stockAfter,
      UUID saleBatchId,
      UUID eventId,
      String eventName,
      LocalDate attributedDate,
      String status,
      String reference,
      String remark,
      Instant createdAt,
      Instant updatedAt,
      long version,
      List<ItemResponse> items) {}
}
