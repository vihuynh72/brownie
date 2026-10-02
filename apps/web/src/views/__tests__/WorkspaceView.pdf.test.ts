import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { RouterView, createRouter, createWebHistory } from 'vue-router'
import WorkspaceView from '@/views/WorkspaceView.vue'
import { useSessionStore } from '@/stores/session'

/*
 * A PDF form in the workspace: its own page view in place of the Word page, the spot bar's questions
 * about a box, the changes sent with the typing saved first, their chat lines with Undo, the chat's
 * "here" picked on a page, and an export that is a PDF only. The page view itself has tests of its own.
 */

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    approveExport: vi.fn(),
    changeFillSpots: vi.fn(),
    executeAssist: vi.fn(),
    exportDocument: vi.fn(),
    getCapabilities: vi.fn(),
    getDocument: vi.fn(),
    getLatestCompilation: vi.fn(),
    getLatestExportApproval: vi.fn(),
    getLatestExportReceipt: vi.fn(),
    getLatestValidation: vi.fn(),
    getTemplateLayout: vi.fn(),
    getTemplateVersion: vi.fn(),
    interpretAssist: vi.fn(),
    keepFillSpot: vi.fn(),
    listDocumentSources: vi.fn(),
    listGenerationRuns: vi.fn(),
    listTemplateVersionRules: vi.fn(),
    patchDocumentContent: vi.fn(),
    restoreRevision: vi.fn(),
    suggestBox: vi.fn(),
    validateDocument: vi.fn(),
  }
})

vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))

// PDF.js needs a canvas and a worker jsdom does not have: a fake answers the calls the page view makes.
vi.mock('pdfjs-dist/legacy/build/pdf.mjs', () => {
  const render = () => ({ promise: Promise.resolve(), cancel: () => {} })
  const getPage = async () => ({ getViewport: ({ scale }: { scale: number }) => ({ width: 612 * scale, height: 792 * scale }), render })
  return {
    GlobalWorkerOptions: { workerSrc: '' },
    AnnotationMode: { ENABLE: 1 },
    version: '6.3.289',
    getDocument: () => ({ promise: Promise.resolve({ numPages: 1, getPage }), destroy: async () => {} }),
  }
})
vi.mock('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url', () => ({ default: '/fake-worker.mjs' }))

import {
  ApiRequestError,
  approveExport,
  changeFillSpots,
  executeAssist,
  exportDocument,
  getCapabilities,
  getDocument,
  getLatestCompilation,
  getLatestExportApproval,
  getLatestExportReceipt,
  getLatestValidation,
  getTemplateLayout,
  getTemplateVersion,
  interpretAssist,
  keepFillSpot,
  listDocumentSources,
  listGenerationRuns,
  listTemplateVersionRules,
  patchDocumentContent,
  restoreRevision,
  suggestBox,
  validateDocument,
  type DocumentResponse,
  type DocumentRevisionResponse,
  type TemplateLayoutResponse,
  type TemplateVersionResponse,
} from '@/api/client'
import { axe } from '@/test/axe'

const CREATED_AT = '2026-09-30T00:00:00Z'

type Fields = DocumentRevisionResponse['fields']
type Spot = NonNullable<TemplateLayoutResponse['pdf']>['spots'][number]

function revision(id: number, fields: Fields = {}, templateVersionId = 12): DocumentRevisionResponse {
  return { id, revisionNumber: id, fields, contentHash: String(id).repeat(64).slice(0, 64), editReason: 'x', createdAt: CREATED_AT, templateVersionId }
}

function documentOn(versionId: number, current: DocumentRevisionResponse): DocumentResponse {
  return {
    id: 1,
    title: 'Membership application',
    templateId: 4,
    templateVersionId: versionId,
    templateLatestVersionId: versionId,
    currentRevisionId: current.id,
    createdAt: CREATED_AT,
    currentRevision: current,
  }
}

const FULL_NAME: Spot = {
  fieldId: 'full.name',
  label: 'Full name',
  origin: 'FORM',
  pageNumber: 1,
  box: { x: 140, y: 138, width: 300, height: 16 },
  style: null,
  multiline: false,
  overflow: 'SHRINK_TO_FIT',
  bindingKind: 'ACROFORM_FIELD',
}
const COMPANY: Spot = {
  fieldId: 'company',
  label: 'Company',
  origin: 'ADDED_BY_PERSON',
  pageNumber: 1,
  box: { x: 140, y: 98, width: 150, height: 14 },
  style: { font: 'SANS', bold: false, sizePt: 11 },
  multiline: false,
  overflow: 'SHRINK_TO_FIT',
  bindingKind: 'PAGE_BOX',
}

function pdfLayout(versionId: number, spots: Spot[]): TemplateLayoutResponse {
  return {
    templateId: 4,
    versionId,
    kind: 'PDF',
    parserVersion: 'brownie-pdf-form-v1',
    parts: [],
    unplacedFieldIds: [],
    pdf: {
      sourceArtifactId: 31,
      pages: [
        {
          pageNumber: 1,
          width: 612,
          height: 792,
          rotation: 0,
          hasText: true,
          lines: [
            { index: 0, text: 'Company:', x: 72, y: 100, w: 60, h: 12 },
            { index: 1, text: 'Full name:', x: 72, y: 140, w: 60, h: 12 },
          ],
        },
      ],
      spots,
    },
  }
}

function version(id: number, spots: Spot[]): TemplateVersionResponse {
  return {
    id,
    templateId: 4,
    versionNumber: id - 11,
    sourceArtifactId: 31,
    extractionVersionId: null,
    kind: 'PDF',
    status: 'ACTIVATED',
    fields: spots.map((spot) => ({
      fieldId: spot.fieldId,
      type: 'TEXT',
      cardinality: 'SCALAR',
      requiredness: 'OPTIONAL',
      bindingKind: spot.bindingKind,
      label: spot.label,
      origin: spot.origin,
    })),
    createdAt: CREATED_AT,
    activatedAt: CREATED_AT,
    acceptedFieldIds: [],
  } as unknown as TemplateVersionResponse
}

/** What the server holds: the version the document is on and its current revision; each test moves it on. */
let server: { versionId: number; revision: DocumentRevisionResponse }
const layouts = new Map<number, TemplateLayoutResponse>()
const versions = new Map<number, TemplateVersionResponse>()

function problem(status: number, code: string, extra: Record<string, unknown> = {}): ApiRequestError {
  return new ApiRequestError(status, { status, title: 'Refused', code, correlationId: 'c', fields: [], recoveryActions: [], ...extra })
}

const Host = defineComponent({ name: 'Host', render: () => h('main', { id: 'main-content' }, [h(RouterView)]) })
const Blank = defineComponent({ render: () => h('div') })
const Stub = (name: string) => defineComponent({ name, props: ['workspaceId', 'documentId', 'receipt', 'unsavedWork', 'documentTitle', 'suggestedDate'], render: () => h('div') })

let page: VueWrapper | null = null

async function settle(): Promise<void> {
  for (let round = 0; round < 6; round++) await new Promise((resolve) => setTimeout(resolve, 0))
}

async function mountPage(): Promise<VueWrapper> {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: Blank },
      { path: '/documents/:id', component: WorkspaceView, props: (route) => ({ documentId: Number(route.params.id) }) },
    ],
  })
  await router.push('/documents/1')
  await router.isReady()
  page = mount(Host, {
    attachTo: document.body,
    global: { plugins: [router], stubs: { DriveSavePanel: Stub('DriveSavePanel'), CalendarEventPanel: Stub('CalendarEventPanel') } },
  })
  // The PDF page view is loaded on demand, the first time a PDF form is shown.
  await vi.waitFor(
    () => {
      if (!document.querySelector('.pdf-form-page canvas')) throw new Error('The PDF page view has not loaded yet.')
    },
    { timeout: 5000 },
  )
  await settle()
  return page
}

function button(name: string, within = ''): HTMLButtonElement {
  const squash = (words: string) => words.replace(/\s+/g, '')
  const found = [...document.querySelectorAll<HTMLButtonElement>(`${within} button`.trim())].find(
    (candidate) => squash(candidate.textContent ?? '') === squash(name),
  )
  if (!found) throw new Error(`No button named ${name}`)
  return found
}

async function press(name: string, within = ''): Promise<void> {
  button(name, within).click()
  await settle()
}

async function focusSpot(id: string): Promise<void> {
  document.getElementById(id)!.focus()
  await settle()
}

function chatLines(): string[] {
  return [...document.querySelectorAll('.chat-line--brownie p')].map((line) => (line.textContent ?? '').replace(/\s+/g, ' ').trim())
}

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  setActivePinia(createPinia())
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, arrayBuffer: async () => new ArrayBuffer(8) })))
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({} as unknown as CanvasRenderingContext2D)
  vi.spyOn(window, 'scrollBy').mockImplementation(() => undefined)
  layouts.clear()
  versions.clear()
  layouts.set(12, pdfLayout(12, [FULL_NAME]))
  versions.set(12, version(12, [FULL_NAME]))
  server = { versionId: 12, revision: revision(1) }
  vi.mocked(getDocument).mockImplementation(async () => documentOn(server.versionId, server.revision))
  vi.mocked(getTemplateVersion).mockImplementation(async (_w, _t, versionId) => versions.get(versionId)!)
  vi.mocked(getTemplateLayout).mockImplementation(async (_w, _t, versionId) => layouts.get(versionId)!)
  vi.mocked(listTemplateVersionRules).mockResolvedValue([])
  vi.mocked(listDocumentSources).mockResolvedValue([])
  vi.mocked(listGenerationRuns).mockResolvedValue([])
  vi.mocked(getCapabilities).mockResolvedValue({ maxUploadBytes: 1024, uploadMediaTypes: [], assistSourceMediaTypes: [], templateMediaTypes: [], trashRetentionDays: 30 })
  for (const nothing of [getLatestCompilation, getLatestValidation, getLatestExportApproval, getLatestExportReceipt]) {
    vi.mocked(nothing).mockRejectedValue(new ApiRequestError(404, undefined))
  }
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
})

afterEach(() => {
  page?.unmount()
  page = null
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

/** The server's side of a box being added: version 13 has Company, and the document is on it. */
function companyAddedOnChange(): void {
  vi.mocked(changeFillSpots).mockImplementation(async () => {
    layouts.set(13, pdfLayout(13, [FULL_NAME, COMPANY]))
    versions.set(13, version(13, [FULL_NAME, COMPANY]))
    const previous = server.revision
    server = { versionId: 13, revision: revision(previous.id + 1, previous.fields, 13) }
    return {
      document: documentOn(13, server.revision),
      revision: server.revision,
      templateVersion: versions.get(13)!,
      previousRevisionId: previous.id,
      fieldIds: ['company'],
      otherDocumentsOnPreviousVersion: 0,
    }
  })
}

describe('WorkspaceView with a PDF form', () => {
  it('shows the form’s own pages, not a page drawn from Word text', async () => {
    await mountPage()

    expect(document.querySelector('canvas[role="img"]')?.getAttribute('aria-label')).toBe('Page 1 of 1')
    expect(document.querySelector('.document-page')).toBeNull()
    expect(document.getElementById('edit-full.name')).not.toBeNull()
    expect(button('Draw a box').getAttribute('aria-pressed')).toBe('false')
  })

  it('adds a box by keyboard with the typing saved first, says so in the chat, and undoes it', async () => {
    vi.mocked(patchDocumentContent).mockImplementation(async (_w, _d, body) => {
      server = { ...server, revision: revision(2, { 'full.name': { type: 'TEXT', cardinality: 'SCALAR', value: 'Nguyễn Văn An', evidenceSourceSpanIds: [], fieldState: null } }) }
      void body
      return server.revision
    })
    vi.mocked(suggestBox).mockResolvedValue({ box: COMPANY.box, style: COMPANY.style!, labelGuess: 'Company' })
    companyAddedOnChange()
    await mountPage()

    const name = document.getElementById('edit-full.name') as HTMLTextAreaElement
    name.value = 'Nguyễn Văn An'
    name.dispatchEvent(new Event('input'))
    await settle()

    await press('Add a fill spot')
    const lines = document.querySelector<HTMLSelectElement>('dialog select[size]')!
    lines.value = '0'
    lines.dispatchEvent(new Event('change'))
    await press('Next', 'dialog')
    expect(suggestBox).toHaveBeenCalledWith(7, 4, 12, { pageNumber: 1, lineIndex: 0 })
    await press('Add the fill spot', 'dialog')

    expect(vi.mocked(patchDocumentContent).mock.invocationCallOrder[0]!).toBeLessThan(vi.mocked(changeFillSpots).mock.invocationCallOrder[0]!)
    expect(changeFillSpots).toHaveBeenCalledWith(
      7,
      1,
      2,
      12,
      [
        {
          kind: 'ADD_BOX',
          pageNumber: 1,
          box: COMPANY.box,
          label: 'Company',
          type: 'TEXT',
          style: { font: 'SANS', bold: false, sizePt: 11 },
          overflow: 'SHRINK_TO_FIT',
        },
      ],
      expect.any(String),
    )
    expect(document.querySelector('dialog form')).toBeNull()
    expect(document.activeElement?.id).toBe('edit-company')
    expect(chatLines()).toContain('Added a fill spot for Company. New documents from this form will have it too.')

    vi.mocked(restoreRevision).mockImplementation(async () => {
      server = { versionId: 12, revision: revision(4, server.revision.fields, 12) }
      return { revision: server.revision, keptLockedFieldIds: [], droppedFieldIds: [] }
    })
    await press('Undo adding Company', '.chat-line')
    expect(restoreRevision).toHaveBeenCalledWith(7, 1, 2, 3, expect.any(String), 'Undid a fill spot change.')
    expect(chatLines()).toContain('Undone: Company is no longer a fill spot.')
    expect(document.getElementById('edit-company')).toBeNull()
  })

  it('keeps the dialog open with the reason a box was refused', async () => {
    vi.mocked(changeFillSpots).mockRejectedValue(problem(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'OFF_PAGE' }))
    vi.mocked(suggestBox).mockResolvedValue({ box: COMPANY.box, style: COMPANY.style!, labelGuess: 'Company' })
    await mountPage()

    await press('Add a fill spot')
    const lines = document.querySelector<HTMLSelectElement>('dialog select[size]')!
    lines.value = '0'
    lines.dispatchEvent(new Event('change'))
    await press('Next', 'dialog')
    await press('Add the fill spot', 'dialog')

    expect(document.querySelector('dialog [role="alert"]')?.textContent).toBe('That box is off the page.')
    expect(chatLines().some((line) => line.startsWith('Added'))).toBe(false)
  })

  it('scrolls a box being typed in clear of the bar about it, as a Word page does', async () => {
    await mountPage()
    const spot = document.getElementById('edit-full.name')!
    const box = (top: number, height: number) =>
      ({ top, left: 0, width: 300, height, right: 300, bottom: top + height, x: 0, y: top, toJSON: () => ({}) }) as DOMRect
    // The bar at the foot of the view, from 500 down; the box being typed in at 600, under it.
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.classList.contains('selection-bar')) return box(500, 200)
      if (this === spot) return box(600, 20)
      return box(0, 0)
    })
    await focusSpot('edit-full.name')

    // Just enough to show it above the bar, with a little room: 620 - 500 + 12.
    expect(window.scrollBy).toHaveBeenLastCalledWith({ top: 132 })
  })

  it('offers renaming, text size and removing for a box Brownie drew, and says a form’s own box keeps its look', async () => {
    layouts.set(12, pdfLayout(12, [FULL_NAME, COMPANY]))
    versions.set(12, version(12, [FULL_NAME, COMPANY]))
    await mountPage()

    await focusSpot('edit-company')
    const bar = document.querySelector('section.selection-bar')!
    expect([...bar.querySelectorAll('[aria-labelledby="selection-bar-spot-label"] button')].map((item) => (item.textContent ?? '').replace(/\s+/g, ' ').trim())).toEqual([
      'Rename… Company',
      'Text size… for Company',
      'Remove… Company',
    ])
    expect((await axe(document.body)).violations).toEqual([])

    await press('Text size… for Company', 'section.selection-bar')
    expect(document.querySelector('dialog h2')?.textContent).toBe('Text size and overflow for Company')
    await press('Cancel', 'dialog')

    await focusSpot('edit-full.name')
    expect(document.querySelector('section.selection-bar')!.textContent).toContain('This box belongs to the PDF’s own form')
    expect(() => button('Text size… for Full name', 'section.selection-bar')).toThrow()
    // The Rules card says how each box's text looks: the box's own style, or that the form sets it.
    const rules = () => document.querySelector('section[aria-labelledby="rules-heading"]')!
    expect(rules().textContent).toContain('The PDF’s own form sets how this box’s text looks.')
    await focusSpot('edit-company')
    expect([...rules().querySelectorAll('.chip-list .chip')].map((chip) => chip.textContent)).toEqual(['Liberation Sans', '11 pt', 'Regular'])
  })

  it('removes a spot from the bar, closing the bar, with Undo in the chat', async () => {
    layouts.set(12, pdfLayout(12, [FULL_NAME, COMPANY]))
    versions.set(12, version(12, [FULL_NAME, COMPANY]))
    vi.mocked(changeFillSpots).mockImplementation(async () => {
      layouts.set(13, pdfLayout(13, [FULL_NAME]))
      versions.set(13, version(13, [FULL_NAME]))
      server = { versionId: 13, revision: revision(2, {}, 13) }
      return {
        document: documentOn(13, server.revision),
        revision: server.revision,
        templateVersion: versions.get(13)!,
        previousRevisionId: 1,
        fieldIds: ['company'],
        otherDocumentsOnPreviousVersion: 0,
      }
    })
    await mountPage()

    await focusSpot('edit-company')
    await press('Remove… Company', 'section.selection-bar')
    expect(document.querySelector('dialog')?.textContent).toContain('Its value stays in the version history.')
    await press('Remove', 'dialog')

    expect(changeFillSpots).toHaveBeenCalledWith(7, 1, 1, 12, [{ kind: 'REMOVE', fieldId: 'company' }], expect.any(String))
    expect(document.querySelector('section.selection-bar')).toBeNull()
    expect(document.activeElement?.id).toBe('document-pane')
    expect(chatLines()).toContain('Removed the fill spot Company. Its value stays in the version history.')
  })

  it('keeps a box Brownie found once the person renames it, so its mark goes', async () => {
    const found: Spot = { ...COMPANY, origin: 'FOUND_BY_BROWNIE' }
    layouts.set(12, pdfLayout(12, [FULL_NAME, found]))
    versions.set(12, version(12, [FULL_NAME, found]))
    vi.mocked(keepFillSpot).mockResolvedValue(undefined)
    vi.mocked(changeFillSpots).mockImplementation(async () => {
      const renamed: Spot = { ...found, label: 'Company name' }
      layouts.set(13, pdfLayout(13, [FULL_NAME, renamed]))
      versions.set(13, version(13, [FULL_NAME, renamed]))
      const previous = server.revision
      server = { versionId: 13, revision: revision(previous.id + 1, previous.fields, 13) }
      return {
        document: documentOn(13, server.revision),
        revision: server.revision,
        templateVersion: versions.get(13)!,
        previousRevisionId: previous.id,
        fieldIds: ['company'],
        otherDocumentsOnPreviousVersion: 0,
      }
    })
    await mountPage()
    expect(document.querySelectorAll('.fill-spot__found')).toHaveLength(1)
    // A PDF's own page marks a found box with a dashed outline, and the line over it says so.
    expect(document.querySelector('.form-strip__text')?.textContent).toBe('Brownie found 1 more place to fill in. Check the one with a dashed outline.')

    await focusSpot('edit-company')
    await press('Rename… Company', 'section.selection-bar')
    const input = document.querySelector<HTMLInputElement>('dialog input[type="text"]')!
    input.value = 'Company name'
    input.dispatchEvent(new Event('input'))
    await press('Rename', 'dialog')

    expect(keepFillSpot).toHaveBeenCalledWith(7, 4, 'company')
    expect(document.querySelectorAll('.fill-spot__found')).toHaveLength(0)
    expect(document.querySelector('.form-strip')).toBeNull()
  })

  it('reloads and says so when the document moved on before the change', async () => {
    vi.mocked(changeFillSpots).mockRejectedValue(problem(412, 'STALE_REVISION'))
    layouts.set(12, pdfLayout(12, [FULL_NAME, COMPANY]))
    versions.set(12, version(12, [FULL_NAME, COMPANY]))
    await mountPage()
    const loads = vi.mocked(getDocument).mock.calls.length

    await focusSpot('edit-company')
    await press('Rename… Company', 'section.selection-bar')
    const input = document.querySelector<HTMLInputElement>('dialog input[type="text"]')!
    input.value = 'Company name'
    input.dispatchEvent(new Event('input'))
    await press('Rename', 'dialog')

    expect(vi.mocked(getDocument).mock.calls.length).toBeGreaterThan(loads)
    expect(document.querySelector('dialog [role="alert"]')?.textContent).toBe(
      'This document changed since you loaded it, so it was reloaded. Try again on the current version.',
    )
  })

  it('sends the point picked on a page as the chat’s "here", and shows the spot the chat added with Undo', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({
      kind: 'ADD_FILL_SPOT',
      summary: 'Add a fill spot for Company here.',
      scope: null,
      executable: true,
      usesModel: false,
      help: [],
    })
    vi.mocked(executeAssist).mockImplementation(async () => {
      layouts.set(13, pdfLayout(13, [FULL_NAME, COMPANY]))
      versions.set(13, version(13, [FULL_NAME, COMPANY]))
      server = { versionId: 13, revision: revision(2, {}, 13) }
      return {
        kind: 'ADD_FILL_SPOT',
        summary: 'Added a fill spot for Company on page 1. Forms you start from this template will have it too.',
        explanation: null,
        help: [],
        spotChange: { fieldId: 'company', label: 'Company', lineText: null, previousRevisionId: 1, templateVersionId: 13 },
      }
    })
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { left: 0, top: 0, right: 612, bottom: 792, width: 612, height: 792, x: 0, y: 0, toJSON: () => ({}) } as DOMRect
    })
    await mountPage()

    const composer = document.getElementById('assist-composer') as HTMLTextAreaElement
    const hint = () => document.getElementById(composer.getAttribute('aria-describedby') ?? '')?.textContent
    composer.value = 'fill in here for Company'
    composer.dispatchEvent(new Event('input'))
    await settle()
    expect(hint()).toBe('To say where "here" is, click the place on the page first.')
    document.querySelector('.pdf-form-page__surface')!.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 140, clientY: 104 }))
    await settle()
    expect(hint()).toBe('"Here" is the place you picked on page 1.')
    composer.form!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()

    const anchor = { kind: 'PDF', pageNumber: 1, point: { x: 140, y: 104 } }
    expect(interpretAssist).toHaveBeenCalledWith(7, 1, 'fill in here for Company', anchor)
    expect(executeAssist).toHaveBeenCalledWith(7, 1, 'fill in here for Company', 1, anchor)
    // The server's own words say what the chat did, as they do on a Word form.
    expect(chatLines()).toContain('Added a fill spot for Company on page 1. Forms you start from this template will have it too.')
    expect(document.getElementById('edit-company')).not.toBeNull()
    expect(button('Undo adding Company', '.chat-line')).toBeTruthy()
  })

  it('undoes a box’s new text size from the chat as a change to that spot', async () => {
    layouts.set(12, pdfLayout(12, [FULL_NAME, COMPANY]))
    versions.set(12, version(12, [FULL_NAME, COMPANY]))
    vi.mocked(changeFillSpots).mockImplementation(async () => {
      const smaller: Spot = { ...COMPANY, style: { font: 'SANS', bold: false, sizePt: 9 } }
      layouts.set(13, pdfLayout(13, [FULL_NAME, smaller]))
      versions.set(13, version(13, [FULL_NAME, smaller]))
      server = { versionId: 13, revision: revision(2, {}, 13) }
      return {
        document: documentOn(13, server.revision),
        revision: server.revision,
        templateVersion: versions.get(13)!,
        previousRevisionId: 1,
        fieldIds: ['company'],
        otherDocumentsOnPreviousVersion: 0,
      }
    })
    vi.mocked(restoreRevision).mockImplementation(async () => {
      server = { versionId: 12, revision: revision(3, {}, 12) }
      return { revision: server.revision, keptLockedFieldIds: [], droppedFieldIds: [] }
    })
    await mountPage()

    await focusSpot('edit-company')
    await press('Text size… for Company', 'section.selection-bar')
    const size = document.querySelector<HTMLInputElement>('dialog input[type="number"]')!
    size.value = '9'
    size.dispatchEvent(new Event('input'))
    document.querySelector<HTMLFormElement>('dialog form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await settle()

    expect(changeFillSpots).toHaveBeenCalledWith(7, 1, 1, 12, [{ kind: 'RESTYLE_BOX', fieldId: 'company', sizePt: 9, overflow: 'SHRINK_TO_FIT' }], expect.any(String))
    expect(chatLines()).toContain('Changed how the text fits in Company. New documents from this form will have the change too.')
    await press('Undo the change to Company', '.chat-line')
    expect(restoreRevision).toHaveBeenCalledWith(7, 1, 1, 2, expect.any(String), 'Undid a fill spot change.')
    expect(chatLines()).toContain('Undone: the change to Company is taken back.')
  })

  it('asks for the move to the newest version before a spot is changed on a document behind it', async () => {
    layouts.set(12, pdfLayout(12, [FULL_NAME, COMPANY]))
    versions.set(12, version(12, [FULL_NAME, COMPANY]))
    versions.set(13, version(13, [FULL_NAME, COMPANY]))
    vi.mocked(getDocument).mockImplementation(async () => ({ ...documentOn(12, server.revision), templateLatestVersionId: 13 }))
    await mountPage()

    await focusSpot('edit-company')
    await press('Rename… Company', 'section.selection-bar')

    expect(document.querySelector('dialog form')).toBeNull()
    expect(document.body.textContent).toContain('This document is on an older version of its form. Move it to the newest version first, then change its fill spots.')
    expect((document.activeElement?.textContent ?? '').trim()).toBe('Move this document to it')
    expect(changeFillSpots).not.toHaveBeenCalled()
  })

  it('offers the move again after "Not now" when a box is added to a document behind the newest version', async () => {
    versions.set(13, version(13, [FULL_NAME, COMPANY]))
    vi.mocked(getDocument).mockImplementation(async () => ({ ...documentOn(12, server.revision), templateLatestVersionId: 13 }))
    vi.mocked(suggestBox).mockResolvedValue({ box: COMPANY.box, style: COMPANY.style!, labelGuess: 'Company' })
    await mountPage()
    await press('Not now')
    expect(document.querySelector('.version-banner')).toBeNull()

    await press('Add a fill spot')
    const lines = document.querySelector<HTMLSelectElement>('dialog select[size]')!
    lines.value = '0'
    lines.dispatchEvent(new Event('change'))
    await press('Next', 'dialog')
    await press('Add the fill spot', 'dialog')

    expect(document.querySelector('dialog [role="alert"]')?.textContent).toBe(
      'This document is on an older version of its form. Move it to the newest version first, then change its fill spots.',
    )
    expect(changeFillSpots).not.toHaveBeenCalled()
    expect(button('Move this document to it')).toBeDefined()
  })

  it('learns of a newer version when the server refuses a box for it, and offers the move', async () => {
    versions.set(13, version(13, [FULL_NAME, COMPANY]))
    vi.mocked(suggestBox).mockResolvedValue({ box: COMPANY.box, style: COMPANY.style!, labelGuess: 'Company' })
    vi.mocked(changeFillSpots).mockImplementation(async () => {
      vi.mocked(getDocument).mockImplementation(async () => ({ ...documentOn(12, server.revision), templateLatestVersionId: 13 }))
      throw problem(409, 'DOCUMENT_TEMPLATE_VERSION_MOVED')
    })
    await mountPage()

    await press('Add a fill spot')
    const lines = document.querySelector<HTMLSelectElement>('dialog select[size]')!
    lines.value = '0'
    lines.dispatchEvent(new Event('change'))
    await press('Next', 'dialog')
    await press('Add the fill spot', 'dialog')

    expect(document.querySelector('dialog [role="alert"]')?.textContent).toBe(
      'This document is on an older version of its form. Move it to the newest version first, then try again.',
    )
    expect(button('Move this document to it')).toBeDefined()
  })

  it('exports a PDF form only as a PDF', async () => {
    vi.mocked(validateDocument).mockResolvedValue({
      id: 501,
      documentId: 1,
      revisionId: 1,
      templateId: 4,
      templateVersionId: 12,
      docxArtifactId: null,
      docxSha256: null,
      pdfArtifactId: 901,
      pdfSha256: 'd'.repeat(64),
      findings: [],
      hasUnresolvedBlocking: false,
      createdAt: CREATED_AT,
    } as never)
    vi.mocked(approveExport).mockResolvedValue({ id: 3, validationManifestId: 501, format: 'PDF' } as never)
    vi.mocked(exportDocument).mockResolvedValue({ id: 4, exportApprovalId: 3, format: 'PDF', docxArtifactId: null, pdfArtifactId: 902 } as never)
    await mountPage()

    await press('Export')
    const dialog = document.querySelector('dialog.export-dialog')!
    expect(dialog.textContent).toContain('This is a PDF form, so Brownie fills it and exports it as a PDF. It does not turn it into a Word file.')
    expect(dialog.querySelectorAll('input[name="export-format"]')).toHaveLength(0)

    await press('Approve and export', 'dialog.export-dialog')
    expect(approveExport).toHaveBeenCalledWith(7, 1, 501, 'PDF')
    expect([...dialog.querySelectorAll('a[download]')].map((link) => (link.textContent ?? '').trim())).toEqual(['Download PDF'])
  })
})
