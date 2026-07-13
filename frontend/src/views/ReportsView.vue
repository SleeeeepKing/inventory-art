<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { EChartsCoreOption } from 'echarts/core'
import { RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import {
  reportDateParam,
  reportHourlyAllowed,
  reportPresetRange,
  reportRangeMatches,
  type ReportDateRange,
} from '@/services/reportFilters'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import ChartCanvas from '@/components/ChartCanvas.vue'
import EmptyState from '@/components/EmptyState.vue'

interface CurrencyMetric {
  currency: string
  totalSales: number
  totalExpenses: number
  balance: number
  transactionCount: number
  averageTransactionValue: number
}

interface TrendPoint {
  bucket: string
  currency: string
  totalSales: number
  transactions: number
}

interface Breakdown {
  eventId: string
  label: string
  currency: string
  totalSales: number
  totalExpenses: number
  balance: number
  transactions: number
  expenseCount: number
}

interface ExpenseBreakdown {
  categoryId: string
  label: string
  currency: string
  totalExpenses: number
  expenseCount: number
}

interface Dashboard {
  timezone: string
  defaultCurrency: string
  granularity: 'DAY' | 'HOUR'
  currencies: CurrencyMetric[]
  salesTrend: TrendPoint[]
  byEvent: Breakdown[]
  expensesByCategory: ExpenseBreakdown[]
}

interface InventoryGroup {
  productId?: string
  sku?: string
  label: string
  units: number
  batches: number
}

interface InventoryReport {
  timezone: string
  summary: { units: number; batches: number }
  byProduct: InventoryGroup[]
  byEvent: InventoryGroup[]
}

const { t, te } = useI18n()
const { showError } = useApiFeedback()
const { money, number } = useFormatters()
const loading = ref(false)
const dashboard = ref<Dashboard | null>(null)
const inventory = ref<InventoryReport | null>(null)
const currency = ref('')
const appliedGranularity = ref<'DAY' | 'HOUR'>('DAY')
const draftGranularity = ref<'DAY' | 'HOUR'>('DAY')
const appliedRange = ref<ReportDateRange>(reportPresetRange(30))
const draftRange = ref<ReportDateRange | null>(reportPresetRange(30))
const hourlyAllowed = computed(() => reportHourlyAllowed(draftRange.value))

const current = computed(
  () =>
    dashboard.value?.currencies.find((metric) => metric.currency === currency.value) ??
    dashboard.value?.currencies[0],
)
const eventRows = computed(() =>
  (dashboard.value?.byEvent ?? []).filter((row) => row.currency === current.value?.currency),
)
const expenseRows = computed(() =>
  (dashboard.value?.expensesByCategory ?? []).filter(
    (row) => row.currency === current.value?.currency,
  ),
)
const localizedLabel = (label: string) =>
  te(`reports.labels.${label}`) ? t(`reports.labels.${label}`) : label || t('reports.unspecified')

const trendOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0 },
  grid: { left: 36, right: 20, top: 24, bottom: 56, containLabel: true },
  xAxis: {
    type: 'category',
    data: (dashboard.value?.salesTrend ?? [])
      .filter((point) => point.currency === current.value?.currency)
      .map((point) => point.bucket),
  },
  yAxis: [{ type: 'value' }, { type: 'value' }],
  series: [
    {
      name: t('reports.revenue'),
      type: 'line',
      smooth: true,
      areaStyle: { opacity: 0.12 },
      data: (dashboard.value?.salesTrend ?? [])
        .filter((point) => point.currency === current.value?.currency)
        .map((point) => Number(point.totalSales)),
    },
    {
      name: t('reports.orders'),
      type: 'bar',
      yAxisIndex: 1,
      data: (dashboard.value?.salesTrend ?? [])
        .filter((point) => point.currency === current.value?.currency)
        .map((point) => Number(point.transactions)),
    },
  ],
}))

async function load() {
  loading.value = true
  try {
    const params = {
      start: reportDateParam(appliedRange.value[0]),
      end: reportDateParam(appliedRange.value[1]),
      granularity: appliedGranularity.value,
    }
    const [financialResponse, inventoryResponse] = await Promise.all([
      api.get<Dashboard>('/reports/dashboard', { params }),
      api.get<InventoryReport>('/reports/inventory-sales', { params }),
    ])
    dashboard.value = financialResponse.data
    inventory.value = inventoryResponse.data
    if (!financialResponse.data.currencies.some((metric) => metric.currency === currency.value)) {
      currency.value =
        financialResponse.data.currencies.find(
          (metric) => metric.currency === financialResponse.data.defaultCurrency,
        )?.currency ??
        financialResponse.data.currencies[0]?.currency ??
        ''
    }
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

function setPreset(days: number) {
  draftRange.value = reportPresetRange(days)
  normalizeDraftGranularity()
}

function normalizeDraftGranularity() {
  if (!hourlyAllowed.value && draftGranularity.value === 'HOUR') {
    draftGranularity.value = 'DAY'
  }
}

async function applyFilters() {
  if (!draftRange.value) return
  appliedRange.value = draftRange.value.map((date) => new Date(date)) as ReportDateRange
  appliedGranularity.value = draftGranularity.value
  await load()
}

async function resetFilters() {
  draftRange.value = reportPresetRange(30)
  draftGranularity.value = 'DAY'
  currency.value = ''
  await applyFilters()
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('reports.eyebrow')"
      :title="t('reports.title')"
      :subtitle="t('reports.subtitle')"
    >
      <template #actions>
        <ElButton :icon="RefreshRight" :loading="loading" @click="load">{{
          t('common.refresh')
        }}</ElButton>
      </template>
    </PageHeader>

    <section class="panel report-filters" :aria-label="t('reports.filters')">
      <div class="report-period">
        <div class="report-filter__label">
          <span>{{ t('reports.period') }}</span>
          <small>{{ t('reports.periodHint') }}</small>
        </div>
        <div class="report-period__rail" aria-hidden="true"><i /><span /><i /></div>
        <div class="report-presets" :aria-label="t('reports.quickRanges')">
          <button
            v-for="days in [7, 30, 90]"
            :key="days"
            type="button"
            :class="{ active: reportRangeMatches(draftRange, days) }"
            :aria-pressed="reportRangeMatches(draftRange, days)"
            @click="setPreset(days)"
          >
            {{ t('reports.lastDays', { count: days }) }}
          </button>
        </div>
        <ElDatePicker
          v-model="draftRange"
          type="daterange"
          range-separator="—"
          :start-placeholder="t('reports.startDate')"
          :end-placeholder="t('reports.endDate')"
          @change="normalizeDraftGranularity"
        />
      </div>

      <div class="report-filter-field">
        <label>{{ t('reports.granularity') }}</label>
        <ElRadioGroup v-model="draftGranularity">
          <ElRadioButton value="DAY">{{ t('reports.byDay') }}</ElRadioButton>
          <ElRadioButton value="HOUR" :disabled="!hourlyAllowed">{{
            t('reports.byHour')
          }}</ElRadioButton>
        </ElRadioGroup>
        <small v-if="!hourlyAllowed">{{ t('reports.hourlyLimit') }}</small>
      </div>

      <div class="report-filter-field report-currency">
        <label>{{ t('reports.currency') }}</label>
        <ElSelect v-model="currency" :disabled="!dashboard?.currencies.length">
          <ElOption
            v-for="metric in dashboard?.currencies ?? []"
            :key="metric.currency"
            :label="metric.currency"
            :value="metric.currency"
          />
        </ElSelect>
      </div>

      <div class="report-filter-actions">
        <ElButton @click="resetFilters">{{ t('common.reset') }}</ElButton>
        <ElButton type="primary" :loading="loading" :disabled="!draftRange" @click="applyFilters">{{
          t('reports.applyFilters')
        }}</ElButton>
      </div>
    </section>

    <section v-if="current" class="metric-grid metric-grid--five">
      <MetricCard
        :label="t('reports.revenue')"
        :value="money(current.totalSales, current.currency)"
      />
      <MetricCard
        :label="t('reports.expenses')"
        :value="money(current.totalExpenses, current.currency)"
      />
      <MetricCard :label="t('reports.balance')" :value="money(current.balance, current.currency)" />
      <MetricCard :label="t('reports.orders')" :value="number(current.transactionCount)" />
      <MetricCard
        :label="t('reports.averageOrderValue')"
        :value="money(current.averageTransactionValue, current.currency)"
      />
    </section>

    <section class="report-grid">
      <article class="panel chart-panel">
        <div class="panel-heading">
          <h2>
            {{ appliedGranularity === 'HOUR' ? t('reports.hourlySales') : t('reports.dailySales') }}
          </h2>
          <small v-if="dashboard">{{
            t('reports.timezoneHint', { timezone: dashboard.timezone })
          }}</small>
        </div>
        <ChartCanvas v-if="current" :option="trendOption" />
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>

      <article class="panel data-panel">
        <div class="panel-heading">
          <h2>{{ t('reports.events') }}</h2>
        </div>
        <ElTable v-if="eventRows.length" :data="eventRows">
          <ElTableColumn prop="label" :label="t('orders.event')" min-width="190" />
          <ElTableColumn :label="t('reports.orders')" align="right">
            <template #default="scope">{{ number(scope.row.transactions) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.expenseEntries')" align="right">
            <template #default="scope">{{ number(scope.row.expenseCount) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.revenue')" align="right">
            <template #default="scope">{{
              money(scope.row.totalSales, scope.row.currency)
            }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.expenses')" align="right">
            <template #default="scope">{{
              money(scope.row.totalExpenses, scope.row.currency)
            }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.balance')" align="right">
            <template #default="scope">{{ money(scope.row.balance, scope.row.currency) }}</template>
          </ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>
    </section>

    <section class="panel data-panel">
      <div class="panel-heading">
        <h2>{{ t('reports.expensesByCategory') }}</h2>
      </div>
      <ElTable v-if="expenseRows.length" :data="expenseRows">
        <ElTableColumn prop="label" :label="t('expenses.category')" min-width="220" />
        <ElTableColumn :label="t('reports.expenseEntries')" align="right">
          <template #default="scope">{{ number(scope.row.expenseCount) }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('reports.expenses')" align="right">
          <template #default="scope">{{
            money(scope.row.totalExpenses, scope.row.currency)
          }}</template>
        </ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('reports.noExpenses')" :body="t('reports.noExpensesBody')" />
    </section>

    <section class="metric-grid">
      <MetricCard :label="t('reports.units')" :value="number(inventory?.summary.units || 0)" />
      <MetricCard
        :label="t('reports.saleBatches')"
        :value="number(inventory?.summary.batches || 0)"
      />
    </section>

    <section class="report-grid">
      <article class="panel data-panel">
        <div class="panel-heading">
          <h2>{{ t('reports.topProducts') }}</h2>
        </div>
        <ElTable v-if="inventory?.byProduct.length" :data="inventory.byProduct">
          <ElTableColumn :label="t('reports.product')" min-width="200">
            <template #default="scope">
              <div class="cell-stack">
                <strong>{{ scope.row.label }}</strong
                ><code>{{ scope.row.sku }}</code>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.units')" align="right">
            <template #default="scope">{{ number(scope.row.units) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.saleBatches')" align="right">
            <template #default="scope">{{ number(scope.row.batches) }}</template>
          </ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>

      <article class="panel data-panel">
        <div class="panel-heading">
          <h2>{{ t('reports.events') }}</h2>
        </div>
        <ElTable v-if="inventory?.byEvent.length" :data="inventory.byEvent">
          <ElTableColumn :label="t('orders.event')" min-width="200">
            <template #default="scope">{{ localizedLabel(scope.row.label) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.units')" align="right">
            <template #default="scope">{{ number(scope.row.units) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.saleBatches')" align="right">
            <template #default="scope">{{ number(scope.row.batches) }}</template>
          </ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>
    </section>
  </div>
</template>
