<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Plus, RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import type { AdminTenant, PageResponse, SupportedLocale, UserProfile } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'

const { t } = useI18n()
const { showError } = useApiFeedback()
const page = ref<PageResponse<UserProfile>>(normalizePage([]))
const tenants = ref<AdminTenant[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const currentPage = ref(1)
const tenantFilter = ref('')
const form = reactive({ tenantId: '', username: '', email: '', password: '', displayName: '', role: 'USER' as 'USER' | 'ADMIN', preferredLocale: 'en' as SupportedLocale })
function tenantName(id?: string) { return tenants.value.find((tenant) => tenant.id === id)?.name || (id ? id.slice(0, 8) : '—') }
async function load() {
  loading.value = true
  try {
    const [users, tenantResponse] = await Promise.all([api.get<PageResponse<UserProfile>>('/admin/users', { params: { page: currentPage.value - 1, size: 20, tenantId: tenantFilter.value || undefined } }), api.get<PageResponse<AdminTenant>>('/admin/tenants', { params: { page: 0, size: 100 } })])
    page.value = normalizePage(users.data); tenants.value = normalizePage(tenantResponse.data).items
  } catch (error) { showError(error) } finally { loading.value = false }
}
function openCreate() { Object.assign(form, { tenantId: tenants.value[0]?.id || '', username: '', email: '', password: '', displayName: '', role: 'USER', preferredLocale: 'en' }); dialogOpen.value = true }
async function createUser() {
  if (!form.username.trim() || !form.email.trim() || !form.displayName.trim() || form.password.length < 10 || (form.role === 'USER' && !form.tenantId)) { ElMessage.warning(t('errors.validation')); return }
  saving.value = true
  try { await api.post('/admin/users', { ...form, tenantId: form.role === 'ADMIN' ? null : form.tenantId }); ElMessage.success(t('admin.created')); dialogOpen.value = false; await load() }
  catch (error) { showError(error) } finally { saving.value = false }
}
async function toggle(user: UserProfile) {
  const next = !(user.enabled ?? true)
  try { await api.post(`/admin/users/${user.id}/enabled`, { enabled: next }); user.enabled = next; ElMessage.success(t('admin.saved')) }
  catch (error) { showError(error) }
}
onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader :eyebrow="t('nav.management')" :title="t('admin.usersTitle')" :subtitle="t('admin.usersSubtitle')"><template #actions><ElButton type="primary" :icon="Plus" @click="openCreate">{{ t('admin.addUser') }}</ElButton></template></PageHeader>
    <section class="panel data-panel">
      <div class="table-toolbar"><ElSelect v-model="tenantFilter" clearable :placeholder="t('common.tenant')" @change="currentPage = 1; load()"><ElOption v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" /></ElSelect><ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton><span class="table-toolbar__count">{{ t('common.items', { count: page.totalElements }) }}</span></div>
      <ElTable v-if="page.items.length || loading" v-loading="loading" :data="page.items">
        <ElTableColumn :label="t('common.name')" min-width="190"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.displayName }}</strong><code class="sku-code">{{ scope.row.username }}</code></div></template></ElTableColumn>
        <ElTableColumn prop="email" :label="t('common.email')" min-width="230" />
        <ElTableColumn :label="t('common.tenant')" min-width="180"><template #default="scope">{{ tenantName(scope.row.tenantId) }}</template></ElTableColumn>
        <ElTableColumn :label="t('common.role')" width="120"><template #default="scope"><StatusPill :status="scope.row.role" /></template></ElTableColumn>
        <ElTableColumn prop="preferredLocale" :label="t('admin.preferredLanguage')" width="150" />
        <ElTableColumn :label="t('common.status')" width="130"><template #default="scope"><ElSwitch :model-value="scope.row.enabled ?? true" :active-text="t('common.enabled')" :inactive-text="t('common.disabled')" @change="toggle(scope.row)" /></template></ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('common.noData')" :body="t('admin.usersSubtitle')"><ElButton type="primary" :icon="Plus" @click="openCreate">{{ t('admin.addUser') }}</ElButton></EmptyState>
      <ElPagination v-if="page.totalPages > 1" v-model:current-page="currentPage" class="table-pagination" background layout="prev, pager, next" :page-size="20" :total="page.totalElements" @current-change="load" />
    </section>
    <ElDialog v-model="dialogOpen" :title="t('admin.addUser')" width="min(680px, 94vw)">
      <ElForm label-position="top" class="form-grid"><ElFormItem :label="t('admin.username')" required><ElInput v-model="form.username" autocomplete="off" /></ElFormItem><ElFormItem :label="t('common.email')" required><ElInput v-model="form.email" type="email" autocomplete="off" /></ElFormItem><ElFormItem :label="t('admin.displayName')" required><ElInput v-model="form.displayName" /></ElFormItem><ElFormItem :label="t('admin.temporaryPassword')" required><ElInput v-model="form.password" type="password" show-password autocomplete="new-password" /><small class="field-hint">{{ t('validation.passwordLength') }}</small></ElFormItem><ElFormItem :label="t('common.role')"><ElSelect v-model="form.role"><ElOption label="USER" value="USER" /><ElOption label="ADMIN" value="ADMIN" /></ElSelect></ElFormItem><ElFormItem v-if="form.role === 'USER'" :label="t('common.tenant')" required><ElSelect v-model="form.tenantId" filterable><ElOption v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" /></ElSelect></ElFormItem><ElFormItem :label="t('admin.preferredLanguage')"><ElSelect v-model="form.preferredLocale"><ElOption :label="t('profile.english')" value="en" /><ElOption :label="t('profile.chinese')" value="zh-CN" /><ElOption :label="t('profile.french')" value="fr-FR" /></ElSelect></ElFormItem></ElForm>
      <template #footer><ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="saving" @click="createUser">{{ saving ? t('common.saving') : t('common.create') }}</ElButton></template>
    </ElDialog>
  </div>
</template>
