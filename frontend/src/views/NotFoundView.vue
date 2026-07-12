<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'

const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <main class="not-found">
    <div class="not-found__code">404</div>
    <p class="eyebrow">{{ t('errors.title') }}</p>
    <h1>{{ t('errors.notFound') }}</h1>
    <p>{{ t('errors.notFoundBody') }}</p>
    <ElButton type="primary" :icon="ArrowLeft" @click="router.push('/dashboard')">{{
      t('errors.returnDashboard')
    }}</ElButton>
  </main>
</template>
