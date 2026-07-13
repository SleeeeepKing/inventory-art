<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, Delete, Download, Edit, Minus, Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { mergeProductSearch } from '@/services/productSelection'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type {
  InventoryOperation,
  InventoryMovement,
  InventorySale,
  PageResponse,
  Product,
  SalesEvent,
} from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import SecureImage from '@/components/SecureImage.vue'
import ProductSelect from '@/components/ProductSelect.vue'

interface SaleLine {
  productId: string
  quantity: number
}

const { t, te } = useI18n()
const { showError } = useApiFeedback()
const { number, date, dateTime } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const saleOpen = ref(false)
const additionOpen = ref(false)
const correctionOpen = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const page = ref<PageResponse<InventoryOperation>>(normalizePage([]))
const products = ref<Product[]>([])
const events = ref<SalesEvent[]>([])
const productCategories = ref<string[]>([])
const filterProductIds = ref<string[]>([])
const filterTypes = ref<string[]>([])
const filterCategories = ref<string[]>([])
const filterEventId = ref('')
const addition = reactive({ productId: '', quantity: 1, remark: '' })
const correction = reactive({ productId: '', quantity: 0, remark: '' })
const sale = reactive({
  id: '',
  version: 0,
  eventId: '',
  items: [] as SaleLine[],
})
const activeEvents = computed(() => events.value.filter((event) => event.enabled))
const movementTypes = [
  'INITIAL',
  'PURCHASE',
  'SALE',
  'RETURN',
  'ADJUSTMENT_IN',
  'ADJUSTMENT_OUT',
  'STOCK_CORRECTION',
]
const selectedEvent = computed(() => events.value.find((event) => event.id === sale.eventId))
const correctionProduct = computed(() => productFor(correction.productId))
const correctionDelta = computed(
  () => correction.quantity - Number(correctionProduct.value?.currentStock || 0),
)
const saleUnits = computed(() =>
  sale.items.reduce((sum, line) => sum + Number(line.quantity || 0), 0),
)
let productSearchTimer: ReturnType<typeof setTimeout> | undefined

async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({
      page: String(currentPage.value - 1),
      size: String(pageSize.value),
    })
    filterTypes.value.forEach((value) => params.append('types', value))
    filterProductIds.value.forEach((value) => params.append('productIds', value))
    filterCategories.value.forEach((value) => params.append('productCategories', value))
    if (filterEventId.value) params.set('eventId', filterEventId.value)
    const movements = await api.get<PageResponse<InventoryOperation> | InventoryOperation[]>(
      '/inventory/operations',
      {
        params,
      },
    )
    page.value = normalizePage(movements.data, currentPage.value - 1, pageSize.value)
    if (!products.value.length) {
      const productResponse = await api.get<PageResponse<Product> | Product[]>('/products', {
        params: { page: 0, size: 20, enabled: true },
      })
      products.value = normalizePage(productResponse.data, 0, 500).items
    }
    if (!events.value.length) {
      const { data } = await api.get<SalesEvent[]>('/sales-events', {
        params: { includeDisabled: true },
      })
      events.value = data
    }
    if (!productCategories.value.length) {
      const { data } = await api.get<string[]>('/products/categories')
      productCategories.value = data
    }
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

function reloadFirstPage() {
  currentPage.value = 1
  void load()
}

async function searchProductsNow(query: string, enabled?: boolean) {
  const { data } = await api.get<PageResponse<Product> | Product[]>('/products', {
    params: { page: 0, size: 20, enabled, q: query || undefined },
  })
  const found = normalizePage(data, 0, 20).items
  const selected = new Set(
    [
      ...filterProductIds.value,
      addition.productId,
      correction.productId,
      ...sale.items.map((line) => line.productId),
    ].filter(Boolean),
  )
  products.value = mergeProductSearch(products.value, found, selected)
}
function searchProducts(query: string) {
  if (productSearchTimer) clearTimeout(productSearchTimer)
  productSearchTimer = setTimeout(
    () => {
      void searchProductsNow(query, true)
    },
    query ? 250 : 0,
  )
}

function searchFilterProducts(query: string) {
  if (productSearchTimer) clearTimeout(productSearchTimer)
  productSearchTimer = setTimeout(
    () => {
      void searchProductsNow(query)
    },
    query ? 250 : 0,
  )
}

function searchSaleProducts(query: string) {
  if (productSearchTimer) clearTimeout(productSearchTimer)
  productSearchTimer = setTimeout(
    () => {
      void searchProductsNow(query, sale.id ? undefined : true)
    },
    query ? 250 : 0,
  )
}

function movementLabel(type: string) {
  return te(`inventory.movementTypes.${type}`)
    ? t(`inventory.movementTypes.${type}`)
    : type.replaceAll('_', ' ')
}
function eventLabel(event: SalesEvent) {
  return `${event.name} · ${event.startDate} — ${event.endDate}`
}
function productFor(id: string) {
  return products.value.find((product) => product.id === id)
}
function operationProduct(operation: InventoryOperation) {
  return operation.items[0]
}
function movementProductName(operation: InventoryOperation) {
  const item = operationProduct(operation)
  if (!item) return operation.id
  return operation.kind === 'SALE' && operation.items.length > 1
    ? `${item.productName} +${operation.items.length - 1}`
    : item.productName
}
function movementProductSku(operation: InventoryOperation) {
  const item = operationProduct(operation)
  return operation.kind === 'SALE'
    ? t('inventory.saleBatchItems', { count: operation.items.length })
    : item?.productSku
}
function movementProductImage(operation: InventoryOperation) {
  return operationProduct(operation)?.productImageUrl
}
function movementProductInitial(operation: InventoryOperation) {
  return movementProductName(operation).trim().slice(0, 1).toUpperCase()
}
function signedQuantity(operation: InventoryOperation) {
  const quantity = Number(operation.quantity)
  return `${quantity > 0 ? '+' : ''}${number(quantity)}`
}
function openAddition() {
  Object.assign(addition, { productId: '', quantity: 1, remark: '' })
  additionOpen.value = true
}
function openCorrection() {
  const selected = productFor(filterProductIds.value[0] || '')
  Object.assign(correction, {
    productId: selected?.id || '',
    quantity: selected?.currentStock || 0,
    remark: '',
  })
  correctionOpen.value = true
}
function selectCorrectionProduct() {
  correction.quantity = correctionProduct.value?.currentStock || 0
}
function openSale() {
  Object.assign(sale, {
    id: '',
    version: 0,
    eventId: '',
    items: [],
  })
  addSaleLine()
  saleOpen.value = true
}

function mergeSaleProducts(record: InventorySale) {
  const additions: Product[] = record.items
    .filter((item) => !productFor(item.productId))
    .map((item) => ({
      id: item.productId,
      sku: item.productSku,
      name: item.productName,
      salePrice: 0,
      currency: '',
      currentStock: item.currentStock,
      enabled: false,
      imageUrl: item.productImageUrl,
    }))
  products.value = [...products.value, ...additions]
}

async function openSaleEdit(operation: InventoryOperation) {
  try {
    const { data } = await api.get<InventorySale>(`/inventory/sales/${operation.id}`)
    mergeSaleProducts(data)
    Object.assign(sale, {
      id: data.id,
      version: data.version,
      eventId: data.eventId,
      items: data.items.map((item) => ({ productId: item.productId, quantity: item.quantity })),
    })
    saleOpen.value = true
  } catch (error) {
    showError(error)
  }
}
function addSaleLine() {
  if (sale.items.length < 100) sale.items.push({ productId: '', quantity: 1 })
}
function removeSaleLine(index: number) {
  if (sale.items.length > 1) sale.items.splice(index, 1)
}

async function addStock() {
  if (!addition.productId || addition.quantity <= 0) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    await api.post('/inventory/adjustments', {
      items: [
        {
          productId: addition.productId,
          type: 'ADJUSTMENT_IN',
          quantity: addition.quantity,
          reference: 'Stock in',
          remark: addition.remark.trim() || null,
        },
      ],
    })
    ElMessage.success(t('inventory.stockAdded'))
    additionOpen.value = false
    await load()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

async function correctStock() {
  if (!correction.productId || correction.quantity < 0 || correctionDelta.value === 0) {
    ElMessage.warning(
      t(correctionDelta.value === 0 ? 'inventory.stockUnchanged' : 'errors.validation'),
    )
    return
  }
  saving.value = true
  try {
    const { data } = await api.put<InventoryMovement>(`/inventory/stock/${correction.productId}`, {
      quantity: correction.quantity,
      remark: correction.remark.trim() || null,
    })
    const product = productFor(correction.productId)
    if (product) product.currentStock = data.stockAfter ?? correction.quantity
    ElMessage.success(t('inventory.stockCorrected'))
    correctionOpen.value = false
    await load()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

async function recordSale() {
  if (
    !sale.eventId ||
    !sale.items.length ||
    sale.items.some((line) => !line.productId || line.quantity <= 0)
  ) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    const payload = { eventId: sale.eventId, items: sale.items }
    if (sale.id) await api.put(`/inventory/sales/${sale.id}`, { ...payload, version: sale.version })
    else await api.post('/inventory/sales', payload)
    ElMessage.success(t(sale.id ? 'inventory.saleUpdated' : 'inventory.saleRecorded'))
    saleOpen.value = false
    await load()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

async function cancelSale(operation: InventoryOperation) {
  try {
    const details = operation.items
      .map((item) => `${item.productName} (${item.productSku}) × ${number(item.quantity)}`)
      .join(', ')
    await ElMessageBox.confirm(
      t('inventory.cancelSaleBody', { count: Math.abs(operation.quantity), details }),
      t('inventory.cancelSaleTitle'),
      { type: 'warning', confirmButtonText: t('inventory.undoSale') },
    )
    await api.post(`/inventory/sales/${operation.id}/cancel`, { version: operation.version })
    ElMessage.success(t('inventory.saleCancelled'))
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  }
}

async function exportCsv() {
  try {
    const { data } = await api.get<Blob>('/inventory/export', { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    const link = document.createElement('a')
    link.href = url
    link.download = `inventory-movements-${new Date().toISOString().slice(0, 10)}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    showError(error)
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('inventory.eyebrow')"
      :title="t('inventory.title')"
      :subtitle="t('inventory.subtitle')"
    >
      <template #actions
        ><ElButton :icon="Download" @click="exportCsv">{{ t('common.export') }}</ElButton
        ><ElButton :icon="Plus" @click="openAddition">{{ t('inventory.stockIn') }}</ElButton
        ><ElButton :icon="Edit" @click="openCorrection">{{ t('inventory.correctStock') }}</ElButton
        ><ElButton type="primary" :icon="Minus" @click="openSale">{{
          t('inventory.sell')
        }}</ElButton></template
      >
    </PageHeader>
    <section class="panel data-panel">
      <div class="table-toolbar">
        <ElSelect
          v-model="filterProductIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          clearable
          filterable
          remote
          reserve-keyword
          :remote-method="searchFilterProducts"
          :placeholder="t('inventory.selectProduct')"
          @change="reloadFirstPage"
        >
          <ElOption
            v-for="product in products"
            :key="product.id"
            :label="`${product.name} · ${product.sku}`"
            :value="product.id"
          />
        </ElSelect>
        <ElSelect
          v-model="filterTypes"
          multiple
          collapse-tags
          clearable
          :placeholder="t('inventory.movement')"
          @change="reloadFirstPage"
        >
          <ElOption
            v-for="type in movementTypes"
            :key="type"
            :label="movementLabel(type)"
            :value="type"
          />
        </ElSelect>
        <ElSelect
          v-model="filterCategories"
          multiple
          collapse-tags
          clearable
          :placeholder="t('inventory.productCategory')"
          @change="reloadFirstPage"
        >
          <ElOption v-for="category in productCategories" :key="category" :value="category" />
        </ElSelect>
        <ElSelect
          v-model="filterEventId"
          clearable
          filterable
          :placeholder="t('orders.event')"
          @change="reloadFirstPage"
          ><ElOption
            v-for="event in events"
            :key="event.id"
            :label="eventLabel(event)"
            :value="event.id"
        /></ElSelect>
        <ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton>
        <span class="table-toolbar__count">{{
          t('common.items', { count: page.totalElements })
        }}</span>
      </div>
      <ElTable
        v-if="page.items.length || loading"
        v-loading="loading"
        :data="page.items"
        row-key="id"
      >
        <ElTableColumn type="expand" width="48">
          <template #default="scope">
            <div v-if="scope.row.kind === 'SALE'" class="sale-operation-items">
              <div v-for="item in scope.row.items" :key="item.productId">
                <span>{{ item.productName }} · {{ item.productSku }}</span>
                <strong>−{{ number(item.quantity) }}</strong>
              </div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('inventory.product')" min-width="260"
          ><template #default="scope"
            ><div class="inventory-product-cell">
              <div class="product-thumb" aria-hidden="true">
                <SecureImage :src="movementProductImage(scope.row)" alt=""
                  ><span>{{ movementProductInitial(scope.row) }}</span></SecureImage
                >
              </div>
              <div class="cell-stack">
                <strong>{{ movementProductName(scope.row) }}</strong
                ><code class="sku-code">{{ movementProductSku(scope.row) }}</code>
              </div>
            </div></template
          ></ElTableColumn
        >
        <ElTableColumn :label="t('inventory.movement')" min-width="160"
          ><template #default="scope">{{ movementLabel(scope.row.type) }}</template></ElTableColumn
        >
        <ElTableColumn :label="t('inventory.quantity')" align="right" width="110"
          ><template #default="scope"
            ><strong class="movement-quantity" :data-positive="scope.row.quantity > 0">{{
              signedQuantity(scope.row)
            }}</strong></template
          ></ElTableColumn
        >
        <ElTableColumn :label="t('inventory.stockBefore')" align="right" width="100"
          ><template #default="scope">{{ scope.row.stockBefore ?? '—' }}</template></ElTableColumn
        >
        <ElTableColumn :label="t('inventory.stockAfter')" align="right" width="100"
          ><template #default="scope">{{ scope.row.stockAfter ?? '—' }}</template></ElTableColumn
        >
        <ElTableColumn
          prop="eventName"
          :label="t('orders.event')"
          min-width="210"
          show-overflow-tooltip
        />
        <ElTableColumn :label="t('inventory.attributedDate')" min-width="145"
          ><template #default="scope">{{
            scope.row.attributedDate ? date(scope.row.attributedDate) : '—'
          }}</template></ElTableColumn
        >
        <ElTableColumn
          prop="remark"
          :label="t('inventory.reason')"
          min-width="220"
          show-overflow-tooltip
        />
        <ElTableColumn :label="t('common.date')" min-width="160"
          ><template #default="scope">{{ dateTime(scope.row.createdAt) }}</template></ElTableColumn
        >
        <ElTableColumn :label="t('common.actions')" width="150" fixed="right">
          <template #default="scope">
            <template v-if="scope.row.kind === 'SALE'">
              <ElButton
                text
                :icon="Edit"
                :aria-label="t('common.edit')"
                @click="openSaleEdit(scope.row)"
              />
              <ElButton
                text
                type="danger"
                :icon="Delete"
                :aria-label="t('inventory.undoSale')"
                @click="cancelSale(scope.row)"
              />
            </template>
          </template>
        </ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('inventory.emptyTitle')" :body="t('inventory.emptyBody')"
        ><ElButton type="primary" :icon="Minus" @click="openSale">{{
          t('inventory.sell')
        }}</ElButton></EmptyState
      >
      <ElPagination
        v-if="page.totalPages > 1"
        v-model:current-page="currentPage"
        class="table-pagination"
        background
        layout="prev, pager, next"
        :page-size="pageSize"
        :total="page.totalElements"
        @current-change="load"
      />
    </section>

    <ElDialog v-model="additionOpen" :title="t('inventory.stockIn')" width="min(540px, 94vw)">
      <ElForm label-position="top">
        <ElFormItem :label="t('inventory.product')" required>
          <ProductSelect
            v-model="addition.productId"
            class="full-width"
            :products="products"
            :remote-method="searchProducts"
            :placeholder="t('inventory.selectProduct')"
          />
        </ElFormItem>
        <ElFormItem :label="t('inventory.quantity')" required
          ><ElInputNumber
            v-model="addition.quantity"
            :min="1"
            :precision="0"
            controls-position="right"
        /></ElFormItem>
        <ElFormItem :label="`${t('inventory.reason')} · ${t('common.optional')}`"
          ><ElInput
            v-model="addition.remark"
            type="textarea"
            :rows="3"
            :placeholder="t('inventory.reasonPlaceholder')"
        /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="additionOpen = false">{{ t('common.cancel') }}</ElButton
        ><ElButton type="primary" :loading="saving" @click="addStock">{{
          saving ? t('common.saving') : t('inventory.stockIn')
        }}</ElButton></template
      >
    </ElDialog>

    <ElDialog
      v-model="correctionOpen"
      :title="t('inventory.correctStock')"
      width="min(540px, 94vw)"
    >
      <ElAlert :title="t('inventory.correctionHint')" type="info" show-icon :closable="false" />
      <ElForm label-position="top" class="inventory-sale-context">
        <ElFormItem :label="t('inventory.product')" required>
          <ProductSelect
            v-model="correction.productId"
            class="full-width"
            :products="products"
            :remote-method="searchProducts"
            :placeholder="t('inventory.selectProduct')"
            @change="selectCorrectionProduct"
          />
        </ElFormItem>
        <div v-if="correctionProduct" class="inline-stock stock-correction-preview">
          <span
            >{{ t('inventory.currentStock') }}
            <strong>{{ number(correctionProduct.currentStock) }}</strong></span
          >
          <span>→</span>
          <span
            >{{ t('inventory.correctedStock') }}
            <strong>{{ number(correction.quantity) }}</strong></span
          >
          <small :data-positive="correctionDelta > 0"
            >{{ correctionDelta > 0 ? '+' : '' }}{{ number(correctionDelta) }}</small
          >
        </div>
        <ElFormItem :label="t('inventory.exactQuantity')" required
          ><ElInputNumber
            v-model="correction.quantity"
            :min="0"
            :precision="0"
            controls-position="right"
        /></ElFormItem>
        <ElFormItem :label="`${t('inventory.reason')} · ${t('common.optional')}`"
          ><ElInput
            v-model="correction.remark"
            type="textarea"
            :rows="3"
            :placeholder="t('inventory.correctionReasonPlaceholder')"
        /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="correctionOpen = false">{{ t('common.cancel') }}</ElButton
        ><ElButton
          type="primary"
          :loading="saving"
          :disabled="!correction.productId || correctionDelta === 0"
          @click="correctStock"
          >{{ saving ? t('common.saving') : t('inventory.applyCorrection') }}</ElButton
        ></template
      >
    </ElDialog>

    <ElDialog
      v-model="saleOpen"
      :title="t(sale.id ? 'inventory.editSale' : 'inventory.recordSale')"
      width="min(900px, 96vw)"
      destroy-on-close
    >
      <ElForm label-position="top" class="form-grid inventory-sale-context">
        <ElFormItem :label="t('orders.event')" required
          ><ElSelect
            v-model="sale.eventId"
            filterable
            class="full-width"
            :placeholder="t('orders.selectEvent')"
            ><ElOption
              v-for="event in sale.id ? events : activeEvents"
              :key="event.id"
              :label="eventLabel(event)"
              :value="event.id" /></ElSelect
        ></ElFormItem>
      </ElForm>
      <div v-if="selectedEvent" class="sale-attribution-date">
        <span>{{ t('inventory.attributedDate') }}</span
        ><strong>{{ selectedEvent.endDate }}</strong
        ><small>{{ t('inventory.attributedDateHint') }}</small>
      </div>
      <div class="inventory-sale-editor">
        <div class="order-editor__heading">
          <span
            ><strong>{{ t('inventory.soldProducts') }}</strong
            ><small>{{ t('common.items', { count: saleUnits }) }}</small></span
          ><ElButton text :icon="Plus" :disabled="sale.items.length >= 100" @click="addSaleLine">{{
            t('orders.addItem')
          }}</ElButton>
        </div>
        <div v-for="(line, index) in sale.items" :key="index" class="inventory-sale-row">
          <ProductSelect
            v-model="line.productId"
            :products="products"
            :remote-method="searchSaleProducts"
            :placeholder="t('inventory.selectProduct')"
            :disabled-product-ids="
              sale.items
                .filter((item, itemIndex) => itemIndex !== index && item.productId)
                .map((item) => item.productId)
            "
          />
          <ElInputNumber
            v-model="line.quantity"
            :min="1"
            :precision="0"
            controls-position="right"
          />
          <ElButton
            text
            type="danger"
            :icon="Close"
            :disabled="sale.items.length === 1"
            :aria-label="t('common.delete')"
            @click="removeSaleLine(index)"
          />
        </div>
      </div>
      <template #footer
        ><ElButton @click="saleOpen = false">{{ t('common.cancel') }}</ElButton
        ><ElButton type="primary" :loading="saving" @click="recordSale">{{
          saving ? t('common.saving') : t(sale.id ? 'common.save' : 'inventory.recordSale')
        }}</ElButton></template
      >
    </ElDialog>
  </div>
</template>
