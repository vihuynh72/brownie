import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { RouterView, createRouter, createWebHistory } from 'vue-router'
import WorkspaceView from '@/views/WorkspaceView.vue'
import { useSessionStore } from '@/stores/session'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    changeFillSpots: vi.fn(),
    executeAssist: vi.fn(),
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
    listActions: vi.fn(),
    listConnections: vi.fn(),
    listDocumentRevisions: vi.fn(),
    listDocumentSources: vi.fn(),
    listGenerationRuns: vi.fn(),
    listTemplateVersionRules: vi.fn(),
    moveDocumentToTemplateVersion: vi.fn(),
    patchDocumentContent: vi.fn(),
    restoreRevision: vi.fn(),
  }
})

vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))
vi.mock('@/components/PdfPreview.vue', () => ({
  __esModule: true,
  default: { name: 'PdfPreview', props: ['src', 'label'], template: '<div data-testid="pdf-preview"></div>' },
}))

import {
  ApiRequestError,
  changeFillSpots,
  executeAssist,
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
  listActions,
  listConnections,
  listDocumentRevisions,
  listDocumentSources,
  listGenerationRuns,
  listTemplateVersionRules,
  moveDocumentToTemplateVersion,
  patchDocumentContent,
  restoreRevision,
  type DocumentResponse,
  type DocumentRevisionResponse,
  type FillSpotsResponse,
  type TemplateLayoutBlockResponse,
  type TemplateLayoutInlineResponse,
  type TemplateLayoutResponse,
  type TemplateVersionResponse,
} from '@/api/client'
import { axe } from '@/test/axe'

const API = [
  changeFillSpots,
  executeAssist,
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
  listActions,
  listConnections,
  listDocumentRevisions,
  listDocumentSources,
  listGenerationRuns,
  listTemplateVersionRules,
  moveDocumentToTemplateVersion,
  patchDocumentContent,
  restoreRevision,
] as unknown as Mock[]

// ---- An application form: a title spot the form had, and a blank after "Company:" -----------------

const PARSER = 'brownie-docx-graph-v3+poi-5.5.1'
const CREATED_AT = '2026-09-01T00:00:00Z'
const HASH_A = 'a'.repeat(64)
const HASH_B = 'b'.repeat(64)

function text(value: string, anchorStart: number | null): TemplateLayoutInlineResponse {
  return { kind: 'TEXT', text: value, anchorStart }
}

function paragraph(nodeId: string, inlines: TemplateLayoutInlineResponse[], extra: Partial<TemplateLayoutBlockResponse> = {}): TemplateLayoutBlockResponse {
  return { kind: 'PARAGRAPH', repeating: false, inlines, nodeId, anchorable: true, anchorTextHash: `hash-${nodeId}`, ...extra }
}

function layoutOf(versionId: number, companyLine: TemplateLayoutInlineResponse[]): TemplateLayoutResponse {
  return {
    templateId: 1,
    versionId,
    kind: 'DOCX',
    parserVersion: PARSER,
    parts: [
      {
        kind: 'MAIN_DOCUMENT',
        blocks: [
          paragraph('p0', [text('Application', 0)]),
          paragraph('p1', companyLine),
          paragraph('p2', [text('Title: ', 0), { kind: 'FILL_SPOT', fieldId: 'report.title', nodeId: 'p2/sdt1', origin: 'FORM' }]),
        ],
      },
      { kind: 'HEADER', blocks: [paragraph('p0', [text('Letterhead', null)], { anchorable: false, anchorTextHash: null })] },
    ],
    unplacedFieldIds: [],
  }
}

const LAYOUT_4 = layoutOf(4, [text('Company: ', 0), text('________', 9)])
const LAYOUT_5 = layoutOf(5, [text('Company: ', 0), { kind: 'FILL_SPOT', fieldId: 'company', nodeId: 'p1/sdt1', origin: 'ADDED_BY_PERSON', label: 'Company' }])

const TITLE_FIELD = { fieldId: 'report.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'report.title', label: 'Title' } as const
const COMPANY_FIELD = { fieldId: 'company', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', bindingKind: 'CONTENT_CONTROL_TAG', tag: 'company', label: 'Company', origin: 'ADDED_BY_PERSON' } as const

function version(id: number, fields: TemplateVersionResponse['fields']): TemplateVersionResponse {
  return { id, templateId: 1, versionNumber: id, sourceArtifactId: 10, extractionVersionId: 11, status: 'ACTIVATED', fields, createdAt: CREATED_AT, activatedAt: CREATED_AT }
}

const VERSION_4 = version(4, [TITLE_FIELD])
const VERSION_5 = version(5, [TITLE_FIELD, COMPANY_FIELD])

function revision(id: number, templateVersionId: number, overrides: Partial<DocumentRevisionResponse> = {}): DocumentRevisionResponse {
  return {
    id,
    revisionNumber: id - 9,
    fields: { 'report.title': { type: 'TEXT', cardinality: 'SCALAR', value: 'Annual report', evidenceSourceSpanIds: [], fieldState: null } },
    contentHash: HASH_A,
    editReason: 'r',
    createdAt: CREATED_AT,
    templateVersionId,
    ...overrides,
  }
}

function documentOn(current: DocumentRevisionResponse, templateVersionId: number, latest = templateVersionId): DocumentResponse {
  return { id: 1, title: 'Application', templateId: 1, templateVersionId, templateLatestVersionId: latest, currentRevisionId: current.id, createdAt: CREATED_AT, currentRevision: current }
}

const R10 = revision(10, 4)
const R11 = revision(11, 5)
const ON_4 = documentOn(R10, 4)
const ON_5 = documentOn(R11, 5)

function added(overrides: Partial<FillSpotsResponse> = {}): FillSpotsResponse {
  return { document: ON_5, revision: R11, templateVersion: VERSION_5, previousRevisionId: 10, fieldIds: ['company'], otherDocumentsOnPreviousVersion: 0, ...overrides }
}

function refusal(status: number, code: string, extra: Record<string, unknown> = {}): ApiRequestError {
  return new ApiRequestError(status, { status, title: 'Refused', code, detail: 'The server says why.', correlationId: 'c', fields: [], recoveryActions: [], ...extra })
}

/** The server as it is once a spot was added: the document, its version and its layout all at 5. */
function serveVersion5(): void {
  vi.mocked(getDocument).mockResolvedValue(ON_5)
}

// ---- Mounting ----------------------------------------------------------------------------------------

const Host = defineComponent({ name: 'Host', render: () => h('main', { id: 'main-content' }, [h(RouterView)]) })
const Blank = defineComponent({ render: () => h('div') })
type Page = VueWrapper
const mounted: Page[] = []

async function flushPromises(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

async function settle(): Promise<void> {
  for (let round = 0; round < 4; round++) await flushPromises()
}

async function mountPage(): Promise<Page> {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: Blank },
      { path: '/documents/:id', component: WorkspaceView, props: (route) => ({ documentId: Number(route.params.id) }) },
    ],
  })
  await router.push('/documents/1')
  await router.isReady()
  const page = mount(Host, { attachTo: document.body, global: { plugins: [router] } })
  mounted.push(page)
  await settle()
  return page
}

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  setActivePinia(createPinia())
  for (const fn of API) fn.mockReset()
  vi.mocked(getDocument).mockResolvedValue(ON_4)
  vi.mocked(getTemplateVersion).mockImplementation(async (_w, _t, versionId) => (versionId === 5 ? VERSION_5 : VERSION_4))
  vi.mocked(getTemplateLayout).mockImplementation(async (_w, _t, versionId) => (versionId === 5 ? LAYOUT_5 : LAYOUT_4))
  vi.mocked(listTemplateVersionRules).mockResolvedValue([])
  vi.mocked(listDocumentSources).mockResolvedValue([])
  vi.mocked(listGenerationRuns).mockResolvedValue([])
  vi.mocked(getCapabilities).mockResolvedValue({ maxUploadBytes: 1024, uploadMediaTypes: [], assistSourceMediaTypes: [], templateMediaTypes: [], trashRetentionDays: 30 })
  const none = new ApiRequestError(404, undefined)
  vi.mocked(getLatestCompilation).mockRejectedValue(none)
  vi.mocked(getLatestValidation).mockRejectedValue(none)
  vi.mocked(getLatestExportApproval).mockRejectedValue(none)
  vi.mocked(getLatestExportReceipt).mockRejectedValue(none)
  vi.mocked(listConnections).mockResolvedValue([])
  vi.mocked(listActions).mockResolvedValue([])
  vi.spyOn(window, 'scrollBy').mockImplementation(() => undefined)
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
})

afterEach(() => {
  vi.useRealTimers()
  for (const page of mounted.splice(0)) if (!page.vm.$.isUnmounted) page.unmount()
  vi.restoreAllMocks()
  window.getSelection()?.removeAllRanges()
  document.body.innerHTML = ''
})

// ---- Finding things ----------------------------------------------------------------------------------

function norm(value: string): string {
  return value.replace(/\s+/g, ' ').trim()
}

function accessibleName(element: Element): string {
  let words = ''
  const walk = (node: Node): void => {
    if (node.nodeType === Node.TEXT_NODE) words += node.textContent ?? ''
    else if (!(node instanceof Element && node.getAttribute('aria-hidden') === 'true')) node.childNodes.forEach(walk)
  }
  walk(element)
  return norm(words)
}

function buttonNamed(page: Page, name: string, within = ''): DOMWrapper<HTMLButtonElement> | undefined {
  return page.findAll<HTMLButtonElement>(`${within} button`.trim()).find((button) => accessibleName(button.element) === name)
}

async function press(page: Page, name: string, within = ''): Promise<void> {
  const target = buttonNamed(page, name, within)
  if (!target) throw new Error(`No button "${name}". There are: ${page.findAll(`${within} button`.trim()).map((button) => accessibleName(button.element)).join(' | ')}`)
  target.element.focus()
  await target.trigger('click')
  await settle()
}

const byId = (page: Page, id: string) => page.find(`[id="${id}"]`)
const dialogs = (page: Page) => page.findAll('dialog.spot-dialog').filter((dialog) => dialog.attributes('open') !== undefined)
const openDialog = (page: Page) => dialogs(page)[0]!
const liveRegion = (page: Page) => page.get('.workspace > [aria-live="polite"][aria-atomic="true"]')
const chatLines = (page: Page) => page.findAll('.chat-line--brownie').map((line) => norm(line.find('p').text()))

async function selectSpot(page: Page, id: string): Promise<void> {
  ;(byId(page, id).element as HTMLElement).focus()
  await settle()
}

/** Adds the spot for Company through the keyboard route, up to the name, which it leaves as suggested. */
async function addCompanyFromTheList(page: Page): Promise<void> {
  await press(page, 'Add a fill spot')
  const dialog = openDialog(page)
  await dialog.get('input[type="search"]').setValue('company')
  await press(page, 'Next', 'dialog[open]')
  await press(page, 'Next', 'dialog[open]')
}

async function submitDialog(page: Page): Promise<void> {
  await openDialog(page).get('form').trigger('submit')
  await settle()
}

// =================================================================================================

describe('WorkspaceView: adding a fill spot', () => {
  it('adds one from the list of lines: the change, the status while it is made, the chat line, and focus on the new spot', async () => {
    let finish!: (response: FillSpotsResponse) => void
    vi.mocked(changeFillSpots).mockImplementation(() => new Promise((resolve) => (finish = resolve)))
    const page = await mountPage()
    await addCompanyFromTheList(page)

    const name = openDialog(page).get('input[type="text"]')
    expect((name.element as HTMLInputElement).value).toBe('Company')
    await submitDialog(page)
    expect(norm(openDialog(page).get('[role="status"]').text())).toBe('Adding the fill spot… Brownie is checking the form still prints correctly.')
    expect(changeFillSpots).toHaveBeenCalledWith(
      7,
      1,
      10,
      4,
      [
        {
          kind: 'ADD',
          label: 'Company',
          type: 'TEXT',
          anchor: { part: 'MAIN_DOCUMENT', paragraphNodeId: 'p1', placement: 'REPLACE', start: 9, end: 17, anchorTextHash: 'hash-p1', parserVersion: PARSER, controlNodeId: null },
        },
      ],
      expect.any(String),
    )

    serveVersion5()
    finish(added())
    await settle()

    expect(dialogs(page)).toHaveLength(0)
    expect(getTemplateLayout).toHaveBeenLastCalledWith(7, 1, 5)
    expect(window.document.activeElement?.id).toBe('edit-company')
    expect(chatLines(page).at(-1)).toBe('Added a fill spot for Company in place of "________". New documents from this form will have it too.')
    expect(liveRegion(page).text()).toBe('Added a fill spot for Company in place of "________". New documents from this form will have it too.')
    expect(buttonNamed(page, 'Undo adding Company')).toBeDefined()
  })

  it('saves what was typed before it asks for the change', async () => {
    vi.mocked(patchDocumentContent).mockResolvedValue(revision(11, 4, { contentHash: HASH_B }))
    vi.mocked(changeFillSpots).mockResolvedValue(added())
    const page = await mountPage()
    await byId(page, 'edit-report.title').setValue('Annual report 2026')
    vi.mocked(getDocument).mockResolvedValueOnce(documentOn(revision(11, 4, { contentHash: HASH_B }), 4)).mockResolvedValue(ON_5)
    await addCompanyFromTheList(page)
    await submitDialog(page)

    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    expect(vi.mocked(patchDocumentContent).mock.invocationCallOrder[0]!).toBeLessThan(vi.mocked(changeFillSpots).mock.invocationCallOrder[0]!)
    // Made against the version the save produced, so nothing typed is lost.
    expect(vi.mocked(changeFillSpots).mock.calls[0]![2]).toBe(11)
  })

  it('adds one at words selected on the page, named from the label before them', async () => {
    vi.mocked(changeFillSpots).mockResolvedValue(added())
    const page = await mountPage()
    const blank = page.findAll('.document-page__text').find((span) => span.element.textContent === '________')!.element
    const range = document.createRange()
    range.setStart(blank.firstChild!, 0)
    range.setEnd(blank.firstChild!, 8)
    window.getSelection()!.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
    await settle()
    await press(page, 'Fill in here')

    const dialog = openDialog(page)
    // The bar stands down behind the dialog; it stays in the page for focus to go back to.
    expect((page.get('.fill-here-bar').element as HTMLElement).style.display).toBe('none')
    expect(dialog.get('.place-spot__covered').text()).toBe('________')
    expect((dialog.get('input[type="text"]').element as HTMLInputElement).value).toBe('Company')
    expect(await axe(page.element)).toHaveNoViolations()
    serveVersion5()
    await submitDialog(page)
    expect(vi.mocked(changeFillSpots).mock.calls[0]![4][0]!.anchor).toMatchObject({ paragraphNodeId: 'p1', placement: 'REPLACE', start: 9, end: 17 })
  })

  it('shows one bar at a time: words selected on the page close the bar about a spot', async () => {
    const page = await mountPage()
    await selectSpot(page, 'edit-report.title')
    expect(page.find('section.selection-bar').exists()).toBe(true)

    const blank = page.findAll('.document-page__text').find((span) => span.element.textContent === '________')!.element
    const range = document.createRange()
    range.setStart(blank.firstChild!, 0)
    range.setEnd(blank.firstChild!, 8)
    // Selecting with a pointer moves focus out of the spot, to the page.
    ;(window.document.getElementById('document-pane') as HTMLElement).focus()
    window.getSelection()!.removeAllRanges()
    window.getSelection()!.addRange(range)
    document.dispatchEvent(new Event('selectionchange'))
    await settle()

    expect(page.find('.fill-here-bar').exists()).toBe(true)
    expect(page.find('section.selection-bar').exists()).toBe(false)
  })

  it.each([
    [refusal(409, 'FILL_SPOT_ANCHOR_STALE'), 'The page changed; select the place again.', true],
    [refusal(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'HEADER_FOOTER' }), 'Brownie fills only the body of the form, not its header or footer.', false],
    [refusal(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'REPEATING_REGION' }), 'This part of the form repeats for each row, so a single fill spot cannot go here.', false],
    [refusal(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'INSIDE_LINK' }), 'Brownie cannot put a fill spot inside a link. Choose a place next to it.', false],
    [refusal(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'INSIDE_FIELD_CODE' }), 'Brownie cannot put a fill spot inside a page number or another Word field.', false],
    [refusal(409, 'TEMPLATE_VERSION_MOVED_ON'), 'This form changed while you were working, so it was reloaded. Try again.', true],
    [refusal(412, 'STALE_REVISION'), 'This document changed since you loaded it, so it was reloaded. Try again on the current version.', true],
    [refusal(409, 'FILL_SPOT_LOCKED', { fieldId: 'report.title' }), 'Unlock Title first; its value would be lost.', false],
    [
      refusal(422, 'FILL_SPOT_WOULD_NOT_PRINT'),
      'Brownie could not add the fill spot, because the form would not print correctly with it. Nothing was changed.',
      false,
    ],
  ])('says why it was refused (%s), in the dialog, and loads the page again only when it changed', async (error, words, reloads) => {
    vi.mocked(changeFillSpots).mockRejectedValue(error)
    const page = await mountPage()
    await addCompanyFromTheList(page)
    const loads = vi.mocked(getDocument).mock.calls.length
    await submitDialog(page)

    expect(norm(openDialog(page).get('[role="alert"]').text())).toBe(words)
    expect(vi.mocked(getDocument).mock.calls.length > loads).toBe(reloads)
    expect(chatLines(page).some((line) => line.startsWith('Added'))).toBe(false)
  })

  it('offers no way to add a spot to a page Brownie could not draw', async () => {
    vi.mocked(getTemplateLayout).mockRejectedValue(refusal(422, 'TEMPLATE_LAYOUT_UNAVAILABLE'))
    const page = await mountPage()
    expect(buttonNamed(page, 'Add a fill spot')).toBeUndefined()
  })
})

describe('WorkspaceView: renaming and removing a fill spot', () => {
  it('renames the selected spot from the bar about it, and gives focus back there', async () => {
    const renamed = version(5, [{ ...TITLE_FIELD, label: 'Report title' }])
    vi.mocked(changeFillSpots).mockResolvedValue(added({ fieldIds: ['report.title'], templateVersion: renamed }))
    const page = await mountPage()
    await selectSpot(page, 'edit-report.title')
    await press(page, 'Rename… Title', 'section.selection-bar')

    const dialog = openDialog(page)
    expect(dialog.get('h2').text()).toBe('Rename Title')
    expect(await axe(page.element)).toHaveNoViolations()
    await dialog.get('input').setValue('Report title')
    vi.mocked(getDocument).mockResolvedValue(ON_5)
    vi.mocked(getTemplateVersion).mockResolvedValue(renamed)
    await dialog.get('form').trigger('submit')
    await settle()

    expect(vi.mocked(changeFillSpots).mock.calls[0]![4]).toEqual([{ kind: 'RENAME', fieldId: 'report.title', label: 'Report title' }])
    expect(chatLines(page).at(-1)).toBe('Renamed the fill spot Title to Report title. New documents from this form will use the new name too.')
    expect(accessibleName(window.document.activeElement!)).toBe('Rename… Report title')
  })

  it('keeps a place Brownie found once the person renames it, so its mark goes', async () => {
    const found = { ...TITLE_FIELD, origin: 'FOUND_BY_BROWNIE' } as const
    vi.mocked(getTemplateVersion).mockImplementation(async (_w, _t, versionId) =>
      versionId === 5 ? version(5, [{ ...found, label: 'Report title' }]) : version(4, [found]),
    )
    vi.mocked(changeFillSpots).mockResolvedValue(added({ fieldIds: ['report.title'] }))
    vi.mocked(keepFillSpot).mockResolvedValue(undefined)
    const page = await mountPage()
    expect(page.findAll('.fill-spot__found')).toHaveLength(1)
    expect(page.find('.form-strip').exists()).toBe(true)
    await selectSpot(page, 'edit-report.title')
    await press(page, 'Rename… Title', 'section.selection-bar')
    await openDialog(page).get('input').setValue('Report title')
    vi.mocked(getDocument).mockResolvedValue(ON_5)
    await openDialog(page).get('form').trigger('submit')
    await settle()

    expect(keepFillSpot).toHaveBeenCalledWith(7, 1, 'report.title')
    expect(page.findAll('.fill-spot__found')).toHaveLength(0)
    expect(page.find('.form-strip').exists()).toBe(false)
    expect(chatLines(page).at(-1)).toBe('Renamed the fill spot Title to Report title. New documents from this form will use the new name too.')
  })

  it('asks before removing a spot the form had, then removes it and moves focus to the document', async () => {
    vi.mocked(changeFillSpots).mockResolvedValue(added({ fieldIds: ['report.title'], templateVersion: version(5, [COMPANY_FIELD]) }))
    const page = await mountPage()
    await selectSpot(page, 'edit-report.title')
    await press(page, 'Remove… Title', 'section.selection-bar')

    const dialog = openDialog(page)
    expect(dialog.findAll('.spot-change__line').map((line) => line.text())).toEqual([
      'Remove the fill spot Title? Its value stays in the version history.',
      'The form keeps its own box; Brownie just stops filling it.',
    ])
    expect(window.document.activeElement?.textContent?.trim()).toBe('Cancel')
    await press(page, 'Remove the fill spot', 'dialog[open]')

    expect(vi.mocked(changeFillSpots).mock.calls[0]![4]).toEqual([{ kind: 'REMOVE', fieldId: 'report.title' }])
    expect(chatLines(page).at(-1)).toBe('Removed the fill spot Title. Its value stays in the version history. New documents from this form will not have it.')
    expect(window.document.activeElement?.id).toBe('document-pane')
  })

  it('offers no Rename or Remove on a document from a server that cannot change fill spots', async () => {
    // A server older than this page sends no newest version of the form at all; the new one sends null when it has none.
    const older: DocumentResponse = { ...ON_4 }
    delete older.templateLatestVersionId
    vi.mocked(getDocument).mockResolvedValue(older)
    const page = await mountPage()
    await selectSpot(page, 'edit-report.title')

    expect(page.find('section.selection-bar').exists()).toBe(true)
    expect(buttonNamed(page, 'Rename… Title', 'section.selection-bar')).toBeUndefined()
    expect(buttonNamed(page, 'Remove… Title', 'section.selection-bar')).toBeUndefined()

    vi.mocked(getDocument).mockResolvedValue({ ...ON_4, templateLatestVersionId: null })
    const current = await mountPage()
    await selectSpot(current, 'edit-report.title')
    expect(buttonNamed(current, 'Rename… Title', 'section.selection-bar')).toBeDefined()
  })

  it('says a locked value would be lost, in the dialog', async () => {
    vi.mocked(changeFillSpots).mockRejectedValue(refusal(409, 'FILL_SPOT_LOCKED', { fieldId: 'report.title' }))
    const page = await mountPage()
    await selectSpot(page, 'edit-report.title')
    await press(page, 'Remove… Title', 'section.selection-bar')
    await press(page, 'Remove the fill spot', 'dialog[open]')

    expect(norm(openDialog(page).get('[role="alert"]').text())).toBe('Unlock Title first; its value would be lost.')
  })
})

describe('WorkspaceView: undoing a fill spot change', () => {
  it('puts the version before the change back from the chat line, and says so', async () => {
    vi.mocked(changeFillSpots).mockResolvedValue(added())
    const page = await mountPage()
    await addCompanyFromTheList(page)
    serveVersion5()
    await submitDialog(page)

    vi.mocked(restoreRevision).mockResolvedValue({ revision: revision(12, 4), keptLockedFieldIds: [] })
    vi.mocked(getDocument).mockResolvedValue(documentOn(revision(12, 4), 4))
    await press(page, 'Undo adding Company')

    expect(restoreRevision).toHaveBeenCalledWith(7, 1, 10, 11, expect.any(String), 'Undid a fill spot change.')
    expect(chatLines(page).at(-1)).toBe('Undone: Company is no longer a fill spot.')
    const done = buttonNamed(page, 'Undone adding Company')!
    expect(done.attributes('aria-disabled')).toBe('true')
    expect(byId(page, 'edit-company').exists()).toBe(false)
  })

  it("goes further back with the toolbar's Undo after the chat's Undo, rather than bringing the change back", async () => {
    vi.mocked(changeFillSpots).mockResolvedValue(added())
    const page = await mountPage()
    await addCompanyFromTheList(page)
    serveVersion5()
    await submitDialog(page)
    const r12 = revision(12, 4)
    vi.mocked(restoreRevision).mockResolvedValue({ revision: r12, keptLockedFieldIds: [] })
    vi.mocked(getDocument).mockResolvedValue(documentOn(r12, 4))
    await press(page, 'Undo adding Company')
    expect(restoreRevision).toHaveBeenLastCalledWith(7, 1, 10, 11, expect.any(String), 'Undid a fill spot change.')

    // Version 1 read differently; 2 is where the chat's Undo went back to, 3 added Company, and 4 took it back.
    const r9 = revision(9, 4, { contentHash: HASH_B, revisionNumber: 1 })
    vi.mocked(listDocumentRevisions).mockResolvedValue([
      r9,
      revision(10, 4, { revisionNumber: 2 }),
      revision(11, 5, { revisionNumber: 3 }),
      revision(12, 4, { revisionNumber: 4 }),
    ])
    vi.mocked(restoreRevision).mockResolvedValue({ revision: revision(13, 4, { contentHash: HASH_B, revisionNumber: 5 }), keptLockedFieldIds: [] })
    await press(page, 'Undo the last change')

    expect(restoreRevision).toHaveBeenLastCalledWith(7, 1, 9, 12, expect.any(String), 'Undid a change: back to version 1.')
  })

  it('counts a change of the form alone as a step of the toolbar Undo', async () => {
    vi.mocked(getDocument).mockResolvedValue(ON_5)
    // The two versions hold the same values; only the version of the form differs.
    vi.mocked(listDocumentRevisions).mockResolvedValue([R10, R11])
    vi.mocked(restoreRevision).mockResolvedValue({ revision: revision(12, 4), keptLockedFieldIds: [] })
    const page = await mountPage()
    vi.mocked(getDocument).mockResolvedValue(documentOn(revision(12, 4), 4))
    await press(page, 'Undo the last change')

    expect(restoreRevision).toHaveBeenCalledWith(7, 1, 10, 11, expect.any(String), 'Undid a change: back to version 1.')
    expect(getTemplateLayout).toHaveBeenLastCalledWith(7, 1, 4)
  })
})

describe('WorkspaceView: fill spots from the chat', () => {
  async function ask(page: Page, words: string): Promise<void> {
    const box = page.get('#assist-composer')
    await box.setValue(words)
    await box.trigger('keydown', { key: 'Enter' })
    await settle()
  }

  it('sends the place selected on the page with a request that says "here", and shows the change with Undo', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'ADD_FILL_SPOT', summary: 'Add a fill spot for Company at the place you selected.', executable: true, usesModel: false, help: [] })
    vi.mocked(executeAssist).mockImplementation(async () => {
      serveVersion5()
      return {
        kind: 'ADD_FILL_SPOT',
        summary: 'Added a fill spot for Company at the place you selected in the line "Company: ________". Forms you start from this template will have it too.',
        help: [],
        spotChange: { fieldId: 'company', label: 'Company', lineText: 'Company: ________', previousRevisionId: 10, templateVersionId: 5 },
      }
    })
    const page = await mountPage()
    const label = page.findAll('.document-page__text').find((span) => span.element.textContent === 'Company: ')!.element
    const caret = document.createRange()
    caret.setStart(label.firstChild!, 9)
    window.getSelection()!.addRange(caret)
    document.dispatchEvent(new Event('selectionchange'))
    await settle()

    await page.get('#assist-composer').setValue('add a fill spot for Company here')
    expect(page.get('#assist-composer-here').text()).toBe('"Here" is the place you chose on the page, in the paragraph "Company: ________".')
    expect(page.get('#assist-composer').attributes('aria-describedby')).toBe('assist-composer-here')
    await ask(page, 'add a fill spot for Company here')

    const anchor = { part: 'MAIN_DOCUMENT', paragraphNodeId: 'p1', placement: 'REPLACE', start: 9, end: 17, anchorTextHash: 'hash-p1', parserVersion: PARSER, controlNodeId: null }
    expect(interpretAssist).toHaveBeenCalledWith(7, 1, 'add a fill spot for Company here', anchor)
    expect(executeAssist).toHaveBeenCalledWith(7, 1, 'add a fill spot for Company here', 10, anchor)
    expect(chatLines(page).at(-1)).toBe(
      'Added a fill spot for Company at the place you selected in the line "Company: ________". Forms you start from this template will have it too.',
    )
    expect(byId(page, 'edit-company').exists()).toBe(true)
    expect(buttonNamed(page, 'Undo adding Company')).toBeDefined()
  })

  it('sends no place when none was chosen on the page', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'NONE', summary: 'Select the place on the page first, then ask again.', executable: false, usesModel: false, help: [] })
    const page = await mountPage()
    await page.get('#assist-composer').setValue('add a fill spot for Company here')
    expect(page.get('#assist-composer-here').text()).toBe('To say where "here" is, select the place on the page first.')
    await ask(page, 'add a fill spot for Company here')

    expect(interpretAssist).toHaveBeenCalledWith(7, 1, 'add a fill spot for Company here')
    expect(chatLines(page).at(-1)).toBe('Select the place on the page first, then ask again.')
  })

  it('offers the lines the quoted words are on, and asks again with the one chosen', async () => {
    const first = { part: 'MAIN_DOCUMENT' as const, paragraphNodeId: 'p0', placement: 'AT' as const, start: 11, end: 11, anchorTextHash: 'hash-p0', parserVersion: PARSER, controlNodeId: null }
    const second = { ...first, paragraphNodeId: 'p2', start: 7, end: 7, anchorTextHash: 'hash-p2' }
    vi.mocked(interpretAssist)
      .mockResolvedValueOnce({
        kind: 'ADD_FILL_SPOT',
        summary: 'The words "Application" are on 2 lines. Choose the line the fill spot goes on.',
        executable: false,
        usesModel: false,
        help: [],
        choices: [
          { lineText: 'Application', anchor: first },
          { lineText: 'Title: Application', anchor: second },
        ],
      })
      .mockResolvedValueOnce({ kind: 'NONE', summary: 'Not now.', executable: false, usesModel: false, help: [] })
    const page = await mountPage()
    await ask(page, 'add a fill spot for Ref after "Application"')

    const choices = page.get('[role="group"][aria-label="The paragraph the fill spot goes in"]')
    expect(choices.findAll('button').map((button) => norm(button.text()))).toEqual(['1Application', '2Title: Application'])
    await choices.findAll('button')[1]!.trigger('click')
    await settle()

    expect(interpretAssist).toHaveBeenLastCalledWith(7, 1, 'add a fill spot for Ref after "Application"', second)
    expect(page.find('[aria-label="The paragraph the fill spot goes in"]').exists()).toBe(false)
    expect(page.findAll('.chat-line--person').map((line) => norm(line.text())).at(-1)).toBe('Title: Application')
  })

  it('saves what was typed before the chat changes a fill spot, and asks against the version the save made', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'ADD_FILL_SPOT', summary: 's', executable: true, usesModel: false, help: [] })
    vi.mocked(patchDocumentContent).mockResolvedValue(revision(11, 4, { contentHash: HASH_B }))
    vi.mocked(executeAssist).mockImplementation(async () => {
      vi.mocked(getDocument).mockResolvedValue(documentOn(revision(12, 5, { contentHash: HASH_B }), 5))
      return {
        kind: 'ADD_FILL_SPOT',
        summary: 'Added a fill spot for Company after "Company:".',
        help: [],
        spotChange: { fieldId: 'company', label: 'Company', lineText: 'Company: ________', previousRevisionId: 11, templateVersionId: 5 },
      }
    })
    const page = await mountPage()
    await byId(page, 'edit-report.title').setValue('Annual report 2026')
    vi.mocked(getDocument).mockResolvedValue(documentOn(revision(11, 4, { contentHash: HASH_B }), 4))
    await ask(page, 'add a fill spot for Company after "Company:"')

    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    expect(vi.mocked(patchDocumentContent).mock.invocationCallOrder[0]!).toBeLessThan(vi.mocked(executeAssist).mock.invocationCallOrder[0]!)
    expect(executeAssist).toHaveBeenCalledWith(7, 1, 'add a fill spot for Company after "Company:"', 11)
    expect(chatLines(page).at(-1)).toBe('Added a fill spot for Company after "Company:".')
  })

  it('says why, and sends nothing, when what was typed cannot be saved before a fill spot change', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'RENAME_FILL_SPOT', summary: 's', executable: true, usesModel: false, help: [] })
    vi.mocked(patchDocumentContent).mockRejectedValue(refusal(503, 'DATABASE_UNAVAILABLE'))
    const page = await mountPage()
    await byId(page, 'edit-report.title').setValue('Annual report 2026')
    await ask(page, 'rename Title to Report title')

    expect(executeAssist).not.toHaveBeenCalled()
    expect(norm(page.findAll('.chat-line--brownie .field-error').at(-1)!.text())).toBe(
      'What you typed on this page could not be saved, so nothing else was changed.',
    )
    expect((page.get('#assist-composer').element as HTMLTextAreaElement).value).toBe('rename Title to Report title')
  })

  it('holds autosave while the chat changes a fill spot, and saves what was typed meanwhile once the page shows the new version', async () => {
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'ADD_FILL_SPOT', summary: 's', executable: true, usesModel: false, help: [] })
    let finish!: () => void
    vi.mocked(executeAssist).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = () => {
            serveVersion5()
            resolve({
              kind: 'ADD_FILL_SPOT',
              summary: 'Added a fill spot for Company after "Company:".',
              help: [],
              spotChange: { fieldId: 'company', label: 'Company', lineText: 'Company: ________', previousRevisionId: 10, templateVersionId: 5 },
            })
          }
        }),
    )
    vi.mocked(patchDocumentContent).mockResolvedValue(revision(12, 5, { contentHash: HASH_B }))
    const page = await mountPage()
    await ask(page, 'add a fill spot for Company after "Company:"')
    expect(executeAssist).toHaveBeenCalledWith(7, 1, 'add a fill spot for Company after "Company:"', 10)

    // The person keeps typing on the page while the form's new version is made.
    vi.useFakeTimers()
    await byId(page, 'edit-report.title').setValue('Annual report 2026')
    await vi.advanceTimersByTimeAsync(3_000)
    expect(patchDocumentContent).not.toHaveBeenCalled()

    finish()
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(3_000)
    vi.useRealTimers()
    await settle()
    expect(patchDocumentContent).toHaveBeenCalledTimes(1)
    // Saved against the version the change made, not the one it replaced.
    expect(vi.mocked(patchDocumentContent).mock.calls[0]![2].expectedRevisionId).toBe(11)
  })

  it('keeps a place Brownie found once the chat renames it', async () => {
    const found = { ...TITLE_FIELD, origin: 'FOUND_BY_BROWNIE' } as const
    vi.mocked(getTemplateVersion).mockImplementation(async (_w, _t, versionId) =>
      versionId === 5 ? version(5, [{ ...found, label: 'Report title' }]) : version(4, [found]),
    )
    vi.mocked(keepFillSpot).mockResolvedValue(undefined)
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'RENAME_FILL_SPOT', summary: 's', executable: true, usesModel: false, help: [] })
    vi.mocked(executeAssist).mockImplementation(async () => {
      serveVersion5()
      return {
        kind: 'RENAME_FILL_SPOT',
        summary: 'Renamed the fill spot Title to Report title.',
        help: [],
        spotChange: { fieldId: 'report.title', label: 'Report title', lineText: null, previousRevisionId: 10, templateVersionId: 5 },
      }
    })
    const page = await mountPage()
    await ask(page, 'rename Title to Report title')

    expect(keepFillSpot).toHaveBeenCalledWith(7, 1, 'report.title')
    expect(page.findAll('.fill-spot__found')).toHaveLength(0)
  })

  it("words the chat's refusals of a spot change the way the page does", async () => {
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'ADD_FILL_SPOT', summary: 's', executable: true, usesModel: false, help: [] })
    vi.mocked(executeAssist).mockRejectedValue(refusal(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'INSIDE_LINK' }))
    const page = await mountPage()
    await ask(page, 'add a fill spot for Company after "Company:"')

    expect(norm(page.findAll('.chat-line--brownie .field-error').at(-1)!.text())).toBe('Brownie cannot put a fill spot inside a link. Choose a place next to it.')
  })
})

describe('WorkspaceView: a newer version of the form', () => {
  const BEHIND = documentOn(R10, 4, 5)

  it('says what the newer version adds, and moves the document to it', async () => {
    vi.mocked(getDocument).mockResolvedValue(BEHIND)
    vi.mocked(moveDocumentToTemplateVersion).mockImplementation(async () => {
      serveVersion5()
      return { document: ON_5, revision: R11, previousTemplateVersionId: 4, droppedFieldIds: [] }
    })
    const page = await mountPage()

    const banner = page.get('.version-banner')
    expect(norm(banner.get('[role="status"]').text())).toBe('This form has a newer version. It adds the fill spot "Company".')
    expect(await axe(page.element)).toHaveNoViolations()
    await press(page, 'Move this document to it')

    expect(moveDocumentToTemplateVersion).toHaveBeenCalledWith(7, 1, 10, 5, expect.any(String))
    expect(page.find('.version-banner').exists()).toBe(false)
    expect(norm(page.get('.version-banner__moved').text())).toBe('Moved this document to the newest version of its form.')
    expect(byId(page, 'edit-company').exists()).toBe(true)
    expect(window.document.activeElement?.id).toBe('document-pane')
  })

  it('names the values the move dropped', async () => {
    vi.mocked(getDocument).mockResolvedValue(BEHIND)
    vi.mocked(moveDocumentToTemplateVersion).mockImplementation(async () => {
      serveVersion5()
      return { document: ON_5, revision: R11, previousTemplateVersionId: 4, droppedFieldIds: ['report.title'] }
    })
    const page = await mountPage()
    await press(page, 'Move this document to it')

    expect(chatLines(page).at(-1)).toBe(
      'Moved this document to the newest version of its form. Title has no fill spot in it, so its value was dropped. It stays in the version history.',
    )
  })

  it('says why the move was refused, and can be set aside for now', async () => {
    vi.mocked(getDocument).mockResolvedValue(BEHIND)
    vi.mocked(moveDocumentToTemplateVersion).mockRejectedValue(refusal(409, 'FILL_SPOT_LOCKED', { fieldId: 'report.title' }))
    const page = await mountPage()
    await press(page, 'Move this document to it')
    expect(norm(page.get('.version-banner [role="alert"]').text())).toBe('Unlock Title first; its value would be lost.')

    await press(page, 'Not now')
    expect(page.find('.version-banner').exists()).toBe(false)
    // The button that had focus went with the banner.
    expect(window.document.activeElement?.id).toBe('document-pane')
  })

  it('offers the move again after "Not now" when a spot change needs it', async () => {
    vi.mocked(getDocument).mockResolvedValue(BEHIND)
    const page = await mountPage()
    await press(page, 'Not now')
    await press(page, 'Add a fill spot')

    expect(dialogs(page)).toHaveLength(0)
    expect(page.find('.version-banner').exists()).toBe(true)
    expect(accessibleName(window.document.activeElement!)).toBe('Move this document to it')
  })

  it("offers the move again after \"Not now\" when the chat's spot change is refused for it", async () => {
    vi.mocked(getDocument).mockResolvedValue(BEHIND)
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'ADD_FILL_SPOT', summary: 's', executable: true, usesModel: false, help: [] })
    vi.mocked(executeAssist).mockRejectedValue(refusal(409, 'DOCUMENT_TEMPLATE_VERSION_MOVED'))
    const page = await mountPage()
    await press(page, 'Not now')
    const box = page.get('#assist-composer')
    await box.setValue('add a fill spot for Company after "Company:"')
    await box.trigger('keydown', { key: 'Enter' })
    await settle()

    expect(norm(page.findAll('.chat-line--brownie .field-error').at(-1)!.text())).toBe(
      'This document is on an older version of its form. Move it to the newest version first, then try again.',
    )
    expect(buttonNamed(page, 'Move this document to it')).toBeDefined()
  })

  it("offers the move again after \"Not now\" when the chat answers that the document must move first", async () => {
    const olderVersion = 'This document is on an older version of its form. Move it to the newest version first, then change its fill spots.'
    vi.mocked(getDocument).mockResolvedValue(BEHIND)
    // What the server answers for a spot change on a document behind its form: read, but not to be done.
    vi.mocked(interpretAssist).mockResolvedValue({ kind: 'RENAME_FILL_SPOT', summary: olderVersion, executable: false, usesModel: false, help: [] })
    const page = await mountPage()
    await press(page, 'Not now')
    expect(page.find('.version-banner').exists()).toBe(false)
    const box = page.get('#assist-composer')
    await box.setValue('rename Title to Report title')
    await box.trigger('keydown', { key: 'Enter' })
    await settle()

    expect(chatLines(page).at(-1)).toBe(olderVersion)
    expect(executeAssist).not.toHaveBeenCalled()
    expect(page.find('.version-banner').exists()).toBe(true)
    expect(buttonNamed(page, 'Move this document to it')).toBeDefined()
  })

  it('asks for the move before a spot is added to a document on an older version', async () => {
    vi.mocked(getDocument).mockResolvedValue(BEHIND)
    const page = await mountPage()
    await press(page, 'Add a fill spot')

    expect(dialogs(page)).toHaveLength(0)
    expect(norm(page.get('.workspace-notices [role="alert"]').text())).toBe(
      'This document is on an older version of its form. Move it to the newest version first, then change its fill spots.',
    )
    expect(accessibleName(window.document.activeElement!)).toBe('Move this document to it')
  })
})
