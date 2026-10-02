import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import TrashView from '@/views/TrashView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    listDeletions: vi.fn(),
    restoreDeletion: vi.fn(),
    purgeDeletion: vi.fn(),
    getCapabilities: vi.fn(),
    listTrashedTemplates: vi.fn(),
    restoreTemplate: vi.fn(),
    listTemplates: vi.fn(),
  }
})

import {
  ApiRequestError,
  getCapabilities,
  listDeletions,
  listTemplates,
  listTrashedTemplates,
  purgeDeletion,
  restoreDeletion,
  restoreTemplate,
  type DeletionResponse,
  type TemplateResponse,
} from '@/api/client'
import { useTemplatesStore } from '@/stores/templates'
import { resetCapabilitiesCache } from '@/capabilities'

const stub = { template: '<div />' }

async function mountTrash() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: stub },
      { path: '/trash', component: TrashView },
      { path: '/signin', component: stub },
      { path: '/documents/:id', component: stub },
    ],
  })
  router.push('/trash')
  await router.isReady()
  return mount(TrashView, { attachTo: document.body, global: { plugins: [router] } })
}

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

const DAY = 24 * 60 * 60 * 1000
// Relative to now, because a row reads differently once its day has passed.
const MOVED_AT = new Date(Date.now() - DAY).toISOString()
const PURGE_AFTER = new Date(Date.now() + 29 * DAY).toISOString()

function problem(status: number, detail: string, code = 'X') {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [] }
}

function entry(overrides: Partial<DeletionResponse>): DeletionResponse {
  return {
    id: 40,
    scope: 'DOCUMENT',
    targetId: 12,
    state: 'TRASHED',
    title: 'March Minutes',
    requestedAt: MOVED_AT,
    purgeAfter: PURGE_AFTER,
    restoredAt: null,
    purgedAt: null,
    verifiedAt: null,
    pendingObjectCount: 0,
    ...overrides,
  }
}

function buttonNamed(wrapper: Awaited<ReturnType<typeof mountTrash>>, name: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text().replace(/\s+/g, ' ').trim() === name)
  if (!found) {
    throw new Error(`No button named "${name}" among: ${wrapper.findAll('button').map((b) => b.text()).join(' | ')}`)
  }
  return found
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

const CAPABILITIES = {
  maxUploadBytes: 10485760,
  uploadMediaTypes: [],
  assistSourceMediaTypes: [],
  templateMediaTypes: [],
  trashRetentionDays: 30,
}

/** The paragraph under the heading that says how the trash bin works, as one line of text. */
function explanation(wrapper: Awaited<ReturnType<typeof mountTrash>>): string {
  const paragraph = wrapper.findAll('p').find((candidate) => candidate.text().startsWith('Documents you move here'))
  if (!paragraph) {
    throw new Error(`No explanation among: ${wrapper.text()}`)
  }
  return paragraph.text().replace(/\s+/g, ' ').trim()
}

describe('TrashView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    vi.mocked(listDeletions).mockReset()
    vi.mocked(restoreDeletion).mockReset()
    vi.mocked(purgeDeletion).mockReset()
    vi.mocked(getCapabilities).mockReset()
    vi.mocked(getCapabilities).mockResolvedValue(CAPABILITIES)
    vi.mocked(listTrashedTemplates).mockReset().mockResolvedValue([])
    vi.mocked(restoreTemplate).mockReset()
    vi.mocked(listTemplates).mockReset().mockResolvedValue([])
    signIn()
  })

  /** The number of days comes from the server that enforces it, so the page can never promise a different period than the one applied. */
  it('says the bin is empty and how long anything moved here would stay', async () => {
    vi.mocked(listDeletions).mockResolvedValue([])
    const wrapper = await mountTrash()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Your trash bin is empty')
    expect(explanation(wrapper)).toBe(
      'Documents you move here can be restored exactly as they were for 30 days. After that, or when you delete one ' +
        'yourself, it is deleted for good together with its files and any source file that nothing else uses.',
    )
    expect(wrapper.find('ul').exists()).toBe(false)
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  it('says "a day", not "1 days", when things stay in the trash for one day', async () => {
    vi.mocked(getCapabilities).mockResolvedValue({ ...CAPABILITIES, trashRetentionDays: 1 })
    vi.mocked(listDeletions).mockResolvedValue([])
    const wrapper = await mountTrash()
    await flushPromises()

    expect(explanation(wrapper)).toContain('exactly as they were for a day. After that,')
    wrapper.unmount()
  })

  /**
   * An older server's capabilities answer has no retention period at all.
   * The sentence must still be whole: no "for  days", and no "After that"
   * pointing back at a period it never named.
   */
  it('explains the trash bin without a period when the server does not send one', async () => {
    const older: Partial<typeof CAPABILITIES> = { ...CAPABILITIES }
    delete older.trashRetentionDays
    vi.mocked(getCapabilities).mockResolvedValue(older as typeof CAPABILITIES)
    vi.mocked(listDeletions).mockResolvedValue([])
    const wrapper = await mountTrash()
    await flushPromises()

    expect(explanation(wrapper)).toBe(
      'Documents you move here can be restored exactly as they were at least until the date shown beside each one. ' +
        'Soon after that date it is deleted for good together with its files and any source file that nothing else ' +
        'uses; deleting one yourself does that straight away.',
    )
    expect(wrapper.text()).not.toMatch(/for\s+days|undefined|After that/)
    wrapper.unmount()
  })

  it('explains the trash bin without a period when the capabilities request fails', async () => {
    vi.mocked(getCapabilities).mockRejectedValue(new TypeError('Failed to fetch'))
    vi.mocked(listDeletions).mockResolvedValue([])
    const wrapper = await mountTrash()
    await flushPromises()

    expect(explanation(wrapper)).toContain('until the date shown beside each one')
    expect(wrapper.text()).not.toMatch(/for\s+days|After that/)
    // The list itself still loaded: a missing period is not a failure of the page.
    expect(wrapper.get('h1').text()).toBe('Your trash bin is empty')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })

  /** Only what can still come back belongs in the list: restored and deleted entries are history, not contents. */
  it('lists what is in the trash with the day each one is deleted for good, and nothing that already left it', async () => {
    vi.mocked(listDeletions).mockResolvedValue([
      entry({ id: 40, title: 'March Minutes' }),
      entry({ id: 41, targetId: 13, state: 'RESTORED', title: null, restoredAt: '2026-09-18T11:00:00Z', purgeAfter: null }),
      entry({ id: 42, targetId: 14, state: 'PURGED', title: null, purgedAt: '2026-09-18T11:00:00Z', purgeAfter: null, pendingObjectCount: 3 }),
    ])
    const wrapper = await mountTrash()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Trash bin')
    expect(wrapper.findAll('.trash__name').map((name) => name.text())).toEqual(['March Minutes'])
    const purgeDay = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(PURGE_AFTER))
    expect(wrapper.get('.trash__item').text()).toContain(`Deleted for good on ${purgeDay}.`)
    expect(wrapper.text()).toContain('The files of 1 deleted document are still being removed in the background.')
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  /**
   * The worker deletes an entry some time after its day, not on the dot, and
   * the entry can be restored until it does. A past date read as "Deleted for
   * good on" beside a Restore button that works says two false things.
   */
  it('says a row whose day has passed is due to be deleted, and can still be restored until then', async () => {
    const ranOut = new Date(Date.now() - 2 * DAY).toISOString()
    vi.mocked(listDeletions).mockResolvedValue([entry({ requestedAt: new Date(Date.now() - 32 * DAY).toISOString(), purgeAfter: ranOut })])
    const wrapper = await mountTrash()
    await flushPromises()

    const day = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(ranOut))
    const row = wrapper.get('.trash__item').text()
    expect(row).toContain(
      `Its time in the trash ran out on ${day}, so it is due to be deleted for good; until that happens, it can still be restored.`,
    )
    expect(row).not.toContain('Deleted for good on')
    expect(buttonNamed(wrapper, 'Restore March Minutes').attributes('disabled')).toBeUndefined()
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  it('leaves the deletion date out of a row the server sent without one', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({ purgeAfter: undefined })])
    const wrapper = await mountTrash()
    await flushPromises()

    const day = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(MOVED_AT))
    expect(wrapper.get('.trash__item .field-hint').text()).toBe(`Moved here ${day}.`)
    wrapper.unmount()
  })

  it('restores a document in one step and offers to open it', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    vi.mocked(restoreDeletion).mockResolvedValue(entry({ state: 'RESTORED', title: null, restoredAt: '2026-09-18T12:00:00Z', purgeAfter: null }))
    const wrapper = await mountTrash()
    await flushPromises()

    await buttonNamed(wrapper, 'Restore March Minutes').trigger('click')
    await flushPromises()

    expect(restoreDeletion).toHaveBeenCalledWith(7, 40)
    expect(wrapper.find('.trash__item').exists()).toBe(false)
    const notice = wrapper.get('.trash__notice')
    expect(notice.text()).toContain('Restored "March Minutes".')
    expect(notice.get('a').attributes('href')).toBe('/documents/12')
    expect(document.activeElement).toBe(notice.element)
    wrapper.unmount()
  })

  /**
   * The only action in the product that cannot be undone. It must take two
   * deliberate steps, name the document in the question, put focus on the
   * answer, and give focus back to where the person was if they back out.
   */
  it('asks once, by name, before deleting forever, and backing out changes nothing', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    vi.mocked(purgeDeletion).mockResolvedValue(entry({ state: 'PURGED', title: null, purgedAt: '2026-09-18T12:00:00Z', purgeAfter: null, pendingObjectCount: 4 }))
    const wrapper = await mountTrash()
    await flushPromises()

    await buttonNamed(wrapper, 'Delete forever March Minutes').trigger('click')
    await flushPromises()
    expect(purgeDeletion).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Delete "March Minutes" forever? This cannot be undone.')
    expect(document.activeElement).toBe(buttonNamed(wrapper, 'Yes, delete forever').element)
    expect(await axe(wrapper.element)).toHaveNoViolations()

    await buttonNamed(wrapper, 'Keep it').trigger('click')
    await flushPromises()
    expect(purgeDeletion).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(buttonNamed(wrapper, 'Delete forever March Minutes').element)

    await buttonNamed(wrapper, 'Delete forever March Minutes').trigger('click')
    await flushPromises()
    await buttonNamed(wrapper, 'Yes, delete forever').trigger('click')
    await flushPromises()

    expect(purgeDeletion).toHaveBeenCalledWith(7, 40)
    expect(wrapper.find('.trash__item').exists()).toBe(false)
    expect(wrapper.get('.trash__notice').text()).toContain('Deleted "March Minutes" for good.')
    expect(wrapper.text()).toContain('The files of 1 deleted document are still being removed in the background.')
    wrapper.unmount()
  })

  /** Nothing was deleted and nothing is broken: the honest message is "wait a moment", not "something went wrong". */
  it('explains that a run is still stopping, keeps the document in the trash, and lets the person try again', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    vi.mocked(purgeDeletion).mockRejectedValue(
      new ApiRequestError(409, {
        status: 409,
        title: 'Conflict',
        code: 'DELETION_WAITING_FOR_RUNNING_WORK',
        correlationId: 'c',
        fields: [],
        recoveryActions: [],
      }),
    )
    const wrapper = await mountTrash()
    await flushPromises()

    await buttonNamed(wrapper, 'Delete forever March Minutes').trigger('click')
    await flushPromises()
    await buttonNamed(wrapper, 'Yes, delete forever').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'A run for "March Minutes" is still stopping, so nothing was deleted. Try again in a moment.',
    )
    expect(wrapper.findAll('.trash__name').map((name) => name.text())).toEqual(['March Minutes'])
    expect(buttonNamed(wrapper, 'Yes, delete forever').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  /**
   * A page left open goes stale: the same entry can be restored in another
   * tab, or deleted for good when its time runs out. "Try again" would be a
   * lie in both cases, because no retry can ever succeed; the row has to go
   * and the sentence has to say what actually happened.
   */
  it('takes a row off the list when deleting finds it was restored elsewhere, and says nothing was deleted', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    vi.mocked(purgeDeletion).mockRejectedValue(
      new ApiRequestError(409, {
        status: 409,
        title: 'Conflict',
        code: 'DELETION_NOT_OPEN',
        correlationId: 'c',
        fields: [],
        recoveryActions: [],
      }),
    )
    const wrapper = await mountTrash()
    await flushPromises()

    await buttonNamed(wrapper, 'Delete forever March Minutes').trigger('click')
    await flushPromises()
    await buttonNamed(wrapper, 'Yes, delete forever').trigger('click')
    await flushPromises()

    expect(wrapper.find('.trash__item').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    const notice = wrapper.get('.trash__notice')
    expect(notice.text()).toContain('"March Minutes" was restored from the trash, so nothing was deleted.')
    expect(notice.get('a').attributes('href')).toBe('/documents/12')
    expect(document.activeElement).toBe(notice.element)
    wrapper.unmount()
  })

  it('takes a row off the list when restoring finds it was already deleted for good', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    vi.mocked(restoreDeletion).mockRejectedValue(
      new ApiRequestError(409, {
        status: 409,
        title: 'Conflict',
        code: 'DELETION_NOT_OPEN',
        correlationId: 'c',
        fields: [],
        recoveryActions: [],
      }),
    )
    const wrapper = await mountTrash()
    await flushPromises()

    await buttonNamed(wrapper, 'Restore March Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.find('.trash__item').exists()).toBe(false)
    const notice = wrapper.get('.trash__notice')
    expect(notice.text()).toContain('"March Minutes" was already deleted for good, so it cannot be restored.')
    expect(notice.find('a').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the row and offers to try again when restoring fails for a reason that may pass', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    vi.mocked(restoreDeletion).mockRejectedValue(new ApiRequestError(503, undefined))
    const wrapper = await mountTrash()
    await flushPromises()

    await buttonNamed(wrapper, 'Restore March Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.trash__name').map((name) => name.text())).toEqual(['March Minutes'])
    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Could not restore "March Minutes". Brownie is briefly unavailable. Try again in a minute.',
    )
    wrapper.unmount()
  })

  /** Nor does a 404 that Brownie did not explain: something in front of it answered, and the entry is untouched. */
  it('keeps the row when restoring gets a 404 that Brownie did not send', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    vi.mocked(restoreDeletion).mockRejectedValue(new ApiRequestError(404, undefined))
    const wrapper = await mountTrash()
    await flushPromises()

    await buttonNamed(wrapper, 'Restore March Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.trash__name').map((name) => name.text())).toEqual(['March Minutes'])
    expect(wrapper.text()).not.toContain('already deleted for good')
    wrapper.unmount()
  })

  /** A route the server does not have says nothing about the entry: it must not be reported as deleted for good. */
  it('keeps the row, and says why, when the server has no way to restore at all', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    vi.mocked(restoreDeletion).mockRejectedValue(
      new ApiRequestError(404, {
        status: 404, title: 'Not Found', code: 'NOT_FOUND', detail: 'No static resource api/v1/workspaces/7/deletions/3/restore.',
        correlationId: 'c', fields: [], recoveryActions: [],
      }),
    )
    const wrapper = await mountTrash()
    await flushPromises()

    await buttonNamed(wrapper, 'Restore March Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.trash__name').map((name) => name.text())).toEqual(['March Minutes'])
    expect(wrapper.get('[role="alert"]').text()).toContain('older than this page')
    expect(wrapper.text()).not.toContain('already deleted for good')
    wrapper.unmount()
  })

  /** What the owner saw: a server that predates the trash bin. Reloading cannot fix that, so the page must not suggest it. */
  it('says the server is older than this page when it has no trash bin, and not to reload', async () => {
    vi.mocked(listDeletions).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/deletions.', 'NOT_FOUND')),
    )
    const wrapper = await mountTrash()
    await flushPromises()

    const alert = wrapper.get('[role="alert"]').text()
    expect(alert).toContain('older than this page and does not have the trash bin yet')
    expect(alert).toContain('Reloading will not change that')
    expect(wrapper.get('h1').text()).toBe('Trash bin')
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  it.each([
    ['a 503 with an explanation from the server', new ApiRequestError(503, problem(503, 'The database is away. Try again in a minute.')), 'The database is away. Try again in a minute.'],
    ['a 429 with an explanation from the server', new ApiRequestError(429, problem(429, 'Wait 12 seconds, then try again.')), 'Wait 12 seconds, then try again.'],
    ['a 503 with no explanation', new ApiRequestError(503, undefined), 'Brownie is briefly unavailable. Try again in a minute.'],
    ['a 401', new ApiRequestError(401, undefined), 'Your session has ended. Sign in again to carry on.'],
    ['a network failure', new TypeError('Failed to fetch'), 'Brownie could not be reached. Check your connection, then try again.'],
    [
      'a failure only Brownie can explain',
      new ApiRequestError(500, problem(500, 'Unexpected.', 'INTERNAL')),
      'Brownie could not load the trash bin. If this keeps happening, let whoever runs this Brownie know.',
    ],
  ])('says what happened when the trash bin could not be loaded because of %s', async (_case, error, sentence) => {
    vi.mocked(listDeletions).mockRejectedValue(error)
    const wrapper = await mountTrash()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(sentence)
    expect(wrapper.text()).not.toContain('Something went wrong')
    expect(wrapper.text()).not.toMatch(/reloading the page/i)
    expect(wrapper.get('h1').text()).toBe('Trash bin')
    wrapper.unmount()
  })

  it('asks a signed-out visitor to sign in and brings them back here afterwards', async () => {
    const session = useSessionStore()
    session.identity = null
    session.status = 'anonymous'
    const wrapper = await mountTrash()
    await flushPromises()

    expect(wrapper.text()).toContain('Sign in to see your trash bin.')
    expect(wrapper.get('a.button--primary').attributes('href')).toBe('/signin?next=/trash')
    expect(listDeletions).not.toHaveBeenCalled()
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  /** In the app, a 401 from any request moves the session to signed out; the page then has to offer the way back in. */
  it('offers to sign in again when the session ends while the page is open', async () => {
    vi.mocked(listDeletions).mockResolvedValue([entry({})])
    const wrapper = await mountTrash()
    await flushPromises()
    expect(wrapper.findAll('.trash__name')).toHaveLength(1)

    const session = useSessionStore()
    session.identity = null
    session.status = 'anonymous'
    await flushPromises()

    expect(wrapper.find('.trash__item').exists()).toBe(false)
    expect(wrapper.get('a.button--primary').attributes('href')).toBe('/signin?next=/trash')
    wrapper.unmount()
  })
  describe('templates', () => {
    const TRASHED_AT = new Date(Date.now() - DAY).toISOString()

    function trashedTemplate(overrides: Partial<TemplateResponse> = {}): TemplateResponse {
      return {
        id: 5,
        displayName: 'Club minutes',
        status: 'ACTIVE',
        currentActiveVersionId: 50,
        createdAt: '2026-09-01T10:00:00Z',
        trashedAt: TRASHED_AT,
        ...overrides,
      }
    }

    function templatesSection(wrapper: Awaited<ReturnType<typeof mountTrash>>) {
      return wrapper.get('section[aria-labelledby="trash-templates-heading"]')
    }

    /** Documents made from a template depend on it, so nothing ever deletes it for good; the page must not suggest a timer. */
    it('lists templates in the trash, says documents made from them keep working, and offers only to restore them', async () => {
      vi.mocked(listDeletions).mockResolvedValue([])
      vi.mocked(listTrashedTemplates).mockResolvedValue([trashedTemplate(), trashedTemplate({ id: 6, displayName: 'Grant report' })])
      const wrapper = await mountTrash()
      await flushPromises()

      expect(listTrashedTemplates).toHaveBeenCalledWith(7)
      const section = templatesSection(wrapper)
      expect(section.get('h2').text()).toBe('Templates')
      expect(section.text().replace(/\s+/g, ' ')).toContain(
        'Documents made from a template keep working while it is in the trash. A template stays here until you restore it.',
      )
      expect(section.findAll('.trash__name').map((name) => name.text())).toEqual(['Club minutes', 'Grant report'])
      const day = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(TRASHED_AT))
      expect(section.get('.trash__item .field-hint').text()).toBe(`Moved here ${day}.`)
      expect(section.findAll('button').map((button) => button.text().replace(/\s+/g, ' ').trim())).toEqual([
        'Restore Club minutes',
        'Restore Grant report',
      ])
      expect(section.text()).not.toMatch(/delete/i)
      // Something is in the trash, so the page does not call itself empty.
      expect(wrapper.get('h1').text()).toBe('Trash bin')
      expect(await axe(wrapper.element)).toHaveNoViolations()
      wrapper.unmount()
    })

    it('says which part of the trash is empty while the other is not', async () => {
      vi.mocked(listDeletions).mockResolvedValue([entry({})])
      const wrapper = await mountTrash()
      await flushPromises()

      expect(templatesSection(wrapper).text()).toContain('No templates are in the trash.')
      expect(wrapper.text()).not.toContain('No documents are in the trash.')
      wrapper.unmount()

      vi.mocked(listDeletions).mockResolvedValue([])
      vi.mocked(listTrashedTemplates).mockResolvedValue([trashedTemplate()])
      const other = await mountTrash()
      await flushPromises()

      expect(other.text()).toContain('No documents are in the trash.')
      expect(templatesSection(other).text()).not.toContain('No templates are in the trash.')
      other.unmount()
    })

    /** Nothing at all in the trash: the heading says it once, rather than every section saying it again. */
    it('calls the whole bin empty only when neither documents nor templates are in it', async () => {
      vi.mocked(listDeletions).mockResolvedValue([])
      const wrapper = await mountTrash()
      await flushPromises()

      expect(wrapper.get('h1').text()).toBe('Your trash bin is empty')
      expect(wrapper.text()).not.toMatch(/No (documents|templates) are in the trash/)
      expect(templatesSection(wrapper).text()).toContain('A template stays here until you restore it.')
      wrapper.unmount()
    })

    it('restores a template in one step, says so, and puts it back in My Templates', async () => {
      vi.mocked(listDeletions).mockResolvedValue([])
      vi.mocked(listTrashedTemplates).mockResolvedValue([trashedTemplate()])
      vi.mocked(restoreTemplate).mockResolvedValue(trashedTemplate({ trashedAt: null }))
      const restoredList = [trashedTemplate({ trashedAt: null })]
      vi.mocked(listTemplates).mockResolvedValue(restoredList)
      const wrapper = await mountTrash()
      await flushPromises()

      await buttonNamed(wrapper, 'Restore Club minutes').trigger('click')
      await flushPromises()

      expect(restoreTemplate).toHaveBeenCalledWith(7, 5)
      expect(templatesSection(wrapper).find('.trash__item').exists()).toBe(false)
      const notice = wrapper.get('.trash__notice')
      expect(notice.text()).toBe('Restored "Club minutes" to My Templates.')
      expect(document.activeElement).toBe(notice.element)
      // The sidebar reads the same store, so the template is back there without a reload.
      expect(listTemplates).toHaveBeenCalledWith(7)
      expect(useTemplatesStore().usable.map((template) => template.id)).toEqual([5])
      wrapper.unmount()
    })

    /** The sidebar stays beside this page: moving a template to the trash from there must show here without a reload. */
    it('lists a template moved to the trash from the sidebar while the page is open, keeping the list on screen meanwhile', async () => {
      vi.mocked(listDeletions).mockResolvedValue([])
      vi.mocked(listTrashedTemplates).mockResolvedValue([trashedTemplate()])
      const wrapper = await mountTrash()
      await flushPromises()
      expect(templatesSection(wrapper).findAll('.trash__name').map((name) => name.text())).toEqual(['Club minutes'])

      let answer: (templates: TemplateResponse[]) => void = () => {}
      vi.mocked(listTrashedTemplates).mockReturnValue(new Promise((resolve) => (answer = resolve)))
      useTemplatesStore().markTrashed(6)
      await flushPromises()

      expect(listTrashedTemplates).toHaveBeenCalledTimes(2)
      expect(templatesSection(wrapper).text()).not.toContain('Loading the templates in the trash')
      expect(templatesSection(wrapper).findAll('.trash__name').map((name) => name.text())).toEqual(['Club minutes'])

      answer([trashedTemplate(), trashedTemplate({ id: 6, displayName: 'Grant report' })])
      await flushPromises()

      expect(templatesSection(wrapper).findAll('.trash__name').map((name) => name.text())).toEqual([
        'Club minutes',
        'Grant report',
      ])
      // Only the templates are asked for again; the documents in the trash did not change.
      expect(listDeletions).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    })

    /** Two templates moved in quick succession: the answer to the first question can arrive after the answer to the second. */
    it('keeps the newest list when two answers arrive out of order', async () => {
      vi.mocked(listDeletions).mockResolvedValue([])
      const wrapper = await mountTrash()
      await flushPromises()
      const answers: ((templates: TemplateResponse[]) => void)[] = []
      vi.mocked(listTrashedTemplates).mockImplementation(() => new Promise((resolve) => answers.push(resolve)))

      useTemplatesStore().markTrashed(5)
      await flushPromises()
      useTemplatesStore().markTrashed(6)
      await flushPromises()
      expect(answers).toHaveLength(2)

      answers[1]!([trashedTemplate(), trashedTemplate({ id: 6, displayName: 'Grant report' })])
      await flushPromises()
      answers[0]!([trashedTemplate()])
      await flushPromises()

      expect(templatesSection(wrapper).findAll('.trash__name').map((name) => name.text())).toEqual([
        'Club minutes',
        'Grant report',
      ])
      wrapper.unmount()
    })

    it('keeps the template and offers to try again when restoring fails for a reason that may pass', async () => {
      vi.mocked(listDeletions).mockResolvedValue([])
      vi.mocked(listTrashedTemplates).mockResolvedValue([trashedTemplate()])
      vi.mocked(restoreTemplate).mockRejectedValue(new ApiRequestError(503, undefined))
      const wrapper = await mountTrash()
      await flushPromises()

      await buttonNamed(wrapper, 'Restore Club minutes').trigger('click')
      await flushPromises()

      expect(wrapper.get('[role="alert"]').text()).toBe(
        'Could not restore "Club minutes". Brownie is briefly unavailable. Try again in a minute.',
      )
      expect(templatesSection(wrapper).findAll('.trash__name').map((name) => name.text())).toEqual(['Club minutes'])
      expect(document.activeElement).toBe(buttonNamed(wrapper, 'Restore Club minutes').element)
      wrapper.unmount()
    })

    /**
     * What the owner's own server may be: one that predates templates in the
     * trash ignores the question and sends every template it has. None of
     * those is in the trash, and none may be offered as if it were.
     */
    it('says the server is older than this page when its answer does not know about the trash', async () => {
      vi.mocked(listDeletions).mockResolvedValue([])
      const older = trashedTemplate()
      delete older.trashedAt
      vi.mocked(listTrashedTemplates).mockResolvedValue([older])
      const wrapper = await mountTrash()
      await flushPromises()

      const section = templatesSection(wrapper)
      expect(section.get('[role="alert"]').text()).toBe(
        'The Brownie server that answered is older than this page and does not have a Trash Bin for templates yet. ' +
          'Reloading will not change that: the server needs to be updated and restarted.',
      )
      expect(section.find('.trash__item').exists()).toBe(false)
      expect(section.text()).not.toMatch(/not yet|later|for now|coming soon/i)
      expect(wrapper.get('h1').text()).toBe('Trash bin')
      expect(await axe(wrapper.element)).toHaveNoViolations()
      wrapper.unmount()
    })

    it('says the server is older than this page when it has no such route at all', async () => {
      vi.mocked(listDeletions).mockResolvedValue([])
      vi.mocked(listTrashedTemplates).mockRejectedValue(
        new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/templates.', 'NOT_FOUND')),
      )
      const wrapper = await mountTrash()
      await flushPromises()

      const alert = templatesSection(wrapper).get('[role="alert"]').text()
      expect(alert).toContain('older than this page and does not have a Trash Bin for templates yet')
      expect(alert).toContain('Reloading will not change that')
      wrapper.unmount()
    })

    it('says in words when the templates in the trash could not be loaded, and still shows the documents', async () => {
      vi.mocked(listDeletions).mockResolvedValue([entry({})])
      vi.mocked(listTrashedTemplates).mockRejectedValue(new ApiRequestError(500, problem(500, 'Unexpected.', 'INTERNAL')))
      const wrapper = await mountTrash()
      await flushPromises()

      expect(templatesSection(wrapper).get('[role="alert"]').text()).toBe(
        'Brownie could not load the templates in the trash. If this keeps happening, let whoever runs this Brownie know.',
      )
      expect(wrapper.findAll('.trash__name').map((name) => name.text())).toEqual(['March Minutes'])
      wrapper.unmount()
    })
  })
})
