<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Close, Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { Order, PageResponse, Product } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'

interface DraftItem { productId: string; quantity: number; unitPrice: number }

const { t } = useI18n()
const { showError } = useApiFeedback()
const { money, dateTime, defaultCurrency } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const page = ref<PageResponse<Order>>(normalizePage([]))
const products = ref<Product[]>([])
const form = reactive({ customerName: '', customerEmail: '', orderDate: new Date().toISOString(), salesChannel: 'OTHER', currency: 'EUR', customerNote: '', items: [{ productId: '', quantity: 1, unitPrice: 0 }] as DraftItem[] })
const orderTotal = computed(() => form.items.reduce((sum, item) => sum + item.quantity * item.unitPrice, 0))

async function load() {
  loading.value = true
  try {
    const [orders, productResponse] = await Promise.all([
      api.get<PageResponse<Order> | Order[]>('/orders', { params: { page: currentPage.value - 1, size: pageSize.value, status: statusFilter.value || undefined } }),
      api.get<PageResponse<Product> | Product[]>('/products', { params: { page: 0, size: 100, enabled: true } }),
    ])
    page.value = normalizePage(orders.data, currentPage.value - 1, pageSize.value)
    products.value = normalizePage(productResponse.data, 0, 500).items
  } catch (error) { showError(error) } finally { loading.value = false }
}

function openCreate() {
  Object.assign(form, { customerName: '', customerEmail: '', orderDate: new Date().toISOString(), salesChannel: 'OTHER', currency: defaultCurrency.value, customerNote: '', items: [{ productId: '', quantity: 1, unitPrice: 0 }] })
  dialogOpen.value = true
}

function addItem() { form.items.push({ productId: '', quantity: 1, unitPrice: 0 }) }
function removeItem(index: number) { if (form.items.length > 1) form.items.splice(index, 1) }
function selectProduct(item: DraftItem) {
  const product = products.value.find((candidate) => candidate.id === item.productId)
  if (product) { item.unitPrice = Number(product.salePrice); form.currency = product.currency }
}

async function createOrder() {
  if (!form.items.length || form.items.some((item) => !item.productId || item.quantity <= 0) || orderTotal.value < 0) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    await api.post('/orders', {
      ...form,
      customerName: form.customerName || null,
      customerEmail: form.customerEmail || null,
      eventName: null,
      paymentMethod: 'OTHER',
      paymentStatus: 'PAID',
      items: form.items.map((item) => ({ ...item, discountAmount: 0, taxRate: 0 })),
    })
    ElMessage.success(t('orders.created'))
    dialogOpen.value = false
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function transition(order: Order, action: 'confirm' | 'cancel' | 'refund') {
  try {
    await ElMessageBox.confirm(t(`orders.${action}Body`), t(`orders.${action}Title`), { type: 'warning', confirmButtonText: t(`orders.${action}Order`), cancelButtonText: t('common.cancel') })
    if (action === 'refund') {
      const { data: detail } = await api.get<Order>(`/orders/${order.id}`)
      const items = detail.items.filter((item) => (item.quantity - (item.refundedQuantity || 0)) > 0).map((item) => ({ orderItemId: item.id, quantity: item.quantity - (item.refundedQuantity || 0) }))
      await api.post(`/orders/${order.id}/refunds`, { items, reason: t('orders.refundOrder') })
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
      <template #actions><ElButton type="primary" :icon="Plus" @click="openCreate">{{ t('orders.newOrder') }}</ElButton></template>
    </PageHeader>
    <section class="panel data-panel">
      <div class="table-toolbar">
        <ElSelect v-model="statusFilter" :placeholder="t('common.status')" clearable @change="currentPage = 1; load()">
          <ElOption :label="t('orders.filters.draft')" value="DRAFT" /><ElOption :label="t('orders.filters.active')" value="CONFIRMED" /><ElOption :label="t('orders.filters.completed')" value="COMPLETED" /><ElOption :label="t('orders.filters.cancelled')" value="CANCELLED" />
        </ElSelect>
        <ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton>
        <span class="table-toolbar__count">{{ t('common.items', { count: page.totalElements }) }}</span>
      </div>
      <ElTable v-if="page.items.length || loading" v-loading="loading" :data="page.items" row-key="id">
        <ElTableColumn type="expand"><template #default="scope"><div class="order-lines"><div v-for="item in scope.row.items" :key="item.id || item.productId"><span><strong>{{ item.productName || item.sku }}</strong><small>{{ item.sku }}</small></span><span>{{ item.quantity }} × {{ money(item.unitPrice, scope.row.currency) }}</span><b>{{ money(item.lineTotal ?? item.quantity * item.unitPrice, scope.row.currency) }}</b></div></div></template></ElTableColumn>
        <ElTableColumn prop="orderNumber" :label="t('orders.orderNumber')" min-width="150"><template #default="scope"><code class="order-code">{{ scope.row.orderNumber }}</code></template></ElTableColumn>
        <ElTableColumn :label="t('orders.customer')" min-width="180"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.customerName || '—' }}</strong><small>{{ scope.row.customerEmail }}</small></div></template></ElTableColumn>
        <ElTableColumn :label="t('orders.orderedAt')" min-width="165"><template #default="scope">{{ dateTime(scope.row.orderDate) }}</template></ElTableColumn>
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
        <ElFormItem :label="t('orders.channel')"><ElSelect v-model="form.salesChannel" class="full-width"><ElOption :label="t('orders.channels.exhibition')" value="EXHIBITION" /><ElOption :label="t('orders.channels.online')" value="ONLINE" /><ElOption :label="t('orders.channels.sumup')" value="SUMUP" /><ElOption :label="t('orders.channels.other')" value="OTHER" /></ElSelect></ElFormItem>
        <div class="span-2 order-editor">
          <div class="order-editor__heading"><strong>{{ t('orders.items') }}</strong><ElButton text :icon="Plus" @click="addItem">{{ t('orders.addItem') }}</ElButton></div>
          <div v-for="(item, index) in form.items" :key="index" class="order-item-row">
            <ElSelect v-model="item.productId" filterable :placeholder="t('orders.product')" @change="selectProduct(item)"><ElOption v-for="product in products" :key="product.id" :label="`${product.name} · ${product.sku} (${product.currentStock})`" :value="product.id" /></ElSelect>
            <ElInputNumber v-model="item.quantity" :min="1" :precision="0" controls-position="right" />
            <ElInputNumber v-model="item.unitPrice" :min="0" :precision="2" controls-position="right" />
            <strong>{{ money(item.quantity * item.unitPrice, form.currency) }}</strong>
            <ElButton text type="danger" :icon="Close" :disabled="form.items.length === 1" :aria-label="t('common.delete')" @click="removeItem(index)" />
          </div>
          <div class="order-total"><span>{{ t('orders.total') }}</span><strong>{{ money(orderTotal, form.currency) }}</strong></div>
        </div>
        <ElFormItem class="span-2" :label="t('orders.notes')"><ElInput v-model="form.customerNote" type="textarea" :rows="3" /></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="createOrder">{{ saving ? t('common.saving') : t('orders.createOrder') }}</ElButton></template>
    </ElDialog>
  </div>
</template>
