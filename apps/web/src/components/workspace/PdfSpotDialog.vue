<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, useId } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { BoxSuggestionResponse, FillSpotChangeRequest } from '@/api/client'
import {
  DEFAULT_TEXT_STYLE,
  LABEL_RULE,
  MAX_TEXT_SIZE,
  MIN_TEXT_SIZE,
  SHRINK_FLOOR_PT,
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
 *
 * How its text fits is a small question about a box that is on the page right now, so it is asked in a
 * card that hangs off that box rather than in the middle of the window, and nothing behind it is dimmed:
 * the box it is about stays in view beside it. The other questions are about the form as a whole and are
 * asked in the usual centred dialog.
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
/** The box a size question is about; the card is placed beside it. */
let anchor: HTMLElement | null = null
/** Where the card hangs, in window pixels, once it has been placed beside its box; null while it is centred. */
const anchored = ref<{ left: number; top: number } | null>(null)

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
/** What the size became after the plus or minus button, said aloud: focus stays on the button, so the box beside it cannot be read. */
const sizeSaid = ref('')

/** A line index that asks for a box at the top of a page with no lines (a scan). */
const TOP_OF_PAGE = -1

/** The size question is the one asked beside its box; every other question is asked in the middle of the window. */
const popover = computed(() => mode.value?.kind === 'restyle')

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
  anchored.value = null
  sizeSaid.value = ''
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

/** `beside` is the box a size question is about, when it is on the page: the card hangs off it. */
function open(next: PdfSpotDialogMode, beside: HTMLElement | null = null): void {
  if (!isOpen.value) opener = window.document.activeElement instanceof HTMLElement ? window.document.activeElement : null
  anchor = beside
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
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    focusStart()
  })
}

// ---- Beside its box ------------------------------------------------------------------------------

/** Air between the box and the card hanging below it, and the least left between the card and the window's edge. */
const GAP = 14
const MARGIN = 12

/**
 * Hangs the size question below its box with the two right edges level, kept inside the window; above the box when
 * there is no room below it. A box that is not on screen, or no box, leaves the card centred like any dialog.
 */
function place(): void {
  const element = dialog.value
  const beside = anchor
  if (!popover.value || element === null || beside === null || !beside.isConnected) {
    anchored.value = null
    return
  }
  const box = beside.getBoundingClientRect()
  const card = element.getBoundingClientRect()
  const viewWidth = window.document.documentElement.clientWidth || window.innerWidth
  const viewHeight = window.innerHeight
  const onScreen = box.width > 0 && box.height > 0 && box.bottom > 0 && box.top < viewHeight && box.right > 0 && box.left < viewWidth
  if (!onScreen || !(card.width > 0)) {
    anchored.value = null
    return
  }
  let top = box.bottom + GAP
  if (top + card.height > viewHeight - MARGIN) {
    const above = box.top - GAP - card.height
    top = above >= MARGIN ? above : Math.max(MARGIN, viewHeight - MARGIN - card.height)
  }
  const left = Math.min(Math.max(box.right - card.width, MARGIN), Math.max(MARGIN, viewWidth - MARGIN - card.width))
  const placed = { left: Math.round(left), top: Math.round(top) }
  const was = anchored.value
  if (was === null || was.left !== placed.left || was.top !== placed.top) anchored.value = placed
}

function stopWatchingPlace(): void {
  window.removeEventListener('resize', place)
  window.removeEventListener('scroll', place, true)
}

onBeforeUnmount(stopWatchingPlace)

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
  stopWatchingPlace()
  anchor = null
  const element = dialog.value
  if (element !== null) {
    if (nativeModal.value && typeof element.close === 'function' && element.open) element.close()
    element.removeAttribute('open')
  }
  nativeModal.value = false
  anchored.value = null
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

/** Where a press began: a press that began in the card and ended outside it is a text selection, not a click away. */
let pressBeganOutside = false

function onPointerDown(event: PointerEvent): void {
  pressBeganOutside = event.target === dialog.value
}

/**
 * Nothing behind the size question is dimmed, so a click anywhere else on the page says no thanks. The dialog's own
 * box is all form, so a click that lands on the dialog itself landed outside the card.
 */
function onClick(event: MouseEvent): void {
  if (popover.value && pressBeganOutside && event.target === dialog.value) cancel()
  pressBeganOutside = false
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

// ---- The text size -------------------------------------------------------------------------------

/** The size as typed, when it is a number at all. */
function typedSize(): number | null {
  const typed = String(sizePt.value).trim()
  const value = Number(typed)
  return typed !== '' && Number.isFinite(value) ? value : null
}

function validSize(): number | null {
  const value = typedSize()
  return value !== null && value >= MIN_TEXT_SIZE && value <= MAX_TEXT_SIZE ? Math.round(value * 10) / 10 : null
}

/** The plus and minus buttons: a point at a time, from whatever is typed (or the usual size where nothing sensible is), within what the server accepts. */
function stepSize(by: 1 | -1): void {
  const from = typedSize() ?? DEFAULT_TEXT_STYLE.sizePt
  const size = Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, Math.round((from + by) * 10) / 10))
  sizePt.value = size
  sizeSaid.value = `Text size ${size} points`
  problem.value = null
}

const atSmallest = computed(() => (typedSize() ?? DEFAULT_TEXT_STYLE.sizePt) <= MIN_TEXT_SIZE)
const atLargest = computed(() => (typedSize() ?? DEFAULT_TEXT_STYLE.sizePt) >= MAX_TEXT_SIZE)

// ---- Sending -------------------------------------------------------------------------------------

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
    :class="{
      dialog: !popover,
      'pdf-spot-dialog--popover': popover,
      'pdf-spot-dialog--anchored': anchored !== null,
      'pdf-spot-dialog--fallback': !nativeModal,
    }"
    :style="anchored ? { left: `${anchored.left}px`, top: `${anchored.top}px` } : undefined"
    :aria-labelledby="isOpen ? ids.heading : undefined"
    @cancel.prevent="cancel"
    @close="onNativeClose"
    @keydown="onKeydown"
    @pointerdown="onPointerDown"
    @click="onClick"
  >
    <form v-if="isOpen && mode" class="pdf-spot-dialog__form" novalidate @submit.prevent="step === 'where' && mode.kind === 'add' ? next() : submit()">
      <header :class="popover ? 'pdf-spot-dialog__header' : 'dialog__head'">
        <h2 :id="ids.heading" ref="heading" :class="popover ? 'pdf-spot-dialog__title' : 'dialog__title'" tabindex="-1">{{ title }}</h2>
        <button class="icon-button" :class="popover ? 'pdf-spot-dialog__close' : 'dialog__close'" type="button" :aria-disabled="busy" @click="cancel">
          <AppIcon name="close" :size="popover ? 18 : 22" />
          <span class="visually-hidden">Close</span>
        </button>
      </header>

      <div :class="popover ? 'pdf-spot-dialog__body' : 'dialog__body pdf-spot-dialog__body'">
        <!-- The keyboard's way: a page, then one of its lines. -->
        <template v-if="mode.kind === 'add' && step === 'where'">
          <p class="field-hint">Choose a page and the line the fill spot goes next to. Brownie puts a box just after the line's words.</p>
          <div v-if="pages.length > 1">
            <label class="field-label" :for="ids.page">Page</label>
            <select :id="ids.page" v-model.number="pageNumber" class="text-field" @change="pageChanged">
              <option v-for="candidate in pages" :key="candidate.pageNumber" :value="candidate.pageNumber">{{ pageName(candidate.pageNumber) }}</option>
            </select>
          </div>
          <template v-if="pageHasLines">
            <div>
              <label class="field-label" :for="ids.filter">Find a line</label>
              <input :id="ids.filter" v-model="lineFilter" type="search" class="text-field" autocomplete="off" />
            </div>
            <div>
              <label class="field-label" :for="ids.line">Next to a line</label>
              <select :id="ids.line" v-model.number="lineIndex" size="8" class="text-field pdf-spot-dialog__lines">
                <option v-for="line in shownLines" :key="line.index" :value="line.index">{{ line.text }}</option>
              </select>
              <p v-if="shownLines.length === 0" class="field-hint pdf-spot-dialog__after">No line on this page has those words.</p>
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
          <div v-if="mode.kind !== 'restyle'">
            <label class="field-label" :for="ids.label">Name</label>
            <input
              :id="ids.label"
              v-model="label"
              type="text"
              class="text-field"
              maxlength="60"
              autocomplete="off"
              required
              :aria-describedby="ids.labelHint"
            />
            <p :id="ids.labelHint" class="field-hint pdf-spot-dialog__after">What goes here, for example Company or Date of birth.</p>
          </div>
          <fieldset v-if="mode.kind === 'add'" class="pdf-spot-dialog__choices">
            <legend class="pdf-spot-dialog__legend pdf-spot-dialog__legend--field">What goes here</legend>
            <label class="pdf-spot-dialog__choice"><input v-model="type" type="radio" value="TEXT" /><span>Text</span></label>
            <label class="pdf-spot-dialog__choice"><input v-model="type" type="radio" value="DATE" /><span>Date</span></label>
          </fieldset>
          <div v-if="mode.kind !== 'rename'" class="pdf-spot-dialog__size-row">
            <label class="pdf-spot-dialog__size-label" :for="ids.size">Text size (points)</label>
            <div class="pdf-spot-dialog__stepper">
              <button type="button" class="pdf-spot-dialog__step" :aria-disabled="atSmallest ? 'true' : undefined" @click="stepSize(-1)">
                <AppIcon name="minus" :size="16" />
                <span class="visually-hidden">Decrease text size</span>
              </button>
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
              <button type="button" class="pdf-spot-dialog__step" :aria-disabled="atLargest ? 'true' : undefined" @click="stepSize(1)">
                <AppIcon name="plus" :size="16" />
                <span class="visually-hidden">Increase text size</span>
              </button>
            </div>
          </div>
          <fieldset v-if="mode.kind !== 'rename'" class="pdf-spot-dialog__choices">
            <legend class="pdf-spot-dialog__legend">If the text is too long:</legend>
            <label class="pdf-spot-dialog__choice">
              <input v-model="overflow" type="radio" value="SHRINK_TO_FIT" /><span>Make the text smaller to fit (down to {{ SHRINK_FLOOR_PT }} pt)</span>
            </label>
            <label class="pdf-spot-dialog__choice"><input v-model="overflow" type="radio" value="BLOCK" /><span>Stop me before export</span></label>
          </fieldset>
        </template>

        <div class="pdf-spot-dialog__notes">
          <p class="pdf-spot-dialog__status" role="status">{{ working }}</p>
          <p v-if="problem" class="field-error" role="alert">{{ problem }}</p>
        </div>
        <p class="visually-hidden" role="status">{{ sizeSaid }}</p>
      </div>

      <div :class="popover ? 'pdf-spot-dialog__actions' : 'dialog__foot'">
        <button
          v-if="mode.kind === 'add' && step === 'name' && mode.box === null"
          type="button"
          class="button button--quiet"
          :class="{ 'button--small': popover }"
          :aria-disabled="busy"
          @click="!busy && back()"
        >
          Back
        </button>
        <button type="button" class="button button--secondary" :class="{ 'button--small': popover }" :aria-disabled="busy" @click="cancel">Cancel</button>
        <button v-if="mode.kind === 'add' && step === 'where'" type="submit" class="button button--primary" :aria-disabled="busy">Next</button>
        <button v-else type="submit" class="button button--primary" :class="{ 'button--small': popover }" :aria-disabled="busy">
          {{ submitWords }}
        </button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
/* The centred questions are the shared dialog, a little narrower than its default: they hold a name and a size. */
.pdf-spot-dialog.dialog {
  --dialog-width: 30rem;
}

/*
 * The size question: a small white card, level with the right edge of its box and hanging just below it. Nothing
 * is dimmed behind it, so the box it is about, and the page around the box, stay in view while it is answered.
 */
.pdf-spot-dialog--popover {
  inline-size: min(21.25rem, calc(100vw - 2rem));
  max-block-size: calc(100dvh - 1.5rem);
  margin: auto;
  padding: 0;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  color: var(--color-text);
  box-shadow: var(--shadow-popover);
}

.pdf-spot-dialog--popover::backdrop {
  background: transparent;
}

/* A browser without modal dialogs gets the same box laid over the page by hand. */
.pdf-spot-dialog--fallback[open] {
  position: fixed;
  inset: 0;
  margin: auto;
  z-index: 50;
  block-size: fit-content;
}

/* Placed beside its box: the two coordinates are set on the element, and the centring that would fight them is off. */
.pdf-spot-dialog.pdf-spot-dialog--anchored {
  position: fixed;
  inset: auto;
  margin: 0;
}

.pdf-spot-dialog__form {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-block-size: 0;
  max-block-size: inherit;
}

.pdf-spot-dialog--popover .pdf-spot-dialog__form {
  position: relative;
}

.pdf-spot-dialog__header {
  padding: 1rem 3rem 0 1rem;
}

.pdf-spot-dialog__title {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.2;
  overflow-wrap: anywhere;
}

/* The glyph sits 25px in from the card's top and right edges, as the title's first line does from the top. */
.pdf-spot-dialog__close {
  position: absolute;
  inset-block-start: 0.5625rem;
  inset-inline-end: 0.5625rem;
}

.pdf-spot-dialog__body {
  min-block-size: 0;
  overflow-y: auto;
}

.pdf-spot-dialog--popover .pdf-spot-dialog__body {
  padding: 0.875rem 1rem 0;
}

.pdf-spot-dialog__body :where(p) {
  margin: 0;
}

.pdf-spot-dialog__body > * + * {
  margin-block-start: var(--space-4);
}

/* A hint under a field sits close to it; the live lines take no room until they have something to say. */
.pdf-spot-dialog__after {
  margin-block-start: 0.5rem;
}

.pdf-spot-dialog__body > .pdf-spot-dialog__notes {
  margin-block-start: 0;
}

.pdf-spot-dialog__notes:has(> p:not(:empty)) {
  margin-block-start: var(--space-3);
}

.pdf-spot-dialog__status {
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.pdf-spot-dialog__lines {
  min-block-size: 10rem;
  padding: 0.25rem 0.5rem;
}

/* The size: its label on the left, the stepper at the right edge, level with each other. */
.pdf-spot-dialog__size-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  font-size: var(--font-size-sm);
}

.pdf-spot-dialog__size-label {
  min-inline-size: 0;
}

/*
 * One pill of three cells: "−", the size and "+". Its edge is the control's only boundary, so it is the darker edge
 * a text field has, not the pale one of a button.
 */
.pdf-spot-dialog__stepper {
  display: inline-flex;
  flex: none;
  align-items: stretch;
  block-size: 2.125rem;
  border: 1px solid var(--color-field-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  overflow: hidden;
}

.pdf-spot-dialog__step {
  display: grid;
  place-items: center;
  inline-size: 2rem;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: var(--color-text);
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.pdf-spot-dialog__step:hover:not([aria-disabled='true']) {
  background: var(--color-paper-soft);
}

.pdf-spot-dialog__step:active:not([aria-disabled='true']) {
  background: var(--color-paper-deep);
}

.pdf-spot-dialog__step[aria-disabled='true'] {
  opacity: 0.4;
  cursor: not-allowed;
}

.pdf-spot-dialog__step:focus-visible,
.pdf-spot-dialog__size:focus-visible {
  position: relative;
  border-radius: 0;
  box-shadow: inset 0 0 0 2px var(--color-focus);
}

.pdf-spot-dialog__size {
  inline-size: 2.75rem;
  min-inline-size: 0;
  padding: 0;
  border: 0;
  border-inline: 1px solid var(--color-outline);
  border-radius: 0;
  background: transparent;
  color: var(--color-text);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  text-align: center;
  appearance: textfield;
}

.pdf-spot-dialog__size::-webkit-inner-spin-button,
.pdf-spot-dialog__size::-webkit-outer-spin-button {
  margin: 0;
  appearance: none;
}

/* A question and the answers under it: the answers are rows with a round mark, chosen by a click anywhere on the row. */
.pdf-spot-dialog__choices {
  min-inline-size: 0;
  margin-inline: 0;
  padding: 0;
  border: 0;
}

.pdf-spot-dialog__legend {
  margin: 0 0 0.375rem;
  padding: 0;
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

.pdf-spot-dialog__legend--field {
  font-weight: var(--font-weight-medium);
}

.pdf-spot-dialog__choice {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-block-size: 2.125rem;
  font-size: var(--font-size-sm);
  line-height: 1.35;
  cursor: pointer;
}

.pdf-spot-dialog__choice input {
  flex: none;
  inline-size: 1.125rem;
  block-size: 1.125rem;
  margin: 0;
  border: 1.5px solid var(--color-field-border);
  border-radius: 50%;
  background: var(--color-surface);
  appearance: none;
  cursor: pointer;
}

/* Chosen: a cocoa disc with a white dot, a different shape from the empty ring, so it does not rest on colour alone. */
.pdf-spot-dialog__choice input:checked {
  border-color: var(--color-cocoa);
  background: radial-gradient(circle, var(--color-surface) 0 0.25rem, var(--color-cocoa) calc(0.25rem + 0.5px));
}

.pdf-spot-dialog__choice input:focus-visible {
  border-radius: 50%;
}

.pdf-spot-dialog__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem;
  padding: 0.5rem 1rem 1rem;
}

@media (forced-colors: active) {
  .pdf-spot-dialog__choice input:checked {
    border-color: Highlight;
    background: radial-gradient(circle, Canvas 0 0.25rem, Highlight calc(0.25rem + 0.5px));
  }
}
</style>
