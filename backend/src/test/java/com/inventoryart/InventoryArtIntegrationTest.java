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
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired TenantRepository tenants;@Autowired UserRepository users;@Autowired ProductRepository products;@Autowired PasswordEncoder passwords;@Autowired InventoryService inventory;@Autowired OrderService orderService;@Autowired AuditLogRepository audits;@Autowired PaymentRepository payments;

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

    @Test void tenantResourcesAreNotDiscoverableAcrossUsers() throws Exception {
        Fixture a=fixture("tenant-a"),b=fixture("tenant-b");Product other=product(b,"OTHER-1",5);
        mvc.perform(get("/api/v1/products/{id}",other.getId()).with(userJwt(a))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(post("/api/v1/inventory/adjustments").with(userJwt(a)).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":\""+other.getId()+"\",\"type\":\"ADJUSTMENT_OUT\",\"quantity\":1}]}"))
            .andExpect(status().isNotFound());
        assertThat(products.findById(other.getId()).orElseThrow().getCurrentStock()).isEqualTo(5);
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

    @Test void orderConfirmationCancellationAndRefundAreInventorySafe() {
        Fixture f=fixture("orders");Product p=product(f,"SKU-ORDER",10);
        OrderDtos.Request req=new OrderDtos.Request(List.of(new OrderDtos.ItemRequest(p.getId(),3,null,BigDecimal.ZERO,BigDecimal.ZERO)),SalesChannel.ONLINE,null,"Customer",null,null,"EUR",PaymentMethod.CARD,PaymentStatus.PAID,Instant.now());
        var draft=orderService.create(f.tenant.getId(),f.user.getId(),req);var confirmed=orderService.confirm(f.tenant.getId(),f.user.getId(),draft.id());
        assertThat(products.findById(p.getId()).orElseThrow().getCurrentStock()).isEqualTo(7);
        assertThat(payments.existsByTenantIdAndOrderId(f.tenant.getId(),draft.id())).isTrue();
        assertThatThrownBy(()->orderService.confirm(f.tenant.getId(),f.user.getId(),draft.id())).hasMessageContaining("already");
        var refund=orderService.refund(f.tenant.getId(),f.user.getId(),draft.id(),new OrderDtos.RefundRequest(List.of(new OrderDtos.RefundLine(confirmed.items().getFirst().id(),1)),"Returned"));
        assertThat(refund.status()).isEqualTo("PARTIALLY_REFUNDED");assertThat(products.findById(p.getId()).orElseThrow().getCurrentStock()).isEqualTo(8);
    }

    @Test void insufficientStockRollsBackOrderConfirmation() {
        Fixture f=fixture("stock");Product p=product(f,"LOW-1",1);OrderDtos.Request req=new OrderDtos.Request(List.of(new OrderDtos.ItemRequest(p.getId(),2,null,BigDecimal.ZERO,BigDecimal.ZERO)),SalesChannel.OTHER,null,null,null,null,"EUR",PaymentMethod.CASH,PaymentStatus.PAID,Instant.now());var draft=orderService.create(f.tenant.getId(),f.user.getId(),req);
        assertThatThrownBy(()->orderService.confirm(f.tenant.getId(),f.user.getId(),draft.id())).hasMessageContaining("Insufficient");assertThat(products.findById(p.getId()).orElseThrow().getCurrentStock()).isEqualTo(1);
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
            SalesChannel.EXHIBITION,"Summer fair",null,null,null,"EUR",PaymentMethod.CARD,PaymentStatus.PAID,Instant.now());
        var draft=orderService.create(f.tenant.getId(),f.user.getId(),request);
        var confirmed=orderService.confirm(f.tenant.getId(),f.user.getId(),draft.id());
        orderService.refund(f.tenant.getId(),f.user.getId(),draft.id(),
            new OrderDtos.RefundRequest(List.of(new OrderDtos.RefundLine(confirmed.items().getFirst().id(),1)),"Returned"));

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
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor userJwt(Fixture f){return jwt().jwt(j->j.subject(f.user.getId().toString()).claim("username",f.user.getUsername()).claim("role","USER").claim("tenantId",f.tenant.getId().toString()));}
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt(User admin){return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")).jwt(j->j.subject(admin.getId().toString()).claim("username",admin.getUsername()).claim("role","ADMIN"));}
    record Fixture(Tenant tenant,User user){}
}
