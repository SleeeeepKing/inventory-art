import { describe, expect, it } from 'vitest'
import { isAppleMobileDevice, isStandaloneDisplay } from './usePwaInstall'

describe('PWA install environment detection', () => {
  it('detects iPhones, iPads, and touch-capable iPad desktop mode', () => {
    expect(isAppleMobileDevice({ userAgent: 'Mozilla/5.0 (iPhone)' })).toBe(true)
    expect(isAppleMobileDevice({ userAgent: 'Mozilla/5.0 (iPad)' })).toBe(true)
    expect(
      isAppleMobileDevice({
        userAgent: 'Mozilla/5.0 (Macintosh)',
        platform: 'MacIntel',
        maxTouchPoints: 5,
      }),
    ).toBe(true)
    expect(
      isAppleMobileDevice({
        userAgent: 'Mozilla/5.0 (Macintosh)',
        platform: 'MacIntel',
        maxTouchPoints: 0,
      }),
    ).toBe(false)
  })

  it('recognizes both standard and Apple standalone modes', () => {
    expect(isStandaloneDisplay({ mediaMatches: true })).toBe(true)
    expect(isStandaloneDisplay({ navigatorStandalone: true })).toBe(true)
    expect(isStandaloneDisplay({ mediaMatches: false, navigatorStandalone: false })).toBe(false)
  })
})
