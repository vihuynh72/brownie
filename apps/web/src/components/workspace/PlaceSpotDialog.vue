<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import SpotDialogShell from '@/components/workspace/SpotDialogShell.vue'
import {
  codePointLength,
  codePointSlice,
  lineWords,
  placementsForLine,
  suggestLabel,
  suggestType,
  type AnchorLine,
  type PlaceOption,
} from '@/workspace/anchors'
import { formatDateLikeExport } from '@/workspace/layout'
import { MAX_SPOT_LABEL, spotLabelProblem, tidySpotLabel, workingWords } from '@/workspace/fillSpotWords'

/**
 * Adding a fill spot to a Word form, in three steps a keyboard reaches as easily as a pointer: which
 * paragraph, where in it, and what goes there. A place chosen on the page itself (a selection, a click)
 * opens it at the last step, which says where the place is, shows the words around it with the new spot
 * drawn in, and has a way back to the other places in that paragraph. (The code calls a paragraph a line,
 * as the server does; the words a person reads say paragraph, which is what a wrapped line of the form is.)
 *
 * The change is made by the page (`send`); the dialog stays open while it is made, says so, and says
 * why it was refused if it was, since the page behind a modal dialog cannot be heard.
 */
export interface PlaceSpotRequest {
  line: AnchorLine
  place: PlaceOption
  label: string
  type: 'TEXT' | 'DATE'
}

/** How a refused change ended: why, and whether the place has to be chosen again (the page changed). */
export interface PlaceSpotRefusal {
  message: string
  choosePlaceAgain: boolean
}

const props = defineProps<{
  /** The lines of the form's body a spot can go in, in reading order. */
  lines: AnchorLine[]
  /** Makes the change; null when it was made. */
  send: (request: PlaceSpotRequest) => Promise<PlaceSpotRefusal | null>
}>()

const emit = defineEmits<{
  /** The spot was added and the dialog closed without taking focus back; the page moves it to the new spot. */
  added: [request: PlaceSpotRequest]
}>()

const shell = ref<InstanceType<typeof SpotDialogShell> | null>(null)
const ids = useId()
/** The form the last step's button, which sits in the dialog's foot, submits. */
const formId = `${ids}-form`

type Step = 1 | 2 | 3
const step = ref<Step>(1)
/** Opened from a place on the page: it starts at the last step, and "back" means the other places in the line. */
const fromPage = ref(false)
const lineId = ref<string | null>(null)
const placeKey = ref<string | null>(null)
/** A place from the page that the line's list of places may not hold (the words selected, a point in a word). */
const pagePlace = ref<PlaceOption | null>(null)
const filter = ref('')
const label = ref('')
const labelEdited = ref(false)
const type = ref<'TEXT' | 'DATE'>('TEXT')
const typeChosen = ref(false)
const labelError = ref<string | null>(null)
const busy = ref(false)
const status = ref('')
const error = ref<string | null>(null)

const line = computed(() => props.lines.find((candidate) => candidate.nodeId === lineId.value) ?? null)
const places = computed<PlaceOption[]>(() => {
  if (!line.value) return []
  const listed = placementsForLine(line.value)
  const extra = pagePlace.value
  return extra && !listed.some((place) => place.key === extra.key) ? [extra, ...listed] : listed
})
const place = computed(() => places.value.find((candidate) => candidate.key === placeKey.value) ?? null)

const shownLines = computed(() => {
  const words = filter.value.trim().toLocaleLowerCase()
  if (words === '') return props.lines
  return props.lines.filter((candidate) => `${lineWords(candidate)} ${candidate.where ?? ''}`.toLocaleLowerCase().includes(words))
})

function optionId(candidate: AnchorLine): string {
  return `${ids}-line-${props.lines.indexOf(candidate)}`
}

// ---- Opening --------------------------------------------------------------------------------------

function reset(): void {
  filter.value = ''
  label.value = ''
  labelEdited.value = false
  type.value = 'TEXT'
  typeChosen.value = false
  labelError.value = null
  busy.value = false
  status.value = ''
  error.value = null
  pagePlace.value = null
}

/** The keyboard route: the first step, the list of lines. */
function openAtLines(): void {
  reset()
  fromPage.value = false
  step.value = 1
  lineId.value = props.lines[0]?.nodeId ?? null
  placeKey.value = null
  shell.value?.open(() => document.getElementById(`${ids}-filter`))
}

/** A place chosen on the page: straight to naming it, the line shown with the place marked. */
function openAtPlace(target: AnchorLine, chosen: PlaceOption, returnTo?: HTMLElement | null): void {
  reset()
  fromPage.value = true
  lineId.value = target.nodeId
  pagePlace.value = chosen
  placeKey.value = chosen.key
  enterNaming()
  step.value = 3
  shell.value?.open(() => document.getElementById(`${ids}-label`), returnTo)
}

/** Whether the dialog is open, so the page can keep its own controls for a new spot out of the way meanwhile. */
const isOpen = computed(() => shell.value?.isOpen === true)

defineExpose({ openAtLines, openAtPlace, isOpen })

// ---- Moving between steps ---------------------------------------------------------------------------

async function focusStep(): Promise<void> {
  await nextTick()
  const target =
    step.value === 1
      ? document.getElementById(`${ids}-filter`)
      : step.value === 2
        ? (document.querySelector<HTMLElement>(`input[name="${ids}-place"]:checked`) ?? document.querySelector<HTMLElement>(`input[name="${ids}-place"]`))
        : document.getElementById(`${ids}-label`)
  target?.focus()
}

function enterNaming(): void {
  if (!line.value || !place.value) return
  if (!labelEdited.value) label.value = suggestLabel(line.value, place.value)
  if (!typeChosen.value) type.value = suggestType(label.value)
}

function toPlaces(): void {
  if (!line.value) return
  if (!places.value.some((candidate) => candidate.key === placeKey.value)) placeKey.value = places.value[0]?.key ?? null
  step.value = 2
  void focusStep()
}

function toNaming(): void {
  if (!place.value) return
  enterNaming()
  step.value = 3
  void focusStep()
}

function back(): void {
  if (busy.value) return
  error.value = null
  if (step.value === 3) toPlaces()
  else if (step.value === 2 && !fromPage.value) {
    step.value = 1
    void focusStep()
  }
}

function next(): void {
  if (step.value === 1) {
    if (lineId.value && shownLines.value.some((candidate) => candidate.nodeId === lineId.value)) toPlaces()
  } else if (step.value === 2) {
    toNaming()
  }
}

// ---- The list of lines ------------------------------------------------------------------------------

watch(shownLines, (shown) => {
  if (!shown.some((candidate) => candidate.nodeId === lineId.value)) lineId.value = shown[0]?.nodeId ?? null
})

function moveInList(event: KeyboardEvent): void {
  const shown = shownLines.value
  if (shown.length === 0) return
  const index = shown.findIndex((candidate) => candidate.nodeId === lineId.value)
  let target = index
  if (event.key === 'ArrowDown') target = Math.min(shown.length - 1, index + 1)
  else if (event.key === 'ArrowUp') target = Math.max(0, index - 1)
  else if (event.key === 'Home') target = 0
  else if (event.key === 'End') target = shown.length - 1
  else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    next()
    return
  } else return
  event.preventDefault()
  lineId.value = shown[target]!.nodeId
  void nextTick(() => document.getElementById(optionId(shown[target]!))?.scrollIntoView?.({ block: 'nearest' }))
}

function onFilterKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    document.getElementById(`${ids}-lines`)?.focus()
  } else if (event.key === 'Enter') {
    event.preventDefault()
    next()
  }
}

function chooseLine(candidate: AnchorLine): void {
  lineId.value = candidate.nodeId
  toPlaces()
}

// ---- The line with the place marked ---------------------------------------------------------------

const UNSEEN = /\ufffc/gu
/** About this many characters of the paragraph are shown on each side of the place. */
const EXCERPT = 40

/** The end of the words before the place, from the start of a word, with an ellipsis where it was cut. */
function wordsJustBefore(text: string): string {
  const length = codePointLength(text)
  if (length <= EXCERPT) return text
  const tail = codePointSlice(text, length - EXCERPT)
  const space = tail.search(/\s/u)
  return `\u2026${space >= 0 && space < tail.length - 1 ? tail.slice(space + 1) : tail}`
}

/** The start of the words after the place, to the end of a word, with an ellipsis where it was cut. */
function wordsJustAfter(text: string): string {
  if (codePointLength(text) <= EXCERPT) return text
  const head = codePointSlice(text, 0, EXCERPT)
  const space = head.search(/\s\S*$/u)
  return `${space > 0 ? head.slice(0, space) : head}\u2026`
}

/** The words around the place, about a short sentence each side of it, with what the spot takes the place of. */
const preview = computed(() => {
  const target = line.value
  const chosen = place.value
  if (!target || !chosen) return null
  const text = target.text
  let before: string
  let covered: string
  let after: string
  if (chosen.placement === 'EXISTING_CONTROL') {
    const control = target.controls.find((candidate) => candidate.controlNodeId === chosen.controlNodeId)
    const at = control?.at ?? chosen.start
    before = codePointSlice(text, 0, at)
    covered = control?.text ?? ''
    after = codePointSlice(text, at)
  } else {
    const wholeLine = chosen.placement === 'WHOLE_LINE' && chosen.end > chosen.start
    const start = wholeLine ? 0 : chosen.start
    const end = wholeLine ? chosen.end : chosen.placement === 'REPLACE' ? chosen.end : chosen.start
    before = codePointSlice(text, 0, start)
    covered = codePointSlice(text, start, end)
    after = codePointSlice(text, end)
  }
  return {
    before: wordsJustBefore(before.replace(UNSEEN, '')),
    covered: covered.replace(UNSEEN, ''),
    after: wordsJustAfter(after.replace(UNSEEN, '')),
  }
})

/** The most of a quoted word or phrase the sentence that says where the spot goes will hold. */
const QUOTED_MAX = 40

/**
 * Words quoted in a sentence: on one line, with a long blank written short (how long the line to write on is does
 * not help to find the place), and cut with an ellipsis, at the end or at the start, when still long.
 */
function quoted(text: string, cut: 'end' | 'start' = 'end'): string {
  const words = text.replace(/\s+/gu, ' ').trim().replace(/([_\uff3f])\1{4,}/gu, '$1$1$1$1')
  const length = codePointLength(words)
  if (length <= QUOTED_MAX) return `\u201c${words}\u201d`
  return cut === 'end'
    ? `\u201c${codePointSlice(words, 0, QUOTED_MAX - 1).trimEnd()}\u2026\u201d`
    : `\u201c\u2026${codePointSlice(words, length - (QUOTED_MAX - 1)).trimStart()}\u201d`
}

/** The last few words before a place: what tells it from other blanks in the paragraph. */
function wordsLeadingUp(before: string): string {
  return before.replace(/^\u2026/u, '').trim().split(/\s+/u).filter(Boolean).slice(-3).join(' ')
}

/**
 * Where the spot goes, in a short phrase for the row above its name: what it takes the place of and the words it
 * follows. A place that is the start, the end or the whole of a paragraph already says so in words of its own.
 */
const whereSummary = computed(() => {
  const chosen = place.value
  const shown = preview.value
  if (!chosen || !shown) return ''
  if (chosen.placement === 'REPLACE' || chosen.placement === 'EXISTING_CONTROL') {
    if (shown.covered.trim() === '') return chosen.words
    const leadingUp = wordsLeadingUp(shown.before)
    return `Replace ${quoted(shown.covered)}${leadingUp ? ` after ${quoted(leadingUp, 'start')}` : ''}`
  }
  const afterWords = /^After '([\s\S]*)'$/u.exec(chosen.words)
  return afterWords ? `After ${quoted(afterWords[1]!)}` : chosen.words
})

/** The new spot as the preview draws it: its name in square brackets, as an empty fill spot reads on the page. */
const markerWords = computed(() => `[${label.value.trim() || 'Fill spot'}]`)

/** How a date value reads once exported, so the Date choice shows what it will look like. */
const DATE_EXAMPLE = formatDateLikeExport('2026-10-13')

/** The words around the place for a screen reader, which is not read the drawn preview; nothing for an empty paragraph. */
const aroundWords = computed(() => {
  const shown = preview.value
  if (!shown || !line.value || place.value?.where.includes('the paragraph')) return ''
  const words = `${shown.before}${shown.covered}${shown.after}`.replace(/\s+/gu, ' ').trim()
  return words ? ` The paragraph reads "${words}".` : ''
})

// ---- Naming and sending -----------------------------------------------------------------------------

function onLabelInput(event: Event): void {
  label.value = (event.target as HTMLInputElement).value
  labelEdited.value = true
  labelError.value = null
  if (!typeChosen.value) type.value = suggestType(label.value)
}

function chooseType(value: 'TEXT' | 'DATE'): void {
  type.value = value
  typeChosen.value = true
}

async function submit(): Promise<void> {
  if (busy.value || !line.value || !place.value) return
  const problem = spotLabelProblem(label.value)
  if (problem) {
    labelError.value = problem
    document.getElementById(`${ids}-label`)?.focus()
    return
  }
  const request: PlaceSpotRequest = { line: line.value, place: place.value, label: tidySpotLabel(label.value), type: type.value }
  busy.value = true
  error.value = null
  status.value = workingWords('add')
  let refusal: PlaceSpotRefusal | null
  try {
    refusal = await props.send(request)
  } finally {
    busy.value = false
    status.value = ''
  }
  if (refusal === null) {
    shell.value?.close(false)
    emit('added', request)
    return
  }
  error.value = refusal.message
  if (refusal.choosePlaceAgain) {
    // The page changed under the dialog: the line is read again, and the person chooses the place in it again.
    pagePlace.value = null
    if (line.value) toPlaces()
    else if (fromPage.value) shell.value?.close()
    else {
      step.value = 1
      lineId.value = props.lines[0]?.nodeId ?? null
      void focusStep()
    }
  }
}

/** What the step asks: the large heading of the dialog. */
const stepTitle = computed(() => (step.value === 1 ? 'Which paragraph?' : step.value === 2 ? 'Where in the paragraph?' : 'What goes here?'))
</script>

<template>
  <SpotDialogShell ref="shell" caption="Add a fill spot" :title="stepTitle" :step="step" :steps="3" :busy="busy" :status="status" :error="error">
    <!-- Step 1: which line. A list to move through with the arrow keys, narrowed by the words typed above it. -->
    <div v-if="step === 1" class="place-spot__body">
      <div>
        <label class="field-label" :for="`${ids}-filter`">Find a paragraph</label>
        <input
          :id="`${ids}-filter`"
          v-model="filter"
          class="text-field"
          type="search"
          autocomplete="off"
          :aria-controls="`${ids}-lines`"
          :aria-describedby="`${ids}-lines-hint`"
          @keydown="onFilterKeydown"
        />
        <p :id="`${ids}-lines-hint`" class="field-hint">Type words from the paragraph, then use the arrow keys to choose it.</p>
      </div>
      <ul
        v-if="shownLines.length > 0"
        :id="`${ids}-lines`"
        class="place-spot__lines"
        role="listbox"
        tabindex="0"
        :aria-label="stepTitle"
        :aria-activedescendant="line && shownLines.includes(line) ? optionId(line) : undefined"
        @keydown="moveInList"
      >
        <li
          v-for="candidate in shownLines"
          :id="optionId(candidate)"
          :key="candidate.nodeId"
          class="place-spot__line"
          role="option"
          :aria-selected="candidate.nodeId === lineId ? 'true' : 'false'"
          @click="chooseLine(candidate)"
        >
          <span class="place-spot__line-words">{{ lineWords(candidate) }}</span>
          <span v-if="candidate.where" class="place-spot__line-where">{{ candidate.where }}</span>
        </li>
      </ul>
      <p v-else class="field-hint" role="status">{{ lines.length === 0 ? 'This form has no paragraph a fill spot can go in.' : 'No paragraph has those words.' }}</p>
    </div>

    <!-- Step 2: where in the line. -->
    <div v-else-if="step === 2 && line" class="place-spot__body">
      <p class="panel panel--quiet place-spot__paragraph">{{ lineWords(line) }}</p>
      <div class="place-spot__cards" role="radiogroup" :aria-label="stepTitle">
        <label v-for="candidate in places" :key="candidate.key" class="place-spot__card" :for="`${ids}-place-${candidate.key}`">
          <input
            :id="`${ids}-place-${candidate.key}`"
            v-model="placeKey"
            class="place-spot__radio"
            type="radio"
            :name="`${ids}-place`"
            :value="candidate.key"
            @keydown.enter.prevent="toNaming"
          />
          <span class="place-spot__card-title">{{ candidate.words }}</span>
        </label>
      </div>
    </div>

    <!-- Step 3: what goes there. The button that adds the spot is in the foot and names this form. -->
    <form v-else-if="step === 3 && line && place" :id="formId" class="place-spot__body" novalidate @submit.prevent="submit">
      <!-- Where the spot goes, in words; the line below it is for the eyes, so the words around the place are also given here, unseen. -->
      <div class="panel panel--quiet place-spot__where">
        <AppIcon name="pointer" :size="16" />
        <p class="place-spot__where-words">
          <span class="visually-hidden">Where it goes: </span>{{ whereSummary }}<span class="visually-hidden">.</span
          ><span class="place-spot__around visually-hidden">{{ aroundWords }}</span>
        </p>
        <button type="button" class="place-spot__link" :aria-disabled="busy ? 'true' : undefined" @click="back">Choose another place</button>
      </div>

      <!-- The words around the place as they will read, with the new spot drawn in as an empty fill spot is on the page. -->
      <div v-if="preview" class="place-spot__preview" aria-hidden="true">
        <p class="place-spot__preview-caption">Preview</p>
        <p class="place-spot__preview-line"><span>{{ preview.before }}</span><span class="place-spot__marker">{{ markerWords }}</span><span>{{ preview.after }}</span></p>
      </div>

      <div class="place-spot__fields">
        <div>
          <label class="field-label" :for="`${ids}-label`">Name</label>
          <input
            :id="`${ids}-label`"
            class="text-field"
            type="text"
            autocomplete="off"
            :value="label"
            :aria-invalid="labelError ? 'true' : undefined"
            :aria-describedby="labelError ? `${ids}-label-error ${ids}-label-hint` : `${ids}-label-hint`"
            @input="onLabelInput"
          />
          <p :id="`${ids}-label-hint`" class="field-hint">What the fill spot is called, such as Company. Up to {{ MAX_SPOT_LABEL }} characters.</p>
          <p v-if="labelError" :id="`${ids}-label-error`" class="field-error place-spot__label-error" role="alert">{{ labelError }}</p>
        </div>

        <fieldset class="place-spot__kinds">
          <legend class="field-label">What kind of value</legend>
          <div class="place-spot__cards place-spot__cards--pair">
            <label class="place-spot__card" :for="`${ids}-text`">
              <input
                :id="`${ids}-text`"
                class="place-spot__radio"
                type="radio"
                :name="`${ids}-type`"
                value="TEXT"
                :checked="type === 'TEXT'"
                :aria-labelledby="`${ids}-text-name`"
                :aria-describedby="`${ids}-text-hint`"
                @change="chooseType('TEXT')"
              />
              <span :id="`${ids}-text-name`" class="place-spot__card-title"><AppIcon name="type" :size="16" />Text</span>
              <span :id="`${ids}-text-hint`" class="place-spot__card-hint">Any words on one line.</span>
            </label>
            <label class="place-spot__card" :for="`${ids}-date`">
              <input
                :id="`${ids}-date`"
                class="place-spot__radio"
                type="radio"
                :name="`${ids}-type`"
                value="DATE"
                :checked="type === 'DATE'"
                :aria-labelledby="`${ids}-date-name`"
                :aria-describedby="`${ids}-date-hint`"
                @change="chooseType('DATE')"
              />
              <span :id="`${ids}-date-name`" class="place-spot__card-title"><AppIcon name="calendar" :size="16" />Date</span>
              <span :id="`${ids}-date-hint`" class="place-spot__card-hint">A date picker. Reads like {{ DATE_EXAMPLE }}.</span>
            </label>
          </div>
        </fieldset>
      </div>
    </form>

    <!-- Going back sits apart, at the start; Cancel and the main button stay together at the end, even when the foot wraps. -->
    <template #foot>
      <div v-if="step === 1" class="place-spot__finish">
        <button type="button" class="button button--secondary" @click="shell?.close()">Cancel</button>
        <button type="button" class="button button--primary" :aria-disabled="lineId === null ? 'true' : undefined" @click="next">
          Next<AppIcon name="arrow-right" :size="18" />
        </button>
      </div>
      <template v-else-if="step === 2 && line">
        <button v-if="!fromPage" type="button" class="button button--quiet place-spot__back" @click="back"><AppIcon name="arrow-left" :size="18" />Back</button>
        <div class="place-spot__finish">
          <button type="button" class="button button--secondary" @click="shell?.close()">Cancel</button>
          <button type="button" class="button button--primary" @click="toNaming">Next<AppIcon name="arrow-right" :size="18" /></button>
        </div>
      </template>
      <template v-else-if="step === 3 && line && place">
        <button type="button" class="button button--quiet place-spot__back" :aria-disabled="busy ? 'true' : undefined" @click="back">
          <AppIcon name="arrow-left" :size="18" />Back
        </button>
        <div class="place-spot__finish">
          <button type="button" class="button button--secondary" :aria-disabled="busy ? 'true' : undefined" @click="!busy && shell?.close()">Cancel</button>
          <button type="submit" class="button button--primary" :form="formId" :aria-disabled="busy ? 'true' : undefined">
            <AppIcon name="plus" :size="18" />{{ busy ? 'Adding…' : 'Add the fill spot' }}
          </button>
        </div>
      </template>
    </template>
  </SpotDialogShell>
</template>

<style scoped>
/* What the dialog shows at each step is a column of blocks, 20px apart. */
.place-spot__body {
  display: grid;
  gap: 1.25rem;
  min-inline-size: 0;
}

/* A hint under a field takes its room above it from the shared field styles; it takes none below, the blocks have gaps. */
.place-spot__body .field-hint {
  margin-block-end: 0;
}

.place-spot__body > .field-hint {
  margin-block-start: 0;
}

/* ---------- Step 1: the paragraphs ---------- */

.place-spot__lines {
  max-block-size: 16rem;
  margin: 0;
  padding: 0.25rem;
  overflow-y: auto;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  line-height: 1.4;
  list-style: none;
}

.place-spot__line {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-block-size: 2.625rem;
  padding: 0.25rem 0.75rem;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.place-spot__line:hover {
  background: var(--color-paper-soft);
}

/* The chosen paragraph has a cocoa edge and heavier words, so it does not rest on colour alone. */
.place-spot__line[aria-selected='true'] {
  background: var(--color-cocoa-soft);
  box-shadow: inset 0 0 0 1px var(--color-cocoa);
  font-weight: var(--font-weight-medium);
}

@media (forced-colors: active) {
  .place-spot__line[aria-selected='true'] {
    outline: 2px solid Highlight;
  }
}

.place-spot__line-where {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: 400;
}

/* ---------- Step 2: the places in a paragraph ---------- */

/* The paragraph on the shared quiet panel, its own line breaks kept. */
.place-spot__paragraph {
  margin: 0;
  font-size: var(--font-size-sm);
  line-height: 1.4;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* ---------- Choices drawn as cards ---------- */

/*
 * One choice is one card: a radio, then its name and, under that, what it means. The radio is the real input,
 * drawn here, so the arrow keys, the label and the form behave as for any radio group, and the whole card is its
 * label, so it can be pressed anywhere. Chosen, the edge thickens to two pixels with an inner shadow rather than
 * a wider border, so choosing never moves anything.
 */
.place-spot__cards {
  display: grid;
  gap: 0.5rem;
}

.place-spot__cards--pair {
  grid-template-columns: repeat(auto-fit, minmax(10.5rem, 1fr));
  align-items: start;
  gap: 0.625rem;
}

.place-spot__card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  column-gap: 0.625rem;
  padding: 0.625rem 0.75rem 0.7875rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  cursor: pointer;
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    border-color var(--motion-fast) var(--motion-ease),
    box-shadow var(--motion-fast) var(--motion-ease);
}

.place-spot__card:hover {
  background: var(--color-paper-soft);
}

.place-spot__card:has(.place-spot__radio:checked) {
  border-color: var(--color-cocoa);
  background: var(--color-cocoa-soft);
  box-shadow: inset 0 0 0 1px var(--color-cocoa);
}

.place-spot__card:has(.place-spot__radio:focus-visible) {
  box-shadow: var(--focus-ring);
}

.place-spot__card:has(.place-spot__radio:checked:focus-visible) {
  box-shadow:
    inset 0 0 0 1px var(--color-cocoa),
    var(--focus-ring);
}

.place-spot__radio {
  appearance: none;
  grid-row: 1 / span 2;
  inline-size: 1.125rem;
  block-size: 1.125rem;
  margin: 0.125rem 0 0;
  border: 1.5px solid var(--color-field-border);
  border-radius: 50%;
  background: var(--color-surface);
  cursor: pointer;
}

.place-spot__radio:checked {
  border-color: var(--color-cocoa);
  background: radial-gradient(circle, var(--color-surface) 0 0.1875rem, var(--color-cocoa) 0.25rem);
}

/* The card carries the focus ring, so the small circle draws none of its own. */
.place-spot__radio:focus-visible {
  box-shadow: none;
  outline: none;
}

.place-spot__card-title {
  display: flex;
  grid-column: 2;
  align-items: center;
  gap: 0.375rem;
  min-block-size: 1.25rem;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.place-spot__card-title > svg {
  flex: none;
}

.place-spot__card-hint {
  grid-column: 2;
  margin-block-start: 0.0625rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

/* A display that forces its own colours drops backgrounds and shadows, which would erase which card is chosen. */
@media (forced-colors: active) {
  .place-spot__radio:checked {
    forced-color-adjust: none;
    border-color: CanvasText;
    background: CanvasText;
  }

  .place-spot__card:has(.place-spot__radio:checked) {
    outline: 2px solid Highlight;
    outline-offset: -2px;
  }
}

/* ---------- Step 3: what goes here ---------- */

/* The shared quiet panel as one slim row: an icon, the words, and the way to choose again. */
.place-spot__where {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-block-size: 2.5rem;
  padding-block: 0.25rem;
  padding-inline: 0.6875rem;
}

.place-spot__where > svg {
  flex: none;
  color: var(--color-text-muted);
}

.place-spot__where-words {
  flex: 1 1 auto;
  min-inline-size: 0;
  margin: 0;
  font-size: var(--font-size-sm);
  line-height: 1.4;
  overflow-wrap: anywhere;
}

/* A button that reads as a link: cocoa words, no box. Dark cocoa, because cocoa itself is too pale on this panel. */
.place-spot__link {
  flex: none;
  min-block-size: 1.5rem;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-cocoa-strong);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
  cursor: pointer;
}

.place-spot__link:hover {
  text-decoration: underline;
  text-underline-offset: 0.1875rem;
}

.place-spot__link[aria-disabled='true'] {
  opacity: 0.4;
  cursor: not-allowed;
}

.place-spot__preview {
  padding: 0.8125rem 1rem 0.875rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.place-spot__preview-caption {
  margin: 0 0 0.46875rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.place-spot__preview-line {
  margin: 0;
  font-size: 0.8125rem;
  font-weight: var(--font-weight-medium);
  line-height: 1.45;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* The new spot as an empty one is drawn on the page: its name in brackets on honey, in the quiet text colour. */
.place-spot__marker {
  display: inline-block;
  margin-inline: 0.1875rem;
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  background: var(--color-honey-spot);
  color: var(--color-text-muted);
  font-weight: 400;
}

.place-spot__label-error {
  margin: 0.25rem 0 0;
}

/* The name and the kind of value belong together, so they sit a little closer than the blocks above them do. */
.place-spot__fields {
  display: grid;
  gap: 1rem;
}

.place-spot__kinds {
  min-inline-size: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.place-spot__kinds legend {
  padding: 0;
  margin-block-end: 0.5rem;
}

/* Going back is the one button at the start of the foot; the pair after it stays at the end, and wraps as a pair. */
.place-spot__back {
  margin-inline-end: auto;
}

.place-spot__finish {
  display: flex;
  gap: 0.625rem;
  margin-inline-start: auto;
}
</style>
