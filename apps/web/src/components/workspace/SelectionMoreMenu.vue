<script setup lang="ts">
/**
 * The selection bar's overflow: "More" gathers the quieter controls of one chosen
 * place -- "Ask Brownie", Lock/Unlock, Rename…, Text size…, Remove…, a row's own
 * actions, and "Text style and rules" -- into one menu, so the bar keeps to the
 * things a person does all the time.
 *
 * The same menu the other menus are: role="menu" with its items reached by the
 * arrow keys; Enter, Space or ArrowDown on the button opens it at the first item;
 * Escape closes it and hands focus back to the button; Tab closes it and carries
 * on; a press anywhere else closes it.
 */
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'

import AppIcon from '@/components/AppIcon.vue'

const menuId = useId()
const open = ref(false)
const buttonRef = ref<HTMLButtonElement | null>(null)
const menuRef = ref<HTMLElement | null>(null)

function items(): HTMLElement[] {
  return menuRef.value ? Array.from(menuRef.value.querySelectorAll<HTMLElement>('[role="menuitem"]')) : []
}

async function openMenu(first: number): Promise<void> {
  open.value = true
  await nextTick()
  items()[first]?.focus({ preventScroll: true })
}

async function closeMenu(backToButton: boolean): Promise<void> {
  open.value = false
  if (backToButton) {
    await nextTick()
    buttonRef.value?.focus({ preventScroll: true })
  }
}

function onButtonKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    void openMenu(0)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    const count = items().length
    void openMenu(count - 1)
  }
}

function onButtonClick(): void {
  if (open.value) {
    void closeMenu(false)
  } else {
    void openMenu(0)
  }
}

function onKeydown(event: KeyboardEvent): void {
  const all = items()
  const current = all.indexOf(event.target as HTMLElement)
  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      all[(current + 1) % all.length]?.focus()
      return
    case 'ArrowUp':
      event.preventDefault()
      all[(current - 1 + all.length) % all.length]?.focus()
      return
    case 'Home':
      event.preventDefault()
      all[0]?.focus()
      return
    case 'End':
      event.preventDefault()
      all[all.length - 1]?.focus()
      return
    case 'Escape':
      // Stopped here: the page's own Escape handling may close the bar, and one press closes one thing.
      event.preventDefault()
      event.stopPropagation()
      void closeMenu(true)
      return
    case 'Tab':
      void closeMenu(false)
      return
  }
}

/** An item's own click runs its action; the menu closes so the bar is what stays. */
function onMenuClick(): void {
  void closeMenu(false)
}

function onDocumentPointerdown(event: PointerEvent): void {
  if (menuRef.value && event.target instanceof Node && !menuRef.value.contains(event.target) && !buttonRef.value?.contains(event.target)) {
    void closeMenu(false)
  }
}

onMounted(() => document.addEventListener('pointerdown', onDocumentPointerdown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocumentPointerdown))

defineExpose({ open })
</script>

<template>
  <span class="selection-bar__more">
    <button
      ref="buttonRef"
      type="button"
      class="button button--small button--quiet"
      :aria-haspopup="'menu'"
      :aria-expanded="open"
      :aria-controls="menuId"
      @click="onButtonClick"
      @keydown="onButtonKeydown"
    >
      <AppIcon name="more" :size="16" />
      More
    </button>
    <span v-if="open" :id="menuId" ref="menuRef" role="menu" class="selection-more-menu" @keydown="onKeydown" @click="onMenuClick">
      <slot :return-to="buttonRef" />
    </span>
  </span>
</template>

<style scoped>
.selection-bar__more {
  position: relative;
  display: inline-block;
}

.selection-more-menu {
  position: absolute;
  inset-block-start: 100%;
  inset-inline-end: 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  min-width: 12rem;
  padding: var(--space-2);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-2);
  box-shadow: var(--shadow-2);
}
</style>
