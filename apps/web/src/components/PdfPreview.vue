<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
// The "legacy" build carries PDF.js's own polyfills (the modern build assumes Map.prototype
// .getOrInsertComputed and other APIs only the newest browsers have, and fails outright without
// them); it is the build that runs in every browser this app supports. Keep PDF.js at 6.2.108 or
// newer: earlier releases could run script from a crafted PDF (npm audit, 2026-09).
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs'
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'

// PDF.js parses in a worker so a large document never freezes the page; Vite serves the worker
// file as its own asset and hands us its URL.
pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

/**
 * Draws one PDF, one page at a time, onto a canvas that fits its container. The bytes are fetched
 * with the session cookie (the API serves them inline through an authorized route) rather than
 * handed to PDF.js as a URL, so a refused request is a readable error here instead of a blank frame.
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
const pageNumber = ref(1)
const canvas = ref<HTMLCanvasElement | null>(null)
const frame = ref<HTMLDivElement | null>(null)

let loadingTask: PDFDocumentLoadingTask | null = null
let pdf: PDFDocumentProxy | null = null
let renderTask: RenderTask | null = null
let loadSequence = 0

/** Drops the current document and its worker connection; PDF.js frees a document through the task that loaded it. */
async function discardDocument(): Promise<void> {
  renderTask?.cancel()
  renderTask = null
  pdf = null
  const task = loadingTask
  loadingTask = null
  if (task) await task.destroy()
}

async function load(src: string | null): Promise<void> {
  const sequence = ++loadSequence
  await discardDocument()
  pageCount.value = 0
  pageNumber.value = 1
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
    pageCount.value = loaded.numPages
    state.value = 'ready'
    await renderPage()
  } catch (cause) {
    if (sequence !== loadSequence) return
    state.value = 'error'
    error.value = cause instanceof Error && cause.message ? cause.message : 'The preview could not be shown.'
  }
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

async function renderPage(): Promise<void> {
  const target = canvas.value
  if (!pdf || !target) return
  renderTask?.cancel()
  const page = await pdf.getPage(pageNumber.value)
  const context = target.getContext('2d')
  if (!context) {
    state.value = 'error'
    error.value = 'This browser cannot draw the preview. Open the PDF instead.'
    return
  }
  // Fit the page to the frame's width; draw at device resolution so text stays crisp when zoomed.
  const unscaled = page.getViewport({ scale: 1 })
  const frameWidth = frame.value?.clientWidth || unscaled.width
  const scale = frameWidth / unscaled.width
  const viewport = page.getViewport({ scale })
  const outputScale = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1
  target.width = Math.floor(viewport.width * outputScale)
  target.height = Math.floor(viewport.height * outputScale)
  target.style.width = `${Math.floor(viewport.width)}px`
  target.style.height = `${Math.floor(viewport.height)}px`
  const task = page.render({
    canvas: target,
    viewport,
    transform: outputScale === 1 ? undefined : [outputScale, 0, 0, outputScale, 0, 0],
  })
  renderTask = task
  try {
    await task.promise
  } catch (cause) {
    // A render cancelled by a newer page or a newer document is not an error worth showing.
    if (cause instanceof Error && cause.name === 'RenderingCancelledException') return
    state.value = 'error'
    error.value = 'The page could not be drawn.'
  }
}

function previousPage(): void {
  if (pageNumber.value > 1) pageNumber.value -= 1
}

function nextPage(): void {
  if (pageNumber.value < pageCount.value) pageNumber.value += 1
}

watch(() => props.src, (src) => void load(src), { immediate: true })
watch(pageNumber, () => void renderPage())

onBeforeUnmount(() => {
  loadSequence += 1
  void discardDocument()
})
</script>

<template>
  <div class="pdf-preview">
    <p v-if="state === 'loading'" aria-live="polite">Loading preview…</p>
    <p v-if="state === 'error'" class="field-error" role="alert">{{ error }}</p>
    <div v-if="state === 'ready'" class="pdf-preview__controls">
      <button type="button" class="button" :disabled="pageNumber <= 1" @click="previousPage">Previous page</button>
      <span aria-live="polite">Page {{ pageNumber }} of {{ pageCount }}</span>
      <button type="button" class="button" :disabled="pageNumber >= pageCount" @click="nextPage">Next page</button>
    </div>
    <div ref="frame" class="pdf-preview__frame">
      <canvas
        v-show="state === 'ready'"
        ref="canvas"
        class="pdf-preview__page"
        role="img"
        :aria-label="`${label}, page ${pageNumber} of ${pageCount}`"
      ></canvas>
    </div>
  </div>
</template>

<style scoped>
.pdf-preview__controls {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-bottom: var(--space-3);
}

.pdf-preview__frame {
  width: 100%;
  overflow: auto;
  background: var(--color-surface-muted, #f3f3f3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.pdf-preview__page {
  display: block;
  max-width: 100%;
  margin: 0 auto;
}
</style>
