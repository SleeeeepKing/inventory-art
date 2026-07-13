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
import com.inventoryart.product.ProductFamily;
import com.inventoryart.product.ProductFamilyRepository;
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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
  @Autowired ProductFamilyRepository productFamilies;
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
            "id",
            "tenant_id",
            "event_id",
            "attributed_date",
            "operator_id",
            "created_at",
            "status",
            "updated_by",
            "updated_at",
            "cancelled_by",
            "cancelled_at",
            "version");
    assertThat(columns("inventory_sale_lines"))
        .containsExactlyInAnyOrder(
            "id",
            "tenant_id",
            "sale_batch_id",
            "product_id",
            "quantity",
            "created_at",
            "updated_at");
    assertThat(columns("expense_categories"))
        .containsExactlyInAnyOrder(
            "id", "tenant_id", "name", "enabled", "created_at", "updated_at", "version");
    assertThat(columns("sales_event_expenses"))
        .containsExactlyInAnyOrder(
            "id",
            "tenant_id",
            "event_id",
            "category_id",
            "amount",
            "currency",
            "expense_date",
            "note",
            "status",
            "created_by",
            "created_at",
            "updated_by",
            "updated_at",
            "voided_by",
            "voided_at",
            "version");
    assertThat(columns("stored_files"))
        .contains(
            "product_family_id",
            "preview_object_key",
            "preview_content_type",
            "preview_size",
            "preview_checksum")
        .doesNotContain("purpose", "resource_type", "resource_id", "product_id");
    assertThat(columns("product_families"))
        .containsExactlyInAnyOrder(
            "id",
            "tenant_id",
            "name",
            "category",
            "artist_name",
            "description",
            "image_object_key",
            "version",
            "created_at",
            "updated_at");
    assertThat(columns("products"))
        .containsExactlyInAnyOrder(
            "id",
            "tenant_id",
            "sku",
            "family_id",
            "variant_name",
            "current_stock",
            "low_stock_threshold",
            "enabled",
            "version",
            "created_at",
            "updated_at");
  }

  @Test
  void v10BackfillsCurrentSaleLinesWithoutChangingStock() {
    String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
    String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway v9 =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("9"))
            .load();
    v9.migrate();
    JdbcTemplate migration = new JdbcTemplate(dataSource);
    UUID tenantId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    migration.update(
        "insert into tenants(id,name,slug,default_currency,timezone,locale,enabled,created_at,updated_at) values (?,?,?,'EUR','Europe/Paris','en',true,now(),now())",
        tenantId,
        "Migration tenant",
        "migration-" + tenantId);
    migration.update(
        "insert into users(id,tenant_id,username,email,password_hash,display_name,role,preferred_locale,enabled,created_at,updated_at) values (?,?,?,?,?,?, 'USER','en',true,now(),now())",
        userId,
        tenantId,
        "migration-" + userId,
        userId + "@migration.test",
        "hash",
        "Migration user");
    migration.update(
        "insert into products(id,tenant_id,sku,name,sale_price,currency,current_stock,low_stock_threshold,enabled,version,created_at,updated_at) values (?,?,?,'Migration product',10,'EUR',7,0,true,0,now(),now())",
        productId,
        tenantId,
        "MIG-" + productId);
    migration.update(
        "insert into sales_events(id,tenant_id,name,enabled,created_at,updated_at,start_date,end_date) values (?,?, 'Migration expo',true,now(),now(),'2026-07-10','2026-07-12')",
        eventId,
        tenantId);
    migration.update(
        "insert into inventory_sale_batches(id,tenant_id,event_id,attributed_date,operator_id,created_at) values (?,?,?,'2026-07-12',?,now())",
        batchId,
        tenantId,
        eventId,
        userId);
    migration.update(
        "insert into inventory_movements(id,tenant_id,product_id,movement_type,quantity,stock_before,stock_after,sale_batch_id,reference,operator_id,created_at) values (?,?,?,?,?,?,?,?,?,?,now()),(?,?,?,?,?,?,?,?,?,?,now())",
        UUID.randomUUID(),
        tenantId,
        productId,
        "SALE",
        -2,
        10,
        8,
        batchId,
        "Sale one",
        userId,
        UUID.randomUUID(),
        tenantId,
        productId,
        "SALE",
        -1,
        8,
        7,
        batchId,
        "Sale two",
        userId);

    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .load()
        .migrate();

    assertThat(
            migration.queryForObject(
                "select quantity from inventory_sale_lines where tenant_id=? and sale_batch_id=? and product_id=?",
                Integer.class,
                tenantId,
                batchId,
                productId))
        .isEqualTo(3);
    assertThat(
            migration.queryForObject(
                "select current_stock from products where tenant_id=? and id=?",
                Integer.class,
                tenantId,
                productId))
        .isEqualTo(7);
    assertThat(
            migration.queryForObject(
                "select status from inventory_sale_batches where tenant_id=? and id=?",
                String.class,
                tenantId,
                batchId))
        .isEqualTo("ACTIVE");
    assertThat(
            migration.queryForObject(
                "select family_id from products where tenant_id=? and id=?",
                UUID.class,
                tenantId,
                productId))
        .isEqualTo(productId);
    assertThat(
            migration.queryForObject(
                "select name from product_families where tenant_id=? and id=?",
                String.class,
                tenantId,
                productId))
        .isEqualTo("Migration product");
    assertThat(migrationColumns(migration, "products"))
        .doesNotContain(
            "name",
            "category",
            "artist_name",
            "description",
            "image_object_key",
            "sale_price",
            "cost_price",
            "currency");
    jdbc.execute("drop schema " + schema + " cascade");
  }

  @Test
  void productListCombinesLowStockAndCategoryFiltersWithinTheCurrentTenant() throws Exception {
    Fixture owner = fixture("product-filter-owner");
    Fixture other = fixture("product-filter-other");
    Product lowPrint = product(owner, "LOW-PRINT", "Print", 1);
    Product healthyPrint = product(owner, "HEALTHY-PRINT", "Print", 5);
    Product lowSculpture = product(owner, "LOW-SCULPTURE", "Sculpture", 1);
    product(other, "OTHER-PHOTO", "Photography", 1);

    mvc.perform(
            get("/api/v1/products")
                .with(userJwt(owner))
                .param("lowStock", "true")
                .param("categories", "print", "Sculpture"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(
            jsonPath("$.items[*].id")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        lowPrint.getId().toString(), lowSculpture.getId().toString())));

    mvc.perform(
            get("/api/v1/products")
                .with(userJwt(owner))
                .param("lowStock", "false")
                .param("categories", "Print"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].id").value(healthyPrint.getId().toString()));

    mvc.perform(get("/api/v1/products/categories").with(userJwt(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItems("Print", "Sculpture")))
        .andExpect(
            jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Photography"))));
  }

  @Test
  void productFamilyCreatesVariantsAtomicallyWithServerDefaultsAndTenantIsolation()
      throws Exception {
    Fixture owner = fixture("family-owner");
    Fixture other = fixture("family-other");
    String response =
        mvc.perform(
                post("/api/v1/product-families")
                    .with(userJwt(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name",
                                "Blue Garden",
                                "category",
                                "Print",
                                "artistName",
                                "Mina",
                                "variants",
                                List.of(
                                    Map.of("variantName", "A5", "sku", "BLUE-A5"),
                                    Map.of(
                                        "variantName",
                                        "A4",
                                        "sku",
                                        "BLUE-A4",
                                        "initialStock",
                                        0,
                                        "lowStockThreshold",
                                        0))))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Blue Garden"))
            .andExpect(jsonPath("$.variants.length()").value(2))
            .andExpect(jsonPath("$.variants[0].currentStock").value(0))
            .andExpect(jsonPath("$.variants[0].lowStockThreshold").value(0))
            .andExpect(jsonPath("$.variants[1].currentStock").value(999))
            .andExpect(jsonPath("$.variants[1].lowStockThreshold").value(5))
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID familyId = UUID.fromString(json.readTree(response).get("id").asText());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from products where tenant_id=? and family_id=?",
                Integer.class,
                owner.tenant().getId(),
                familyId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_movements where tenant_id=? and movement_type='INITIAL' and quantity=999",
                Integer.class,
                owner.tenant().getId()))
        .isEqualTo(1);
    mvc.perform(
            get("/api/v1/product-families")
                .with(userJwt(owner))
                .param("lowStock", "true")
                .param("categories", "print"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].id").value(familyId.toString()));
    mvc.perform(
            get("/api/v1/product-families")
                .with(userJwt(other))
                .param("lowStock", "true")
                .param("categories", "print"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/api/v1/product-families/{id}", familyId).with(userJwt(other)))
        .andExpect(status().isNotFound());

    mvc.perform(
            post("/api/v1/product-families")
                .with(userJwt(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name",
                            "Duplicate",
                            "variants",
                            List.of(
                                Map.of("variantName", "A5", "sku", "DUPLICATE"),
                                Map.of("variantName", "A4", "sku", "duplicate"))))))
        .andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from product_families where tenant_id=? and name='Duplicate'",
                Integer.class,
                owner.tenant().getId()))
        .isZero();
  }

  @Test
  void productFamilyImageUploadsOnceAndIsTenantScoped() throws Exception {
    Fixture owner = fixture("family-image-owner");
    Fixture other = fixture("family-image-other");
    String familyResponse =
        mvc.perform(
                post("/api/v1/product-families")
                    .with(userJwt(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name",
                                "Shared Image",
                                "variants",
                                List.of(Map.of("variantName", "A4", "sku", "SHARED-A4"))))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID familyId = UUID.fromString(json.readTree(familyResponse).get("id").asText());
    byte[] original = "shared-family-image".getBytes(StandardCharsets.UTF_8);
    byte[] preview = webpPreview(480, 320);

    String presignResponse =
        mvc.perform(
                post("/api/v1/files/presign")
                    .with(userJwt(owner))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "originalFilename",
                                "shared.png",
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
                                "productFamilyId",
                                familyId))))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.objectKey")
                    .value(
                        org.hamcrest.Matchers.matchesPattern(
                            "tenants/"
                                + owner.tenant().getSlug()
                                + "/product-families/"
                                + familyId
                                + "/[0-9a-f-]+/original\\.png")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode presigned = json.readTree(presignResponse);
    uploadLocal(presigned, "uploadUrl", "headers", original);
    uploadLocal(presigned, "previewUploadUrl", "previewHeaders", preview);
    UUID fileId = UUID.fromString(presigned.get("fileId").asText());
    mvc.perform(post("/api/v1/files/{fileId}/confirm", fileId).with(userJwt(owner)))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/product-families/{id}", familyId).with(userJwt(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imageUrl").value("/files/" + fileId + "/preview"));
    mvc.perform(get("/api/v1/files/{fileId}/preview", fileId).with(userJwt(other)))
        .andExpect(status().isNotFound());
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

    inventory.apply(
        owner.tenant().getId(),
        product.getId(),
        3,
        MovementType.ADJUSTMENT_IN,
        "report image test",
        null,
        owner.user().getId());
    SalesEvent event = event(owner, "Image Expo", "2026-07-10", "2026-07-12");
    createSale(owner, event, List.of(Map.of("productId", product.getId(), "quantity", 1)));
    mvc.perform(
            get("/api/v1/reports/inventory-sales")
                .with(userJwt(owner))
                .param("start", "2026-07-12")
                .param("end", "2026-07-12"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.byProduct[0].productImageUrl").value("/files/" + fileId + "/preview"));
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
  void inventorySaleCanBeEditedFilteredAndPermanentlyCancelled() throws Exception {
    Fixture fixture = fixture("sale-edit");
    Fixture other = fixture("sale-other");
    Product first = product(fixture, "EDIT-FIRST", "Print", 10);
    Product removed = product(fixture, "EDIT-REMOVED", "Sculpture", 5);
    Product replacement = product(fixture, "EDIT-REPLACEMENT", "Print", 8);
    Product otherProduct = product(other, "EDIT-OTHER", 20);
    SalesEvent originalEvent = event(fixture, "Original Expo", "2026-07-10", "2026-07-12");
    SalesEvent replacementEvent = event(fixture, "Historical Expo", "2026-07-18", "2026-07-20");
    replacementEvent.setEnabled(false);
    events.save(replacementEvent);
    mvc.perform(delete("/api/v1/products/{id}", replacement.getId()).with(userJwt(fixture)))
        .andExpect(status().isNoContent());

    JsonNode created =
        createSale(
            fixture,
            originalEvent,
            List.of(
                Map.of("productId", first.getId(), "quantity", 3),
                Map.of("productId", removed.getId(), "quantity", 1)));
    UUID saleId = UUID.fromString(created.get("id").asText());
    assertThat(created.get("version").asLong()).isZero();
    assertThat(products.findById(first.getId()).orElseThrow().getCurrentStock()).isEqualTo(7);
    assertThat(products.findById(removed.getId()).orElseThrow().getCurrentStock()).isEqualTo(4);

    mvc.perform(get("/api/v1/inventory/sales/{id}", saleId).with(userJwt(other)))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/api/v1/inventory/sales/{id}", saleId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "eventId",
                            replacementEvent.getId(),
                            "version",
                            0,
                            "items",
                            List.of(
                                Map.of("productId", first.getId(), "quantity", 5),
                                Map.of("productId", otherProduct.getId(), "quantity", 2))))))
        .andExpect(status().isNotFound());
    assertThat(products.findById(first.getId()).orElseThrow().getCurrentStock()).isEqualTo(7);

    String updatedBody =
        mvc.perform(
                put("/api/v1/inventory/sales/{id}", saleId)
                    .with(userJwt(fixture))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "eventId",
                                replacementEvent.getId(),
                                "version",
                                0,
                                "items",
                                List.of(
                                    Map.of("productId", first.getId(), "quantity", 5),
                                    Map.of("productId", replacement.getId(), "quantity", 2))))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventName").value("Historical Expo"))
            .andExpect(jsonPath("$.attributedDate").value("2026-07-20"))
            .andExpect(jsonPath("$.version").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode updated = json.readTree(updatedBody);
    assertThat(updated.get("items").size()).isEqualTo(2);
    assertThat(products.findById(first.getId()).orElseThrow().getCurrentStock()).isEqualTo(5);
    assertThat(products.findById(removed.getId()).orElseThrow().getCurrentStock()).isEqualTo(5);
    assertThat(products.findById(replacement.getId()).orElseThrow().getCurrentStock()).isEqualTo(6);

    mvc.perform(
            put("/api/v1/inventory/sales/{id}", saleId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "eventId",
                            replacementEvent.getId(),
                            "version",
                            0,
                            "items",
                            List.of(Map.of("productId", first.getId(), "quantity", 1))))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    mvc.perform(
            put("/api/v1/inventory/sales/{id}", saleId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "eventId",
                            replacementEvent.getId(),
                            "version",
                            1,
                            "items",
                            List.of(
                                Map.of("productId", first.getId(), "quantity", 100),
                                Map.of("productId", replacement.getId(), "quantity", 1))))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    assertThat(products.findById(first.getId()).orElseThrow().getCurrentStock()).isEqualTo(5);
    assertThat(products.findById(replacement.getId()).orElseThrow().getCurrentStock()).isEqualTo(6);

    mvc.perform(
            get("/api/v1/inventory/operations")
                .with(userJwt(fixture))
                .param("types", "ADJUSTMENT_IN", "SALE")
                .param("productIds", UUID.randomUUID().toString(), first.getId().toString())
                .param("productCategories", "Print")
                .param("eventId", replacementEvent.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].kind").value("SALE"))
        .andExpect(jsonPath("$.items[0].items.length()").value(2));
    mvc.perform(
            get("/api/v1/inventory/operations")
                .with(userJwt(fixture))
                .param("types", "SALE")
                .param("productIds", first.getId().toString())
                .param("productCategories", "Sculpture"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/v1/reports/inventory-sales")
                .with(userJwt(fixture))
                .param("start", "2026-07-20")
                .param("end", "2026-07-20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.units").value(7))
        .andExpect(jsonPath("$.summary.batches").value(1));
    assertThat(
            mvc.perform(get("/api/v1/inventory/export").with(userJwt(fixture)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .contains(",SALE,-5,")
        .contains(",SALE,-2,")
        .doesNotContain("SALE_CORRECTION");

    String cancelledBody =
        mvc.perform(
                post("/api/v1/inventory/sales/{id}/cancel", saleId)
                    .with(userJwt(fixture))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("version", 1))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.version").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long cancelledVersion = json.readTree(cancelledBody).get("version").asLong();
    assertThat(products.findById(first.getId()).orElseThrow().getCurrentStock()).isEqualTo(10);
    assertThat(products.findById(removed.getId()).orElseThrow().getCurrentStock()).isEqualTo(5);
    assertThat(products.findById(replacement.getId()).orElseThrow().getCurrentStock()).isEqualTo(8);
    mvc.perform(
            post("/api/v1/inventory/sales/{id}/cancel", saleId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", cancelledVersion))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVENTORY_SALE_CANCELLED"));
    mvc.perform(get("/api/v1/inventory/operations").with(userJwt(fixture)).param("types", "SALE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/v1/reports/inventory-sales")
                .with(userJwt(fixture))
                .param("start", "2026-07-20")
                .param("end", "2026-07-20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.units").value(0))
        .andExpect(jsonPath("$.byProduct.length()").value(0));
    assertThat(
            mvc.perform(get("/api/v1/inventory/export").with(userJwt(fixture)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .doesNotContain(",SALE,")
        .doesNotContain("SALE_REVERSAL");
  }

  @Test
  void eventExpensesSupportSharedCategoriesReportsVoidingAndTenantIsolation() throws Exception {
    Fixture fixture = fixture("expense");
    Fixture other = fixture("expense-other");
    SalesEvent event = event(fixture, "Expense Only Expo", "2026-07-10", "2026-07-12");
    SalesEvent otherEvent = event(other, "Other Expo", "2026-07-10", "2026-07-12");

    String categoryBody =
        mvc.perform(
                post("/api/v1/expense-categories")
                    .with(userJwt(fixture))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "Travel"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID categoryId = UUID.fromString(json.readTree(categoryBody).get("id").asText());
    mvc.perform(
            post("/api/v1/expense-categories")
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", " travel "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_EXPENSE_CATEGORY"));
    mvc.perform(
            put("/api/v1/expense-categories/{id}", categoryId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("name", "Travel", "enabled", false, "version", 0))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            post("/api/v1/sales-events/{eventId}/expenses", event.getId())
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(expenseBody(categoryId, "12.50", "disabled")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EXPENSE_CATEGORY_DISABLED"));
    mvc.perform(
            put("/api/v1/expense-categories/{id}", categoryId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("name", "Transport", "enabled", true, "version", 1))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));

    mvc.perform(
            post("/api/v1/sales-events/{eventId}/expenses", event.getId())
                .with(userJwt(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(expenseBody(categoryId, "12.50", "cross tenant event")))
        .andExpect(status().isNotFound());
    String otherCategoryBody =
        mvc.perform(
                post("/api/v1/expense-categories")
                    .with(userJwt(other))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "Other category"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID otherCategoryId = UUID.fromString(json.readTree(otherCategoryBody).get("id").asText());
    mvc.perform(
            post("/api/v1/sales-events/{eventId}/expenses", event.getId())
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(expenseBody(otherCategoryId, "12.50", "cross tenant category")))
        .andExpect(status().isNotFound());

    String expenseResponse =
        mvc.perform(
                post("/api/v1/sales-events/{eventId}/expenses", event.getId())
                    .with(userJwt(fixture))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(expenseBody(categoryId, "20.00", "Train")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.categoryName").value("Transport"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID expenseId = UUID.fromString(json.readTree(expenseResponse).get("id").asText());
    mvc.perform(
            get("/api/v1/reports/dashboard")
                .with(userJwt(fixture))
                .param("start", "2026-07-12")
                .param("end", "2026-07-12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currencies[0].totalSales").value(0))
        .andExpect(jsonPath("$.currencies[0].totalExpenses").value(20))
        .andExpect(jsonPath("$.currencies[0].balance").value(-20))
        .andExpect(jsonPath("$.byEvent[0].label").value("Expense Only Expo"))
        .andExpect(jsonPath("$.byEvent[0].transactions").value(0))
        .andExpect(jsonPath("$.byEvent[0].expenseCount").value(1))
        .andExpect(jsonPath("$.expensesByCategory[0].label").value("Transport"));

    mvc.perform(
            put("/api/v1/sales-events/{eventId}/expenses/{id}", event.getId(), expenseId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(expenseUpdateBody(categoryId, "25.00", "Hotel", 0)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(25))
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            put("/api/v1/sales-events/{eventId}/expenses/{id}", event.getId(), expenseId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(expenseUpdateBody(categoryId, "30.00", "Stale", 0)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    mvc.perform(delete("/api/v1/sales-events/{id}", event.getId()).with(userJwt(fixture)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SALES_EVENT_IN_USE"));

    mvc.perform(
            post("/api/v1/sales-events/{eventId}/expenses/{id}/void", event.getId(), expenseId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", 1))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOIDED"))
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(
            post("/api/v1/sales-events/{eventId}/expenses/{id}/void", event.getId(), expenseId)
                .with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", 2))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EVENT_EXPENSE_VOIDED"));
    mvc.perform(
            get("/api/v1/sales-events/{eventId}/expenses", event.getId()).with(userJwt(fixture)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/v1/sales-events/{eventId}/expenses", event.getId())
                .with(userJwt(fixture))
                .param("includeVoided", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            get("/api/v1/sales-events/{eventId}/expenses", otherEvent.getId())
                .with(userJwt(fixture)))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/reports/dashboard")
                .with(userJwt(fixture))
                .param("start", "2026-07-12")
                .param("end", "2026-07-12"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currencies[0].totalExpenses").value(0))
        .andExpect(jsonPath("$.currencies[0].balance").value(0))
        .andExpect(jsonPath("$.byEvent.length()").value(0))
        .andExpect(jsonPath("$.expensesByCategory.length()").value(0));
    mvc.perform(delete("/api/v1/sales-events/{id}", event.getId()).with(userJwt(fixture)))
        .andExpect(status().isConflict());
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

  private List<String> migrationColumns(JdbcTemplate migration, String table) {
    return migration.queryForList(
        "select column_name from information_schema.columns where table_schema=current_schema() and table_name=?",
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

  private JsonNode createSale(Fixture fixture, SalesEvent event, Object items) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/inventory/sales")
                    .with(userJwt(fixture))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("eventId", event.getId(), "items", items))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response);
  }

  private String expenseBody(UUID categoryId, String amount, String note) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "categoryId",
            categoryId,
            "amount",
            new BigDecimal(amount),
            "expenseDate",
            "2026-07-11",
            "note",
            note));
  }

  private String expenseUpdateBody(UUID categoryId, String amount, String note, long version)
      throws Exception {
    return json.writeValueAsString(
        Map.of(
            "categoryId",
            categoryId,
            "amount",
            new BigDecimal(amount),
            "expenseDate",
            "2026-07-11",
            "note",
            note,
            "version",
            version));
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
    return product(fixture, sku, "Test", stock);
  }

  private Product product(Fixture fixture, String sku, String category, int stock) {
    ProductFamily family =
        productFamilies.save(
            new ProductFamily(
                UUID.randomUUID(), fixture.tenant().getId(), "Product", category, null, null));
    Product product =
        products.save(
            new Product(
                UUID.randomUUID(),
                family,
                null,
                sku + UUID.randomUUID().toString().substring(0, 4),
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
    ProductFamily family =
        productFamilies.save(
            new ProductFamily(
                UUID.randomUUID(), fixture.tenant().getId(), "Product", "Test", null, null));
    return products.save(new Product(UUID.randomUUID(), family, null, sku, 2));
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
