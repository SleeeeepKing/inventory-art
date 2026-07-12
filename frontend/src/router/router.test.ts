import { describe, expect, it } from 'vitest'
import { homeRouteName, router } from './index'

describe('role-aware navigation', () => {
  it('uses tenant management as the administrator home', () => {
    expect(homeRouteName(true)).toBe('admin-tenants')
    expect(homeRouteName(false)).toBe('dashboard')
  })

  it('keeps reports user-only and removes the global data route', () => {
    expect(router.getRoutes().find((route) => route.name === 'reports')?.meta.userOnly).toBe(true)
    expect(router.hasRoute('admin-data')).toBe(false)
  })
})
