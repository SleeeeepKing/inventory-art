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
  totalUnitsSold?: number
  totalSalesRevenue?: number
  lastSaleAt?: string
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
  saleBatchId?: string
  salesChannel?: string
  eventId?: string
  eventName?: string
  attributedDate?: string
  unitPrice?: number
  currency?: string
  attributedAmount?: number
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
  status:
    | 'DRAFT'
    | 'CONFIRMED'
    | 'COMPLETED'
    | 'CANCELLED'
    | 'REFUNDED'
    | 'PARTIALLY_REFUNDED'
    | 'UNALLOCATED'
  source?: string
  channel?: string
  salesChannel?: string
  eventId?: string
  eventName?: string
  customerName?: string
  customerEmail?: string
  currency: string
  subtotal: number
  taxAmount?: number
  refundAmount?: number
  totalAmount: number
  notes?: string
  customerNote?: string
  items: OrderItem[]
  orderedAt?: string
  orderDate: string
  createdBy?: string
  createdAt?: string
}

export interface OrderBatchFailure {
  id: string
  orderNumber?: string
  code: string
  message: string
}

export interface OrderBatchResponse {
  succeeded: Array<{ id: string; orderNumber: string; status: string }>
  failed: OrderBatchFailure[]
}

export interface OrderBatchCreateResponse {
  eventId: string
  eventName: string
  currency: string
  orderCount: number
  totalAmount: number
  orders: Array<{ id: string; orderNumber: string; status: string }>
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

export interface ImportBatch {
  id: string
  fileName?: string
  originalFilename: string
  status:
    | 'UPLOADED'
    | 'ANALYZING'
    | 'READY_FOR_MAPPING'
    | 'READY_FOR_CONFIRMATION'
    | 'IMPORTING'
    | 'COMPLETED'
    | 'COMPLETED_WITH_ERRORS'
    | 'FAILED'
    | 'REVERSED'
  fileType?: string
  detectedType?: string
  importType?: string
  totalRows?: number
  validRows?: number
  importedRows?: number
  updatedRows?: number
  duplicateRows?: number
  skippedRows?: number
  errorRows?: number
  analysisVersion?: number
  createdAt: string
  completedAt?: string
  orderCount?: number
  inventoryMovementCount?: number
  eventId?: string
  eventName?: string
}

export interface ReportSummary {
  currency: string
  revenue: number
  grossSales: number
  discounts: number
  refunds: number
  fees: number
  afterFees: number
  productCost: number
  orders: number
  unitsSold: number
  grossProfit: number
  averageOrderValue: number
  successfulPayments: number
  pendingAllocation: number
  importErrors: number
  lowStockProducts: number
  dailySales: Array<{ date: string; revenue: number; orders: number }>
  topProducts: Array<{ name: string; sku?: string; quantity: number; revenue: number }>
  sources?: Array<{ name: string; value: number }>
  channels?: Array<{ name: string; value: number }>
  paymentMethods?: Array<{ name: string; value: number }>
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
