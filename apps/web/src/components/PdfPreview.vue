<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
// The "legacy" build carries PDF.js's own polyfills (the modern build assumes Map.prototype
// .getOrInsertComputed and other APIs only the newest browsers have, and fails outright without
// them); it is the build that runs in every browser this app supports. Keep PDF.js at 6.2.108 or
// newer: earlier releases could run script from a crafted PDF (npm audit, 2026-09).
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs'
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'
import AppIcon from '@/components/AppIcon.vue'

// PDF.js parses in a worker so a large document never freezes the page; Vite serves the worker
// file as its own asset and hands us its URL.
pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

/**
 * Draws one PDF as the white sheets it prints on, one under the other, the way the page view draws its sheet, with a
 * small pager floating over the foot of the view. The bytes are fetched with the session cookie (the API serves them
 * inline through an authorized route) rather than handed to PDF.js as a URL, so a refused request is a readable error
 * here instead of a blank frame. Only the pages near the screen are painted, so a long document costs no more memory
 * than a short one.
 */
const props = defineProps<{
  /** The URL to fetch the PDF from, or null to show nothing. */
  src: string | null
  /** What the drawing is, for assistive technology ("Preview of revision 4"). */
  label: string
}>()

const state = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const error = ref<string | null>(null)
const pageCount = ref(0)
/** The page most of which is on screen, which is the one the pager names. */
const currentPage = ref(1)
/** Height over width of each sheet. Every page is taken to be shaped like the first until it has been drawn. */
const shapes = ref<number[]>([])
const pages = ref<HTMLDivElement | null>(null)
const previousButton = ref<HTMLButtonElement | null>(null)
const nextButton = ref<HTMLButtonElement | null>(null)

const atFirst = computed(() => currentPage.value <= 1)
const atLast = computed(() => currentPage.value >= pageCount.value)

/** A page on its canvas, or on its way there, with the width (in CSS pixels) and the screen density it was drawn for. */
interface Drawing {
  width: number
  density: number
  task: RenderTask | null
}

let loadingTask: PDFDocumentLoadingTask | null = null
let pdf: PDFDocumentProxy | null = null
let loadSequence = 0
const drawn = new Map<number, Drawing>()

/** What the pages scroll in: the nearest element that scrolls its own contents, or null where the window scrolls. */
let scroller: HTMLElement | null = null
let listening: EventTarget | null = null
let sizeObserver: ResizeObserver | null = null
let frame = 0

/** Drops the current document and its worker connection; PDF.js frees a document through the task that loaded it. */
async function discardDocument(): Promise<void> {
  for (const page of [...drawn.keys()]) release(page)
  pdf = null
  const task = loadingTask
  loadingTask = null
  if (task) await task.destroy()
}

async function load(src: string | null): Promise<void> {
  const sequence = ++loadSequence
  await discardDocument()
  pageCount.value = 0
  currentPage.value = 1
  error.value = null
  if (!src) {
    state.value = 'idle'
    return
  }
  state.value = 'loading'
  try {
    const response = await fetch(src, { credentials: 'same-origin' })
    if (!response.ok) throw new Error(await describeRefusal(response))
    const data = new Uint8Array(await response.arrayBuffer())
    const task = pdfjs.getDocument({ data })
    const loaded = await task.promise
    if (sequence !== loadSequence) {
      await task.destroy()
      return
    }
    loadingTask = task
    pdf = loaded
    // The first page gives every sheet its shape up front, so nothing below it moves while the others are drawn.
    const { width, height } = (await loaded.getPage(1)).getViewport({ scale: 1 })
    shapes.value = Array.from({ length: loaded.numPages }, () => height / width)
    pageCount.value = loaded.numPages
    state.value = 'ready'
    await nextTick()
    if (sequence !== loadSequence) return
    follow()
    refresh()
  } catch (cause) {
    if (sequence !== loadSequence) return
    fail(cause instanceof Error && cause.message ? cause.message : 'The preview could not be shown.')
  }
}

function fail(message: string): void {
  for (const page of [...drawn.keys()]) release(page)
  state.value = 'error'
  error.value = message
}

/** The API's own problem detail and correlation id when it sent one, so a refused preview can be reported; the status alone otherwise. */
async function describeRefusal(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: string; correlationId?: string }
    if (problem && (problem.detail || problem.correlationId)) {
      return `${problem.detail ?? `The preview could not be fetched (${response.status}).`}${
        problem.correlationId ? ` Correlation ID: ${problem.correlationId}.` : ''
      }`
    }
  } catch {
    // Not a problem document; fall through to the plain status.
  }
  return `The preview could not be fetched (${response.status}).`
}

// ---- Drawing the pages that are near ---------------------------------------------------------------

function sheets(): HTMLElement[] {
  return pages.value ? (Array.from(pages.value.children) as HTMLElement[]) : []
}

function canvasOf(page: number): HTMLCanvasElement | null {
  return sheets()[page - 1]?.querySelector('canvas') ?? null
}

/** Paints one page to fit its sheet. A page already painted at this width is left as it is. */
async function draw(page: number): Promise<void> {
  const canvas = canvasOf(page)
  if (!pdf || !canvas) return
  const width = canvas.parentElement?.clientWidth ?? 0
  const density = window.devicePixelRatio || 1
  const earlier = drawn.get(page)
  if (earlier?.width === width && earlier.density === density) return
  earlier?.task?.cancel()
  const drawing: Drawing = { width, density, task: null }
  drawn.set(page, drawing)
  const sequence = loadSequence
  try {
    const source = await pdf.getPage(page)
    if (sequence !== loadSequence || drawn.get(page) !== drawing) return
    const context = canvas.getContext('2d')
    if (!context) {
      fail('This browser cannot draw the preview. Open the PDF instead.')
      return
    }
    // Fit the page to its sheet's width; draw at device resolution so text stays crisp when zoomed.
    const natural = source.getViewport({ scale: 1 })
    if (Math.abs((shapes.value[page - 1] ?? 0) - natural.height / natural.width) > 0.001) {
      shapes.value[page - 1] = natural.height / natural.width
    }
    const viewport = source.getViewport({ scale: (width || natural.width) / natural.width })
    canvas.width = Math.floor(viewport.width * density)
    canvas.height = Math.floor(viewport.height * density)
    drawing.task = source.render({
      canvas,
      viewport,
      transform: density === 1 ? undefined : [density, 0, 0, density, 0, 0],
    })
    await drawing.task.promise
  } catch (cause) {
    // A render cancelled by a newer width or a page that went out of range is not an error worth showing.
    if (cause instanceof Error && cause.name === 'RenderingCancelledException') return
    if (sequence !== loadSequence || drawn.get(page) !== drawing) return
    fail(`Page ${page} could not be drawn.`)
  }
}

/** Stops painting a page and gives back the memory its canvas held. */
function release(page: number): void {
  const drawing = drawn.get(page)
  if (!drawing) return
  drawing.task?.cancel()
  drawn.delete(page)
  const canvas = canvasOf(page)
  if (canvas) {
    canvas.width = 0
    canvas.height = 0
  }
}

// ---- Which page is on screen, and which are close enough to paint -----------------------------------

/** The part of the screen the sheets scroll through, in window coordinates. */
function visibleArea(): { top: number; bottom: number } {
  if (scroller) {
    const box = scroller.getBoundingClientRect()
    return { top: box.top, bottom: box.bottom }
  }
  return { top: 0, bottom: window.innerHeight }
}

/**
 * Names the page the person is on and paints the pages near the screen: those within a screen's height of it, with
 * the ones further than two screens away given up again. The page is the one with the largest share of itself in
 * view, the first of them when several are whole, and it stays put while it is still wholly in view, so the arrow
 * that was just pressed is not undone by the view settling.
 */
function refresh(): void {
  const list = sheets()
  if (state.value !== 'ready' || list.length === 0) return
  const { top, bottom } = visibleArea()
  const room = bottom - top
  if (room <= 0) return
  const boxes = list.map((sheet) => sheet.getBoundingClientRect())
  const shares = boxes.map((box) =>
    box.height > 0 ? Math.max(0, Math.min(box.bottom, bottom) - Math.max(box.top, top)) / box.height : 0,
  )
  if (shares[currentPage.value - 1] < 0.999) {
    let best = 0
    shares.forEach((share, index) => {
      if (share > shares[best] + 0.001) best = index
    })
    if (shares[best] > 0) currentPage.value = best + 1
  }
  boxes.forEach((box, index) => {
    // A sheet with no box is not on screen at all (the view is hidden); it is drawn once it has one.
    if (box.height <= 0) return
    const away = Math.max(top - box.bottom, box.top - bottom, 0)
    if (away <= room) void draw(index + 1)
    else if (away > 2 * room) release(index + 1)
  })
}

function schedule(): void {
  if (frame !== 0) return
  frame = requestAnimationFrame(() => {
    frame = 0
    refresh()
  })
}

function scrollParent(from: HTMLElement): HTMLElement | null {
  for (let node = from.parentElement; node; node = node.parentElement) {
    const { overflowY } = window.getComputedStyle(node)
    if (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') return node
  }
  return null
}

/** Starts listening to whatever scrolls the sheets. The window's height decides which that is, so this is also done on a resize. */
function follow(): void {
  if (!pages.value) return
  scroller = scrollParent(pages.value)
  const target: EventTarget = scroller ?? window
  if (target === listening) return
  listening?.removeEventListener('scroll', schedule)
  target.addEventListener('scroll', schedule, { passive: true })
  listening = target
}

function onResize(): void {
  follow()
  schedule()
}

// ---- The pager --------------------------------------------------------------------------------------

function goTo(page: number): void {
  const sheet = sheets()[page - 1]
  if (!sheet) return
  const behavior: ScrollBehavior = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true ? 'auto' : 'smooth'
  // The first page goes back to the very top, where the line above it that says what the file is can be read too.
  if (page === 1) (scroller ?? window).scrollTo?.({ top: 0, behavior })
  else sheet.scrollIntoView?.({ behavior, block: 'start' })
  // A page already wholly in view leaves nothing to scroll, so nothing would tell the pager which page was meant.
  const box = sheet.getBoundingClientRect()
  const { top, bottom } = visibleArea()
  if (box.top >= top && box.bottom <= bottom) currentPage.value = page
}

// A button that has just become unavailable would drop the focus the person pressed it with, so the other one takes
// it. This runs before the page is redrawn, while the button still holds the focus: the browser moves the focus away
// the moment the button is disabled, and by then there is no telling who had it. The focus is given without scrolling,
// which would stop the sheets in the middle of the glide that is bringing the page into view.
watch([atFirst, atLast], async () => {
  const held = document.activeElement
  await nextTick()
  if (held === nextButton.value && atLast.value && !atFirst.value) previousButton.value?.focus({ preventScroll: true })
  else if (held === previousButton.value && atFirst.value && !atLast.value) nextButton.value?.focus({ preventScroll: true })
})

watch(() => props.src, (src) => void load(src), { immediate: true })

watch(
  pages,
  (element, earlier) => {
    if (earlier) sizeObserver?.unobserve(earlier)
    if (element) sizeObserver?.observe(element)
  },
  { flush: 'post' },
)

onMounted(() => {
  window.addEventListener('resize', onResize)
  // The sheets change width with the room beside them (the sidebar opening, a narrower window): the pages are drawn again to fit.
  if (typeof ResizeObserver !== 'undefined') sizeObserver = new ResizeObserver(schedule)
  if (pages.value) sizeObserver?.observe(pages.value)
})

onBeforeUnmount(() => {
  loadSequence += 1
  window.removeEventListener('resize', onResize)
  listening?.removeEventListener('scroll', schedule)
  listening = null
  sizeObserver?.disconnect()
  sizeObserver = null
  cancelAnimationFrame(frame)
  void discardDocument()
})
</script>

<template>
  <div class="pdf-preview">
    <div v-if="state === 'loading'" class="pdf-preview__sheet">
      <p class="pdf-preview__quiet" role="status">
        <span class="activity-indicator" aria-hidden="true"></span>Loading preview…
      </p>
    </div>
    <p v-else-if="state === 'error'" class="panel panel--quiet pdf-preview__notice" role="alert">
      <AppIcon name="alert-circle" :size="16" />
      <span>{{ error }}</span>
    </p>
    <template v-else-if="state === 'ready'">
      <div ref="pages" class="pdf-preview__pages" role="group" :aria-label="label">
        <div v-for="page in pageCount" :key="page" class="pdf-preview__sheet" :style="{ aspectRatio: `1 / ${shapes[page - 1] ?? 1}` }">
          <canvas class="pdf-preview__canvas" role="img" :aria-label="`${label}, page ${page} of ${pageCount}`"></canvas>
          <span class="pdf-preview__number" aria-hidden="true">{{ page }}</span>
        </div>
      </div>
      <!-- It floats over the foot of the view and stays there while the sheets scroll, but comes after them so a keyboard reaches the pages first. -->
      <div class="pdf-preview__dock">
        <nav class="pdf-preview__pager" aria-label="Preview pages">
          <button ref="previousButton" type="button" class="icon-button pdf-preview__step" :disabled="atFirst" @click="goTo(currentPage - 1)">
            <AppIcon name="chevron-left" :size="18" />
            <span class="visually-hidden">Previous page</span>
          </button>
          <span class="pdf-preview__position" aria-live="polite" aria-atomic="true">Page {{ currentPage }} of {{ pageCount }}</span>
          <button ref="nextButton" type="button" class="icon-button pdf-preview__step" :disabled="atLast" @click="goTo(currentPage + 1)">
            <AppIcon name="chevron-right" :size="18" />
            <span class="visually-hidden">Next page</span>
          </button>
        </nav>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* The column the sheets sit in: as wide as the page view's sheet, 600px at most, and as wide as the screen allows below that. */
.pdf-preview {
  inline-size: 100%;
  max-inline-size: 37.5rem;
  min-inline-size: 0;
  margin-inline: auto;
}

.pdf-preview__pages {
  display: grid;
  gap: var(--space-4);
}

/*
 * The paper, drawn as the page view draws its own: white on the page colour, soft corners, no frame. Its shape is the
 * PDF page's (the script sets it), a letter page's until the pages have been read. The transparent outline is drawn by
 * forced colours, where the sheet would otherwise lose its edge.
 */
.pdf-preview__sheet {
  position: relative;
  inline-size: 100%;
  aspect-ratio: 8.5 / 11;
  border-radius: var(--radius-md);
  background: var(--color-surface);
  outline: 1px solid transparent;
  outline-offset: -1px;
  /* A button that scrolls a sheet to the top of the view leaves a little of the page colour above it. */
  scroll-margin-block-start: var(--space-3);
}

.pdf-preview__canvas {
  position: absolute;
  inset: 0;
  inline-size: 100%;
  block-size: 100%;
  border-radius: inherit;
}

/*
 * The number of each sheet, in the margin beside its top corner, where the page view keeps its own. That margin exists
 * only where the page sits beside Brownie's panel, so the number is drawn only there.
 */
.pdf-preview__number {
  display: none;
  position: absolute;
  inset-block-start: 0.1875rem;
  inset-inline-start: -3.125rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1;
  pointer-events: none;
  user-select: none;
}

@container main (min-width: 50rem) {
  .pdf-preview__number {
    display: block;
  }
}

/* While the file comes in, the sheet is already there with a quiet word on it, so the page does not jump when the file arrives. */
.pdf-preview__quiet {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  padding: 2.5rem 3rem 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.pdf-preview__notice {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin: 0;
  color: var(--color-text);
  font-size: var(--font-size-sm);
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.pdf-preview__notice > svg {
  margin-block-start: 0.0625rem;
  color: var(--color-error);
}

/*
 * A pill over the foot of the view that stays there while the sheets scroll. The dock is as tall as nothing, stuck to
 * the foot of the view, so the pill rises from that edge without taking any room. A document that fits the view has
 * nothing to stick to: its dock rests just below the last sheet, which puts the pill across that sheet's foot.
 */
.pdf-preview__dock {
  position: sticky;
  inset-block-end: 0.5rem;
  z-index: 1;
  block-size: 0;
  margin-block-start: 1.25rem;
  pointer-events: none;
}

.pdf-preview__pager {
  position: absolute;
  inset-block-end: 0;
  inset-inline: 0;
  inline-size: fit-content;
  margin-inline: auto;
  display: flex;
  align-items: center;
  /* Wide enough that the focus ring round an arrow clears the words beside it. */
  gap: 0.3125rem;
  padding: 1px 0.3125rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  box-shadow: var(--shadow-chip);
  pointer-events: auto;
}

.pdf-preview__step {
  border-radius: 50%;
  color: var(--color-text);
}

/* Unavailable at either end: quieter, and no wash under a pointer that has nothing to press. */
.pdf-preview__step:disabled,
.pdf-preview__step:disabled:hover {
  background: transparent;
  color: var(--color-text-faint);
  cursor: not-allowed;
}

.pdf-preview__position {
  color: var(--color-text);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.25;
  white-space: nowrap;
}
</style>
