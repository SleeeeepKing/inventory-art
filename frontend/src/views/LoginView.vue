<script setup lang="ts">
import { reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight, Lock, Message, TrendCharts } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useApiFeedback } from '@/composables/useApiFeedback'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const { showError } = useApiFeedback()
const form = reactive({ username: '', password: '' })

async function submit() {
  try {
    await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (error) {
    showError(error)
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-story">
      <div class="login-brand"><span class="brand-mark"><span /><b>{{ t('app.shortName') }}</b></span><strong>{{ t('app.name') }}</strong></div>
      <div class="login-story__message">
        <p class="eyebrow">{{ t('app.workspace') }}</p>
        <h1>{{ t('app.tagline') }}</h1>
        <div class="stock-track" aria-hidden="true"><i /><i /><i /><i /><i /></div>
        <p>{{ t('dashboard.subtitle') }}</p>
      </div>
      <div class="login-story__foot"><TrendCharts />{{ t('dashboard.salesTrend') }}</div>
    </section>
    <section class="login-panel">
      <div class="login-form-wrap">
        <p class="eyebrow">{{ t('auth.signIn') }}</p>
        <h2>{{ t('auth.welcome') }}</h2>
        <p>{{ t('auth.instruction') }}</p>
        <ElForm label-position="top" @submit.prevent="submit">
          <ElFormItem :label="t('auth.email')">
            <ElInput v-model="form.username" size="large" autocomplete="username" :placeholder="t('auth.emailPlaceholder')" :prefix-icon="Message" />
          </ElFormItem>
          <ElFormItem :label="t('auth.password')">
            <ElInput v-model="form.password" size="large" type="password" autocomplete="current-password" show-password :placeholder="t('auth.passwordPlaceholder')" :prefix-icon="Lock" @keyup.enter="submit" />
          </ElFormItem>
          <ElButton class="login-submit" type="primary" size="large" :loading="auth.loading" @click="submit">
            {{ auth.loading ? t('auth.signingIn') : t('auth.signIn') }}<ArrowRight v-if="!auth.loading" />
          </ElButton>
        </ElForm>
        <small class="secure-note"><Lock />{{ t('auth.secureSession') }}</small>
      </div>
    </section>
  </main>
</template>
