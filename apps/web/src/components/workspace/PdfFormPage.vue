<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, useId, watch } from 'vue'
// The "legacy" build, as the print preview uses: it carries PDF.js's own polyfills for the browsers this app supports.
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs'
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'
import FillSpot from '@/components/workspace/FillSpot.vue'
import PdfSpotDialog from '@/components/workspace/PdfSpotDialog.vue'
import {
  artifactPreviewUrl,
  suggestBox,
  type DocumentRevisionResponse,
  type FieldStateResponse,
  type PdfPageAnchor,
  type TemplateLayoutResponse,
} from '@/api/client'
import { fieldLabel, stateFromAssist, type EditableField } from '@/workspace/layout'
import {
  WORKING_WORDS,
  boxPlacement,
  boxesInCells,
  buildPdfFormModel,
  clampBox,
  describeBox,
  nudgeBox,
  pagePoint,
  spotTextCss,
  type BoxKey,
  type PdfPageModel,
  type PdfPageSpot,
  type PdfSpotDialogMode,
  type PdfSpotRequest,
  type PdfSpotResult,
} from '@/workspace/pdfPage'
import { boxFromDisplayed, boxToDisplayed, pointFromDisplayed, type PdfBox, type PdfPoint } from '@/workspace/pdfGeometry'
import { canvasPixels, canvasResolution, keepMiddle, pageWidthCss, zoomPercent, zoomStep, type PdfZoom } from '@/workspace/pdfZoom'

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

/*
 * Where PDF.js finds what it does not carry in its own script: the decoders for scanned pictures
 * (JBIG2 and JPEG 2000, as WebAssembly with plain-script fallbacks), the fonts a PDF names without
 * embedding, the character maps of Asian fonts, and a colour profile. The build copies them from
 * pdfjs-dist under a folder named for its version (vite.config.ts), so a new release never meets an
 * older copy kept by a browser.
 */
const PDFJS_ASSETS = `${import.meta.env.BASE_URL}assets/pdfjs-${pdfjs.version}/`

/**
 * A PDF form as the page the person fills in: each page of the PDF drawn as it prints, with a fill spot
 * laid over every place a value goes, so what is typed sits where the export writes it. Pages keep
 * their shape at any width, and the spots follow them.
 *
 * It takes the same props and gives the same events as the page for a Word form, and adds what only a
 * PDF has: boxes the person draws, moves and sizes, and a point on a page that the chat's "here" means.
 * Every change to the boxes goes through `sendSpotChanges`; the page owns only the questions it asks.
 */
const props = defineProps<{
  layout: TemplateLayoutResponse | null
  layoutState: 'loading' | 'ready' | 'unavailable'
  layoutProblem?: string | null
  fields: EditableField[]
  drafts: Record<string, string | string[]>
  revisionFields: DocumentRevisionResponse['fields'] | null
  requiredFieldIds: ReadonlySet<string>
  lockedFieldIds: ReadonlySet<string>
  rowsLocked: boolean
  selected: { fieldId: string; rowIndex: number | null } | null
  foundFieldIds?: ReadonlySet<string>
  /** The workspace the PDF and its template belong to. */
  workspaceId: number
  /** Makes a change to the form's fill spots; the result says where focus goes next, or why nothing changed. */
  sendSpotChanges: (request: PdfSpotRequest) => Promise<PdfSpotResult>
  /** The chat's message says "here": a click on a page picks the point it means. */
  pickingPoint?: boolean
}>()

const emit = defineEmits<{
  'update-scalar': [fieldId: string, value: string]
  'update-row': [fieldId: string, rowIndex: number, value: string]
  select: [target: { fieldId: string; rowIndex: number | null }]
  'open-actions': [target: { fieldId: string; rowIndex: number | null }]
  'add-row': []
  /** A point on a page, picked for the chat's "here". */
  'pick-point': [anchor: PdfPageAnchor]
}>()

const headingId = useId()
const keysHintId = useId()
const handleHintId = useId()
const zoomLevelId = useId()
const actionsKey = /Mac|iPhone|iPad/.test(window.navigator.platform ?? '') ? 'Option+Return' : 'Alt+Enter'

const fieldsById = computed(() => new Map(props.fields.map((field) => [field.fieldId, field])))

function labelOf(fieldId: string): string {
  return fieldLabel(fieldsById.value.get(fieldId) ?? { fieldId })
}

const model = computed(() =>
  props.layout?.pdf ? buildPdfFormModel(props.layout.pdf, props.fields) : { pages: [] as PdfPageModel[], spots: [] as PdfPageSpot[], unplaced: props.fields },
)
const pages = computed(() => model.value.pages)
/** Boxes in a table's cells, whose found outline is drawn lighter so it does not read as a second rule. */
const cellKeys = computed(() => new Set(pages.value.flatMap((page) => [...boxesInCells(page.spots)])))
const pageCount = computed(() => pages.value.length)

function pageName(page: { pageNumber: number }): string {
  return `Page ${page.pageNumber} of ${pageCount.value}`
}

// ---- One fill spot ------------------------------------------------------------------------------

function valueOf(fieldId: string): string {
  const draft = props.drafts[fieldId]
  return typeof draft === 'string' ? draft : ''
}

function isEmpty(fieldId: string): boolean {
  return valueOf(fieldId).trim() === ''
}

function stateOf(fieldId: string): FieldStateResponse | null {
  return props.revisionFields?.[fieldId]?.fieldState ?? null
}

function spotProps(fieldId: string, inputId: string, placeholder: string | null, styleCss: Record<string, string>) {
  const selected = props.selected
  return {
    fieldId,
    rowIndex: null,
    type: fieldsById.value.get(fieldId)?.type ?? 'TEXT',
    value: valueOf(fieldId),
    label: labelOf(fieldId),
    placeholder,
    styleCss,
    required: props.requiredFieldIds.has(fieldId),
    locked: props.lockedFieldIds.has(fieldId),
    state: stateOf(fieldId),
    selected: selected !== null && selected.fieldId === fieldId && selected.rowIndex === null,
    inputId,
    keysHintId,
    found: props.foundFieldIds?.has(fieldId) ?? false,
  }
}

const lastFocusedFieldId = ref<string | null>(null)

function onSpotInput(fieldId: string, value: string): void {
  emit('update-scalar', fieldId, value)
}

function onSpotFocus(fieldId: string): void {
  lastFocusedFieldId.value = fieldId
  emit('select', { fieldId, rowIndex: null })
}

function onSpotActions(fieldId: string): void {
  emit('open-actions', { fieldId, rowIndex: null })
}

function focusById(id: string): void {
  const target = window.document.getElementById(id)
  if (!(target instanceof HTMLElement) || !root.value?.contains(target)) return
  target.scrollIntoView?.({ block: 'center' })
  target.focus()
}

// ---- Progress -----------------------------------------------------------------------------------

/** Each value the page can fill, once, in the keyboard's order: the places on the pages, then the ones listed after. */
const targets = computed(() => {
  const seen = new Set<string>()
  const result: { fieldId: string; inputId: string }[] = []
  for (const spot of model.value.spots) {
    if (seen.has(spot.fieldId)) continue
    seen.add(spot.fieldId)
    result.push({ fieldId: spot.fieldId, inputId: spot.inputId })
  }
  for (const field of listed.value) result.push({ fieldId: field.fieldId, inputId: `edit-${field.fieldId}` })
  return result
})

const progressText = computed(() => {
  const total = targets.value.length
  const filled = targets.value.filter((target) => !isEmpty(target.fieldId)).length
  return `${filled} of ${total} ${total === 1 ? 'fill spot' : 'fill spots'} filled`
})

const hasEmptyTarget = computed(() => targets.value.some((target) => isEmpty(target.fieldId) && !props.lockedFieldIds.has(target.fieldId)))
const hasRequiredTarget = computed(() => targets.value.some((target) => props.requiredFieldIds.has(target.fieldId)))
const hasAssistValue = computed(() => targets.value.some((target) => !isEmpty(target.fieldId) && stateFromAssist(stateOf(target.fieldId))))
/**
 * A box on the pages Brownie found and nobody has checked yet: the legend says what its outline means. In Edit boxes
 * every box is a dashed handle and none is marked, so the legend says nothing of it there.
 */
const hasFoundSpot = computed(() => model.value.spots.some((spot) => props.foundFieldIds?.has(spot.fieldId) ?? false))

function focusNextEmpty(): void {
  const list = targets.value
  const start = list.findIndex((target) => target.fieldId === lastFocusedFieldId.value)
  for (let step = 1; step <= list.length; step++) {
    const candidate = list[(start + step) % list.length]!
    if (isEmpty(candidate.fieldId) && !props.lockedFieldIds.has(candidate.fieldId)) {
      focusById(candidate.inputId)
      return
    }
  }
}

/** Scalar fields with no place on any page; a PDF form has no rows, so a repeated field is not one this page can fill. */
const listed = computed(() => model.value.unplaced.filter((field) => field.cardinality === 'SCALAR'))

/** Pages that are pictures of paper: Brownie cannot read their words, so every place on them is a box someone draws. */
const scannedPages = computed(() => pages.value.filter((page) => !page.hasText))
const scanNote = computed(() => {
  const scanned = scannedPages.value
  if (scanned.length === 0) return null
  if (scanned.length === pages.value.length) {
    return 'This PDF is a scan, so Brownie cannot read its words. Draw a box wherever something should be filled in.'
  }
  const numbers = scanned.map((page) => page.pageNumber)
  const named = numbers.length === 1 ? `Page ${numbers[0]} is a scan` : `Pages ${numbers.slice(0, -1).join(', ')} and ${numbers[numbers.length - 1]} are scans`
  return `${named}, so Brownie cannot read ${numbers.length === 1 ? 'its' : 'their'} words. Draw a box wherever something should be filled in there.`
})

/** What the page is doing, said once in its own status line: drawing, editing, sending, or where the chat's "here" is. */
const status = ref('')
const problem = ref<string | null>(null)

// ---- Drawing the pages --------------------------------------------------------------------------

const root = ref<HTMLElement | null>(null)
const pagesElement = ref<HTMLElement | null>(null)
const acrossBar = ref<HTMLElement | null>(null)
const drawState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const drawError = ref<string | null>(null)
const canvases = new Map<number, HTMLCanvasElement>()
const pageElements = new Map<number, HTMLElement>()
const pdf = shallowRef<PDFDocumentProxy | null>(null)
let loadingTask: PDFDocumentLoadingTask | null = null
const renderTasks = new Map<number, RenderTask>()
/** How each page's picture was last drawn: its width on the screen and its canvas pixels to a CSS pixel. */
const renderedAs = new Map<number, string>()
/** How each page's picture is being drawn, while pdf.js draws it. */
const renderingAs = new Map<number, string>()
let loadSequence = 0
/** Counts the passes over the pages: a pass a newer one has overtaken stops, so two never draw on one canvas. */
let renderPass = 0

function setCanvas(pageNumber: number, element: unknown): void {
  if (element instanceof HTMLCanvasElement) canvases.set(pageNumber, element)
  else canvases.delete(pageNumber)
}

function setPageElement(pageNumber: number, element: unknown): void {
  if (element instanceof HTMLElement) pageElements.set(pageNumber, element)
  else pageElements.delete(pageNumber)
}

const sourceUrl = computed(() => {
  const source = props.layout?.pdf?.sourceArtifactId
  return typeof source === 'number' ? artifactPreviewUrl(props.workspaceId, source) : null
})

async function discardDocument(): Promise<void> {
  for (const task of renderTasks.values()) task.cancel()
  renderTasks.clear()
  renderedAs.clear()
  renderingAs.clear()
  pdf.value = null
  const task = loadingTask
  loadingTask = null
  if (task) await task.destroy()
}

async function load(src: string | null): Promise<void> {
  const sequence = ++loadSequence
  await discardDocument()
  drawError.value = null
  if (!src) {
    drawState.value = 'idle'
    return
  }
  drawState.value = 'loading'
  try {
    const response = await fetch(src, { credentials: 'same-origin' })
    if (!response.ok) throw new Error(`The PDF could not be fetched (${response.status}).`)
    const data = new Uint8Array(await response.arrayBuffer())
    const task = pdfjs.getDocument({
      data,
      // A PDF's own forms, scripts and XFA never run here: Brownie draws the pages and lays its own fill spots over them.
      enableXfa: false,
      wasmUrl: `${PDFJS_ASSETS}wasm/`,
      standardFontDataUrl: `${PDFJS_ASSETS}standard_fonts/`,
      cMapUrl: `${PDFJS_ASSETS}cmaps/`,
      cMapPacked: true,
      iccUrl: `${PDFJS_ASSETS}iccs/`,
    })
    const loaded = await task.promise
    if (sequence !== loadSequence) {
      await task.destroy()
      return
    }
    loadingTask = task
    pdf.value = loaded
    drawState.value = 'ready'
    await nextTick()
    await renderAll()
  } catch (cause) {
    if (sequence !== loadSequence) return
    drawState.value = 'error'
    drawError.value =
      cause instanceof Error && /fetched/.test(cause.message)
        ? `${cause.message} The fill spots still work; only the picture of the pages is missing.`
        : 'Brownie could not draw the pages of this PDF. The fill spots still work; only the picture of the pages is missing.'
  }
}

/** The width a page is shown at, in CSS pixels; before the browser has laid it out, the width its zoom gives it. */
function drawnWidth(page: PdfPageModel): number {
  const measured = pageElements.get(page.pageNumber)?.getBoundingClientRect().width ?? 0
  if (measured > 0) return measured
  return zoom.value.kind === 'scale' ? Math.round(page.shownWidth * zoom.value.scale) : page.shownWidth
}

/**
 * Draws each page at the size it is shown, at the screen's own density so text stays sharp; a page already
 * drawn that way is left. A newer pass (the window resized, the zoom changed) stops this one.
 */
async function renderAll(): Promise<void> {
  const loaded = pdf.value
  if (!loaded) return
  const pass = ++renderPass
  measureFit()
  for (const page of pages.value) {
    if (pdf.value !== loaded || pass !== renderPass) return
    const canvas = canvases.get(page.pageNumber)
    if (!canvas || page.pageNumber > loaded.numPages) continue
    const cssWidth = drawnWidth(page)
    const resolution = canvasResolution(cssWidth, (cssWidth * page.shownHeight) / page.shownWidth, window.devicePixelRatio || 1)
    const drawnAs = `${cssWidth}@${resolution}`
    if (renderedAs.get(page.pageNumber) === drawnAs) continue
    // The same picture already under way, from a pass this one overtook (a zoom, then the resize it causes): wait
    // for it rather than start it again.
    const underWay = renderTasks.get(page.pageNumber)
    if (underWay && renderingAs.get(page.pageNumber) === drawnAs) {
      await underWay.promise.catch(() => undefined)
      if (renderedAs.get(page.pageNumber) === drawnAs) continue
      if (pdf.value !== loaded || pass !== renderPass) return
    }
    renderTasks.get(page.pageNumber)?.cancel()
    try {
      const pdfPage = await loaded.getPage(page.pageNumber)
      if (pdf.value !== loaded || pass !== renderPass) return
      const unscaled = pdfPage.getViewport({ scale: 1 })
      const viewport = pdfPage.getViewport({ scale: cssWidth / unscaled.width })
      canvas.width = canvasPixels(viewport.width, resolution)
      canvas.height = canvasPixels(viewport.height, resolution)
      const task = pdfPage.render({
        canvas,
        viewport,
        transform: resolution === 1 ? undefined : [resolution, 0, 0, resolution, 0, 0],
        // Each field's own look is drawn, but none of the PDF's form controls come alive: the spots on top are the ones to type in.
        annotationMode: pdfjs.AnnotationMode.ENABLE,
      })
      renderTasks.set(page.pageNumber, task)
      renderingAs.set(page.pageNumber, drawnAs)
      await task.promise
      renderedAs.set(page.pageNumber, drawnAs)
    } catch (cause) {
      if (pass !== renderPass || (cause instanceof Error && cause.name === 'RenderingCancelledException')) continue
      drawState.value = 'error'
      drawError.value = `Brownie could not draw page ${page.pageNumber}. Its fill spots still work.`
    }
  }
}

let resizeObserver: ResizeObserver | null = null
let resizeTimer: ReturnType<typeof setTimeout> | null = null
/** The width the observer last saw; the pages fit the width, so a change of height alone asks nothing of them. */
let observedWidth: number | null = null
let resizeFrame: number | null = null

/**
 * The window or the pane changed the page's width. What the pages show is measured again in the next frame,
 * not while the browser is still telling observers about sizes: what that changes (the size shown, the way
 * across under a wide page) changes this page's own size, which the browser would otherwise have to report
 * again in the same frame, and could not.
 */
function onResized(entries: readonly ResizeObserverEntry[]): void {
  const width = entries.at(-1)?.contentRect.width
  if (width !== undefined && observedWidth !== null && Math.abs(width - observedWidth) < 0.5) return
  if (width !== undefined) observedWidth = width
  if (resizeFrame !== null) cancelFrame(resizeFrame)
  resizeFrame = nextFrame(() => {
    resizeFrame = null
    measureFit()
    void measureAcross()
  })
  // A narrower window fits the pages smaller; they are drawn again once it settles, so text stays sharp.
  if (resizeTimer) clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => void renderAll(), 200)
}

function nextFrame(callback: () => void): number {
  return typeof window.requestAnimationFrame === 'function' ? window.requestAnimationFrame(callback) : window.setTimeout(callback, 0)
}

function cancelFrame(handle: number): void {
  if (typeof window.cancelAnimationFrame === 'function') window.cancelAnimationFrame(handle)
  else window.clearTimeout(handle)
}
let densityQuery: MediaQueryList | null = null

/** A window moved to a screen of another density draws its pages again, as sharp as that screen shows them. */
function watchDensity(): void {
  densityQuery?.removeEventListener('change', onDensityChange)
  densityQuery = typeof window.matchMedia === 'function' ? window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`) : null
  densityQuery?.addEventListener?.('change', onDensityChange)
}

function onDensityChange(): void {
  watchDensity()
  void renderAll()
}

onMounted(() => {
  watchDensity()
  if (typeof ResizeObserver === 'undefined' || !root.value) return
  resizeObserver = new ResizeObserver(onResized)
  resizeObserver.observe(root.value)
})

watch(sourceUrl, (src) => void load(src), { immediate: true })
watch(
  () => pages.value.map((page) => page.pageNumber).join(','),
  () => void nextTick(renderAll),
)

onBeforeUnmount(() => {
  loadSequence += 1
  renderPass += 1
  resizeObserver?.disconnect()
  if (resizeTimer) clearTimeout(resizeTimer)
  if (resizeFrame !== null) cancelFrame(resizeFrame)
  densityQuery?.removeEventListener?.('change', onDensityChange)
  window.removeEventListener('keydown', onWindowKeydown)
  void discardDocument()
})

// ---- Zoom ---------------------------------------------------------------------------------------

/** How large the pages are drawn: to fit the width they are shown in, up to an easy reading size, or at a size the person chose. */
const zoom = ref<PdfZoom>({ kind: 'fit' })
/** The scale Fit width gives the first page, measured on the screen; null before it is laid out. */
const fittedScale = ref<number | null>(null)
const shownScale = computed(() => (zoom.value.kind === 'scale' ? zoom.value.scale : fittedScale.value))
const zoomInTo = computed(() => (shownScale.value === null ? null : zoomStep(shownScale.value, 'in')))
const zoomOutTo = computed(() => (shownScale.value === null ? null : zoomStep(shownScale.value, 'out')))
const zoomLabel = computed(() => (shownScale.value === null ? '' : `${zoomPercent(shownScale.value)}%`))

function measureFit(): void {
  if (zoom.value.kind !== 'fit') return
  const first = pages.value[0]
  const width = first ? (pageElements.get(first.pageNumber)?.getBoundingClientRect().width ?? 0) : 0
  fittedScale.value = first && width > 0 ? width / first.shownWidth : null
}

function pageWrapStyle(page: PdfPageModel): Record<string, string> {
  return { '--pdf-page-width': pageWidthCss(page.shownWidth, zoom.value) }
}

/** The element that scrolls the pages up and down: the document's own pane, or else the window. */
function verticalScroller(element: HTMLElement): HTMLElement {
  for (let node = element.parentElement; node; node = node.parentElement) {
    const overflow = window.getComputedStyle(node).overflowY
    if ((overflow === 'auto' || overflow === 'scroll') && node.scrollHeight > node.clientHeight) return node
  }
  return (window.document.scrollingElement as HTMLElement | null) ?? window.document.documentElement
}

/** Notes what is in the middle of the view, down and across, and gives back a step that brings it back there once the pages have their new size. */
function holdMiddle(): (() => void) | null {
  const pagesBox = pagesElement.value
  if (!pagesBox) return null
  const scroller = verticalScroller(pagesBox)
  const isWindow = scroller === window.document.scrollingElement
  const viewTop = isWindow ? 0 : scroller.getBoundingClientRect().top
  const viewHeight = isWindow ? window.innerHeight : scroller.clientHeight
  const top = scroller.scrollTop
  const before = pagesBox.getBoundingClientRect()
  const start = before.top - viewTop + top
  const left = pagesBox.scrollLeft
  const widthBefore = pagesBox.scrollWidth
  return () => {
    scroller.scrollTop = keepMiddle(top, viewHeight, start, before.height, pagesBox.getBoundingClientRect().height)
    pagesBox.scrollLeft = keepMiddle(left, pagesBox.clientWidth, 0, widthBefore, pagesBox.scrollWidth)
  }
}

/**
 * Draws the pages at another size, keeping what was in the middle of the view there, and says the size in
 * the page's status line. The boxes are placed in fractions of their page, so they go with it.
 */
async function setZoom(next: PdfZoom): Promise<void> {
  const bringBack = holdMiddle()
  zoom.value = next
  await nextTick()
  measureFit()
  bringBack?.()
  void measureAcross()
  const percent = shownScale.value === null ? null : zoomPercent(shownScale.value)
  if (next.kind === 'fit') status.value = percent === null ? 'The pages fit the width.' : `The pages fit the width, at ${percent}%.`
  else status.value = `Zoomed to ${percent}%.`
  void renderAll()
}

function zoomBy(direction: 'in' | 'out'): void {
  const next = direction === 'in' ? zoomInTo.value : zoomOutTo.value
  if (next !== null) void setZoom({ kind: 'scale', scale: next })
}

function fitWidth(): void {
  void setZoom({ kind: 'fit' })
}

/*
 * A page zoomed wider than the pane scrolls across in the box that holds the pages, which is as tall as all of
 * them: its own scroll bar is under the last page, out of view. While the pages are wider, a bar that stays at
 * the foot of the view scrolls them across instead, and follows them when they are scrolled another way (a
 * trackpad, or moving to a spot from the keyboard).
 */

/** How wide the pages are while wider than their box, for the bar at the foot of the view; 0 while they fit. */
const acrossWidth = ref(0)

async function measureAcross(): Promise<void> {
  const pagesBox = pagesElement.value
  acrossWidth.value = pagesBox && pagesBox.scrollWidth > pagesBox.clientWidth + 1 ? pagesBox.scrollWidth : 0
  await nextTick()
  onPagesScroll()
}

function onPagesScroll(): void {
  const pagesBox = pagesElement.value
  const bar = acrossBar.value
  if (pagesBox && bar && Math.abs(bar.scrollLeft - pagesBox.scrollLeft) >= 1) bar.scrollLeft = pagesBox.scrollLeft
}

function onAcrossScroll(): void {
  const pagesBox = pagesElement.value
  const bar = acrossBar.value
  if (pagesBox && bar && Math.abs(pagesBox.scrollLeft - bar.scrollLeft) >= 1) pagesBox.scrollLeft = bar.scrollLeft
}

// ---- Where the pointer is on a page -------------------------------------------------------------

/** The point under the pointer, as the page is shown (points from its top-left), kept on the page, at whatever size it is drawn. */
function shownPointOf(event: PointerEvent | MouseEvent, page: PdfPageModel): PdfPoint | null {
  const rect = pageElements.get(page.pageNumber)?.getBoundingClientRect()
  return rect ? pagePoint(event, rect, page) : null
}

function toStored(point: PdfPoint, page: PdfPageModel): PdfPoint {
  const stored = pointFromDisplayed(point, page, page.rotation)
  const round = (value: number) => Math.round(value * 100) / 100
  return { x: round(stored.x), y: round(stored.y) }
}

// ---- The chat's "here" --------------------------------------------------------------------------

const picked = ref<{ pageNumber: number; shown: PdfPoint } | null>(null)

watch(
  () => props.pickingPoint,
  (picking) => {
    if (!picking) picked.value = null
  },
)

function onSurfaceClick(event: MouseEvent, page: PdfPageModel): void {
  // The click that ends drawing a box is not a pick.
  if (drawEndedAt !== null && event.timeStamp - drawEndedAt < 500) return
  if (!props.pickingPoint || mode.value !== 'fill') return
  const shown = shownPointOf(event, page)
  if (!shown) return
  picked.value = { pageNumber: page.pageNumber, shown }
  emit('pick-point', { kind: 'PDF', pageNumber: page.pageNumber, point: toStored(shown, page) })
  status.value = `The place on ${pageName(page).toLowerCase()} is picked. Send your message to add the fill spot there.`
}

// ---- Drawing a box ------------------------------------------------------------------------------

type Mode = 'fill' | 'draw' | 'edit'
const mode = ref<Mode>('fill')
const drawButton = ref<HTMLButtonElement | null>(null)
const editButton = ref<HTMLButtonElement | null>(null)
const dialogRef = ref<InstanceType<typeof PdfSpotDialog> | null>(null)
/** The box being drawn, as the page is shown. */
const drag = ref<{ pageNumber: number; start: PdfPoint; end: PdfPoint; pointerId: number } | null>(null)

/** When the last box was drawn, so the click the pointer sends after it is not taken for a pick. */
let drawEndedAt: number | null = null

const DRAW_HINT = 'Drag across the page where the box goes, or click a place for a box there. Escape stops drawing.'

function toggleDraw(): void {
  if (mode.value === 'edit') return
  if (mode.value === 'draw') {
    stopDrawing()
    return
  }
  mode.value = 'draw'
  problem.value = null
  picked.value = null
  status.value = DRAW_HINT
  window.addEventListener('keydown', onWindowKeydown)
}

function stopDrawing(said = 'Drawing stopped.'): void {
  drag.value = null
  mode.value = 'fill'
  status.value = said
  window.removeEventListener('keydown', onWindowKeydown)
}

/** Escape while drawing: a box being dragged is dropped first, then drawing stops. */
function onWindowKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || mode.value !== 'draw') return
  event.preventDefault()
  if (drag.value) {
    drag.value = null
    status.value = `That box was not drawn. ${DRAW_HINT}`
    return
  }
  stopDrawing()
  drawButton.value?.focus()
}

function onSurfacePointerDown(event: PointerEvent, page: PdfPageModel): void {
  if (mode.value !== 'draw' || event.button !== 0) return
  const start = shownPointOf(event, page)
  if (!start) return
  event.preventDefault()
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  drag.value = { pageNumber: page.pageNumber, start, end: start, pointerId: event.pointerId }
}

function onSurfacePointerMove(event: PointerEvent, page: PdfPageModel): void {
  const current = drag.value
  if (!current || current.pageNumber !== page.pageNumber || current.pointerId !== event.pointerId) return
  const end = shownPointOf(event, page)
  if (end) drag.value = { ...current, end }
}

async function onSurfacePointerUp(event: PointerEvent, page: PdfPageModel): Promise<void> {
  const current = drag.value
  if (!current || current.pageNumber !== page.pageNumber || current.pointerId !== event.pointerId) return
  const end = shownPointOf(event, page) ?? current.end
  drag.value = null
  drawEndedAt = event.timeStamp
  const shown = spanningBox(current.start, end)
  // Barely moved: a click, which asks where a box at that place would go.
  const clicked = shown.width < 4 && shown.height < 4
  stopDrawing('')
  await placeNewBox(page, clicked ? null : clampBox(boxFromDisplayed(shown, page, page.rotation), page), toStored(current.start, page))
}

function spanningBox(first: PdfPoint, second: PdfPoint): PdfBox {
  return { x: Math.min(first.x, second.x), y: Math.min(first.y, second.y), width: Math.abs(second.x - first.x), height: Math.abs(second.y - first.y) }
}

const dragPlacement = computed(() => {
  const current = drag.value
  const page = current ? pages.value.find((candidate) => candidate.pageNumber === current.pageNumber) : null
  return current && page ? { pageNumber: page.pageNumber, style: boxPlacement(spanningBox(current.start, current.end), page) } : null
})

async function suggestAt(pageNumber: number, place: { lineIndex: number } | { point: PdfPoint }) {
  const layout = props.layout!
  return suggestBox(props.workspaceId, layout.templateId, layout.versionId, { pageNumber, ...place })
}

/**
 * Asks for a new box's name. A drawn box keeps the size it was drawn at; the server still says which
 * words sit beside it, for their look and a name to start from. A click takes the server's whole box.
 */
async function placeNewBox(page: PdfPageModel, drawn: PdfBox | null, point: PdfPoint): Promise<void> {
  status.value = 'Finding the words beside that place…'
  let suggestion: Awaited<ReturnType<typeof suggestAt>> | null = null
  try {
    suggestion = await suggestAt(page.pageNumber, { point: drawn ? { x: drawn.x, y: drawn.y + drawn.height / 2 } : point })
  } catch {
    suggestion = null
  }
  status.value = ''
  const box = drawn ?? suggestion?.box ?? null
  if (!box) {
    problem.value = 'Brownie could not place a box there. Drag across the page to draw one instead.'
    return
  }
  openDialog({ kind: 'add', pageNumber: page.pageNumber, box, style: suggestion?.style ?? null, labelGuess: suggestion?.labelGuess ?? null })
}

function openDialog(next: PdfSpotDialogMode): void {
  problem.value = null
  dialogRef.value?.open(next)
}

function addWithKeyboard(): void {
  if (mode.value === 'draw') stopDrawing('')
  openDialog({ kind: 'add', pageNumber: null, box: null, style: null, labelGuess: null })
}

// ---- Moving and sizing boxes --------------------------------------------------------------------

/** Boxes moved or resized since Edit boxes was pressed, by field; sent together when Done is pressed. */
const edited = ref<Record<string, PdfBox>>({})
const editing = ref(false)
const movableSpots = computed(() => model.value.spots.filter((spot) => !spot.formField))
const formFieldCount = computed(() => model.value.spots.filter((spot) => spot.formField).length)

function boxOf(spot: PdfPageSpot): PdfBox {
  return edited.value[spot.fieldId] ?? spot.box
}

function shownBoxOf(spot: PdfPageSpot, page: PdfPageModel): PdfBox {
  return boxToDisplayed(boxOf(spot), page, page.rotation)
}

function startEditing(): void {
  if (mode.value === 'draw') stopDrawing('')
  mode.value = 'edit'
  edited.value = {}
  problem.value = null
  picked.value = null
  status.value = 'Choose a box to move it. Press Done when the boxes are where they belong.'
  void nextTick(() => root.value?.querySelector<HTMLElement>('.pdf-form-page__handle')?.focus())
}

function cancelEditing(): void {
  if (editing.value) return
  edited.value = {}
  mode.value = 'fill'
  status.value = 'No box was moved.'
  void nextTick(() => editButton.value?.focus())
}

async function finishEditing(): Promise<void> {
  if (editing.value) return
  const moved = movableSpots.value.filter((spot, index, all) => edited.value[spot.fieldId] && all.findIndex((other) => other.fieldId === spot.fieldId) === index)
  const changes = moved
    .map((spot) => ({ spot, box: edited.value[spot.fieldId]! }))
    .filter(({ spot, box }) => ['x', 'y', 'width', 'height'].some((key) => box[key as keyof PdfBox] !== spot.box[key as keyof PdfBox]))
  if (changes.length === 0) {
    cancelEditing()
    return
  }
  editing.value = true
  problem.value = null
  status.value = WORKING_WORDS.move
  try {
    const result = await props.sendSpotChanges({
      kind: 'move',
      label: changes[0]!.spot.label,
      changes: changes.map(({ spot, box }) => ({ kind: 'MOVE_BOX', fieldId: spot.fieldId, box })),
    })
    if (!result.ok) {
      problem.value = result.message
      status.value = ''
      return
    }
    edited.value = {}
    mode.value = 'fill'
    status.value = changes.length === 1 ? `The box for ${changes[0]!.spot.label} was moved.` : `${changes.length} boxes were moved.`
    await nextTick()
    focusById(result.focusId ?? changes[0]!.spot.inputId)
  } finally {
    editing.value = false
  }
}

const ARROWS: readonly string[] = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']

function onHandleKeydown(event: KeyboardEvent, spot: PdfPageSpot, page: PdfPageModel): void {
  if (!ARROWS.includes(event.key) || event.ctrlKey || event.metaKey) return
  event.preventDefault()
  const box = nudgeBox(boxOf(spot), page, event.key as BoxKey, { large: event.shiftKey, resize: event.altKey })
  edited.value = { ...edited.value, [spot.fieldId]: box }
  status.value = describeBox(spot.label, box, page)
}

/** Dragging a box moves it; dragging its corner grip sizes it. */
const handleDrag = ref<{ fieldId: string; pointerId: number; start: PdfPoint; from: PdfBox; resize: boolean } | null>(null)

function onHandlePointerDown(event: PointerEvent, spot: PdfPageSpot, page: PdfPageModel): void {
  if (event.button !== 0) return
  const start = shownPointOf(event, page)
  if (!start) return
  event.preventDefault()
  const handle = event.currentTarget as HTMLElement
  // Held by the pointer, the box is also the one the arrow keys move next.
  handle.focus()
  handle.setPointerCapture?.(event.pointerId)
  const resize = (event.target as HTMLElement).closest('.pdf-form-page__grip') !== null
  handleDrag.value = { fieldId: spot.fieldId, pointerId: event.pointerId, start, from: shownBoxOf(spot, page), resize }
}

function onHandlePointerMove(event: PointerEvent, spot: PdfPageSpot, page: PdfPageModel): void {
  const current = handleDrag.value
  if (!current || current.fieldId !== spot.fieldId || current.pointerId !== event.pointerId) return
  const point = shownPointOf(event, page)
  if (!point) return
  const dx = point.x - current.start.x
  const dy = point.y - current.start.y
  const shown = current.resize
    ? { ...current.from, width: current.from.width + dx, height: current.from.height + dy }
    : { ...current.from, x: current.from.x + dx, y: current.from.y + dy }
  edited.value = { ...edited.value, [spot.fieldId]: clampBox(boxFromDisplayed(shown, page, page.rotation), page) }
}

function onHandlePointerUp(event: PointerEvent, spot: PdfPageSpot, page: PdfPageModel): void {
  const current = handleDrag.value
  if (!current || current.fieldId !== spot.fieldId || current.pointerId !== event.pointerId) return
  handleDrag.value = null
  status.value = describeBox(spot.label, boxOf(spot), page)
}

// ---- What the page offers the bar about a spot ---------------------------------------------------

function spotFor(fieldId: string): PdfPageSpot | null {
  return model.value.spots.find((spot) => spot.fieldId === fieldId) ?? null
}

/** Whether a spot is one of the PDF's own form fields, whose place and look the form sets; null when the page has no place for it. */
function isFormField(fieldId: string): boolean | null {
  const spot = spotFor(fieldId)
  return spot ? spot.formField : null
}

function openRename(fieldId: string): void {
  openDialog({ kind: 'rename', fieldId, label: labelOf(fieldId) })
}

function openRestyle(fieldId: string): void {
  const spot = spotFor(fieldId)
  if (!spot || spot.formField) return
  openDialog({
    kind: 'restyle',
    fieldId,
    label: labelOf(fieldId),
    sizePt: spot.style?.sizePt ?? 11,
    overflow: spot.overflow,
  })
}

function openRemove(fieldId: string): void {
  const origin = fieldsById.value.get(fieldId)?.origin ?? spotFor(fieldId)?.origin ?? null
  openDialog({ kind: 'remove', fieldId, label: labelOf(fieldId), fromForm: origin === null || origin === 'FORM' })
}

defineExpose({ openRename, openRestyle, openRemove, isFormField })

// ---- Page text ----------------------------------------------------------------------------------

function pointMarker(page: PdfPageModel): Record<string, string> | null {
  const current = picked.value
  if (!current || current.pageNumber !== page.pageNumber) return null
  const placement = boxPlacement({ x: current.shown.x, y: current.shown.y, width: 0, height: 0 }, page)
  return { left: placement.left!, top: placement.top! }
}

function pageStyle(page: PdfPageModel): Record<string, string> {
  return { aspectRatio: `${page.shownWidth} / ${page.shownHeight}`, '--pdf-point': `calc(100cqw / ${page.shownWidth})` }
}

function spotBoxStyle(spot: PdfPageSpot, page: PdfPageModel): Record<string, string> {
  return boxPlacement(shownBoxOf(spot, page), page)
}

</script>

<template>
  <section ref="root" class="pdf-form-page" :aria-labelledby="headingId">
    <h2 :id="headingId" class="visually-hidden">Document</h2>
    <p v-if="!layout?.pdf" class="field-hint pdf-form-page__notice" :role="layoutState === 'loading' ? 'status' : undefined">
      {{ layoutState === 'loading' ? 'Loading the page…' : (layoutProblem ?? 'Brownie could not draw this form’s pages.') }}
    </p>

    <div class="pdf-form-page__progress">
      <p v-if="targets.length > 0" class="pdf-form-page__progress-text">{{ progressText }}</p>
      <p v-if="hasRequiredTarget || hasAssistValue || (hasFoundSpot && mode !== 'edit')" class="pdf-form-page__legend">
        <span v-if="hasRequiredTarget"><span class="pdf-form-page__legend-required" aria-hidden="true">*</span> Required before export</span>
        <span v-if="hasAssistValue"><span class="pdf-form-page__legend-assist" aria-hidden="true">Aa</span> Filled by Brownie</span>
        <span v-if="hasFoundSpot && mode !== 'edit'"><span class="pdf-form-page__legend-found" aria-hidden="true"></span> Dashed outline: found by Brownie, check it</span>
      </p>
      <div class="pdf-form-page__tools">
        <button v-if="hasEmptyTarget && mode === 'fill'" type="button" class="button button--secondary" @click="focusNextEmpty">Next empty spot</button>
        <template v-if="layout?.pdf && mode !== 'edit'">
          <button type="button" class="button button--secondary" @click="addWithKeyboard">Add a fill spot</button>
          <button ref="drawButton" type="button" class="button button--secondary" :aria-pressed="mode === 'draw' ? 'true' : 'false'" @click="toggleDraw">
            Draw a box
          </button>
          <button v-if="movableSpots.length > 0" ref="editButton" type="button" class="button button--secondary" @click="startEditing">
            Edit boxes
          </button>
        </template>
        <template v-if="mode === 'edit'">
          <button type="button" class="button button--primary" :aria-disabled="editing" @click="finishEditing">Done</button>
          <button type="button" class="button button--secondary" :aria-disabled="editing" @click="cancelEditing">Cancel</button>
        </template>
      </div>
    </div>
    <p v-if="scanNote && mode !== 'edit'" class="pdf-form-page__scan-note">{{ scanNote }}</p>
    <p v-if="mode === 'edit' && formFieldCount > 0" class="field-hint pdf-form-page__notice">
      Boxes that belong to the PDF’s own form stay where the form puts them; Brownie cannot move them.
    </p>
    <p class="pdf-form-page__status" role="status">{{ status }}</p>
    <p v-if="problem" class="field-error pdf-form-page__notice" role="alert">{{ problem }}</p>
    <p :id="keysHintId" class="visually-hidden">{{ actionsKey }} moves to the bar about this spot.</p>
    <p :id="handleHintId" class="visually-hidden">
      Arrow keys move the box by 1 point, and by 10 points with Shift. Alt and an arrow key make it wider, narrower, taller or shorter.
    </p>

    <p v-if="drawState === 'loading'" class="field-hint pdf-form-page__notice" role="status">Drawing the pages…</p>
    <p v-if="drawError" class="field-error pdf-form-page__notice" role="alert">{{ drawError }}</p>

    <!-- How large the pages are drawn. Each change is said in the status line; the size stays in view between the buttons. -->
    <div v-if="pages.length > 0" class="pdf-form-page__zoom" role="group" aria-label="Zoom">
      <button type="button" class="button button--secondary" :aria-pressed="zoom.kind === 'fit' ? 'true' : 'false'" @click="fitWidth">Fit width</button>
      <button
        type="button"
        class="button button--secondary pdf-form-page__zoom-step"
        :aria-disabled="zoomOutTo === null ? 'true' : undefined"
        :aria-describedby="zoomLevelId"
        @click="zoomBy('out')"
      >
        <span aria-hidden="true">−</span><span class="visually-hidden">Zoom out</span>
      </button>
      <span :id="zoomLevelId" class="pdf-form-page__zoom-level"><span class="visually-hidden">Shown at </span>{{ zoomLabel }}</span>
      <button
        type="button"
        class="button button--secondary pdf-form-page__zoom-step"
        :aria-disabled="zoomInTo === null ? 'true' : undefined"
        :aria-describedby="zoomLevelId"
        @click="zoomBy('in')"
      >
        <span aria-hidden="true">+</span><span class="visually-hidden">Zoom in</span>
      </button>
    </div>

    <div
      ref="pagesElement"
      class="pdf-form-page__pages"
      :class="{
        'pdf-form-page__pages--drawing': mode === 'draw',
        'pdf-form-page__pages--picking': pickingPoint && mode === 'fill',
        'pdf-form-page__pages--across': acrossWidth > 0,
      }"
      @scroll="onPagesScroll"
    >
      <div v-for="page in pages" :key="page.pageNumber" class="pdf-form-page__page-wrap" :style="pageWrapStyle(page)">
        <div :ref="(element) => setPageElement(page.pageNumber, element)" class="pdf-form-page__page" :style="pageStyle(page)" :data-page="page.pageNumber">
          <canvas :ref="(element) => setCanvas(page.pageNumber, element)" class="pdf-form-page__canvas" role="img" :aria-label="pageName(page)"></canvas>
          <!-- The paper itself: where a box is drawn, or the chat's "here" is picked, by pointer. Keyboard users have Add a fill spot. -->
          <div
            class="pdf-form-page__surface"
            aria-hidden="true"
            @click="onSurfaceClick($event, page)"
            @pointerdown="onSurfacePointerDown($event, page)"
            @pointermove="onSurfacePointerMove($event, page)"
            @pointerup="onSurfacePointerUp($event, page)"
            @pointercancel="drag = null"
          ></div>
          <template v-for="spot in page.spots" :key="spot.key">
            <button
              v-if="mode === 'edit' && !spot.formField"
              type="button"
              class="pdf-form-page__handle"
              :style="spotBoxStyle(spot, page)"
              :aria-label="`Move or resize ${spot.label}`"
              :aria-describedby="handleHintId"
              @keydown="onHandleKeydown($event, spot, page)"
              @pointerdown="onHandlePointerDown($event, spot, page)"
              @pointermove="onHandlePointerMove($event, spot, page)"
              @pointerup="onHandlePointerUp($event, spot, page)"
              @pointercancel="handleDrag = null"
            >
              <span class="pdf-form-page__handle-name" aria-hidden="true">{{ spot.label }}</span>
              <span class="pdf-form-page__grip" aria-hidden="true"></span>
            </button>
            <div
              v-else
              class="pdf-form-page__spot"
              :class="{
                'pdf-form-page__spot--inert': mode !== 'fill',
                'pdf-form-page__spot--found': foundFieldIds?.has(spot.fieldId),
                'pdf-form-page__spot--in-cell': cellKeys.has(spot.key),
              }"
              :style="spotBoxStyle(spot, page)"
              :data-field-id="spot.fieldId"
            >
              <FillSpot
                v-bind="spotProps(spot.fieldId, spot.inputId, null, spotTextCss(spot.style))"
                @update:value="onSpotInput(spot.fieldId, $event)"
                @focus="onSpotFocus(spot.fieldId)"
                @actions="onSpotActions(spot.fieldId)"
              />
              <span v-if="foundFieldIds?.has(spot.fieldId)" class="pdf-form-page__found-mark" aria-hidden="true"></span>
            </div>
          </template>
          <div v-if="dragPlacement && dragPlacement.pageNumber === page.pageNumber" class="pdf-form-page__drawn" :style="dragPlacement.style" aria-hidden="true"></div>
          <span v-if="pointMarker(page)" class="pdf-form-page__picked" :style="pointMarker(page)!" aria-hidden="true"></span>
        </div>
        <details class="pdf-form-page__text">
          <summary>Text on this page<span class="visually-hidden"> ({{ pageName(page).toLowerCase() }})</span></summary>
          <p v-if="!page.hasText" class="field-hint">Brownie cannot read the words on this page, because it is a scan.</p>
          <ol v-else class="pdf-form-page__lines">
            <li v-for="line in page.lines" :key="line.index">{{ line.text }}</li>
          </ol>
        </details>
      </div>
    </div>

    <!--
      The way across a page wider than the pane, kept at the foot of the view. Seen, not heard, and not a stop for
      the Tab key: the keyboard moves across by going to a spot, and the page scrolls to show it.
    -->
    <div v-if="acrossWidth > 0" ref="acrossBar" class="pdf-form-page__across" aria-hidden="true" tabindex="-1" @scroll="onAcrossScroll">
      <div :style="{ inlineSize: `${acrossWidth}px` }" class="pdf-form-page__across-width"></div>
    </div>

    <div v-if="listed.length > 0" class="pdf-form-page__list">
      <h3 class="pdf-form-page__list-heading">Other fill spots</h3>
      <p class="field-hint">The form has no place on its pages for these.</p>
      <p v-for="field in listed" :key="field.fieldId" class="pdf-form-page__list-item">
        <span class="pdf-form-page__field-label">{{ labelOf(field.fieldId) }}:</span>
        <FillSpot
          v-bind="spotProps(field.fieldId, `edit-${field.fieldId}`, null, {})"
          @update:value="onSpotInput(field.fieldId, $event)"
          @focus="onSpotFocus(field.fieldId)"
          @actions="onSpotActions(field.fieldId)"
        />
      </p>
    </div>

    <PdfSpotDialog ref="dialogRef" :pages="pages" :suggest="suggestAt" :send="sendSpotChanges" />
  </section>
</template>

<style scoped>
.pdf-form-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-inline-size: 0;
}

.pdf-form-page__notice,
.pdf-form-page__progress,
.pdf-form-page__scan-note,
.pdf-form-page__status,
.pdf-form-page__zoom,
.pdf-form-page__list {
  inline-size: 100%;
  max-inline-size: 50rem;
  margin: 0 auto;
}

.pdf-form-page__status {
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.pdf-form-page__progress {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1) var(--space-4);
  min-block-size: 1.75rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}

.pdf-form-page__progress-text,
.pdf-form-page__legend {
  margin: 0;
}

.pdf-form-page__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-3);
}

.pdf-form-page__legend-required {
  font-weight: 700;
  color: var(--color-error);
}

.pdf-form-page__legend-assist {
  padding: 0 0.2em;
  border-block-end: 3px double var(--color-cocoa);
  background: var(--color-cocoa-wash);
  color: var(--color-text);
}

.pdf-form-page__tools {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-inline-start: auto;
}

.pdf-form-page__tools .button,
.pdf-form-page__zoom .button {
  min-block-size: 1.75rem;
  padding: 0 var(--space-3);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: 500;
}

/* A pressed toggle says so by its shape as well as its colour: a filled pill. */
.pdf-form-page__tools .button[aria-pressed='true'],
.pdf-form-page__zoom .button[aria-pressed='true'] {
  border-color: var(--color-cocoa);
  background: var(--color-cocoa-wash);
  font-weight: 700;
}

.pdf-form-page__scan-note {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-honey-line);
  border-radius: var(--radius);
  background: var(--color-honey-faint);
  font-size: var(--font-size-sm);
}

.pdf-form-page__zoom {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-1);
  font-size: var(--font-size-xs);
}

.pdf-form-page__zoom .pdf-form-page__zoom-step {
  min-inline-size: 1.75rem;
  padding: 0;
  font-size: var(--font-size-base);
  line-height: 1;
}

.pdf-form-page__zoom-level {
  min-inline-size: 3.25em;
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

/*
 * A page zoomed wider than the pane scrolls across here, under the notes and buttons, which stay put. The
 * padding keeps the page's edge inside the scrolling edge.
 */
.pdf-form-page__pages {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  overflow-x: auto;
  padding: 2px 2px var(--space-1);
  scrollbar-width: thin;
  scrollbar-color: var(--color-scrollbar) transparent;
}

/* Its own scroll bar would sit under the last page: the bar at the foot of the view stands in for it. */
.pdf-form-page__pages--across {
  scrollbar-width: none;
}

.pdf-form-page__pages--across::-webkit-scrollbar {
  display: none;
}

/*
 * Stuck to the foot of whatever scrolls the page up and down, the document's pane or the window, from the
 * first page to the last. A scroll bar styled this way is drawn all the time, not only while it moves, so
 * a mouse that cannot scroll sideways still finds it.
 */
.pdf-form-page__across {
  position: sticky;
  inset-block-end: 0;
  z-index: 3;
  overflow-x: auto;
  overflow-y: hidden;
  margin-block-start: calc(-1 * var(--space-3));
  background: var(--color-paper);
  /* Inherited from the pane, a scroll bar color would turn off the styling below in Chromium. */
  scrollbar-color: auto;
}

/* Firefox has no styling below: the closest it allows. */
@supports not selector(::-webkit-scrollbar) {
  .pdf-form-page__across {
    scrollbar-color: var(--color-scrollbar) var(--color-track);
  }
}

.pdf-form-page__across::-webkit-scrollbar {
  block-size: 0.75rem;
}

.pdf-form-page__across::-webkit-scrollbar-track {
  background: var(--color-track);
  border-radius: 0.375rem;
}

.pdf-form-page__across::-webkit-scrollbar-thumb {
  background: var(--color-scrollbar);
  border: 2px solid var(--color-track);
  border-radius: 0.375rem;
}

.pdf-form-page__across-width {
  block-size: 1px;
}

/*
 * As wide as its zoom makes it (--pdf-page-width): the pane's width up to an easy reading size, or a set width.
 * Centred while it fits; once wider than the pane, it starts at the pane's edge so all of it can be scrolled to.
 */
.pdf-form-page__page-wrap {
  inline-size: var(--pdf-page-width, 100%);
  margin-inline: auto;
}

/*
 * One page, the shape the PDF gives it at any width. It is the container its spots measure against:
 * --pdf-point is one point of the page as drawn, so a box's text keeps its size relative to the page. Its edge
 * is an outline, drawn outside it, so the page's picture, its boxes and the pointer all measure the same box.
 */
.pdf-form-page__page {
  container-type: inline-size;
  position: relative;
  inline-size: 100%;
  overflow: hidden;
  outline: 1px solid var(--color-hairline);
  border-radius: 0.25rem;
  background: #fff;
  box-shadow: 0 1px 3px color-mix(in srgb, var(--color-text) 6%, transparent);
}

.pdf-form-page__canvas {
  position: absolute;
  inset: 0;
  inline-size: 100%;
  block-size: 100%;
}

.pdf-form-page__surface {
  position: absolute;
  inset: 0;
}

.pdf-form-page__pages--drawing .pdf-form-page__surface,
.pdf-form-page__pages--picking .pdf-form-page__surface {
  cursor: crosshair;
  touch-action: none;
}

/* While drawing or moving, the page takes the pointer: the spots show where they are without catching it. */
.pdf-form-page__pages--drawing .pdf-form-page__spot,
.pdf-form-page__spot--inert {
  pointer-events: none;
}

.pdf-form-page__spot {
  position: absolute;
  box-sizing: border-box;
  line-height: 1.15;
}

.pdf-form-page__spot :deep(.fill-spot) {
  display: block;
  position: relative;
  inline-size: 100%;
  block-size: 100%;
}

.pdf-form-page__spot :deep(.fill-spot__control) {
  inline-size: 100%;
  block-size: 100%;
  min-inline-size: 0;
  max-inline-size: none;
  padding: 0 0.15em;
  border-radius: 0.1rem;
  field-sizing: fixed;
  overflow: hidden;
  line-height: 1.15;
  background: color-mix(in srgb, var(--color-surface) 70%, transparent);
}

/*
 * At the form's own size, text on a page drawn for a phone is tiny: an 11 pt box is about 6 px there. While a box
 * is typed in, its text is at least the page's ordinary size and the box grows down over the page to hold it; it
 * goes back to the form's size when focus leaves.
 */
.pdf-form-page__spot:focus-within {
  --pdf-min-text: 1rem;
  z-index: 3;
}

.pdf-form-page__spot:focus-within :deep(.fill-spot__control) {
  block-size: auto;
  min-block-size: 100%;
}

.pdf-form-page__spot :deep(.fill-spot__required) {
  position: absolute;
  inset-block-start: -0.2em;
  inset-inline-end: -0.5em;
}

.pdf-form-page__spot :deep(.fill-spot__lock) {
  position: absolute;
  inset-block-start: 0.1em;
  inset-inline-end: 0.1em;
}

/*
 * On a drawn page a label would cover the form's own words, which sit right above and beside its boxes; so a box
 * Brownie found is told by a thin dashed line instead, which the legend names. Its description says it in words,
 * and the bar about it asks the person to check it. The line is the box's own edge, inside it: a found box often
 * starts right after its label's colon or fills a table's cell, and a line outside it covered the colon and the
 * cell's rules; a line further in ran through the text typed in a small box. Drawn over the edge, it leaves the
 * lines inside it that say a box is filled, filled by Brownie or needs attention, and it sits apart from the
 * selected box's outline and the focus ring around it, so the box the bar is about still shows it was found.
 * While the box is typed in, it grows to hold the text and the focus ring marks it, so the line steps aside.
 */
.pdf-form-page__spot :deep(.fill-spot__found) {
  display: none;
}

.pdf-form-page__found-mark {
  position: absolute;
  inset: 1px 3px;
  border: 1px dashed var(--color-cocoa);
  border-radius: 1px;
  pointer-events: none;
}

/*
 * A found box and its line are kept a little in from the box's own edges, clear of the colon just before it and
 * of a cell's rules round it; more so in a cell, which has the room. The value keeps its place on the page: only
 * the box drawn over it moves in.
 */
.pdf-form-page__spot--found:not(:focus-within) {
  padding: 1px 3px;
}

.pdf-form-page__spot--found.pdf-form-page__spot--in-cell:not(:focus-within) {
  padding: 2px 4px;
}

/*
 * In a cell, the line is lighter and is the box's only edge, so beside the cell's own rules it reads as a mark
 * inside the cell rather than a second rule.
 */
.pdf-form-page__spot--in-cell .pdf-form-page__found-mark {
  inset: 2px 4px;
  border-color: color-mix(in srgb, var(--color-cocoa) 45%, transparent);
}

.pdf-form-page__spot--found.pdf-form-page__spot--in-cell :deep(.fill-spot__control) {
  border-color: transparent;
}

.pdf-form-page__spot:focus-within .pdf-form-page__found-mark {
  display: none;
}

/* A dashed edge means found on the pages, so an empty box keeps a solid one here. */
.pdf-form-page__spot :deep(.fill-spot--empty:not(.fill-spot--locked) .fill-spot__control) {
  border-style: solid;
}

.pdf-form-page__legend-found {
  display: inline-block;
  inline-size: 1.25em;
  block-size: 0.75em;
  margin-inline: 0.15em 0.1em;
  border: 1px dashed var(--color-cocoa);
  border-radius: 1px;
  vertical-align: -0.05em;
}

.pdf-form-page__drawn {
  position: absolute;
  border: 2px dashed var(--color-cocoa);
  background: color-mix(in srgb, var(--color-cocoa-wash) 40%, transparent);
  pointer-events: none;
}

.pdf-form-page__picked {
  position: absolute;
  inline-size: 0.75rem;
  block-size: 0.75rem;
  border: 2px solid var(--color-surface);
  border-radius: 50%;
  background: var(--color-cocoa);
  transform: translate(-50%, -50%);
  pointer-events: none;
}

/* In Edit boxes, each box Brownie drew is a button: dashed, with its name and a grip to size it. */
.pdf-form-page__handle {
  position: absolute;
  box-sizing: border-box;
  display: block;
  padding: 0;
  border: 2px dashed var(--color-cocoa);
  border-radius: 0.1rem;
  background: color-mix(in srgb, var(--color-cocoa-wash) 55%, transparent);
  color: var(--color-text);
  cursor: move;
  touch-action: none;
  text-align: start;
}

.pdf-form-page__handle-name {
  position: absolute;
  inset-block-end: 100%;
  inset-inline-start: 0;
  padding: 0 0.3em;
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  font-size: 0.6875rem;
  font-weight: 600;
  white-space: nowrap;
}

.pdf-form-page__grip {
  position: absolute;
  inset-block-end: -0.35rem;
  inset-inline-end: -0.35rem;
  inline-size: 0.7rem;
  block-size: 0.7rem;
  border: 2px solid var(--color-surface);
  background: var(--color-cocoa);
  cursor: nwse-resize;
}

.pdf-form-page__text {
  margin-block-start: var(--space-2);
  font-size: var(--font-size-sm);
}

.pdf-form-page__text summary {
  cursor: pointer;
  color: var(--color-text-muted);
}

.pdf-form-page__lines {
  margin: var(--space-2) 0 0;
  padding-inline-start: var(--space-5);
  overflow-wrap: anywhere;
}

.pdf-form-page__list {
  padding-block-start: var(--space-4);
  border-block-start: 1px solid var(--color-hairline);
}

.pdf-form-page__list-heading {
  margin: 0 0 var(--space-1);
  font-size: var(--font-size-base);
  font-weight: 600;
}

.pdf-form-page__field-label {
  margin-inline-end: 0.35em;
  font-weight: 600;
}

@media (forced-colors: active) {
  .pdf-form-page__handle,
  .pdf-form-page__drawn {
    border-color: Highlight;
  }
}
</style>
