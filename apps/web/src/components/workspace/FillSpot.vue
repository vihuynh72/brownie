<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { IconName } from '@/components/icons'
import type { FieldStateResponse } from '@/api/client'
import {
  fieldStateWords,
  formatDateLikeExport,
  hasLineBreak,
  normalizeLineBreaks,
  stateFromAssist,
  type CssStyle,
} from '@/workspace/layout'

/**
 * One place in the document where a value goes, drawn in the line of text it belongs to and in the
 * template's own text style, so what the person types already looks like the exported page. Its look
 * says what state the value is in (empty, typed, from Brownie, a place Brownie found, a question
 * waiting, needing attention, locked, proposed), and each of those looks differs in shape as well as
 * colour: the edge, a small mark at the start, an underline. The same words reach assistive technology
 * through the control's description.
 */
const props = withDefaults(
  defineProps<{
    /** The repeated item this spot edits; null for a scalar field. */
    rowIndex: number | null
    type: 'TEXT' | 'DATE'
    value: string
    label: string
    /** The template's own text inside the spot, shown while it is empty. */
    placeholder: string | null
    styleCss: CssStyle
    required: boolean
    locked: boolean
    state: FieldStateResponse | null
    selected: boolean
    inputId: string
    /** Why a locked spot cannot be edited, when the reason is not its own lock (a row locked elsewhere). */
    lockedHint?: string
    /** The page knows this value cannot be saved as it is (a row's date left blank). */
    attention?: boolean
    /** An element that says, once for the whole page, which key reaches a spot's review and lock controls. */
    keysHintId?: string
    /** Brownie found this place itself and the person has not said it is right yet: it carries a sparkle, and its words are in the description. */
    found?: boolean
    /** A value Brownie proposes that is not in the document yet. It stands in for the value as a ghost and cannot be edited until it is decided. */
    preview?: string | null
    /** How many questions from Brownie about this spot are still open. */
    questionCount?: number
  }>(),
  { lockedHint: 'Locked: unlock it to edit.', attention: false, keysHintId: undefined, found: false, preview: null, questionCount: 0 },
)

const emit = defineEmits<{
  'update:value': [value: string]
  focus: []
  /** Alt+Enter (Option+Return on a Mac): the person wants this spot's review and lock controls. */
  actions: []
}>()

const accessibleName = computed(() => {
  let name = props.label
  if (props.required) name += ', required'
  if (props.rowIndex !== null) name += `, row ${props.rowIndex + 1}`
  return name
})

const previewing = computed(() => typeof props.preview === 'string' && props.preview.trim() !== '')
/** What the spot shows: a proposal stands in for the value until it is decided. */
const shownValue = computed(() => (previewing.value ? (props.preview ?? '') : props.value))
/** A locked value cannot change, and neither can a proposal that is not in the document yet; both can still be read, selected and described. */
const readOnly = computed(() => props.locked || previewing.value)
const empty = computed(() => props.value.trim() === '')
const shownEmpty = computed(() => shownValue.value.trim() === '')

/** The state in words; a locked spot says why it is locked instead of repeating "Locked". */
const description = computed(() => {
  const state = props.state && props.locked ? { ...props.state, lock: 'EDITABLE' as const } : props.state
  const sentences = fieldStateWords(state).map((word) => `${word}.`)
  // The sparkle on the page says it in a picture; this says the same to someone who cannot see it.
  if (props.found) sentences.unshift('Found by Brownie; check that this is the right place.')
  if (previewing.value) sentences.unshift('Proposed by Brownie, not in the document yet. Approve or hide the preview to edit it.')
  if (props.questionCount > 0) {
    sentences.push(
      props.questionCount === 1
        ? 'Brownie has a question about this spot. Answer it in the chat.'
        : `Brownie has ${props.questionCount} questions about this spot. Answer them in the chat.`,
    )
  }
  if (props.locked) sentences.push(props.lockedHint)
  return sentences.join(' ')
})
const descriptionId = computed(() => `${props.inputId}-state`)
const describedBy = computed(() => [description.value ? descriptionId.value : null, props.keysHintId ?? null].filter(Boolean).join(' ') || undefined)

/** An empty spot shows the template's own placeholder, in brackets, so it reads as a gap and not as a value. */
const shownPlaceholder = computed(() => {
  const own = props.placeholder?.trim()
  if (!own) return `[${props.label}]`
  return own.startsWith('[') && own.endsWith(']') ? own : `[${own}]`
})

/**
 * How many characters wide a text spot is where the browser cannot size it to its content itself: what
 * it shows (the value, or the placeholder while empty), from a small minimum up to about a line.
 */
const MIN_CHARS = 4
const MAX_CHARS = 60
const widthCss = computed(() => {
  const shown = shownEmpty.value ? shownPlaceholder.value : shownValue.value
  return { '--spot-chars': String(Math.min(MAX_CHARS, Math.max(MIN_CHARS, [...shown].length + 1))) }
})

/** A date reads as the export prints it while it is not being edited; the control underneath keeps the entry, the picker and the screen reader. */
const dateText = computed(() => (shownEmpty.value ? shownPlaceholder.value : formatDateLikeExport(shownValue.value.trim())))
/** A date the page can read is one short line and stays on it; whatever else a date field holds may be long, so it is let wrap. */
const dateOnOneLine = computed(() => !shownEmpty.value && dateText.value !== shownValue.value.trim())

/**
 * A proposal always reads in honey ink and italics, whatever colour or slant the template gives that text,
 * so it cannot be mistaken for a value that is in the document.
 */
const PREVIEW_INK = { color: 'var(--color-honey-ink)', fontStyle: 'italic' }
const controlStyle = computed(() => [props.styleCss, previewing.value ? PREVIEW_INK : undefined])
/** The placeholder keeps its own quiet colour, so a template colour reaches a date's text only once it holds a value. */
const dateStyle = computed(() => {
  const own = { ...props.styleCss }
  if (shownEmpty.value) delete own.color
  return [own, previewing.value ? PREVIEW_INK : undefined]
})
/** The marks are positioned in em of the spot's own text, so they follow the template's size. */
const markStyle = computed(() => (props.styleCss.fontSize ? { fontSize: props.styleCss.fontSize } : undefined))

const invalid = computed(() => props.attention || props.state?.validation === 'BLOCKING')
/** Red: the value cannot be saved or exported as it is, or a reviewer turned it down. */
const errorLook = computed(() => invalid.value || props.state?.review === 'REJECTED')
/** Honey with a question mark: Brownie has asked about it, or the person asked for clarification. */
const questioned = computed(() => props.questionCount > 0 || props.state?.review === 'NEEDS_CLARIFICATION')
/** The small mark at the start of a spot that is more than a plain value, by what matters most about it. */
const mark = computed<IconName | null>(() => {
  if (previewing.value) return 'sparkle'
  if (errorLook.value) return 'alert-circle'
  if (questioned.value) return 'help-circle'
  return props.found ? 'sparkle' : null
})
const countText = computed(() => (props.questionCount > 99 ? '99+' : String(props.questionCount)))

const classes = computed(() => ({
  'fill-spot--empty': empty.value,
  'fill-spot--filled': !empty.value,
  'fill-spot--assist': !empty.value && stateFromAssist(props.state),
  'fill-spot--attention': errorLook.value,
  'fill-spot--question': questioned.value,
  'fill-spot--found': props.found,
  'fill-spot--preview': previewing.value,
  'fill-spot--locked': props.locked,
  'fill-spot--selected': props.selected,
  'fill-spot--date': props.type === 'DATE',
  'fill-spot--marked': mark.value !== null,
}))

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.isComposing) return
  // The exported value is one line of text, so Enter never starts a second one. The Enter that
  // finishes an input-method composition is left alone: it commits the composed text.
  event.preventDefault()
  // The spot's review and lock controls sit in a bar after the whole page, where Tab alone would
  // only reach them for the last spot; this key goes straight there.
  if (event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey) emit('actions')
}

function onPaste(event: ClipboardEvent): void {
  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (readOnly.value || !hasLineBreak(text)) return
  event.preventDefault()
  const control = event.target as HTMLTextAreaElement
  const oneLine = normalizeLineBreaks(text)
  // insertText keeps the paste on the browser's own undo history and fires the input event itself;
  // where it is not supported, the text is placed directly and the new value reported here.
  const inserted = typeof window.document.execCommand === 'function' && window.document.execCommand('insertText', false, oneLine)
  if (!inserted) {
    control.setRangeText(oneLine, control.selectionStart, control.selectionEnd, 'end')
    emit('update:value', control.value)
  }
}

function onInput(event: Event): void {
  const control = event.target as HTMLTextAreaElement | HTMLInputElement
  let value = control.value
  if (props.type === 'TEXT' && hasLineBreak(value)) {
    // A line break that arrived some other way (dropped text, a phone keyboard's return key) is
    // folded like a pasted one, keeping the caret where the person left it.
    const caret = normalizeLineBreaks(value.slice(0, control.selectionStart ?? value.length)).length
    value = normalizeLineBreaks(value)
    control.value = value
    control.setSelectionRange(caret, caret)
  }
  emit('update:value', value)
}
</script>

<template>
  <span class="fill-spot" :class="classes">
    <span class="fill-spot__field">
      <!-- Pictures only: the words are in the control's description. The ones that say a place was found keep their words here, out of sight and out of the way of a screen reader. -->
      <span v-if="mark" class="fill-spot__mark" :class="{ 'fill-spot__found': found && mark === 'sparkle' }" :style="markStyle" aria-hidden="true">
        <AppIcon :name="mark" :size="12" />
        <span v-if="found && mark === 'sparkle'" class="visually-hidden">Found by Brownie</span>
      </span>
      <textarea
        v-if="type === 'TEXT'"
        :id="inputId"
        class="fill-spot__control fill-spot__control--text fill-spot__box"
        rows="1"
        :value="shownValue"
        :placeholder="shownPlaceholder"
        :aria-label="accessibleName"
        :aria-describedby="describedBy"
        aria-keyshortcuts="Alt+Enter"
        :aria-invalid="invalid ? 'true' : undefined"
        :aria-readonly="readOnly ? 'true' : undefined"
        :readonly="readOnly"
        :style="[controlStyle, widthCss]"
        @input="onInput"
        @keydown="onKeydown"
        @paste="onPaste"
        @focus="emit('focus')"
        @click="emit('focus')"
      ></textarea>
      <template v-else>
        <input
          :id="inputId"
          type="date"
          class="fill-spot__control fill-spot__control--date fill-spot__box"
          :value="shownValue"
          :aria-label="accessibleName"
          :aria-describedby="describedBy"
          aria-keyshortcuts="Alt+Enter"
          :aria-invalid="invalid ? 'true' : undefined"
          :aria-readonly="readOnly ? 'true' : undefined"
          :readonly="readOnly"
          :style="controlStyle"
          @input="onInput"
          @keydown="onKeydown"
          @focus="emit('focus')"
          @click="emit('focus')"
        />
        <!-- Sits over the date control while it is not being edited, and sizes the spot to what it says. -->
        <span class="fill-spot__date-text fill-spot__box" :class="{ 'fill-spot__date-text--one-line': dateOnOneLine }" :style="dateStyle" aria-hidden="true">{{ dateText }}</span>
      </template>
      <AppIcon v-if="locked" class="fill-spot__lock" name="lock" :size="12" :style="markStyle" />
    </span>
    <!--
      Seen, not heard: the control's own name already says "required", and the page says what the mark
      means. The word joiner keeps the mark on the control's line after a long value.
    -->
    <span v-if="required" class="fill-spot__required" aria-hidden="true">&#8288;*</span>
    <span v-if="questionCount > 0" class="fill-spot__count" aria-hidden="true">{{ countText }}</span>
    <span v-if="description" :id="descriptionId" class="visually-hidden">{{ description }}</span>
  </span>
</template>

<style scoped>
.fill-spot {
  /* Stays in the line of text, like the value it stands for. */
  display: inline;
  /* A little air after the label before it, beyond the space the page's own text leaves. */
  margin-inline-start: 0.2em;
  --spot-radius: 0.1875rem;
  /* Room a spot keeps at the start and the end of its text for a small picture, and below it for the double underline. */
  --spot-room-start: 0px;
  --spot-room-end: 0px;
  --spot-under: 0px;
  /* The colour of the double underline under a value from Brownie. */
  --spot-line: var(--color-honey);
  /* What a heavier edge adds inside the box; a focus ring is drawn outside it and keeps it. */
  --spot-inset: inset 0 0 0 0 transparent;
}

/* At the start of a line the box lines up with the text above it. */
.fill-spot:first-child {
  margin-inline-start: 0;
}

.fill-spot--marked {
  --spot-room-start: calc(12px + 0.3em);
}

.fill-spot--locked {
  --spot-room-end: calc(12px + 0.3em);
}

/* A value from Brownie carries two thin lines under its tint, so it is told apart without seeing colour. */
.fill-spot--assist {
  --spot-under: 6px;
}

.fill-spot--question {
  --spot-inset: inset 0 0 0 1px var(--color-honey);
}

.fill-spot--attention {
  --spot-line: var(--color-error);
  --spot-inset: inset 0 0 0 1px var(--color-error);
}

.fill-spot--preview {
  --spot-inset: inset 0 0 0 0 transparent;
}

/*
 * The spot's frame, which holds the control and the small pictures around it. Top-aligned rather than on the
 * baseline: browsers put a text area's baseline at its bottom edge, so baseline alignment would drop the label
 * beside it below the value, and to the last line of a value that wraps. Aligned to the top and raised by its
 * own edge, the value's first line sits level with the text before it.
 */
.fill-spot__field {
  position: relative;
  /* Its own stacking layer, so the pictures raised above the control stay inside the spot. */
  isolation: isolate;
  display: inline-block;
  vertical-align: top;
  max-inline-size: 100%;
  margin-block-start: -1px;
  padding-block-end: var(--spot-under);
}

.fill-spot--assist .fill-spot__field::after {
  content: '';
  position: absolute;
  inset: auto 0 0;
  block-size: 4px;
  background:
    linear-gradient(var(--spot-line), var(--spot-line)) top / 100% 1px no-repeat,
    linear-gradient(var(--spot-line), var(--spot-line)) bottom / 100% 1px no-repeat;
  pointer-events: none;
}

/* The room under a value from Brownie stays while a proposal stands in for it, so showing or hiding proposals moves nothing. */
.fill-spot--preview .fill-spot__field::after {
  display: none;
}

/* A display that forces its own colours drops fills, which would erase the lines; hand them the text colour. */
@media (forced-colors: active) {
  .fill-spot--assist .fill-spot__field::after {
    forced-color-adjust: none;
    background:
      linear-gradient(CanvasText, CanvasText) top / 100% 1px no-repeat,
      linear-gradient(CanvasText, CanvasText) bottom / 100% 1px no-repeat;
  }
}

/*
 * The box of a spot: a fill, an edge and a rounded corner, drawn on the text itself (the control, or the date
 * drawn over it) so the colour of the text is always read against the colour behind it. Every state has the
 * same size, so a spot never shifts the line it is in.
 */
.fill-spot__box {
  box-sizing: border-box;
  border: 1px solid transparent;
  border-radius: var(--spot-radius);
  background: transparent;
  box-shadow: var(--spot-inset);
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    border-color var(--motion-fast) var(--motion-ease);
}

/*
 * A value the person typed: plain on the page, with a thin line under it that says where it can be changed.
 * It is the one sign that this text is a field, so it takes the field edge colour (3:1 on white) rather than
 * the paler outline colour.
 */
.fill-spot--filled .fill-spot__box {
  border-block-end-color: var(--color-field-border);
}

/* A gap in the page, and a value Brownie wrote: a warm tint. */
.fill-spot--empty .fill-spot__box {
  background: var(--color-honey-spot);
}

.fill-spot--assist .fill-spot__box {
  border-block-end-color: transparent;
  background: var(--color-honey-soft);
}

.fill-spot--locked .fill-spot__box {
  border-color: var(--color-outline);
}

/* A place Brownie found and the person has not kept yet. */
.fill-spot--found .fill-spot__box {
  border-style: dashed;
  border-color: var(--color-honey-ink);
}

/* A question is waiting: a heavier honey edge. */
.fill-spot--question .fill-spot__box {
  border-style: solid;
  border-color: var(--color-honey);
  background: var(--color-honey-spot);
}

/* Needs attention: a heavier red edge on a red wash. */
.fill-spot--attention .fill-spot__box {
  border-style: solid;
  border-color: var(--color-error);
  background: var(--color-error-wash);
}

/* A proposal: a ghost, outlined with a dashed honey line and not filled. */
.fill-spot--preview .fill-spot__box {
  border-style: dashed;
  border-color: var(--color-honey);
  background: transparent;
}

/* The spot the bar below the page is about: a blue edge hugging the box. When the control has focus, the usual ring takes that edge's place. */
.fill-spot--selected .fill-spot__box {
  outline: 2px solid var(--color-focus);
  outline-offset: 0;
}

.fill-spot__control {
  display: block;
  min-inline-size: 4ch;
  max-inline-size: 100%;
  margin: 0;
  padding-block: 0;
  padding-inline: calc(0.3em + var(--spot-room-start)) calc(0.3em + var(--spot-room-end));
  color: var(--color-text);
  font: inherit;
  line-height: inherit;
  cursor: text;
}

/* The ring is drawn outside the box and leaves a heavier edge inside it as it was. */
.fill-spot__control:focus-visible {
  box-shadow: var(--spot-inset), var(--focus-ring);
}

.fill-spot--selected .fill-spot__control:focus-visible {
  outline-offset: 2px;
}

/*
 * A text spot is as wide as what it shows, from a small minimum, so a spot added after a label stays on
 * the label's line where the line has room. Where the browser can size a field to its content it does so
 * as the person types, and wraps at the line's width; elsewhere the width is worked out from the number of
 * characters, and a longer value scrolls inside it.
 */
.fill-spot__control--text {
  inline-size: calc(var(--spot-chars, 16) * 1ch + 0.6em + var(--spot-room-start) + var(--spot-room-end) + 2px);
  resize: none;
  overflow: auto;
  field-sizing: content;
}

@supports (field-sizing: content) {
  .fill-spot__control--text {
    inline-size: auto;
    overflow: hidden;
  }
}

/* The bracketed placeholder is how an empty spot reads, so it keeps full text contrast. */
.fill-spot__control::placeholder {
  color: var(--color-text-muted);
  opacity: 1;
}

/* A gap in a date spot reads like the placeholder of a text spot. */
.fill-spot--empty .fill-spot__date-text {
  color: var(--color-text-muted);
}

/* A question about a value that is already there leaves it in quieter ink, as a value still being settled. */
.fill-spot--question .fill-spot__control,
.fill-spot--question .fill-spot__date-text {
  color: var(--color-text-muted);
}

/* On the red wash the words, even a placeholder, take the full ink: red already says it needs a look. */
.fill-spot--attention .fill-spot__control,
.fill-spot--attention .fill-spot__control::placeholder,
.fill-spot--attention .fill-spot__date-text {
  color: var(--color-text);
}

.fill-spot--locked .fill-spot__control,
.fill-spot--preview .fill-spot__control {
  cursor: default;
}

/*
 * A date spot reads as text, like the others. The date control stays where it is, filling the box, but is
 * not drawn until it has focus; until then the date as the export prints it, or the bracketed placeholder,
 * is drawn over it and sizes the spot. Focus swaps them, so the same control is always the one that is
 * focused, with the browser's own entry, picker and screen-reader behaviour.
 */
.fill-spot__control--date {
  position: absolute;
  inset: 0 auto auto 0;
  inline-size: 100%;
  block-size: calc(100% - var(--spot-under));
  opacity: 0;
}

.fill-spot__field:focus-within .fill-spot__control--date {
  opacity: 1;
}

.fill-spot__date-text {
  display: block;
  min-inline-size: 4ch;
  padding-inline: calc(0.3em + var(--spot-room-start)) calc(0.3em + var(--spot-room-end));
  color: var(--color-text);
  line-height: inherit;
  overflow-wrap: anywhere;
  pointer-events: none;
  user-select: none;
}

.fill-spot__date-text--one-line {
  white-space: nowrap;
}

.fill-spot__field:focus-within .fill-spot__date-text {
  visibility: hidden;
}

/* Room for the browser's own date entry (ten characters) and its picker button, beside the pictures, while it is shown. */
.fill-spot--date .fill-spot__field:focus-within {
  min-inline-size: calc(10ch + 2.4em + var(--spot-room-start) + var(--spot-room-end));
}

/* A small picture at the start of a spot, level with its first line, above the control so its fill never covers it. */
.fill-spot__mark {
  position: absolute;
  z-index: 1;
  inset-block-start: calc(1px + (1lh - 12px) / 2);
  inset-inline-start: calc(0.3em + 1px);
  display: inline-flex;
  color: var(--color-honey-ink);
  pointer-events: none;
}

.fill-spot--attention .fill-spot__mark {
  color: var(--color-error);
}

/* A proposal is still a proposal when the value it would replace needs attention. */
.fill-spot--preview .fill-spot__mark {
  color: var(--color-honey-ink);
}

.fill-spot__lock {
  position: absolute;
  z-index: 1;
  inset-block-start: calc(1px + (1lh - 12px) / 2);
  inset-inline-end: calc(0.3em + 1px);
  color: var(--color-text-muted);
  pointer-events: none;
}

/* Beside the spot, a little above the middle of its box, as a footnote mark is. */
.fill-spot__required {
  position: relative;
  inset-block-start: 0.35rem;
  margin-inline-start: 0.375rem;
  font-family: 'Rubik', system-ui, sans-serif;
  font-size: 0.75rem;
  font-weight: 500;
  line-height: 1;
  vertical-align: top;
  color: var(--color-error);
  pointer-events: none;
  user-select: none;
}

/* How many of Brownie's questions are still open about this spot, in the honey of the edge that says there are some. */
.fill-spot__count {
  display: inline-grid;
  place-items: center;
  box-sizing: border-box;
  min-inline-size: 1.125rem;
  block-size: 1.125rem;
  margin-inline-start: 0.375rem;
  padding-inline: 0.25rem;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: var(--color-honey);
  color: var(--color-text);
  font-family: 'Rubik', system-ui, sans-serif;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1;
  vertical-align: top;
  user-select: none;
}
</style>
