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
 *
 * When a page holds an open dialog, axe asks the browser which element sits at a point, and jsdom
 * has no `elementFromPoint`: every rule then fails with an error, axe files the errors under
 * "incomplete", and a scan with no violations passes without having checked anything. So the
 * scan runs with a stand-in that answers "nothing there" (what jsdom, which lays nothing out,
 * would truthfully say), and a rule that errored anyway fails the scan instead of passing silently.
 */
export async function axe(html: Element | Document | string, options: RunOptions = {}): Promise<AxeResults> {
  const missing = typeof document !== 'undefined' && typeof document.elementFromPoint !== 'function'
  if (missing) Object.defineProperty(document, 'elementFromPoint', { value: () => null, configurable: true })
  try {
    const results = await runAxe(html, { ...options, rules: { region: { enabled: false }, ...options.rules } })
    const errored = results.incomplete.filter((result) => result.error !== undefined).map((result) => result.id)
    if (errored.length > 0) throw new Error(`The accessibility scan could not run these rules: ${errored.join(', ')}`)
    return results
  } finally {
    if (missing) Reflect.deleteProperty(document, 'elementFromPoint')
  }
}
