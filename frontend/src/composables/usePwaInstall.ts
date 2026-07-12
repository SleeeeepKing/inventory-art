import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'

export function isAppleMobileDevice(input: {
  userAgent: string
  platform?: string
  maxTouchPoints?: number
}) {
  return (
    /iPad|iPhone|iPod/i.test(input.userAgent) ||
    (input.platform === 'MacIntel' && (input.maxTouchPoints || 0) > 1)
  )
}

export function isStandaloneDisplay(input: {
  mediaMatches?: boolean
  navigatorStandalone?: boolean
}) {
  return Boolean(input.mediaMatches || input.navigatorStandalone)
}

export function usePwaInstall() {
  const deferredPrompt = shallowRef<BeforeInstallPromptEvent>()
  const standalone = ref(false)
  const installed = ref(false)
  const isAndroid = /Android/i.test(navigator.userAgent)
  const isAppleMobile = isAppleMobileDevice({
    userAgent: navigator.userAgent,
    platform: navigator.platform,
    maxTouchPoints: navigator.maxTouchPoints,
  })

  function refreshStandaloneState() {
    standalone.value = isStandaloneDisplay({
      mediaMatches: window.matchMedia?.('(display-mode: standalone)').matches,
      navigatorStandalone: navigator.standalone,
    })
  }

  function captureInstallPrompt(event: Event) {
    event.preventDefault()
    deferredPrompt.value = event as BeforeInstallPromptEvent
  }

  function handleInstalled() {
    installed.value = true
    deferredPrompt.value = undefined
    refreshStandaloneState()
  }

  async function promptInstall() {
    const prompt = deferredPrompt.value
    if (!prompt) return 'unavailable' as const
    await prompt.prompt()
    const choice = await prompt.userChoice
    deferredPrompt.value = undefined
    return choice.outcome
  }

  onMounted(() => {
    refreshStandaloneState()
    window.addEventListener('beforeinstallprompt', captureInstallPrompt)
    window.addEventListener('appinstalled', handleInstalled)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('beforeinstallprompt', captureInstallPrompt)
    window.removeEventListener('appinstalled', handleInstalled)
  })

  const canInstallAndroid = computed(
    () => isAndroid && Boolean(deferredPrompt.value) && !standalone.value && !installed.value,
  )
  const shouldGuideApple = computed(() => isAppleMobile && !standalone.value && !installed.value)

  return {
    canInstallAndroid,
    shouldGuideApple,
    standalone,
    promptInstall,
  }
}
