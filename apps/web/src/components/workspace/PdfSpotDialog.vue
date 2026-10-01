<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { BoxSuggestionResponse, FillSpotChangeRequest } from '@/api/client'
import {
  DEFAULT_TEXT_STYLE,
  LABEL_RULE,
  MAX_TEXT_SIZE,
  MIN_TEXT_SIZE,
  WORKING_WORDS,
  normalizeSpotLabel,
  type PdfOverflow,
  type PdfPageModel,
  type PdfSpotDialogMode,
  type PdfSpotRequest,
  type PdfSpotResult,
  type PdfTextStyle,
} from '@/workspace/pdfPage'
import { pointFromDisplayed, type PdfBox } from '@/workspace/pdfGeometry'

/**
 * The questions a change to a PDF form's fill spots asks before it is made: where a new one goes and
 * what it is called, a new name, how its text fits, or whether to take it away. One modal dialog for
 * all of them, so each keeps focus inside while it is open and hands it back when it closes. The change
 * is sent from here and the dialog stays open while it is checked, so a refusal is read where the
 * person can put it right (a new name, a smaller size) or cancel.
 */
const props = defineProps<{
  pages: PdfPageModel[]
  /** Where a box goes next to one of a page's lines, or at a point on it: the server's suggestion. */
  suggest: (pageNumber: number, place: { lineIndex: number } | { point: { x: number; y: number } }) => Promise<BoxSuggestionResponse>
  /** Makes the change; the result says where focus goes next, or why nothing changed. */
  send: (request: PdfSpotRequest) => Promise<PdfSpotResult>
}>()

const ids = { heading: useId(), label: useId(), labelHint: useId(), size: useId(), page: useId(), line: useId(), filter: useId() }

const dialog = ref<HTMLDialogElement | null>(null)
const heading = ref<HTMLElement | null>(null)
const isOpen = ref(false)
const nativeModal = ref(false)
let opener: HTMLElement | null = null

const mode = ref<PdfSpotDialogMode | null>(null)
/** The keyboard's way to a new box asks where first; a drawn box starts at its name. */
const step = ref<'where' | 'name'>('name')
const pageNumber = ref<number>(1)
const lineFilter = ref('')
const lineIndex = ref<number | null>(null)
const placedBox = ref<PdfBox | null>(null)
const placedStyle = ref<PdfTextStyle | null>(null)
const placedLine = ref<string | null>(null)

const label = ref('')
const type = ref<'TEXT' | 'DATE'>('TEXT')
const sizePt = ref<number | string>(DEFAULT_TEXT_STYLE.sizePt)
const overflow = ref<PdfOverflow>('SHRINK_TO_FIT')

const busy = ref(false)
const working = ref('')
const problem = ref<string | null>(null)

/** A line index that asks for a box at the top of a page with no lines (a scan). */
const TOP_OF_PAGE = -1

const page = computed(() => props.pages.find((candidate) => candidate.pageNumber === pageNumber.value) ?? null)
const shownLines = computed(() => {
  const words = lineFilter.value.trim().toLocaleLowerCase()
  const lines = (page.value?.lines ?? []).filter((line) => line.text.trim() !== '')
  return words ? lines.filter((line) => line.text.toLocaleLowerCase().includes(words)) : lines
})
const pageHasLines = computed(() => (page.value?.lines ?? []).some((line) => line.text.trim() !== ''))

const title = computed(() => {
  const current = mode.value
  if (!current) return ''
  switch (current.kind) {
    case 'add':
      return step.value === 'where' ? 'Add a fill spot' : 'Name the fill spot'
    case 'rename':
      return `Rename ${current.label}`
    case 'restyle':
      return `Text size and overflow for ${current.label}`
    default:
      return `Remove the fill spot ${current.label}?`
  }
})

const whereWords = computed(() => {
  const current = mode.value
  if (current?.kind !== 'add' || (current.box === null && placedBox.value === null)) return ''
  const onPage = props.pages.length > 1 ? `On page ${pageNumber.value}` : 'On the page'
  return placedLine.value ? `${onPage}, next to “${placedLine.value}”.` : `${onPage}, where you drew it.`
})

function pageName(pageNumberToName: number): string {
  return `Page ${pageNumberToName} of ${props.pages.length}`
}

// ---- Opening and closing -----------------------------------------------------------------------

function reset(next: PdfSpotDialogMode): void {
  mode.value = next
  problem.value = null
  working.value = ''
  busy.value = false
  lineFilter.value = ''
  lineIndex.value = null
  placedLine.value = null
  type.value = 'TEXT'
  overflow.value = 'SHRINK_TO_FIT'
  sizePt.value = DEFAULT_TEXT_STYLE.sizePt
  label.value = ''
  if (next.kind === 'add') {
    step.value = next.box === null ? 'where' : 'name'
    pageNumber.value = next.pageNumber ?? props.pages[0]?.pageNumber ?? 1
    placedBox.value = next.box
    placedStyle.value = next.style
    sizePt.value = next.style?.sizePt ?? DEFAULT_TEXT_STYLE.sizePt
    label.value = next.labelGuess ?? ''
  } else if (next.kind === 'rename') {
    label.value = next.label
  } else if (next.kind === 'restyle') {
    sizePt.value = next.sizePt
    overflow.value = next.overflow
  }
}

function open(next: PdfSpotDialogMode): void {
  if (!isOpen.value) opener = window.document.activeElement instanceof HTMLElement ? window.document.activeElement : null
  reset(next)
  isOpen.value = true
  void nextTick(() => {
    const element = dialog.value
    if (element === null || !isOpen.value) return
    if (!element.open) {
      nativeModal.value = false
      if (typeof element.showModal === 'function') {
        try {
          element.showModal()
          nativeModal.value = true
        } catch {
          // A dialog the page already shows without a modal cannot become one; it is laid over the page instead.
        }
      }
      if (!nativeModal.value) element.setAttribute('open', '')
    }
    focusStart()
  })
}

/** A question is read first (its heading); a form starts at its first field. */
function focusStart(): void {
  if (mode.value?.kind === 'remove') {
    heading.value?.focus()
    return
  }
  // The first thing to fill in: the page or the line to find, a name, or a size.
  const field = dialog.value?.querySelector<HTMLInputElement | HTMLSelectElement>(
    '.pdf-spot-dialog__body select, .pdf-spot-dialog__body input:not([type="radio"])',
  )
  if (field) {
    field.focus()
    if (field instanceof HTMLInputElement && field.type === 'text') field.select()
  } else {
    heading.value?.focus()
  }
}

/** Closes the dialog; focus goes to `focusId` when given (the spot a change made), else back where it came from. */
function close(focusId: string | null = null): void {
  if (!isOpen.value) return
  isOpen.value = false
  const element = dialog.value
  if (element !== null) {
    if (nativeModal.value && typeof element.close === 'function' && element.open) element.close()
    element.removeAttribute('open')
  }
  nativeModal.value = false
  const returnTo = opener
  opener = null
  void nextTick(() => {
    const target = focusId ? window.document.getElementById(focusId) : null
    if (target instanceof HTMLElement) {
      target.scrollIntoView?.({ block: 'center' })
      target.focus()
      return
    }
    const active = window.document.activeElement
    const lost = active === null || active === window.document.body || element?.contains(active) === true
    if (lost && returnTo !== null && returnTo.isConnected) returnTo.focus()
  })
}

function cancel(): void {
  // A change already sent cannot be called back; the dialog waits for its answer.
  if (!busy.value) close()
}

function onNativeClose(): void {
  if (isOpen.value && dialog.value?.open !== true) close()
}

/** Without a native modal, Escape still cancels and Tab still stays inside. */
function onKeydown(event: KeyboardEvent): void {
  if (nativeModal.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    cancel()
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const focusable = [...dialog.value.querySelectorAll<HTMLElement>('button, input, select, textarea, [tabindex="0"]')].filter(
    (element) => !element.hasAttribute('disabled') && element.getClientRects().length > 0,
  )
  if (focusable.length === 0) return
  const first = focusable[0]!
  const last = focusable[focusable.length - 1]!
  if (event.shiftKey && window.document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && window.document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

defineExpose({ open, close })

// ---- The keyboard's way to a place ---------------------------------------------------------------

function pageChanged(): void {
  lineIndex.value = null
  lineFilter.value = ''
  problem.value = null
}

async function next(): Promise<void> {
  if (busy.value) return
  const chosen = pageHasLines.value ? lineIndex.value : TOP_OF_PAGE
  if (chosen === null) {
    problem.value = 'Choose the line the fill spot goes next to.'
    return
  }
  problem.value = null
  busy.value = true
  working.value = 'Finding a place next to that line…'
  try {
    const current = page.value!
    const place =
      chosen === TOP_OF_PAGE
        ? { point: pointFromDisplayed({ x: 36, y: 36 }, current, current.rotation) }
        : { lineIndex: chosen }
    const suggestion = await props.suggest(current.pageNumber, place)
    placedBox.value = suggestion.box
    placedStyle.value = suggestion.style
    placedLine.value = chosen === TOP_OF_PAGE ? null : (current.lines.find((line) => line.index === chosen)?.text.trim() ?? null)
    sizePt.value = suggestion.style?.sizePt ?? DEFAULT_TEXT_STYLE.sizePt
    if (!label.value.trim() && suggestion.labelGuess) label.value = suggestion.labelGuess
    step.value = 'name'
    await nextTick()
    focusStart()
  } catch {
    problem.value = 'Brownie could not find a place next to that line. Try again, or draw the box on the page.'
  } finally {
    busy.value = false
    working.value = ''
  }
}

async function back(): Promise<void> {
  step.value = 'where'
  problem.value = null
  await nextTick()
  focusStart()
}

// ---- Sending -------------------------------------------------------------------------------------

function validSize(): number | null {
  const value = Number(sizePt.value)
  return Number.isFinite(value) && value >= MIN_TEXT_SIZE && value <= MAX_TEXT_SIZE ? Math.round(value * 10) / 10 : null
}

function request(): PdfSpotRequest | string {
  const current = mode.value!
  if (current.kind === 'remove') {
    return { kind: 'remove', label: current.label, changes: [{ kind: 'REMOVE', fieldId: current.fieldId }] }
  }
  const size = current.kind === 'rename' ? null : validSize()
  if (current.kind !== 'rename' && size === null) return `Choose a text size from ${MIN_TEXT_SIZE} to ${MAX_TEXT_SIZE} points.`
  if (current.kind === 'restyle') {
    return { kind: 'restyle', label: current.label, changes: [{ kind: 'RESTYLE_BOX', fieldId: current.fieldId, sizePt: size, overflow: overflow.value }] }
  }
  const name = normalizeSpotLabel(label.value)
  if (name === null) return LABEL_RULE
  if (current.kind === 'rename') {
    return { kind: 'rename', label: name, changes: [{ kind: 'RENAME', fieldId: current.fieldId, label: name }] }
  }
  const box = placedBox.value
  if (box === null) return 'Choose where the fill spot goes first.'
  const change: FillSpotChangeRequest = {
    kind: 'ADD_BOX',
    pageNumber: pageNumber.value,
    box,
    label: name,
    type: type.value,
    style: { ...(placedStyle.value ?? DEFAULT_TEXT_STYLE), sizePt: size! },
    overflow: overflow.value,
  }
  return { kind: 'add', label: name, changes: [change] }
}

async function submit(): Promise<void> {
  if (busy.value || !mode.value) return
  const built = request()
  if (typeof built === 'string') {
    problem.value = built
    return
  }
  problem.value = null
  busy.value = true
  working.value = WORKING_WORDS[built.kind]
  try {
    const result = await props.send(built)
    if (result.ok) {
      busy.value = false
      close(result.focusId)
      return
    }
    problem.value = result.message
  } finally {
    busy.value = false
    working.value = ''
  }
}

const submitWords = computed(() => {
  switch (mode.value?.kind) {
    case 'add':
      return 'Add the fill spot'
    case 'rename':
      return 'Rename'
    case 'restyle':
      return 'Save'
    default:
      return 'Remove'
  }
})
</script>

<template>
  <dialog
    ref="dialog"
    class="pdf-spot-dialog"
    :class="{ 'pdf-spot-dialog--fallback': !nativeModal }"
    :aria-labelledby="isOpen ? ids.heading : undefined"
    @cancel.prevent="cancel"
    @close="onNativeClose"
    @keydown="onKeydown"
  >
    <form v-if="isOpen && mode" class="pdf-spot-dialog__form" novalidate @submit.prevent="step === 'where' && mode.kind === 'add' ? next() : submit()">
      <header class="pdf-spot-dialog__header">
        <h2 :id="ids.heading" ref="heading" class="pdf-spot-dialog__title" tabindex="-1">{{ title }}</h2>
        <button class="icon-button" type="button" :aria-disabled="busy" @click="cancel">
          <AppIcon name="close" />
          <span class="visually-hidden">Close</span>
        </button>
      </header>

      <div class="pdf-spot-dialog__body">
        <!-- The keyboard's way: a page, then one of its lines. -->
        <template v-if="mode.kind === 'add' && step === 'where'">
          <p class="field-hint">Choose a page and the line the fill spot goes next to. Brownie puts a box just after the line's words.</p>
          <div v-if="pages.length > 1" class="pdf-spot-dialog__field">
            <label class="field-label" :for="ids.page">Page</label>
            <select :id="ids.page" v-model.number="pageNumber" @change="pageChanged">
              <option v-for="candidate in pages" :key="candidate.pageNumber" :value="candidate.pageNumber">{{ pageName(candidate.pageNumber) }}</option>
            </select>
          </div>
          <template v-if="pageHasLines">
            <div class="pdf-spot-dialog__field">
              <label class="field-label" :for="ids.filter">Find a line</label>
              <input :id="ids.filter" v-model="lineFilter" type="search" autocomplete="off" />
            </div>
            <div class="pdf-spot-dialog__field">
              <label class="field-label" :for="ids.line">Next to a line</label>
              <select :id="ids.line" v-model.number="lineIndex" size="8" class="pdf-spot-dialog__lines">
                <option v-for="line in shownLines" :key="line.index" :value="line.index">{{ line.text }}</option>
              </select>
              <p v-if="shownLines.length === 0" class="field-hint">No line on this page has those words.</p>
            </div>
          </template>
          <p v-else class="field-hint">
            Brownie cannot read the lines on this page, because it is a scan. It puts the box near the top of the page; move
            it where it belongs with Edit boxes afterwards.
          </p>
        </template>

        <template v-else-if="mode.kind === 'remove'">
          <p>Its value stays in the version history.</p>
          <p v-if="mode.fromForm">The form keeps its own box; Brownie just stops filling it.</p>
        </template>

        <template v-else>
          <p v-if="whereWords" class="field-hint">{{ whereWords }}</p>
          <div v-if="mode.kind !== 'restyle'" class="pdf-spot-dialog__field">
            <label class="field-label" :for="ids.label">Name</label>
            <input
              :id="ids.label"
              v-model="label"
              type="text"
              maxlength="60"
              autocomplete="off"
              required
              :aria-describedby="ids.labelHint"
            />
            <p :id="ids.labelHint" class="field-hint">What goes here, for example Company or Date of birth.</p>
          </div>
          <fieldset v-if="mode.kind === 'add'" class="pdf-spot-dialog__choices">
            <legend class="field-label">What goes here</legend>
            <label><input v-model="type" type="radio" value="TEXT" /> Text</label>
            <label><input v-model="type" type="radio" value="DATE" /> Date</label>
          </fieldset>
          <div v-if="mode.kind !== 'rename'" class="pdf-spot-dialog__field">
            <label class="field-label" :for="ids.size">Text size (points)</label>
            <input
              :id="ids.size"
              v-model="sizePt"
              type="number"
              inputmode="decimal"
              :min="MIN_TEXT_SIZE"
              :max="MAX_TEXT_SIZE"
              step="0.5"
              class="pdf-spot-dialog__size"
            />
          </div>
          <fieldset v-if="mode.kind !== 'rename'" class="pdf-spot-dialog__choices">
            <legend class="field-label">If the text is too long:</legend>
            <label><input v-model="overflow" type="radio" value="SHRINK_TO_FIT" /> Make the text smaller to fit (down to 6 pt)</label>
            <label><input v-model="overflow" type="radio" value="BLOCK" /> Stop me before export</label>
          </fieldset>
        </template>

        <p class="pdf-spot-dialog__status" role="status">{{ working }}</p>
        <p v-if="problem" class="field-error" role="alert">{{ problem }}</p>
      </div>

      <div class="pdf-spot-dialog__actions">
        <button v-if="mode.kind === 'add' && step === 'where'" type="submit" class="button button--primary" :aria-disabled="busy">Next</button>
        <template v-else>
          <button type="submit" class="button button--primary" :aria-disabled="busy">{{ submitWords }}</button>
          <button
            v-if="mode.kind === 'add' && mode.box === null"
            type="button"
            class="button button--secondary"
            :aria-disabled="busy"
            @click="!busy && back()"
          >
            Back
          </button>
        </template>
        <button type="button" class="button button--secondary" :aria-disabled="busy" @click="cancel">Cancel</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.pdf-spot-dialog {
  width: min(32rem, 100% - 2rem);
  max-width: none;
  max-height: min(90dvh, 44rem);
  padding: 0;
  border: 1px solid var(--color-hairline);
  border-radius: 1rem;
  background: var(--color-surface);
  color: var(--color-text);
  box-shadow: 0 1rem 3rem rgb(42 41 36 / 0.2);
}

.pdf-spot-dialog::backdrop {
  background: color-mix(in srgb, var(--color-text, #2a2924) 45%, transparent);
}

/* A browser without modal dialogs gets the same box laid over the page by hand. */
.pdf-spot-dialog--fallback[open] {
  position: fixed;
  inset: 0;
  margin: auto;
  z-index: 50;
  height: fit-content;
}

.pdf-spot-dialog__form {
  display: flex;
  flex-direction: column;
  max-height: inherit;
}

.pdf-spot-dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5) 0;
}

.pdf-spot-dialog__title {
  margin: 0;
  font-size: var(--font-size-lg);
  overflow-wrap: anywhere;
}

.pdf-spot-dialog__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-5);
  overflow-y: auto;
}

.pdf-spot-dialog__body p {
  margin: 0;
}

.pdf-spot-dialog__field input,
.pdf-spot-dialog__field select {
  box-sizing: border-box;
  inline-size: 100%;
  min-block-size: var(--control-height);
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.pdf-spot-dialog__size {
  max-inline-size: 8rem;
}

.pdf-spot-dialog__lines {
  min-block-size: 10rem;
}

.pdf-spot-dialog__choices {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  border: 0;
}

.pdf-spot-dialog__choices label {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.pdf-spot-dialog__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5) var(--space-4);
}
</style>
