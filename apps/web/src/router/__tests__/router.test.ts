import { describe, expect, it } from 'vitest'
import router from '@/router'

describe('router', () => {
  it('sends any unknown address to the not-found page instead of a blank main region', () => {
    expect(router.resolve('/documents/12/whatever').name).toBe('not-found')
    expect(router.resolve('/nope').name).toBe('not-found')
    expect(router.resolve('/documents/12').name).toBe('workspace')
  })
})
