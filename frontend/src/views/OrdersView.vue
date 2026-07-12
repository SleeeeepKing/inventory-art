<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Close, Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { Order, OrderBatchCreateResponse, OrderBatchFailure, OrderBatchResponse, PageResponse, Product, SalesEvent } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'

interface DraftItem { productId: string; quantity: number }

const { t } = useI18n()
const { showError } = useApiFeedback()
const { money, dateTime, defaultCurrency } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const batchCreateOpen = ref(false)
const eventsDialogOpen = ref(false)
const eventEditorOpen = ref(false)
const eventSaving = ref(false)
const batchProcessing = ref(false)
const batchResultOpen = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const eventFilter = ref('')
const page = ref<PageResponse<Order>>(normalizePage([]))
const products = ref<Product[]>([])
const events = ref<SalesEvent[]>([])
const selectedOrders = ref<Order[]>([])
const batchFailures = ref<OrderBatchFailure[]>([])
const loadedOrderDetails = new Set<string>()
const today = new Date().toISOString().slice(0, 10)
const eventEditor = reactive({ id: '', name: '', startDate: today, endDate: today, enabled: true, selectAfterCreate: false })
const form = reactive({ customerName: '', customerEmail: '', orderDate: new Date().toISOString(), salesChannel: 'OTHER', eventId: '', currency: 'EUR', totalAmount: 0, customerNote: '', items: [] as DraftItem[] })
const batchForm = reactive({ eventId: '', currency: 'EUR', paymentMethod: 'OTHER', paymentStatus: 'PAID', orderDate: new Date().toISOString(), amounts: [0] as number[] })
const activeEvents = computed(() => events.value.filter((event) => event.enabled))
const batchTotal = computed(() => batchForm.amounts.reduce((sum, amount) => sum + Number(amount || 0), 0))
const batchConfirmOrders = computed(() => selectedOrders.value.filter((order) => order.status === 'DRAFT'))
const batchCancelOrders = computed(() => selectedOrders.value.filter((order) => ['DRAFT', 'CONFIRMED'].includes(order.status)))
let productSearchTimer: ReturnType<typeof setTimeout> | undefined

async function load() {
  loading.value = true
  try {
    const [orders, productResponse, eventResponse] = await Promise.all([
      api.get<PageResponse<Order> | Order[]>('/orders', { params: { page: currentPage.value - 1, size: pageSize.value, status: statusFilter.value || undefined, eventId: eventFilter.value || undefined } }),
      api.get<PageResponse<Product> | Product[]>('/products', { params: { page: 0, size: 20, enabled: true } }),
      api.get<SalesEvent[]>('/sales-events', { params: { includeDisabled: true } }),
    ])
    loadedOrderDetails.clear()
    page.value = normalizePage(orders.data, currentPage.value - 1, pageSize.value)
    products.value = normalizePage(productResponse.data, 0, 500).items
    events.value = eventResponse.data
  } catch (error) { showError(error) } finally { loading.value = false }
}
async function searchProductsNow(query: string) {
  const { data } = await api.get<PageResponse<Product> | Product[]>('/products', { params: { page: 0, size: 20, enabled: true, q: query || undefined } })
  const found = normalizePage(data, 0, 20).items
  const selected = new Set(form.items.map((item) => item.productId).filter(Boolean))
  const retained = products.value.filter((product) => selected.has(product.id))
  products.value = [...retained, ...found.filter((product) => !retained.some((item) => item.id === product.id))]
}
function searchProducts(query: string) {
  if (productSearchTimer) clearTimeout(productSearchTimer)
  productSearchTimer = setTimeout(() => { void searchProductsNow(query) }, query ? 250 : 0)
}

function openCreate() {
  Object.assign(form, { customerName: '', customerEmail: '', orderDate: new Date().toISOString(), salesChannel: 'OTHER', eventId: '', currency: defaultCurrency.value, totalAmount: 0, customerNote: '', items: [] })
  dialogOpen.value = true
}

function openBatchCreate() {
  Object.assign(batchForm, { eventId: '', currency: defaultCurrency.value, paymentMethod: 'OTHER', paymentStatus: 'PAID', orderDate: new Date().toISOString(), amounts: [0] })
  batchCreateOpen.value = true
}

function addBatchRow() { if (batchForm.amounts.length < 100) batchForm.amounts.push(0) }
function removeBatchRow(index: number) { if (batchForm.amounts.length > 1) batchForm.amounts.splice(index, 1) }
function eventLabel(event: SalesEvent) { return `${event.name} · ${event.startDate} — ${event.endDate}` }

function addItem() { form.items.push({ productId: '', quantity: 1 }) }
function removeItem(index: number) { form.items.splice(index, 1) }
function changeChannel(channel: string) { if (channel !== 'EXHIBITION') form.eventId = '' }
function selectEvent() { if (form.eventId) form.salesChannel = 'EXHIBITION' }

function openEventEditor(event?: SalesEvent, selectAfterCreate = false) {
  Object.assign(eventEditor, { id: event?.id || '', name: event?.name || '', startDate: event?.startDate || today, endDate: event?.endDate || today, enabled: event?.enabled ?? true, selectAfterCreate })
  eventEditorOpen.value = true
}

async function saveEvent() {
  const name = eventEditor.name.trim()
  if (!name || !eventEditor.startDate || !eventEditor.endDate || eventEditor.endDate < eventEditor.startDate) { ElMessage.warning(t('validation.dateRange')); return }
  eventSaving.value = true
  try {
    const { data } = eventEditor.id
      ? await api.put<SalesEvent>(`/sales-events/${eventEditor.id}`, { name, startDate: eventEditor.startDate, endDate: eventEditor.endDate, enabled: eventEditor.enabled })
      : await api.post<SalesEvent>('/sales-events', { name, startDate: eventEditor.startDate, endDate: eventEditor.endDate })
    const { data: refreshed } = await api.get<SalesEvent[]>('/sales-events', { params: { includeDisabled: true } })
    events.value = refreshed
    if (!eventEditor.id && eventEditor.selectAfterCreate) {
      form.eventId = data.id
      form.salesChannel = 'EXHIBITION'
    }
    ElMessage.success(t(eventEditor.id ? 'orders.eventUpdated' : 'orders.eventCreated'))
    eventEditorOpen.value = false
  } catch (error) { showError(error) } finally { eventSaving.value = false }
}

async function createBatchOrders() {
  if (!batchForm.eventId || !batchForm.orderDate || batchForm.amounts.some((amount) => Number(amount) <= 0)) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    const { data } = await api.post<OrderBatchCreateResponse>('/orders/batch', {
      eventId: batchForm.eventId,
      currency: batchForm.currency,
      paymentMethod: batchForm.paymentMethod,
      paymentStatus: batchForm.paymentStatus,
      orderDate: batchForm.orderDate,
      orders: batchForm.amounts.map((totalAmount) => ({ totalAmount })),
    })
    ElMessage.success(t('orders.batchRecorded', { count: data.orderCount }))
    batchCreateOpen.value = false
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function toggleEvent(event: SalesEvent, enabled: boolean) {
  try {
    await api.post(`/sales-events/${event.id}/enabled`, { enabled })
    event.enabled = enabled
    ElMessage.success(t('orders.eventUpdated'))
  } catch (error) { showError(error) }
}
function selectProduct(item: DraftItem) {
  const product = products.value.find((candidate) => candidate.id === item.productId)
  if (product) form.currency = product.currency
}

async function createOrder() {
  if (form.totalAmount <= 0 || form.items.some((item) => !item.productId || item.quantity <= 0)) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    await api.post('/orders', {
      ...form,
      customerName: form.customerName || null,
      customerEmail: form.customerEmail || null,
      eventId: form.eventId || null,
      eventName: events.value.find((event) => event.id === form.eventId)?.name || null,
      paymentMethod: 'OTHER',
      paymentStatus: 'PAID',
      items: form.items.map((item) => ({ ...item, unitPrice: null, discountAmount: 0, taxRate: 0 })),
    })
    ElMessage.success(t('orders.recorded'))
    dialogOpen.value = false
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

function canBatchSelect(order: Order) {
  return ['DRAFT', 'CONFIRMED'].includes(order.status)
}

async function loadOrderDetails(order: Order, expandedRows: Order[]) {
  if (!expandedRows.some((row) => row.id === order.id) || loadedOrderDetails.has(order.id)) return
  loadedOrderDetails.add(order.id)
  try {
    const { data } = await api.get<Order>(`/orders/${order.id}`)
    order.items = data.items
  } catch (error) {
    loadedOrderDetails.delete(order.id)
    showError(error)
  }
}

async function batchTransition(action: 'confirm' | 'cancel') {
  const candidates = action === 'confirm' ? batchConfirmOrders.value : batchCancelOrders.value
  if (!candidates.length) return
  try {
    await ElMessageBox.confirm(
      t(action === 'confirm' ? 'orders.batchConfirmBody' : 'orders.batchCancelBody', { count: candidates.length }),
      t(action === 'confirm' ? 'orders.batchConfirmTitle' : 'orders.batchCancelTitle'),
      { type: 'warning', confirmButtonText: t(action === 'confirm' ? 'orders.batchConfirm' : 'orders.batchCancel'), cancelButtonText: t('common.cancel') },
    )
    batchProcessing.value = true
    const { data } = await api.post<OrderBatchResponse>(`/orders/batch-${action}`, { orderIds: candidates.map((order) => order.id) })
    batchFailures.value = data.failed
    if (data.failed.length) {
      batchResultOpen.value = true
      ElMessage.warning(t('orders.batchPartial', { succeeded: data.succeeded.length, failed: data.failed.length }))
    } else {
      ElMessage.success(t('orders.batchSucceeded', { count: data.succeeded.length }))
    }
    selectedOrders.value = []
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  } finally { batchProcessing.value = false }
}

async function transition(order: Order, action: 'confirm' | 'cancel' | 'refund') {
  try {
    await ElMessageBox.confirm(t(`orders.${action}Body`), t(`orders.${action}Title`), { type: 'warning', confirmButtonText: t(`orders.${action}Order`), cancelButtonText: t('common.cancel') })
    if (action === 'refund') {
      const { data: detail } = await api.get<Order>(`/orders/${order.id}`)
      const items = detail.items.filter((item) => (item.quantity - (item.refundedQuantity || 0)) > 0).map((item) => ({ orderItemId: item.id, quantity: item.quantity - (item.refundedQuantity || 0) }))
      const amount = Math.max(0, Number(detail.totalAmount) - Number(detail.refundAmount || 0))
      await api.post(`/orders/${order.id}/refunds`, { items, amount, reason: t('orders.refundOrder') })
    } else {
      await api.post(`/orders/${order.id}/${action}`)
    }
    ElMessage.success(t(`orders.${action === 'confirm' ? 'confirmed' : action === 'cancel' ? 'cancelled' : 'refunded'}`))
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader :eyebrow="t('orders.eyebrow')" :title="t('orders.title')" :subtitle="t('orders.subtitle')">
      <template #actions><ElButton :icon="Plus" @click="openCreate">{{ t('orders.newOrder') }}</ElButton><ElButton type="primary" :icon="Plus" @click="openBatchCreate">{{ t('orders.batchRecord') }}</ElButton></template>
    </PageHeader>
    <section class="panel data-panel">
      <div class="table-toolbar">
        <ElSelect v-model="statusFilter" :placeholder="t('common.status')" clearable @change="currentPage = 1; load()">
          <ElOption :label="t('orders.filters.draft')" value="DRAFT" /><ElOption :label="t('orders.filters.active')" value="CONFIRMED" /><ElOption :label="t('orders.filters.completed')" value="COMPLETED" /><ElOption :label="t('orders.filters.cancelled')" value="CANCELLED" />
        </ElSelect>
        <ElSelect v-model="eventFilter" :placeholder="t('orders.event')" clearable filterable @change="currentPage = 1; load()">
          <ElOption v-for="event in events" :key="event.id" :label="eventLabel(event)" :value="event.id" />
        </ElSelect>
        <ElButton @click="eventsDialogOpen = true">{{ t('orders.manageEvents') }}</ElButton>
        <ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton>
        <span class="table-toolbar__count">{{ t('common.items', { count: page.totalElements }) }}</span>
      </div>
      <div v-if="selectedOrders.length" class="table-toolbar">
        <span>{{ t('orders.selectedCount', { count: selectedOrders.length }) }}</span>
        <ElButton type="primary" :loading="batchProcessing" :disabled="!batchConfirmOrders.length" @click="batchTransition('confirm')">{{ t('orders.batchConfirm') }} ({{ batchConfirmOrders.length }})</ElButton>
        <ElButton type="danger" plain :loading="batchProcessing" :disabled="!batchCancelOrders.length" @click="batchTransition('cancel')">{{ t('orders.batchCancel') }} ({{ batchCancelOrders.length }})</ElButton>
      </div>
      <ElTable v-if="page.items.length || loading" v-loading="loading" :data="page.items" row-key="id" @selection-change="selectedOrders = $event" @expand-change="loadOrderDetails">
        <ElTableColumn type="selection" width="48" :selectable="canBatchSelect" />
        <ElTableColumn type="expand"><template #default="scope"><div class="order-lines"><div v-for="item in scope.row.items" :key="item.id || item.productId"><span><strong>{{ item.productName || item.sku }}</strong><small>{{ item.sku }}</small></span><span>{{ t('orders.quantity') }}: {{ item.quantity }}</span><b>{{ money(item.lineTotal ?? item.quantity * item.unitPrice, scope.row.currency) }}</b></div><p v-if="!scope.row.items?.length">{{ t('orders.noItems') }}</p></div></template></ElTableColumn>
        <ElTableColumn prop="orderNumber" :label="t('orders.orderNumber')" min-width="150"><template #default="scope"><code class="order-code">{{ scope.row.orderNumber }}</code></template></ElTableColumn>
        <ElTableColumn :label="t('orders.customer')" min-width="180"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.customerName || '—' }}</strong><small>{{ scope.row.customerEmail }}</small></div></template></ElTableColumn>
        <ElTableColumn :label="t('orders.orderedAt')" min-width="165"><template #default="scope">{{ dateTime(scope.row.orderDate) }}</template></ElTableColumn>
        <ElTableColumn :label="t('orders.event')" min-width="220"><template #default="scope">{{ scope.row.eventName || '—' }}</template></ElTableColumn>
        <ElTableColumn :label="t('orders.source')" prop="source" min-width="110" />
        <ElTableColumn :label="t('common.status')" min-width="170"><template #default="scope"><StatusPill :status="scope.row.status" /></template></ElTableColumn>
        <ElTableColumn :label="t('orders.total')" min-width="130" align="right"><template #default="scope"><strong>{{ money(scope.row.totalAmount, scope.row.currency) }}</strong></template></ElTableColumn>
        <ElTableColumn :label="t('common.actions')" width="175" fixed="right"><template #default="scope"><ElTooltip v-if="scope.row.status === 'DRAFT'" :content="t('orders.confirmOrder')"><ElButton circle size="small" type="primary" :icon="Check" @click="transition(scope.row, 'confirm')" /></ElTooltip><ElTooltip v-if="scope.row.status === 'CONFIRMED'" :content="t('orders.cancelOrder')"><ElButton circle size="small" type="danger" plain :icon="Close" @click="transition(scope.row, 'cancel')" /></ElTooltip><ElButton v-if="['CONFIRMED', 'COMPLETED', 'PARTIALLY_REFUNDED'].includes(scope.row.status)" text size="small" @click="transition(scope.row, 'refund')">{{ t('orders.refundOrder') }}</ElButton></template></ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('orders.emptyTitle')" :body="t('orders.emptyBody')"><ElButton type="primary" :icon="Plus" @click="openCreate">{{ t('orders.newOrder') }}</ElButton></EmptyState>
      <ElPagination v-if="page.totalPages > 1" v-model:current-page="currentPage" class="table-pagination" background layout="prev, pager, next" :page-size="pageSize" :total="page.totalElements" @current-change="load" />
    </section>

    <ElDialog v-model="dialogOpen" :title="t('orders.createOrder')" width="min(780px, 96vw)" destroy-on-close>
      <ElForm label-position="top" class="form-grid">
        <ElFormItem :label="t('orders.customerName')"><ElInput v-model="form.customerName" /></ElFormItem>
        <ElFormItem :label="t('orders.customerEmail')"><ElInput v-model="form.customerEmail" type="email" /></ElFormItem>
        <ElFormItem :label="t('orders.orderedAt')"><ElDatePicker v-model="form.orderDate" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.SSSZ" class="full-width" /></ElFormItem>
        <ElFormItem :label="t('orders.channel')"><ElSelect v-model="form.salesChannel" class="full-width" @change="changeChannel"><ElOption :label="t('orders.channels.exhibition')" value="EXHIBITION" /><ElOption :label="t('orders.channels.online')" value="ONLINE" /><ElOption :label="t('orders.channels.other')" value="OTHER" /></ElSelect></ElFormItem>
        <ElFormItem :label="t('orders.totalAmount')" required><ElInputNumber v-model="form.totalAmount" :min="0.01" :precision="2" controls-position="right" class="full-width" /></ElFormItem>
        <ElFormItem v-if="form.salesChannel === 'EXHIBITION'" :label="t('orders.event')">
          <ElSelect v-model="form.eventId" class="full-width" clearable filterable :placeholder="t('orders.selectEvent')" @change="selectEvent">
            <ElOption v-for="event in activeEvents" :key="event.id" :label="eventLabel(event)" :value="event.id" />
          </ElSelect>
          <ElButton text :icon="Plus" @click="openEventEditor(undefined, true)">{{ t('orders.addEvent') }}</ElButton>
        </ElFormItem>
        <div class="span-2 order-editor">
          <div class="order-editor__heading"><span><strong>{{ t('orders.items') }}</strong><small>{{ t('orders.itemsOptional') }}</small></span><ElButton text :icon="Plus" @click="addItem">{{ t('orders.addItem') }}</ElButton></div>
          <p v-if="!form.items.length" class="order-editor__empty">{{ t('orders.itemsHint') }}</p>
          <div v-for="(item, index) in form.items" :key="index" class="order-item-row">
            <ElSelect v-model="item.productId" filterable remote reserve-keyword :remote-method="searchProducts" :placeholder="t('orders.product')" @change="selectProduct(item)"><ElOption v-for="product in products" :key="product.id" :label="`${product.name} · ${product.sku}`" :value="product.id" /></ElSelect>
            <ElInputNumber v-model="item.quantity" :min="1" :precision="0" controls-position="right" />
            <ElButton text type="danger" :icon="Close" :aria-label="t('common.delete')" @click="removeItem(index)" />
          </div>
          <div class="order-total"><span>{{ t('orders.total') }}</span><strong>{{ money(form.totalAmount, form.currency) }}</strong></div>
        </div>
        <ElFormItem class="span-2" :label="t('orders.notes')"><ElInput v-model="form.customerNote" type="textarea" :rows="3" /></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="createOrder">{{ saving ? t('common.saving') : t('orders.createOrder') }}</ElButton></template>
    </ElDialog>

    <ElDialog v-model="batchCreateOpen" :title="t('orders.batchRecord')" width="min(760px, 96vw)" destroy-on-close>
      <ElAlert :title="t('orders.batchTimeHint')" type="info" show-icon :closable="false" />
      <ElForm label-position="top" class="form-grid batch-order-context">
        <ElFormItem :label="t('orders.event')" required><ElSelect v-model="batchForm.eventId" filterable class="full-width" :placeholder="t('orders.selectEvent')"><ElOption v-for="event in activeEvents" :key="event.id" :label="eventLabel(event)" :value="event.id" /></ElSelect></ElFormItem>
        <ElFormItem :label="t('orders.orderedAt')" required><ElDatePicker v-model="batchForm.orderDate" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.SSSZ" class="full-width" /></ElFormItem>
        <ElFormItem :label="t('products.currency')" required><ElInput v-model="batchForm.currency" maxlength="3" /></ElFormItem>
        <ElFormItem :label="t('reports.paymentMethods')" required><ElSelect v-model="batchForm.paymentMethod" class="full-width"><ElOption :label="t('reports.labels.CASH')" value="CASH" /><ElOption :label="t('reports.labels.CARD')" value="CARD" /><ElOption label="SumUp" value="SUMUP" /><ElOption :label="t('reports.labels.OTHER')" value="OTHER" /></ElSelect></ElFormItem>
      </ElForm>
      <div class="batch-amount-editor">
        <div class="order-editor__heading"><span><strong>{{ t('orders.batchAmounts') }}</strong><small>{{ t('common.items', { count: batchForm.amounts.length }) }}</small></span><ElButton text :icon="Plus" :disabled="batchForm.amounts.length >= 100" @click="addBatchRow">{{ t('orders.addAmount') }}</ElButton></div>
        <div v-for="(amount, index) in batchForm.amounts" :key="index" class="batch-amount-row"><span>{{ index + 1 }}</span><ElInputNumber v-model="batchForm.amounts[index]" :min="0.01" :precision="2" controls-position="right" /><ElButton text type="danger" :icon="Close" :disabled="batchForm.amounts.length === 1" :aria-label="t('common.delete')" @click="removeBatchRow(index)" /></div>
        <div class="order-total"><span>{{ t('orders.batchTotal') }}</span><strong>{{ money(batchTotal, batchForm.currency) }}</strong></div>
      </div>
      <template #footer><ElButton @click="batchCreateOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="createBatchOrders">{{ saving ? t('common.saving') : t('orders.recordBatch') }}</ElButton></template>
    </ElDialog>

    <ElDialog v-model="eventsDialogOpen" :title="t('orders.manageEvents')" width="min(680px, 94vw)">
      <div class="table-toolbar">
        <ElButton type="primary" :icon="Plus" @click="openEventEditor()">{{ t('orders.addEvent') }}</ElButton>
      </div>
      <ElTable :data="events" row-key="id">
        <ElTableColumn prop="name" :label="t('orders.event')" min-width="260" />
        <ElTableColumn :label="t('orders.eventDates')" min-width="210"><template #default="scope">{{ scope.row.startDate }} — {{ scope.row.endDate }}</template></ElTableColumn>
        <ElTableColumn :label="t('common.created')" min-width="170"><template #default="scope">{{ dateTime(scope.row.createdAt) }}</template></ElTableColumn>
        <ElTableColumn :label="t('common.status')" width="110"><template #default="scope"><ElSwitch :model-value="scope.row.enabled" @change="toggleEvent(scope.row, Boolean($event))" /></template></ElTableColumn>
        <ElTableColumn :label="t('common.actions')" width="100"><template #default="scope"><ElButton text @click="openEventEditor(scope.row)">{{ t('common.edit') }}</ElButton></template></ElTableColumn>
      </ElTable>
    </ElDialog>

    <ElDialog v-model="eventEditorOpen" :title="t(eventEditor.id ? 'orders.editEvent' : 'orders.addEvent')" width="min(480px, 94vw)" append-to-body>
      <ElForm label-position="top">
        <ElFormItem :label="t('orders.event')" required><ElInput v-model="eventEditor.name" maxlength="240" show-word-limit /></ElFormItem>
        <ElFormItem :label="t('orders.eventStartDate')" required><ElDatePicker v-model="eventEditor.startDate" type="date" value-format="YYYY-MM-DD" class="full-width" /></ElFormItem>
        <ElFormItem :label="t('orders.eventEndDate')" required><ElDatePicker v-model="eventEditor.endDate" type="date" value-format="YYYY-MM-DD" class="full-width" /></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="eventEditorOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="eventSaving" @click="saveEvent">{{ eventSaving ? t('common.saving') : t('common.save') }}</ElButton></template>
    </ElDialog>

    <ElDialog v-model="batchResultOpen" :title="t('orders.batchFailures')" width="min(620px, 94vw)">
      <ElTable :data="batchFailures">
        <ElTableColumn :label="t('orders.orderNumber')" min-width="180"><template #default="scope">{{ scope.row.orderNumber || scope.row.id }}</template></ElTableColumn>
        <ElTableColumn prop="message" :label="t('errors.title')" min-width="280" />
      </ElTable>
    </ElDialog>
  </div>
</template>
