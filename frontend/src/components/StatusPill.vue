<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ status: string }>()
const { t, te } = useI18n()
const label = computed(() =>
  te(`status.${props.status}`) ? t(`status.${props.status}`) : props.status.replaceAll('_', ' '),
)
const tone = computed(() => {
  if (['ENABLED', 'SUCCESS', 'USER'].includes(props.status)) return 'success'
  if (['FAILED', 'DISABLED'].includes(props.status)) return 'danger'
  if (props.status === 'ADMIN') return 'info'
  return 'neutral'
})
</script>

<template>
  <span class="status-pill" :data-tone="tone"><i />{{ label }}</span>
</template>
