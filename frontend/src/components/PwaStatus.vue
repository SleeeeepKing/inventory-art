<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRegisterSW } from 'virtual:pwa-register/vue'
import { ElMessageBox } from 'element-plus'
import {
  CircleCheck,
  Connection,
  Download,
  Refresh,
  Share,
  WarningFilled,
} from '@element-plus/icons-vue'
import { backendConnectionStatus, isOnline, markBackendAvailable } from '@/services/connectivity'
import { warmBackend } from '@/services/api'
import { usePwaInstall } from '@/composables/usePwaInstall'

const { t } = useI18n()
const installDismissed = ref(false)
const iosGuideOpen = ref(false)
const retryingBackend = ref(false)
const registration = shallowRef<ServiceWorkerRegistration>()
let lastUpdateCheck = 0

const { canInstallAndroid, shouldGuideApple, promptInstall } = usePwaInstall()
const { offlineReady, needRefresh, updateServiceWorker } = useRegisterSW({
  immediate: true,
  onRegisteredSW(_url, useRegistration) {
    registration.value = useRegistration
  },
})

type StatusKind = 'offline' | 'update' | 'backend' | 'ready' | 'install'

const statusKind = computed<StatusKind | undefined>(() => {
  if (!isOnline.value) return 'offline'
  if (needRefresh.value) return 'update'
  if (backendConnectionStatus.value !== 'idle') return 'backend'
  if (offlineReady.value) return 'ready'
  if (!installDismissed.value && (canInstallAndroid.value || shouldGuideApple.value))
    return 'install'
  return undefined
})

const statusIcon = computed(() => {
  if (statusKind.value === 'offline') return WarningFilled
  if (statusKind.value === 'update') return Refresh
  if (statusKind.value === 'backend') return Connection
  if (statusKind.value === 'ready') return CircleCheck
  return Download
})

const statusTitle = computed(() =>
  statusKind.value ? t(`pwa.status.${statusKind.value}.title`) : '',
)
const statusBody = computed(() => {
  if (statusKind.value === 'backend' && backendConnectionStatus.value === 'unavailable') {
    return t('pwa.status.backend.unavailable')
  }
  return statusKind.value ? t(`pwa.status.${statusKind.value}.body`) : ''
})

async function confirmUpdate() {
  try {
    await ElMessageBox.confirm(t('pwa.updateWarning'), t('pwa.updateTitle'), {
      confirmButtonText: t('pwa.updateNow'),
      cancelButtonText: t('common.cancel'),
      type: 'warning',
    })
    await updateServiceWorker(true)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function installAndroid() {
  const outcome = await promptInstall()
  if (outcome !== 'accepted') installDismissed.value = true
}

async function retryBackend() {
  retryingBackend.value = true
  try {
    await warmBackend()
    markBackendAvailable()
  } catch {
    return
  } finally {
    retryingBackend.value = false
  }
}

function checkForUpdate() {
  if (document.visibilityState !== 'visible' || !navigator.onLine || !registration.value) return
  const now = Date.now()
  if (now - lastUpdateCheck < 60 * 60 * 1_000) return
  lastUpdateCheck = now
  void registration.value.update()
}

onMounted(() => document.addEventListener('visibilitychange', checkForUpdate))
onBeforeUnmount(() => document.removeEventListener('visibilitychange', checkForUpdate))
</script>

<template>
  <Transition name="pwa-ticket">
    <section
      v-if="statusKind"
      class="pwa-status"
      :data-tone="statusKind"
      :role="statusKind === 'offline' ? 'alert' : 'status'"
      aria-live="polite"
    >
      <span class="pwa-status__mark"><component :is="statusIcon" /></span>
      <span class="pwa-status__copy">
        <strong>{{ statusTitle }}</strong>
        <small>{{ statusBody }}</small>
      </span>
      <span class="pwa-status__actions">
        <template v-if="statusKind === 'update'">
          <ElButton size="small" @click="needRefresh = false">{{ t('pwa.later') }}</ElButton>
          <ElButton size="small" type="primary" @click="confirmUpdate">{{
            t('pwa.updateNow')
          }}</ElButton>
        </template>
        <template v-else-if="statusKind === 'backend'">
          <ElButton
            v-if="backendConnectionStatus === 'unavailable'"
            size="small"
            :loading="retryingBackend"
            @click="retryBackend"
            >{{ t('common.retry') }}</ElButton
          >
        </template>
        <template v-else-if="statusKind === 'ready'">
          <ElButton size="small" @click="offlineReady = false">{{ t('common.close') }}</ElButton>
        </template>
        <template v-else-if="statusKind === 'install'">
          <ElButton size="small" @click="installDismissed = true">{{ t('pwa.later') }}</ElButton>
          <ElButton v-if="canInstallAndroid" size="small" type="primary" @click="installAndroid">{{
            t('pwa.install')
          }}</ElButton>
          <ElButton v-else size="small" type="primary" @click="iosGuideOpen = true">{{
            t('pwa.showInstructions')
          }}</ElButton>
        </template>
      </span>
    </section>
  </Transition>

  <ElDialog
    v-model="iosGuideOpen"
    class="pwa-install-dialog"
    :title="t('pwa.ios.title')"
    width="430"
  >
    <div class="pwa-install-guide">
      <span class="pwa-install-guide__icon"><Share /></span>
      <ol>
        <li>{{ t('pwa.ios.share') }}</li>
        <li>{{ t('pwa.ios.add') }}</li>
        <li>{{ t('pwa.ios.confirm') }}</li>
      </ol>
    </div>
    <template #footer>
      <ElButton type="primary" @click="iosGuideOpen = false">{{ t('common.close') }}</ElButton>
    </template>
  </ElDialog>
</template>
