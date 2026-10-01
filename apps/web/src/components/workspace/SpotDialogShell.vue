<script setup lang="ts">
import { nextTick, ref, useId } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

/**
 * The frame the fill spot dialogs share: a modal dialog with a heading and a close button, which keeps
 * focus inside while it is open and gives it back to whatever opened it when it closes (unless the
 * change it made moves focus somewhere better, such as a new fill spot). While a change is being made
 * it cannot be closed, so the person always hears how the change ended.
 */
const props = defineProps<{
  title: string
  /** A change is under way: Escape and the close button wait for it. */
  busy?: boolean
  /** The element that says what the dialog is about, when it is more than its title. */
  describedBy?: string
}>()

const emit = defineEmits<{ closed: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)
const headingId = useId()
const isOpen = ref(false)
/** Whether the browser drew the dialog as a real modal; without one it is laid over the page by hand. */
const nativeModal = ref(false)
let opener: HTMLElement | null = null

/** Opens the dialog; `firstFocus` names the control that takes focus, else the dialog's heading does. */
function open(firstFocus?: () => HTMLElement | null | undefined, returnTo?: HTMLElement | null): void {
  if (isOpen.value) return
  const active = document.activeElement
  opener = returnTo ?? (active instanceof HTMLElement && active !== document.body ? active : null)
  isOpen.value = true
  void nextTick(() => {
    const element = dialog.value
    if (element === null) return
    if (typeof element.showModal === 'function') {
      nativeModal.value = true
      if (!element.open) element.showModal()
    } else {
      nativeModal.value = false
      element.setAttribute('open', '')
    }
    const target = firstFocus?.() ?? element.querySelector<HTMLElement>('.spot-dialog__title')
    target?.focus()
  })
}

/** Closes the dialog, giving focus back to what opened it unless `returnFocus` is false. */
function close(returnFocus = true): void {
  if (!isOpen.value) return
  isOpen.value = false
  const element = dialog.value
  if (element !== null) {
    if (nativeModal.value && typeof element.close === 'function' && element.open) element.close()
    element.removeAttribute('open')
  }
  const target = opener
  opener = null
  if (returnFocus && target !== null) {
    void nextTick(() => {
      if (target.isConnected) target.focus()
    })
  }
  emit('closed')
}

function requestClose(): void {
  if (!props.busy) close()
}

/** The browser closed the dialog by itself (it lets a page refuse Escape only once in a row). */
function onNativeClose(): void {
  if (isOpen.value && dialog.value?.open !== true) close()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || nativeModal.value) return
  event.preventDefault()
  requestClose()
}

defineExpose({ open, close, isOpen })
</script>

<template>
  <dialog
    ref="dialog"
    class="spot-dialog"
    :class="{ 'spot-dialog--fallback': !nativeModal }"
    :aria-labelledby="headingId"
    :aria-describedby="describedBy"
    @cancel.prevent="requestClose"
    @close="onNativeClose"
    @keydown="onKeydown"
  >
    <template v-if="isOpen">
      <header class="spot-dialog__header">
        <h2 :id="headingId" class="spot-dialog__title" tabindex="-1">{{ title }}</h2>
        <button type="button" class="icon-button" :aria-disabled="busy ? 'true' : undefined" @click="requestClose">
          <AppIcon name="close" />
          <span class="visually-hidden">Close</span>
        </button>
      </header>
      <div class="spot-dialog__body">
        <slot />
      </div>
    </template>
  </dialog>
</template>

<style scoped>
.spot-dialog {
  width: min(34rem, 100% - 2rem);
  max-width: none;
  max-height: min(90dvh, 44rem);
  padding: 0;
  border: 1px solid var(--color-hairline);
  border-radius: 1rem;
  background: var(--color-surface);
  color: var(--color-text);
  box-shadow: 0 1rem 3rem rgb(42 41 36 / 0.2);
}

/* Only while open: a display value on a closed dialog would override the browser's own display: none. */
.spot-dialog[open] {
  display: flex;
  flex-direction: column;
}

.spot-dialog::backdrop {
  background: rgb(42 41 36 / 0.45);
  background: color-mix(in srgb, var(--color-text, #2a2924) 45%, transparent);
}

.spot-dialog--fallback[open] {
  position: fixed;
  inset: 0;
  margin: auto;
  z-index: 50;
  height: fit-content;
}

.spot-dialog__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-bottom: 1px solid var(--color-hairline);
}

.spot-dialog__title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
}

.spot-dialog__title:focus {
  outline: none;
}

.spot-dialog__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--space-4) var(--space-5) var(--space-5);
}
</style>
