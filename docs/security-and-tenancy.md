# 安全与多租户隔离

## 信任边界

系统把浏览器、上传文件、JWT 声明、请求中的资源 ID 和第三方导出内容都视为不可信输入。只有后端认证上下文、数据库约束和服务端重算结果可以决定 Tenant、权限、库存和金额。

| 边界 | 主要风险 | 控制措施 |
| --- | --- | --- |
| Browser → API | 越权 ID、伪造金额、Token 窃取、暴力登录 | Spring Security、DTO 校验、服务端金额计算、限流、短期 JWT、HttpOnly Refresh Cookie |
| USER → 其他 Tenant | IDOR、跨 Tenant 文件/映射访问 | 认证上下文 Tenant、Tenant-aware Repository、组合外键、隔离集成测试 |
| ADMIN → 全局数据 | 权限滥用、不可追溯修复 | 独立 ADMIN API/Service、角色注解、跨 Tenant 审计 |
| 文件 → Parser/Storage | 恶意格式、超大文件、敏感数据泄露 | allowlist、大小/MIME/扩展名校验、流式解析、私有对象、原始数据清理 |
| API → PostgreSQL/R2 | 密钥泄露、明文连接、公共对象 | 部署平台 Secret、TLS、最小权限 R2 Token、私有 Bucket、短期预签名 URL |

## Tenant 解析与访问规则

### USER 请求

1. Security filter 验证 Access Token，并加载当前启用用户。
2. 当前主体包含 `userId`、`role` 和数据库确认的 `tenantId`。
3. 普通 Controller 的 Request DTO 不暴露 `tenantId` 字段；Service 从主体获取 Tenant。
4. Repository 使用 `find...ByIdAndTenantId`、`find...ByTenantId` 或等价查询，不能先全局查 ID 后在内存判断。
5. 资源不存在或属于其他 Tenant 时统一返回 `404 RESOURCE_NOT_FOUND`，不泄露 ID 是否有效。
6. 创建子资源时同时验证所有父 ID 属于当前 Tenant；数据库组合外键再次阻止跨 Tenant 关联。

该规则适用于商品、库存、订单、支付、退款、外部交易、导入批次/行、商品映射、文件、审计查询和报表。

### ADMIN 请求

- ADMIN 只能通过 `/api/v1/admin/**` 及明确的管理员 Service 执行全局查询或修复，不能把普通 USER API 当作跨 Tenant 后门。
- 管理员接口使用角色授权，并显式接收/解析目标 Tenant 过滤条件。
- 每次跨 Tenant 读取、导出或修改写入 `audit_logs`：actor、目标 Tenant、动作、资源、结果、IP、User-Agent 和不含敏感信息的筛选摘要。
- 管理员也不能绕过库存、订单、文件确认或导入幂等规则；修复应调用相同领域 Service。

### 数据库防线

- 业务表携带 `tenant_id`；父子表使用 `(tenant_id,id)` 候选键和组合外键。
- SKU、订单号、文件 checksum、外部交易 ID/fingerprint 等唯一约束以 Tenant 为作用域。
- 高频索引把 `tenant_id` 放在首列。
- 当前 MVP 不启用 PostgreSQL RLS。应用授权、Tenant-aware 查询、数据库关联约束和真实 PostgreSQL 集成测试共同构成防线；未来启用 RLS 时仍保留应用层检查。

## 认证设计

### 登录

- 用户名在限流和查询前执行一致的标准化；失败统一返回认证错误，不能区分“用户不存在”“密码错误”或“已禁用”。
- 登录默认按“源 IP + 标准化用户名”限制为每分钟 5 次。限流记录不得包含密码。
- 密码使用 BCrypt 哈希；日志、审计 metadata、错误和响应中不得出现密码或哈希。
- 禁用用户不能登录；成功验证后仍在签发 Token 前检查 `enabled`，并更新 `last_login_at`。
- 登录成功和失败写安全审计，但失败 metadata 仅保留必要的非敏感上下文。

### Access Token

- Access Token 是默认 15 分钟有效的签名 JWT，只通过 `Authorization: Bearer` 使用。
- 前端只在 Pinia/应用内存保存 Access Token；不得放入 Local Storage、Session Storage、URL 或非 HttpOnly Cookie。
- API 不仅相信 JWT 中的历史 `enabled` 状态；安全关键路径和 Refresh 必须确认当前用户仍启用。
- `JWT_SECRET` 必须是生产 Secret 中的高熵随机值，不能使用仓库中的本地 fallback。

### Refresh Token 轮换

- Refresh Token 是至少 256 位随机值，通过 `HttpOnly; SameSite=Lax` Cookie 传输；生产必须设置 `Secure`。
- 数据库只保存明文 Token 的 SHA-256 哈希、用户、`family_id`、过期时间、创建上下文、撤销时间和替代令牌关联。
- 每次刷新都撤销旧 Token 并在同一令牌族签发新 Token；响应同时返回新 Access Token 和新 Cookie。
- 已轮换/撤销 Token 再次出现视为可能泄露：撤销该 `family_id` 下所有未过期 Token，要求重新登录。
- 退出撤销当前 Refresh Token（安全时可撤销整个会话族），并清除 Cookie。
- 用户被禁用或密码被管理员重置时，撤销该用户所有 Refresh Token。

### Cookie 请求与 CSRF

Refresh 和 Logout 会自动携带 Cookie，因此服务端必须校验请求 `Origin` 是否在精确的 `CORS_ALLOWED_ORIGINS` allowlist；缺失或不匹配 Origin 的浏览器跨站请求应拒绝。生产只允许 HTTPS Origin，并设置 `COOKIE_SECURE=true`。

普通 Bearer Token API 不依赖 Cookie 认证。CORS 只允许需要的方法和请求头，并在使用凭据时返回具体 Origin，不能使用通配符 `*`。

## 语言设置与账号边界

- `users.preferred_locale` 只允许 `en`、`zh-CN`、`fr-FR`，默认 `en`。
- `/api/v1/profile` 只能读取/修改当前用户的 `displayName` 和语言等允许字段，不能修改角色、Tenant 或 enabled。
- UI 语言不是授权信息；服务端不能根据 locale 决定权限。
- `tenants.locale` 是业务区域设置，和用户 UI 语言分别保存。

## 文件与对象存储安全

- Bucket 默认私有，禁止匿名列举；R2 API Token 仅授予指定 Bucket 的对象读写权限。
- object key 由后端生成，包含 `tenants/{tenantId}/...` 和随机 UUID；请求不能提交任意 key 或其他 Tenant 的路径。
- 商品图片只允许 JPG、PNG、WebP，默认最大 10 MB；SumUp 文件只允许 CSV、XLS、XLSX，默认最大 20 MB。扩展名、声明 MIME 和文件特征需要联合校验。
- 预签名 URL 是 bearer credential，只授权一个 key 和一个操作，默认 900 秒有效。服务端永不把 Access Key/Secret 返回浏览器。
- 浏览器 PUT 完成后，后端必须 HEAD 对象并校验 content type、大小和 checksum，然后才把 `stored_files` 标记为已确认并绑定资源。
- 下载也使用短期 URL 或受控后端流；授权发生在签名之前。
- 删除写审计并使用软删除 metadata；未确认临时对象按保留期清理。
- SumUp 原文件和行 JSON 必须清理完整卡号、CVV、访问 Token 等；只允许保留业务需要的遮罩卡信息。

更多配置见 [r2-storage.md](r2-storage.md)。

## 输入、错误和日志

- Controller 使用 Bean Validation；ID、枚举、分页、日期范围、金额、文件名和上传大小都有边界。
- 订单金额必须为正数，商品行只能引用当前 Tenant 的商品；订单接口没有库存写入能力。
- CSV/XLS/XLSX 采用 allowlist 和流式/事件式读取；拒绝 PDF、图片、可执行文件和宏文件。
- 统一错误只返回稳定 code、用户可读 message 和 traceId；不返回 SQL、堆栈、内部类名或存储 Secret。
- 日志过滤 `Authorization`、`Cookie`、密码、Refresh Token、R2 Secret、完整银行卡数据和上传原始行。
- traceId 写入响应和日志 MDC，便于排错；不能把敏感字段复制到 trace 或 audit metadata。
- 审计记录是业务安全历史，不记录 Token/密码，不允许普通用户修改。

## 多租户测试矩阵

关键隔离测试必须使用两个真实 Tenant 和 Testcontainers PostgreSQL：

| 场景 | 期望 |
| --- | --- |
| 用户 A 读取/修改用户 B 商品 | 404，B 数据不变 |
| 用户 A 调整用户 B 库存 | 404，无库存流水 |
| 用户 A 读取用户 B 订单 | 404 |
| 用户 A 读取 ImportBatch/错误 CSV | 404，不返回文件 URL |
| 用户 A 修改用户 B 商品映射 | 404，映射不变 |
| 用户 A 请求用户 B 文件 PUT/GET/Delete | 404，不生成预签名 URL |
| 两个 Tenant 使用相同 SKU/外部交易号 | 分别成功且互不影响 |
| ADMIN 查询两个 Tenant | 成功并生成跨 Tenant 审计 |
| 禁用用户使用旧 Refresh Token | 拒绝并撤销会话 |
| 旧 Refresh Token 被重放 | 整个令牌族撤销 |

测试同时断言响应状态、数据库副作用和审计副作用，不能只检查前端菜单是否隐藏。

## 生产安全清单

- [ ] 不启用 `dev` profile 或演示 seed；不保留演示账号密码。
- [ ] `JWT_SECRET`、数据库密码和 R2 Secret 均使用部署平台 Secret，并完成初始轮换。
- [ ] Neon JDBC 强制 TLS；数据库账号遵循最小权限，备份/恢复流程已演练。
- [ ] `COOKIE_SECURE=true`；`CORS_ALLOWED_ORIGINS` 只包含实际 HTTPS Pages/自定义域名。
- [ ] R2 Bucket 私有、禁止匿名列举，Token 仅限一个 Bucket；CORS 只允许实际前端 Origin。
- [ ] Actuator 只公开 health/info；Swagger 的生产暴露符合团队政策。
- [ ] Railway/Cloudflare 日志不会采集 Authorization、Cookie 或导入原始敏感行。
- [ ] 管理员初始化密码通过 Secret 提供，首次登录后轮换并移除初始化变量。
- [ ] Testcontainers 隔离测试、前端权限测试和依赖扫描在 CI 中通过。
- [ ] 监控登录限流、异常 Refresh 重放、导入失败、跨 Tenant ADMIN 操作和对象确认失败。
