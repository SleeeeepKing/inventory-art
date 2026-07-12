import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, configureSessionHandlers, refreshSession, setAccessToken } from '@/services/api'
import { setAppLocale } from '@/i18n'
import type { AuthResponse, SupportedLocale, UserProfile } from '@/types/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserProfile | null>(null)
  const token = ref<string | null>(null)
  const initialized = ref(false)
  const loading = ref(false)

  const isAuthenticated = computed(() => Boolean(token.value && user.value))
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  function applySession(session: AuthResponse) {
    token.value = session.accessToken
    user.value = session.user
    setAccessToken(session.accessToken)
    setAppLocale(session.user.preferredLocale || 'en')
  }

  function clearSession() {
    token.value = null
    user.value = null
    setAccessToken(null)
    setAppLocale('en')
  }

  function invalidateLocalSession() {
    clearSession()
    initialized.value = true
  }

  configureSessionHandlers({ updated: applySession, expired: clearSession })

  async function initialize() {
    if (initialized.value) return
    try {
      applySession(await refreshSession())
    } catch {
      clearSession()
    } finally {
      initialized.value = true
    }
  }

  async function login(username: string, password: string) {
    loading.value = true
    try {
      const { data } = await api.post<AuthResponse>('/auth/login', { username, password })
      applySession(data)
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    try {
      await api.post('/auth/logout')
    } finally {
      clearSession()
      initialized.value = true
    }
  }

  async function updateProfile(payload: { displayName: string; preferredLocale: SupportedLocale }) {
    const previousLocale = user.value?.preferredLocale || 'en'
    setAppLocale(payload.preferredLocale)
    try {
      const { data } = await api.patch<UserProfile>('/profile', payload)
      user.value = data
      setAppLocale(data.preferredLocale)
      return data
    } catch (error) {
      setAppLocale(previousLocale)
      throw error
    }
  }

  return {
    user,
    token,
    initialized,
    loading,
    isAuthenticated,
    isAdmin,
    initialize,
    login,
    logout,
    updateProfile,
    applySession,
    clearSession,
    invalidateLocalSession,
  }
})
