import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listDocuments: vi.fn(), trashDocument: vi.fn() }
})

import { ApiRequestError, listDocuments, trashDocument, type DeletionResponse } from '@/api/client'

async function mountWithRouter(pathOrOptions: string | { attachTo: HTMLElement } = '/') {
  const path = typeof pathOrOptions === 'string' ? pathOrOptions : '/'
  const mountOptions = typeof pathOrOptions === 'string' ? {} : pathOrOptions
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: HomeView },
      { path: '/signin', name: 'signin', component: { template: '<div />' } },
      { path: '/trash', component: { template: '<div />' } },
      { path: '/documents/new', component: { template: '<div />' } },
      { path: '/documents/:id', component: { template: '<div />' } },
    ],
  })
  router.push(path)
  await router.isReady()
  return mount(HomeView, { ...mountOptions, global: { plugins: [router] } })
}

function signedIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = {
    userId: 1,
    issuer: 'x',
    subject: 'y',
    displayName: 'Vi Huynh',
    memberships: [{ workspaceId: 7, role: 'OWNER' }],
  }
}

function summary(id: number, title: string, createdAt: string) {
  return { id, title, templateId: 1, templateVersionId: 1, currentRevisionId: 1, createdAt }
}

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listDocuments).mockReset()
    vi.mocked(trashDocument).mockReset()
  })

  /** A visitor who is not signed in gets the same front door, not a wall: the upload action is still there to follow. */
  /** Reloading cannot help with any of these, so the page must not say it will. */
  it.each([
    [
      new ApiRequestError(404, {
        status: 404, title: 'Not Found', code: 'NOT_FOUND', detail: 'No static resource api/v1/workspaces/7/documents.',
        correlationId: 'c', fields: [], recoveryActions: [],
      }),
      'older than this page',
    ],
    [new ApiRequestError(503, { status: 503, title: 'x', code: 'DATABASE_UNAVAILABLE', detail: 'The database is away.', correlationId: 'c', fields: [], recoveryActions: [] }), 'The database is away.'],
    [new TypeError('Failed to fetch'), 'could not be reached'],
    [new ApiRequestError(500, { status: 500, title: 'x', code: 'INTERNAL_ERROR', detail: 'x', correlationId: 'c', fields: [], recoveryActions: [] }), 'could not load your documents'],
  ])('says what went wrong when the documents cannot be loaded (%#)', async (failure, expected) => {
    signedIn()
    vi.mocked(listDocuments).mockRejectedValue(failure)
    const wrapper = await mountWithRouter()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(wrapper.get('[role="alert"]').text()).toContain(expected)
    expect(wrapper.text()).not.toContain('reloading')
  })

  it('welcomes a signed-out visitor and still offers the upload action', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter()

    expect(wrapper.text()).toContain('Welcome to Brownie!')
    expect(wrapper.find('a[href="/documents/new"]').text()).toContain('Upload your documents')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(listDocuments).not.toHaveBeenCalled()
  })

  /** The API sends a refused sign-in back here with only the provider's error code; the page must say so rather than look like a fresh visit. */
  it('explains a failed sign-in, with its reason, and offers to try again', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter('/?signin=failed&reason=access_denied')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Sign-in did not complete')
    expect(alert.text()).toContain('access_denied')
    expect(alert.find('a[href="/signin"]').text()).toBe('Try again')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  /**
   * The one refusal that must not say "try again": an account that was not
   * invited will be refused every time, and a link inviting the person to
   * repeat it would send them round a loop and tell them something untrue.
   */
  it('tells someone who was not invited that trying again will not help', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter('/?signin=failed&reason=not_invited')

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('invited people only')
    expect(alert.text()).not.toContain('not_invited')
    expect(alert.find('a[href="/signin"]').exists()).toBe(false)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('greets a signed-in person by their first name only', async () => {
    vi.mocked(listDocuments).mockResolvedValue([])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe("What's on your mind today, Vi?")
  })

  it('groups recent documents by the day they were made, newest first, with the time beside each', async () => {
    const today = new Date()
    const earlier = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000)
    vi.mocked(listDocuments).mockResolvedValue([
      summary(1, 'Older Minutes', earlier.toISOString()),
      summary(2, 'March Minutes', today.toISOString()),
    ])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(listDocuments).toHaveBeenCalledWith(7)
    const headings = wrapper.findAll('h3').map((h) => h.text())
    expect(headings[0]).toBe('Today')
    expect(headings).toHaveLength(2)

    const rows = wrapper.findAll('.home__row')
    expect(rows[0]!.text()).toContain('March Minutes')
    expect(rows[0]!.attributes('href')).toBe('/documents/2')
    expect(rows[1]!.text()).toContain('Older Minutes')
    expect(rows[0]!.text()).toContain(new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(today))
  })

  it('shows an empty-state prompt when there are no documents', async () => {
    vi.mocked(listDocuments).mockResolvedValue([])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.text()).toContain("don't have any documents yet")
  })

  /** Someone who just moved their last document to the trash has had documents; "not yet" would tell them it is gone. */
  it('says the list is empty and where the trash bin is once the last document has been moved there', async () => {
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'March Minutes', '2026-03-01T10:00:00Z')])
    vi.mocked(trashDocument).mockResolvedValue(trashedEntry(40, 1, 'March Minutes'))
    signedIn()
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()

    await trashButton(wrapper, 'March Minutes').trigger('click')
    await flushPromises()

    const empty = wrapper.findAll('.field-hint').find((hint) => hint.text().startsWith('There are no documents here'))
    expect(empty).toBeDefined()
    expect(empty!.text().replace(/\s+/g, ' ')).toBe(
      'There are no documents here now. Anything moved to the trash can be restored from the trash bin.',
    )
    expect(empty!.get('a').attributes('href')).toBe('/trash')
    expect(wrapper.text()).not.toContain("don't have any documents yet")
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  /**
   * Trashing is reversible, so it asks nothing; what it owes the person is
   * an immediate, truthful list and a way back. The row's own button leaves
   * with the row, so focus has to land on the sentence that says what
   * happened, or a keyboard user is dropped at the top of the page.
   */
  it('moves a document to the trash, takes it off the list, and points to where it can be restored', async () => {
    vi.mocked(listDocuments).mockResolvedValue([
      summary(1, 'March Minutes', '2026-03-01T10:00:00Z'),
      summary(2, 'April Minutes', '2026-04-01T10:00:00Z'),
    ])
    vi.mocked(trashDocument).mockResolvedValue(trashedEntry(40, 1, 'March Minutes'))
    signedIn()
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()

    const button = wrapper.findAll('button').find((candidate) => candidate.text() === 'Move March Minutes to the trash')
    expect(button).toBeDefined()
    await button!.trigger('click')
    await flushPromises()

    expect(trashDocument).toHaveBeenCalledWith(7, 1)
    expect(wrapper.findAll('.home__row-title').map((title) => title.text())).toEqual(['April Minutes'])
    const notice = wrapper.get('.home__notice')
    expect(notice.text()).toContain('Moved "March Minutes" to the trash.')
    expect(notice.get('a').attributes('href')).toBe('/trash')
    expect(document.activeElement).toBe(notice.element)
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  it('keeps the document on the list and says so when it could not be moved to the trash', async () => {
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'March Minutes', '2026-03-01T10:00:00Z')])
    vi.mocked(trashDocument).mockRejectedValue(new ApiRequestError(503, undefined))
    signedIn()
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()

    await trashButton(wrapper, 'March Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.home__row-title').map((title) => title.text())).toEqual(['March Minutes'])
    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Could not move "March Minutes" to the trash. Brownie is briefly unavailable. Try again in a minute.',
    )
    expect(wrapper.find('.home__notice').exists()).toBe(false)
    // Waiting can help here, so focus goes back to the button that asked.
    expect(document.activeElement).toBe(trashButton(wrapper, 'March Minutes').element)
    wrapper.unmount()
  })

  it.each([
    [
      'a 503 with an explanation from the server',
      new ApiRequestError(503, problem(503, 'The database is away. Try again in a minute.')),
      'Could not move "March Minutes" to the trash. The database is away. Try again in a minute.',
    ],
    [
      'a 429 with an explanation from the server',
      new ApiRequestError(429, problem(429, 'Wait 12 seconds, then try again.')),
      'Could not move "March Minutes" to the trash. Wait 12 seconds, then try again.',
    ],
    ['a 401', new ApiRequestError(401, undefined), 'Could not move "March Minutes" to the trash. Your session has ended. Sign in again to carry on.'],
    [
      'a network failure',
      new TypeError('Failed to fetch'),
      'Could not move "March Minutes" to the trash. Brownie could not be reached. Check your connection, then try again.',
    ],
    // Not Brownie's own answer, so nothing says the document is gone.
    ['a 404 with no explanation', new ApiRequestError(404, undefined), 'Could not move "March Minutes" to the trash. Try again.'],
  ])('keeps the document and says what happened when moving it to the trash fails because of %s', async (_case, error, sentence) => {
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'March Minutes', '2026-03-01T10:00:00Z')])
    vi.mocked(trashDocument).mockRejectedValue(error)
    signedIn()
    const wrapper = await mountWithRouter()
    await flushPromises()

    await trashButton(wrapper, 'March Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.home__row-title').map((title) => title.text())).toEqual(['March Minutes'])
    expect(wrapper.get('[role="alert"]').text()).toBe(sentence)
  })

  /**
   * A server older than this page answers 404 for the route itself. That is
   * not "already deleted": the document is untouched, so the row stays and
   * the sentence says nothing was changed and why trying again will not help.
   */
  it('keeps the document when the server cannot move documents to the trash at all, and says nothing was changed', async () => {
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'March Minutes', '2026-03-01T10:00:00Z')])
    vi.mocked(trashDocument).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/deletions.', 'NOT_FOUND')),
    )
    signedIn()
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()

    await trashButton(wrapper, 'March Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.home__row-title').map((title) => title.text())).toEqual(['March Minutes'])
    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toBe(
      'This Brownie server cannot move documents to the trash yet, so nothing was changed and "March Minutes" is still here. The server needs to be updated first.',
    )
    expect(alert.text()).not.toContain('already deleted')
    expect(wrapper.find('.home__notice').exists()).toBe(false)
    expect(document.activeElement).toBe(alert.element)
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  /** Deleted for good from another tab: no retry can succeed, so the row goes and the sentence says why instead of "try again". */
  it('takes a document that no longer exists off the list instead of offering to try again', async () => {
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'March Minutes', '2026-03-01T10:00:00Z')])
    vi.mocked(trashDocument).mockRejectedValue(new ApiRequestError(404, problem(404, 'Document 1 not found.', 'NOT_FOUND')))
    signedIn()
    const wrapper = await mountWithRouter()
    await flushPromises()

    await trashButton(wrapper, 'March Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.home__row-title')).toHaveLength(0)
    expect(wrapper.get('[role="alert"]').text()).toBe('"March Minutes" was already deleted, so it was taken off this list.')
    // It had documents a moment ago, so the empty list must not read as though there never were any.
    expect(wrapper.text()).toContain('There are no documents here now.')
    expect(wrapper.text()).not.toContain("don't have any documents yet")
  })

  it('has no automatically-detectable accessibility violations with documents listed', async () => {
    vi.mocked(listDocuments).mockResolvedValue([
      summary(1, 'March Minutes', '2026-03-01T10:00:00Z'),
      summary(2, 'April Minutes', '2026-04-01T10:00:00Z'),
    ])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})

function trashedEntry(id: number, targetId: number, title: string): DeletionResponse {
  return {
    id,
    scope: 'DOCUMENT',
    targetId,
    state: 'TRASHED',
    title,
    requestedAt: '2026-09-18T10:00:00Z',
    purgeAfter: '2026-10-18T10:00:00Z',
    restoredAt: null,
    purgedAt: null,
    verifiedAt: null,
    pendingObjectCount: 0,
  }
}

function problem(status: number, detail: string, code = 'X') {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [] }
}

function trashButton(wrapper: Awaited<ReturnType<typeof mountWithRouter>>, title: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text() === `Move ${title} to the trash`)
  if (!found) {
    throw new Error(`No button to move "${title}" to the trash`)
  }
  return found
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}
