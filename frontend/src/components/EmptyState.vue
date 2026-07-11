<script setup lang="ts">
import { Box } from '@element-plus/icons-vue'

defineProps<{ title: string; body: string }>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-state__icon"><Box /></div>
    <h3>{{ title }}</h3>
    <p>{{ body }}</p>
    <slot />
  </div>
</template>
