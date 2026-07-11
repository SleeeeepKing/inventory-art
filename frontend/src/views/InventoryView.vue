<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Download, Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { InventoryMovement, PageResponse, Product } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t, te } = useI18n()
const { showError } = useApiFeedback()
const { number, dateTime } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const page = ref<PageResponse<InventoryMovement>>(normalizePage([]))
const products = ref<Product[]>([])
const filterProductId = ref('')
const form = reactive({ productId: '', type: 'ADD', quantity: 1, reason: '' })
const selectedProduct = computed(() => products.value.find((product) => product.id === form.productId))

async function load() {
  loading.value = true
  try {
    const [movements, productResponse] = await Promise.all([
      api.get<PageResponse<InventoryMovement> | InventoryMovement[]>('/inventory/movements', { params: { page: currentPage.value - 1, size: pageSize.value, productId: filterProductId.value || undefined } }),
      api.get<PageResponse<Product> | Product[]>('/products', { params: { page: 0, size: 100, enabled: true } }),
    ])
    page.value = normalizePage(movements.data, currentPage.value - 1, pageSize.value)
    products.value = normalizePage(productResponse.data, 0, 500).items
  } catch (error) { showError(error) } finally { loading.value = false }
}

function movementLabel(type: string) {
  return te(`inventory.movementTypes.${type}`) ? t(`inventory.movementTypes.${type}`) : type.replaceAll('_', ' ')
}

function signedQuantity(movement: InventoryMovement) {
  const quantity = Number(movement.quantity)
  return `${quantity > 0 ? '+' : ''}${number(quantity)}`
}

function openAdjustment() {
  Object.assign(form, { productId: '', type: 'ADD', quantity: 1, reason: '' })
  dialogOpen.value = true
}

async function adjust() {
  if (!form.productId || form.quantity <= 0 || !form.reason.trim()) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    const type = form.type === 'ADD' ? 'ADJUSTMENT_IN' : 'ADJUSTMENT_OUT'
    await api.post('/inventory/adjustments', { items: [{ productId: form.productId, type, quantity: form.quantity, reference: 'Manual', remark: form.reason.trim() }] })
    ElMessage.success(t('inventory.adjusted'))
    dialogOpen.value = false
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
      <template #actions><ElButton :icon="Download" @click="exportCsv">{{ t('common.export') }}</ElButton><ElButton type="primary" :icon="Plus" @click="openAdjustment">{{ t('inventory.adjust') }}</ElButton></template>
    </PageHeader>
    <section class="panel data-panel">
      <div class="table-toolbar">
        <ElSelect v-model="filterProductId" clearable filterable :placeholder="t('inventory.selectProduct')" @change="currentPage = 1; load()">
          <ElOption v-for="product in products" :key="product.id" :label="`${product.name} · ${product.sku}`" :value="product.id" />
        </ElSelect>
        <ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton>
        <span class="table-toolbar__count">{{ t('common.items', { count: page.totalElements }) }}</span>
      </div>
      <ElTable v-if="page.items.length || loading" v-loading="loading" :data="page.items" row-key="id">
        <ElTableColumn :label="t('inventory.product')" min-width="220"><template #default="scope"><div class="cell-stack"><strong>{{ products.find((product) => product.id === scope.row.productId)?.name || scope.row.productId }}</strong><code class="sku-code">{{ products.find((product) => product.id === scope.row.productId)?.sku }}</code></div></template></ElTableColumn>
        <ElTableColumn :label="t('inventory.movement')" min-width="160"><template #default="scope">{{ movementLabel(scope.row.type) }}</template></ElTableColumn>
        <ElTableColumn :label="t('inventory.quantity')" align="right" width="110"><template #default="scope"><strong class="movement-quantity" :data-positive="scope.row.quantity > 0">{{ signedQuantity(scope.row) }}</strong></template></ElTableColumn>
        <ElTableColumn :label="t('inventory.stockBefore')" align="right" width="100"><template #default="scope">{{ number(scope.row.stockBefore) }}</template></ElTableColumn>
        <ElTableColumn :label="t('inventory.stockAfter')" align="right" width="100"><template #default="scope">{{ number(scope.row.stockAfter) }}</template></ElTableColumn>
        <ElTableColumn prop="remark" :label="t('inventory.reason')" min-width="220" show-overflow-tooltip />
        <ElTableColumn :label="t('common.date')" min-width="160"><template #default="scope">{{ dateTime(scope.row.createdAt) }}</template></ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('inventory.emptyTitle')" :body="t('inventory.emptyBody')"><ElButton type="primary" :icon="Plus" @click="openAdjustment">{{ t('inventory.adjust') }}</ElButton></EmptyState>
      <ElPagination v-if="page.totalPages > 1" v-model:current-page="currentPage" class="table-pagination" background layout="prev, pager, next" :page-size="pageSize" :total="page.totalElements" @current-change="load" />
    </section>

    <ElDialog v-model="dialogOpen" :title="t('inventory.adjust')" width="min(540px, 94vw)">
      <ElForm label-position="top">
        <ElFormItem :label="t('inventory.product')" required><ElSelect v-model="form.productId" filterable class="full-width" :placeholder="t('inventory.selectProduct')"><ElOption v-for="product in products" :key="product.id" :label="`${product.name} · ${product.sku} (${product.currentStock})`" :value="product.id" /></ElSelect></ElFormItem>
        <div v-if="selectedProduct" class="inline-stock"><span>{{ t('products.stock') }}</span><strong>{{ number(selectedProduct.currentStock) }}</strong></div>
        <ElFormItem :label="t('inventory.adjustmentType')" required><ElSegmented v-model="form.type" :options="[{ label: t('inventory.addStock'), value: 'ADD' }, { label: t('inventory.removeStock'), value: 'REMOVE' }]" /></ElFormItem>
        <ElFormItem :label="t('inventory.quantity')" required><ElInputNumber v-model="form.quantity" :min="1" :precision="0" controls-position="right" /></ElFormItem>
        <ElFormItem :label="t('inventory.reason')" required><ElInput v-model="form.reason" type="textarea" :rows="3" :placeholder="t('inventory.reasonPlaceholder')" /></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="adjust">{{ saving ? t('common.saving') : t('common.save') }}</ElButton></template>
    </ElDialog>
  </div>
</template>
