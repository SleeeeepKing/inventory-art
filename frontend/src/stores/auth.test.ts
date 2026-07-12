import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const mocks = vi.hoisted(() => ({
  patch: vi.fn(),
  post: vi.fn(),
  setAccessToken: vi.fn(),
  configureSessionHandlers: vi.fn(),
  refreshSession: vi.fn(),
  warmBackend: vi.fn(),
  broadcastSessionChange: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  api: { patch: mocks.patch, post: mocks.post },
  setAccessToken: mocks.setAccessToken,
  configureSessionHandlers: mocks.configureSessionHandlers,
  refreshSession: mocks.refreshSession,
  warmBackend: mocks.warmBackend,
}))
vi.mock('@/services/sessionSync', () => ({
  broadcastSessionChange: mocks.broadcastSessionChange,
}))

import { i18n, setAppLocale } from '@/i18n'
import { useAuthStore } from './auth'
import type { AuthResponse, UserProfile } from '@/types/api'

function profile(locale: 'en' | 'zh-CN' | 'fr-FR'): UserProfile {
  return {
    id: 'user-1',
    username: 'demo',
    email: 'demo@example.com',
    displayName: 'Demo',
    role: 'USER',
    preferredLocale: locale,
  }
}
function session(locale: 'en' | 'zh-CN' | 'fr-FR'): AuthResponse {
  return { accessToken: 'access-token', user: profile(locale) }
}

describe('auth locale behavior', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mocks.warmBackend.mockResolvedValue(undefined)
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

  it('invalidates the local session after a password change', () => {
    const auth = useAuthStore()
    auth.applySession(session('fr-FR'))
    auth.invalidateLocalSession()
    expect(auth.user).toBeNull()
    expect(auth.token).toBeNull()
    expect(auth.initialized).toBe(true)
    expect(mocks.post).not.toHaveBeenCalled()
    expect(mocks.broadcastSessionChange).toHaveBeenCalledOnce()
  })

  it('warms the backend before refreshing the initial session', async () => {
    const auth = useAuthStore()
    mocks.refreshSession.mockResolvedValue(session('en'))

    await auth.initialize()

    expect(mocks.warmBackend).toHaveBeenCalledOnce()
    expect(mocks.refreshSession).toHaveBeenCalledOnce()
    expect(mocks.warmBackend.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.refreshSession.mock.invocationCallOrder[0],
    )
  })

  it('broadcasts login without persisting user data in browser storage', async () => {
    const auth = useAuthStore()
    mocks.post.mockResolvedValue({ data: session('en') })

    await auth.login('demo', 'password')

    expect(mocks.broadcastSessionChange).toHaveBeenCalledOnce()
    expect(auth.user?.username).toBe('demo')
  })

  it('persists a changed locale and keeps the server response', async () => {
    const auth = useAuthStore()
    auth.applySession(session('en'))
    mocks.patch.mockResolvedValue({ data: profile('fr-FR') })
    await auth.updateProfile({ displayName: 'Demo', preferredLocale: 'fr-FR' })
    expect(mocks.patch).toHaveBeenCalledWith('/profile', {
      displayName: 'Demo',
      preferredLocale: 'fr-FR',
    })
    expect(i18n.global.locale.value).toBe('fr-FR')
  })

  it('rolls the locale back when profile persistence fails', async () => {
    const auth = useAuthStore()
    auth.applySession(session('en'))
    mocks.patch.mockRejectedValue(new Error('network'))
    await expect(
      auth.updateProfile({ displayName: 'Demo', preferredLocale: 'zh-CN' }),
    ).rejects.toThrow()
    expect(i18n.global.locale.value).toBe('en')
  })
})
