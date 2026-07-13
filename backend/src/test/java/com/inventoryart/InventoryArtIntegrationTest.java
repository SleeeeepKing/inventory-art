package com.inventoryart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventoryart.audit.AuditLogRepository;
import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.inventory.InventoryService;
import com.inventoryart.inventory.MovementType;
import com.inventoryart.order.OrderRepository;
import com.inventoryart.product.Product;
import com.inventoryart.product.ProductRepository;
import com.inventoryart.tenant.Tenant;
import com.inventoryart.tenant.TenantRepository;
import com.inventoryart.user.User;
import com.inventoryart.user.UserRepository;
import com.inventoryart.user.UserRole;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    properties = {
      "app.seed.enabled=false",
      "app.storage.provider=local",
      "app.storage.local-path=target/test-storage"
    })
@AutoConfigureMockMvc
class InventoryArtIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TenantRepository tenants;
  @Autowired UserRepository users;
  @Autowired ProductRepository products;
  @Autowired SalesEventRepository events;
  @Autowired OrderRepository orders;
  @Autowired PasswordEncoder passwords;
  @Autowired InventoryService inventory;
  @Autowired AuditLogRepository audits;
  @Autowired JdbcTemplate jdbc;

  @Test
  void v8LeavesOnlyTheSimplifiedSalesSchema() {
    for (String table :
        List.of(
            "order_items",
            "payments",
            "order_refunds",
            "order_refund_items",
            "import_batches",
            "import_rows",
            "external_transactions",
            "external_product_mappings",
            "imported_sales_summaries",
            "imported_accounting_summaries")) {
      assertThat(jdbc.queryForObject("select to_regclass(?)", String.class, table)).isNull();
    }
    assertThat(columns("orders"))
        .containsExactlyInAnyOrder(
            "id",
            "tenant_id",
            "order_number",
            "event_id",
            "currency",
            "total_amount",
            "order_date",
            "created_by",
            "created_at",
            "updated_at",
            "version");
    assertThat(columns("inventory_sale_batches"))
        .containsExactlyInAnyOrder(
            "id", "tenant_id", "event_id", "attributed_date", "operator_id", "created_at");
    assertThat(columns("stored_files"))
        .contains(
            "product_id",
            "preview_object_key",
            "preview_content_type",
            "preview_size",
            "preview_checksum")
        .doesNotContain("purpose", "resource_type", "resource_id");
  }

  @Test
  void productImagesUseSlugAndSkuPathsAndExposeOnlyTenantScopedPreviews() throws Exception {
    Fixture owner = fixture("image-owner");
    Fixture other = fixture("image-other");
    Product product = productWithSku(owner, "ART / 001");
    byte[] original = "private-original-image".getBytes(StandardCharsets.UTF_8);
    byte[] preview = webpPreview(480, 320);

    String response =
        mvc.perform(
                post("/api/v1/files/presign")
                    .with(userJwt(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "originalFilename",
                                "art.png",
                                "contentType",
                                "image/png",
                                "size",
                                original.length,
                                "checksumSha256",
                                sha256(original),
                                "previewSize",
                                preview.length,
                                "previewChecksumSha256",
                                sha256(preview),
                                "productId",
                                product.getId()))))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.objectKey")
                    .value(
                        org.hamcrest.Matchers.matchesPattern(
                            "tenants/"
                                + owner.tenant().getSlug()
                                + "/products/ART%20%2F%20001/[0-9a-f-]+/original\\.png")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode presigned = json.readTree(response);
    uploadLocal(presigned, "uploadUrl", "headers", original);
    uploadLocal(presigned, "previewUploadUrl", "previewHeaders", preview);

    UUID fileId = UUID.fromString(presigned.get("fileId").asText());
    mvc.perform(post("/api/v1/files/{fileId}/confirm", fileId).with(userJwt(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));

    mvc.perform(get("/api/v1/products/{id}", product.getId()).with(userJwt(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imageUrl").value("/files/" + fileId + "/preview"))
        .andExpect(jsonPath("$.imageObjectKey").doesNotExist());

    byte[] downloadedPreview =
        mvc.perform(get("/api/v1/files/{fileId}/preview", fileId).with(userJwt(owner)))
            .andExpect(status().isOk())
            .andExpect(
                result -> assertThat(result.getResponse().getContentType()).isEqualTo("image/webp"))
            .andExpect(
                result ->
                    assertThat(result.getResponse().getHeader("Cache-Control"))
                        .contains("no-store", "private"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(downloadedPreview).isEqualTo(preview);

    mvc.perform(get("/api/v1/files/{fileId}/preview", fileId).with(userJwt(other)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/files/{fileId}/download-url", fileId).with(userJwt(owner)))
        .andExpect(status().isNotFound());
  }

  @Test
  void batchEntryUsesTenantCurrencyAndNormalizesToTheTenantHour() throws Exception {
    Fixture fixture = fixture("batch");
    SalesEvent event = event(fixture, "Paris Expo", "2026-07-10", "2026-07-12");
    String body =
        json.writeValueAsString(
            Map.of(
                "eventId",
                event.getId(),
                "orderDate",
                "2026-07-11T10:37:44Z",
                "orders",
                List.of(Map.of("totalAmount", 12.5), Map.of("totalAmount", 8))));

    mvc.perform(
            post("/api/v1/orders/batch")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.eventName").value("Paris Expo"))
        .andExpect(jsonPath("$.currency").value("EUR"))
        .andExpect(jsonPath("$.orderDate").value("2026-07-11T10:00:00Z"))
        .andExpect(jsonPath("$.orderCount").value(2))
        .andExpect(jsonPath("$.totalAmount").value(20.5));

    assertThat(
            orders
                .findAllByTenantId(
                    fixture.tenant().getId(), org.springframework.data.domain.Pageable.unpaged())
                .getTotalElements())
        .isEqualTo(2);
    assertThat(audits.findAll()).anyMatch(log -> log.getAction().equals("ORDER_BATCH_CREATE"));
  }

  @Test
  void transactionHourMustBelongToTheSelectedEvent() throws Exception {
    Fixture fixture = fixture("outside");
    SalesEvent event = event(fixture, "One Day", "2026-07-10", "2026-07-10");
    String body =
        json.writeValueAsString(
            Map.of(
                "eventId",
                event.getId(),
                "orderDate",
                "2026-07-11T10:00:00Z",
                "orders",
                List.of(Map.of("totalAmount", 10))));

    mvc.perform(
            post("/api/v1/orders/batch")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ORDER_OUTSIDE_EVENT"));
    assertThat(orders.existsByTenantId(fixture.tenant().getId())).isFalse();
  }

  @Test
  void batchEntryEnforcesPositiveAmountsAndTheOneHundredOrderLimitAtomically() throws Exception {
    Fixture fixture = fixture("limits");
    SalesEvent event = event(fixture, "Limit Expo", "2026-07-10", "2026-07-12");
    List<Map<String, Integer>> tooMany =
        java.util.stream.IntStream.range(0, 101)
            .mapToObj(index -> Map.of("totalAmount", index + 1))
            .toList();

    mvc.perform(
            post("/api/v1/orders/batch")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchBody(event, tooMany)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/orders/batch")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batchBody(event, List.of(Map.of("totalAmount", 10), Map.of("totalAmount", 0)))))
        .andExpect(status().isBadRequest());
    assertThat(orders.existsByTenantId(fixture.tenant().getId())).isFalse();

    List<Map<String, Integer>> maximum =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(index -> Map.of("totalAmount", index + 1))
            .toList();
    mvc.perform(
            post("/api/v1/orders/batch")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchBody(event, maximum)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderCount").value(100));
  }

  @Test
  void individualTransactionsCanBeEditedAndDeleted() throws Exception {
    Fixture fixture = fixture("edit");
    SalesEvent event = event(fixture, "Editable Expo", "2026-07-10", "2026-07-12");
    UUID orderId = createOrder(fixture, event, "2026-07-11T08:15:00Z", "11.00");
    String update =
        json.writeValueAsString(
            Map.of(
                "eventId", event.getId(), "orderDate", "2026-07-11T09:59:00Z", "totalAmount", 25));

    mvc.perform(
            put("/api/v1/orders/{id}", orderId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(update))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalAmount").value(25))
        .andExpect(jsonPath("$.orderDate").value("2026-07-11T09:00:00Z"))
        .andExpect(jsonPath("$.status").doesNotExist())
        .andExpect(jsonPath("$.paymentMethod").doesNotExist())
        .andExpect(jsonPath("$.items").doesNotExist());

    mvc.perform(delete("/api/v1/orders/{id}", orderId).with(userJwt(fixture)))
        .andExpect(status().isNoContent());
    assertThat(orders.findByIdAndTenantId(orderId, fixture.tenant().getId())).isEmpty();
  }

  @Test
  void selectedTransactionsCanBeDeletedAsOneAuditedBatch() throws Exception {
    Fixture fixture = fixture("bulk-delete");
    SalesEvent event = event(fixture, "Bulk Expo", "2026-07-10", "2026-07-12");
    UUID first = createOrder(fixture, event, "2026-07-11T08:15:00Z", "11.00");
    UUID second = createOrder(fixture, event, "2026-07-11T09:15:00Z", "12.00");
    UUID retained = createOrder(fixture, event, "2026-07-11T10:15:00Z", "13.00");

    mvc.perform(
            post("/api/v1/orders/bulk-delete")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ids", List.of(first, second)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(2));

    assertThat(orders.findById(first)).isEmpty();
    assertThat(orders.findById(second)).isEmpty();
    assertThat(orders.findById(retained)).isPresent();
    assertThat(audits.findAll())
        .anyMatch(log -> log.getAction().equals("ORDER_BULK_DELETE"))
        .filteredOn(log -> log.getAction().equals("ORDER_DELETE"))
        .extracting(log -> log.getResourceId())
        .contains(first, second);
  }

  @Test
  void bulkDeleteRejectsInvalidSelectionsAtomically() throws Exception {
    Fixture owner = fixture("bulk-owner");
    Fixture other = fixture("bulk-other");
    SalesEvent ownerEvent = event(owner, "Owner Expo", "2026-07-10", "2026-07-12");
    SalesEvent otherEvent = event(other, "Other Expo", "2026-07-10", "2026-07-12");
    UUID ownerOrder = createOrder(owner, ownerEvent, "2026-07-11T08:15:00Z", "11.00");
    UUID otherOrder = createOrder(other, otherEvent, "2026-07-11T08:15:00Z", "12.00");

    mvc.perform(
            post("/api/v1/orders/bulk-delete")
                .with(userJwt(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ids", List.of()))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/orders/bulk-delete")
                .with(userJwt(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ids", List.of(ownerOrder, ownerOrder)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_IN_BATCH"));
    mvc.perform(
            post("/api/v1/orders/bulk-delete")
                .with(userJwt(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ids", List.of(ownerOrder, otherOrder)))))
        .andExpect(status().isNotFound());

    assertThat(orders.findById(ownerOrder)).isPresent();
    assertThat(orders.findById(otherOrder)).isPresent();
  }

  @Test
  void administratorsAreRestrictedToAccountTenantAndAuditApis() throws Exception {
    User admin = admin("scope");

    for (String path :
        List.of("/api/v1/orders", "/api/v1/inventory", "/api/v1/reports/dashboard")) {
      mvc.perform(get(path).with(adminJwt(admin))).andExpect(status().isForbidden());
    }
    for (String path :
        List.of(
            "/api/v1/admin/products",
            "/api/v1/admin/orders",
            "/api/v1/admin/inventory/movements",
            "/api/v1/admin/sales-events",
            "/api/v1/admin/reports/dashboard",
            "/api/v1/admin/reports/inventory-sales")) {
      mvc.perform(get(path).with(adminJwt(admin))).andExpect(status().isNotFound());
    }

    mvc.perform(get("/api/v1/admin/tenants").with(adminJwt(admin))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/users").with(adminJwt(admin))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/audit").with(adminJwt(admin))).andExpect(status().isOk());
    mvc.perform(get("/api/v1/profile").with(adminJwt(admin))).andExpect(status().isOk());
  }

  @Test
  void inventorySalesStoreOnlyProductQuantities() throws Exception {
    Fixture fixture = fixture("quantity");
    Product product = product(fixture, "QTY", 10);
    SalesEvent event = event(fixture, "Quantity Expo", "2026-07-10", "2026-07-12");
    String body =
        json.writeValueAsString(
            Map.of(
                "eventId",
                event.getId(),
                "items",
                List.of(Map.of("productId", product.getId(), "quantity", 3))));

    mvc.perform(
            post("/api/v1/inventory/sales")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.eventName").value("Quantity Expo"))
        .andExpect(jsonPath("$.attributedDate").value("2026-07-12"))
        .andExpect(jsonPath("$.movements[0].quantity").value(-3))
        .andExpect(jsonPath("$.movements[0].unitPrice").doesNotExist())
        .andExpect(jsonPath("$.movements[0].salesChannel").doesNotExist());

    assertThat(products.findById(product.getId()).orElseThrow().getCurrentStock()).isEqualTo(7);
    mvc.perform(
            get("/api/v1/reports/inventory-sales")
                .with(userJwt(fixture))
                .param("start", "2026-07-10")
                .param("end", "2026-07-12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.units").value(3))
        .andExpect(jsonPath("$.byProduct[0].units").value(3))
        .andExpect(jsonPath("$.byProduct[0].attributedAmount").doesNotExist());
    mvc.perform(get("/api/v1/products/{id}", product.getId()).with(userJwt(fixture)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalUnitsSold").value(3))
        .andExpect(jsonPath("$.lastSaleDate").value("2026-07-12"))
        .andExpect(jsonPath("$.totalSalesRevenue").doesNotExist());
  }

  @Test
  void movementListAndExportSupportMovementsWithoutSaleBatch() throws Exception {
    Fixture fixture = fixture("movement");
    Product product = product(fixture, "MOVEMENT", 10);

    mvc.perform(
            get("/api/v1/inventory/movements")
                .with(userJwt(fixture))
                .param("productId", product.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].type").value("INITIAL"))
        .andExpect(jsonPath("$.items[0].productName").value(product.getName()))
        .andExpect(jsonPath("$.items[0].productSku").value(product.getSku()))
        .andExpect(jsonPath("$.items[0].saleBatchId").doesNotExist());

    String csv =
        mvc.perform(get("/api/v1/inventory/export").with(userJwt(fixture)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(csv).contains("," + product.getId() + ",INITIAL,");
  }

  @Test
  void inventorySaleRollsBackWhenAnyProductHasInsufficientStock() throws Exception {
    Fixture fixture = fixture("rollback");
    Product first = product(fixture, "FIRST", 4);
    Product second = product(fixture, "SECOND", 1);
    SalesEvent event = event(fixture, "Rollback Expo", "2026-07-10", "2026-07-12");
    String body =
        json.writeValueAsString(
            Map.of(
                "eventId",
                event.getId(),
                "items",
                List.of(
                    Map.of("productId", first.getId(), "quantity", 2),
                    Map.of("productId", second.getId(), "quantity", 2))));

    mvc.perform(
            post("/api/v1/inventory/sales")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    assertThat(products.findById(first.getId()).orElseThrow().getCurrentStock()).isEqualTo(4);
    assertThat(products.findById(second.getId()).orElseThrow().getCurrentStock()).isEqualTo(1);
  }

  @Test
  void inventorySaleRejectsDuplicateProductsWithoutCreatingAStockMovement() throws Exception {
    Fixture fixture = fixture("duplicate");
    Product product = product(fixture, "DUPLICATE", 5);
    SalesEvent event = event(fixture, "Duplicate Expo", "2026-07-10", "2026-07-12");
    String body =
        json.writeValueAsString(
            Map.of(
                "eventId",
                event.getId(),
                "items",
                List.of(
                    Map.of("productId", product.getId(), "quantity", 1),
                    Map.of("productId", product.getId(), "quantity", 2))));

    mvc.perform(
            post("/api/v1/inventory/sales")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_PRODUCT_IN_BATCH"));
    assertThat(products.findById(product.getId()).orElseThrow().getCurrentStock()).isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_sale_batches where tenant_id=?",
                Long.class,
                fixture.tenant().getId()))
        .isZero();
  }

  @Test
  void financialReportsContainOnlyRecordedTransactionsAndEventBreakdown() throws Exception {
    Fixture fixture = fixture("report");
    SalesEvent event = event(fixture, "Report Expo", "2026-07-10", "2026-07-12");
    createOrder(fixture, event, "2026-07-11T08:15:00Z", "10.00");
    createOrder(fixture, event, "2026-07-11T08:45:00Z", "20.00");

    mvc.perform(
            get("/api/v1/reports/dashboard")
                .with(userJwt(fixture))
                .param("start", "2026-07-11")
                .param("end", "2026-07-11")
                .param("granularity", "HOUR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currencies[0].totalSales").value(30))
        .andExpect(jsonPath("$.currencies[0].transactionCount").value(2))
        .andExpect(jsonPath("$.currencies[0].averageTransactionValue").value(15))
        .andExpect(jsonPath("$.salesTrend[0].transactions").value(2))
        .andExpect(jsonPath("$.byEvent[0].label").value("Report Expo"))
        .andExpect(jsonPath("$.currencies[0].refunds").doesNotExist())
        .andExpect(jsonPath("$.byPaymentMethod").doesNotExist());
  }

  @Test
  void removedSalesEndpointsReturnNotFound() throws Exception {
    Fixture fixture = fixture("removed");
    for (String path :
        List.of("/api/v1/payments", "/api/v1/imports/sumup", "/api/v1/external-transactions")) {
      mvc.perform(get(path).with(userJwt(fixture))).andExpect(status().isNotFound());
    }
    for (String path :
        List.of(
            "/api/v1/orders",
            "/api/v1/orders/batch-confirm",
            "/api/v1/orders/00000000-0000-0000-0000-000000000000/confirm",
            "/api/v1/orders/00000000-0000-0000-0000-000000000000/allocate",
            "/api/v1/orders/00000000-0000-0000-0000-000000000000/refunds")) {
      mvc.perform(post(path).with(userJwt(fixture))).andExpect(status().isNotFound());
    }
  }

  private List<String> columns(String table) {
    return jdbc.queryForList(
        "select column_name from information_schema.columns where table_schema='public' and table_name=?",
        String.class,
        table);
  }

  private UUID createOrder(Fixture fixture, SalesEvent event, String orderDate, String amount)
      throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "eventId",
                event.getId(),
                "orderDate",
                orderDate,
                "orders",
                List.of(Map.of("totalAmount", new BigDecimal(amount)))));
    String response =
        mvc.perform(
                post("/api/v1/orders/batch")
                    .with(userJwt(fixture))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("orders").get(0).get("id").asText());
  }

  private String batchBody(SalesEvent event, Object lines) throws Exception {
    return json.writeValueAsString(
        Map.of("eventId", event.getId(), "orderDate", "2026-07-11T10:30:00Z", "orders", lines));
  }

  private Fixture fixture(String prefix) {
    String nonce = UUID.randomUUID().toString().substring(0, 8);
    Tenant tenant =
        tenants.save(
            new Tenant(
                UUID.randomUUID(),
                prefix + nonce,
                prefix + "-" + nonce,
                "EUR",
                "Europe/Paris",
                "zh-CN"));
    User user =
        users.save(
            new User(
                UUID.randomUUID(),
                tenant.getId(),
                prefix + nonce,
                prefix + nonce + "@test.local",
                passwords.encode("ValidPassword123!"),
                prefix,
                UserRole.USER));
    return new Fixture(tenant, user);
  }

  private User admin(String prefix) {
    String nonce = UUID.randomUUID().toString().substring(0, 8);
    return users.save(
        new User(
            UUID.randomUUID(),
            null,
            prefix + nonce,
            prefix + nonce + "@test.local",
            passwords.encode("ValidPassword123!"),
            "Administrator",
            UserRole.ADMIN));
  }

  private SalesEvent event(Fixture fixture, String name, String start, String end) {
    return events.save(
        new SalesEvent(
            UUID.randomUUID(),
            fixture.tenant().getId(),
            name,
            LocalDate.parse(start),
            LocalDate.parse(end)));
  }

  private Product product(Fixture fixture, String sku, int stock) {
    Product product =
        products.save(
            new Product(
                UUID.randomUUID(),
                fixture.tenant().getId(),
                sku + UUID.randomUUID().toString().substring(0, 4),
                "Product",
                "Test",
                null,
                null,
                new BigDecimal("2.00"),
                new BigDecimal("10.00"),
                "EUR",
                2));
    inventory.apply(
        fixture.tenant().getId(),
        product.getId(),
        stock,
        MovementType.INITIAL,
        "test",
        null,
        fixture.user().getId());
    return product;
  }

  private Product productWithSku(Fixture fixture, String sku) {
    return products.save(
        new Product(
            UUID.randomUUID(),
            fixture.tenant().getId(),
            sku,
            "Product",
            "Test",
            null,
            null,
            new BigDecimal("2.00"),
            new BigDecimal("10.00"),
            "EUR",
            2));
  }

  private void uploadLocal(JsonNode presigned, String urlField, String headersField, byte[] content)
      throws Exception {
    var request = put(URI.create(presigned.get(urlField).asText())).content(content);
    presigned
        .get(headersField)
        .fields()
        .forEachRemaining(header -> request.header(header.getKey(), header.getValue().asText()));
    mvc.perform(request).andExpect(status().isNoContent());
  }

  private byte[] webpPreview(int width, int height) {
    byte[] bytes = new byte[30];
    ascii(bytes, 0, "RIFF");
    littleEndian(bytes, 4, 22, 4);
    ascii(bytes, 8, "WEBP");
    ascii(bytes, 12, "VP8X");
    littleEndian(bytes, 16, 10, 4);
    littleEndian(bytes, 24, width - 1, 3);
    littleEndian(bytes, 27, height - 1, 3);
    return bytes;
  }

  private void ascii(byte[] target, int offset, String value) {
    byte[] source = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(source, 0, target, offset, source.length);
  }

  private void littleEndian(byte[] target, int offset, int value, int length) {
    for (int index = 0; index < length; index++) {
      target[offset + index] = (byte) (value >>> (index * 8));
    }
  }

  private String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      userJwt(Fixture fixture) {
    return jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_USER"))
        .jwt(
            token ->
                token
                    .subject(fixture.user().getId().toString())
                    .claim("username", fixture.user().getUsername())
                    .claim("role", "USER")
                    .claim("tenantId", fixture.tenant().getId().toString()));
  }

  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .JwtRequestPostProcessor
      adminJwt(User admin) {
    return jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
        .jwt(
            token ->
                token
                    .subject(admin.getId().toString())
                    .claim("username", admin.getUsername())
                    .claim("role", "ADMIN"));
  }

  record Fixture(Tenant tenant, User user) {}
}
