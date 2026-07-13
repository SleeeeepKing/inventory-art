export type SupportedLocale = 'en' | 'zh-CN' | 'fr-FR'
export type UserRole = 'USER' | 'ADMIN'

export interface TenantSummary {
  id: string
  name: string
  defaultCurrency: string
  timezone: string
  locale: string
}

export interface UserProfile {
  id: string
  username?: string
  email: string
  displayName: string
  role: UserRole
  preferredLocale: SupportedLocale
  enabled?: boolean
  tenant?: TenantSummary
  tenantId?: string
}

export interface AuthResponse {
  accessToken: string
  expiresIn?: number
  user: UserProfile
}

export interface ApiError {
  timestamp?: string
  status?: number
  code?: string
  message?: string
  path?: string
  traceId?: string
  fieldErrors?: Record<string, string>
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  sort?: string
}

export interface Product {
  id: string
  familyId?: string
  variantName?: string
  sku: string
  name: string
  description?: string
  currentStock: number
  lowStockThreshold?: number
  enabled: boolean
  imageUrl?: string
  category?: string
  artistName?: string
  totalUnitsSold?: number
  lastSaleDate?: string
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface ProductVariant {
  id: string
  variantName?: string
  sku: string
  currentStock: number
  lowStockThreshold: number
  enabled: boolean
  totalUnitsSold: number
  lastSaleDate?: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface ProductFamily {
  id: string
  name: string
  category?: string
  artistName?: string
  description?: string
  imageUrl?: string
  version: number
  createdAt: string
  updatedAt: string
  variants: ProductVariant[]
}

export interface InventoryMovement {
  id: string
  productId: string
  productName?: string
  productSku?: string
  productImageUrl?: string
  type: string
  quantity: number
  stockBefore?: number
  stockAfter?: number
  reference?: string
  remark?: string
  createdAt: string
  operatorId?: string
  saleBatchId?: string
  eventId?: string
  eventName?: string
  attributedDate?: string
}

export interface InventoryOperationItem {
  id?: string
  productId: string
  productName: string
  productSku: string
  productCategory?: string
  productImageUrl?: string
  currentStock: number
  quantity: number
}

export interface InventoryOperation {
  id: string
  kind: 'MOVEMENT' | 'SALE'
  type: string
  quantity: number
  stockBefore?: number
  stockAfter?: number
  saleBatchId?: string
  eventId?: string
  eventName?: string
  attributedDate?: string
  status?: 'ACTIVE' | 'CANCELLED'
  reference?: string
  remark?: string
  createdAt: string
  updatedAt: string
  version: number
  items: InventoryOperationItem[]
}

export interface InventorySale {
  id: string
  eventId: string
  eventName: string
  attributedDate: string
  status: 'ACTIVE' | 'CANCELLED'
  version: number
  createdAt: string
  updatedAt: string
  items: Array<{
    id: string
    productId: string
    productName: string
    productSku: string
    productImageUrl?: string
    currentStock: number
    quantity: number
  }>
}

export interface ExpenseCategory {
  id: string
  name: string
  enabled: boolean
  version: number
  createdAt: string
  updatedAt: string
}

export interface EventExpense {
  id: string
  eventId: string
  categoryId: string
  categoryName: string
  amount: number
  currency: string
  expenseDate: string
  note?: string
  status: 'ACTIVE' | 'VOIDED'
  version: number
  createdAt: string
  updatedAt: string
}

export interface Order {
  id: string
  orderNumber: string
  eventId: string
  eventName: string
  currency: string
  totalAmount: number
  orderDate: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
  version?: number
}

export interface OrderBatchCreateResponse {
  eventId: string
  eventName: string
  currency: string
  orderDate: string
  orderCount: number
  totalAmount: number
  orders: Array<{ id: string; orderNumber: string }>
}

export interface OrderBulkDeleteResponse {
  deletedCount: number
}

export interface SalesEvent {
  id: string
  name: string
  startDate: string
  endDate: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface ReportSummary {
  currency: string
  revenue: number
  orders: number
  averageOrderValue: number
  lowStockProducts: number
  dailySales: Array<{ date: string; revenue: number; orders: number }>
  events?: Array<{ name: string; value: number }>
}

export interface AdminTenant {
  id: string
  name: string
  slug?: string
  currency?: string
  defaultCurrency: string
  timezone: string
  locale: string
  enabled: boolean
  userCount?: number
  createdAt?: string
}

export interface AuditLog {
  id: string
  action: string
  entityType?: string
  entityId?: string
  actorEmail?: string
  tenantName?: string
  ipAddress?: string
  createdAt: string
  metadata?: Record<string, unknown>
  tenantId?: string
  actorUserId?: string
  actorRole?: string
  resourceType?: string
  resourceId?: string
  result?: string
}
