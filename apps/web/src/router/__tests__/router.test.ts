import { describe, expect, it, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from '@/router'
import { useSessionStore } from '@/stores/session'
import { ApiRequestError, getCurrentIdentity } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getCurrentIdentity: vi.fn(),
    listDocuments: vi.fn().mockResolvedValue([]),
    listTemplates: vi.fn().mockResolvedValue([]),
  }
})

const IDENTITY = {
  userId: 1,
  issuer: 'https://issuer',
  subject: 'subject',
  memberships: [{ workspaceId: 7, role: 'OWNER' }],
}

describe('router', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    useSessionStore().status = 'anonymous'
    await router.replace('/')
  })

  it('sends any unknown address to the not-found page instead of a blank main region', () => {
    expect(router.resolve('/documents/12/whatever').name).toBe('not-found')
    expect(router.resolve('/nope').name).toBe('not-found')
    expect(router.resolve('/documents/12').name).toBe('workspace')
  })

  /**
   * Every address that reads or writes workspace data is behind a session,
   * so following one while signed out lands on the sign-in page rather
   * than on a screen whose first request comes back 401.
   */
  it.each(['/documents/new', '/documents/12', '/templates/new', '/trash', '/your-data', '/chat', '/connections'])(
    'sends a signed-out visitor from %s to the sign-in page, carrying where they were going',
    async (path) => {
      useSessionStore().status = 'anonymous'

      await router.push(path)

      expect(router.currentRoute.value.name).toBe('signin')
      expect(router.currentRoute.value.query.next).toBe(path)
    },
  )

  it('leaves the home page and the sign-in page open to a signed-out visitor', async () => {
    useSessionStore().status = 'anonymous'

    await router.push('/signin')
    expect(router.currentRoute.value.name).toBe('signin')

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('home')
  })

  /**
   * What happens on a real page load: the app shell has already started asking who is signed in by the time the
   * guard runs, so the guard sees "loading", not "unknown". It has to wait for that answer, not go ahead.
   */
  it('waits for an identity request already under way, and then sends a signed-out visitor to sign in', async () => {
    const session = useSessionStore()
    session.status = 'unknown'
    let answer: (error: unknown) => void = () => {}
    vi.mocked(getCurrentIdentity).mockReturnValue(new Promise((_, reject) => (answer = reject)))
    const underWay = session.loadIdentity()
    expect(session.status).toBe('loading')

    const navigation = router.push('/your-data')
    // Let the router get as far as it will without the answer, as it does on a real page load, where the network is
    // slower than the router.
    await new Promise((resolve) => setTimeout(resolve, 0))
    answer(new ApiRequestError(401, undefined))
    await underWay
    await navigation

    expect(router.currentRoute.value.name).toBe('signin')
    expect(router.currentRoute.value.query.next).toBe('/your-data')
  })

  /** A failed identity request is not the same as being signed out; the shell shows that failure with a retry. */
  it('does not redirect when identity could not be loaded at all', async () => {
    const session = useSessionStore()
    session.status = 'error'
    session.lastError = 'Brownie could not be reached.'

    await router.push('/trash')

    expect(router.currentRoute.value.name).toBe('trash')
  })

  it('does not strand a signed-in person on the sign-in page, and honours where they were going', async () => {
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = IDENTITY

    await router.push('/signin?next=%2Ftrash')
    expect(router.currentRoute.value.path).toBe('/trash')

    await router.push('/signin')
    expect(router.currentRoute.value.name).toBe('home')
  })
})
