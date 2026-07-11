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
  sku: string
  name: string
  description?: string
  salePrice: number
  costPrice?: number
  currency: string
  currentStock: number
  lowStockThreshold?: number
  enabled: boolean
  imageObjectKey?: string
  imageUrl?: string
  category?: string
  artistName?: string
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface InventoryMovement {
  id: string
  productId: string
  productName?: string
  productSku?: string
  type: string
  quantity: number
  stockBefore?: number
  stockAfter?: number
  reason?: string
  remark?: string
  referenceType?: string
  referenceId?: string
  createdAt: string
  createdByName?: string
  operatorId?: string
}

export interface OrderItem {
  id?: string
  productId: string
  productName?: string
  sku?: string
  quantity: number
  unitPrice: number
  lineTotal?: number
  refundedQuantity?: number
}

export interface Order {
  id: string
  orderNumber: string
  status: 'DRAFT' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'REFUNDED' | 'PARTIALLY_REFUNDED' | 'UNALLOCATED'
  source?: string
  channel?: string
  salesChannel?: string
  customerName?: string
  customerEmail?: string
  currency: string
  subtotal: number
  taxAmount?: number
  totalAmount: number
  notes?: string
  customerNote?: string
  items: OrderItem[]
  orderedAt?: string
  orderDate: string
  createdAt?: string
}

export interface ImportBatch {
  id: string
  fileName?: string
  originalFilename: string
  status: 'UPLOADED' | 'ANALYZING' | 'READY_FOR_MAPPING' | 'READY_FOR_CONFIRMATION' | 'IMPORTING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED' | 'REVERSED'
  fileType?: string
  detectedType?: string
  importType?: string
  totalRows?: number
  validRows?: number
  importedRows?: number
  errorRows?: number
  analysisVersion?: number
  createdAt: string
  completedAt?: string
  orderCount?: number
  inventoryMovementCount?: number
}

export interface ReportSummary {
  currency: string
  revenue: number
  orders: number
  unitsSold: number
  grossProfit: number
  pendingAllocation: number
  dailySales: Array<{ date: string; revenue: number; orders: number }>
  topProducts: Array<{ name: string; sku?: string; quantity: number; revenue: number }>
  sources?: Array<{ name: string; value: number }>
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
