package com.inventoryart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventoryart.audit.AuditLogRepository;
import com.inventoryart.inventory.*;
import com.inventoryart.order.*;
import com.inventoryart.payment.PaymentRepository;
import com.inventoryart.product.*;
import com.inventoryart.tenant.*;
import com.inventoryart.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties={"app.seed.enabled=false","app.storage.provider=local","app.storage.local-path=target/test-storage"})
@AutoConfigureMockMvc
class InventoryArtIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired TenantRepository tenants;@Autowired UserRepository users;@Autowired ProductRepository products;@Autowired PasswordEncoder passwords;@Autowired InventoryService inventory;@Autowired OrderService orderService;@Autowired OrderRepository orders;@Autowired OrderItemRepository orderItems;@Autowired AuditLogRepository audits;@Autowired PaymentRepository payments;

    @Test void loginRefreshAndLocalePersistence() throws Exception {
        Fixture f=fixture("auth");
        String body=json.writeValueAsString(Map.of("username",f.user.getUsername(),"password","ValidPassword123!"));
        var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString()).andExpect(jsonPath("$.user.preferredLocale").value("en")).andReturn();
        var oldCookie=Objects.requireNonNull(login.getResponse().getCookie("refresh_token"));
        var refreshed=mvc.perform(post("/api/v1/auth/refresh").cookie(oldCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString()).andReturn();
        var replacementCookie=Objects.requireNonNull(refreshed.getResponse().getCookie("refresh_token"));
        mvc.perform(post("/api/v1/auth/refresh").cookie(oldCookie)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(post("/api/v1/auth/refresh").cookie(replacementCookie)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(patch("/api/v1/profile").with(userJwt(f)).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Nom Français\",\"preferredLocale\":\"fr-FR\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.preferredLocale").value("fr-FR"));
        assertThat(users.findById(f.user.getId()).orElseThrow().getPreferredLocale()).isEqualTo("fr-FR");
    }

    @Test void localeValidationIsolationAndAdminDefaultAreEnforced() throws Exception {
        Fixture first=fixture("locale-first"),second=fixture("locale-second");
        mvc.perform(patch("/api/v1/profile").with(userJwt(first)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"First\",\"preferredLocale\":\"zh-CN\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.preferredLocale").value("zh-CN"));
        mvc.perform(get("/api/v1/profile").with(userJwt(second)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.preferredLocale").value("en"));
        mvc.perform(patch("/api/v1/profile").with(userJwt(first)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"First\",\"preferredLocale\":\"de\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_LOCALE"));

        User admin=users.save(new User(UUID.randomUUID(),null,"locale-admin-"+UUID.randomUUID(),"locale-admin-"+UUID.randomUUID()+"@test.local",passwords.encode("ValidPassword123!"),"Admin",UserRole.ADMIN));
        String nonce=UUID.randomUUID().toString().substring(0,8);
        String create=json.writeValueAsString(Map.of("tenantId",first.tenant.getId(),"username","friend-"+nonce,
            "email","friend-"+nonce+"@example.test","password","AnotherValid123!","displayName","Friend","role","USER"));
        mvc.perform(post("/api/v1/admin/users").with(adminJwt(admin)).contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.preferredLocale").value("en"));
    }

    @Test void usersChangeTheirOwnPasswordAndAdminsResetAnyPassword() throws Exception {
        Fixture f=fixture("password");String loginBody=json.writeValueAsString(Map.of("username",f.user.getUsername(),"password","ValidPassword123!"));
        var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody)).andExpect(status().isOk()).andReturn();
        var refreshCookie=Objects.requireNonNull(login.getResponse().getCookie("refresh_token"));
        mvc.perform(post("/api/v1/profile/password").with(userJwt(f)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"ChangedPassword123!\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
        mvc.perform(post("/api/v1/profile/password").with(userJwt(f)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"ValidPassword123!\",\"newPassword\":\"ChangedPassword123!\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody)).andExpect(status().isUnauthorized());
        String changedLogin=json.writeValueAsString(Map.of("username",f.user.getUsername(),"password","ChangedPassword123!"));
        var changedSession=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(changedLogin)).andExpect(status().isOk()).andReturn();
        var changedCookie=Objects.requireNonNull(changedSession.getResponse().getCookie("refresh_token"));

        User admin=users.save(new User(UUID.randomUUID(),null,"password-admin-"+UUID.randomUUID(),"password-admin-"+UUID.randomUUID()+"@test.local",passwords.encode("ValidPassword123!"),"Admin",UserRole.ADMIN));
        mvc.perform(post("/api/v1/admin/users/{id}/reset-password",f.user.getId()).with(adminJwt(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"AdminResetPassword123!\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(changedCookie))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        String resetLogin=json.writeValueAsString(Map.of("username",f.user.getUsername(),"password","AdminResetPassword123!"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(resetLogin)).andExpect(status().isOk());
        assertThat(audits.findAll()).anyMatch(a->a.getAction().equals("PASSWORD_CHANGE")&&a.getResourceId().equals(f.user.getId()));
        assertThat(audits.findAll()).anyMatch(a->a.getAction().equals("USER_PASSWORD_RESET")&&a.getResourceId().equals(f.user.getId()));
    }

    @Test void tenantResourcesAreNotDiscoverableAcrossUsers() throws Exception {
        Fixture a=fixture("tenant-a"),b=fixture("tenant-b");Product other=product(b,"OTHER-1",5);
        mvc.perform(get("/api/v1/products/{id}",other.getId()).with(userJwt(a))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(post("/api/v1/inventory/adjustments").with(userJwt(a)).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":\""+other.getId()+"\",\"type\":\"ADJUSTMENT_OUT\",\"quantity\":1}]}"))
            .andExpect(status().isNotFound());
        assertThat(products.findById(other.getId()).orElseThrow().getCurrentStock()).isEqualTo(5);
    }

    @Test void salesEventsAreTenantScopedFilterOrdersAndRejectDisabledEvents() throws Exception {
        Fixture first=fixture("event-first"),second=fixture("event-second");Product product=product(first,"EVENT",5);
        String created=mvc.perform(post("/api/v1/sales-events").with(userJwt(first)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"JAPAN EXPO PARIS 2026\",\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-05\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("JAPAN EXPO PARIS 2026"))
            .andReturn().getResponse().getContentAsString();
        UUID eventId=UUID.fromString(json.readTree(created).get("id").asText());
        String disposable=mvc.perform(post("/api/v1/sales-events").with(userJwt(first)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"One-off fair\",\"startDate\":\"2026-06-01\",\"endDate\":\"2026-06-01\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID disposableId=UUID.fromString(json.readTree(disposable).get("id").asText());
        mvc.perform(get("/api/v1/sales-events/{id}",disposableId).with(userJwt(first)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("One-off fair"));
        mvc.perform(delete("/api/v1/sales-events/{id}",disposableId).with(userJwt(second)))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/sales-events/{id}",disposableId).with(userJwt(first)))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/sales-events/{id}",disposableId).with(userJwt(first)))
            .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/sales-events").with(userJwt(second)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put("/api/v1/sales-events/{id}",eventId).with(userJwt(second)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Changed\",\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-05\",\"enabled\":true}"))
            .andExpect(status().isNotFound());

        String order=json.writeValueAsString(Map.of(
            "items",List.of(Map.of("productId",product.getId(),"quantity",1,"unitPrice",10,"discountAmount",0,"taxRate",0)),
            "totalAmount",10,"salesChannel","EXHIBITION","eventId",eventId,"currency","EUR",
            "paymentMethod","CARD","paymentStatus","PAID","orderDate",Instant.now().toString()));
        mvc.perform(post("/api/v1/orders").with(userJwt(first)).contentType(MediaType.APPLICATION_JSON).content(order))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.eventId").value(eventId.toString()))
            .andExpect(jsonPath("$.eventName").value("JAPAN EXPO PARIS 2026"))
            .andExpect(jsonPath("$.salesChannel").value("EXHIBITION"));
        mvc.perform(get("/api/v1/orders").param("eventId",eventId.toString()).with(userJwt(first)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].eventName").value("JAPAN EXPO PARIS 2026"));
        mvc.perform(delete("/api/v1/sales-events/{id}",eventId).with(userJwt(first)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SALES_EVENT_IN_USE"));

        mvc.perform(post("/api/v1/sales-events/{id}/enabled",eventId).with(userJwt(first)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/v1/sales-events").with(userJwt(first)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/orders").with(userJwt(first)).contentType(MediaType.APPLICATION_JSON).content(order))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SALES_EVENT_DISABLED"));
    }

    @Test void defaultProductSearchAndDashboardReportUsePostgresTypesSafely() throws Exception {
        Fixture f=fixture("api-defaults");product(f,"SEARCH-DEFAULT",3);
        mvc.perform(get("/api/v1/products").with(userJwt(f)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sku").exists());
        mvc.perform(get("/api/v1/orders").with(userJwt(f)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/inventory/movements").with(userJwt(f)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("INITIAL"));
        mvc.perform(get("/api/v1/external-transactions").with(userJwt(f)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/reports/dashboard").with(userJwt(f)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("Europe/Paris"));
    }

    @Test void batchOrdersAndInventorySalesShareEventWithoutDoubleCountingRevenue() throws Exception {
        Fixture fixture=fixture("event-sales");Product product=product(fixture,"EVENT-SALE",10);
        String eventJson=mvc.perform(post("/api/v1/sales-events").with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Paris Expo\",\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-05\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID eventId=UUID.fromString(json.readTree(eventJson).get("id").asText());
        String batch=json.writeValueAsString(Map.of(
            "eventId",eventId,"currency","EUR","paymentMethod","CASH","paymentStatus","PAID",
            "orderDate","2026-07-05T10:15:00Z",
            "orders",List.of(Map.of("totalAmount",10),Map.of("totalAmount",20))));
        mvc.perform(post("/api/v1/orders/batch").with(userJwt(fixture)).contentType(MediaType.APPLICATION_JSON).content(batch))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.orderCount").value(2))
            .andExpect(jsonPath("$.totalAmount").value(30));
        assertThat(products.findById(product.getId()).orElseThrow().getCurrentStock()).isEqualTo(10);

        String sale=json.writeValueAsString(Map.of(
            "salesChannel","EXHIBITION","eventId",eventId,"currency","EUR",
            "items",List.of(Map.of("productId",product.getId(),"quantity",2,"unitPrice",12.5))));
        mvc.perform(post("/api/v1/inventory/sales").with(userJwt(fixture)).contentType(MediaType.APPLICATION_JSON).content(sale))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.attributedDate").value("2026-07-05"))
            .andExpect(jsonPath("$.movements[0].attributedAmount").value(25));
        assertThat(products.findById(product.getId()).orElseThrow().getCurrentStock()).isEqualTo(8);

        mvc.perform(get("/api/v1/reports/dashboard").with(userJwt(fixture))
                .param("start","2026-07-05").param("end","2026-07-05").param("granularity","HOUR"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currencies[0].netSales").value(30))
            .andExpect(jsonPath("$.salesTrend[0].bucket").value("2026-07-05T12:00:00"));
        mvc.perform(get("/api/v1/reports/inventory-sales").with(userJwt(fixture))
                .param("start","2026-07-05").param("end","2026-07-05"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currencies[0].units").value(2))
            .andExpect(jsonPath("$.currencies[0].attributedAmount").value(25))
            .andExpect(jsonPath("$.byProduct[0].weightedAveragePrice").value(12.5));

        Product scarce=product(fixture,"SCARCE",1);
        String failingSale=json.writeValueAsString(Map.of(
            "salesChannel","EXHIBITION","eventId",eventId,"currency","EUR",
            "items",List.of(
                Map.of("productId",product.getId(),"quantity",1,"unitPrice",12.5),
                Map.of("productId",scarce.getId(),"quantity",2,"unitPrice",5))));
        mvc.perform(post("/api/v1/inventory/sales").with(userJwt(fixture))
                .contentType(MediaType.APPLICATION_JSON).content(failingSale))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(products.findById(product.getId()).orElseThrow().getCurrentStock()).isEqualTo(8);
        assertThat(products.findById(scarce.getId()).orElseThrow().getCurrentStock()).isEqualTo(1);
    }

    @Test void orderLifecycleNeverChangesInventory() {
        Fixture f=fixture("orders");Product p=product(f,"SKU-ORDER",10);
        OrderDtos.Request req=new OrderDtos.Request(List.of(new OrderDtos.ItemRequest(p.getId(),3,null,BigDecimal.ZERO,BigDecimal.ZERO)),new BigDecimal("30.00"),SalesChannel.ONLINE,null,null,"Customer",null,null,"EUR",PaymentMethod.CARD,PaymentStatus.PAID,Instant.now());
        var confirmed=orderService.create(f.tenant.getId(),f.user.getId(),req);
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(products.findById(p.getId()).orElseThrow().getCurrentStock()).isEqualTo(10);
        assertThat(payments.existsByTenantIdAndOrderId(f.tenant.getId(),confirmed.id())).isTrue();
        assertThatThrownBy(()->orderService.confirm(f.tenant.getId(),f.user.getId(),confirmed.id())).hasMessageContaining("already");
        var refund=orderService.refund(f.tenant.getId(),f.user.getId(),confirmed.id(),new OrderDtos.RefundRequest(List.of(new OrderDtos.RefundLine(confirmed.items().getFirst().id(),1)),null,"Returned"));
        assertThat(refund.status()).isEqualTo("PARTIALLY_REFUNDED");assertThat(products.findById(p.getId()).orElseThrow().getCurrentStock()).isEqualTo(10);
    }

    @Test void amountOnlyOrderCanBeCreatedAndRefunded() {
        Fixture f=fixture("amount-only");
        OrderDtos.Request request=new OrderDtos.Request(List.of(),new BigDecimal("42.50"),SalesChannel.EXHIBITION,null,"Art fair",null,null,null,"EUR",PaymentMethod.OTHER,PaymentStatus.PAID,Instant.now());
        var created=orderService.create(f.tenant.getId(),f.user.getId(),request);
        assertThat(created.status()).isEqualTo("CONFIRMED");
        assertThat(created.totalAmount()).isEqualByComparingTo("42.5000");
        assertThat(created.items()).isEmpty();
        var refunded=orderService.refund(f.tenant.getId(),f.user.getId(),created.id(),new OrderDtos.RefundRequest(List.of(),new BigDecimal("42.50"),"Customer refund"));
        assertThat(refunded.status()).isEqualTo("REFUNDED");
        assertThat(refunded.refundAmount()).isEqualByComparingTo("42.5000");
    }

    @Test void orderCreationDoesNotRequireAvailableStock() {
        Fixture f=fixture("stock");Product p=product(f,"LOW-1",1);OrderDtos.Request req=new OrderDtos.Request(List.of(new OrderDtos.ItemRequest(p.getId(),2,null,BigDecimal.ZERO,BigDecimal.ZERO)),new BigDecimal("20.00"),SalesChannel.OTHER,null,null,null,null,null,"EUR",PaymentMethod.CASH,PaymentStatus.PAID,Instant.now());
        orderService.create(f.tenant.getId(),f.user.getId(),req);assertThat(products.findById(p.getId()).orElseThrow().getCurrentStock()).isEqualTo(1);assertThat(orders.existsByTenantId(f.tenant.getId())).isTrue();
    }

    @Test void batchOrderActionsPartiallySucceedAndRemainTenantScoped() throws Exception {
        Fixture first=fixture("batch-first"),second=fixture("batch-second");Product firstProduct=product(first,"BATCH",10),secondProduct=product(second,"OTHER-BATCH",5);
        SalesOrder legacyDraft=draft(first,firstProduct,2);
        OrderDtos.Request otherRequest=new OrderDtos.Request(List.of(new OrderDtos.ItemRequest(secondProduct.getId(),1,null,BigDecimal.ZERO,BigDecimal.ZERO)),new BigDecimal("10.00"),SalesChannel.OTHER,null,null,null,null,null,"EUR",PaymentMethod.CASH,PaymentStatus.PAID,Instant.now());
        var otherOrder=orderService.create(second.tenant.getId(),second.user.getId(),otherRequest);

        UUID missing=UUID.randomUUID();
        String confirm=json.writeValueAsString(Map.of("orderIds",List.of(legacyDraft.getId(),missing)));
        mvc.perform(post("/api/v1/orders/batch-confirm").with(userJwt(first)).contentType(MediaType.APPLICATION_JSON).content(confirm))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.succeeded.length()").value(1))
            .andExpect(jsonPath("$.succeeded[0].id").value(legacyDraft.getId().toString()))
            .andExpect(jsonPath("$.failed.length()").value(1))
            .andExpect(jsonPath("$.failed[0].code").value("RESOURCE_NOT_FOUND"));
        assertThat(products.findById(firstProduct.getId()).orElseThrow().getCurrentStock()).isEqualTo(10);

        String cancel=json.writeValueAsString(Map.of("orderIds",List.of(legacyDraft.getId(),otherOrder.id())));
        mvc.perform(post("/api/v1/orders/batch-cancel").with(userJwt(first)).contentType(MediaType.APPLICATION_JSON).content(cancel))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.succeeded.length()").value(1))
            .andExpect(jsonPath("$.failed.length()").value(1))
            .andExpect(jsonPath("$.failed[0].code").value("RESOURCE_NOT_FOUND"));
        assertThat(products.findById(firstProduct.getId()).orElseThrow().getCurrentStock()).isEqualTo(10);
        assertThat(products.findById(secondProduct.getId()).orElseThrow().getCurrentStock()).isEqualTo(5);
    }

    @Test void productImagePresignUploadAndConfirmBindsThePrivateObject() throws Exception {
        Fixture f=fixture("image");Product p=product(f,"IMAGE",2);
        byte[] image="synthetic-png-test-content".getBytes(StandardCharsets.UTF_8);
        String checksum=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(image));
        String request=json.writeValueAsString(Map.of("originalFilename","test.png","contentType","image/png",
            "size",image.length,"checksumSha256",checksum,"productId",p.getId()));
        String presign=mvc.perform(post("/api/v1/files/presign").with(userJwt(f)).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        URI upload=URI.create(json.readTree(presign).get("uploadUrl").asText());
        UUID fileId=UUID.fromString(json.readTree(presign).get("fileId").asText());
        mvc.perform(put(upload).contentType("image/png").header("X-Content-Sha256",checksum).content(image))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/files/{id}/confirm",fileId).with(userJwt(f)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(get("/api/v1/products/{id}",p.getId()).with(userJwt(f)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").isString());
    }

    @Test void adminCanReadTenantsAndCreatesAudit() throws Exception {
        Fixture f=fixture("admin-target");User admin=users.save(new User(UUID.randomUUID(),null,"admin-"+UUID.randomUUID(),"admin-"+UUID.randomUUID()+"@test.local",passwords.encode("ValidPassword123!"),"Admin",UserRole.ADMIN));
        mvc.perform(get("/api/v1/admin/tenants/{id}",f.tenant.getId()).with(adminJwt(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/reports/dashboard").with(adminJwt(admin)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.defaultCurrency").value("MULTI"));
        assertThat(audits.findAll()).anyMatch(a->a.getAction().equals("ADMIN_TENANT_READ")&&f.tenant.getId().equals(a.getTenantId()));
    }

    @Test void adminOperationalViewsFilterBySystemUserAndRejectRegularUsers() throws Exception {
        Fixture fixture=fixture("admin-operations");Product product=product(fixture,"ADMIN-OPS",4);
        OrderDtos.Request request=new OrderDtos.Request(List.of(),new BigDecimal("18.00"),SalesChannel.ONLINE,
            null,null,null,null,null,"EUR",PaymentMethod.CARD,PaymentStatus.PAID,Instant.now());
        orderService.create(fixture.tenant.getId(),fixture.user.getId(),request);
        inventory.apply(fixture.tenant.getId(),product.getId(),1,MovementType.ADJUSTMENT_IN,
            null,null,"restock",null,fixture.user.getId());
        User admin=users.save(new User(UUID.randomUUID(),null,"operations-admin-"+UUID.randomUUID(),
            "operations-admin-"+UUID.randomUUID()+"@test.local",passwords.encode("ValidPassword123!"),
            "Admin",UserRole.ADMIN));
        mvc.perform(get("/api/v1/admin/orders").with(adminJwt(admin))
                .param("tenantId",fixture.tenant.getId().toString())
                .param("userId",fixture.user.getId().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].createdByName").value(fixture.user.getDisplayName()));
        mvc.perform(get("/api/v1/admin/inventory/movements").with(adminJwt(admin))
                .param("tenantId",fixture.tenant.getId().toString())
                .param("userId",fixture.user.getId().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.items[0].operatorName").value(fixture.user.getDisplayName()));
        mvc.perform(get("/api/v1/admin/reports/dashboard").with(adminJwt(admin))
                .param("granularity","HOUR"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TENANT_REQUIRED_FOR_HOURLY_REPORT"));
        mvc.perform(get("/api/v1/admin/orders").with(userJwt(fixture)))
            .andExpect(status().isForbidden());
    }

    @Test void disabledTenantBlocksLoginRefreshAndExistingAccessToken() throws Exception {
        Fixture f=fixture("disabled");String body=json.writeValueAsString(Map.of("username",f.user.getUsername(),"password","ValidPassword123!"));
        var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
        var refreshCookie=Objects.requireNonNull(login.getResponse().getCookie("refresh_token"));f.tenant.setEnabled(false);tenants.saveAndFlush(f.tenant);
        mvc.perform(get("/api/v1/products").with(userJwt(f))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_DISABLED"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test void concurrentStockDecrementAllowsExactlyOneWinner() throws Exception {
        Fixture f=fixture("concurrent");Product p=product(f,"ONLY-ONE",1);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        Callable<Boolean> decrement=()->{ready.countDown();start.await(5,TimeUnit.SECONDS);try{inventory.apply(f.tenant.getId(),p.getId(),-1,MovementType.SALE,null,null,"concurrency-test",null,f.user.getId());return true;}catch(com.inventoryart.exception.BusinessException ex){assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_STOCK");return false;}};
        Future<Boolean> first=pool.submit(decrement),second=pool.submit(decrement);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
        try{assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}finally{pool.shutdownNow();}
        assertThat(products.findById(p.getId()).orElseThrow().getCurrentStock()).isZero();
    }

    @Test void reportAndProductAggregatesRespectRefundedUnitsAndProductCost() throws Exception {
        Fixture f=fixture("report-cost");Product p=product(f,"REPORT-COST",10);
        OrderDtos.Request request=new OrderDtos.Request(
            List.of(new OrderDtos.ItemRequest(p.getId(),3,null,BigDecimal.ZERO,BigDecimal.ZERO)),
            new BigDecimal("30.00"),SalesChannel.EXHIBITION,null,"Summer fair",null,null,null,"EUR",PaymentMethod.CARD,PaymentStatus.PAID,Instant.now());
        var confirmed=orderService.create(f.tenant.getId(),f.user.getId(),request);
        orderService.refund(f.tenant.getId(),f.user.getId(),confirmed.id(),
            new OrderDtos.RefundRequest(List.of(new OrderDtos.RefundLine(confirmed.items().getFirst().id(),1)),null,"Returned"));

        mvc.perform(get("/api/v1/reports/dashboard").with(userJwt(f)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currencies[0].currency").value("EUR"))
            .andExpect(jsonPath("$.currencies[0].grossSales").value(30.0))
            .andExpect(jsonPath("$.currencies[0].refunds").value(10.0))
            .andExpect(jsonPath("$.currencies[0].netSales").value(20.0))
            .andExpect(jsonPath("$.currencies[0].productCost").value(4.0))
            .andExpect(jsonPath("$.currencies[0].estimatedGrossProfit").value(16.0))
            .andExpect(jsonPath("$.currencies[0].unitsSold").value(2))
            .andExpect(jsonPath("$.topProducts[0].currency").value("EUR"))
            .andExpect(jsonPath("$.topProducts[0].quantity").value(2))
            .andExpect(jsonPath("$.topProducts[0].revenue").value(20.0));

        mvc.perform(get("/api/v1/products/{id}",p.getId()).with(userJwt(f)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalUnitsSold").value(2))
            .andExpect(jsonPath("$.totalSalesRevenue").value(20.0))
            .andExpect(jsonPath("$.lastSaleAt").isString());
    }

    private Fixture fixture(String prefix){String nonce=UUID.randomUUID().toString().substring(0,8);Tenant t=tenants.save(new Tenant(UUID.randomUUID(),prefix+nonce,prefix+"-"+nonce,"EUR","Europe/Paris","zh-CN"));User u=users.save(new User(UUID.randomUUID(),t.getId(),prefix+nonce,prefix+nonce+"@test.local",passwords.encode("ValidPassword123!"),prefix,UserRole.USER));return new Fixture(t,u);}
    private Product product(Fixture f,String sku,int stock){Product p=products.save(new Product(UUID.randomUUID(),f.tenant.getId(),sku+UUID.randomUUID().toString().substring(0,4),"Product","Test",null,null,new BigDecimal("2.00"),new BigDecimal("10.00"),"EUR",2));inventory.apply(f.tenant.getId(),p.getId(),stock,MovementType.INITIAL,null,null,"test",null,f.user.getId());return p;}
    private SalesOrder draft(Fixture f,Product product,int quantity){UUID id=UUID.randomUUID();BigDecimal total=new BigDecimal("10.00").multiply(BigDecimal.valueOf(quantity));SalesOrder order=new SalesOrder(id,f.tenant.getId(),"LEGACY-"+id.toString().substring(0,8),OrderSource.MANUAL,OrderStatus.DRAFT,AllocationStatus.FULLY_ALLOCATED,SalesChannel.OTHER,null,null,null,null,null,"EUR",PaymentMethod.CASH,PaymentStatus.PAID,Instant.now(),f.user.getId());order.setAmounts(total,BigDecimal.ZERO,BigDecimal.ZERO,total,BigDecimal.ZERO);orders.saveAndFlush(order);orderItems.saveAndFlush(new OrderItem(f.tenant.getId(),id,product.getId(),product.getSku(),product.getName(),new BigDecimal("10.00"),quantity,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,total));return order;}
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt(Fixture f){return jwt().jwt(j->j.subject(f.user.getId().toString()).claim("username",f.user.getUsername()).claim("role","USER").claim("tenantId",f.tenant.getId().toString()));}
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt(User admin){return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")).jwt(j->j.subject(admin.getId().toString()).claim("username",admin.getUsername()).claim("role","ADMIN"));}
    record Fixture(Tenant tenant,User user){}
}
