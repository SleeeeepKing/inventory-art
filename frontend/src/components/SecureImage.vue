<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/services/api'

defineOptions({ inheritAttrs: false })

const props = defineProps<{ src?: string; alt: string }>()
const objectUrl = ref<string>()

function release() {
  if (objectUrl.value) URL.revokeObjectURL(objectUrl.value)
  objectUrl.value = undefined
}

watch(
  () => props.src,
  async (src, _previous, onCleanup) => {
    release()
    if (!src) return
    const controller = new AbortController()
    onCleanup(() => controller.abort())
    try {
      const { data } = await api.get<Blob>(src, {
        responseType: 'blob',
        signal: controller.signal,
      })
      if (!controller.signal.aborted) objectUrl.value = URL.createObjectURL(data)
    } catch {
      if (!controller.signal.aborted) release()
    }
  },
  { immediate: true },
)

onBeforeUnmount(release)
</script>

<template>
  <img
    v-if="objectUrl"
    v-bind="$attrs"
    :src="objectUrl"
    :alt="alt"
    loading="lazy"
    decoding="async"
  />
  <slot v-else />
</template>
