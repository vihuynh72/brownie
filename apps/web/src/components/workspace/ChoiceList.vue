<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

/**
 * Numbered answers Brownie offers in the chat, with a last line for typing
 * something else. Each choice is an ordinary button, so it works with a
 * keyboard, a switch or a screen reader without a custom widget; the
 * numbers are only a visual aid, since an ordered list already tells
 * assistive technology each item's position. With no choices to offer, the list is just the line
 * for typing, without a number.
 */
const props = withDefaults(
  defineProps<{
    /** The words of each choice, in order. */
    options: readonly string[]
    /** What the group is about, for assistive technology ("Your answer for Meeting date"). */
    name: string
    /** Makes the ids of this list's own controls unique on the page. */
    idPrefix: string
    /** Whether the last line takes free text. */
    allowOther?: boolean
    otherLabel?: string
    otherPlaceholder?: string
    /** While Brownie is acting on an earlier choice, a second press is ignored rather than sent. */
    busy?: boolean
    /**
     * Whether sending empties the line at once. A list that goes away when its answer is saved keeps
     * the words instead, so a failed save does not lose what the person typed.
     */
    clearOnSend?: boolean
    /** Drawn without a card of its own, for a card that already holds it (a question's). */
    flush?: boolean
  }>(),
  { allowOther: true, otherLabel: 'Other', otherPlaceholder: 'Type what you would prefer…', busy: false, clearOnSend: true, flush: false },
)

const emit = defineEmits<{ choose: [index: number]; other: [text: string] }>()

const otherText = ref('')
/** "Send other answer", or "Send your answer" when the line's own label already says what it is. */
const sendName = computed(() => {
  const label = props.otherLabel.toLowerCase()
  return /answer$/.test(label) ? `Send ${label}` : `Send ${label} answer`
})

function choose(index: number): void {
  if (props.busy) return
  emit('choose', index)
}

function submitOther(): void {
  const text = otherText.value.trim()
  if (text === '' || props.busy) return
  emit('other', text)
  if (props.clearOnSend) otherText.value = ''
}
</script>

<template>
  <div class="choices" :class="flush ? 'choices--flush' : 'msg-card'" role="group" :aria-label="name">
    <ol class="choices__list" role="list">
      <li v-for="(option, index) in options" :key="index" class="choices__item">
        <!-- Not disabled while busy: that would drop the focus the button holds. A second press is ignored instead. -->
        <button type="button" class="choices__option" :aria-disabled="busy" @click="choose(index)">
          <span class="choices__number" aria-hidden="true">{{ index + 1 }}</span>
          <span class="choices__text">{{ option }}</span>
        </button>
      </li>
      <li v-if="allowOther" class="choices__item">
        <form class="choices__other" @submit.prevent="submitOther">
          <span v-if="options.length > 0" class="choices__number" aria-hidden="true">{{ options.length + 1 }}</span>
          <label class="choices__other-label" :for="`${idPrefix}-other`">{{ otherLabel }}:</label>
          <input
            :id="`${idPrefix}-other`"
            v-model="otherText"
            class="choices__other-input"
            type="text"
            autocomplete="off"
            :placeholder="otherPlaceholder"
          />
          <!-- Enter sends; the button only appears once there is something to send, so an empty line stays quiet. -->
          <button
            type="submit"
            class="icon-button choices__send"
            :class="{ 'choices__send--idle': otherText.trim() === '' }"
            :aria-disabled="busy || otherText.trim() === ''"
          >
            <AppIcon name="arrow-up" :size="18" />
            <span class="visually-hidden">{{ sendName }}</span>
          </button>
        </form>
      </li>
    </ol>
  </div>
</template>

<style scoped>
/*
 * A card of rows: 42px high, the number in a tile, a paper wash under the row the pointer or the keyboard is on.
 * Rows sit 2px apart; a card that wants them further apart (a question's) sets --choices-gap around the list.
 */
.choices {
  padding: 0.4375rem 0.5rem;
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

/* Inside a card of its own: the card's heading has spaced it from the top, and no edge is drawn twice. */
.choices--flush {
  padding-block: 0 0.5rem;
}

.choices__list {
  display: grid;
  gap: var(--choices-gap, 0.125rem);
  margin: 0;
  padding: 0;
  list-style: none;
}

.choices__option,
.choices__other {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  inline-size: 100%;
  min-block-size: 2.625rem;
  padding: 0.25rem 0.75rem;
  border-radius: 0.5rem;
}

.choices__option {
  border: 0;
  background: transparent;
  text-align: start;
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.choices__option:hover,
.choices__option:focus-visible,
.choices__other:focus-within {
  background: var(--color-paper-soft);
}

.choices__option[aria-disabled='true'] {
  cursor: progress;
}

.choices__number {
  flex: none;
  display: inline-grid;
  place-items: center;
  inline-size: 1.375rem;
  block-size: 1.375rem;
  border-radius: 0.375rem;
  background: var(--color-paper-deep);
  color: var(--color-text);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1;
}

.choices__text {
  overflow-wrap: anywhere;
}

.choices__other-label {
  flex: none;
  color: var(--color-text);
}

.choices__other-input {
  flex: 1;
  min-inline-size: 0;
  padding: 0.25rem 0;
  border: 0;
  background: transparent;
  font-size: inherit;
}

.choices__other-input::placeholder {
  color: var(--color-text-faint);
  opacity: 1;
}

.choices__other-input:focus-visible {
  box-shadow: none;
  outline: none;
}

/* The line itself shows focus when its field has it, since the field has no border of its own. */
.choices__other:focus-within {
  box-shadow: var(--focus-ring);
}

/* Forced colours drop box shadows, so the line is outlined in the system's own focus colour instead. */
@media (forced-colors: active) {
  .choices__other:focus-within {
    outline: 2px solid Highlight;
    outline-offset: 1px;
  }
}

/* A round, ready-to-send button like the message box's: cocoa with a white arrow. */
.choices__send {
  flex: none;
  border-radius: 50%;
  background: var(--color-cocoa);
  color: var(--color-surface);
}

.choices__send:hover:not([aria-disabled='true']) {
  background: var(--color-cocoa-strong);
  color: var(--color-surface);
}

.choices__send[aria-disabled='true'] {
  opacity: 0.5;
}

/* With nothing typed there is nothing to send: the button keeps its room, so the line does not shift, but is not shown or reached. */
.choices__send--idle {
  visibility: hidden;
}
</style>
