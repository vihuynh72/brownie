<script setup lang="ts">
/**
 * A small "Fill in here" button that appears next to words selected on the page. Pressing it keeps the
 * selection (the press does not take it away), so the page can turn exactly those words into the place
 * for a new fill spot. Escape on it lets the words go, and the button with them.
 */
defineProps<{
  /** Where the button sits, in pixels from the top left of the page's body. */
  top: number
  left: number
}>()

const emit = defineEmits<{ 'fill-here': [button: HTMLElement]; dismiss: [] }>()

function press(event: MouseEvent): void {
  emit('fill-here', event.currentTarget as HTMLElement)
}
</script>

<template>
  <div class="fill-here-bar" :style="{ top: `${top}px`, left: `${left}px` }">
    <button
      type="button"
      class="button button--primary fill-here-bar__button"
      @mousedown.prevent
      @click="press"
      @keydown.esc.prevent="emit('dismiss')"
    >Fill in here</button>
  </div>
</template>

<style scoped>
/* Always one line: near the page's edge, a box placed by its left would otherwise wrap its words to fit. */
.fill-here-bar {
  position: absolute;
  z-index: 5;
  inline-size: max-content;
  white-space: nowrap;
}

.fill-here-bar__button {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  border-radius: var(--radius-pill);
  box-shadow: 0 0.25rem 0.75rem rgb(42 41 36 / 0.2);
  font-size: var(--font-size-sm);
}

@media (pointer: coarse) {
  .fill-here-bar__button {
    min-block-size: 2.75rem;
  }
}
</style>
