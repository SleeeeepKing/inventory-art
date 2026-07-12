import { describe, expect, it } from 'vitest'
import {
  isNavigationFallbackAllowed,
  isNetworkOnlyRequest,
  navigationFallbackDenylist,
} from './cachePolicy'

const appOrigin = 'https://inventory.example.com'

describe('service worker cache boundaries', () => {
  it('keeps API, actuator, and every cross-origin request on the network', () => {
    expect(isNetworkOnlyRequest(new URL(`${appOrigin}/api/v1/orders`), appOrigin)).toBe(true)
    expect(isNetworkOnlyRequest(new URL(`${appOrigin}/api`), appOrigin)).toBe(true)
    expect(isNetworkOnlyRequest(new URL(`${appOrigin}/actuator/health`), appOrigin)).toBe(true)
    expect(
      isNetworkOnlyRequest(new URL('https://api.railway.example/api/v1/products'), appOrigin),
    ).toBe(true)
    expect(
      isNetworkOnlyRequest(
        new URL('https://private.r2.cloudflarestorage.com/signed-object?token=secret'),
        appOrigin,
      ),
    ).toBe(true)
  })

  it('allows same-origin app-shell assets and navigations', () => {
    expect(isNetworkOnlyRequest(new URL(`${appOrigin}/assets/app-123.js`), appOrigin)).toBe(false)
    expect(isNavigationFallbackAllowed(new URL(`${appOrigin}/orders`), appOrigin)).toBe(true)
    expect(isNavigationFallbackAllowed(new URL(`${appOrigin}/api/v1/orders`), appOrigin)).toBe(
      false,
    )
  })

  it('denies API and actuator paths from the navigation fallback', () => {
    expect(navigationFallbackDenylist.some((pattern) => pattern.test('/api/v1/orders'))).toBe(true)
    expect(navigationFallbackDenylist.some((pattern) => pattern.test('/actuator/health'))).toBe(
      true,
    )
    expect(navigationFallbackDenylist.some((pattern) => pattern.test('/reports'))).toBe(false)
  })
})
