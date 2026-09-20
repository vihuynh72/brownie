import { describe, expect, it, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { ApiRequestError } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, getCurrentIdentity: vi.fn(), logout: vi.fn() }
})

import { getCurrentIdentity, logout } from '@/api/client'

describe('session store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getCurrentIdentity).mockReset()
    vi.mocked(logout).mockReset()
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
    expect(store.lastError).toBeNull()
  })

  /**
   * A real browser finding: with /me answering 503, the old store rethrew, the router guard's
   * navigation aborted, and every page sat on its loading message forever with nothing to click.
   * A failure to load identity is a state the UI must be able to render and recover from, not an
   * exception for the framework to swallow.
   */
  it('records a genuine server error as a recoverable error state instead of throwing', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValue(new ApiRequestError(503, { detail: 'The database is away.' } as never))

    const store = useSessionStore()
    await expect(store.loadIdentity()).resolves.toBeUndefined()

    expect(store.status).toBe('error')
    expect(store.identity).toBeNull()
    // The server's own explanation, which says what is wrong; not a status number.
    expect(store.lastError).toBe('The database is away.')
  })

  it('records an error it has no better words for plainly, and says to try again', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValue(new ApiRequestError(500, { detail: 'Unexpected.' } as never))

    const store = useSessionStore()
    await store.loadIdentity()

    expect(store.lastError).toBe('Brownie could not confirm your sign-in. Try again in a moment.')
  })

  /**
   * The app shell asks for the identity as it first renders, and the router guard asks again before the first page
   * mounts. The second must wait for the first, not take "still loading" for an answer.
   */
  it('makes one request however many ask while it is under way, and everyone waits for its answer', async () => {
    let answer: (value: never) => void = () => {}
    vi.mocked(getCurrentIdentity).mockReturnValue(new Promise((resolve) => (answer = resolve as never)))
    const store = useSessionStore()

    const first = store.loadIdentity()
    const second = store.loadIdentity()
    expect(store.status).toBe('loading')
    answer({ userId: 1, issuer: 'i', subject: 's', memberships: [{ workspaceId: 42, role: 'OWNER' }] } as never)
    await Promise.all([first, second])

    expect(getCurrentIdentity).toHaveBeenCalledTimes(1)
    expect(store.status).toBe('authenticated')
  })

  it('becomes signed out when some other request finds the session has ended', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue({ userId: 1, issuer: 'i', subject: 's', memberships: [{ workspaceId: 42, role: 'OWNER' }] })
    const store = useSessionStore()
    await store.loadIdentity()

    store.markSignedOut()

    expect(store.status).toBe('anonymous')
    expect(store.identity).toBeNull()
  })

  it('records a network failure the same way, then recovers on a later successful retry', async () => {
    vi.mocked(getCurrentIdentity).mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const store = useSessionStore()
    await store.loadIdentity()
    expect(store.status).toBe('error')
    expect(store.lastError).toContain('could not be reached')

    vi.mocked(getCurrentIdentity).mockResolvedValueOnce({
      userId: 1,
      issuer: 'https://issuer',
      subject: 'subject',
      memberships: [{ workspaceId: 42, role: 'OWNER' }],
    })
    await store.loadIdentity()
    expect(store.status).toBe('authenticated')
    expect(store.lastError).toBeNull()
  })

  it('signs out by ending the server session first, then reports where to navigate', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue({
      userId: 1,
      issuer: 'https://issuer',
      subject: 'subject',
      memberships: [{ workspaceId: 42, role: 'OWNER' }],
    })
    vi.mocked(logout).mockResolvedValue({ redirectUrl: 'https://idp.example/logout?post_logout_redirect_uri=http://localhost' })
    const store = useSessionStore()
    await store.loadIdentity()

    const redirectUrl = await store.signOut()

    expect(logout).toHaveBeenCalledTimes(1)
    expect(redirectUrl).toContain('https://idp.example/logout')
    expect(store.status).toBe('anonymous')
    expect(store.identity).toBeNull()
  })

  it('stays signed in if the logout request itself fails', async () => {
    vi.mocked(getCurrentIdentity).mockResolvedValue({
      userId: 1,
      issuer: 'https://issuer',
      subject: 'subject',
      memberships: [{ workspaceId: 42, role: 'OWNER' }],
    })
    vi.mocked(logout).mockRejectedValue(new ApiRequestError(403, undefined))
    const store = useSessionStore()
    await store.loadIdentity()

    await expect(store.signOut()).rejects.toBeInstanceOf(ApiRequestError)

    expect(store.status).toBe('authenticated')
    expect(store.identity).not.toBeNull()
  })
})
