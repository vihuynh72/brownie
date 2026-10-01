import { describe, expect, it, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
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
    vi.mocked(learnFormAndStartDocument).mockReset()
    vi.mocked(getCapabilities).mockReset().mockResolvedValue(capabilities('RULES'))
    resetCapabilitiesCache()
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
    // What the button takes is said to everyone; what happens to a form's text is asked about only once signed in.
    expect(wrapper.get('.home__upload-kinds').text()).toBe('Word, PDF, Pages, OpenDocument or RTF')
    expect(getCapabilities).not.toHaveBeenCalled()
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
    expect(rows[0]!.text()).toContain(new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(today))
  })

  /** The page that used to start a document from a template is gone; the words point at where templates are now. */
  it('says where a first document comes from when there are none', async () => {
    vi.mocked(listDocuments).mockResolvedValue([])
    signedIn()

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.get('.home__hint').text()).toBe(
      'There are no documents here. Upload a form above to start one, or choose a template under My Templates.',
    )
    expect(wrapper.find('.home__hint a').exists()).toBe(false)
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
    expect(wrapper.text()).not.toContain('Upload a form above')
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
    // The kinds of file it takes are said under it in plain words, and read out with it.
    expect(wrapper.get('#home-upload-kinds').text()).toBe('Word, PDF, Pages, OpenDocument or RTF')
    expect(button.attributes('aria-describedby')).toBe('home-upload-kinds')
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
   * Learning a form takes several requests, one of them a render; the line under the button says which
   * part is under way, and the button, which keeps the keyboard focus, does nothing until it is over.
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

    const file = new File(['docx'], 'Club minutes.docx')
    await chooseFile(wrapper, file)

    expect(learnFormAndStartDocument).toHaveBeenCalledWith(7, file, expect.any(Function))
    const status = wrapper.get('[role="status"]')
    expect(status.text()).toBe('Uploading…')
    // Set apart from the hints above it by a turning ring beside it, which is seen and not read out.
    const ring = wrapper.get('.home__upload-progress .activity-indicator')
    expect(ring.attributes('aria-hidden')).toBe('true')
    expect(status.element.contains(ring.element)).toBe(false)
    expect(button.attributes('aria-disabled')).toBe('true')
    expect(button.attributes('disabled')).toBeUndefined()
    expect(document.activeElement).toBe(button.element)

    const openChooser = vi.spyOn(wrapper.get('input[type="file"]').element as HTMLInputElement, 'click')
    await button.trigger('click')
    expect(openChooser).not.toHaveBeenCalled()

    const steps: [LearnStep, StepDetail, string][] = [
      ['checking', { mediaType: 'PAGES', waiting: false }, 'Checking the file…'],
      ['preparing-copy', { mediaType: 'PAGES', waiting: false }, 'Opening your Pages file and finding where the values go…'],
      ['preparing-copy', { mediaType: 'PAGES', waiting: true }, 'Waiting for a free moment…'],
      ['preparing-copy', { mediaType: 'PAGES', waiting: false }, 'Opening your Pages file and finding where the values go…'],
      ['learning', { mediaType: 'PAGES', waiting: false }, 'Learning the form…'],
      ['preparing', { mediaType: 'PAGES', waiting: false }, 'Getting the template ready…'],
      ['opening', { mediaType: 'PAGES', waiting: false }, 'Opening your document…'],
    ]
    for (const [step, detail, words] of steps) {
      report(step, detail)
      await nextTick()
      expect(status.text()).toBe(words)
    }
    expect(await axe(wrapper.element)).toHaveNoViolations()

    finish({ ok: true, documentId: 77, name: 'Club minutes', notes: [] })
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/documents/77')
    expect(readDocumentHandoff()).toBeNull()
    wrapper.unmount()
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

  it('says why a form could not be used, clears the progress line, and lets the person choose another', async () => {
    signedIn()
    vi.mocked(learnFormAndStartDocument).mockResolvedValue({
      ok: false,
      message: 'This PDF has been signed. Filling it in would break the signature, so Brownie leaves it as it is.',
    })
    const wrapper = await mountWithRouter({ attachTo: document.body })
    await flushPromises()

    await chooseFile(wrapper, new File(['%PDF'], 'form.pdf'))
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'This PDF has been signed. Filling it in would break the signature, so Brownie leaves it as it is.',
    )
    expect(wrapper.get('[role="status"]').text()).toBe('')
    expect(wrapper.find('.activity-indicator').exists()).toBe(false)
    expect(wrapper.get('button.home__upload').attributes('aria-disabled')).toBeUndefined()
    expect(router.currentRoute.value.fullPath).toBe('/')
    expect(await axe(wrapper.element)).toHaveNoViolations()

    // The reason belongs to the file it was about: choosing another takes it away.
    vi.mocked(learnFormAndStartDocument).mockResolvedValue({ ok: true, documentId: 79, name: 'Minutes', notes: [] })
    await chooseFile(wrapper, new File(['docx'], 'Minutes.docx'))
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(router.currentRoute.value.fullPath).toBe('/documents/79')
    wrapper.unmount()
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
  })

  /** Coming back to Home, the person finds the document it made, and opening it from there still brings its notes. */
  it('offers the document made while the person was away, with its notes', async () => {
    signedIn()
    formUploadFinished.value = { documentId: 80, name: 'Minutes', notes: ['Brownie found 1 place to fill in.'] }
    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.get('.home__upload-finished').text()).toBe('Brownie learned "Minutes" and started a document from it. Open it.')
    await wrapper.get('.home__upload-finished a').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/documents/80')
    expect(readDocumentHandoff()).toEqual({ attachedSources: [], sourceWarning: null, formNotes: ['Brownie found 1 place to fill in.'] })
    expect(formUploadFinished.value).toBeNull()
  })

  /** More than one polite region on a page and a screen reader queues them against each other. */
  it('has one polite live region, and it is the line under the upload button', async () => {
    signedIn()
    vi.mocked(listDocuments).mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(wrapper.text()).toContain('Loading documents…')
    const live = wrapper.findAll('[aria-live="polite"], [role="status"]')
    expect(live).toHaveLength(1)
    expect(live[0]!.classes()).toContain('home__upload-status')
    // Its line is there before anything is chosen, holding the room the steps will take, with no ring yet.
    expect(live[0]!.element.parentElement?.classList).toContain('home__upload-progress')
    expect(wrapper.find('.activity-indicator').exists()).toBe(false)
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
