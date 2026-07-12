<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { AdminTenant, PageResponse, SalesEvent, UserProfile } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

type Row = Record<string, unknown>
type DataType = 'products' | 'orders' | 'inventory'
const { t } = useI18n()
const { showError } = useApiFeedback()
const { money, number, dateTime } = useFormatters()
const start = new Date()
start.setDate(start.getDate() - 29)
start.setHours(0, 0, 0, 0)
const rows = ref<PageResponse<Row>>(normalizePage([]))
const tenants = ref<AdminTenant[]>([])
const users = ref<UserProfile[]>([])
const events = ref<SalesEvent[]>([])
const loading = ref(false)
const currentPage = ref(1)
const tenantId = ref('')
const userId = ref('')
const eventId = ref('')
const range = ref<[Date, Date]>([start, new Date()])
const dataType = ref<DataType>('orders')
let userSearchTimer: ReturnType<typeof setTimeout> | undefined
const tabs = [
  { value: 'orders', key: 'admin.orders' },
  { value: 'inventory', key: 'admin.inventory' },
  { value: 'products', key: 'admin.products' },
] as const
const supportsOperationalFilters = computed(() => ['orders', 'inventory'].includes(dataType.value))
function text(row: Row, key: string) {
  const value = row[key]
  return value == null || value === '' ? '—' : String(value)
}
function numeric(row: Row, key: string) {
  return Number(row[key] || 0)
}
function endpoint() {
  return dataType.value === 'inventory' ? '/admin/inventory/movements' : `/admin/${dataType.value}`
}
function queryBounds() {
  const from = new Date(range.value[0])
  from.setHours(0, 0, 0, 0)
  const to = new Date(range.value[1])
  to.setDate(to.getDate() + 1)
  to.setHours(0, 0, 0, 0)
  return { from: from.toISOString(), to: to.toISOString() }
}
async function load() {
  loading.value = true
  try {
    const params = {
      page: currentPage.value - 1,
      size: 20,
      tenantId: tenantId.value || undefined,
      userId: supportsOperationalFilters.value && userId.value ? userId.value : undefined,
      eventId: supportsOperationalFilters.value && eventId.value ? eventId.value : undefined,
      ...(supportsOperationalFilters.value ? queryBounds() : {}),
    }
    const { data } = await api.get<PageResponse<Row>>(endpoint(), { params })
    rows.value = normalizePage(data)
  } catch (error) {
    rows.value = normalizePage([])
    showError(error)
  } finally {
    loading.value = false
  }
}

function reloadFirstPage() {
  currentPage.value = 1
  void load()
}

async function loadTenants() {
  const { data } = await api.get<PageResponse<AdminTenant>>('/admin/tenants', {
    params: { page: 0, size: 100 },
  })
  tenants.value = normalizePage(data).items
}
async function changeTenant() {
  userId.value = ''
  eventId.value = ''
  users.value = []
  events.value = []
  if (tenantId.value) {
    await searchUsersNow('')
    const eventResponse = await api.get<SalesEvent[]>('/admin/sales-events', {
      params: { tenantId: tenantId.value },
    })
    events.value = eventResponse.data
  }
  currentPage.value = 1
  await load()
}
async function searchUsersNow(query: string) {
  if (!tenantId.value) {
    users.value = []
    return
  }
  const { data } = await api.get<PageResponse<UserProfile>>('/admin/users', {
    params: { tenantId: tenantId.value, q: query || undefined, page: 0, size: 20 },
  })
  users.value = normalizePage(data).items
}
function searchUsers(query: string) {
  if (userSearchTimer) clearTimeout(userSearchTimer)
  userSearchTimer = setTimeout(
    () => {
      void searchUsersNow(query)
    },
    query ? 250 : 0,
  )
}
function changeType() {
  currentPage.value = 1
  void load()
}
function selectType(value: DataType) {
  dataType.value = value
  changeType()
}
onMounted(async () => {
  try {
    await loadTenants()
    await load()
  } catch (error) {
    showError(error)
  }
})
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('nav.management')"
      :title="t('admin.globalTitle')"
      :subtitle="t('admin.globalSubtitle')"
    />
    <ElAlert :title="t('admin.crossTenantNotice')" type="warning" show-icon :closable="false" />
    <section class="panel data-panel">
      <div class="data-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.value"
          type="button"
          :class="{ active: dataType === tab.value }"
          @click="selectType(tab.value)"
        >
          {{ t(tab.key) }}
        </button>
      </div>
      <div class="table-toolbar">
        <ElSelect
          v-model="tenantId"
          clearable
          :placeholder="t('common.tenant')"
          @change="changeTenant"
        >
          <ElOption
            v-for="tenant in tenants"
            :key="tenant.id"
            :label="tenant.name"
            :value="tenant.id"
          />
        </ElSelect>
        <ElSelect
          v-if="supportsOperationalFilters"
          v-model="userId"
          clearable
          filterable
          remote
          :remote-method="searchUsers"
          :disabled="!tenantId"
          :placeholder="t('admin.userFilter')"
          @change="reloadFirstPage"
          ><ElOption
            v-for="user in users"
            :key="user.id"
            :label="`${user.displayName} · ${user.username || user.email}`"
            :value="user.id"
        /></ElSelect>
        <ElDatePicker
          v-if="supportsOperationalFilters"
          v-model="range"
          type="daterange"
          :range-separator="'—'"
          :start-placeholder="t('admin.fromDate')"
          :end-placeholder="t('admin.toDate')"
          @change="reloadFirstPage"
        />
        <ElSelect
          v-if="supportsOperationalFilters"
          v-model="eventId"
          clearable
          filterable
          :disabled="!tenantId"
          :placeholder="t('orders.event')"
          @change="reloadFirstPage"
          ><ElOption
            v-for="event in events"
            :key="event.id"
            :label="`${event.name} · ${event.startDate} — ${event.endDate}`"
            :value="event.id"
        /></ElSelect>
        <ElButton :icon="RefreshRight" @click="load">
          {{ t('common.refresh') }}
        </ElButton>
        <span class="table-toolbar__count">{{
          t('common.items', { count: rows.totalElements })
        }}</span>
      </div>
      <ElTable v-if="rows.items.length || loading" v-loading="loading" :data="rows.items">
        <template v-if="dataType === 'products'">
          <ElTableColumn prop="tenantName" :label="t('common.tenant')" min-width="150" />
          <ElTableColumn prop="sku" :label="t('products.sku')" min-width="130" />
          <ElTableColumn prop="name" :label="t('products.productName')" min-width="240" />
          <ElTableColumn :label="t('products.stock')" align="right">
            <template #default="scope">
              {{ numeric(scope.row, 'currentStock') }}
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('products.price')" align="right">
            <template #default="scope">
              {{ money(numeric(scope.row, 'salePrice'), text(scope.row, 'currency')) }}
            </template>
          </ElTableColumn>
        </template>
        <template v-else-if="dataType === 'orders'">
          <ElTableColumn prop="tenantName" :label="t('common.tenant')" min-width="150" />
          <ElTableColumn prop="orderNumber" :label="t('orders.orderNumber')" min-width="160" />
          <ElTableColumn prop="createdByName" :label="t('admin.operator')" min-width="150" />
          <ElTableColumn prop="eventName" :label="t('orders.event')" min-width="200" />
          <ElTableColumn :label="t('orders.total')" align="right">
            <template #default="scope">
              {{ money(numeric(scope.row, 'totalAmount'), text(scope.row, 'currency')) }}
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('common.date')">
            <template #default="scope">
              {{ dateTime(text(scope.row, 'orderDate')) }}
            </template>
          </ElTableColumn>
        </template>
        <template v-else-if="dataType === 'inventory'">
          <ElTableColumn prop="tenantName" :label="t('common.tenant')" min-width="150" />
          <ElTableColumn :label="t('inventory.product')" min-width="220"
            ><template #default="scope"
              ><div class="cell-stack">
                <strong>{{ text(scope.row, 'productName') }}</strong
                ><code class="sku-code">{{ text(scope.row, 'productSku') }}</code>
              </div></template
            ></ElTableColumn
          >
          <ElTableColumn prop="operatorName" :label="t('admin.operator')" min-width="150" />
          <ElTableColumn prop="type" :label="t('inventory.movement')" min-width="140" />
          <ElTableColumn :label="t('inventory.quantity')" align="right" width="100"
            ><template #default="scope">{{
              number(numeric(scope.row, 'quantity'))
            }}</template></ElTableColumn
          >
          <ElTableColumn prop="eventName" :label="t('orders.event')" min-width="200" />
          <ElTableColumn :label="t('common.date')" min-width="170"
            ><template #default="scope">{{
              dateTime(text(scope.row, 'createdAt'))
            }}</template></ElTableColumn
          >
        </template>
      </ElTable>
      <EmptyState v-else :title="t('common.noData')" :body="t('admin.globalSubtitle')" />
      <ElPagination
        v-if="rows.totalPages > 1"
        v-model:current-page="currentPage"
        class="table-pagination"
        background
        layout="prev, pager, next"
        :page-size="20"
        :total="rows.totalElements"
        @current-change="load"
      />
    </section>
  </div>
</template>
