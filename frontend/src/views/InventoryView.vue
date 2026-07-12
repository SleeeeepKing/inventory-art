<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Close, Download, Minus, Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { InventoryMovement, PageResponse, Product, SalesEvent } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

interface SaleLine { productId: string; quantity: number; unitPrice: number }

const { t, te } = useI18n()
const { showError } = useApiFeedback()
const { number, money, date, dateTime, defaultCurrency } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const saleOpen = ref(false)
const additionOpen = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const page = ref<PageResponse<InventoryMovement>>(normalizePage([]))
const products = ref<Product[]>([])
const events = ref<SalesEvent[]>([])
const filterProductId = ref('')
const filterChannel = ref('')
const filterEventId = ref('')
const addition = reactive({ productId: '', quantity: 1, remark: '' })
const sale = reactive({ salesChannel: 'EXHIBITION', eventId: '', currency: 'EUR', remark: '', items: [] as SaleLine[] })
const activeEvents = computed(() => events.value.filter((event) => event.enabled))
const selectedEvent = computed(() => events.value.find((event) => event.id === sale.eventId))
const saleUnits = computed(() => sale.items.reduce((sum, line) => sum + Number(line.quantity || 0), 0))
const saleAmount = computed(() => sale.items.reduce((sum, line) => sum + Number(line.quantity || 0) * Number(line.unitPrice || 0), 0))
let productSearchTimer: ReturnType<typeof setTimeout> | undefined

async function load() {
  loading.value = true
  try {
    const movements = await api.get<PageResponse<InventoryMovement> | InventoryMovement[]>('/inventory/movements', { params: { page: currentPage.value - 1, size: pageSize.value, productId: filterProductId.value || undefined, channel: filterChannel.value || undefined, eventId: filterEventId.value || undefined } })
    page.value = normalizePage(movements.data, currentPage.value - 1, pageSize.value)
    if (!products.value.length) {
      const productResponse = await api.get<PageResponse<Product> | Product[]>('/products', { params: { page: 0, size: 20, enabled: true } })
      products.value = normalizePage(productResponse.data, 0, 500).items
    }
    if (!events.value.length) {
      const { data } = await api.get<SalesEvent[]>('/sales-events')
      events.value = data
    }
  } catch (error) { showError(error) } finally { loading.value = false }
}
async function searchProductsNow(query: string) {
  const { data } = await api.get<PageResponse<Product> | Product[]>('/products', { params: { page: 0, size: 20, enabled: true, q: query || undefined } })
  const found = normalizePage(data, 0, 20).items
  const selected = new Set([filterProductId.value, addition.productId, ...sale.items.map((line) => line.productId)].filter(Boolean))
  const retained = products.value.filter((product) => selected.has(product.id))
  products.value = [...retained, ...found.filter((product) => !retained.some((item) => item.id === product.id))]
}
function searchProducts(query: string) {
  if (productSearchTimer) clearTimeout(productSearchTimer)
  productSearchTimer = setTimeout(() => { void searchProductsNow(query) }, query ? 250 : 0)
}

function movementLabel(type: string) {
  return te(`inventory.movementTypes.${type}`) ? t(`inventory.movementTypes.${type}`) : type.replaceAll('_', ' ')
}
function channelLabel(channel?: string) {
  const key = `orders.channels.${String(channel || '').toLowerCase()}`
  return channel && te(key) ? t(key) : channel || '—'
}
function eventLabel(event: SalesEvent) { return `${event.name} · ${event.startDate} — ${event.endDate}` }
function productFor(id: string) { return products.value.find((product) => product.id === id) }
function signedQuantity(movement: InventoryMovement) {
  const quantity = Number(movement.quantity)
  return `${quantity > 0 ? '+' : ''}${number(quantity)}`
}
function openAddition() {
  Object.assign(addition, { productId: '', quantity: 1, remark: '' })
  additionOpen.value = true
}
function openSale() {
  Object.assign(sale, { salesChannel: 'EXHIBITION', eventId: '', currency: defaultCurrency.value, remark: '', items: [] })
  addSaleLine()
  saleOpen.value = true
}
function addSaleLine() { if (sale.items.length < 100) sale.items.push({ productId: '', quantity: 1, unitPrice: 0 }) }
function removeSaleLine(index: number) { if (sale.items.length > 1) sale.items.splice(index, 1) }
function selectSaleProduct(line: SaleLine) {
  const product = productFor(line.productId)
  if (product) line.unitPrice = Number(product.salePrice)
}
function changeSaleChannel() { if (sale.salesChannel !== 'EXHIBITION') sale.eventId = '' }
function changeFilterChannel() { if (filterChannel.value !== 'EXHIBITION') filterEventId.value = ''; currentPage.value = 1; void load() }

async function addStock() {
  if (!addition.productId || addition.quantity <= 0) { ElMessage.warning(t('errors.validation')); return }
  saving.value = true
  try {
    await api.post('/inventory/adjustments', { items: [{ productId: addition.productId, type: 'ADJUSTMENT_IN', quantity: addition.quantity, reference: 'Stock in', remark: addition.remark.trim() || null }] })
    ElMessage.success(t('inventory.stockAdded'))
    additionOpen.value = false
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function recordSale() {
  if (!sale.salesChannel || (sale.salesChannel === 'EXHIBITION' && !sale.eventId) || !sale.items.length || sale.items.some((line) => !line.productId || line.quantity <= 0 || line.unitPrice < 0)) {
    ElMessage.warning(t('errors.validation')); return
  }
  saving.value = true
  try {
    await api.post('/inventory/sales', { ...sale, eventId: sale.eventId || null, remark: sale.remark.trim() || null })
    ElMessage.success(t('inventory.saleRecorded'))
    saleOpen.value = false
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
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
  } catch (error) { showError(error) }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader :eyebrow="t('inventory.eyebrow')" :title="t('inventory.title')" :subtitle="t('inventory.subtitle')">
      <template #actions><ElButton :icon="Download" @click="exportCsv">{{ t('common.export') }}</ElButton><ElButton :icon="Plus" @click="openAddition">{{ t('inventory.stockIn') }}</ElButton><ElButton type="primary" :icon="Minus" @click="openSale">{{ t('inventory.sell') }}</ElButton></template>
    </PageHeader>
    <section class="panel data-panel">
      <div class="table-toolbar">
        <ElSelect v-model="filterProductId" clearable filterable remote reserve-keyword :remote-method="searchProducts" :placeholder="t('inventory.selectProduct')" @change="currentPage = 1; load()">
          <ElOption v-for="product in products" :key="product.id" :label="`${product.name} · ${product.sku}`" :value="product.id" />
        </ElSelect>
        <ElSelect v-model="filterChannel" clearable :placeholder="t('orders.channel')" @change="changeFilterChannel"><ElOption :label="t('orders.channels.exhibition')" value="EXHIBITION" /><ElOption :label="t('orders.channels.online')" value="ONLINE" /><ElOption :label="t('orders.channels.other')" value="OTHER" /></ElSelect>
        <ElSelect v-if="filterChannel === 'EXHIBITION'" v-model="filterEventId" clearable filterable :placeholder="t('orders.event')" @change="currentPage = 1; load()"><ElOption v-for="event in activeEvents" :key="event.id" :label="eventLabel(event)" :value="event.id" /></ElSelect>
        <ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton>
        <span class="table-toolbar__count">{{ t('common.items', { count: page.totalElements }) }}</span>
      </div>
      <ElTable v-if="page.items.length || loading" v-loading="loading" :data="page.items" row-key="id">
        <ElTableColumn :label="t('inventory.product')" min-width="220"><template #default="scope"><div class="cell-stack"><strong>{{ productFor(scope.row.productId)?.name || scope.row.productId }}</strong><code class="sku-code">{{ productFor(scope.row.productId)?.sku }}</code></div></template></ElTableColumn>
        <ElTableColumn :label="t('inventory.movement')" min-width="160"><template #default="scope">{{ movementLabel(scope.row.type) }}</template></ElTableColumn>
        <ElTableColumn :label="t('inventory.quantity')" align="right" width="110"><template #default="scope"><strong class="movement-quantity" :data-positive="scope.row.quantity > 0">{{ signedQuantity(scope.row) }}</strong></template></ElTableColumn>
        <ElTableColumn :label="t('inventory.stockBefore')" align="right" width="100"><template #default="scope">{{ number(scope.row.stockBefore) }}</template></ElTableColumn>
        <ElTableColumn :label="t('inventory.stockAfter')" align="right" width="100"><template #default="scope">{{ number(scope.row.stockAfter) }}</template></ElTableColumn>
        <ElTableColumn :label="t('orders.channel')" min-width="120"><template #default="scope">{{ channelLabel(scope.row.salesChannel) }}</template></ElTableColumn>
        <ElTableColumn prop="eventName" :label="t('orders.event')" min-width="210" show-overflow-tooltip />
        <ElTableColumn :label="t('inventory.actualPrice')" min-width="130" align="right"><template #default="scope">{{ scope.row.unitPrice == null ? '—' : money(scope.row.unitPrice, scope.row.currency) }}</template></ElTableColumn>
        <ElTableColumn :label="t('inventory.attributedAmount')" min-width="150" align="right"><template #default="scope">{{ scope.row.attributedAmount == null ? '—' : money(scope.row.attributedAmount, scope.row.currency) }}</template></ElTableColumn>
        <ElTableColumn :label="t('inventory.attributedDate')" min-width="145"><template #default="scope">{{ scope.row.attributedDate ? date(scope.row.attributedDate) : '—' }}</template></ElTableColumn>
        <ElTableColumn prop="remark" :label="t('inventory.reason')" min-width="220" show-overflow-tooltip />
        <ElTableColumn :label="t('common.date')" min-width="160"><template #default="scope">{{ dateTime(scope.row.createdAt) }}</template></ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('inventory.emptyTitle')" :body="t('inventory.emptyBody')"><ElButton type="primary" :icon="Minus" @click="openSale">{{ t('inventory.sell') }}</ElButton></EmptyState>
      <ElPagination v-if="page.totalPages > 1" v-model:current-page="currentPage" class="table-pagination" background layout="prev, pager, next" :page-size="pageSize" :total="page.totalElements" @current-change="load" />
    </section>

    <ElDialog v-model="additionOpen" :title="t('inventory.stockIn')" width="min(540px, 94vw)">
      <ElForm label-position="top">
        <ElFormItem :label="t('inventory.product')" required><ElSelect v-model="addition.productId" filterable remote reserve-keyword :remote-method="searchProducts" class="full-width" :placeholder="t('inventory.selectProduct')"><ElOption v-for="product in products" :key="product.id" :label="`${product.name} · ${product.sku} (${product.currentStock})`" :value="product.id" /></ElSelect></ElFormItem>
        <ElFormItem :label="t('inventory.quantity')" required><ElInputNumber v-model="addition.quantity" :min="1" :precision="0" controls-position="right" /></ElFormItem>
        <ElFormItem :label="`${t('inventory.reason')} · ${t('common.optional')}`"><ElInput v-model="addition.remark" type="textarea" :rows="3" :placeholder="t('inventory.reasonPlaceholder')" /></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="additionOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="addStock">{{ saving ? t('common.saving') : t('inventory.stockIn') }}</ElButton></template>
    </ElDialog>

    <ElDialog v-model="saleOpen" :title="t('inventory.recordSale')" width="min(900px, 96vw)" destroy-on-close>
      <ElAlert :title="t('inventory.attributionWarning')" type="info" show-icon :closable="false" />
      <ElForm label-position="top" class="form-grid inventory-sale-context">
        <ElFormItem :label="t('orders.channel')" required><ElSelect v-model="sale.salesChannel" class="full-width" @change="changeSaleChannel"><ElOption :label="t('orders.channels.exhibition')" value="EXHIBITION" /><ElOption :label="t('orders.channels.online')" value="ONLINE" /><ElOption :label="t('orders.channels.other')" value="OTHER" /></ElSelect></ElFormItem>
        <ElFormItem v-if="sale.salesChannel === 'EXHIBITION'" :label="t('orders.event')" required><ElSelect v-model="sale.eventId" filterable class="full-width" :placeholder="t('orders.selectEvent')"><ElOption v-for="event in activeEvents" :key="event.id" :label="eventLabel(event)" :value="event.id" /></ElSelect></ElFormItem>
        <ElFormItem :label="t('products.currency')" required><ElInput v-model="sale.currency" maxlength="3" /></ElFormItem>
        <ElFormItem :label="`${t('inventory.reason')} · ${t('common.optional')}`"><ElInput v-model="sale.remark" /></ElFormItem>
      </ElForm>
      <div v-if="selectedEvent" class="sale-attribution-date"><span>{{ t('inventory.attributedDate') }}</span><strong>{{ selectedEvent.endDate }}</strong><small>{{ t('inventory.attributedDateHint') }}</small></div>
      <div class="inventory-sale-editor">
        <div class="order-editor__heading"><span><strong>{{ t('inventory.soldProducts') }}</strong><small>{{ t('inventory.saleSummary', { count: saleUnits, amount: money(saleAmount, sale.currency) }) }}</small></span><ElButton text :icon="Plus" :disabled="sale.items.length >= 100" @click="addSaleLine">{{ t('orders.addItem') }}</ElButton></div>
        <div v-for="(line, index) in sale.items" :key="index" class="inventory-sale-row">
          <ElSelect v-model="line.productId" filterable remote reserve-keyword :remote-method="searchProducts" :placeholder="t('inventory.selectProduct')" @change="selectSaleProduct(line)"><ElOption v-for="product in products" :key="product.id" :label="`${product.name} · ${product.sku} (${product.currentStock})`" :value="product.id" :disabled="sale.items.some((item, itemIndex) => itemIndex !== index && item.productId === product.id)" /></ElSelect>
          <ElInputNumber v-model="line.quantity" :min="1" :precision="0" controls-position="right" />
          <ElInputNumber v-model="line.unitPrice" :min="0" :precision="2" controls-position="right" />
          <strong>{{ money(line.quantity * line.unitPrice, sale.currency) }}</strong>
          <ElButton text type="danger" :icon="Close" :disabled="sale.items.length === 1" :aria-label="t('common.delete')" @click="removeSaleLine(index)" />
        </div>
      </div>
      <template #footer><ElButton @click="saleOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="recordSale">{{ saving ? t('common.saving') : t('inventory.recordSale') }}</ElButton></template>
    </ElDialog>
  </div>
</template>
