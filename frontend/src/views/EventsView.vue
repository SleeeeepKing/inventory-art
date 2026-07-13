<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Calendar, Coin, Delete, Edit, Plus, Search, Setting } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { useApiFeedback } from '@/composables/useApiFeedback'
import type { EventExpense, ExpenseCategory, PageResponse, SalesEvent } from '@/types/api'
import { normalizePage } from '@/services/paging'
import { useFormatters } from '@/composables/useFormatters'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t, locale } = useI18n()
const { showError } = useApiFeedback()
const { money } = useFormatters()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const expensesOpen = ref(false)
const expenseFormOpen = ref(false)
const categoriesOpen = ref(false)
const search = ref('')
const status = ref<'ALL' | 'ENABLED' | 'DISABLED'>('ALL')
const events = ref<SalesEvent[]>([])
const today = new Date().toISOString().slice(0, 10)
const selectedEvent = ref<SalesEvent>()
const expenses = ref<EventExpense[]>([])
const categories = ref<ExpenseCategory[]>([])
const emptyExpense = () => ({
  id: '',
  version: 0,
  categoryId: '',
  amount: 0,
  expenseDate: today,
  note: '',
})
const expenseForm = reactive(emptyExpense())
const categoryForm = reactive({ id: '', name: '', enabled: true, version: 0 })
const emptyForm = () => ({ id: '', name: '', startDate: today, endDate: today, enabled: true })
const form = reactive(emptyForm())

const filteredEvents = computed(() =>
  events.value
    .filter(
      (event) =>
        !search.value.trim() ||
        event.name.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase()),
    )
    .filter((event) => status.value === 'ALL' || event.enabled === (status.value === 'ENABLED'))
    .sort((left, right) => right.startDate.localeCompare(left.startDate)),
)
const enabledCount = computed(() => events.value.filter((event) => event.enabled).length)
const upcomingCount = computed(
  () => events.value.filter((event) => event.enabled && event.endDate >= today).length,
)
const expenseCurrency = computed(
  () => auth.user?.tenant?.defaultCurrency || expenses.value[0]?.currency || '—',
)

async function load() {
  loading.value = true
  try {
    const { data } = await api.get<SalesEvent[]>('/sales-events', {
      params: { includeDisabled: true },
    })
    events.value = data
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, emptyForm())
  dialogOpen.value = true
}

function openEdit(event: SalesEvent) {
  Object.assign(form, {
    id: event.id,
    name: event.name,
    startDate: event.startDate,
    endDate: event.endDate,
    enabled: event.enabled,
  })
  dialogOpen.value = true
}

async function save() {
  const name = form.name.trim()
  if (!name || !form.startDate || !form.endDate) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  if (form.endDate < form.startDate) {
    ElMessage.warning(t('validation.dateRange'))
    return
  }
  saving.value = true
  try {
    const payload = {
      name,
      startDate: form.startDate,
      endDate: form.endDate,
      enabled: form.enabled,
    }
    if (form.id) await api.put(`/sales-events/${form.id}`, payload)
    else await api.post('/sales-events', payload)
    ElMessage.success(t(form.id ? 'events.updated' : 'events.created'))
    dialogOpen.value = false
    await load()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

async function toggle(event: SalesEvent, enabled: boolean) {
  try {
    await api.post(`/sales-events/${event.id}/enabled`, { enabled })
    event.enabled = enabled
    ElMessage.success(t(enabled ? 'events.enabled' : 'events.disabled'))
  } catch (error) {
    showError(error)
  }
}

async function remove(event: SalesEvent) {
  try {
    await ElMessageBox.confirm(
      t('events.deleteBody', { name: event.name }),
      t('events.deleteTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      },
    )
    await api.delete(`/sales-events/${event.id}`)
    ElMessage.success(t('events.deleted'))
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  }
}

async function openExpenses(event: SalesEvent) {
  selectedEvent.value = event
  expensesOpen.value = true
  await loadExpenses()
}

async function loadExpenses() {
  if (!selectedEvent.value) return
  try {
    const [expenseResponse, categoryResponse] = await Promise.all([
      api.get<PageResponse<EventExpense>>(`/sales-events/${selectedEvent.value.id}/expenses`, {
        params: { page: 0, size: 100 },
      }),
      api.get<ExpenseCategory[]>('/expense-categories', { params: { includeDisabled: true } }),
    ])
    expenses.value = normalizePage(expenseResponse.data).items
    categories.value = categoryResponse.data
  } catch (error) {
    showError(error)
  }
}

function openExpenseCreate() {
  Object.assign(expenseForm, emptyExpense())
  expenseFormOpen.value = true
}

function openExpenseEdit(expense: EventExpense) {
  Object.assign(expenseForm, {
    id: expense.id,
    version: expense.version,
    categoryId: expense.categoryId,
    amount: Number(expense.amount),
    expenseDate: expense.expenseDate,
    note: expense.note || '',
  })
  expenseFormOpen.value = true
}

async function saveExpense() {
  if (!selectedEvent.value || !expenseForm.categoryId || expenseForm.amount <= 0) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    const payload = {
      categoryId: expenseForm.categoryId,
      amount: expenseForm.amount,
      expenseDate: expenseForm.expenseDate,
      note: expenseForm.note.trim() || null,
      version: expenseForm.version,
    }
    const base = `/sales-events/${selectedEvent.value.id}/expenses`
    if (expenseForm.id) await api.put(`${base}/${expenseForm.id}`, payload)
    else await api.post(base, payload)
    ElMessage.success(t(expenseForm.id ? 'expenses.updated' : 'expenses.created'))
    expenseFormOpen.value = false
    await loadExpenses()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

async function voidExpense(expense: EventExpense) {
  if (!selectedEvent.value) return
  try {
    await ElMessageBox.confirm(t('expenses.voidBody'), t('expenses.voidTitle'), {
      type: 'warning',
      confirmButtonText: t('expenses.voidAction'),
    })
    await api.post(`/sales-events/${selectedEvent.value.id}/expenses/${expense.id}/void`, {
      version: expense.version,
    })
    ElMessage.success(t('expenses.voided'))
    await loadExpenses()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  }
}

function openCategories() {
  Object.assign(categoryForm, { id: '', name: '', enabled: true, version: 0 })
  categoriesOpen.value = true
}

function editCategory(category: ExpenseCategory) {
  Object.assign(categoryForm, category)
}

async function saveCategory() {
  if (!categoryForm.name.trim()) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    if (categoryForm.id) {
      await api.put(`/expense-categories/${categoryForm.id}`, {
        name: categoryForm.name.trim(),
        enabled: categoryForm.enabled,
        version: categoryForm.version,
      })
    } else {
      await api.post('/expense-categories', { name: categoryForm.name.trim() })
    }
    ElMessage.success(t('expenses.categorySaved'))
    Object.assign(categoryForm, { id: '', name: '', enabled: true, version: 0 })
    await loadExpenses()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

function formatDate(value: string) {
  const [year, month, day] = value.split('-').map(Number)
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(year, month - 1, day, 12)))
}

function lifecycle(event: SalesEvent) {
  if (!event.enabled) return 'disabled'
  if (event.startDate > today) return 'upcoming'
  if (event.endDate < today) return 'finished'
  return 'ongoing'
}

function duration(event: SalesEvent) {
  const start = Date.parse(`${event.startDate}T00:00:00Z`)
  const end = Date.parse(`${event.endDate}T00:00:00Z`)
  return Math.round((end - start) / 86_400_000) + 1
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('events.eyebrow')"
      :title="t('events.title')"
      :subtitle="t('events.subtitle')"
    >
      <template #actions
        ><ElButton type="primary" :icon="Plus" @click="openCreate">{{
          t('events.add')
        }}</ElButton></template
      >
    </PageHeader>

    <section class="event-summary" :aria-label="t('events.summary')">
      <div>
        <Calendar /><span>{{ t('events.total') }}</span
        ><strong>{{ events.length }}</strong>
      </div>
      <div>
        <i /><span>{{ t('events.enabledCount') }}</span
        ><strong>{{ enabledCount }}</strong>
      </div>
      <div>
        <i /><span>{{ t('events.upcomingCount') }}</span
        ><strong>{{ upcomingCount }}</strong>
      </div>
    </section>

    <section class="panel data-panel">
      <div class="table-toolbar">
        <ElInput
          v-model="search"
          class="search-input"
          clearable
          :placeholder="t('events.searchPlaceholder')"
          :prefix-icon="Search"
        />
        <ElSelect v-model="status" :placeholder="t('common.status')">
          <ElOption :label="t('common.all')" value="ALL" />
          <ElOption :label="t('common.enabled')" value="ENABLED" />
          <ElOption :label="t('common.disabled')" value="DISABLED" />
        </ElSelect>
        <span class="table-toolbar__count">{{
          t('common.items', { count: filteredEvents.length })
        }}</span>
      </div>

      <ElTable
        v-if="filteredEvents.length || loading"
        v-loading="loading"
        :data="filteredEvents"
        row-key="id"
      >
        <ElTableColumn :label="t('events.exhibition')" min-width="250">
          <template #default="scope">
            <div class="event-name-cell">
              <span class="event-date-mark"
                >{{ scope.row.startDate.slice(5, 7)
                }}<b>{{ scope.row.startDate.slice(8, 10) }}</b></span
              >
              <span
                ><strong>{{ scope.row.name }}</strong
                ><small>{{ t(`events.lifecycle.${lifecycle(scope.row)}`) }}</small></span
              >
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('events.schedule')" min-width="310">
          <template #default="scope">
            <div class="event-schedule">
              <span>{{ formatDate(scope.row.startDate) }}</span
              ><i /><span>{{ formatDate(scope.row.endDate) }}</span>
              <small>{{ t('events.days', { count: duration(scope.row) }) }}</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('common.status')" width="140">
          <template #default="scope"
            ><ElSwitch
              :model-value="scope.row.enabled"
              :active-text="t('common.enabled')"
              :inactive-text="t('common.disabled')"
              inline-prompt
              @change="toggle(scope.row, Boolean($event))"
          /></template>
        </ElTableColumn>
        <ElTableColumn :label="t('common.actions')" width="180" fixed="right">
          <template #default="scope">
            <ElButton
              text
              :icon="Coin"
              :aria-label="t('expenses.manage')"
              @click="openExpenses(scope.row)"
            />
            <ElButton
              text
              :icon="Edit"
              :aria-label="t('common.edit')"
              @click="openEdit(scope.row)"
            />
            <ElButton
              text
              type="danger"
              :icon="Delete"
              :aria-label="t('common.delete')"
              @click="remove(scope.row)"
            />
          </template>
        </ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('events.emptyTitle')" :body="t('events.emptyBody')">
        <ElButton type="primary" :icon="Plus" @click="openCreate">{{ t('events.add') }}</ElButton>
      </EmptyState>
    </section>

    <ElDialog
      v-model="dialogOpen"
      :title="t(form.id ? 'events.edit' : 'events.create')"
      width="min(520px, 94vw)"
      destroy-on-close
    >
      <ElForm label-position="top">
        <ElFormItem :label="t('events.name')" required
          ><ElInput v-model="form.name" maxlength="240" show-word-limit
        /></ElFormItem>
        <div class="form-grid">
          <ElFormItem :label="t('events.startDate')" required
            ><ElDatePicker
              v-model="form.startDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="full-width"
          /></ElFormItem>
          <ElFormItem :label="t('events.endDate')" required
            ><ElDatePicker
              v-model="form.endDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="full-width"
          /></ElFormItem>
        </div>
        <ElFormItem v-if="form.id" :label="t('common.status')"
          ><ElSwitch
            v-model="form.enabled"
            :active-text="t('common.enabled')"
            :inactive-text="t('common.disabled')"
        /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton
        ><ElButton type="primary" :loading="saving" @click="save">{{
          saving ? t('common.saving') : t('common.save')
        }}</ElButton></template
      >
    </ElDialog>

    <ElDialog
      v-model="expensesOpen"
      :title="t('expenses.eventTitle', { name: selectedEvent?.name || '' })"
      width="min(900px, 96vw)"
      destroy-on-close
    >
      <div class="expense-toolbar">
        <ElButton :icon="Setting" @click="openCategories">{{ t('expenses.categories') }}</ElButton>
        <ElButton type="primary" :icon="Plus" @click="openExpenseCreate">{{
          t('expenses.add')
        }}</ElButton>
      </div>
      <ElTable v-if="expenses.length" :data="expenses" row-key="id">
        <ElTableColumn prop="expenseDate" :label="t('expenses.date')" min-width="130" />
        <ElTableColumn prop="categoryName" :label="t('expenses.category')" min-width="170" />
        <ElTableColumn :label="t('expenses.amount')" min-width="150" align="right">
          <template #default="scope">{{ money(scope.row.amount, scope.row.currency) }}</template>
        </ElTableColumn>
        <ElTableColumn
          prop="note"
          :label="t('expenses.note')"
          min-width="220"
          show-overflow-tooltip
        />
        <ElTableColumn :label="t('common.actions')" width="140" fixed="right">
          <template #default="scope">
            <ElButton
              text
              :icon="Edit"
              :aria-label="t('common.edit')"
              @click="openExpenseEdit(scope.row)"
            />
            <ElButton
              text
              type="danger"
              :icon="Delete"
              :aria-label="t('expenses.voidAction')"
              @click="voidExpense(scope.row)"
            />
          </template>
        </ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('expenses.emptyTitle')" :body="t('expenses.emptyBody')">
        <ElButton type="primary" :icon="Plus" @click="openExpenseCreate">{{
          t('expenses.add')
        }}</ElButton>
      </EmptyState>
    </ElDialog>

    <ElDialog
      v-model="expenseFormOpen"
      :title="t(expenseForm.id ? 'expenses.edit' : 'expenses.create')"
      width="min(560px, 94vw)"
      append-to-body
    >
      <ElForm label-position="top">
        <ElFormItem :label="t('expenses.category')" required>
          <ElSelect v-model="expenseForm.categoryId" class="full-width" filterable>
            <ElOption
              v-for="category in categories"
              :key="category.id"
              :label="category.name"
              :value="category.id"
              :disabled="!category.enabled && category.id !== expenseForm.categoryId"
            />
          </ElSelect>
        </ElFormItem>
        <div class="form-grid">
          <ElFormItem :label="t('expenses.amount')" required>
            <ElInputNumber
              v-model="expenseForm.amount"
              :min="0.01"
              :precision="2"
              controls-position="right"
              class="full-width"
            />
          </ElFormItem>
          <ElFormItem :label="t('expenses.date')" required>
            <ElDatePicker
              v-model="expenseForm.expenseDate"
              type="date"
              value-format="YYYY-MM-DD"
              class="full-width"
            />
          </ElFormItem>
        </div>
        <ElFormItem :label="t('expenses.currency')">
          <ElInput :model-value="expenseCurrency" disabled />
        </ElFormItem>
        <ElFormItem :label="`${t('expenses.note')} · ${t('common.optional')}`">
          <ElInput v-model="expenseForm.note" type="textarea" :rows="3" maxlength="2000" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="expenseFormOpen = false">{{ t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveExpense">{{
          saving ? t('common.saving') : t('common.save')
        }}</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="categoriesOpen"
      :title="t('expenses.categories')"
      width="min(620px, 94vw)"
      append-to-body
    >
      <ElForm label-position="top">
        <ElFormItem :label="t('expenses.categoryName')" required>
          <ElInput v-model="categoryForm.name" maxlength="160" />
        </ElFormItem>
        <ElFormItem v-if="categoryForm.id" :label="t('common.status')">
          <ElSwitch
            v-model="categoryForm.enabled"
            :active-text="t('common.enabled')"
            :inactive-text="t('common.disabled')"
          />
        </ElFormItem>
        <ElButton type="primary" :loading="saving" @click="saveCategory">{{
          t(categoryForm.id ? 'common.save' : 'expenses.addCategory')
        }}</ElButton>
      </ElForm>
      <ElTable v-if="categories.length" class="expense-category-table" :data="categories">
        <ElTableColumn prop="name" :label="t('expenses.categoryName')" />
        <ElTableColumn :label="t('common.status')" width="120">
          <template #default="scope">{{
            t(scope.row.enabled ? 'common.enabled' : 'common.disabled')
          }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('common.actions')" width="90">
          <template #default="scope">
            <ElButton
              text
              :icon="Edit"
              :aria-label="t('common.edit')"
              @click="editCategory(scope.row)"
            />
          </template>
        </ElTableColumn>
      </ElTable>
    </ElDialog>
  </div>
</template>
