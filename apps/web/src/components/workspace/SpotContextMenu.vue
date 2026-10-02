<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'

/**
 * The menu a right-click on the page's text opens, in place of the browser's own: "Fill in here" and
 * "Copy". Shift with the right-click still opens the browser's menu, and the menu says so. It works as
 * a menu does from the keyboard: the arrow keys move between its items, Enter chooses, and Escape or
 * Tab closes it and gives focus back.
 *
 * It stays where the page puts it, inside the page's landmarks, and rises into the top layer, so nothing
 * around it can clip it or shift where it lands; without the Popover API it moves to the end of the page.
 */
const props = defineProps<{
  /** Where it opens, in pixels from the top left of the window. */
  x: number
  y: number
  /** Words are selected, so there is something to copy. */
  canCopy: boolean
}>()

const emit = defineEmits<{ 'fill-here': []; copy: []; close: [] }>()

const topLayer = typeof HTMLElement !== 'undefined' && 'popover' in HTMLElement.prototype

const root = ref<HTMLElement | null>(null)
const menu = ref<HTMLElement | null>(null)
const hintId = useId()
const left = ref(props.x)
const top = ref(props.y)

function items(): HTMLElement[] {
  return [...(menu.value?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])]
}

function onKeydown(event: KeyboardEvent): void {
  const all = items()
  const index = all.indexOf(document.activeElement as HTMLElement)
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const step = event.key === 'ArrowDown' ? 1 : -1
    all[(index + step + all.length) % all.length]?.focus()
  } else if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault()
    ;(event.key === 'Home' ? all[0] : all[all.length - 1])?.focus()
  } else if (event.key === 'Escape' || event.key === 'Tab') {
    event.preventDefault()
    emit('close')
  }
}

/** A press anywhere outside the menu closes it, as a menu does. */
function onPointerDown(event: Event): void {
  if (root.value && event.target instanceof Node && !root.value.contains(event.target)) emit('close')
}

onMounted(async () => {
  if (topLayer) root.value?.showPopover()
  await nextTick()
  // Kept inside the window: a menu opened near its edge opens towards the middle instead.
  const box = root.value?.getBoundingClientRect()
  if (box && box.width > 0) {
    left.value = Math.max(8, Math.min(props.x, window.innerWidth - box.width - 8))
    top.value = Math.max(8, Math.min(props.y, window.innerHeight - box.height - 8))
  }
  items()[0]?.focus()
  window.document.addEventListener('pointerdown', onPointerDown, true)
})

onBeforeUnmount(() => window.document.removeEventListener('pointerdown', onPointerDown, true))
</script>

<template>
  <Teleport to="body" :disabled="topLayer">
    <div ref="root" class="spot-menu" :popover="topLayer ? 'manual' : undefined" :style="{ left: `${left}px`, top: `${top}px` }">
      <ul ref="menu" class="spot-menu__items" role="menu" aria-label="Page text" :aria-describedby="hintId" @keydown="onKeydown">
        <li role="none">
          <button type="button" role="menuitem" class="spot-menu__item" tabindex="-1" @click="emit('fill-here')">Fill in here</button>
        </li>
        <li v-if="canCopy" role="none">
          <button type="button" role="menuitem" class="spot-menu__item" tabindex="-1" @click="emit('copy')">Copy</button>
        </li>
      </ul>
      <p :id="hintId" class="spot-menu__hint">Shift+right-click shows your browser's menu</p>
    </div>
  </Teleport>
</template>

<style scoped>
/*
 * Fixed to the window either way: in the top layer, and at the end of the page without it. The rules a
 * browser gives a popover centre it and give it a border and padding; inset and margin are reset so the
 * coordinates worked out above are where it lands.
 */
.spot-menu {
  position: fixed;
  inset: auto;
  margin: 0;
  z-index: 60;
  min-inline-size: 12rem;
  padding: var(--space-1);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  color: var(--color-text);
  box-shadow: 0 0.5rem 1.5rem rgb(42 41 36 / 0.2);
  overflow: visible;
}

.spot-menu__items {
  list-style: none;
  margin: 0;
  padding: 0;
}

.spot-menu__item {
  display: block;
  inline-size: 100%;
  min-block-size: 2rem;
  padding: 0 var(--space-3);
  border: 0;
  border-radius: calc(var(--radius) - 2px);
  background: transparent;
  color: var(--color-text);
  font: inherit;
  text-align: start;
  cursor: pointer;
}

.spot-menu__item:hover,
.spot-menu__item:focus-visible {
  background: var(--color-cocoa-wash);
}

@media (pointer: coarse) {
  .spot-menu__item {
    min-block-size: 2.75rem;
  }
}

.spot-menu__hint {
  margin: var(--space-1) 0 0;
  padding: var(--space-1) var(--space-3) 0;
  border-block-start: 1px solid var(--color-hairline);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}
</style>
