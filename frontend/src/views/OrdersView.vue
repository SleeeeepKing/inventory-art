<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { nearestEnabledEventId } from '@/services/events'
import { advanceAmountEntry, submittedAmounts } from '@/services/rapidEntry'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { Order, OrderBatchCreateResponse, PageResponse, SalesEvent } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

interface FocusableInput {
  focus?: () => void
}

const { t } = useI18n()
const router = useRouter()
const { showError } = useApiFeedback()
const { money, dateTime, defaultCurrency } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const deletingId = ref('')
const entryOpen = ref(false)
const editOpen = ref(false)
const editingOrderId = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const eventFilter = ref('')
const page = ref<PageResponse<Order>>(normalizePage([]))
const events = ref<SalesEvent[]>([])
const amountInputs = ref<Array<FocusableInput | null>>([])
const entry = reactive({
  eventId: '',
  orderDate: '',
  amounts: [null] as Array<number | null>,
})
const edit = reactive({ eventId: '', orderDate: '', totalAmount: 0 })

const activeEvents = computed(() => events.value.filter((event) => event.enabled))
const editEvents = computed(() => {
  const selected = events.value.find((event) => event.id === edit.eventId)
  return selected && !selected.enabled ? [selected, ...activeEvents.value] : activeEvents.value
})
const validEntryAmounts = computed(() => {
  return submittedAmounts(entry.amounts)
})
const entryTotal = computed(() =>
  validEntryAmounts.value.reduce<number>((sum, amount) => sum + Number(amount || 0), 0),
)

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

function setAmountInput(element: unknown, index: number) {
  amountInputs.value[index] = element as FocusableInput | null
}

async function focusAmount(index: number) {
  await nextTick()
  amountInputs.value[index]?.focus?.()
}

function openEntry() {
  Object.assign(entry, {
    eventId: nearestEnabledEventId(events.value),
    orderDate: '',
    amounts: [null],
  })
  entryOpen.value = true
  void focusAmount(0)
}

function addAmount() {
  if (entry.amounts.length >= 100) return
  entry.amounts.push(null)
  void focusAmount(entry.amounts.length - 1)
}

function removeAmount(index: number) {
  if (entry.amounts.length === 1) {
    entry.amounts[0] = null
    return
  }
  entry.amounts.splice(index, 1)
  amountInputs.value.splice(index, 1)
}

function advanceAmount(index: number) {
  const next = advanceAmountEntry(entry.amounts, index)
  entry.amounts = next.values
  void focusAmount(next.focusIndex)
}

async function recordBatch() {
  const amounts = validEntryAmounts.value
  if (
    !entry.eventId ||
    !entry.orderDate ||
    !amounts.length ||
    amounts.some((amount) => amount == null || amount <= 0)
  ) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    const { data } = await api.post<OrderBatchCreateResponse>('/orders/batch', {
      eventId: entry.eventId,
      orderDate: entry.orderDate,
      orders: amounts.map((totalAmount) => ({ totalAmount })),
    })
    ElMessage.success(t('orders.batchRecorded', { count: data.orderCount }))
    entry.amounts = [null]
    amountInputs.value = []
    await load()
    await focusAmount(0)
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

function openEdit(order: Order) {
  editingOrderId.value = order.id
  Object.assign(edit, {
    eventId: order.eventId,
    orderDate: order.orderDate,
    totalAmount: Number(order.totalAmount),
  })
  editOpen.value = true
}

async function saveEdit() {
  if (!edit.eventId || !edit.orderDate || edit.totalAmount <= 0) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    await api.put(`/orders/${editingOrderId.value}`, edit)
    ElMessage.success(t('orders.updated'))
    editOpen.value = false
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
        <ElButton type="primary" :icon="Plus" @click="openEntry">{{
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
        <ElTableColumn prop="eventName" :label="t('orders.event')" min-width="240" />
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
        <ElButton type="primary" :icon="Plus" @click="openEntry">{{
          t('orders.batchRecord')
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

    <ElDialog
      v-model="entryOpen"
      :title="t('orders.batchRecord')"
      width="min(760px, 96vw)"
      destroy-on-close
    >
      <ElAlert :title="t('orders.batchTimeHint')" type="info" show-icon :closable="false" />
      <ElForm label-position="top" class="form-grid batch-order-context">
        <ElFormItem :label="t('orders.event')" required>
          <ElSelect
            v-model="entry.eventId"
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
            v-model="entry.orderDate"
            type="datetime"
            format="YYYY-MM-DD HH:00"
            value-format="YYYY-MM-DDTHH:mm:ss.SSSZ"
            class="full-width"
          />
        </ElFormItem>
      </ElForm>
      <div class="batch-amount-editor">
        <div class="order-editor__heading">
          <span>
            <strong>{{ t('orders.batchAmounts') }}</strong>
            <small>{{ t('common.items', { count: validEntryAmounts.length }) }}</small>
          </span>
          <ElButton text :icon="Plus" :disabled="entry.amounts.length >= 100" @click="addAmount">{{
            t('orders.addAmount')
          }}</ElButton>
        </div>
        <div v-for="(amount, index) in entry.amounts" :key="index" class="batch-amount-row">
          <span>{{ index + 1 }}</span>
          <ElInputNumber
            :ref="(element: unknown) => setAmountInput(element, index)"
            v-model="entry.amounts[index]"
            :min="0.01"
            :precision="2"
            controls-position="right"
            @keyup.enter.prevent="advanceAmount(index)"
          />
          <ElButton
            text
            type="danger"
            :icon="Delete"
            :aria-label="t('common.delete')"
            @click="removeAmount(index)"
          />
        </div>
        <div class="order-total">
          <span>{{ t('orders.batchTotal') }}</span>
          <strong>{{ money(entryTotal, defaultCurrency) }}</strong>
        </div>
      </div>
      <template #footer>
        <ElButton @click="entryOpen = false">{{ t('common.close') }}</ElButton>
        <ElButton type="primary" :loading="saving" @click="recordBatch">{{
          saving ? t('common.saving') : t('orders.recordBatch')
        }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="editOpen" :title="t('orders.editOrder')" width="min(620px, 96vw)">
      <ElForm label-position="top" class="form-grid">
        <ElFormItem class="span-2" :label="t('orders.event')" required>
          <ElSelect v-model="edit.eventId" class="full-width" filterable>
            <ElOption
              v-for="event in editEvents"
              :key="event.id"
              :label="eventLabel(event)"
              :value="event.id"
              :disabled="!event.enabled"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem :label="t('orders.orderedAt')" required>
          <ElDatePicker
            v-model="edit.orderDate"
            type="datetime"
            format="YYYY-MM-DD HH:00"
            value-format="YYYY-MM-DDTHH:mm:ss.SSSZ"
            class="full-width"
          />
        </ElFormItem>
        <ElFormItem :label="t('orders.totalAmount')" required>
          <ElInputNumber
            v-model="edit.totalAmount"
            :min="0.01"
            :precision="2"
            controls-position="right"
            class="full-width"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editOpen = false">{{ t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveEdit">{{
          saving ? t('common.saving') : t('common.save')
        }}</ElButton>
      </template>
    </ElDialog>
  </div>
</template>
