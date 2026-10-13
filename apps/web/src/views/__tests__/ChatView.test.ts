import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import { readDocumentHandoff } from '@/router/handoff'
import { resetCapabilitiesCache } from '@/capabilities'
import { useSessionStore } from '@/stores/session'
import { documentTitleFor, useTemplatesStore } from '@/stores/templates'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    listDocuments: vi.fn(),
    listTemplates: vi.fn(),
    allocateUpload: vi.fn(),
    uploadArtifactContent: vi.fn(),
    completeUpload: vi.fn(),
    extractArtifact: vi.fn(),
    createDocument: vi.fn(),
    attachDocumentSource: vi.fn(),
    getCapabilities: vi.fn(),
  }
})

import {
  ApiRequestError,
  allocateUpload,
  attachDocumentSource,
  completeUpload,
  createDocument,
  extractArtifact,
  getCapabilities,
  listDocuments,
  listTemplates,
  uploadArtifactContent,
  type ArtifactResponse,
  type CapabilitiesResponse,
  type DocumentResponse,
  type DocumentSourceResponse,
  type DocumentSummaryResponse,
  type TemplateResponse,
} from '@/api/client'

const stub = { template: '<div />' }
const WHEN = '2026-10-07T22:58:00Z'
const REQUEST = 'Make minutes for Tuesday’s robotics meeting from my transcript'

function template(id: number, displayName: string, overrides: Partial<TemplateResponse> = {}): TemplateResponse {
  return { id, displayName, status: 'ACTIVE', currentActiveVersionId: id * 10, createdAt: '2026-09-21T10:05:00Z', trashedAt: null, ...overrides }
}

// The three templates of the design: two kinds of minutes, and a form that was added last.
const FLOWING = template(1, 'Flowing meeting minutes')
const TABLE_LED = template(2, 'Table-led meeting minutes', { createdAt: '2026-09-21T10:05:01Z' })
const CLUB = template(3, 'Club equipment request', { createdAt: '2026-10-07T15:54:00Z' })

function summary(id: number, title: string, createdAt: string): DocumentSummaryResponse {
  return { id, title, templateId: 1, templateVersionId: 1, currentRevisionId: 1, createdAt }
}

const SOURCE: DocumentSourceResponse = { id: 3, artifactId: 5, displayFilename: 'minutes.txt', kind: 'ARTIFACT', fetchedAt: WHEN, attachedAt: WHEN }

function problem(status: number, code: string, detail: string): ApiRequestError {
  return new ApiRequestError(status, { status, title: 'Refused', code, detail, correlationId: 'c', fields: [], recoveryActions: [] })
}

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

let router: Router
let wrapper: VueWrapper

async function mountChat(options: { templates?: TemplateResponse[]; documents?: DocumentSummaryResponse[] | Error } = {}): Promise<void> {
  vi.mocked(listTemplates).mockResolvedValue(options.templates ?? [FLOWING, TABLE_LED, CLUB])
  if (options.documents instanceof Error) vi.mocked(listDocuments).mockRejectedValue(options.documents)
  else vi.mocked(listDocuments).mockResolvedValue(options.documents ?? [])
  signIn()
  router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: stub },
      { path: '/chat', component: stub },
      { path: '/documents/:id', component: stub },
    ],
  })
  await router.push('/chat')
  await router.isReady()
  wrapper = mount(ChatView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
}

/** What the person does: types a line in the message box and sends it. */
async function send(text: string): Promise<void> {
  await wrapper.get('#chat-composer').setValue(text)
  await wrapper.get('form.composer').trigger('submit')
  await flushPromises()
}

function textFile(name = 'minutes.txt', size?: number): File {
  const file = new File(['notes'], name, { type: 'text/plain' })
  if (size !== undefined) Object.defineProperty(file, 'size', { value: size })
  return file
}

/** The hidden input behind the + button, given a file the way a chooser would. */
async function attach(file: File = textFile()): Promise<void> {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

function acceptUploads(overrides: Partial<ArtifactResponse> = {}): void {
  vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'minutes.txt' })
  vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'minutes.txt' })
  vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'READY', displayFilename: 'minutes.txt', detectedMediaType: 'PLAIN_TEXT', ...overrides })
  vi.mocked(extractArtifact).mockResolvedValue({ status: 'COMPLETE' })
}

function acceptDocuments(): void {
  vi.mocked(createDocument).mockResolvedValue({ id: 42 } as DocumentResponse)
  vi.mocked(attachDocumentSource).mockResolvedValue(SOURCE)
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

const words = (element: { text(): string }): string => element.text().replace(/\s+/g, ' ')

/** The plan Brownie showed last: what each row says, and the steps under it. */
function lastPlan() {
  const card = wrapper.findAll('.chat-plan').at(-1)!
  const values = card.findAll('dd')
  return {
    labels: card.findAll('dt').map((label) => label.text()),
    template: card.get('.chat-plan__strong').text(),
    hint: card.find('.chat-plan__hint').exists() ? card.get('.chat-plan__hint').text() : null,
    reads: words(values[1]!),
    title: words(values[2]!),
    steps: card.findAll('.chat-plan__steps li').map((item) => item.findAll('span')[1]!.text()),
  }
}

/** The choices the person has now: only the plan being answered draws them. */
const choices = (): string[] => wrapper.findAll('.chat__log .choices__text').map((choice) => choice.text())

function choiceButton(label: string): DOMWrapper<Element> {
  const found = wrapper.findAll('.chat__log .choices__option').find((button) => button.text().includes(label))
  if (!found) throw new Error(`No choice "${label}" among: ${choices().join(' | ')}`)
  return found
}

async function choose(label: string): Promise<void> {
  await choiceButton(label).trigger('click')
  await flushPromises()
}

const log = (): string => words(wrapper.get('.chat__log'))

function buttonNamed(name: string): DOMWrapper<Element> {
  const found = wrapper.findAll('button').find((button) => button.text().replace(/\s+/g, ' ').trim() === name)
  if (!found) throw new Error(`No button "${name}"`)
  return found
}

describe('ChatView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    for (const call of [
      listDocuments,
      listTemplates,
      allocateUpload,
      uploadArtifactContent,
      completeUpload,
      extractArtifact,
      createDocument,
      attachDocumentSource,
      getCapabilities,
    ]) {
      vi.mocked(call).mockReset()
    }
    resetCapabilitiesCache()
  })

  afterEach(() => {
    wrapper?.unmount()
    document.body.innerHTML = ''
    window.history.replaceState(null, '', '/')
  })

  describe('before there is a conversation', () => {
    it('opens with Brownie asking what is needed, and names what the page does and does not do', async () => {
      await mountChat()

      expect(wrapper.get('h1').text()).toBe('Chat with Brownie')
      // Honest about how the template is chosen: a suggestion Brownie lays out, started only when you agree.
      expect(wrapper.get('.page-lede').text()).toBe(
        'Tell Brownie what you need filled. It suggests a template, shows its plan, and starts only when you agree.',
      )
      expect(words(wrapper.get('.chat__start'))).toContain(
        'Tell me what you need filled in. Attach notes or a transcript with +, and I will show you a plan before I start anything.',
      )
      expect(wrapper.get('.chat__disclaimer').text()).toBe(
        'Brownie can make mistakes. It never fills, sends or saves anything without your approval.',
      )
    })

    it('lists the newest documents, six at most, as plain links to open', async () => {
      const documents = Array.from({ length: 8 }, (_, index) => summary(index + 1, `Minutes ${index + 1}`, `2026-03-0${index + 1}T10:00:00Z`))
      await mountChat({ documents })

      expect(wrapper.get('h2').text()).toBe('Open a document')
      const rows = wrapper.findAll('.chat__row')
      expect(rows.map((row) => row.attributes('href'))).toEqual([8, 7, 6, 5, 4, 3].map((id) => `/documents/${id}`))
      expect(rows[0]!.text()).toBe('Minutes 8')
    })

    it('says so plainly when there is nothing to open yet', async () => {
      await mountChat({ documents: [] })

      expect(wrapper.text()).toContain('You don’t have any documents yet.')
    })

    it('says so plainly when the documents would not load, and still lets a request be made', async () => {
      await mountChat({ documents: new Error('down') })

      expect(wrapper.get('[role="alert"]').text()).toBe('Something went wrong loading your documents. Try reloading the page.')
      await send('Make minutes')
      expect(wrapper.findAll('.chat-plan')).toHaveLength(1)
    })

    it('tells a person with no template to start from before they ask, and links to Home to upload a form', async () => {
      await mountChat({ templates: [template(1, 'Old', { trashedAt: '2026-10-01T10:00:00Z' }), template(2, 'Unfinished', { currentActiveVersionId: null })] })

      const opening = wrapper.get('.chat__start')
      expect(words(opening)).toContain('You don’t have a template to start from yet.')
      expect(opening.get('a[href="/"]').text()).toBe('Upload a form on Home')
    })

    it('has a named conversation, a labelled message box, and a + that attaches plain text', async () => {
      await mountChat()

      expect(wrapper.get('[role="log"]').attributes('aria-label')).toBe('Conversation with Brownie')
      expect(wrapper.get('label[for="chat-composer"]').text()).toBe('How may I help you?')
      expect(wrapper.get('#chat-composer').attributes('placeholder')).toBe('How may I help you?')
      expect(wrapper.get('.chat__attach').text()).toBe('Attach notes or a transcript')
      expect(wrapper.get('input[type="file"]').attributes('accept')).toBe('.txt,text/plain')
      expect(buttonNamed('Send').attributes('aria-disabled')).toBe('true')
    })

    it('opens the file chooser from the + button', async () => {
      await mountChat()
      const click = vi.spyOn(wrapper.get('input[type="file"]').element as HTMLInputElement, 'click')

      await wrapper.get('.chat__attach').trigger('click')

      expect(click).toHaveBeenCalledTimes(1)
    })

    it('passes an accessibility scan', async () => {
      await mountChat({ documents: [summary(1, 'March Minutes', '2026-03-01T10:00:00Z')] })

      expect(await axe(wrapper.element)).toHaveNoViolations()
    })
  })

  describe("Brownie's plan", () => {
    it('answers a request with the template, what it reads, the new document, and what happens next', async () => {
      acceptUploads({ displayFilename: 'Oct 6 meeting transcript.txt' })
      await mountChat()
      await attach(textFile('Oct 6 meeting transcript.txt', 6139))
      await send(REQUEST)

      expect(log()).toContain(`You said: ${REQUEST}`)
      expect(log()).toContain('I can do that in a new document. Here is my plan:')
      const plan = lastPlan()
      expect(plan.labels).toEqual(['Template', 'Brownie reads', 'New document'])
      expect(plan.template).toBe('Flowing meeting minutes')
      expect(plan.reads).toBe('Oct 6 meeting transcript.txt, and nothing else')
      expect(plan.title).toBe(documentTitleFor('Flowing meeting minutes'))
      expect(wrapper.get('.chat-plan__next .chat-plan__label').text()).toBe('What happens next')
      expect(plan.steps).toEqual([
        'I start the document and read the file you attached.',
        'I ask you about anything missing or unclear.',
        'I show you what I found. Nothing is filled until you approve it.',
      ])
      expect(choices()).toEqual(['Start the document', 'Use Table-led meeting minutes instead'])
      expect(wrapper.get('.chat__log .choices__other-label').text()).toBe('Other:')
    })

    it('says there is no source yet, and what happens instead, when nothing was attached', async () => {
      await mountChat()
      await send('I need the club equipment request filled in')

      const plan = lastPlan()
      expect(plan.template).toBe('Club equipment request')
      expect(plan.reads).toBe('No source yet: you can add one in the document')
      expect(plan.steps[0]).toBe('I start the document. You can add notes or a transcript there, and I read them.')
    })

    it('does not guess aloud when a name matches the request more closely than any other', async () => {
      await mountChat()
      await send('I need the club equipment request filled in')

      expect(lastPlan().hint).toBeNull()
    })

    it('owns up to a toss-up between templates that fit the words equally well', async () => {
      await mountChat()
      await send(REQUEST)

      expect(lastPlan().template).toBe('Flowing meeting minutes')
      expect(lastPlan().hint).toBe('I picked the closest template to your words; change it if it is wrong.')
    })

    it('owns up to having nothing to go on, and picks the newest template', async () => {
      await mountChat()
      await send('Something about taxes')

      expect(lastPlan().template).toBe('Club equipment request')
      expect(lastPlan().hint).toBe('No template name matches your words, so I picked your newest one; change it if it is wrong.')
    })

    it('says it is not sure the request is about a document, instead of claiming it, when no template matches at all', async () => {
      await mountChat()
      await send('Something about taxes')

      expect(log()).toContain("I'm not sure that's something to fill in, but if it is, here's my best guess:")
      expect(log()).not.toContain('I can do that in a new document.')
    })

    it('still hedges when there is only one template to guess from', async () => {
      await mountChat({ templates: [CLUB] })
      await send('What is my upload size limit?')

      expect(lastPlan().template).toBe('Club equipment request')
      expect(choices()).toEqual(['Start the document'])
      expect(log()).toContain("I'm not sure that's something to fill in, but if it is, here's my best guess:")
    })

    it('speaks with its usual confidence once the person has picked the template themselves, even with nothing to go on', async () => {
      await mountChat()
      await send('Something about taxes')
      await wrapper.get('.chat-plan__change').trigger('click')

      await choose('Flowing meeting minutes')

      expect(lastPlan().template).toBe('Flowing meeting minutes')
      expect(log()).toContain('Here is my updated plan:')
    })

    it('never suggests a template that is in the Trash Bin or not finished', async () => {
      await mountChat({
        templates: [template(1, 'Trashed minutes', { trashedAt: '2026-10-01T10:00:00Z' }), template(2, 'Draft minutes', { currentActiveVersionId: null }), TABLE_LED, CLUB],
      })
      await send('Make minutes')

      expect(lastPlan().template).toBe('Table-led meeting minutes')
      expect(choices()).toEqual(['Start the document', 'Use Club equipment request instead'])
    })

    it('offers no other template, and no Change, when there is only one to use', async () => {
      await mountChat({ templates: [FLOWING] })
      await send('Make minutes')

      expect(choices()).toEqual(['Start the document'])
      expect(wrapper.find('.chat-plan__change').exists()).toBe(false)
      expect(lastPlan().hint).toBeNull()
    })

    it('says so, and links to Home, when there is no template to plan with, and makes nothing', async () => {
      await mountChat({ templates: [] })
      await send('Make minutes')

      expect(wrapper.find('.chat-plan').exists()).toBe(false)
      expect(log()).toContain('You don’t have a template to start from yet, so I have nothing to plan with.')
      expect(wrapper.get('.chat__log a[href="/"]').text()).toBe('Upload a form on Home')
      expect(createDocument).not.toHaveBeenCalled()
    })

    it('waits for the templates when they have not arrived, and says it is looking', async () => {
      const arriving = deferred<TemplateResponse[]>()
      await mountChat()
      useTemplatesStore().$patch({ status: 'idle', templates: [] })
      vi.mocked(listTemplates).mockReturnValue(arriving.promise)

      await send('Make minutes')
      expect(log()).toContain('Looking at your templates…')
      expect(wrapper.find('.chat-plan').exists()).toBe(false)

      arriving.resolve([FLOWING, TABLE_LED])
      await flushPromises()
      expect(log()).not.toContain('Looking at your templates…')
      expect(lastPlan().template).toBe('Flowing meeting minutes')
    })

    it('says when the templates could not be read, and plans once Try again can read them', async () => {
      await mountChat()
      useTemplatesStore().$patch({ status: 'error', templates: [] })
      vi.mocked(listTemplates).mockRejectedValueOnce(new Error('down'))

      await send('Make minutes')
      expect(log()).toContain('I could not look at your templates just now, so I cannot suggest one. Try again in a moment.')
      expect(wrapper.find('.chat-plan').exists()).toBe(false)

      vi.mocked(listTemplates).mockResolvedValue([FLOWING, TABLE_LED])
      await buttonNamed('Try again').trigger('click')
      await flushPromises()

      expect(lastPlan().template).toBe('Flowing meeting minutes')
      // The one request was asked once: Try again planned it, it did not say it a second time.
      expect(wrapper.findAll('.chat__log .msg--user')).toHaveLength(1)
      expect(wrapper.find('.chat__retry').exists()).toBe(false)
    })
  })

  describe('answering a plan', () => {
    it('plans again with the other template when it is chosen, saying what was chosen, and no longer guesses', async () => {
      await mountChat()
      await send(REQUEST)

      await choose('Use Table-led meeting minutes instead')

      expect(log()).toContain('You said: Use Table-led meeting minutes instead')
      expect(log()).toContain('Here is my updated plan:')
      expect(lastPlan().template).toBe('Table-led meeting minutes')
      expect(lastPlan().hint).toBeNull()
      expect(choices()).toEqual(['Start the document', 'Use Flowing meeting minutes instead'])
      // Only the plan that can still be answered offers choices.
      expect(wrapper.findAll('.chat__log .choices')).toHaveLength(1)
      expect(wrapper.findAll('.chat__log .chat-plan__change')).toHaveLength(1)
    })

    it('takes the line typed beside "Other:" as a new request, and keeps the file', async () => {
      acceptUploads()
      await mountChat()
      await attach()
      await send(REQUEST)

      await wrapper.get('.chat__log .choices__other-input').setValue('Rather the club equipment request')
      await wrapper.get('.chat__log .choices__other').trigger('submit')
      await flushPromises()

      expect(log()).toContain('You said: Rather the club equipment request')
      expect(lastPlan().template).toBe('Club equipment request')
      expect(lastPlan().reads).toBe('minutes.txt, and nothing else')
      expect(wrapper.findAll('.chat__log .choices')).toHaveLength(1)
    })

    it('puts focus on the first choice of the plan that replaces the one answered', async () => {
      await mountChat()
      await send(REQUEST)

      await choose('Use Table-led meeting minutes instead')

      expect((document.activeElement as HTMLElement).textContent).toContain('Start the document')
      expect(document.activeElement?.closest('.chat__log')).not.toBeNull()
    })

    it('opens every other template on Change, and plans with the one chosen', async () => {
      await mountChat()
      await send(REQUEST)

      const change = wrapper.get('.chat-plan__change')
      expect(change.text()).toBe('Change template')
      expect(change.attributes('aria-expanded')).toBe('false')
      await change.trigger('click')

      expect(change.attributes('aria-expanded')).toBe('true')
      expect(wrapper.get('.chat-plan__picker-title').text()).toBe('Which template should I use?')
      expect(choices()).toEqual(['Table-led meeting minutes', 'Club equipment request'])
      expect((document.activeElement as HTMLElement).textContent).toContain('Table-led meeting minutes')

      await choose('Club equipment request')

      expect(log()).toContain('You said: Use Club equipment request')
      expect(lastPlan().template).toBe('Club equipment request')
      expect(choices()).toEqual(['Start the document', 'Use Flowing meeting minutes instead'])
    })

    it('goes back to the usual choices, with focus on Change, when the template is kept', async () => {
      await mountChat()
      await send(REQUEST)
      await wrapper.get('.chat-plan__change').trigger('click')

      await buttonNamed('Keep Flowing meeting minutes').trigger('click')
      await flushPromises()

      expect(choices()).toEqual(['Start the document', 'Use Table-led meeting minutes instead'])
      expect(wrapper.get('.chat-plan__change').attributes('aria-expanded')).toBe('false')
      expect(document.activeElement).toBe(wrapper.get('.chat-plan__change').element)
      expect(wrapper.findAll('.chat-plan')).toHaveLength(1)
    })

    it('closes the other templates on Escape, with focus back on Change', async () => {
      await mountChat()
      await send(REQUEST)
      await wrapper.get('.chat-plan__change').trigger('click')

      await wrapper.get('.chat-plan__picker .choices__option').trigger('keydown', { key: 'Escape' })
      await flushPromises()

      expect(choices()).toEqual(['Start the document', 'Use Table-led meeting minutes instead'])
      expect(document.activeElement).toBe(wrapper.get('.chat-plan__change').element)
    })

    it('shows an older plan as it was said, without choices that no longer apply', async () => {
      await mountChat()
      await send(REQUEST)
      await send('Rather the club equipment request')

      expect(wrapper.findAll('.chat-plan')).toHaveLength(2)
      expect(wrapper.findAll('.chat__log .choices')).toHaveLength(1)
      expect(wrapper.findAll('.chat__log .chat-plan__change')).toHaveLength(1)
    })
  })

  describe('adding a file with +', () => {
    it('uploads, scans and reads the file as the document page does, and holds it: nothing is attached to a document yet', async () => {
      acceptUploads({ displayFilename: 'Oct 6 meeting transcript.txt' })
      await mountChat()

      await attach(textFile('Oct 6 meeting transcript.txt', 6139))

      expect(allocateUpload).toHaveBeenCalledWith(7, 'Oct 6 meeting transcript.txt')
      expect(uploadArtifactContent).toHaveBeenCalledWith(7, 5, expect.any(File))
      expect(completeUpload).toHaveBeenCalledWith(7, 5)
      expect(extractArtifact).toHaveBeenCalledWith(7, 5)
      expect(attachDocumentSource).not.toHaveBeenCalled()
      expect(createDocument).not.toHaveBeenCalled()
      const chip = wrapper.get('.chat__log .source-chip')
      expect(chip.get('.source-chip__name').text()).toBe('You attached Oct 6 meeting transcript.txt')
      expect(words(chip.get('.source-chip__meta'))).toBe('Text file • , 6 KB')
      // It sits on the person's side of the conversation, and the opening gives way to it.
      expect(wrapper.find('.chat__start').exists()).toBe(false)
    })

    it('says "File", not "Text file", when the server found something else in the bytes', async () => {
      acceptUploads({ detectedMediaType: 'PDF' })
      await mountChat()

      await attach(textFile('minutes.pdf'))

      expect(words(wrapper.get('.source-chip__meta'))).toContain('File')
      expect(wrapper.get('.source-chip__meta').text()).not.toContain('Text file')
    })

    it('shows the chip while the upload runs, and holds the + until it is done', async () => {
      acceptUploads()
      const uploading = deferred<ArtifactResponse>()
      vi.mocked(uploadArtifactContent).mockReturnValue(uploading.promise)
      await mountChat()

      await attach()
      expect(wrapper.get('.source-chip__meta').text()).toContain('Uploading…')
      expect(wrapper.get('.chat__attach').attributes('aria-disabled')).toBe('true')

      uploading.resolve({ id: 5, status: 'SCANNING' })
      await flushPromises()
      expect(wrapper.get('.source-chip__meta').text()).toContain('Text file')
      expect(wrapper.get('.chat__attach').attributes('aria-disabled')).toBe('false')
    })

    it('puts focus in the message box once the file is in, so what is needed can be said next', async () => {
      acceptUploads()
      await mountChat()
      wrapper.get<HTMLButtonElement>('.chat__attach').element.focus()

      await attach()

      expect(document.activeElement).toBe(wrapper.get('#chat-composer').element)
    })

    it('leaves focus where it was when it had moved on while the file was on its way', async () => {
      acceptUploads()
      const uploading = deferred<ArtifactResponse>()
      vi.mocked(uploadArtifactContent).mockReturnValue(uploading.promise)
      await mountChat()
      await attach()
      wrapper.get<HTMLButtonElement>('button[type="submit"]').element.focus()

      uploading.resolve({ id: 5, status: 'SCANNING' })
      await flushPromises()

      expect(document.activeElement).toBe(wrapper.get('button[type="submit"]').element)
    })

    it('plans with the file it holds', async () => {
      acceptUploads()
      await mountChat()
      await attach()

      await send(REQUEST)

      expect(lastPlan().reads).toBe('minutes.txt, and nothing else')
    })

    it('makes a new plan, with the template as it stood, when a file is added after one was shown', async () => {
      acceptUploads()
      await mountChat()
      await send(REQUEST)
      await choose('Use Table-led meeting minutes instead')
      expect(lastPlan().reads).toBe('No source yet: you can add one in the document')

      await attach()

      expect(log()).toContain('Thanks, I have it. Here is my updated plan:')
      expect(lastPlan().template).toBe('Table-led meeting minutes')
      expect(lastPlan().hint).toBeNull()
      expect(lastPlan().reads).toBe('minutes.txt, and nothing else')
      expect(wrapper.findAll('.chat__log .choices')).toHaveLength(1)
    })

    it('waits for a file still on its way before planning a request typed meanwhile', async () => {
      acceptUploads()
      const uploading = deferred<ArtifactResponse>()
      vi.mocked(uploadArtifactContent).mockReturnValue(uploading.promise)
      await mountChat()
      await attach()

      await send(REQUEST)
      expect(log()).toContain(`You said: ${REQUEST}`)
      expect(wrapper.find('.chat-plan').exists()).toBe(false)

      uploading.resolve({ id: 5, status: 'SCANNING' })
      await flushPromises()
      expect(lastPlan().reads).toBe('minutes.txt, and nothing else')
      expect(wrapper.findAll('.chat-plan')).toHaveLength(1)
    })

    it.each([
      ['a scan that flagged it', { status: 'REJECTED', rejectionReason: 'MALWARE_DETECTED' }, 'That file was not attached: the malware scan flagged it.'],
      ['a kind of file Brownie cannot use', { status: 'REJECTED', rejectionReason: 'UNSUPPORTED_MEDIA_TYPE' }, 'That file was not attached: Brownie cannot use that kind of file.'],
      ['an upload that took too long', { status: 'REJECTED', rejectionReason: 'EXPIRED_ABANDONED_UPLOAD' }, 'That file was not attached: the upload took too long to finish.'],
      ['a scan that did not finish', { status: 'QUARANTINED' }, 'That file was not attached: Brownie could not finish checking it.'],
    ] as const)('takes the chip back and says why for %s', async (_name, finished, sentence) => {
      acceptUploads()
      vi.mocked(completeUpload).mockResolvedValue({ id: 5, ...finished })
      await mountChat()

      await attach()

      expect(wrapper.find('.source-chip').exists()).toBe(false)
      expect(log()).toContain(sentence)
      expect(extractArtifact).not.toHaveBeenCalled()
    })

    it('names the limit when the file is over it', async () => {
      acceptUploads()
      vi.mocked(uploadArtifactContent).mockRejectedValue(problem(413, 'CONTENT_TOO_LARGE', 'Too large.'))
      vi.mocked(getCapabilities).mockResolvedValue({ maxUploadBytes: 10 * 1024 * 1024 } as CapabilitiesResponse)
      await mountChat()

      await attach(textFile('big.txt', 20 * 1024 * 1024))

      expect(log()).toContain('That file was not attached: it is larger than the 10 MB upload limit.')
      expect(wrapper.find('.source-chip').exists()).toBe(false)
    })

    it('asks for plain text when the server will not take the kind of file', async () => {
      acceptUploads()
      vi.mocked(uploadArtifactContent).mockRejectedValue(problem(415, 'UNSUPPORTED_MEDIA_TYPE', 'Not a document.'))
      await mountChat()

      await attach(textFile('picture.png'))

      expect(log()).toContain('That file was not attached: Brownie cannot use that kind of file as a source. Attach a plain-text (.txt) file.')
    })

    it('says Brownie could not be reached when no answer came back', async () => {
      acceptUploads()
      vi.mocked(allocateUpload).mockRejectedValue(new TypeError('Failed to fetch'))
      await mountChat()

      await attach()

      expect(log()).toContain('That file was not attached. Brownie could not be reached. Check your connection, then try again.')
    })

    it('leaves a plan as it was when the file is refused, so it can still be answered', async () => {
      acceptUploads()
      vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'REJECTED', rejectionReason: 'MALWARE_DETECTED' })
      await mountChat()
      await send(REQUEST)

      await attach()

      expect(log()).toContain('That file was not attached: the malware scan flagged it.')
      expect(wrapper.findAll('.chat-plan')).toHaveLength(1)
      expect(choices()).toEqual(['Start the document', 'Use Table-led meeting minutes instead'])
      expect(wrapper.find('.chat-plan__change').exists()).toBe(true)
    })

    it('ignores a press of + while a file is on its way', async () => {
      acceptUploads()
      vi.mocked(uploadArtifactContent).mockReturnValue(deferred<ArtifactResponse>().promise)
      await mountChat()
      await attach()
      const click = vi.spyOn(wrapper.get('input[type="file"]').element as HTMLInputElement, 'click')

      await wrapper.get('.chat__attach').trigger('click')

      expect(click).not.toHaveBeenCalled()
    })
  })

  describe('starting the document', () => {
    it('makes the document from the template, attaches the file, and opens it with the request', async () => {
      acceptUploads()
      acceptDocuments()
      await mountChat()
      await attach()
      await send(REQUEST)
      const push = vi.spyOn(router, 'push')

      await choose('Start the document')

      expect(createDocument).toHaveBeenCalledTimes(1)
      expect(createDocument).toHaveBeenCalledWith(7, expect.any(String), {
        title: documentTitleFor('Flowing meeting minutes'),
        templateId: 1,
        templateVersionId: 10,
        fields: {},
        initialRevisionReason: 'Started from a request in the chat.',
      })
      expect(attachDocumentSource).toHaveBeenCalledWith(7, 42, 5)
      expect(router.currentRoute.value.fullPath).toBe('/documents/42')
      expect(readDocumentHandoff()).toEqual({
        attachedSources: [SOURCE],
        sourceWarning: null,
        formNotes: [],
        request: { text: REQUEST, sourceId: 3 },
      })
      // History keeps only plain data. What the page holds is wrapped, and a wrapped object makes the router load
      // the address afresh instead, losing the request and the file on the way.
      const pushed = push.mock.calls[0]![0] as { state: unknown }
      expect(() => structuredClone(pushed.state)).not.toThrow()
    })

    it('starts without a file when none was added, and says that nothing was read', async () => {
      acceptDocuments()
      await mountChat()
      await send(REQUEST)

      await choose('Start the document')

      expect(attachDocumentSource).not.toHaveBeenCalled()
      expect(router.currentRoute.value.fullPath).toBe('/documents/42')
      expect(readDocumentHandoff()).toEqual({
        attachedSources: [],
        sourceWarning: null,
        formNotes: [],
        request: { text: REQUEST, sourceId: null },
      })
    })

    it('starts from the template that was chosen, not the one first suggested', async () => {
      acceptDocuments()
      await mountChat()
      await send(REQUEST)
      await choose('Use Table-led meeting minutes instead')

      await choose('Start the document')

      expect(createDocument).toHaveBeenCalledWith(
        7,
        expect.any(String),
        expect.objectContaining({ templateId: 2, templateVersionId: 20, title: documentTitleFor('Table-led meeting minutes') }),
      )
    })

    it('says what it is doing while it works, and shows the choice as the person made it', async () => {
      acceptUploads()
      const creating = deferred<DocumentResponse>()
      const attaching = deferred<DocumentSourceResponse>()
      vi.mocked(createDocument).mockReturnValue(creating.promise)
      vi.mocked(attachDocumentSource).mockReturnValue(attaching.promise)
      await mountChat()
      await attach()
      await send(REQUEST)

      await choose('Start the document')
      expect(log()).toContain('You said: Start the document')
      expect(log()).toContain(`Starting “${documentTitleFor('Flowing meeting minutes')}”…`)
      expect(wrapper.find('.chat__log .choices').exists()).toBe(false)
      expect(document.activeElement).toBe(wrapper.get('.chat__start-turn').element)

      creating.resolve({ id: 42 } as DocumentResponse)
      await flushPromises()
      expect(log()).toContain('Attaching minutes.txt…')

      attaching.resolve(SOURCE)
      await flushPromises()
      expect(router.currentRoute.value.fullPath).toBe('/documents/42')
    })

    it('takes no other request while the document is being made', async () => {
      acceptDocuments()
      vi.mocked(createDocument).mockReturnValue(deferred<DocumentResponse>().promise)
      await mountChat()
      await send(REQUEST)
      await choose('Start the document')

      await send('One more thing')

      expect(wrapper.findAll('.chat__log .msg--user')).toHaveLength(2)
      expect(wrapper.get('form.composer button[type="submit"]').attributes('aria-disabled')).toBe('true')
    })

    it('says why the document could not be made, puts focus on Try again, and then makes only one', async () => {
      acceptDocuments()
      vi.mocked(createDocument).mockRejectedValueOnce(problem(503, 'DATABASE_UNAVAILABLE', 'Brownie is briefly unavailable.'))
      await mountChat()
      await send(REQUEST)

      await choose('Start the document')

      expect(log()).toContain('I could not start the document. Brownie is briefly unavailable.')
      expect(document.activeElement).toBe(wrapper.get('.chat__retry').element)
      expect(router.currentRoute.value.fullPath).toBe('/chat')

      await wrapper.get('.chat__retry').trigger('click')
      await flushPromises()

      // The same key both times, so an answer that was lost on the way cannot have made a first document.
      const keys = vi.mocked(createDocument).mock.calls.map((call) => call[1])
      expect(keys).toHaveLength(2)
      expect(keys[0]).toBe(keys[1])
      expect(router.currentRoute.value.fullPath).toBe('/documents/42')
    })

    it('does not make a second document when only the file could not be attached', async () => {
      acceptUploads()
      acceptDocuments()
      vi.mocked(attachDocumentSource).mockRejectedValueOnce(new TypeError('Failed to fetch'))
      await mountChat()
      await attach()
      await send(REQUEST)

      await choose('Start the document')

      expect(log()).toContain(
        `I started “${documentTitleFor('Flowing meeting minutes')}”, but could not attach minutes.txt. Brownie could not be reached. Check your connection, then try again.`,
      )
      expect(router.currentRoute.value.fullPath).toBe('/chat')
      expect(wrapper.text()).toContain('Open the document without it')

      await wrapper.get('.chat__retry').trigger('click')
      await flushPromises()

      expect(createDocument).toHaveBeenCalledTimes(1)
      expect(attachDocumentSource).toHaveBeenCalledTimes(2)
      expect(router.currentRoute.value.fullPath).toBe('/documents/42')
      expect(readDocumentHandoff()?.request).toEqual({ text: REQUEST, sourceId: 3 })
    })

    it('opens the document without the file when asked, and warns there that it was left out', async () => {
      acceptUploads()
      acceptDocuments()
      vi.mocked(attachDocumentSource).mockRejectedValue(new TypeError('Failed to fetch'))
      await mountChat()
      await attach()
      await send(REQUEST)
      await choose('Start the document')

      await buttonNamed('Open the document without it').trigger('click')
      await flushPromises()

      expect(attachDocumentSource).toHaveBeenCalledTimes(1)
      expect(createDocument).toHaveBeenCalledTimes(1)
      expect(router.currentRoute.value.fullPath).toBe('/documents/42')
      expect(readDocumentHandoff()).toEqual({
        attachedSources: [],
        sourceWarning: 'minutes.txt was not attached, so Brownie has nothing to read yet. You can add it again with Add a source.',
        formNotes: [],
        request: { text: REQUEST, sourceId: null },
      })
    })

    it('does not let a file be added once the document exists and only its file is missing', async () => {
      acceptUploads()
      acceptDocuments()
      vi.mocked(attachDocumentSource).mockRejectedValue(new TypeError('Failed to fetch'))
      await mountChat()
      await attach()
      await send(REQUEST)

      await choose('Start the document')

      expect(wrapper.get('.chat__attach').attributes('aria-disabled')).toBe('true')
    })

    it('drops a template that went to the Trash Bin from somewhere else, and plans again with the rest', async () => {
      acceptDocuments()
      vi.mocked(createDocument).mockRejectedValueOnce(problem(409, 'TEMPLATE_TRASHED', 'This template is in the Trash Bin.'))
      await mountChat()
      await send(REQUEST)

      await choose('Start the document')

      expect(log()).toContain('“Flowing meeting minutes” is in the Trash Bin now, so I cannot start a document from it. Nothing was made.')
      expect(wrapper.find('.chat__retry').exists()).toBe(false)
      expect(useTemplatesStore().usable.map((usable) => usable.id)).toEqual([2, 3])
      expect(log()).toContain('Here is a new plan:')
      expect(lastPlan().template).toBe('Table-led meeting minutes')
      expect(choices()).toEqual(['Start the document', 'Use Club equipment request instead'])
      expect(router.currentRoute.value.fullPath).toBe('/chat')

      await choose('Start the document')

      expect(createDocument).toHaveBeenLastCalledWith(7, expect.any(String), expect.objectContaining({ templateId: 2 }))
      expect(router.currentRoute.value.fullPath).toBe('/documents/42')
    })

    it('says so, and offers no plan, when the template that went was the only one', async () => {
      vi.mocked(createDocument).mockRejectedValue(problem(404, 'NOT_FOUND', 'Template 1 was not found in this workspace.'))
      await mountChat({ templates: [FLOWING] })
      await send('Make minutes')

      await choose('Start the document')

      expect(log()).toContain('“Flowing meeting minutes” is not there any more, so I cannot start a document from it. Nothing was made.')
      expect(log()).toContain('You don’t have a template to start from yet, so I have nothing to plan with.')
      expect(wrapper.findAll('.chat-plan')).toHaveLength(1)
      expect(wrapper.find('.chat__log .choices').exists()).toBe(false)
    })

    it('opens nothing over a page the person has gone to, but still finishes what it started', async () => {
      acceptUploads()
      const creating = deferred<DocumentResponse>()
      vi.mocked(createDocument).mockReturnValue(creating.promise)
      vi.mocked(attachDocumentSource).mockResolvedValue(SOURCE)
      await mountChat()
      await attach()
      await send(REQUEST)
      const push = vi.spyOn(router, 'push')
      await choose('Start the document')

      wrapper.unmount()
      creating.resolve({ id: 42 } as DocumentResponse)
      await flushPromises()

      expect(attachDocumentSource).toHaveBeenCalledWith(7, 42, 5)
      expect(push).not.toHaveBeenCalled()
      // The address, since unmounting the page resets what the router itself reports.
      expect(window.location.pathname).toBe('/chat')
    })

    it('says so, and offers to try again, when the document was made but would not open', async () => {
      acceptDocuments()
      await mountChat()
      await send(REQUEST)
      vi.spyOn(router, 'push').mockRejectedValueOnce(new Error('chunk failed'))

      await choose('Start the document')

      expect(log()).toContain(`I started “${documentTitleFor('Flowing meeting minutes')}”, but it did not open.`)
      await wrapper.get('.chat__retry').trigger('click')
      await flushPromises()
      expect(createDocument).toHaveBeenCalledTimes(1)
      expect(router.currentRoute.value.fullPath).toBe('/documents/42')
    })
  })

  describe('the message box', () => {
    it('sends on Enter, starts a new line on Shift+Enter, and leaves a key that finishes a composed character alone', async () => {
      await mountChat()
      const box = wrapper.get('#chat-composer')
      await box.setValue('Make minutes')

      await box.trigger('keydown', { key: 'Enter', shiftKey: true })
      await box.trigger('keydown', { key: 'Enter', isComposing: true })
      await flushPromises()
      expect(wrapper.find('.msg--user').exists()).toBe(false)

      await box.trigger('keydown', { key: 'Enter' })
      await flushPromises()
      expect(log()).toContain('You said: Make minutes')
      expect((box.element as HTMLTextAreaElement).value).toBe('')
    })

    it('does not send an empty line, and shows the send button as unavailable until there are words', async () => {
      await mountChat()
      const send = wrapper.get('form.composer button[type="submit"]')

      await wrapper.get('form.composer').trigger('submit')
      expect(wrapper.find('.msg--user').exists()).toBe(false)
      expect(send.attributes('aria-disabled')).toBe('true')

      await wrapper.get('#chat-composer').setValue('  Make minutes ')
      expect(send.attributes('aria-disabled')).toBe('false')
    })

    it('keeps focus in the box after a request is sent from it', async () => {
      await mountChat()
      wrapper.get<HTMLTextAreaElement>('#chat-composer').element.focus()

      await send(REQUEST)

      expect(document.activeElement).toBe(wrapper.get('#chat-composer').element)
    })

    it('brings each new turn into view above the pinned box, or its top when it is taller than what is left', async () => {
      const scrollIntoView = vi.fn()
      Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { value: scrollIntoView, configurable: true })
      try {
        await mountChat()
        await send(REQUEST)
        expect(scrollIntoView).toHaveBeenLastCalledWith(expect.objectContaining({ block: 'end' }))

        Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { get: () => 5000, configurable: true })
        await choose('Use Table-led meeting minutes instead')
        expect(scrollIntoView).toHaveBeenLastCalledWith(expect.objectContaining({ block: 'start' }))
      } finally {
        Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
        Reflect.deleteProperty(HTMLElement.prototype, 'offsetHeight')
      }
    })
  })

  describe('the pinned message box', () => {
    it('keeps the strip under it out of what counts as in view, as tall as it is, and gives the page back on leaving', async () => {
      const page = document.documentElement.style
      const set = vi.spyOn(page, 'setProperty')
      const remove = vi.spyOn(page, 'removeProperty')
      const computed = vi.spyOn(window, 'getComputedStyle').mockReturnValue({ position: 'sticky' } as CSSStyleDeclaration)
      Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { get: () => 150, configurable: true })
      try {
        await mountChat()
        expect(set).toHaveBeenLastCalledWith('scroll-padding-block-end', '150px')

        wrapper.unmount()
        expect(remove).toHaveBeenLastCalledWith('scroll-padding-block-end')
      } finally {
        computed.mockRestore()
        Reflect.deleteProperty(HTMLElement.prototype, 'offsetHeight')
      }
    })

    it('leaves the page alone where the box is not pinned', async () => {
      const set = vi.spyOn(document.documentElement.style, 'setProperty')

      await mountChat()

      expect(set).not.toHaveBeenCalled()
    })
  })

  describe('accessibility', () => {
    it('passes a scan with a file, a plan and its choices on the page', async () => {
      acceptUploads()
      await mountChat()
      await attach()
      await send(REQUEST)

      expect(await axe(wrapper.element)).toHaveNoViolations()
    })

    it('passes a scan with the other templates on offer and with a failure to answer', async () => {
      acceptUploads()
      acceptDocuments()
      vi.mocked(createDocument).mockRejectedValueOnce(problem(503, 'DATABASE_UNAVAILABLE', 'Brownie is briefly unavailable.'))
      await mountChat()
      await attach()
      await send(REQUEST)
      await wrapper.get('.chat-plan__change').trigger('click')
      expect(await axe(wrapper.element)).toHaveNoViolations()

      await choose('Club equipment request')
      await choose('Start the document')
      expect(wrapper.find('.chat__retry').exists()).toBe(true)
      expect(await axe(wrapper.element)).toHaveNoViolations()
    })
  })
})
