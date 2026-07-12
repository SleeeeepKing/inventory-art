# 管理员与用户账号初始化

## 推荐方式：应用初始化 + ADMIN 页面

生产首次启动时，在 Railway Variables 临时设置：

```text
APP_SEED_ENABLED=false
ADMIN_BOOTSTRAP_USERNAME=<管理员登录名或邮箱>
ADMIN_BOOTSTRAP_PASSWORD=<至少 12 位的唯一强密码>
```

应用仅在数据库中还没有 ADMIN 时创建首个管理员，密码使用 BCrypt 保存。确认可以登录后，移除两项 `ADMIN_BOOTSTRAP_*` 变量。随后在“管理 → Tenant”和“管理 → 用户”创建工作空间及普通 USER；新用户的 `preferred_locale` 默认为 `en`。

ADMIN 是跨 Tenant 的账号管理身份，`tenant_id` 必须为 `NULL`，只负责 Tenant、账号和审计，不访问订单、商品、库存、展会或报表。普通 USER 必须关联 Tenant。多个 USER 可以使用同一个 `tenant_id` 共享同一工作空间；朋友需要独立库存时则创建独立 Tenant。

## 紧急 SQL 方式

只有无法通过应用初始化时才直接写数据库。以下 SQL 依赖 PostgreSQL `pgcrypto` 产生 BCrypt；必须替换全部占位值，并确保用户名、邮箱和 slug 不重复。SQL 编辑器可能保存明文历史，执行后应立即清除历史并要求用户在登录后修改密码。

```sql
BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO users (
    id, tenant_id, username, email, password_hash,
    display_name, role, preferred_locale, enabled,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), NULL,
    '<ADMIN_USERNAME>', '<ADMIN_EMAIL>',
    crypt('<TEMPORARY_ADMIN_PASSWORD>', gen_salt('bf', 12)),
    'Administrator', 'ADMIN', 'en', TRUE,
    now(), now()
);

WITH new_tenant AS (
    INSERT INTO tenants (
        id, name, slug, default_currency, timezone, locale,
        enabled, created_at, updated_at
    ) VALUES (
        gen_random_uuid(), '<FRIEND_WORKSPACE>', '<UNIQUE_SLUG>',
        'EUR', 'Europe/Paris', 'fr-FR', TRUE, now(), now()
    )
    RETURNING id
)
INSERT INTO users (
    id, tenant_id, username, email, password_hash,
    display_name, role, preferred_locale, enabled,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), id,
    '<FRIEND_USERNAME>', '<FRIEND_EMAIL>',
    crypt('<TEMPORARY_FRIEND_PASSWORD>', gen_salt('bf', 12)),
    '<FRIEND_DISPLAY_NAME>', 'USER', 'en', TRUE,
    now(), now()
FROM new_tenant;

COMMIT;
```

如果朋友应加入现有工作空间，不创建 `new_tenant`，而是使用受控查询取得 Tenant：

```sql
INSERT INTO users (
    id, tenant_id, username, email, password_hash,
    display_name, role, preferred_locale, enabled,
    created_at, updated_at
)
SELECT
    gen_random_uuid(), id,
    '<FRIEND_USERNAME>', '<FRIEND_EMAIL>',
    crypt('<TEMPORARY_FRIEND_PASSWORD>', gen_salt('bf', 12)),
    '<FRIEND_DISPLAY_NAME>', 'USER', 'en', TRUE,
    now(), now()
FROM tenants
WHERE slug = '<EXISTING_TENANT_SLUG>' AND enabled = TRUE;
```

执行后确认最后一条 `INSERT 0 1`；若返回 `INSERT 0 0`，说明 Tenant slug 不存在或已禁用，不要改成无 Tenant 的 USER。
