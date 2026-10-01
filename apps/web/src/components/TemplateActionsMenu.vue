<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'

/**
 * What can be done to one template in the sidebar, as a menu. The row's
 * "More" button opens it underneath itself; a right-click, or Shift+F10 or
 * the context-menu key on a focused row, opens the same menu, at the
 * pointer when there is one.
 *
 * It is a real menu (role="menu" with its items reached by the arrow
 * keys), because that is what a context menu is and what a screen reader
 * user expects after pressing a button announced as having one. Focus
 * goes to the first item as it opens. Escape closes it and hands focus
 * back to whoever opened it; Tab closes it too and carries on from there,
 * so the menu is never a place a keyboard gets stuck. Pressing anywhere
 * else, scrolling or resizing the window closes it, since it would
 * otherwise be left floating beside a row that has moved.
 *
 * It floats above everything in the top layer where the browser has the
 * Popover API. Without it (an older browser, or the test environment) it
 * is moved to the end of the page instead, so neither the sidebar's
 * clipping nor the drawer's slide can cut it off or shift it.
 */
const props = withDefaults(
  defineProps<{
    /** The menu's own id: the button that opens it points at it with aria-controls. */
    id: string
    /** The id of the button that names the menu ("More for Club minutes"). */
    labelledBy: string
    /** The row's "More" button: the menu opens underneath it unless it was opened at a point. */
    anchor: HTMLElement
    /** Where a right-click happened, in window pixels; the menu opens there instead. */
    point?: { x: number; y: number } | null
    /** Which item takes focus as the menu opens: ArrowUp on the button asks for the last. */
    startAt?: 'first' | 'last'
  }>(),
  { point: null, startAt: 'first' },
)

const emit = defineEmits<{
  /** "Move to Trash Bin" was chosen. */
  trash: []
  /** The menu should go; true when focus should go back to whatever opened it. */
  close: [returnFocus: boolean]
}>()

const topLayer = typeof HTMLElement !== 'undefined' && 'popover' in HTMLElement.prototype

const menuRef = ref<HTMLElement | null>(null)
const position = ref<{ top: number; left: number } | null>(null)

/** How far the menu keeps from the window's edges, and from the button it hangs under. */
const EDGE = 8
const GAP = 4

function items(): HTMLElement[] {
  return menuRef.value ? Array.from(menuRef.value.querySelectorAll<HTMLElement>('[role="menuitem"]')) : []
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, max))
}

/**
 * Under the button, left edges aligned, or at the pointer. Where that would
 * run off the window it flips to the other side of the button or the
 * pointer, and whatever still does not fit is pulled back inside.
 */
function place(): void {
  const menu = menuRef.value
  if (!menu) return
  const { width, height } = menu.getBoundingClientRect()
  const viewportWidth = document.documentElement.clientWidth
  const viewportHeight = document.documentElement.clientHeight
  let top: number
  let left: number
  if (props.point) {
    top = props.point.y
    left = props.point.x
    if (left + width > viewportWidth - EDGE) left -= width
    if (top + height > viewportHeight - EDGE) top -= height
  } else {
    const box = props.anchor.getBoundingClientRect()
    top = box.bottom + GAP
    left = box.left
    if (left + width > viewportWidth - EDGE) left = box.right - width
    if (top + height > viewportHeight - EDGE) top = box.top - GAP - height
  }
  position.value = {
    top: Math.round(clamp(top, EDGE, viewportHeight - EDGE - height)),
    left: Math.round(clamp(left, EDGE, viewportWidth - EDGE - width)),
  }
}

function onKeydown(event: KeyboardEvent): void {
  const all = items()
  if (all.length === 0) return
  const current = all.indexOf(document.activeElement as HTMLElement)
  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      all[(current + 1) % all.length]!.focus()
      break
    case 'ArrowUp':
      event.preventDefault()
      all[(current - 1 + all.length) % all.length]!.focus()
      break
    case 'Home':
      event.preventDefault()
      all[0]!.focus()
      break
    case 'End':
      event.preventDefault()
      all[all.length - 1]!.focus()
      break
    case 'Escape':
      // Stopped here: the drawer the menu may sit in also closes on Escape, and one press closes one thing.
      event.preventDefault()
      event.stopPropagation()
      emit('close', true)
      break
    case 'Tab':
      // Not prevented: focus goes back to the opener first, and the Tab then moves on from there.
      emit('close', true)
      break
  }
}

function focusIsInside(): boolean {
  return menuRef.value?.contains(document.activeElement) ?? false
}

/** A press on the opening button is left to that button, which closes the menu itself. */
function onPointerDown(event: PointerEvent): void {
  const target = event.target as Node | null
  if (target && (menuRef.value?.contains(target) || props.anchor.contains(target))) return
  emit('close', false)
}

function onScroll(event: Event): void {
  if (event.target instanceof Node && menuRef.value?.contains(event.target)) return
  emit('close', focusIsInside())
}

function onResize(): void {
  emit('close', focusIsInside())
}

onMounted(() => {
  const menu = menuRef.value
  if (!menu) return
  if (topLayer) menu.showPopover()
  place()
  const all = items()
  const first = props.startAt === 'last' ? all[all.length - 1] : all[0]
  first?.focus({ preventScroll: true })
  document.addEventListener('pointerdown', onPointerDown, true)
  document.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown, true)
  document.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <Teleport to="body" :disabled="topLayer">
    <!-- The browser's own context menu stays shut over this one, including the one a context-menu key raises on release. -->
    <div
      :id="props.id"
      ref="menuRef"
      class="template-menu"
      role="menu"
      :aria-labelledby="props.labelledBy"
      :popover="topLayer ? 'manual' : undefined"
      :style="position ? { top: `${position.top}px`, left: `${position.left}px` } : undefined"
      @keydown="onKeydown"
      @contextmenu.prevent
    >
      <button type="button" role="menuitem" tabindex="-1" class="template-menu__item" @click="emit('trash')">
        <AppIcon name="trash" :size="18" />
        <span>Move to Trash Bin</span>
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
/*
 * Fixed to the window either way: in the top layer, and at the end of the
 * page without it. The popover rules a browser applies by default centre
 * the element and give it a border and padding; inset and margin are reset
 * so the coordinates worked out above are where it actually lands.
 */
.template-menu {
  position: fixed;
  inset: auto;
  margin: 0;
  z-index: 50;
  min-inline-size: 12rem;
  max-inline-size: calc(100vw - 2 * var(--space-2));
  padding: var(--space-1);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  color: var(--color-text);
  box-shadow: 0 0.5rem 1.5rem rgb(42 41 36 / 0.16);
  overflow: visible;
}

.template-menu__item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  inline-size: 100%;
  min-block-size: var(--control-height);
  padding: 0 var(--space-3);
  border: 0;
  border-radius: calc(var(--radius) - 0.125rem);
  background: transparent;
  color: var(--color-text);
  text-align: start;
  white-space: nowrap;
  cursor: pointer;
}

/* Focus sits on the item itself, so the item wears the same wash whether a pointer or a key put it there. */
.template-menu__item:hover,
.template-menu__item:focus {
  background: var(--color-cocoa-wash);
}

@media (pointer: coarse) {
  .template-menu__item {
    min-block-size: 2.75rem;
  }
}
</style>
