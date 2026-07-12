<script setup lang="ts">
import { computed } from 'vue'
import { ElConfigProvider } from 'element-plus'
import { RouterView } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { elementLocaleFor } from '@/i18n/elementLocale'

const { locale } = useI18n()
const elementLocale = computed(() => elementLocaleFor(locale.value))
</script>

<template>
  <ElConfigProvider :locale="elementLocale">
    <RouterView />
  </ElConfigProvider>
</template>
