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
    expect(mobileRules).toMatch(/\.el-dialog__footer[^}]*flex-direction:\s*column/s)
    expect(mobileRules).toContain('overflow-x: auto')
  })

  it('stacks report filters and quick actions without scaling translated labels', () => {
    expect(mobileRules).toMatch(/\.report-filters\s*\{[^}]*flex-direction:\s*column/s)
    expect(mobileRules).toMatch(/\.quick-actions__list\s*\{[^}]*grid-template-columns:\s*1fr/s)
    expect(css).toMatch(/\.quick-action\s*\{[^}]*font-size:\s*13px/s)
  })

  it('keeps PWA notices clear of notches and standalone chrome', () => {
    expect(css).toContain('@media (display-mode: standalone)')
    expect(css).toContain('env(safe-area-inset-top)')
    expect(css).toContain('env(safe-area-inset-bottom)')
    expect(mobileRules).toMatch(/\.pwa-status\s*\{[^}]*top:/s)
  })
})
