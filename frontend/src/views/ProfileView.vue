<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Lock, Location, OfficeBuilding, User } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { useApiFeedback } from '@/composables/useApiFeedback'
import type { SupportedLocale } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'

const auth = useAuthStore()
const router = useRouter()
const { t } = useI18n()
const { showError } = useApiFeedback()
const saving = ref(false)
const passwordSaving = ref(false)
const form = reactive({ displayName: '', preferredLocale: 'en' as SupportedLocale })
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const localeOptions: Array<{ value: SupportedLocale; key: string }> = [{ value: 'en', key: 'profile.english' }, { value: 'zh-CN', key: 'profile.chinese' }, { value: 'fr-FR', key: 'profile.french' }]

onMounted(() => {
  form.displayName = auth.user?.displayName || ''
  form.preferredLocale = auth.user?.preferredLocale || 'en'
})

async function save() {
  if (!form.displayName.trim()) { ElMessage.warning(t('errors.validation')); return }
  saving.value = true
  try {
    await auth.updateProfile({ displayName: form.displayName.trim(), preferredLocale: form.preferredLocale })
    ElMessage.success(t('profile.saved'))
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function changePassword() {
  if (!passwordForm.currentPassword || passwordForm.newPassword.length < 10 || passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning(t(passwordForm.newPassword !== passwordForm.confirmPassword ? 'profile.passwordMismatch' : 'errors.validation'))
    return
  }
  passwordSaving.value = true
  try {
    await api.post('/profile/password', { currentPassword: passwordForm.currentPassword, newPassword: passwordForm.newPassword })
    auth.invalidateLocalSession()
    ElMessage.success(t('profile.passwordChanged'))
    await router.replace({ name: 'login' })
  } catch (error) { showError(error) } finally { passwordSaving.value = false }
}
</script>

<template>
  <div class="page-stack profile-page">
    <PageHeader :eyebrow="t('profile.eyebrow')" :title="t('profile.title')" :subtitle="t('profile.subtitle')" />
    <section class="settings-grid">
      <article class="panel settings-card">
        <div class="settings-card__heading"><span><User /></span><div><h2>{{ t('profile.identity') }}</h2><p>{{ auth.user?.role }}</p></div></div>
        <ElForm label-position="top">
          <ElFormItem :label="t('profile.displayName')" required><ElInput v-model="form.displayName" size="large" /></ElFormItem>
          <ElFormItem :label="t('profile.email')"><ElInput :model-value="auth.user?.email" size="large" disabled /></ElFormItem>
        </ElForm>
      </article>
      <article class="panel settings-card language-card">
        <div class="settings-card__heading"><span><Location /></span><div><h2>{{ t('profile.language') }}</h2><p>{{ t('profile.languageHint') }}</p></div></div>
        <div class="locale-options">
          <label v-for="option in localeOptions" :key="option.value" :class="{ 'is-selected': form.preferredLocale === option.value }">
            <input v-model="form.preferredLocale" type="radio" :value="option.value" />
            <span class="locale-code">{{ option.value }}</span><strong>{{ t(option.key) }}</strong><i />
          </label>
        </div>
      </article>
      <article class="panel settings-card tenant-card">
        <div class="settings-card__heading"><span><OfficeBuilding /></span><div><h2>{{ t('profile.tenantSettings') }}</h2><p>{{ auth.user?.tenant?.name }}</p></div></div>
        <dl><div><dt>{{ t('profile.timezone') }}</dt><dd>{{ auth.user?.tenant?.timezone || 'UTC' }}</dd></div><div><dt>{{ t('profile.currency') }}</dt><dd>{{ auth.user?.tenant?.defaultCurrency || 'EUR' }}</dd></div><div><dt>{{ t('profile.businessLocale') }}</dt><dd>{{ auth.user?.tenant?.locale || '—' }}</dd></div></dl>
      </article>
      <article class="panel settings-card">
        <div class="settings-card__heading"><span><Lock /></span><div><h2>{{ t('profile.changePassword') }}</h2><p>{{ t('profile.passwordHint') }}</p></div></div>
        <ElForm label-position="top">
          <ElFormItem :label="t('profile.currentPassword')" required><ElInput v-model="passwordForm.currentPassword" type="password" show-password autocomplete="current-password" /></ElFormItem>
          <ElFormItem :label="t('profile.newPassword')" required><ElInput v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" /><small class="field-hint">{{ t('validation.passwordLength') }}</small></ElFormItem>
          <ElFormItem :label="t('profile.confirmPassword')" required><ElInput v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" /></ElFormItem>
          <ElButton type="primary" :loading="passwordSaving" @click="changePassword">{{ t('profile.changePassword') }}</ElButton>
        </ElForm>
      </article>
    </section>
    <div class="sticky-save"><ElButton type="primary" size="large" :loading="saving" @click="save">{{ saving ? t('common.saving') : t('common.save') }}</ElButton></div>
  </div>
</template>
