package com.inventoryart.config;

import com.inventoryart.inventory.InventoryService;
import com.inventoryart.inventory.MovementType;
import com.inventoryart.order.OrderDtos;
import com.inventoryart.order.OrderRepository;
import com.inventoryart.order.OrderService;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(10)
public class DataInitializer implements ApplicationRunner {
  private final AppProperties props;
  private final TenantRepository tenants;
  private final UserRepository users;
  private final ProductRepository products;
  private final ProductFamilyRepository productFamilies;
  private final PasswordEncoder passwords;
  private final InventoryService inventory;
  private final OrderService orderService;
  private final OrderRepository orderRepository;
  private final JdbcTemplate jdbc;

  @Value("${ADMIN_BOOTSTRAP_USERNAME:}")
  private String bootstrapUsername;

  @Value("${ADMIN_BOOTSTRAP_PASSWORD:}")
  private String bootstrapPassword;

  public DataInitializer(
      AppProperties props,
      TenantRepository tenants,
      UserRepository users,
      ProductRepository products,
      ProductFamilyRepository productFamilies,
      PasswordEncoder passwords,
      InventoryService inventory,
      OrderService orderService,
      OrderRepository orderRepository,
      JdbcTemplate jdbc) {
    this.props = props;
    this.tenants = tenants;
    this.users = users;
    this.products = products;
    this.productFamilies = productFamilies;
    this.passwords = passwords;
    this.inventory = inventory;
    this.orderService = orderService;
    this.orderRepository = orderRepository;
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (props.getSeed().isEnabled()) {
      seedDevelopment();
    } else {
      bootstrapAdmin();
    }
  }

  private void bootstrapAdmin() {
    if (bootstrapUsername.isBlank()
        || bootstrapPassword.isBlank()
        || users.existsByRole(UserRole.ADMIN)) {
      return;
    }
    if (bootstrapPassword.length() < 12) {
      throw new IllegalStateException(
          "ADMIN_BOOTSTRAP_PASSWORD must contain at least 12 characters");
    }
    users.save(
        new User(
            UUID.randomUUID(),
            null,
            bootstrapUsername,
            bootstrapUsername.contains("@") ? bootstrapUsername : "admin@example.invalid",
            passwords.encode(bootstrapPassword),
            "Administrator",
            UserRole.ADMIN));
  }

  private void seedDevelopment() {
    if (!users.existsByUsernameIgnoreCase("admin")) {
      users.save(
          new User(
              UUID.randomUUID(),
              null,
              "admin",
              "admin@inventory-art.local",
              passwords.encode("Admin123!"),
              "Administrator",
              UserRole.ADMIN));
    }
    seedTenant(
        "creator-one",
        "Creator One",
        "user1",
        "user1@inventory-art.local",
        "Art Print A",
        "ART-001",
        12,
        new BigDecimal("15.00"));
    seedTenant(
        "creator-two",
        "Creator Two",
        "user2",
        "user2@inventory-art.local",
        "Keychain B",
        "KEY-001",
        20,
        new BigDecimal("8.50"));
  }

  private void seedTenant(
      String slug,
      String tenantName,
      String username,
      String email,
      String productName,
      String sku,
      int stock,
      BigDecimal orderAmount) {
    Tenant tenant =
        tenants
            .findBySlug(slug)
            .orElseGet(
                () ->
                    tenants.save(
                        new Tenant(
                            UUID.randomUUID(), tenantName, slug, "EUR", "Europe/Paris", "zh-CN")));
    User user =
        users
            .findByUsernameIgnoreCase(username)
            .orElseGet(
                () ->
                    users.save(
                        new User(
                            UUID.randomUUID(),
                            tenant.getId(),
                            username,
                            email,
                            passwords.encode("User123!"),
                            tenantName + " Owner",
                            UserRole.USER)));
    Product product =
        products
            .findByTenantIdAndSkuIgnoreCase(tenant.getId(), sku)
            .orElseGet(
                () -> {
                  ProductFamily family =
                      productFamilies.save(
                          new ProductFamily(
                              UUID.randomUUID(),
                              tenant.getId(),
                              productName,
                              "Demo",
                              "Demo Artist",
                              "Development seed product"));
                  return products.save(new Product(UUID.randomUUID(), family, null, sku, 3));
                });
    if (product.getCurrentStock() == 0) {
      inventory.apply(
          tenant.getId(),
          product.getId(),
          stock,
          MovementType.INITIAL,
          "Development seed",
          null,
          user.getId());
    }
    UUID eventId = ensureDemoEvent(tenant, slug);
    if (!orderRepository.existsByTenantId(tenant.getId())) {
      orderService.createBatch(
          tenant.getId(),
          user.getId(),
          new OrderDtos.BatchCreateRequest(
              eventId,
              Instant.now().truncatedTo(ChronoUnit.HOURS),
              List.of(new OrderDtos.BatchCreateLine(orderAmount))));
    }
  }

  private UUID ensureDemoEvent(Tenant tenant, String slug) {
    UUID eventId = UUID.nameUUIDFromBytes((slug + "-demo-event").getBytes(StandardCharsets.UTF_8));
    LocalDate end = LocalDate.now(ZoneId.of(tenant.getTimezone()));
    LocalDate start = end.minusDays(3);
    jdbc.update(
        """
            insert into sales_events(id,tenant_id,name,start_date,end_date,enabled,created_at,updated_at)
            values(?,?,'Demo Expo',?,?,true,now(),now())
            on conflict(tenant_id,name) do update set start_date=excluded.start_date,end_date=excluded.end_date,updated_at=now()
            """,
        eventId,
        tenant.getId(),
        start,
        end);
    return jdbc.queryForObject(
        "select id from sales_events where tenant_id=? and name='Demo Expo'",
        UUID.class,
        tenant.getId());
  }
}
