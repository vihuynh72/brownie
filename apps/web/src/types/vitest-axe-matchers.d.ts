// Registers toHaveNoViolations() on vitest's own `expect` type once
// expect.extend(toHaveNoViolations) has run at runtime (see src/test/axe.ts) -- vitest's
// documented pattern for typing a custom matcher.
//
// Unlike jest-axe.d.ts, this file must itself be a module (hence the export below) so that
// `declare module 'vitest'` is treated as an augmentation of vitest's real types rather than a
// wholesale (and destructive) redeclaration of the module.
export {}

interface CustomMatchers<R = unknown> {
  toHaveNoViolations(): R
}

declare module 'vitest' {
  interface Assertion<T = unknown> extends CustomMatchers<T> {}
  interface AsymmetricMatchersContaining extends CustomMatchers {}
}
