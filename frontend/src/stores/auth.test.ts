import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const mocks = vi.hoisted(() => ({
  patch: vi.fn(),
  post: vi.fn(),
  setAccessToken: vi.fn(),
  configureSessionHandlers: vi.fn(),
  refreshSession: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  api: { patch: mocks.patch, post: mocks.post },
  setAccessToken: mocks.setAccessToken,
  configureSessionHandlers: mocks.configureSessionHandlers,
  refreshSession: mocks.refreshSession,
}))

import { i18n, setAppLocale } from '@/i18n'
import { useAuthStore } from './auth'
import type { AuthResponse, UserProfile } from '@/types/api'

function profile(locale: 'en' | 'zh-CN' | 'fr-FR'): UserProfile {
  return { id: 'user-1', username: 'demo', email: 'demo@example.com', displayName: 'Demo', role: 'USER', preferredLocale: locale }
}
function session(locale: 'en' | 'zh-CN' | 'fr-FR'): AuthResponse {
  return { accessToken: 'access-token', user: profile(locale) }
}

describe('auth locale behavior', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    setAppLocale('en')
  })

  it('uses the preferred locale returned by the server after login/session refresh', () => {
    const auth = useAuthStore()
    auth.applySession(session('zh-CN'))
    expect(auth.user?.preferredLocale).toBe('zh-CN')
    expect(i18n.global.locale.value).toBe('zh-CN')
    expect(mocks.setAccessToken).toHaveBeenCalledWith('access-token')
  })

  it('returns unauthenticated UI to English', () => {
    const auth = useAuthStore()
    auth.applySession(session('fr-FR'))
    auth.clearSession()
    expect(auth.user).toBeNull()
    expect(i18n.global.locale.value).toBe('en')
  })

  it('persists a changed locale and keeps the server response', async () => {
    const auth = useAuthStore()
    auth.applySession(session('en'))
    mocks.patch.mockResolvedValue({ data: profile('fr-FR') })
    await auth.updateProfile({ displayName: 'Demo', preferredLocale: 'fr-FR' })
    expect(mocks.patch).toHaveBeenCalledWith('/profile', { displayName: 'Demo', preferredLocale: 'fr-FR' })
    expect(i18n.global.locale.value).toBe('fr-FR')
  })

  it('rolls the locale back when profile persistence fails', async () => {
    const auth = useAuthStore()
    auth.applySession(session('en'))
    mocks.patch.mockRejectedValue(new Error('network'))
    await expect(auth.updateProfile({ displayName: 'Demo', preferredLocale: 'zh-CN' })).rejects.toThrow()
    expect(i18n.global.locale.value).toBe('en')
  })
})
