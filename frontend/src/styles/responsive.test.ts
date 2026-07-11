import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/styles/main.css', 'utf8')
const mobileRules = css.slice(css.indexOf('@media (max-width: 768px)'))

describe('mobile-primary layout contract', () => {
  it('keeps mobile navigation and controls touch friendly', () => {
    expect(mobileRules).toContain('.side-nav')
    expect(mobileRules).toMatch(/\.mobile-menu\s*\{[^}]*width:\s*44px[^}]*height:\s*44px/s)
    expect(mobileRules).toContain('min-height: 44px')
  })

  it('keeps dialogs, actions, and data tables inside a phone viewport', () => {
    expect(mobileRules).toContain('max-height: calc(100dvh - 20px)')
    expect(mobileRules).toMatch(/\.wizard-actions[^}]*flex-direction:\s*column/s)
    expect(mobileRules).toContain('overflow-x: auto')
  })
})
