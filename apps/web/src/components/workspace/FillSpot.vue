<script setup lang="ts">
import { computed } from 'vue'
import type { FieldStateResponse } from '@/api/client'
import {
  fieldStateWords,
  hasLineBreak,
  normalizeLineBreaks,
  stateFromAssist,
  stateNeedsAttention,
  type CssStyle,
} from '@/workspace/layout'

/**
 * One place in the document where a value goes, drawn in the line of text it belongs to and in the
 * template's own text style, so what the person types already looks like the exported page. Its look
 * says what state the value is in (empty, typed, from Brownie, needing attention, locked), and each of
 * those looks differs in shape as well as colour; the same words reach assistive technology through
 * the control's description.
 */
const props = withDefaults(
  defineProps<{
    fieldId: string
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
  }>(),
  { lockedHint: 'Locked: unlock it to edit.', attention: false, keysHintId: undefined },
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

/** The state in words; a locked spot says why it is locked instead of repeating "Locked". */
const description = computed(() => {
  const state = props.state && props.locked ? { ...props.state, lock: 'EDITABLE' as const } : props.state
  const sentences = fieldStateWords(state).map((word) => `${word}.`)
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

const empty = computed(() => props.value.trim() === '')
const invalid = computed(() => props.attention || props.state?.validation === 'BLOCKING')
const classes = computed(() => ({
  'fill-spot--empty': empty.value,
  'fill-spot--filled': !empty.value,
  'fill-spot--assist': !empty.value && stateFromAssist(props.state),
  'fill-spot--attention': props.attention || stateNeedsAttention(props.state),
  'fill-spot--locked': props.locked,
  'fill-spot--selected': props.selected,
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
  if (props.locked || !hasLineBreak(text)) return
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
    <textarea
      v-if="type === 'TEXT'"
      :id="inputId"
      class="fill-spot__control fill-spot__control--text"
      rows="1"
      :value="value"
      :placeholder="shownPlaceholder"
      :aria-label="accessibleName"
      :aria-describedby="describedBy"
      aria-keyshortcuts="Alt+Enter"
      :aria-invalid="invalid ? 'true' : undefined"
      :aria-readonly="locked ? 'true' : undefined"
      :readonly="locked"
      :style="styleCss"
      @input="onInput"
      @keydown="onKeydown"
      @paste="onPaste"
      @focus="emit('focus')"
      @click="emit('focus')"
    ></textarea>
    <input
      v-else
      :id="inputId"
      type="date"
      class="fill-spot__control fill-spot__control--date"
      :value="value"
      :aria-label="accessibleName"
      :aria-describedby="describedBy"
      aria-keyshortcuts="Alt+Enter"
      :aria-invalid="invalid ? 'true' : undefined"
      :aria-readonly="locked ? 'true' : undefined"
      :readonly="locked"
      :style="styleCss"
      @input="onInput"
      @keydown="onKeydown"
      @focus="emit('focus')"
      @click="emit('focus')"
    />
    <!--
      Seen, not heard: the control's own name already says "required", and the page says what the mark
      means. The word joiner keeps the mark on the control's line after a long value.
    -->
    <span v-if="required" class="fill-spot__required" aria-hidden="true">&#8288;*</span>
    <svg
      v-if="locked"
      class="fill-spot__lock"
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2.2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M6 11h12v9H6z" />
      <path d="M8.5 11V8a3.5 3.5 0 0 1 7 0v3" />
    </svg>
    <span v-if="description" :id="descriptionId" class="visually-hidden">{{ description }}</span>
  </span>
</template>

<style scoped>
.fill-spot {
  /* Stays in the line of text, like the value it stands for. */
  display: inline;
}


/*
 * Top-aligned rather than on the baseline: browsers put a text area's baseline at its bottom edge, so
 * baseline alignment would drop the label beside it below the value, and to the last line of a value
 * that wraps. Aligned to the top, the value's first line sits level with the text before it.
 */
.fill-spot__control {
  display: inline-block;
  box-sizing: border-box;
  vertical-align: top;
  min-inline-size: 6ch;
  max-inline-size: 100%;
  margin: 0;
  padding: 0 0.25em;
  border: 1px solid transparent;
  border-radius: 0.25rem;
  background: transparent;
  color: var(--color-text);
  font: inherit;
  line-height: inherit;
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    border-color var(--motion-fast) var(--motion-ease);
}

/*
 * A text spot grows with what is typed where the browser can size a field to its content, and wraps
 * at the line's width. Elsewhere it is a fixed box that scrolls, which still holds any value.
 */
.fill-spot__control--text {
  inline-size: 16ch;
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

.fill-spot--filled .fill-spot__control {
  border-block-end: 1px dotted var(--color-cocoa);
}

/* A value from Brownie is double-underlined as well as tinted, so it is told apart without seeing colour. */
.fill-spot--assist .fill-spot__control {
  background: var(--color-cocoa-wash);
  border-block-end: 3px double var(--color-cocoa);
}

/* A gap in the page: a faint tint with a light dashed edge, quiet enough that a page of gaps still reads as a page. */
.fill-spot--empty .fill-spot__control {
  border: 1px dashed var(--color-honey-line);
  background: var(--color-honey-faint);
}

.fill-spot--locked .fill-spot__control {
  border: 1px dotted var(--color-text-muted);
  cursor: default;
}

.fill-spot--attention .fill-spot__control {
  border-block-end: 2px solid var(--color-error);
}

/* A value that needs attention is also marked at its start, a shape of its own beside any underline. */
.fill-spot--attention .fill-spot__control {
  border-inline-start: 3px solid var(--color-error);
}

/* A locked or flagged value from Brownie keeps its double underline, in the colour of what it needs. */
.fill-spot--locked.fill-spot--assist .fill-spot__control {
  border-block-end: 3px double var(--color-text-muted);
}

.fill-spot--attention.fill-spot--assist .fill-spot__control {
  border-block-end: 3px double var(--color-error);
}

.fill-spot--selected .fill-spot__control {
  outline: 2px solid var(--color-cocoa);
  outline-offset: 1px;
}

/* Tucked against the spot's top corner, taking almost no room in the line. */
.fill-spot__required {
  position: relative;
  inset-block-start: -0.35em;
  margin-inline-start: -0.2em;
  font-size: 0.85em;
  font-weight: 700;
  line-height: 1;
  color: var(--color-error);
  pointer-events: none;
}

.fill-spot__lock {
  display: inline-block;
  margin-inline-start: 0.15em;
  vertical-align: -0.05em;
  color: var(--color-text-muted);
}
</style>
