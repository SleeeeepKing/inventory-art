# 商品图片存储

存储模块只负责商品图片。开发环境可以使用本地目录或 MinIO，生产环境可以使用 Cloudflare R2 等 S3 兼容私有对象存储。

## 数据与对象

`stored_files` 直接关联商品，分别保存原图和低清预览的对象 key、MIME、大小、checksum 和时间戳。对象 key 由服务端根据当前数据库中的 Tenant slug 与商品 SKU 生成：

```text
tenants/{tenantSlug}/products/{encodedSku}/{uuid}/original.{ext}
tenants/{tenantSlug}/products/{encodedSku}/{uuid}/preview.webp
```

SKU 作为单一路径段进行百分号编码，不能借助 `/` 等字符改变对象层级。Bucket 不开放匿名列举或公开读取。浏览器只在上传时取得短期预签名 PUT URL；日常展示通过带 JWT 的后端预览接口读取，不向浏览器返回 R2 GET URL。

## 配置

| 变量                                  | 说明                                         |
| ------------------------------------- | -------------------------------------------- |
| `STORAGE_PROVIDER`                    | `local`、`r2` 或 `minio`                     |
| `LOCAL_STORAGE_PATH`                  | 本地存储目录                                 |
| `R2_ENDPOINT`                         | S3 API 地址                                  |
| `R2_PUBLIC_ENDPOINT`                  | 浏览器能够访问的签名上传 URL 端点            |
| `R2_REGION`                           | 区域；R2 常用 `auto`，MinIO 可用 `us-east-1` |
| `R2_ACCESS_KEY_ID`                    | 访问密钥 ID                                  |
| `R2_SECRET_ACCESS_KEY`                | 访问密钥 Secret                              |
| `R2_BUCKET_PRIVATE`                   | 私有 Bucket 名称                             |
| `R2_PRESIGNED_URL_EXPIRATION_SECONDS` | 预签名 URL 有效期，默认 900 秒               |

本地 Docker Compose 使用 MinIO，并由 `minio-init` 自动创建 Bucket。`R2_PUBLIC_ENDPOINT` 必须是浏览器可达地址，不能填写仅容器网络可解析的内部域名。

## 上传和访问

1. 用户选择当前 Tenant 下的商品。
2. 后端验证商品归属、扩展名、MIME 和大小。
3. 浏览器在本地生成最长边 480 px、最大 512 KiB 的 WebP 预览，原图与预览分别直传私有 Bucket。
4. 后端确认两份对象的 MIME、大小、checksum，并校验预览画布不超过 480 × 480。
5. 成功后关联商品；读取或删除前重新验证文件、商品和 Tenant 的关联。
6. 商品 API 只返回 `/files/{fileId}/preview`，前端携带 Bearer Token 获取预览 Blob；响应使用 `private, no-store`，不会进入 PWA 缓存。

删除或替换图片时同时清理原图、预览和数据库元数据。旧版 JPEG/PNG 对象没有预览时，由后端按 480 px 上限即时转换；旧版 WebP 不回退到原图，以免重新暴露源文件。

## CORS 与权限

对象存储 CORS 只允许实际前端域名和必要方法。Access Key 只授予目标 Bucket 的对象读写权限，不授予账户级管理权限。密钥只存放在部署平台 Secret 中。

## 运维检查

- 验证双对象上传、鉴权预览、替换和删除。
- 验证商品响应和浏览器网络请求中没有 R2 GET URL。
- 验证 Tenant A 无法签名、预览或删除 Tenant B 商品图片。
- 定期比对 `stored_files` 与 Bucket 对象，清理确认无引用的孤儿对象。
- 备份数据库和 Bucket，并演练一起恢复，避免元数据与对象版本错位。
