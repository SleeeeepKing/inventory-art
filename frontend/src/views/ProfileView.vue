<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Location, OfficeBuilding, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useApiFeedback } from '@/composables/useApiFeedback'
import type { SupportedLocale } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'

const auth = useAuthStore()
const { t } = useI18n()
const { showError } = useApiFeedback()
const saving = ref(false)
const form = reactive({ displayName: '', preferredLocale: 'en' as SupportedLocale })
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
    </section>
    <div class="sticky-save"><ElButton type="primary" size="large" :loading="saving" @click="save">{{ saving ? t('common.saving') : t('common.save') }}</ElButton></div>
  </div>
</template>
