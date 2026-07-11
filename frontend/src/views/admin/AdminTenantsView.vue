<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Edit, Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { AdminTenant, PageResponse } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()
const { showError } = useApiFeedback()
const { date } = useFormatters()
const page = ref<PageResponse<AdminTenant>>(normalizePage([]))
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const editingId = ref<string>()
const currentPage = ref(1)
const form = reactive({ name: '', slug: '', defaultCurrency: 'EUR', timezone: 'Europe/Paris', locale: 'fr-FR' })

async function load() {
  loading.value = true
  try { const { data } = await api.get<PageResponse<AdminTenant>>('/admin/tenants', { params: { page: currentPage.value - 1, size: 20 } }); page.value = normalizePage(data) }
  catch (error) { showError(error) } finally { loading.value = false }
}
function openCreate() { editingId.value = undefined; Object.assign(form, { name: '', slug: '', defaultCurrency: 'EUR', timezone: 'Europe/Paris', locale: 'fr-FR' }); dialogOpen.value = true }
function openEdit(tenant: AdminTenant) { editingId.value = tenant.id; Object.assign(form, { name: tenant.name, slug: tenant.slug || '', defaultCurrency: tenant.defaultCurrency || tenant.currency || 'EUR', timezone: tenant.timezone, locale: tenant.locale }); dialogOpen.value = true }
async function save() {
  if (!form.name.trim() || !form.slug.trim()) { ElMessage.warning(t('errors.validation')); return }
  saving.value = true
  try { if (editingId.value) await api.put(`/admin/tenants/${editingId.value}`, form); else await api.post('/admin/tenants', form); ElMessage.success(t(editingId.value ? 'admin.saved' : 'admin.created')); dialogOpen.value = false; await load() }
  catch (error) { showError(error) } finally { saving.value = false }
}
async function toggle(tenant: AdminTenant) {
  try { await api.post(`/admin/tenants/${tenant.id}/enabled`, { enabled: !tenant.enabled }); tenant.enabled = !tenant.enabled; ElMessage.success(t('admin.saved')) }
  catch (error) { showError(error) }
}
onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader :eyebrow="t('nav.management')" :title="t('admin.tenantsTitle')" :subtitle="t('admin.tenantsSubtitle')"><template #actions><ElButton type="primary" :icon="Plus" @click="openCreate">{{ t('admin.addTenant') }}</ElButton></template></PageHeader>
    <section class="panel data-panel">
      <div class="table-toolbar"><ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton><span class="table-toolbar__count">{{ t('common.items', { count: page.totalElements }) }}</span></div>
      <ElTable v-if="page.items.length || loading" v-loading="loading" :data="page.items">
        <ElTableColumn prop="name" :label="t('admin.tenantName')" min-width="220"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.name }}</strong><code class="sku-code">{{ scope.row.slug }}</code></div></template></ElTableColumn>
        <ElTableColumn prop="defaultCurrency" :label="t('admin.currency')" width="110" />
        <ElTableColumn prop="timezone" :label="t('admin.timezone')" min-width="170" />
        <ElTableColumn prop="locale" :label="t('admin.businessLocale')" width="140" />
        <ElTableColumn :label="t('common.created')" min-width="140"><template #default="scope">{{ date(scope.row.createdAt) }}</template></ElTableColumn>
        <ElTableColumn :label="t('common.status')" width="110"><template #default="scope"><ElSwitch :model-value="scope.row.enabled" @change="toggle(scope.row)" /></template></ElTableColumn>
        <ElTableColumn :label="t('common.actions')" width="90"><template #default="scope"><ElButton text :icon="Edit" :aria-label="t('common.edit')" @click="openEdit(scope.row)" /></template></ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('common.noData')" :body="t('admin.tenantsSubtitle')"><ElButton type="primary" :icon="Plus" @click="openCreate">{{ t('admin.addTenant') }}</ElButton></EmptyState>
      <ElPagination v-if="page.totalPages > 1" v-model:current-page="currentPage" class="table-pagination" background layout="prev, pager, next" :page-size="20" :total="page.totalElements" @current-change="load" />
    </section>
    <ElDialog v-model="dialogOpen" :title="t(editingId ? 'admin.editTenant' : 'admin.addTenant')" width="min(620px, 94vw)">
      <ElForm label-position="top" class="form-grid"><ElFormItem :label="t('admin.tenantName')" required><ElInput v-model="form.name" /></ElFormItem><ElFormItem :label="t('admin.slug')" required><ElInput v-model="form.slug" :disabled="Boolean(editingId)" /></ElFormItem><ElFormItem :label="t('admin.currency')"><ElSelect v-model="form.defaultCurrency"><ElOption label="EUR" value="EUR" /><ElOption label="USD" value="USD" /><ElOption label="GBP" value="GBP" /></ElSelect></ElFormItem><ElFormItem :label="t('admin.timezone')"><ElInput v-model="form.timezone" /></ElFormItem><ElFormItem :label="t('admin.businessLocale')"><ElSelect v-model="form.locale"><ElOption label="en" value="en" /><ElOption label="zh-CN" value="zh-CN" /><ElOption label="fr-FR" value="fr-FR" /></ElSelect></ElFormItem></ElForm>
      <template #footer><ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="save">{{ saving ? t('common.saving') : t('common.save') }}</ElButton></template>
    </ElDialog>
  </div>
</template>
