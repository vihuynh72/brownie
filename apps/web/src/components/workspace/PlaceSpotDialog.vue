<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
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
import { MAX_SPOT_LABEL, spotLabelProblem, tidySpotLabel, workingWords } from '@/workspace/fillSpotWords'

/**
 * Adding a fill spot to a Word form, in three steps a keyboard reaches as easily as a pointer: which
 * paragraph, where in it, and what goes there. A place chosen on the page itself (a selection, a click)
 * opens it at the last step, showing the words around the place with the place marked, with a way back
 * to the other places in that paragraph. (The code calls a paragraph a line, as the server does; the
 * words a person reads say paragraph, which is what a wrapped line of the form is.)
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

/** Where the spot goes, in one short sentence; the words around it are shown above. */
const whereWords = computed(() => (place.value ? `The fill spot goes ${place.value.where}.` : ''))
/** The words around the place for a screen reader, which is not read the marked preview; nothing for an empty paragraph. */
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

const stepWords = computed(() => (fromPage.value ? null : `Step ${step.value} of 3`))
</script>

<template>
  <SpotDialogShell ref="shell" title="Add a fill spot" :busy="busy">
    <p v-if="stepWords" class="field-hint place-spot__step">{{ stepWords }}</p>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>

    <!-- Step 1: which line. A list to move through with the arrow keys, narrowed by the words typed above it. -->
    <div v-if="step === 1" class="place-spot__step-body">
      <h3 :id="`${ids}-lines-heading`" class="place-spot__heading">Which paragraph?</h3>
      <label class="field-label" :for="`${ids}-filter`">Find a paragraph</label>
      <input
        :id="`${ids}-filter`"
        v-model="filter"
        class="place-spot__input"
        type="search"
        autocomplete="off"
        :aria-controls="`${ids}-lines`"
        :aria-describedby="`${ids}-lines-hint`"
        @keydown="onFilterKeydown"
      />
      <p :id="`${ids}-lines-hint`" class="field-hint">Type words from the paragraph, then use the arrow keys to choose it.</p>
      <ul
        v-if="shownLines.length > 0"
        :id="`${ids}-lines`"
        class="place-spot__lines"
        role="listbox"
        tabindex="0"
        :aria-labelledby="`${ids}-lines-heading`"
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
      <div class="button-row place-spot__buttons">
        <button type="button" class="button button--secondary" @click="shell?.close()">Cancel</button>
        <button type="button" class="button button--primary" :aria-disabled="lineId === null ? 'true' : undefined" @click="next">Next</button>
      </div>
    </div>

    <!-- Step 2: where in the line. -->
    <fieldset v-else-if="step === 2 && line" class="place-spot__step-body place-spot__fieldset">
      <legend class="place-spot__heading">Where in the paragraph?</legend>
      <p class="place-spot__line-shown">{{ lineWords(line) }}</p>
      <div v-for="candidate in places" :key="candidate.key" class="place-spot__choice">
        <input
          :id="`${ids}-place-${candidate.key}`"
          v-model="placeKey"
          type="radio"
          :name="`${ids}-place`"
          :value="candidate.key"
          @keydown.enter.prevent="toNaming"
        />
        <label :for="`${ids}-place-${candidate.key}`">{{ candidate.words }}</label>
      </div>
      <div class="button-row place-spot__buttons">
        <button v-if="!fromPage" type="button" class="button button--secondary" @click="back">Back</button>
        <button type="button" class="button button--secondary" @click="shell?.close()">Cancel</button>
        <button type="button" class="button button--primary" @click="toNaming">Next</button>
      </div>
    </fieldset>

    <!-- Step 3: what goes there. -->
    <form v-else-if="step === 3 && line && place" class="place-spot__step-body" novalidate @submit.prevent="submit">
      <h3 class="place-spot__heading">What goes here?</h3>
      <!-- The words around the place as they read, with the place marked; the sentence after it says the same in words. -->
      <p v-if="preview" class="place-spot__preview" aria-hidden="true">
        <span>{{ preview.before }}</span><del v-if="preview.covered" class="place-spot__covered">{{ preview.covered }}</del
        ><span class="place-spot__marker">{{ label.trim() || 'Fill spot' }}</span><span>{{ preview.after }}</span>
      </p>
      <p class="field-hint">{{ whereWords }}<span class="visually-hidden">{{ aroundWords }}</span></p>
      <label class="field-label" :for="`${ids}-label`">Name</label>
      <input
        :id="`${ids}-label`"
        class="place-spot__input"
        type="text"
        autocomplete="off"
        :value="label"
        :aria-invalid="labelError ? 'true' : undefined"
        :aria-describedby="labelError ? `${ids}-label-error ${ids}-label-hint` : `${ids}-label-hint`"
        @input="onLabelInput"
      />
      <p :id="`${ids}-label-hint`" class="field-hint">What the fill spot is called, such as Company. Up to {{ MAX_SPOT_LABEL }} characters.</p>
      <p v-if="labelError" :id="`${ids}-label-error`" class="field-error" role="alert">{{ labelError }}</p>
      <fieldset class="place-spot__fieldset place-spot__types">
        <legend class="field-label">What kind of value</legend>
        <div class="place-spot__choice">
          <input :id="`${ids}-text`" type="radio" :name="`${ids}-type`" value="TEXT" :checked="type === 'TEXT'" @change="chooseType('TEXT')" />
          <label :for="`${ids}-text`">Text</label>
        </div>
        <div class="place-spot__choice">
          <input :id="`${ids}-date`" type="radio" :name="`${ids}-type`" value="DATE" :checked="type === 'DATE'" @change="chooseType('DATE')" />
          <label :for="`${ids}-date`">Date</label>
        </div>
      </fieldset>
      <p class="place-spot__status" role="status">{{ status }}</p>
      <!-- Going back sits apart, at the start; Cancel and the button that adds the spot stay together on one row. -->
      <div class="button-row place-spot__buttons place-spot__buttons--split">
        <button type="button" class="button button--secondary" :aria-disabled="busy ? 'true' : undefined" @click="back">
          {{ fromPage ? 'Choose another place' : 'Back' }}
        </button>
        <div class="place-spot__finish">
          <button type="button" class="button button--secondary" :aria-disabled="busy ? 'true' : undefined" @click="!busy && shell?.close()">Cancel</button>
          <button type="submit" class="button button--primary" :aria-disabled="busy ? 'true' : undefined">
            {{ busy ? 'Adding…' : 'Add the fill spot' }}
          </button>
        </div>
      </div>
    </form>
  </SpotDialogShell>
</template>

<style scoped>
.place-spot__step {
  margin: 0 0 var(--space-2);
}

.place-spot__step-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.place-spot__heading {
  margin: 0 0 var(--space-1);
  padding: 0;
  font-size: var(--font-size-base);
  font-weight: 600;
}

.place-spot__fieldset {
  margin: 0;
  padding: 0;
  border: 0;
  min-inline-size: 0;
}

.place-spot__input {
  inline-size: 100%;
  min-block-size: var(--control-height);
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  color: var(--color-text);
  font: inherit;
}

.place-spot__input[aria-invalid='true'] {
  border-color: var(--color-error);
}

.place-spot__lines {
  list-style: none;
  margin: 0;
  padding: var(--space-1);
  max-block-size: 16rem;
  overflow-y: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.place-spot__line {
  display: flex;
  flex-direction: column;
  padding: var(--space-2) var(--space-3);
  border-radius: calc(var(--radius) - 2px);
  cursor: pointer;
}

/* The chosen line: a tint and a bar at its start, so it does not rest on colour alone. */
.place-spot__line[aria-selected='true'] {
  background: var(--color-cocoa-wash);
  box-shadow: inset 3px 0 0 var(--color-cocoa);
  font-weight: 600;
}

@media (forced-colors: active) {
  .place-spot__line[aria-selected='true'] {
    outline: 2px solid Highlight;
  }
}

.place-spot__line-where {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  font-weight: 400;
}

.place-spot__line-shown {
  margin: 0 0 var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-paper);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.place-spot__choice {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-block-size: 2rem;
}

.place-spot__types {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-4);
}

.place-spot__types legend {
  inline-size: 100%;
}

.place-spot__preview {
  margin: 0;
  padding: var(--space-3);
  border-radius: var(--radius);
  background: var(--color-paper);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.place-spot__covered {
  color: var(--color-text-secondary);
}

/* Where the value goes: a marked box with its name, not a colour alone. */
.place-spot__marker {
  display: inline-block;
  margin-inline: 0.15em;
  padding: 0 0.4em;
  border: 1px dashed var(--color-cocoa);
  border-radius: 0.25rem;
  background: var(--color-cocoa-wash);
  font-size: 0.9em;
  font-weight: 600;
}

.place-spot__status {
  margin: 0;
}

.place-spot__buttons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  justify-content: flex-end;
  margin-block-start: var(--space-2);
}

.place-spot__buttons--split {
  justify-content: space-between;
}

/* Cancel and "Add the fill spot" wrap as a pair, never apart, and each label stays on one line. */
.place-spot__finish {
  display: flex;
  gap: var(--space-2);
  margin-inline-start: auto;
}

.place-spot__buttons .button {
  white-space: nowrap;
}
</style>
