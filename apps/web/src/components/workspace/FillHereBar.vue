<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'

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
      class="button button--primary button--small fill-here-bar__button"
      @mousedown.prevent
      @click="press"
      @keydown.esc.prevent="emit('dismiss')"
    ><AppIcon name="fill-here" :size="16" /><span>Fill in here</span></button>
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

/* It floats over the page's own text, so it casts the shadow a floating thing does. */
.fill-here-bar__button {
  box-shadow: var(--shadow-popover);
}

@media (pointer: coarse) {
  .fill-here-bar__button {
    min-block-size: 2.75rem;
  }
}
</style>
