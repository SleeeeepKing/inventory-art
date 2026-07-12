import { readonly, ref } from 'vue'

export type BackendConnectionStatus = 'idle' | 'waiting' | 'retrying' | 'unavailable'

const online = ref(typeof navigator === 'undefined' ? true : navigator.onLine)
const backendStatus = ref<BackendConnectionStatus>('idle')
let activeRequests = 0

if (typeof window !== 'undefined') {
  window.addEventListener('online', () => {
    online.value = true
    if (backendStatus.value === 'unavailable') backendStatus.value = 'idle'
  })
  window.addEventListener('offline', () => {
    online.value = false
    backendStatus.value = 'idle'
  })
}

export function beginBackendRead() {
  activeRequests += 1
  let finished = false
  const slowTimer = window.setTimeout(() => {
    if (online.value && backendStatus.value === 'idle') backendStatus.value = 'waiting'
  }, 1_500)

  return () => {
    if (finished) return
    finished = true
    window.clearTimeout(slowTimer)
    activeRequests = Math.max(0, activeRequests - 1)
    if (activeRequests === 0 && backendStatus.value !== 'unavailable') {
      backendStatus.value = 'idle'
    }
  }
}

export function markBackendRetrying() {
  if (online.value) backendStatus.value = 'retrying'
}

export function markBackendUnavailable() {
  if (online.value) backendStatus.value = 'unavailable'
}

export function markBackendAvailable() {
  if (activeRequests === 0) backendStatus.value = 'idle'
}

export function resetConnectivityState() {
  activeRequests = 0
  backendStatus.value = 'idle'
}

export const isOnline = readonly(online)
export const backendConnectionStatus = readonly(backendStatus)
