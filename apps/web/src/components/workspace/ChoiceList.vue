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
  }>(),
  { allowOther: true, otherLabel: 'Other', otherPlaceholder: 'Type what you would prefer…', busy: false, clearOnSend: true },
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
  <div class="choices" role="group" :aria-label="name">
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
          <button type="submit" class="icon-button choices__send" :aria-disabled="busy || otherText.trim() === ''">
            <AppIcon name="send" :size="18" />
            <span class="visually-hidden">{{ sendName }}</span>
          </button>
        </form>
      </li>
    </ol>
  </div>
</template>

<style scoped>
/* A card of rows, as the design draws Brownie's numbered answers: a thin edge, a tint on the row under the pointer. */
.choices {
  border: 1px solid var(--color-hairline);
  border-radius: 0.75rem;
  background: var(--color-surface);
  padding: var(--space-1);
  font-size: var(--font-size-sm);
}

.choices__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.choices__option,
.choices__other {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  inline-size: 100%;
  min-block-size: 2.5rem;
  padding: var(--space-2) var(--space-3);
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
.choices__option:focus-visible {
  background: var(--color-paper);
}

.choices__option[aria-disabled='true'] {
  cursor: progress;
}

.choices__number {
  flex: none;
  display: inline-grid;
  place-items: center;
  inline-size: 1.25rem;
  block-size: 1.25rem;
  border-radius: 0.3125rem;
  background: var(--color-paper-deep);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: 500;
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
  border: 0;
  background: transparent;
  padding: var(--space-1) 0;
}

.choices__other-input::placeholder {
  color: var(--color-text-muted);
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

.choices__send[aria-disabled='true'] {
  opacity: 0.5;
}
</style>
