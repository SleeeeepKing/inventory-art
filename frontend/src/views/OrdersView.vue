<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { nearestEnabledEventId } from '@/services/events'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { Order, OrderBatchCreateResponse, PageResponse, SalesEvent } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()
const router = useRouter()
const { showError } = useApiFeedback()
const { money, dateTime, defaultCurrency } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const deletingId = ref('')
const dialogOpen = ref(false)
const batchCreateOpen = ref(false)
const editingOrderId = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const eventFilter = ref('')
const page = ref<PageResponse<Order>>(normalizePage([]))
const events = ref<SalesEvent[]>([])
const form = reactive({
  eventId: '',
  orderDate: new Date().toISOString(),
  currency: 'EUR',
  totalAmount: 0,
  paymentMethod: 'OTHER',
  paymentStatus: 'PAID',
})
const batchForm = reactive({
  eventId: '',
  currency: 'EUR',
  paymentMethod: 'OTHER',
  orderDate: new Date().toISOString(),
  amounts: [0] as number[],
})

const activeEvents = computed(() => events.value.filter((event) => event.enabled))
const formEvents = computed(() => {
  const selected = events.value.find((event) => event.id === form.eventId)
  return selected && !selected.enabled ? [selected, ...activeEvents.value] : activeEvents.value
})
const dialogTitle = computed(() =>
  editingOrderId.value ? t('orders.editOrder') : t('orders.createOrder'),
)
const batchTotal = computed(() =>
  batchForm.amounts.reduce((sum, amount) => sum + Number(amount || 0), 0),
)

function nearestEventId() {
  return nearestEnabledEventId(events.value)
}

async function load() {
  loading.value = true
  try {
    const [orders, eventResponse] = await Promise.all([
      api.get<PageResponse<Order> | Order[]>('/orders', {
        params: {
          page: currentPage.value - 1,
          size: pageSize.value,
          eventId: eventFilter.value || undefined,
        },
      }),
      api.get<SalesEvent[]>('/sales-events', { params: { includeDisabled: true } }),
    ])
    page.value = normalizePage(orders.data, currentPage.value - 1, pageSize.value)
    events.value = eventResponse.data
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

function eventLabel(event: SalesEvent) {
  return `${event.name} · ${event.startDate} — ${event.endDate}`
}

function resetForm(eventId: string) {
  Object.assign(form, {
    eventId,
    orderDate: new Date().toISOString(),
    currency: defaultCurrency.value,
    totalAmount: 0,
    paymentMethod: 'OTHER',
    paymentStatus: 'PAID',
  })
}

function openCreate() {
  editingOrderId.value = ''
  resetForm(nearestEventId())
  dialogOpen.value = true
}

function openEdit(order: Order) {
  editingOrderId.value = order.id
  Object.assign(form, {
    eventId: order.eventId ?? '',
    orderDate: order.orderDate,
    currency: order.currency,
    totalAmount: Number(order.totalAmount),
    paymentMethod: order.paymentMethod ?? 'OTHER',
    paymentStatus: order.paymentStatus ?? 'PAID',
  })
  dialogOpen.value = true
}

function openBatchCreate() {
  Object.assign(batchForm, {
    eventId: nearestEventId(),
    currency: defaultCurrency.value,
    paymentMethod: 'OTHER',
    orderDate: new Date().toISOString(),
    amounts: [0],
  })
  batchCreateOpen.value = true
}

function addBatchRow() {
  if (batchForm.amounts.length < 100) batchForm.amounts.push(0)
}

function removeBatchRow(index: number) {
  if (batchForm.amounts.length > 1) batchForm.amounts.splice(index, 1)
}

async function saveOrder() {
  if (!form.eventId || !form.orderDate || form.totalAmount <= 0) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    const event = events.value.find((candidate) => candidate.id === form.eventId)
    const payload = {
      totalAmount: form.totalAmount,
      salesChannel: 'EXHIBITION',
      eventId: form.eventId,
      eventName: event?.name ?? null,
      currency: form.currency,
      paymentMethod: form.paymentMethod,
      paymentStatus: form.paymentStatus,
      orderDate: form.orderDate,
    }
    if (editingOrderId.value) {
      await api.put(`/orders/${editingOrderId.value}`, payload)
      ElMessage.success(t('orders.updated'))
    } else {
      await api.post('/orders', payload)
      ElMessage.success(t('orders.recorded'))
    }
    dialogOpen.value = false
    await load()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

async function deleteOrder(order: Order) {
  try {
    await ElMessageBox.confirm(
      t('orders.deleteBody', { orderNumber: order.orderNumber }),
      t('orders.deleteTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
    deletingId.value = order.id
    await api.delete(`/orders/${order.id}`)
    ElMessage.success(t('orders.deleted'))
    if (page.value.items.length === 1 && currentPage.value > 1) currentPage.value -= 1
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  } finally {
    deletingId.value = ''
  }
}

async function createBatchOrders() {
  if (
    !batchForm.eventId ||
    !batchForm.orderDate ||
    batchForm.amounts.some((amount) => Number(amount) <= 0)
  ) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    const { data } = await api.post<OrderBatchCreateResponse>('/orders/batch', {
      eventId: batchForm.eventId,
      currency: batchForm.currency,
      paymentMethod: batchForm.paymentMethod,
      paymentStatus: 'PAID',
      orderDate: batchForm.orderDate,
      orders: batchForm.amounts.map((totalAmount) => ({ totalAmount })),
    })
    ElMessage.success(t('orders.batchRecorded', { count: data.orderCount }))
    batchCreateOpen.value = false
    await load()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('orders.eyebrow')"
      :title="t('orders.title')"
      :subtitle="t('orders.subtitle')"
    >
      <template #actions>
        <ElButton :icon="Plus" @click="openCreate">{{ t('orders.newOrder') }}</ElButton>
        <ElButton type="primary" :icon="Plus" @click="openBatchCreate">{{
          t('orders.batchRecord')
        }}</ElButton>
      </template>
    </PageHeader>

    <section class="panel data-panel">
      <div class="table-toolbar">
        <ElSelect
          v-model="eventFilter"
          :placeholder="t('orders.event')"
          clearable
          filterable
          @change="reloadFirstPage"
        >
          <ElOption
            v-for="event in events"
            :key="event.id"
            :label="eventLabel(event)"
            :value="event.id"
          />
        </ElSelect>
        <ElButton @click="router.push('/events')">{{ t('orders.manageEvents') }}</ElButton>
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
        <ElTableColumn prop="orderNumber" :label="t('orders.orderNumber')" min-width="170">
          <template #default="scope">
            <code class="order-code">{{ scope.row.orderNumber }}</code>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('orders.orderedAt')" min-width="180">
          <template #default="scope">{{ dateTime(scope.row.orderDate) }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('orders.event')" min-width="240">
          <template #default="scope">{{ scope.row.eventName || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('orders.total')" min-width="140" align="right">
          <template #default="scope">
            <strong>{{ money(scope.row.totalAmount, scope.row.currency) }}</strong>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('common.actions')" width="190" fixed="right">
          <template #default="scope">
            <ElButton :icon="Edit" size="small" @click="openEdit(scope.row)">{{
              t('common.edit')
            }}</ElButton>
            <ElButton
              :icon="Delete"
              size="small"
              type="danger"
              plain
              :loading="deletingId === scope.row.id"
              @click="deleteOrder(scope.row)"
              >{{ t('common.delete') }}</ElButton
            >
          </template>
        </ElTableColumn>
      </ElTable>

      <EmptyState v-else :title="t('orders.emptyTitle')" :body="t('orders.emptyBody')">
        <ElButton type="primary" :icon="Plus" @click="openCreate">{{
          t('orders.newOrder')
        }}</ElButton>
      </EmptyState>

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

    <ElDialog v-model="dialogOpen" :title="dialogTitle" width="min(620px, 96vw)" destroy-on-close>
      <ElForm label-position="top" class="form-grid">
        <ElFormItem class="span-2" :label="t('orders.event')" required>
          <ElSelect
            v-model="form.eventId"
            class="full-width"
            filterable
            :placeholder="t('orders.selectEvent')"
          >
            <ElOption
              v-for="event in formEvents"
              :key="event.id"
              :label="eventLabel(event)"
              :value="event.id"
              :disabled="!event.enabled"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('orders.orderedAt')" required>
          <ElDatePicker
            v-model="form.orderDate"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss.SSSZ"
            class="full-width"
          />
        </ElFormItem>
        <ElFormItem :label="t('orders.totalAmount')" required>
          <ElInputNumber
            v-model="form.totalAmount"
            :min="0.01"
            :precision="2"
            controls-position="right"
            class="full-width"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveOrder">{{
          saving ? t('common.saving') : editingOrderId ? t('common.save') : t('orders.createOrder')
        }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="batchCreateOpen"
      :title="t('orders.batchRecord')"
      width="min(760px, 96vw)"
      destroy-on-close
    >
      <ElAlert :title="t('orders.batchTimeHint')" type="info" show-icon :closable="false" />
      <ElForm label-position="top" class="form-grid batch-order-context">
        <ElFormItem :label="t('orders.event')" required>
          <ElSelect
            v-model="batchForm.eventId"
            filterable
            class="full-width"
            :placeholder="t('orders.selectEvent')"
          >
            <ElOption
              v-for="event in activeEvents"
              :key="event.id"
              :label="eventLabel(event)"
              :value="event.id"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('orders.orderedAt')" required>
          <ElDatePicker
            v-model="batchForm.orderDate"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss.SSSZ"
            class="full-width"
          />
        </ElFormItem>
        <ElFormItem :label="t('products.currency')" required>
          <ElInput v-model="batchForm.currency" maxlength="3" />
        </ElFormItem>
        <ElFormItem :label="t('reports.paymentMethods')" required>
          <ElSelect v-model="batchForm.paymentMethod" class="full-width">
            <ElOption :label="t('reports.labels.CASH')" value="CASH" />
            <ElOption :label="t('reports.labels.CARD')" value="CARD" />
            <ElOption label="SumUp" value="SUMUP" />
            <ElOption :label="t('reports.labels.OTHER')" value="OTHER" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <div class="batch-amount-editor">
        <div class="order-editor__heading">
          <span>
            <strong>{{ t('orders.batchAmounts') }}</strong>
            <small>{{ t('common.items', { count: batchForm.amounts.length }) }}</small>
          </span>
          <ElButton
            text
            :icon="Plus"
            :disabled="batchForm.amounts.length >= 100"
            @click="addBatchRow"
            >{{ t('orders.addAmount') }}</ElButton
          >
        </div>
        <div v-for="(amount, index) in batchForm.amounts" :key="index" class="batch-amount-row">
          <span>{{ index + 1 }}</span>
          <ElInputNumber
            v-model="batchForm.amounts[index]"
            :min="0.01"
            :precision="2"
            controls-position="right"
          />
          <ElButton
            text
            type="danger"
            :icon="Delete"
            :disabled="batchForm.amounts.length === 1"
            :aria-label="t('common.delete')"
            @click="removeBatchRow(index)"
          />
        </div>
        <div class="order-total">
          <span>{{ t('orders.batchTotal') }}</span>
          <strong>{{ money(batchTotal, batchForm.currency) }}</strong>
        </div>
      </div>
      <template #footer>
        <ElButton @click="batchCreateOpen = false">{{ t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="saving" @click="createBatchOrders">{{
          saving ? t('common.saving') : t('orders.recordBatch')
        }}</ElButton>
      </template>
    </ElDialog>
  </div>
</template>
