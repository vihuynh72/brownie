import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { ApiRequestError } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, getCurrentIdentity: vi.fn() }
})

import { getCurrentIdentity } from '@/api/client'

describe('session store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getCurrentIdentity).mockReset()
  })

  it('stores the identity and exposes the first membership as the personal workspace', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue({
      userId: 1,
      issuer: 'https://issuer',
      subject: 'subject',
      memberships: [{ workspaceId: 42, role: 'OWNER' }],
    })

    const store = useSessionStore()
    await store.loadIdentity()

    expect(store.status).toBe('authenticated')
    expect(store.personalWorkspaceId).toBe(42)
  })

  it('treats a 401 as a normal signed-out state, not an error', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValue(new ApiRequestError(401, undefined))

    const store = useSessionStore()
    await store.loadIdentity()

    expect(store.status).toBe('anonymous')
    expect(store.identity).toBeNull()
  })

  it('rethrows a genuine server error instead of treating it as signed-out', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValue(new ApiRequestError(500, undefined))

    const store = useSessionStore()
    await expect(store.loadIdentity()).rejects.toThrow()
  })
})
