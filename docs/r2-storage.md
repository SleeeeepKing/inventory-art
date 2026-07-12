# 商品图片存储

存储模块只负责商品图片。开发环境可以使用本地目录或 MinIO，生产环境可以使用 Cloudflare R2 等 S3 兼容私有对象存储。

## 数据与对象

`stored_files` 直接关联商品，保存对象 key、原文件名、MIME、大小、checksum 和时间戳。对象 key 由服务端生成，推荐结构：

```text
tenants/{tenantId}/products/{productId}/{uuid}.{ext}
```

Bucket 不开放匿名列举或公开读取。浏览器通过后端授权后取得短期预签名 URL。

## 配置

| 变量                                  | 说明                                         |
| ------------------------------------- | -------------------------------------------- |
| `STORAGE_PROVIDER`                    | `local`、`r2` 或 `minio`                     |
| `LOCAL_STORAGE_PATH`                  | 本地存储目录                                 |
| `R2_ENDPOINT`                         | S3 API 地址                                  |
| `R2_PUBLIC_ENDPOINT`                  | 浏览器能够访问的签名 URL 端点                |
| `R2_REGION`                           | 区域；R2 常用 `auto`，MinIO 可用 `us-east-1` |
| `R2_ACCESS_KEY_ID`                    | 访问密钥 ID                                  |
| `R2_SECRET_ACCESS_KEY`                | 访问密钥 Secret                              |
| `R2_BUCKET_PRIVATE`                   | 私有 Bucket 名称                             |
| `R2_PRESIGNED_URL_EXPIRATION_SECONDS` | 预签名 URL 有效期，默认 900 秒               |

本地 Docker Compose 使用 MinIO，并由 `minio-init` 自动创建 Bucket。`R2_PUBLIC_ENDPOINT` 必须是浏览器可达地址，不能填写仅容器网络可解析的内部域名。

## 上传和访问

1. 用户选择当前 Tenant 下的商品。
2. 后端验证商品归属、扩展名、MIME 和大小。
3. 后端生成不可预测对象 key 并保存对象。
4. 成功后写入 `stored_files`；失败时清理已创建对象。
5. 读取或删除前重新验证文件、商品和 Tenant 的关联。

删除或替换图片时应同时清理对象和数据库元数据。对象操作失败必须记录可追踪日志，避免静默产生孤儿数据。

## CORS 与权限

对象存储 CORS 只允许实际前端域名和必要方法。Access Key 只授予目标 Bucket 的对象读写权限，不授予账户级管理权限。密钥只存放在部署平台 Secret 中。

## 运维检查

- 验证上传、预签名读取、替换和删除。
- 验证 Tenant A 无法签名或删除 Tenant B 商品图片。
- 定期比对 `stored_files` 与 Bucket 对象，清理确认无引用的孤儿对象。
- 备份数据库和 Bucket，并演练一起恢复，避免元数据与对象版本错位。
