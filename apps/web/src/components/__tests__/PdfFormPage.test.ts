import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import type { PdfLayoutResponse, TemplateLayoutResponse } from '@/api/client'
import type { EditableField } from '@/workspace/layout'
import type { PdfSpotRequest, PdfSpotResult } from '@/workspace/pdfPage'
import { axe } from '@/test/axe'
import vectors from '../../../../../fixtures/public/pdf-geometry-vectors.json'
import type { CropBox, PdfBox, PdfPoint } from '@/workspace/pdfGeometry'

// PDF.js needs a real canvas and a worker; neither exists in jsdom, so the library is replaced with a
// fake that answers the calls the page makes, and the worker asset import with a path.
const { render, getPage, getDocument, destroy } = vi.hoisted(() => {
  const render = vi.fn(() => ({ promise: Promise.resolve(), cancel: vi.fn() }))
  const getPage = vi.fn(async () => ({
    getViewport: ({ scale }: { scale: number }) => ({ width: 612 * scale, height: 792 * scale }),
    render,
  }))
  const destroy = vi.fn(async () => {})
  const getDocument = vi.fn(() => ({ promise: Promise.resolve({ numPages: 2, getPage }), destroy }))
  return { render, getPage, getDocument, destroy }
})

vi.mock('pdfjs-dist/legacy/build/pdf.mjs', () => ({
  GlobalWorkerOptions: { workerSrc: '' },
  AnnotationMode: { DISABLE: 0, ENABLE: 1, ENABLE_FORMS: 2, ENABLE_STORAGE: 3 },
  version: '6.3.289',
  getDocument,
}))
vi.mock('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url', () => ({ default: '/fake-worker.mjs' }))

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, suggestBox: vi.fn() }
})

import { suggestBox } from '@/api/client'
import PdfFormPage from '@/components/workspace/PdfFormPage.vue'
import FillSpot from '@/components/workspace/FillSpot.vue'

type Spot = PdfLayoutResponse['spots'][number]

function box(fieldId: string, label: string, pageNumber: number, place: Spot['box'], extra: Partial<Spot> = {}): Spot {
  return {
    fieldId,
    label,
    origin: 'ADDED_BY_PERSON',
    pageNumber,
    box: place,
    style: { font: 'SANS', bold: false, sizePt: 11 },
    multiline: false,
    overflow: 'SHRINK_TO_FIT',
    bindingKind: 'PAGE_BOX',
    ...extra,
  }
}

function layout(pdf: Partial<PdfLayoutResponse> = {}): TemplateLayoutResponse {
  return {
    templateId: 4,
    versionId: 12,
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
            { index: 0, text: 'Membership application', x: 72, y: 60, w: 200, h: 14 },
            { index: 1, text: 'Company:', x: 72, y: 100, w: 60, h: 12 },
            { index: 2, text: 'Full name:', x: 72, y: 140, w: 60, h: 12 },
          ],
        },
        { pageNumber: 2, width: 612, height: 792, rotation: 0, hasText: true, lines: [{ index: 0, text: 'Signature:', x: 72, y: 700, w: 60, h: 12 }] },
      ],
      spots: [
        box('signed.on', 'Signed on', 2, { x: 140, y: 698, width: 120, height: 16 }),
        box('full.name', 'Full name', 1, { x: 140, y: 138, width: 300, height: 16 }, { bindingKind: 'ACROFORM_FIELD', style: null, origin: 'FORM' }),
        box('company', 'Company', 1, { x: 306, y: 98, width: 153, height: 16 }, { origin: 'FOUND_BY_BROWNIE', style: { font: 'SERIF', bold: true, sizePt: 9 } }),
        box('member.id', 'Member number', 1, { x: 470, y: 100, width: 80, height: 16 }),
      ],
      ...pdf,
    },
  }
}

const FIELDS: EditableField[] = [
  { fieldId: 'full.name', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'REQUIRED', label: 'Full name', origin: 'FORM' },
  { fieldId: 'company', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', label: 'Company', origin: 'FOUND_BY_BROWNIE' },
  { fieldId: 'member.id', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL', label: 'Member number', origin: 'ADDED_BY_PERSON' },
  { fieldId: 'signed.on', type: 'DATE', cardinality: 'SCALAR', requiredness: 'OPTIONAL', label: 'Signed on', origin: 'ADDED_BY_PERSON' },
]

let wrapper: VueWrapper | null = null
const fetchSpy = vi.fn()

beforeEach(() => {
  vi.stubGlobal('fetch', fetchSpy)
  fetchSpy.mockResolvedValue({ ok: true, status: 200, arrayBuffer: async () => new ArrayBuffer(8) })
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({} as unknown as CanvasRenderingContext2D)
  // Each page is drawn 612 pixels wide, one pixel to the point, at the top-left of the window; page 2 below page 1.
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const page = Number(this.dataset.page ?? 0)
    const top = page === 2 ? 800 : 0
    return { left: 0, top, right: 612, bottom: top + 792, width: 612, height: 792, x: 0, y: top, toJSON: () => ({}) } as DOMRect
  })
  vi.mocked(suggestBox).mockReset()
  render.mockClear()
  getPage.mockClear()
  getDocument.mockClear()
  destroy.mockClear()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

function mountPage(
  options: {
    layout?: TemplateLayoutResponse
    send?: (request: PdfSpotRequest) => Promise<PdfSpotResult>
    pickingPoint?: boolean
    found?: string[]
    previewDrafts?: Record<string, string | string[]> | null
    banner?: string
    required?: string[]
    questionCounts?: Record<string, number>
  } = {},
) {
  const send = vi.fn(options.send ?? (async (): Promise<PdfSpotResult> => ({ ok: true, focusId: null })))
  wrapper = mount(PdfFormPage, {
    attachTo: document.body,
    props: {
      layout: options.layout ?? layout(),
      layoutState: 'ready',
      fields: FIELDS,
      drafts: { 'full.name': 'Nguyễn Thị Minh Khai', company: '', 'member.id': '', 'signed.on': '' },
      previewDrafts: options.previewDrafts ?? null,
      revisionFields: null,
      requiredFieldIds: new Set(options.required ?? ['full.name']),
      lockedFieldIds: new Set<string>(),
      rowsLocked: false,
      selected: null,
      foundFieldIds: new Set(options.found ?? ['company']),
      questionCounts: options.questionCounts,
      workspaceId: 7,
      sendSpotChanges: send,
      pickingPoint: options.pickingPoint ?? false,
    },
    slots: options.banner === undefined ? undefined : { banner: options.banner },
  })
  return { wrapper, send }
}

/** A pointer event as a browser sends it; jsdom has no PointerEvent, so a mouse event carries the pointer's id. */
async function pointer(target: { element: Element }, type: string, init: { pointerId: number; clientX: number; clientY: number }): Promise<void> {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, button: 0, clientX: init.clientX, clientY: init.clientY })
  Object.defineProperty(event, 'pointerId', { value: init.pointerId })
  target.element.dispatchEvent(event)
  await flushPromises()
}

function rect(left: number, top: number, width: number, height: number): DOMRect {
  return { left, top, right: left + width, bottom: top + height, width, height, x: left, y: top, toJSON: () => ({}) } as DOMRect
}

/** The width the page view gives a page, as a browser lays it out in a pane `available` pixels wide. */
function laidOutWidth(pageNumber: number, available: number): number {
  const wrap = document.querySelector(`[data-page="${pageNumber}"]`)?.closest('.pdf-form-page__page-wrap')
  const css = /--pdf-page-width:\s*([^;]+)/.exec(wrap?.getAttribute('style') ?? '')?.[1]?.trim() ?? ''
  const fit = /^min\(100%, (\d+)px\)$/.exec(css)
  return fit ? Math.min(available, Number(fit[1])) : Number.parseFloat(css) || available
}

/**
 * Lays the pages out as a browser would, in a pane `available` pixels wide: each page as wide as the page
 * view says, its height in proportion to its size as shown, one below the other at the window's top-left.
 */
function layOutPages(available: number, shown: Record<number, { width: number; height: number }>): void {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const pageNumber = Number(this.dataset.page ?? 0)
    if (!shown[pageNumber]) return rect(0, 0, available, 600)
    const heightOf = (number: number) => (laidOutWidth(number, available) * shown[number]!.height) / shown[number]!.width
    let top = 0
    for (let earlier = 1; earlier < pageNumber; earlier++) top += heightOf(earlier) + 20
    return rect(0, top, laidOutWidth(pageNumber, available), heightOf(pageNumber))
  })
}

const LETTER_PAGES = { 1: { width: 612, height: 792 }, 2: { width: 612, height: 792 } }

/** A button of the zoom group, by the name a screen reader gives it. */
function zoomButton(name: 'Fit width' | 'Zoom in' | 'Zoom out') {
  const button = wrapper!
    .get('[role="group"][aria-label="Zoom"]')
    .findAll('button')
    .find((candidate) => candidate.text().replace(/^[−+]/, '') === name)
  if (!button) throw new Error(`No ${name} button`)
  return button
}

/** Where a spot's box is on its page, in pixels, from the percentages the page view places it by. */
function placedBox(fieldId: string, pageWidth: number, pageHeight: number): PdfBox {
  const style = wrapper!.get(`[data-field-id="${fieldId}"]`).attributes('style') ?? ''
  const percent = (property: string) => Number(new RegExp(`(?:^|;)\\s*${property}:\\s*([\\d.]+)%`).exec(style)?.[1] ?? Number.NaN) / 100
  return { x: percent('left') * pageWidth, y: percent('top') * pageHeight, width: percent('width') * pageWidth, height: percent('height') * pageHeight }
}

function inputIds(): string[] {
  return wrapper!.findAll('textarea, input[type="date"]').map((control) => control.attributes('id')!)
}

describe('PdfFormPage', () => {
  it('draws each page with PDF.js, as an image named by its page, with nothing of the PDF run', async () => {
    mountPage()
    await flushPromises()

    expect(fetchSpy).toHaveBeenCalledWith('/api/v1/workspaces/7/uploads/31/preview', { credentials: 'same-origin' })
    expect(getDocument).toHaveBeenCalledWith(
      expect.objectContaining({
        enableXfa: false,
        wasmUrl: '/assets/pdfjs-6.3.289/wasm/',
        standardFontDataUrl: '/assets/pdfjs-6.3.289/standard_fonts/',
        cMapUrl: '/assets/pdfjs-6.3.289/cmaps/',
      }),
    )
    expect(getPage.mock.calls.map((call) => (call as unknown[])[0])).toEqual([1, 2])
    expect(render).toHaveBeenCalledWith(expect.objectContaining({ annotationMode: 1 }))
    expect(wrapper!.findAll('canvas').map((canvas) => [canvas.attributes('role'), canvas.attributes('aria-label')])).toEqual([
      ['img', 'Page 1 of 2'],
      ['img', 'Page 2 of 2'],
    ])
  })

  it('lays each fill spot over its box, and the keyboard meets them page by page, top to bottom, left to right', async () => {
    mountPage()
    await flushPromises()

    expect(inputIds()).toEqual(['edit-company', 'edit-member.id', 'edit-full.name', 'edit-signed.on'])
    const company = wrapper!.get('[data-field-id="company"]')
    expect(company.attributes('style')).toContain('left: 50%')
    expect(company.attributes('style')).toContain('top: 12.3737%')
    expect(company.attributes('style')).toContain('width: 25%')
    // The box's own look, scaled with the page: 9 pt bold serif, never smaller than the floor the page sets while it is typed in.
    const control = wrapper!.get('#edit-company')
    expect(control.attributes('style')).toContain('font-weight: 700')
    expect(control.attributes('style')).toContain('font-size: max(var(--pdf-min-text, 0px), calc(9 * var(--pdf-point, 1px)))')
    expect(wrapper!.get('[data-page="1"]').attributes('style')).toContain('--pdf-point: calc(100cqw / 612)')
    // Named for what goes there, and marked where Brownie found the place itself: by the shape of its outline,
    // which the legend names, since a label over the page would cover the form's own words; and in words in its description.
    expect(wrapper!.get('#edit-full\\.name').attributes('aria-label')).toBe('Full name, required')
    expect(company.get('.fill-spot').classes()).toContain('fill-spot--found')
    expect(wrapper!.get('[data-field-id="member.id"] .fill-spot').classes()).not.toContain('fill-spot--found')
    expect(wrapper!.get('.pdf-form-page__legend').text()).toContain('Dashed outline: found by Brownie, check it')
    const described = (control.attributes('aria-describedby') ?? '').split(' ').map((id) => document.getElementById(id)?.textContent ?? '')
    expect(described.join(' ')).toContain('Found by Brownie; check that this is the right place.')
    expect(wrapper!.text()).toContain('1 of 4 fill spots filled')
  })

  it('repeats each page’s text below it, and passes an accessibility scan', async () => {
    mountPage()
    await flushPromises()

    const texts = wrapper!.findAll('details')
    expect(texts.map((details) => details.get('summary').text())).toEqual(['Text on this page (page 1 of 2)', 'Text on this page (page 2 of 2)'])
    expect(texts[0]!.findAll('li').map((line) => line.text())).toEqual(['Membership application', 'Company:', 'Full name:'])
    expect((await axe(wrapper!.element)).violations).toEqual([])
  })

  it('reports typing, focus and the key for a spot’s bar the way the Word page does', async () => {
    mountPage()
    await flushPromises()

    await wrapper!.get('#edit-company').setValue('Brownie Bakery')
    await wrapper!.get('#edit-company').trigger('focus')
    await wrapper!.get('#edit-company').trigger('keydown', { key: 'Enter', altKey: true })

    expect(wrapper!.emitted('update-scalar')).toEqual([['company', 'Brownie Bakery']])
    expect(wrapper!.emitted('select')).toEqual([[{ fieldId: 'company', rowIndex: null }]])
    expect(wrapper!.emitted('open-actions')).toEqual([[{ fieldId: 'company', rowIndex: null }]])
  })

  it('lists a field that has no place on any page, so it can still be filled', async () => {
    mountPage({ layout: layout({ spots: [] }) })
    await flushPromises()

    expect(wrapper!.text()).toContain('Other fill spots')
    expect(inputIds()).toEqual(['edit-full.name', 'edit-company', 'edit-member.id', 'edit-signed.on'])
  })

  it('says what a scanned PDF needs, next to Draw a box, with no spots at all', async () => {
    mountPage({
      layout: layout({ pages: [{ pageNumber: 1, width: 612, height: 792, rotation: 0, hasText: false, lines: [] }], spots: [] }),
    })
    await flushPromises()

    expect(wrapper!.text()).toContain('This PDF is a scan, so Brownie cannot read its words. Draw a box wherever something should be filled in.')
    expect(wrapper!.text()).toContain('Brownie cannot read the words on this page, because it is a scan.')
    expect(wrapper!.find('button[aria-pressed]').text()).toBe('Draw a box')
  })

  it('draws a box with the pointer and asks for its name, with the words beside it for its look', async () => {
    vi.mocked(suggestBox).mockResolvedValue({ box: { x: 1, y: 1, width: 144, height: 14 }, style: { font: 'SERIF', bold: false, sizePt: 10 }, labelGuess: 'Company' })
    const { send } = mountPage()
    await flushPromises()

    const draw = wrapper!.get('button[aria-pressed]')
    await draw.trigger('click')
    expect(draw.attributes('aria-pressed')).toBe('true')
    expect(wrapper!.get('[role="status"]').text()).toContain('Drag across the page where the box goes')

    const surface = wrapper!.findAll('.pdf-form-page__surface')[0]!
    await pointer(surface, 'pointerdown', { pointerId: 1, clientX: 140, clientY: 180 })
    await pointer(surface, 'pointermove', { pointerId: 1, clientX: 300, clientY: 196 })
    expect(wrapper!.find('.pdf-form-page__drawn').exists()).toBe(true)
    await pointer(surface, 'pointerup', { pointerId: 1, clientX: 300, clientY: 196 })
    await flushPromises()

    expect(suggestBox).toHaveBeenCalledWith(7, 4, 12, { pageNumber: 1, point: { x: 140, y: 188 } })
    expect(wrapper!.get('button[aria-pressed]').attributes('aria-pressed')).toBe('false')
    expect(wrapper!.get('dialog h2').text()).toBe('Name the fill spot')
    expect(wrapper!.get<HTMLInputElement>('dialog input[type="text"]').element.value).toBe('Company')

    await wrapper!.get('dialog form').trigger('submit')
    await flushPromises()
    expect(send.mock.calls[0]![0]).toEqual({
      kind: 'add',
      label: 'Company',
      changes: [
        {
          kind: 'ADD_BOX',
          pageNumber: 1,
          box: { x: 140, y: 180, width: 160, height: 16 },
          label: 'Company',
          type: 'TEXT',
          style: { font: 'SERIF', bold: false, sizePt: 10 },
          overflow: 'SHRINK_TO_FIT',
        },
      ],
    })
  })

  it('takes a click while drawing as the place for the box the server suggests there', async () => {
    vi.mocked(suggestBox).mockResolvedValue({ box: { x: 150, y: 430, width: 144, height: 14 }, style: { font: 'SANS', bold: false, sizePt: 11 }, labelGuess: null })
    mountPage()
    await flushPromises()

    await wrapper!.get('button[aria-pressed]').trigger('click')
    const page2 = wrapper!.findAll('.pdf-form-page__surface')[1]!
    await pointer(page2, 'pointerdown', { pointerId: 3, clientX: 150, clientY: 1237 })
    await pointer(page2, 'pointerup', { pointerId: 3, clientX: 151, clientY: 1238 })
    await flushPromises()

    expect(suggestBox).toHaveBeenCalledWith(7, 4, 12, { pageNumber: 2, point: { x: 150, y: 437 } })
    expect(wrapper!.get('dialog').text()).toContain('On page 2, where you drew it.')
  })

  it('stops drawing with Escape, dropping a box being dragged first', async () => {
    mountPage()
    await flushPromises()
    const draw = wrapper!.get('button[aria-pressed]')
    await draw.trigger('click')
    const surface = wrapper!.findAll('.pdf-form-page__surface')[0]!
    await pointer(surface, 'pointerdown', { pointerId: 1, clientX: 10, clientY: 10 })
    await pointer(surface, 'pointermove', { pointerId: 1, clientX: 90, clientY: 30 })

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(wrapper!.find('.pdf-form-page__drawn').exists()).toBe(false)
    expect(draw.attributes('aria-pressed')).toBe('true')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(draw.attributes('aria-pressed')).toBe('false')
    expect(document.activeElement).toBe(draw.element)
    expect(suggestBox).not.toHaveBeenCalled()
  })

  it('moves and sizes the boxes Brownie drew with the arrow keys, and sends every move at once on Done', async () => {
    const { send } = mountPage({ send: async () => ({ ok: true, focusId: 'edit-company' }) })
    await flushPromises()

    const edit = wrapper!.findAll('button').find((button) => button.text() === 'Edit boxes')!
    await edit.trigger('click')
    await flushPromises()

    // A box of the PDF's own form cannot be moved: it gets no handle, and the page says why.
    const handles = wrapper!.findAll('.pdf-form-page__handle')
    expect(handles.map((handle) => handle.attributes('aria-label'))).toEqual(['Move or resize Company', 'Move or resize Member number', 'Move or resize Signed on'])
    expect(document.activeElement).toBe(handles[0]!.element)
    expect(wrapper!.text()).toContain('Boxes that belong to the PDF’s own form stay where the form puts them')
    // Every handle is dashed and none is marked found, so the legend does not say what a dashed outline means here.
    expect(wrapper!.text()).not.toContain('Dashed outline: found by Brownie')

    await handles[0]!.trigger('keydown', { key: 'ArrowRight' })
    await handles[0]!.trigger('keydown', { key: 'ArrowDown', shiftKey: true })
    await handles[0]!.trigger('keydown', { key: 'ArrowRight', altKey: true })
    expect(wrapper!.get('[role="status"]').text()).toBe('Company: 154 by 16 points, 307 from the left and 108 from the top.')
    await handles[2]!.trigger('keydown', { key: 'ArrowUp' })

    await wrapper!.findAll('button').find((button) => button.text() === 'Done')!.trigger('click')
    await flushPromises()

    expect(send).toHaveBeenCalledTimes(1)
    expect(send.mock.calls[0]![0]).toEqual({
      kind: 'move',
      label: 'Company',
      changes: [
        { kind: 'MOVE_BOX', fieldId: 'company', box: { x: 307, y: 108, width: 154, height: 16 } },
        { kind: 'MOVE_BOX', fieldId: 'signed.on', box: { x: 140, y: 697, width: 120, height: 16 } },
      ],
    })
    expect(wrapper!.findAll('.pdf-form-page__handle')).toHaveLength(0)
    expect(document.activeElement).toBe(wrapper!.get('#edit-company').element)
    expect(wrapper!.get('.pdf-form-page__legend').text()).toContain('Dashed outline: found by Brownie, check it')
  })

  it('keeps the moves when they are refused, and says why', async () => {
    mountPage({ send: async () => ({ ok: false, message: 'That box covers another fill spot; move it a little.' }) })
    await flushPromises()
    await wrapper!.findAll('button').find((button) => button.text() === 'Edit boxes')!.trigger('click')
    await flushPromises()
    await wrapper!.findAll('.pdf-form-page__handle')[0]!.trigger('keydown', { key: 'ArrowLeft', shiftKey: true })
    await wrapper!.findAll('button').find((button) => button.text() === 'Done')!.trigger('click')
    await flushPromises()

    expect(wrapper!.get('[role="alert"]').text()).toBe('That box covers another fill spot; move it a little.')
    expect(wrapper!.findAll('.pdf-form-page__handle')).toHaveLength(3)
  })

  it('moves a box by dragging it, and sizes it by its corner grip', async () => {
    const { send } = mountPage()
    await flushPromises()
    await wrapper!.findAll('button').find((button) => button.text() === 'Edit boxes')!.trigger('click')
    await flushPromises()

    const member = wrapper!.findAll('.pdf-form-page__handle')[1]!
    await pointer(member, 'pointerdown', { pointerId: 5, clientX: 480, clientY: 105 })
    await pointer(member, 'pointermove', { pointerId: 5, clientX: 470, clientY: 125 })
    await pointer(member, 'pointerup', { pointerId: 5, clientX: 470, clientY: 125 })
    await pointer({ element: member.get('.pdf-form-page__grip').element }, 'pointerdown', { pointerId: 6, clientX: 540, clientY: 136 })
    await pointer(member, 'pointermove', { pointerId: 6, clientX: 560, clientY: 140 })
    await pointer(member, 'pointerup', { pointerId: 6, clientX: 560, clientY: 140 })
    expect(wrapper!.get('[role="status"]').text()).toBe('Member number: 100 by 20 points, 460 from the left and 120 from the top.')

    await wrapper!.findAll('button').find((button) => button.text() === 'Done')!.trigger('click')
    await flushPromises()
    expect(send.mock.calls[0]![0].changes).toEqual([{ kind: 'MOVE_BOX', fieldId: 'member.id', box: { x: 460, y: 120, width: 100, height: 20 } }])
  })

  it('reaches a new box by keyboard: a page, one of its lines, then its name', async () => {
    vi.mocked(suggestBox).mockResolvedValue({ box: { x: 140, y: 98, width: 150, height: 14 }, style: { font: 'SANS', bold: false, sizePt: 11 }, labelGuess: 'Company' })
    const { send } = mountPage()
    await flushPromises()

    await wrapper!.findAll('button').find((button) => button.text() === 'Add a fill spot')!.trigger('click')
    await flushPromises()
    expect(wrapper!.get('dialog h2').text()).toBe('Add a fill spot')
    await wrapper!.get('dialog select[size]').setValue('1')
    await wrapper!.get('dialog form').trigger('submit')
    await flushPromises()
    expect(suggestBox).toHaveBeenCalledWith(7, 4, 12, { pageNumber: 1, lineIndex: 1 })

    await wrapper!.get('dialog form').trigger('submit')
    await flushPromises()
    expect(send.mock.calls[0]![0].changes[0]).toMatchObject({ kind: 'ADD_BOX', pageNumber: 1, label: 'Company', box: { x: 140, y: 98, width: 150, height: 14 } })
  })

  it('picks the point a click on a turned page means for the chat, in the page’s stored measure', async () => {
    const turned = layout({ pages: [{ pageNumber: 1, width: 612, height: 792, rotation: 90, hasText: true, lines: [] }], spots: [] })
    mountPage({ layout: turned, pickingPoint: true })
    await flushPromises()

    // Shown 792 points wide at 612 pixels: a click at (87, 105) pixels is (112.59, 135.88) shown.
    await wrapper!.find('.pdf-form-page__surface').trigger('click', { clientX: 87, clientY: 105 })
    const [anchor] = wrapper!.emitted('pick-point')![0] as [{ kind: string; pageNumber: number; point: { x: number; y: number } }]
    expect(anchor.kind).toBe('PDF')
    expect(anchor.pageNumber).toBe(1)
    expect(anchor.point.x).toBeCloseTo(135.88, 1)
    expect(anchor.point.y).toBeCloseTo(792 - 112.59, 1)
    expect(wrapper!.find('.pdf-form-page__picked').exists()).toBe(true)
    expect(wrapper!.get('[role="status"]').text()).toBe('The place on page 1 of 1 is picked. Send your message to add the fill spot there.')
  })

  it('opens the questions the bar asks about a spot: rename, text size, remove', async () => {
    const { send } = mountPage()
    await flushPromises()
    const page = wrapper!.vm as unknown as {
      openRename: (fieldId: string) => void
      openRestyle: (fieldId: string) => void
      openRemove: (fieldId: string) => void
      isFormField: (fieldId: string) => boolean | null
    }
    expect([page.isFormField('full.name'), page.isFormField('company'), page.isFormField('nothing')]).toEqual([true, false, null])

    page.openRestyle('company')
    await flushPromises()
    expect(wrapper!.get('dialog h2').text()).toBe('Text size and overflow for Company')
    expect(wrapper!.get<HTMLInputElement>('dialog input[type="number"]').element.value).toBe('9')
    await wrapper!.get('dialog input[value="BLOCK"]').setValue(true)
    await wrapper!.get('dialog form').trigger('submit')
    await flushPromises()
    expect(send.mock.calls[0]![0].changes).toEqual([{ kind: 'RESTYLE_BOX', fieldId: 'company', sizePt: 9, overflow: 'BLOCK' }])

    page.openRemove('full.name')
    await flushPromises()
    expect(wrapper!.get('dialog').text()).toContain('The form keeps its own box; Brownie just stops filling it.')
  })

  it('fits each page to the pane, no larger than an easy reading size, and draws it as sharp as the screen shows', async () => {
    vi.stubGlobal('devicePixelRatio', 2)
    layOutPages(464, LETTER_PAGES)
    mountPage()
    await flushPromises()

    expect(wrapper!.findAll('.pdf-form-page__page-wrap').map((wrap) => wrap.attributes('style'))).toEqual([
      '--pdf-page-width: min(100%, 918px);',
      '--pdf-page-width: min(100%, 918px);',
    ])
    expect(zoomButton('Fit width').attributes('aria-pressed')).toBe('true')
    expect(wrapper!.get('[role="group"][aria-label="Zoom"]').text()).toContain('Shown at 57%')
    // Drawn 464 pixels wide, at two canvas pixels to each.
    const [drawn] = render.mock.calls[0] as unknown as [{ viewport: { width: number; height: number }; transform?: number[] }]
    expect(drawn.viewport.width).toBeCloseTo(464, 6)
    expect(drawn.transform).toEqual([2, 0, 0, 2, 0, 0])
    const canvas = wrapper!.get('canvas').element as HTMLCanvasElement
    expect([canvas.width, canvas.height]).toEqual([928, 1200])
  })

  it('zooms in and out with the buttons, says each size, and draws again at it', async () => {
    vi.stubGlobal('devicePixelRatio', 2)
    layOutPages(464, LETTER_PAGES)
    mountPage()
    await flushPromises()
    const status = () => wrapper!.get('.pdf-form-page__status').text()
    const firstWidth = () => wrapper!.get('.pdf-form-page__page-wrap').attributes('style')

    await zoomButton('Zoom in').trigger('click')
    await flushPromises()
    expect(status()).toBe('Zoomed to 75%.')
    expect(firstWidth()).toBe('--pdf-page-width: 612px;')
    expect(zoomButton('Fit width').attributes('aria-pressed')).toBe('false')
    expect((wrapper!.get('canvas').element as HTMLCanvasElement).width).toBe(1224)

    await zoomButton('Zoom in').trigger('click')
    await flushPromises()
    expect(status()).toBe('Zoomed to 100%.')
    expect(firstWidth()).toBe('--pdf-page-width: 816px;')
    const last = render.mock.calls[render.mock.calls.length - 1] as unknown as [{ viewport: { width: number } }]
    expect(last[0].viewport.width).toBeCloseTo(816, 6)

    // At the largest size, Zoom in says it can go no further and keeps the focus, so the keyboard is not lost.
    const zoomIn = zoomButton('Zoom in')
    ;(zoomIn.element as HTMLButtonElement).focus()
    for (let press = 0; press < 6; press++) {
      await zoomIn.trigger('click')
      await flushPromises()
    }
    expect(status()).toBe('Zoomed to 200%.')
    expect(firstWidth()).toBe('--pdf-page-width: 1632px;')
    expect(zoomIn.attributes('aria-disabled')).toBe('true')
    expect(document.activeElement).toBe(zoomIn.element)

    for (let press = 0; press < 6; press++) {
      await zoomButton('Zoom out').trigger('click')
      await flushPromises()
    }
    expect(status()).toBe('Zoomed to 50%.')
    expect(zoomButton('Zoom out').attributes('aria-disabled')).toBe('true')

    await zoomButton('Fit width').trigger('click')
    await flushPromises()
    expect(status()).toBe('The pages fit the width, at 57%.')
    expect(firstWidth()).toBe('--pdf-page-width: min(100%, 918px);')
    expect(zoomButton('Fit width').attributes('aria-pressed')).toBe('true')
    expect((await axe(wrapper!.element)).violations).toEqual([])
  })

  it('draws a page once when a zoom and the resize it causes ask for the same picture', async () => {
    // pdf.js draws until the test says it is done; cancelling a drawing fails it the way pdf.js does.
    const drawing: { canvas: HTMLCanvasElement; finish: () => void; cancel: ReturnType<typeof vi.fn> }[] = []
    render.mockImplementation(((options: { canvas: HTMLCanvasElement }) => {
      let finish = () => {}
      let fail = (_: Error) => {}
      const promise = new Promise<void>((resolve, reject) => {
        finish = resolve
        fail = reject
      })
      const cancel = vi.fn(() => fail(Object.assign(new Error('Rendering cancelled'), { name: 'RenderingCancelledException' })))
      drawing.push({ canvas: options.canvas, finish, cancel })
      return { promise, cancel }
    }) as unknown as Parameters<typeof render.mockImplementation>[0])
    const finishAll = async () => {
      for (let round = 0; round < 6; round++) {
        drawing.forEach((entry) => entry.finish())
        await flushPromises()
      }
    }
    let resized: (entries: { contentRect: { width: number } }[]) => void = () => {}
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: (entries: { contentRect: { width: number } }[]) => void) {
          resized = callback
        }
        observe() {}
        disconnect() {}
      },
    )
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      layOutPages(464, LETTER_PAGES)
      mountPage()
      await flushPromises()
      await finishAll()
      const firstCanvas = wrapper!.get('canvas').element
      const drawnBefore = drawing.length

      await zoomButton('Zoom in').trigger('click')
      await flushPromises()
      const zoomed = drawing.slice(drawnBefore).filter((entry) => entry.canvas === firstCanvas)
      expect(zoomed).toHaveLength(1)

      // The page grew, and the pane's scroll bar narrowed it, so the observer fires; once it settles, its pass
      // finds page 1 already being drawn that way.
      resized([{ contentRect: { width: 452 } }])
      vi.advanceTimersByTime(200)
      await flushPromises()
      expect(zoomed[0]!.cancel).not.toHaveBeenCalled()
      await finishAll()
      expect(drawing.slice(drawnBefore).filter((entry) => entry.canvas === firstCanvas)).toHaveLength(1)
      expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('measures the pages again in the next frame after the width changes, never while sizes are being reported, and not for height alone', async () => {
    let resized: (entries: { contentRect: { width: number; height: number } }[]) => void = () => {}
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: (entries: { contentRect: { width: number; height: number } }[]) => void) {
          resized = callback
        }
        observe() {}
        disconnect() {}
      },
    )
    const frames: (() => void)[] = []
    vi.stubGlobal('requestAnimationFrame', (callback: () => void) => frames.push(callback))
    vi.stubGlobal('cancelAnimationFrame', () => {})
    layOutPages(464, LETTER_PAGES)
    mountPage()
    await flushPromises()
    const shown = () => wrapper!.get('.pdf-form-page__zoom-level').text()
    const before = shown()

    resized([{ contentRect: { width: 464, height: 900 } }])
    layOutPages(300, LETTER_PAGES)
    resized([{ contentRect: { width: 300, height: 900 } }])
    await flushPromises()
    // Nothing on the page changed while the sizes were being reported.
    expect(shown()).toBe(before)
    expect(frames.length).toBeGreaterThan(0)

    frames.splice(0).forEach((frame) => frame())
    await flushPromises()
    expect(shown()).not.toBe(before)

    // Taller only (the way across appearing under the pages, say): nothing to measure again.
    resized([{ contentRect: { width: 300, height: 1200 } }])
    expect(frames).toHaveLength(0)
  })

  it('keeps a way across a page zoomed wider than the pane at the foot of the view, moving with the page', async () => {
    layOutPages(464, LETTER_PAGES)
    mountPage()
    await flushPromises()
    const pagesBox = wrapper!.get('.pdf-form-page__pages').element as HTMLElement
    // jsdom lays nothing out: the pages box is as wide as the pane, and as wide inside as its pages.
    let inside = 452
    Object.defineProperty(pagesBox, 'clientWidth', { configurable: true, get: () => 452 })
    Object.defineProperty(pagesBox, 'scrollWidth', { configurable: true, get: () => inside })
    expect(wrapper!.find('.pdf-form-page__across').exists()).toBe(false)

    inside = 820
    await zoomButton('Zoom in').trigger('click')
    await zoomButton('Zoom in').trigger('click')
    await flushPromises()
    const bar = wrapper!.get('.pdf-form-page__across')
    expect(bar.attributes('aria-hidden')).toBe('true')
    expect(bar.attributes('tabindex')).toBe('-1')
    expect(bar.get('.pdf-form-page__across-width').attributes('style')).toBe('inline-size: 820px;')
    expect(pagesBox.classList.contains('pdf-form-page__pages--across')).toBe(true)

    bar.element.scrollLeft = 184
    await bar.trigger('scroll')
    expect(pagesBox.scrollLeft).toBe(184)
    pagesBox.scrollLeft = 40
    await wrapper!.get('.pdf-form-page__pages').trigger('scroll')
    expect(bar.element.scrollLeft).toBe(40)
    expect((await axe(wrapper!.element)).violations).toEqual([])

    inside = 452
    await zoomButton('Fit width').trigger('click')
    await flushPromises()
    expect(wrapper!.find('.pdf-form-page__across').exists()).toBe(false)
    expect(pagesBox.classList.contains('pdf-form-page__pages--across')).toBe(false)
  })

  it('draws a box with the pointer on a zoomed page where it was drawn', async () => {
    vi.mocked(suggestBox).mockResolvedValue({ box: { x: 1, y: 1, width: 144, height: 14 }, style: { font: 'SERIF', bold: false, sizePt: 10 }, labelGuess: 'Company' })
    layOutPages(464, LETTER_PAGES)
    const { send } = mountPage()
    await flushPromises()
    await zoomButton('Zoom in').trigger('click')
    await zoomButton('Zoom in').trigger('click')
    await flushPromises()
    expect(wrapper!.get('.pdf-form-page__page-wrap').attributes('style')).toBe('--pdf-page-width: 816px;')

    // At 100%, 4/3 of a pixel to the point: the same box the page at 75% draws from (140, 180) to (300, 196).
    const scale = 816 / 612
    await wrapper!.get('button[aria-pressed="false"]').trigger('click')
    expect(wrapper!.get('.pdf-form-page__tools button[aria-pressed]').attributes('aria-pressed')).toBe('true')
    const surface = wrapper!.findAll('.pdf-form-page__surface')[0]!
    await pointer(surface, 'pointerdown', { pointerId: 1, clientX: 140 * scale, clientY: 180 * scale })
    await pointer(surface, 'pointermove', { pointerId: 1, clientX: 300 * scale, clientY: 196 * scale })
    await pointer(surface, 'pointerup', { pointerId: 1, clientX: 300 * scale, clientY: 196 * scale })
    await flushPromises()
    expect(suggestBox).toHaveBeenCalledWith(7, 4, 12, { pageNumber: 1, point: { x: 140, y: 188 } })

    await wrapper!.get('dialog form').trigger('submit')
    await flushPromises()
    expect(send.mock.calls[0]![0].changes[0]).toMatchObject({ kind: 'ADD_BOX', pageNumber: 1, box: { x: 140, y: 180, width: 160, height: 16 } })
  })

  describe('on a turned page, at every size', () => {
    interface Vector {
      name: string
      cropBox: CropBox
      rotation: 0 | 90 | 180 | 270
      box: PdfBox
      displayed: PdfBox
      point: PdfPoint
      displayedPoint: PdfPoint
    }
    const turned = (vectors as { cases: Vector[] }).cases.filter((vector) => vector.rotation !== 0)

    for (const vector of turned) {
      it(`keeps the box on its place, and a pick there means the same point: ${vector.name}`, async () => {
        const { cropBox: crop, rotation } = vector
        const quarter = rotation % 180 !== 0
        const shown = { width: quarter ? crop.height : crop.width, height: quarter ? crop.width : crop.height }
        // A wide pane: Fit width draws the page at its largest, 1.5 pixels to the point.
        layOutPages(4000, { 1: shown })
        mountPage({
          layout: layout({
            pages: [{ pageNumber: 1, width: crop.width, height: crop.height, rotation, hasText: true, lines: [] }],
            spots: [box('member.id', 'Member number', 1, vector.box)],
          }),
          pickingPoint: true,
        })
        await flushPromises()

        const seen: number[] = []
        // Fit width, then 100% and 75% on the way down, then 100%, 125% and 150% on the way up.
        for (const step of [null, 'Zoom out', 'Zoom out', 'Zoom in', 'Zoom in', 'Zoom in'] as const) {
          if (step) await zoomButton(step).trigger('click')
          await flushPromises()
          const width = laidOutWidth(1, 4000)
          const scale = width / shown.width
          // A zoomed page is drawn whole pixels wide, so its scale can differ from the step's in the third decimal.
          seen.push(Math.round(scale * 100) / 100)

          const placed = placedBox('member.id', width, shown.height * scale)
          for (const key of ['x', 'y', 'width', 'height'] as const) expect(placed[key], `${key} at ${scale}`).toBeCloseTo(vector.displayed[key] * scale, 2)

          await wrapper!.get('.pdf-form-page__surface').trigger('click', { clientX: vector.displayedPoint.x * scale, clientY: vector.displayedPoint.y * scale })
          const picks = wrapper!.emitted('pick-point')!
          const [anchor] = picks[picks.length - 1] as [{ point: PdfPoint }]
          expect(anchor.point.x, `x at ${scale}`).toBeCloseTo(vector.point.x, 1)
          expect(anchor.point.y, `y at ${scale}`).toBeCloseTo(vector.point.y, 1)
        }
        expect(seen).toEqual([1.5, 1.33, 1, 1.33, 1.67, 2])
      })
    }
  })

  it('tells a box Brownie found from the others by its look, and the legend says what the look means, until it is kept', async () => {
    mountPage()
    await flushPromises()

    // The spot itself draws the dashed edge; the form's own field is the other kind of outline, drawn by the page.
    const company = wrapper!.get('[data-field-id="company"]')
    expect(company.get('.fill-spot').classes()).toContain('fill-spot--found')
    expect(wrapper!.findAll('.fill-spot--found')).toHaveLength(1)
    expect(wrapper!.get('[data-field-id="full.name"]').classes()).toContain('pdf-form-page__spot--own')
    expect(company.classes()).not.toContain('pdf-form-page__spot--own')
    const legend = wrapper!.get('.pdf-form-page__legend')
    expect(legend.text()).toContain('Dashed outline: found by Brownie, check it')
    expect(legend.text()).toContain('Solid outline: the PDF’s own form field')
    expect(legend.findAll('[aria-hidden="true"]')).toHaveLength(2)

    // The box the bar is about is still known to be found.
    await wrapper!.setProps({ selected: { fieldId: 'company', rowIndex: null } })
    expect(company.get('.fill-spot').classes()).toContain('fill-spot--found')
    expect(company.classes()).toContain('pdf-form-page__spot--selected')

    // In Edit boxes each box is a handle to move, and none is marked found, so the legend does not explain a dashed outline.
    await wrapper!.findAll('button').find((button) => button.text() === 'Edit boxes')!.trigger('click')
    await flushPromises()
    expect(wrapper!.find('.fill-spot--found').exists()).toBe(false)
    expect(wrapper!.get('.pdf-form-page__legend').text()).not.toContain('Dashed outline')
    expect(wrapper!.get('.pdf-form-page__legend').text()).toContain('Solid outline')
    await wrapper!.findAll('button').find((button) => button.text() === 'Cancel')!.trigger('click')
    await flushPromises()

    // Kept: no dashed look, and the legend no longer explains one.
    await wrapper!.setProps({ foundFieldIds: new Set<string>() })
    expect(wrapper!.find('.fill-spot--found').exists()).toBe(false)
    expect(wrapper!.get('.pdf-form-page__legend').text()).not.toContain('Dashed outline')
  })

  it('names no outline in its legend that the page does not show', async () => {
    // No box found, and none of the form's own fields: nothing to explain.
    mountPage({
      layout: layout({ spots: [box('member.id', 'Member number', 1, { x: 470, y: 100, width: 80, height: 16 })] }),
      found: [],
    })
    await flushPromises()
    expect(wrapper!.find('.pdf-form-page__legend').exists()).toBe(false)
  })

  it('says how many places are filled and which mark means required, in a line of its own', async () => {
    mountPage()
    await flushPromises()
    const line = wrapper!.get('.pdf-form-page__progress')
    expect(line.text()).toContain('1 of 4 fill spots filled')
    expect(line.text()).toContain('Required before export')
    // The mark itself is only a picture of what the words say.
    expect(line.get('[aria-hidden="true"]').text()).toBe('*')

    wrapper!.unmount()
    mountPage({ required: [] })
    await flushPromises()
    expect(wrapper!.get('.pdf-form-page__progress').text()).toBe('1 of 4 fill spots filled')
  })

  it('has the four tools in a row over the page, each with a picture and its name, and Next empty spot only while a place is empty', async () => {
    mountPage()
    await flushPromises()

    const tools = wrapper!.get('.pdf-form-page__tools')
    expect(tools.findAll('button').map((button) => button.text())).toEqual(['Next empty spot', 'Add a fill spot', 'Draw a box', 'Edit boxes'])
    for (const button of tools.findAll('button')) {
      expect(button.find('svg[aria-hidden="true"]').exists(), button.text()).toBe(true)
      expect(button.classes()).toEqual(expect.arrayContaining(['button', 'button--secondary', 'button--small']))
    }
    // The tools sit above the pages.
    expect(tools.element.compareDocumentPosition(wrapper!.get('.pdf-form-page__pages').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    // With every place filled there is no next empty one to go to.
    await wrapper!.setProps({ drafts: { 'full.name': 'A', company: 'B', 'member.id': 'C', 'signed.on': '2026-10-01' } })
    expect(wrapper!.get('.pdf-form-page__tools').findAll('button').map((button) => button.text())).toEqual(['Add a fill spot', 'Draw a box', 'Edit boxes'])
  })

  it('goes to the next empty place in the order the keyboard meets them, and round to the first again', async () => {
    mountPage()
    await flushPromises()
    const next = () => wrapper!.findAll('button').find((button) => button.text() === 'Next empty spot')!

    const visited: (string | null)[] = []
    for (let press = 0; press < 4; press++) {
      await next().trigger('click')
      visited.push(document.activeElement?.id ?? null)
    }
    // Full name holds a value, so it is passed over.
    expect(visited).toEqual(['edit-company', 'edit-member.id', 'edit-signed.on', 'edit-company'])
  })

  it('puts the zoom ahead of the boxes for the keyboard, though it floats at the foot of the pages', async () => {
    mountPage()
    await flushPromises()
    const zoom = wrapper!.get('[role="group"][aria-label="Zoom"]').element
    expect(zoom.compareDocumentPosition(wrapper!.get('.pdf-form-page__spot').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(wrapper!.get('.pdf-form-page__tools').element.compareDocumentPosition(zoom) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('shows Done and Cancel in place of the other tools while boxes are being moved', async () => {
    mountPage()
    await flushPromises()
    await wrapper!.findAll('button').find((button) => button.text() === 'Edit boxes')!.trigger('click')
    await flushPromises()

    const tools = wrapper!.get('.pdf-form-page__tools')
    expect(tools.findAll('button').map((button) => button.text())).toEqual(['Done', 'Cancel'])
    expect(tools.findAll('button')[0]!.classes()).toContain('button--primary')
  })

  it('has a place between the tools and the pages for what the screen has to say about the document', async () => {
    mountPage({ banner: '<p id="note">Brownie found 1 more place to fill in.</p>' })
    await flushPromises()

    const note = wrapper!.get('#note')
    expect(note.element.closest('.pdf-form-page__banner')).not.toBeNull()
    expect(wrapper!.get('.pdf-form-page__tools').element.compareDocumentPosition(note.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(note.element.compareDocumentPosition(wrapper!.get('.pdf-form-page__pages').element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect((await axe(wrapper!.element)).violations).toEqual([])
  })

  it('takes no room for a banner it was not given', async () => {
    mountPage()
    await flushPromises()
    expect(wrapper!.find('.pdf-form-page__banner').exists()).toBe(false)
  })

  it('draws what Brownie proposes in the boxes as a preview that cannot be typed over, and leaves the rest as they are', async () => {
    mountPage({ previewDrafts: { company: 'Brownie Bakery', 'member.id': ['not', 'for', 'a', 'box'], 'signed.on': '   ' } })
    await flushPromises()

    const company = wrapper!.get<HTMLTextAreaElement>('#edit-company')
    expect(company.element.value).toBe('Brownie Bakery')
    expect(company.element.readOnly).toBe(true)
    expect(company.element.closest('.fill-spot')!.classList.contains('fill-spot--preview')).toBe(true)
    const described = (company.attributes('aria-describedby') ?? '').split(' ').map((id) => document.getElementById(id)?.textContent ?? '')
    expect(described.join(' ')).toContain('Proposed by Brownie')
    // A list of values is for a table's rows, which a PDF form has none of; a blank proposal says nothing.
    expect(wrapper!.get<HTMLTextAreaElement>('#edit-member\\.id').element.readOnly).toBe(false)
    expect(wrapper!.get('#edit-signed\\.on').element.closest('.fill-spot')!.classList.contains('fill-spot--preview')).toBe(false)
    // Not in the document: it is still empty, so the count says so.
    expect(wrapper!.text()).toContain('1 of 4 fill spots filled')

    await wrapper!.setProps({ previewDrafts: null })
    expect(wrapper!.get<HTMLTextAreaElement>('#edit-company').element.value).toBe('')
    expect(wrapper!.get<HTMLTextAreaElement>('#edit-company').element.readOnly).toBe(false)
    expect((await axe(wrapper!.element)).violations).toEqual([])
  })

  it('works out for each box whether its text sits in the middle of it, as the export sits it', async () => {
    mountPage()
    await flushPromises()
    const style = (fieldId: string) => wrapper!.get(`[data-field-id="${fieldId}"]`).attributes('style') ?? ''

    // The form's own field takes one line, centred; the numbers are in points, which the page turns into pixels at any zoom.
    expect(style('full.name')).toContain('--pdf-centred: 1')
    expect(style('full.name')).toContain('--pdf-box-height: calc(16 * var(--pdf-point, 1px))')
    expect(style('full.name')).toContain('--pdf-text-size: calc(11 * var(--pdf-point, 1px))')
    expect(style('company')).toContain('--pdf-text-size: calc(9 * var(--pdf-point, 1px))')
    expect(style('company')).toContain('--pdf-centred: 1')

    // A value too long for one line of its box starts at the top of it instead.
    await wrapper!.setProps({ drafts: { 'full.name': 'Nguyễn Thị Minh Khai', company: 'Brownie Bakery and Sons '.repeat(8), 'member.id': '', 'signed.on': '' } })
    expect(style('company')).toContain('--pdf-centred: 0')
    expect(style('full.name')).toContain('--pdf-centred: 1')
  })

  it('puts a rail beside each page: its number, and a tick for each line of places on it, level with the line', async () => {
    mountPage()
    await flushPromises()

    const rails = wrapper!.findAll('.pdf-form-page__rail')
    expect(rails.map((rail) => rail.get('.pdf-form-page__rail-number').text())).toEqual(['1', '2'])
    // A mouse's way to a place, and nothing for a screen reader to hear or the keyboard to stop at.
    for (const rail of rails) {
      expect(rail.attributes('aria-hidden')).toBe('true')
      expect(rail.findAll('button, a, input, textarea, [tabindex]')).toHaveLength(0)
    }
    // Company and Member number are on one line of page 1, Full name on the next; Signed on is alone on page 2.
    const tops = (rail: number) => rails[rail]!.findAll('.pdf-form-page__tick').map((tick) => /top: ([\d.]+%)/.exec(tick.attributes('style') ?? '')?.[1])
    expect(tops(0)).toEqual(['13.3838%', '18.4343%'])
    expect(tops(1)).toEqual(['89.1414%'])
    // Still empty on the first line and on the second page; filled on the second line.
    const states = (rail: number) => rails[rail]!.findAll('.pdf-form-page__tick').map((tick) => tick.classes().find((name) => name.startsWith('pdf-form-page__tick--')))
    expect(states(0)).toEqual(['pdf-form-page__tick--empty', 'pdf-form-page__tick--filled'])
    expect(states(1)).toEqual(['pdf-form-page__tick--empty'])
  })

  it('shows on the rail which line holds the box in hand and which one needs a look', async () => {
    mountPage()
    await flushPromises()
    const first = () => wrapper!.findAll('.pdf-form-page__tick').map((tick) => tick.classes().find((name) => name.startsWith('pdf-form-page__tick--')))

    await wrapper!.setProps({ selected: { fieldId: 'member.id', rowIndex: null } })
    expect(first()).toEqual(['pdf-form-page__tick--selected', 'pdf-form-page__tick--filled', 'pdf-form-page__tick--empty'])

    const blocking = { authorship: 'USER_AUTHORED', evidenceSupport: 'MISSING', validation: 'BLOCKING', review: 'UNREVIEWED', lock: 'EDITABLE' }
    await wrapper!.setProps({ selected: null, revisionFields: { 'full.name': { fieldState: blocking } } as never })
    expect(first()).toEqual(['pdf-form-page__tick--empty', 'pdf-form-page__tick--attention', 'pdf-form-page__tick--empty'])

    // Every place on the line holds a value: the tick says it is done.
    await wrapper!.setProps({ revisionFields: null, drafts: { 'full.name': 'A', company: 'B', 'member.id': 'C', 'signed.on': '2026-10-01' } })
    expect(first()).toEqual(['pdf-form-page__tick--filled', 'pdf-form-page__tick--filled', 'pdf-form-page__tick--filled'])
  })

  it('takes the pointer to the place a tick stands for: the first one on its line still waiting, else the first', async () => {
    mountPage()
    await flushPromises()
    const ticks = wrapper!.findAll('.pdf-form-page__tick')

    await ticks[0]!.trigger('click')
    expect(document.activeElement).toBe(wrapper!.get('#edit-company').element)
    await ticks[1]!.trigger('click')
    expect(document.activeElement).toBe(wrapper!.get('#edit-full\\.name').element)
    await ticks[2]!.trigger('click')
    expect(document.activeElement).toBe(wrapper!.get('#edit-signed\\.on').element)
    expect(wrapper!.emitted('select')!.map(([target]) => (target as { fieldId: string }).fieldId)).toEqual(['company', 'full.name', 'signed.on'])
  })

  it('sizes the selected box by its corners with the pointer, and sends the new size when the pointer is let go', async () => {
    const { send } = mountPage({ send: async () => ({ ok: true, focusId: 'edit-company' }) })
    await flushPromises()
    const corners = () => wrapper!.findAll('.pdf-form-page__corner')

    // Nothing is selected, and the PDF's own field keeps the place the form gives it: no corners for either.
    expect(corners()).toHaveLength(0)
    await wrapper!.setProps({ selected: { fieldId: 'full.name', rowIndex: null } })
    expect(corners()).toHaveLength(0)

    await wrapper!.setProps({ selected: { fieldId: 'company', rowIndex: null } })
    expect(corners().map((corner) => corner.classes().find((name) => name.startsWith('pdf-form-page__corner--')))).toEqual([
      'pdf-form-page__corner--nw',
      'pdf-form-page__corner--ne',
      'pdf-form-page__corner--sw',
      'pdf-form-page__corner--se',
    ])
    expect(corners().every((corner) => corner.attributes('aria-hidden') === 'true')).toBe(true)

    // The page is drawn at a pixel to the point, so the pointer's pixels are the box's points: Company is 153 by 16 at (306, 98).
    const southEast = corners()[3]!
    await pointer(southEast, 'pointerdown', { pointerId: 4, clientX: 459, clientY: 114 })
    await pointer(southEast, 'pointermove', { pointerId: 4, clientX: 479, clientY: 124 })
    expect(placedBox('company', 612, 792)).toEqual({ x: expect.closeTo(306, 1), y: expect.closeTo(98, 1), width: expect.closeTo(173, 1), height: expect.closeTo(26, 1) })
    expect(send).not.toHaveBeenCalled()

    await pointer(southEast, 'pointerup', { pointerId: 4, clientX: 479, clientY: 124 })
    expect(send).toHaveBeenCalledTimes(1)
    expect(send.mock.calls[0]![0]).toEqual({
      kind: 'move',
      label: 'Company',
      changes: [{ kind: 'MOVE_BOX', fieldId: 'company', box: { x: 306, y: 98, width: 173, height: 26 } }],
    })
    expect(wrapper!.get('.pdf-form-page__status').text()).toBe('The box for Company was moved.')
    expect(document.activeElement).toBe(wrapper!.get('#edit-company').element)
  })

  it('moves the corner it takes and keeps the opposite one, and a box dragged to nothing is not sent', async () => {
    const { send } = mountPage()
    await flushPromises()
    await wrapper!.setProps({ selected: { fieldId: 'company', rowIndex: null } })
    const northWest = wrapper!.findAll('.pdf-form-page__corner')[0]!

    await pointer(northWest, 'pointerdown', { pointerId: 2, clientX: 306, clientY: 98 })
    await pointer(northWest, 'pointermove', { pointerId: 2, clientX: 296, clientY: 90 })
    expect(placedBox('company', 612, 792)).toEqual({ x: expect.closeTo(296, 1), y: expect.closeTo(90, 1), width: expect.closeTo(163, 1), height: expect.closeTo(24, 1) })
    await pointer(northWest, 'pointermove', { pointerId: 2, clientX: 306, clientY: 98 })
    await pointer(northWest, 'pointerup', { pointerId: 2, clientX: 306, clientY: 98 })
    expect(send).not.toHaveBeenCalled()
    expect(placedBox('company', 612, 792).width).toBeCloseTo(153, 1)
  })

  it('puts the box back and says why when the new size is refused, and when the pointer is taken away', async () => {
    mountPage({ send: async () => ({ ok: false, message: 'That box covers another fill spot; move it a little.' }) })
    await flushPromises()
    await wrapper!.setProps({ selected: { fieldId: 'company', rowIndex: null } })
    const southEast = wrapper!.findAll('.pdf-form-page__corner')[3]!

    await pointer(southEast, 'pointerdown', { pointerId: 4, clientX: 459, clientY: 114 })
    await pointer(southEast, 'pointermove', { pointerId: 4, clientX: 500, clientY: 130 })
    await pointer(southEast, 'pointerup', { pointerId: 4, clientX: 500, clientY: 130 })
    await flushPromises()
    expect(wrapper!.get('[role="alert"]').text()).toBe('That box covers another fill spot; move it a little.')
    expect(placedBox('company', 612, 792).width).toBeCloseTo(153, 1)

    await pointer(southEast, 'pointerdown', { pointerId: 5, clientX: 459, clientY: 114 })
    await pointer(southEast, 'pointermove', { pointerId: 5, clientX: 470, clientY: 120 })
    await pointer(southEast, 'pointercancel', { pointerId: 5, clientX: 470, clientY: 120 })
    expect(placedBox('company', 612, 792).width).toBeCloseTo(153, 1)
  })

  it('offers no corners while boxes are drawn or moved in Edit boxes, where they have handles of their own', async () => {
    mountPage()
    await flushPromises()
    await wrapper!.setProps({ selected: { fieldId: 'company', rowIndex: null } })
    expect(wrapper!.findAll('.pdf-form-page__corner')).toHaveLength(4)

    await wrapper!.get('button[aria-pressed]').trigger('click')
    expect(wrapper!.findAll('.pdf-form-page__corner')).toHaveLength(0)
    await wrapper!.get('button[aria-pressed]').trigger('click')
    expect(wrapper!.findAll('.pdf-form-page__corner')).toHaveLength(4)

    await wrapper!.findAll('button').find((button) => button.text() === 'Edit boxes')!.trigger('click')
    await flushPromises()
    expect(wrapper!.findAll('.pdf-form-page__corner')).toHaveLength(0)
  })

  it('asks about a box’s text size in a card hung below that box, level with its right edge', async () => {
    mountPage()
    await flushPromises()
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      if (this.matches('.pdf-form-page__spot')) return rect(306, 98, 153, 16)
      if (this.tagName === 'DIALOG') return rect(0, 0, 340, 264)
      return rect(0, Number(this.dataset.page ?? 0) === 2 ? 800 : 0, 612, 792)
    })

    ;(wrapper!.vm as unknown as { openRestyle: (fieldId: string) => void }).openRestyle('company')
    await flushPromises()
    const card = wrapper!.get('dialog')
    expect(card.classes()).toEqual(expect.arrayContaining(['pdf-spot-dialog--popover', 'pdf-spot-dialog--anchored']))
    // 14 points under the box's foot, and its right edge (459) is the card's: 459 - 340.
    expect(card.attributes('style')).toContain('left: 119px')
    expect(card.attributes('style')).toContain('top: 128px')
  })

  it("marks the found boxes that sit in a table's cells, so their line is drawn lighter there", async () => {
    // The company box with another box right beside it on its line, as two cells of a table's row are.
    mountPage({
      layout: layout({
        spots: [
          box('full.name', 'Full name', 1, { x: 140, y: 138, width: 300, height: 16 }, { bindingKind: 'ACROFORM_FIELD', style: null, origin: 'FORM' }),
          box('company', 'Company', 1, { x: 306, y: 98, width: 153, height: 16 }, { origin: 'FOUND_BY_BROWNIE' }),
          box('member.id', 'Member number', 1, { x: 463, y: 98, width: 80, height: 16 }),
          box('signed.on', 'Signed on', 2, { x: 140, y: 698, width: 120, height: 16 }),
        ],
      }),
    })
    await flushPromises()

    expect(wrapper!.get('[data-field-id="company"]').classes()).toContain('pdf-form-page__spot--in-cell')
    expect(wrapper!.get('[data-field-id="member.id"]').classes()).toContain('pdf-form-page__spot--in-cell')
    expect(wrapper!.get('[data-field-id="full.name"]').classes()).not.toContain('pdf-form-page__spot--in-cell')
  })

  describe('questions Brownie has asked', () => {
    it('tells a spot how many are open, whether its box is on a page or the form has no place for it', async () => {
      mountPage({ questionCounts: { company: 2, 'member.id': 1, 'full.name': 0 } })
      await flushPromises()
      const onPage = Object.fromEntries(wrapper!.findAllComponents(FillSpot).map((spot) => [spot.props('inputId'), spot.props('questionCount')]))
      expect(onPage['edit-company']).toBe(2)
      expect(onPage['edit-member.id']).toBe(1)
      expect(onPage['edit-full.name'] ?? 0).toBe(0)
      expect(onPage['edit-signed.on'] ?? 0).toBe(0)

      wrapper!.unmount()
      mountPage({ layout: layout({ spots: [] }), questionCounts: { company: 2 } })
      await flushPromises()
      const listed = Object.fromEntries(wrapper!.findAllComponents(FillSpot).map((spot) => [spot.props('inputId'), spot.props('questionCount')]))
      expect(listed['edit-company']).toBe(2)
      expect(listed['edit-full.name'] ?? 0).toBe(0)
    })
  })
})
