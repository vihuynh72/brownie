// jest-axe ships no types of its own, and the community @types/jest-axe package pulls in
// @types/jest (a dependency this project doesn't otherwise need, since it uses vitest). This is
// a minimal local declaration for the two exports the accessibility scan tests actually use.
//
// This file must stay a global script (no top-level import/export) -- declaring a brand-new
// ambient module like this only works from script scope. See vitest-axe-matchers.d.ts for why
// registering the matcher's *type* on vitest's own Assertion interface needs the opposite (a file
// that IS a module), and so has to live in a separate file from this one.
declare module 'jest-axe' {
  import type { AxeResults, RunOptions } from 'axe-core'

  export function axe(html: Element | Document | string, options?: RunOptions): Promise<AxeResults>

  export const toHaveNoViolations: {
    toHaveNoViolations(results: AxeResults): { pass: boolean; message: () => string }
  }
}
