<script setup lang="ts">
import { computed } from 'vue'
import { ElConfigProvider } from 'element-plus'
import elementEn from 'element-plus/es/locale/lang/en'
import elementFr from 'element-plus/es/locale/lang/fr'
import elementZhCn from 'element-plus/es/locale/lang/zh-cn'
import { RouterView } from 'vue-router'
import { useI18n } from 'vue-i18n'

const { locale } = useI18n()
const elementLocale = computed(() => ({ en: elementEn, 'zh-CN': elementZhCn, 'fr-FR': elementFr })[locale.value] || elementEn)
</script>

<template>
  <ElConfigProvider :locale="elementLocale">
    <RouterView />
  </ElConfigProvider>
</template>
