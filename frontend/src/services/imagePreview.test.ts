import { describe, expect, it } from 'vitest'
import { fitPreview } from './imagePreview'

describe('fitPreview', () => {
  it('limits landscape and portrait previews to 480 pixels', () => {
    expect(fitPreview(2400, 1200)).toEqual({ width: 480, height: 240 })
    expect(fitPreview(1000, 2000)).toEqual({ width: 240, height: 480 })
  })

  it('does not upscale small images', () => {
    expect(fitPreview(120, 80)).toEqual({ width: 120, height: 80 })
  })
})
