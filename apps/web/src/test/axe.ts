// Shared accessibility-scan setup: importing this registers vitest's toHaveNoViolations()
// matcher and re-exports an axe() runner, so a test file only needs one import to do
// `expect(await axe(wrapper.element)).toHaveNoViolations()`.
import { expect } from 'vitest'
import { axe as runAxe, toHaveNoViolations } from 'jest-axe'
import type { AxeResults, RunOptions } from 'axe-core'

expect.extend(toHaveNoViolations)

/**
 * These tests each mount a single view or component in isolation, without the <header>/<main>
 * landmarks App.vue wraps it in at runtime, so axe's "region" best-practice rule (all page content
 * must live inside a landmark) is a false positive at this granularity -- disabled here, once,
 * rather than at every call site.
 */
export function axe(html: Element | Document | string, options: RunOptions = {}): Promise<AxeResults> {
  return runAxe(html, { ...options, rules: { region: { enabled: false }, ...options.rules } })
}
