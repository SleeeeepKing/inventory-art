<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElConfigProvider } from 'element-plus'
import { RouterView, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { elementLocaleFor } from '@/i18n/elementLocale'
import { useAuthStore } from '@/stores/auth'
import { onRemoteSessionChange } from '@/services/sessionSync'
import PwaStatus from '@/components/PwaStatus.vue'

const { locale } = useI18n()
const auth = useAuthStore()
const router = useRouter()
const elementLocale = computed(() => elementLocaleFor(locale.value))
const revalidating = ref(false)
const sessionEpoch = ref(0)
let stopSessionSync: () => void = () => undefined

async function handleRemoteSessionChange() {
  auth.invalidateLocalSession(false)
  sessionEpoch.value += 1
  await router.replace('/login')
}

async function handlePageShow(event: PageTransitionEvent) {
  if (!event.persisted) return
  revalidating.value = true
  auth.invalidateLocalSession(false)
  try {
    await auth.revalidateSession()
    sessionEpoch.value += 1
    if (!auth.isAuthenticated) await router.replace('/login')
  } finally {
    revalidating.value = false
  }
}

onMounted(() => {
  stopSessionSync = onRemoteSessionChange(() => void handleRemoteSessionChange())
  window.addEventListener('pageshow', handlePageShow)
})
onBeforeUnmount(() => {
  stopSessionSync()
  window.removeEventListener('pageshow', handlePageShow)
})
</script>

<template>
  <ElConfigProvider :locale="elementLocale">
    <div v-if="revalidating" class="session-revalidation" role="status" aria-live="polite">
      <span />{{ $t('auth.restoringSession') }}
    </div>
    <RouterView v-else :key="sessionEpoch" />
    <PwaStatus />
  </ElConfigProvider>
</template>
