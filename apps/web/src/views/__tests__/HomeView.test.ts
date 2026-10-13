import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listDocuments: vi.fn(), trashDocument: vi.fn(), getCapabilities: vi.fn() }
})
vi.mock('@/upload/learnAndStart', async () => {
  const actual = await vi.importActual<typeof import('@/upload/learnAndStart')>('@/upload/learnAndStart')
  return { ...actual, learnFormAndStartDocument: vi.fn() }
})

import { ApiRequestError, getCapabilities, listDocuments, trashDocument, type CapabilitiesResponse, type DeletionResponse } from '@/api/client'
import { learnFormAndStartDocument, type LearnOutcome, type LearnStep, type StepDetail } from '@/upload/learnAndStart'
import { PDF_REFUSAL_SENTENCES, REFUSAL_SENTENCES } from '@/upload/fillableCopyWords'
import { formUploadError, formUploadFinished, formUploadStep } from '@/upload/formUploadState'
import { resetCapabilitiesCache } from '@/capabilities'
import { readDocumentHandoff } from '@/router/handoff'

/** What the server says it can do; `fillSpotNaming` is left out as an older server leaves it out. */
function capabilities(fillSpotNaming?: CapabilitiesResponse['fillSpotNaming']): CapabilitiesResponse {
  return {
    maxUploadBytes: 10 * 1024 * 1024, uploadMediaTypes: [], assistSourceMediaTypes: [], templateMediaTypes: [], trashRetentionDays: 30,
    ...(fillSpotNaming ? { fillSpotNaming } : {}),
  }
}

let router: Router
// Every page mounted here listens to the window's drags until it is unmounted, so a test's drag must find only its own.
const mounted = new Set<VueWrapper>()

async function mountWithRouter(pathOrOptions: string | { attachTo: HTMLElement } = '/') {
  const path = typeof pathOrOptions === 'string' ? pathOrOptions : '/'
  const mountOptions = typeof pathOrOptions === 'string' ? {} : pathOrOptions
  router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: HomeView },
      { path: '/signin', name: 'signin', component: { template: '<div />' } },
      { path: '/trash', component: { template: '<div />' } },
      { path: '/documents/:id', component: { template: '<div />' } },
    ],
  })
  router.push(path)
  await router.isReady()
  const wrapper = mount(HomeView, { ...mountOptions, global: { plugins: [router] } })
  mounted.add(wrapper)
  const unmount = wrapper.unmount.bind(wrapper)
  wrapper.unmount = () => {
    mounted.delete(wrapper)
    unmount()
  }
  return wrapper
}

afterEach(() => {
  for (const wrapper of [...mounted]) wrapper.unmount()
})

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

const timeOf = (date: Date) => new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(date)

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listDocuments).mockReset()
    vi.mocked(trashDocument).mockReset()
    vi.mocked(learnFormAndStartDocument).mockReset()
    vi.mocked(getCapabilities).mockReset().mockResolvedValue(capabilities('RULES'))
    resetCapabilitiesCache()
  })

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
    // Not knowing what is in the list is not the same as there being nothing in it.
    expect(wrapper.find('.home__how').exists()).toBe(false)
    expect(wrapper.find('.home__empty').exists()).toBe(false)
  })

  /** Signed out, the same button is a way to sign in that comes back here, rather than a control that refuses. */
  it('welcomes a signed-out visitor and sends the upload action to sign in, and back here', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter()

    expect(wrapper.get('h1').text()).toBe('Welcome to Brownie!')
    const upload = wrapper.get('a.home__upload')
    expect(upload.text()).toBe('Upload your documents')
    expect(upload.attributes('href')).toBe('/signin?next=/')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(listDocuments).not.toHaveBeenCalled()
    // What the button takes is said to everyone; how large a file may be, and what happens to a form's text, are
    // asked about only once signed in.
    expect(wrapper.get('.home__upload-kinds').text()).toBe('Word, PDF, Pages, OpenDocument or RTF')
    expect(getCapabilities).not.toHaveBeenCalled()
    expect(wrapper.find('.home__how').exists()).toBe(false)
    expect(wrapper.find('.home__recent').exists()).toBe(false)
    expect(await axe(wrapper.element)).toHaveNoViolations()
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

    expect(wrapper.get('h1').text()).toBe('What’s on your mind today, Vi?')
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
    expect(rows[0]!.text()).toContain(timeOf(today))
    // Only what the list really says: a title and a time, with nothing made up beneath them.
    expect(rows[0]!.findAll('span:not(.visually-hidden)').map((part) => part.text()).filter(Boolean)).toEqual([
      'March Minutes',
      timeOf(today),
    ])
  })

  /** The page that used to start a document from a template is gone; the words point at where templates are now. */
  it('says where a first document comes from when there are none, and shows how Brownie works', async () => {
    vi.mocked(listDocuments).mockResolvedValue([])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.get('.home__hint').text()).toBe(
      'There are no documents here. Upload a form above to start one, or choose a template under My Templates.',
    )
    expect(wrapper.find('.home__hint a').exists()).toBe(false)

    const how = wrapper.get('.home__how')
    expect(how.get('h2').text()).toBe('How Brownie works')
    expect(how.findAll('.home__how-card').map((card) => card.get('.home__how-step').text() + ' ' + card.get('h3').text())).toEqual([
      'Step 1 Upload a form',
      'Step 2 Add your notes',
      'Step 3 Check and export',
    ])
    expect(how.text()).toContain('Brownie learns where the values go and keeps the form’s own look.')
    expect(how.text()).toContain('A transcript, notes, a calendar event or a Google Doc. Brownie reads only what you add.')
    expect(how.text()).toContain('You approve every value. Brownie exports exactly the version you approved.')
    // Nothing to continue from yet.
    expect(wrapper.find('.home__resume').exists()).toBe(false)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  /** A returning person would see the cards flash and vanish if they were drawn before the list had answered. */
  it('does not show how Brownie works until the list has said there is nothing in it', async () => {
    vi.mocked(listDocuments).mockReturnValue(new Promise(() => {}))
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.find('.home__how').exists()).toBe(false)
    expect(wrapper.text()).toContain('Loading documents…')
  })

  it('leaves out how Brownie works for someone who has documents', async () => {
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'March Minutes', '2026-03-01T10:00:00Z')])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.find('.home__how').exists()).toBe(false)
    expect(wrapper.find('.home__empty').exists()).toBe(false)
  })

  /** The list says only when each document was made, so that is all "where you left off" can honestly rest on. */
  it('offers the newest document first, says when it was started, and shows no counts the list does not give', async () => {
    const today = new Date()
    vi.mocked(listDocuments).mockResolvedValue([
      summary(1, 'Older Minutes', new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString()),
      summary(2, 'March Minutes', today.toISOString()),
    ])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    const resume = wrapper.get('.home__resume')
    expect(resume.get('h2').text()).toBe('Continue where you left off')
    expect(resume.get('.home__resume-title').text()).toBe('March Minutes')
    expect(resume.get('.home__resume-started').text()).toBe(`Started today at ${timeOf(today)}`)
    const open = resume.get('a')
    expect(open.attributes('href')).toBe('/documents/2')
    // The button says "Open"; the document it opens is part of its name for anyone who hears it out of context.
    expect(open.text()).toBe('Open March Minutes')
    expect(resume.find('.tag').exists()).toBe(false)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('names the day an older newest document was started, not "today"', async () => {
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'Older Minutes', '2020-03-02T12:00:00Z')])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    const started = wrapper.get('.home__resume-started').text()
    expect(started).toMatch(/^Started .+ at .+/)
    expect(started).not.toContain('today')
    expect(started).toContain('Mar 2, 2020')
  })

  it('moves on to the next newest document when the one it offered goes to the trash', async () => {
    vi.mocked(listDocuments).mockResolvedValue([
      summary(1, 'March Minutes', '2026-03-01T10:00:00Z'),
      summary(2, 'April Minutes', '2026-04-01T10:00:00Z'),
    ])
    vi.mocked(trashDocument).mockResolvedValue(trashedEntry(40, 2, 'April Minutes'))
    signedIn()
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()
    expect(wrapper.get('.home__resume-title').text()).toBe('April Minutes')

    await trashButton(wrapper, 'April Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.get('.home__resume-title').text()).toBe('March Minutes')
    expect(wrapper.get('.home__resume a').attributes('href')).toBe('/documents/1')
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

    const empty = wrapper.findAll('.home__hint').find((hint) => hint.text().startsWith('There are no documents here'))
    expect(empty).toBeDefined()
    expect(empty!.text().replace(/\s+/g, ' ')).toBe(
      'There are no documents here now. Anything moved to the trash can be restored from the trash bin.',
    )
    expect(empty!.get('a').attributes('href')).toBe('/trash')
    expect(wrapper.text()).not.toContain('Upload a form above')
    expect(wrapper.find('.home__resume').exists()).toBe(false)
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
      'This Brownie server cannot move documents to the trash, so nothing was changed and "March Minutes" is still here. The server needs to be updated first.',
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
    expect(wrapper.text()).not.toContain('Upload a form above')
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

describe('HomeView: uploading a form', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listDocuments).mockReset().mockResolvedValue([])
    vi.mocked(learnFormAndStartDocument).mockReset()
    vi.mocked(getCapabilities).mockReset().mockResolvedValue(capabilities('RULES'))
    resetCapabilitiesCache()
    formUploadStep.value = null
    formUploadError.value = null
    formUploadFinished.value = null
    document.body.innerHTML = ''
  })

  /** A file as a browser hands it over: its own name and bytes, with the size said for it. */
  function formFile(name: string, size = 4): File {
    const file = new File(['x'], name)
    Object.defineProperty(file, 'size', { value: size })
    return file
  }

  async function chooseFile(wrapper: Awaited<ReturnType<typeof mountWithRouter>>, file: File): Promise<void> {
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
  }

  /** The chooser belongs to a hidden input; the one control a person or a screen reader meets is the button. */
  it('opens the file chooser from the button, offering every word-processing form and PDFs', async () => {
    signedIn()
    const wrapper = await mountWithRouter()
    await flushPromises()

    const input = wrapper.get('input[type="file"]')
    const openChooser = vi.spyOn(input.element as HTMLInputElement, 'click').mockImplementation(() => {})
    await wrapper.get('button.home__upload').trigger('click')

    expect(openChooser).toHaveBeenCalledTimes(1)
    expect(input.attributes('accept')).toBe(
      '.docx,.dotx,.docm,.dotm,.doc,.dot,.rtf,.odt,.ott,.pages,.pdf,' +
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document,' +
        'application/vnd.openxmlformats-officedocument.wordprocessingml.template,' +
        'application/vnd.ms-word.document.macroEnabled.12,application/vnd.ms-word.template.macroEnabled.12,' +
        'application/msword,application/rtf,text/rtf,application/vnd.oasis.opendocument.text,' +
        'application/vnd.oasis.opendocument.text-template,application/vnd.apple.pages,' +
        'application/x-iwork-pages-sffpages,application/pdf',
    )
    expect(input.attributes('tabindex')).toBe('-1')
    expect(input.attributes('aria-hidden')).toBe('true')
    const button = wrapper.get('button.home__upload')
    expect(button.text()).toBe('Upload your documents')
    // The kinds of file it takes, and how large one may be, are said under it in plain words, and read out with it.
    expect(wrapper.get('#home-upload-kinds').text()).toBe('Word, PDF, Pages, OpenDocument or RTF, up to 10 MB')
    expect(button.attributes('aria-describedby')).toBe('home-upload-kinds')
  })

  /** An older server does not say how large a file may be; a limit it did not state must not be made up. */
  it.each([
    ['a size it does not send', { ...capabilities('RULES'), maxUploadBytes: undefined as unknown as number }],
    ['a size of nothing', { ...capabilities('RULES'), maxUploadBytes: 0 }],
  ])('says only which kinds of file it takes when the server gives %s', async (_case, answer) => {
    signedIn()
    vi.mocked(getCapabilities).mockResolvedValue(answer)
    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.get('#home-upload-kinds').text()).toBe('Word, PDF, Pages, OpenDocument or RTF')
  })

  it('says only which kinds of file it takes when it cannot ask the server', async () => {
    signedIn()
    vi.mocked(getCapabilities).mockRejectedValue(new TypeError('Failed to fetch'))
    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.get('#home-upload-kinds').text()).toBe('Word, PDF, Pages, OpenDocument or RTF')
  })

  /** Where the AI service names the places found, a form's text goes to it; the person reads that before choosing a file. */
  it("says under the button that a form's text goes to the AI service, only where it does", async () => {
    signedIn()
    vi.mocked(getCapabilities).mockResolvedValue(capabilities('MODEL'))
    const wrapper = await mountWithRouter()
    await flushPromises()

    const disclosure = wrapper.get('#home-upload-disclosure')
    expect(disclosure.text()).toBe('When you upload a form, its text is sent to our AI service so Brownie can find the places to fill.')
    expect(disclosure.attributes('role')).toBeUndefined()
    expect(wrapper.get('button.home__upload').attributes('aria-describedby')).toBe('home-upload-kinds home-upload-disclosure')
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  it.each([
    ["Brownie's own rules", capabilities('RULES')],
    ['an older server', capabilities()],
  ])('says nothing about the AI service where %s name the places', async (_case, answer) => {
    signedIn()
    vi.mocked(getCapabilities).mockResolvedValue(answer)
    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(getCapabilities).toHaveBeenCalled()
    expect(wrapper.find('#home-upload-disclosure').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('AI service')
  })

  it('says nothing about the AI service when it cannot ask the server', async () => {
    signedIn()
    vi.mocked(getCapabilities).mockRejectedValue(new TypeError('Failed to fetch'))
    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.find('#home-upload-disclosure').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  /**
   * Learning a form takes several requests, one of them a render; the card under the button names the form and says
   * which part is under way, and the button, which keeps the keyboard focus, does nothing until it is over.
   */
  it('says each step as it starts, keeps the button unavailable, and opens the new document', async () => {
    signedIn()
    let finish: (outcome: LearnOutcome) => void = () => {}
    let report: (step: LearnStep, detail: StepDetail) => void = () => {}
    vi.mocked(learnFormAndStartDocument).mockImplementation((_workspaceId, _file, onStep) => {
      report = onStep ?? report
      return new Promise((resolve) => (finish = resolve))
    })
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()
    const button = wrapper.get('button.home__upload')
    ;(button.element as HTMLButtonElement).focus()

    const file = formFile('Club minutes.docx', 49152)
    await chooseFile(wrapper, file)

    expect(learnFormAndStartDocument).toHaveBeenCalledWith(7, file, expect.any(Function))
    const status = wrapper.get('[role="status"]')
    expect(status.text()).toBe('Uploading…')
    // The line that is read out is not drawn: the card shows the same thing.
    expect(status.classes()).toContain('visually-hidden')
    expect(button.attributes('aria-disabled')).toBe('true')
    expect(button.attributes('disabled')).toBeUndefined()
    expect(document.activeElement).toBe(button.element)

    const card = wrapper.get('.home__card--learning')
    expect(card.get('.home__card-name').text()).toBe('Club minutes.docx')
    expect(card.get('.home__card-meta').text()).toBe('Word • 48 KB • becomes a template and a document')
    expect(card.get('.home__progress').attributes('aria-hidden')).toBe('true')

    const openChooser = vi.spyOn(wrapper.get('input[type="file"]').element as HTMLInputElement, 'click')
    await button.trigger('click')
    expect(openChooser).not.toHaveBeenCalled()

    const rows = () => card.findAll('.step').map((row) => [row.classes().find((name) => name.startsWith('step--')), row.text()])
    expect(rows()).toEqual([
      ['step--active', 'In progress: Uploading the file'],
      ['step--pending', 'Not started: Checking the file'],
      ['step--pending', 'Not started: Learning the form: finding where the values go'],
      ['step--pending', 'Not started: Getting the template ready'],
      ['step--pending', 'Not started: Opening your document'],
    ])

    const steps: [LearnStep, StepDetail, string, string][] = [
      ['checking', { mediaType: 'PAGES', waiting: false }, 'Checking the file…', 'Checking the file'],
      ['preparing-copy', { mediaType: 'PAGES', waiting: false }, 'Opening your Pages file and finding where the values go…', 'Learning the form: finding where the values go'],
      ['preparing-copy', { mediaType: 'PAGES', waiting: true }, 'Waiting for a free moment…', 'Learning the form: waiting for a free moment'],
      ['preparing-copy', { mediaType: 'PAGES', waiting: false }, 'Opening your Pages file and finding where the values go…', 'Learning the form: finding where the values go'],
      ['learning', { mediaType: 'PAGES', waiting: false }, 'Learning the form…', 'Learning the form: finding where the values go'],
      ['preparing', { mediaType: 'PAGES', waiting: false }, 'Getting the template ready…', 'Getting the template ready'],
      ['opening', { mediaType: 'PAGES', waiting: false }, 'Opening your document…', 'Opening your document'],
    ]
    for (const [step, detail, words, activeLine] of steps) {
      report(step, detail)
      await nextTick()
      expect(status.text()).toBe(words)
      const active = card.findAll('.step--active')
      expect(active).toHaveLength(1)
      expect(active[0]!.text()).toBe(`In progress: ${activeLine}`)
    }
    expect(rows().map(([state]) => state)).toEqual(['step--done', 'step--done', 'step--done', 'step--done', 'step--active'])
    expect(card.findAll('.step--done').map((row) => row.text())).toEqual([
      'Done: Uploaded',
      'Done: Checked the file',
      'Done: Learned the form',
      'Done: Got the template ready',
    ])
    expect(await axe(wrapper.element)).toHaveNoViolations()

    finish({ ok: true, documentId: 77, name: 'Club minutes', notes: [] })
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/documents/77')
    expect(readDocumentHandoff()).toBeNull()
    wrapper.unmount()
  })

  /** The card is the one place the person is told what they may do while it runs, and it must be true to what the page does. */
  it('tells the person they can leave while the form is learned, and keeps the cards for first-timers out of the way', async () => {
    signedIn()
    vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithRouter()
    await flushPromises()
    expect(wrapper.find('.home__how').exists()).toBe(true)

    await chooseFile(wrapper, formFile('Club minutes.docx'))

    const note = wrapper.get('.home__card--learning .home__note')
    expect(note.text()).toBe(
      'You can leave this page, but keep this tab open. Brownie opens the document when it is ready, or it waits for you in Recent documents.',
    )
    // The cards for someone with nothing yet, and a box saying there are no documents, would only crowd the card.
    expect(wrapper.find('.home__how').exists()).toBe(false)
    expect(wrapper.find('.home__empty').exists()).toBe(false)
    // The section itself stays, so a trash notice reached while nothing is listed is not pulled out from under it.
    expect(wrapper.find('.home__recent').exists()).toBe(true)
  })

  /**
   * Trashing the last document leaves a notice that undoes it, unread until the person follows or
   * closes it; starting the next upload is not that, so the notice must still be there afterwards.
   */
  it('keeps the trash-undo notice in view when an upload starts before it was dismissed', async () => {
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'March Minutes', '2026-03-01T10:00:00Z')])
    vi.mocked(trashDocument).mockResolvedValue(trashedEntry(40, 1, 'March Minutes'))
    vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
    signedIn()
    const wrapper = await mountWithRouter()
    await flushPromises()

    await trashButton(wrapper, 'March Minutes').trigger('click')
    await flushPromises()
    expect(wrapper.get('.home__notice').text()).toContain('Moved "March Minutes" to the trash.')

    await chooseFile(wrapper, formFile('Club minutes.docx'))

    const notice = wrapper.find('.home__notice')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('Moved "March Minutes" to the trash.')
    expect(notice.get('a').attributes('href')).toBe('/trash')
  })

  it('keeps the documents already listed in view while a form is learned', async () => {
    signedIn()
    vi.mocked(listDocuments).mockResolvedValue([summary(1, 'March Minutes', '2026-03-01T10:00:00Z')])
    vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithRouter()
    await flushPromises()

    await chooseFile(wrapper, formFile('Club minutes.docx'))

    expect(wrapper.find('.home__card--learning').exists()).toBe(true)
    expect(wrapper.findAll('.home__row-title').map((title) => title.text())).toEqual(['March Minutes'])
  })

  /** What the person is told before choosing a file has nothing to say while there is no file left to choose. */
  it('puts the note about the AI service away while a form is learned, and brings it back after', async () => {
    signedIn()
    vi.mocked(getCapabilities).mockResolvedValue(capabilities('MODEL'))
    let finish: (outcome: LearnOutcome) => void = () => {}
    vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise((resolve) => (finish = resolve)))
    const wrapper = await mountWithRouter()
    await flushPromises()
    expect(wrapper.find('#home-upload-disclosure').exists()).toBe(true)

    await chooseFile(wrapper, formFile('Minutes.docx'))
    expect(wrapper.find('#home-upload-disclosure').exists()).toBe(false)
    expect(wrapper.get('button.home__upload').attributes('aria-describedby')).toBe('home-upload-kinds')

    finish({ ok: false, message: 'Brownie could not read this file. It may be damaged: open it, save it again, and upload it again.' })
    await flushPromises()
    // A refusal sends the person back to the button, so what they are told before choosing is there again.
    expect(wrapper.find('#home-upload-disclosure').exists()).toBe(true)
  })

  /** What Brownie found and changed is news about the form, not a warning: the page it opens on shows it under its own heading. */
  it('hands the notes about the form to the document it opens', async () => {
    signedIn()
    const notes = [
      "Brownie's copy has the tracked changes accepted and the comments left out; your original file is unchanged.",
      'Brownie found 2 places to fill in. Each is marked "Found by Brownie" so you can check it.',
    ]
    vi.mocked(learnFormAndStartDocument).mockResolvedValue({ ok: true, documentId: 78, name: 'Invoice', notes })
    const wrapper = await mountWithRouter()
    await flushPromises()

    await chooseFile(wrapper, new File(['docx'], 'Invoice.docx'))
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/documents/78')
    expect(readDocumentHandoff()).toEqual({ attachedSources: [], sourceWarning: null, formNotes: notes })
  })

  it.each([
    ['Club minutes.docx', 'Word'],
    ['Old minutes.DOC', 'Word'],
    ['Notes.rtf', 'RTF'],
    ['Minutes.odt', 'OpenDocument'],
    ['Minutes.pages', 'Pages'],
    ['Budget.pdf', 'PDF'],
  ])('names %s as a %s file on the card', async (name, kind) => {
    signedIn()
    vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithRouter()
    await flushPromises()

    await chooseFile(wrapper, formFile(name, 1536))

    expect(wrapper.get('.home__card-meta').text()).toBe(`${kind} • 1.5 KB • becomes a template and a document`)
  })

  it('names only what it knows of a file whose kind or size it cannot tell', async () => {
    signedIn()
    vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithRouter()
    await flushPromises()

    await chooseFile(wrapper, formFile('Notes', 0.5))

    expect(wrapper.get('.home__card-name').text()).toBe('Notes')
    expect(wrapper.get('.home__card-meta').text()).toBe('becomes a template and a document')
  })

  describe('when a form is refused', () => {
    const SIGNED = PDF_REFUSAL_SENTENCES.SIGNED

    async function refused(message: string, file = formFile('Club budget form.pdf', 1258291)) {
      signedIn()
      vi.mocked(learnFormAndStartDocument).mockResolvedValue({ ok: false, message })
      const wrapper = await mountWithRouter({ attachTo: document.body })
      await flushPromises()
      const upload = wrapper.get('button.home__upload')
      ;(upload.element as HTMLButtonElement).focus()
      await chooseFile(wrapper, file)
      await flushPromises()
      return { wrapper, upload }
    }

    it('names the form, says why it was not added and what to do, and lets the person choose another', async () => {
      const { wrapper, upload } = await refused(SIGNED)

      const card = wrapper.get('.home__card--refused')
      expect(card.get('.home__card-name').text()).toBe('Club budget form.pdf')
      expect(card.get('.home__card-meta').text()).toBe('PDF • 1.2 MB • not added')
      // The reason is the alert, word for word as the rest of the app says it, and is all the alert says.
      expect(wrapper.get('[role="alert"]').text()).toBe(SIGNED)
      expect(card.get('.home__note').text()).toBe(
        'What you can do: upload the form from before it was signed, or ask whoever sent it for an unsigned copy.',
      )
      expect(card.findAll('button').map((button) => button.text())).toEqual(['Close', 'Choose another file', 'Dismiss'])
      // Nothing is learned, so no step is under way, and the button is ready for another try.
      expect(wrapper.find('.home__card--learning').exists()).toBe(false)
      expect(wrapper.get('[role="status"]').text()).toBe('')
      expect(upload.attributes('aria-disabled')).toBeUndefined()
      expect(router.currentRoute.value.fullPath).toBe('/')
      // The button that was pressed keeps the focus: it is the way to try again, and the alert has been read out.
      expect(document.activeElement).toBe(upload.element)
      // A refusal is not the first visit: the page does not offer the cards for someone with nothing yet.
      expect(wrapper.find('.home__how').exists()).toBe(false)
      expect(wrapper.text()).toContain('There are no documents here.')
      expect(await axe(wrapper.element)).toHaveNoViolations()
      wrapper.unmount()
    })

    it('puts the steps to take in a panel of their own when the failure gives them', async () => {
      const { wrapper } = await refused(REFUSAL_SENTENCES.PASSWORD_PROTECTED, formFile('Locked minutes.docx'))

      expect(wrapper.get('[role="alert"]').text()).toBe("This file is locked with a password, so Brownie can't open it.")
      expect(wrapper.get('.home__note').text()).toBe(
        'What you can do: open it, remove the password, save it, and upload it again.',
      )
      expect(wrapper.get('.home__card-meta').text()).toBe('Word • 4 bytes • not added')
      wrapper.unmount()
    })

    it('shows a sentence whose way forward the failure does not give as one piece, with no panel', async () => {
      const message = 'Could not upload "Club budget form.pdf". Brownie could not be reached. Check your connection, then try again.'
      const { wrapper } = await refused(message)

      expect(wrapper.get('[role="alert"]').text()).toBe(message)
      expect(wrapper.find('.home__note').exists()).toBe(false)
      expect(await axe(wrapper.element)).toHaveNoViolations()
      wrapper.unmount()
    })

    it('opens the file chooser again from "Choose another file", and leaves the card until another file is chosen', async () => {
      const { wrapper } = await refused(SIGNED)
      const openChooser = vi.spyOn(wrapper.get('input[type="file"]').element as HTMLInputElement, 'click').mockImplementation(() => {})

      await wrapper.get('.home__card--refused .home__card-actions button').trigger('click')

      expect(openChooser).toHaveBeenCalledTimes(1)
      expect(wrapper.find('.home__card--refused').exists()).toBe(true)

      // The reason belongs to the file it was about: choosing another takes it away.
      vi.mocked(learnFormAndStartDocument).mockResolvedValue({ ok: true, documentId: 79, name: 'Minutes', notes: [] })
      await chooseFile(wrapper, formFile('Minutes.docx'))
      await flushPromises()

      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(wrapper.find('.home__card--refused').exists()).toBe(false)
      expect(router.currentRoute.value.fullPath).toBe('/documents/79')
      wrapper.unmount()
    })

    /** Choosing from the card replaces the card, and the button that was pressed goes with it. */
    it('keeps the focus on the page when "Choose another file" is used and the next form starts to be learned', async () => {
      const { wrapper, upload } = await refused(SIGNED)
      const choose = wrapper.get('.home__card--refused .home__card-actions button')
      ;(choose.element as HTMLButtonElement).focus()
      expect(document.activeElement).toBe(choose.element)

      vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
      await chooseFile(wrapper, formFile('Minutes.docx'))
      await flushPromises()

      expect(wrapper.find('.home__card--refused').exists()).toBe(false)
      expect(document.activeElement).toBe(upload.element)
      wrapper.unmount()
    })

    it.each([
      ['"Dismiss"', 'Dismiss'],
      ['the close button', 'Close'],
    ])('takes the card away with %s, and puts the focus back on the upload button', async (_case, label) => {
      const { wrapper, upload } = await refused(SIGNED)
      ;(wrapper.get('.home__card--refused .home__card-close').element as HTMLButtonElement).focus()

      const button = wrapper.findAll('.home__card--refused button').find((candidate) => candidate.text() === label)
      await button!.trigger('click')
      await flushPromises()

      expect(wrapper.find('.home__card--refused').exists()).toBe(false)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(document.activeElement).toBe(upload.element)
      wrapper.unmount()
    })

    /** A refusal already shown here is not said again on the next visit; one that came while the person was away is. */
    it('does not say a refusal again on the next visit, but says one that came while the person was away', async () => {
      const { wrapper } = await refused(SIGNED)
      wrapper.unmount()

      const next = await mountWithRouter()
      await flushPromises()
      expect(next.find('.home__card--refused').exists()).toBe(false)
      next.unmount()

      formUploadError.value = { form: { name: 'Away.pdf', kind: 'PDF', size: '4 bytes' }, message: SIGNED }
      const back = await mountWithRouter()
      await flushPromises()
      expect(back.get('.home__card-name').text()).toBe('Away.pdf')
      expect(back.get('[role="alert"]').text()).toBe(SIGNED)
      back.unmount()
    })
  })

  it('does not pull someone who has left Home back to the document once it is ready', async () => {
    signedIn()
    let finish: (outcome: LearnOutcome) => void = () => {}
    vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise((resolve) => (finish = resolve)))
    const wrapper = await mountWithRouter()
    await flushPromises()

    await chooseFile(wrapper, new File(['docx'], 'Minutes.docx'))
    await router.push('/trash')
    const push = vi.spyOn(router, 'push')
    wrapper.unmount()
    finish({ ok: true, documentId: 80, name: 'Minutes', notes: ['Brownie found 1 place to fill in.'] })
    await flushPromises()

    expect(push).not.toHaveBeenCalled()
    // It is said on Home for when they come back, under the name of the file they chose.
    expect(formUploadFinished.value).toEqual({ documentId: 80, fileName: 'Minutes.docx', notes: ['Brownie found 1 place to fill in.'] })
  })

  /** Coming back to Home, the person finds the document it made, and opening it from there still brings its notes. */
  it('offers the document made while the person was away, with its notes', async () => {
    signedIn()
    formUploadFinished.value = { documentId: 80, fileName: 'Minutes.docx', notes: ['Brownie found 1 place to fill in.'] }
    const wrapper = await mountWithRouter()
    await flushPromises()

    const toast = wrapper.get('.home__toast')
    expect(toast.get('p').text()).toBe('Brownie learned “Minutes.docx” and started a document from it.')
    expect(toast.get('a').text()).toBe('Open it')
    expect(await axe(wrapper.element)).toHaveNoViolations()

    await toast.get('a').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/documents/80')
    expect(readDocumentHandoff()).toEqual({ attachedSources: [], sourceWarning: null, formNotes: ['Brownie found 1 place to fill in.'] })
    expect(formUploadFinished.value).toBeNull()
  })

  /**
   * A form can finish while its page is showing yet nothing opens the document for the person: the upload began on an
   * earlier visit. The notice appears, and the live region says so, once.
   */
  it('says in the live region that a form has finished when it does so while the page is showing, and only then', async () => {
    signedIn()
    const wrapper = await mountWithRouter()
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('')

    formUploadFinished.value = { documentId: 80, fileName: 'Minutes.docx', notes: [] }
    await nextTick()

    expect(wrapper.get('[role="status"]').text()).toBe('Brownie learned “Minutes.docx” and started a document from it.')
    expect(wrapper.findAll('[role="status"], [aria-live="polite"]')).toHaveLength(1)

    await wrapper.get('.home__toast button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('')
  })

  it('does not read out a notice that was already there when the page opened', async () => {
    signedIn()
    formUploadFinished.value = { documentId: 80, fileName: 'Minutes.docx', notes: [] }
    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.find('.home__toast').exists()).toBe(true)
    expect(wrapper.get('[role="status"]').text()).toBe('')
  })

  it('keeps that notice until it is used or closed, and puts the focus back on the upload button when it is closed', async () => {
    signedIn()
    formUploadFinished.value = { documentId: 80, fileName: 'Minutes.docx', notes: [] }
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()
    expect(wrapper.find('.home__toast').exists()).toBe(true)

    const close = wrapper.get('.home__toast button')
    expect(close.text()).toBe('Dismiss')
    ;(close.element as HTMLButtonElement).focus()
    await close.trigger('click')
    await flushPromises()

    expect(wrapper.find('.home__toast').exists()).toBe(false)
    expect(formUploadFinished.value).toBeNull()
    expect(document.activeElement).toBe(wrapper.get('button.home__upload').element)
    wrapper.unmount()
  })

  it('takes the notice away when the person chooses another form, and keeps the focus on the page if it held it', async () => {
    signedIn()
    formUploadFinished.value = { documentId: 80, fileName: 'Minutes.docx', notes: [] }
    vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()
    ;(wrapper.get('.home__toast button').element as HTMLButtonElement).focus()

    await chooseFile(wrapper, formFile('Invoice.docx'))
    await flushPromises()

    expect(wrapper.find('.home__toast').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('button.home__upload').element)
    wrapper.unmount()
  })

  /** More than one polite region on a page and a screen reader queues them against each other. */
  it('has one polite live region, and it is not drawn', async () => {
    signedIn()
    vi.mocked(listDocuments).mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.text()).toContain('Loading documents…')
    const live = wrapper.findAll('[aria-live="polite"], [role="status"]')
    expect(live).toHaveLength(1)
    expect(live[0]!.classes()).toContain('home__status')
    // Its line is there before anything is chosen, because a region that only appears with its first message is not reliably read out.
    expect(live[0]!.text()).toBe('')
    expect(live[0]!.classes()).toContain('visually-hidden')
  })

  describe('dropping a form on the page', () => {
    const DROP_WORDS =
      'Drop your form to start. Word, PDF, Pages, OpenDocument or RTF, up to 10 MB. Brownie makes a template and a document from it.'

    /** A drag as the browser reports it: what it carries, and, for files, whether each is a folder. */
    function drag(
      type: string,
      carries: { files?: File[]; folders?: boolean[]; types?: string[] } = {},
    ): Event & { dataTransfer: { dropEffect: string } } {
      const files = carries.files ?? []
      const event = new Event(type, { bubbles: true, cancelable: true })
      Object.defineProperty(event, 'dataTransfer', {
        value: {
          types: carries.types ?? (files.length > 0 ? ['Files'] : ['text/plain']),
          files,
          items: files.map((_file, index) => ({
            kind: 'file',
            webkitGetAsEntry: () => ({ isDirectory: carries.folders?.[index] ?? false }),
          })),
          dropEffect: 'none',
        },
      })
      return event as Event & { dataTransfer: { dropEffect: string } }
    }

    const zone = () => document.body.querySelector('.home__drop')

    async function pageReady() {
      signedIn()
      const wrapper = await mountWithRouter()
      await flushPromises()
      return wrapper
    }

    it('turns the page into a drop zone while a file is dragged over it, and says so in the one live region', async () => {
      const wrapper = await pageReady()
      expect(zone()).toBeNull()

      const enter = drag('dragenter', { files: [formFile('Minutes.docx')] })
      window.dispatchEvent(enter)
      await nextTick()

      expect(zone()).not.toBeNull()
      // The zone is decoration, hidden from assistive technology; the live region says it is open.
      expect(zone()!.getAttribute('aria-hidden')).toBe('true')
      expect(zone()!.querySelector('.home__drop-title')!.textContent).toBe('Drop your form to start')
      expect(zone()!.querySelector('.home__drop-text')!.textContent).toBe(
        'Word, PDF, Pages, OpenDocument or RTF, up to 10 MB. Brownie makes a template and a document from it.',
      )
      expect(zone()!.querySelector('.home__drop-note')!.textContent?.trim()).toBe(
        'Locked, signed or damaged files are refused with the reason.',
      )
      expect(wrapper.get('[role="status"]').text()).toBe(DROP_WORDS)
      expect(wrapper.findAll('[role="status"], [aria-live="polite"]')).toHaveLength(1)
      expect(enter.defaultPrevented).toBe(true)
      expect(await axe(document.body)).toHaveNoViolations()

      window.dispatchEvent(drag('dragleave', { files: [formFile('Minutes.docx')] }))
      await nextTick()

      expect(zone()).toBeNull()
      expect(wrapper.get('[role="status"]').text()).toBe('')
    })

    it('stays open while the drag crosses from one element to the next, and closes when it leaves the page', async () => {
      await pageReady()
      const files = [formFile('Minutes.docx')]

      window.dispatchEvent(drag('dragenter', { files }))
      window.dispatchEvent(drag('dragenter', { files }))
      window.dispatchEvent(drag('dragleave', { files }))
      await nextTick()
      expect(zone()).not.toBeNull()

      window.dispatchEvent(drag('dragleave', { files }))
      await nextTick()
      expect(zone()).toBeNull()
    })

    it('answers a drag over the page with permission to copy, and leaves anything that is not a file alone', async () => {
      await pageReady()

      const over = drag('dragover', { files: [formFile('Minutes.docx')] })
      window.dispatchEvent(over)
      expect(over.defaultPrevented).toBe(true)
      expect(over.dataTransfer.dropEffect).toBe('copy')

      for (const type of ['dragenter', 'dragover', 'dragleave', 'drop']) {
        const text = drag(type, { types: ['text/plain', 'text/uri-list'] })
        window.dispatchEvent(text)
        expect(text.defaultPrevented).toBe(false)
      }
      await nextTick()
      expect(zone()).toBeNull()
      expect(learnFormAndStartDocument).not.toHaveBeenCalled()
    })

    /** Choosing a file and dropping one end in the same place, so both are taken and refused alike. */
    it('treats a drop exactly like choosing the file', async () => {
      vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
      const wrapper = await pageReady()
      const file = formFile('Minutes.docx', 49152)

      window.dispatchEvent(drag('dragenter', { files: [file] }))
      await nextTick()
      const drop = drag('drop', { files: [file] })
      window.dispatchEvent(drop)
      await nextTick()

      expect(drop.defaultPrevented).toBe(true)
      expect(learnFormAndStartDocument).toHaveBeenCalledTimes(1)
      expect(learnFormAndStartDocument).toHaveBeenCalledWith(7, file, expect.any(Function))
      expect(zone()).toBeNull()
      expect(wrapper.get('.home__card-name').text()).toBe('Minutes.docx')
      expect(wrapper.get('.home__card-meta').text()).toBe('Word • 48 KB • becomes a template and a document')
      expect(wrapper.get('button.home__upload').attributes('aria-disabled')).toBe('true')
    })

    it('refuses a dropped form in the same words as a chosen one', async () => {
      vi.mocked(learnFormAndStartDocument).mockResolvedValue({ ok: false, message: PDF_REFUSAL_SENTENCES.SIGNED })
      const wrapper = await pageReady()

      window.dispatchEvent(drag('drop', { files: [formFile('Club budget form.pdf', 1258291)] }))
      await flushPromises()

      expect(wrapper.get('[role="alert"]').text()).toBe(PDF_REFUSAL_SENTENCES.SIGNED)
      expect(wrapper.get('.home__card-meta').text()).toBe('PDF • 1.2 MB • not added')
    })

    /** A form being learned is not interrupted: the drop is claimed, so the browser does not open the file in its place. */
    it('ignores a drop while another form is being learned, and does not show the drop zone', async () => {
      vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
      const wrapper = await pageReady()
      await chooseFile(wrapper, formFile('First.docx'))
      const second = [formFile('Second.docx')]

      window.dispatchEvent(drag('dragenter', { files: second }))
      const over = drag('dragover', { files: second })
      window.dispatchEvent(over)
      await nextTick()
      expect(zone()).toBeNull()
      expect(over.dataTransfer.dropEffect).toBe('none')

      const drop = drag('drop', { files: second })
      window.dispatchEvent(drop)
      await nextTick()

      expect(drop.defaultPrevented).toBe(true)
      expect(learnFormAndStartDocument).toHaveBeenCalledTimes(1)
      expect(wrapper.get('.home__card-name').text()).toBe('First.docx')
    })

    it('closes the drop zone when the form that was dropped on it starts to be learned', async () => {
      vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
      await pageReady()
      const files = [formFile('Minutes.docx')]

      window.dispatchEvent(drag('dragenter', { files }))
      await nextTick()
      expect(zone()).not.toBeNull()
      window.dispatchEvent(drag('drop', { files }))
      await nextTick()

      expect(zone()).toBeNull()
    })

    it('ignores a dropped folder, and says why a dropped Pages document that is one cannot be used', async () => {
      vi.mocked(learnFormAndStartDocument).mockReturnValue(new Promise(() => {}))
      await pageReady()

      window.dispatchEvent(drag('drop', { files: [formFile('Old minutes', 0)], folders: [true] }))
      await flushPromises()
      expect(learnFormAndStartDocument).not.toHaveBeenCalled()

      // A Pages document on a Mac is a folder; it goes on so that the person is told why it cannot be uploaded.
      const pages = formFile('Minutes.pages', 0)
      window.dispatchEvent(drag('drop', { files: [pages], folders: [true] }))
      await flushPromises()
      expect(learnFormAndStartDocument).toHaveBeenCalledWith(7, pages, expect.any(Function))
    })

    it('takes one form at a time, and says so when several are dropped', async () => {
      const wrapper = await pageReady()

      window.dispatchEvent(drag('drop', { files: [formFile('One.docx'), formFile('Two.pdf')] }))
      await flushPromises()

      expect(learnFormAndStartDocument).not.toHaveBeenCalled()
      expect(wrapper.get('.home__card-name').text()).toBe('2 files')
      expect(wrapper.get('.home__card-meta').text()).toBe('not added')
      expect(wrapper.get('[role="alert"]').text()).toBe('Brownie fills one form at a time. Drop or choose a single file.')
    })

    it('offers nothing to someone who is not signed in, and still keeps the browser from opening a dropped file', async () => {
      const session = useSessionStore()
      session.status = 'anonymous'
      const wrapper = await mountWithRouter()

      const enter = drag('dragenter', { files: [formFile('Minutes.docx')] })
      window.dispatchEvent(enter)
      const drop = drag('drop', { files: [formFile('Minutes.docx')] })
      window.dispatchEvent(drop)
      await nextTick()

      expect(zone()).toBeNull()
      expect(drop.defaultPrevented).toBe(true)
      expect(learnFormAndStartDocument).not.toHaveBeenCalled()
      expect(wrapper.find('.home__card').exists()).toBe(false)
    })

    it('stops listening to the window when the page is left', async () => {
      const wrapper = await pageReady()
      wrapper.unmount()

      const enter = drag('dragenter', { files: [formFile('Minutes.docx')] })
      window.dispatchEvent(enter)
      await nextTick()

      expect(zone()).toBeNull()
      expect(enter.defaultPrevented).toBe(false)
    })
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
