<script setup lang="ts">
import { computed, nextTick, ref, useId } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

/**
 * The frame the fill spot dialogs share: a modal dialog with a small caption over a large heading, a line of bars
 * that shows how far along its steps it is, and a close button. It keeps focus inside while it is open and gives
 * it back to whatever opened it when it closes (unless the change it made moves focus somewhere better, such as a
 * new fill spot). While a change is being made it cannot be closed, so the person always hears how the change
 * ended: the frame also says what is happening and why a change was refused, in one place for every dialog.
 *
 * The body is the default slot and the buttons are the `foot` slot. A button there that submits a form in the body
 * names that form with its `form` attribute.
 */
const props = withDefaults(
  defineProps<{
    /** The large heading: what this step asks, or what the dialog is about. */
    title: string
    /** The small line above it: what the dialog does. With more than one step it also says which step this is. */
    caption: string
    /** The step the dialog is at, counting from 1. */
    step?: number
    /** How many steps there are, which is how many bars the line under the heading has. */
    steps?: number
    /** Taking something away for good: the line under the heading is red instead of cocoa. */
    danger?: boolean
    /** A change is under way: Escape and the close button wait for it. */
    busy?: boolean
    /** What is happening while a change is made; spoken as it appears. */
    status?: string
    /** Why the last change was refused, spoken as it appears. */
    error?: string | null
    /** The element that says what the dialog is about, when it is more than its title. */
    describedBy?: string
  }>(),
  { step: 1, steps: 1, danger: false, busy: false, status: '', error: null, describedBy: undefined },
)

const emit = defineEmits<{ closed: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)
const captionId = useId()
const headingId = useId()
const isOpen = ref(false)
/** Whether the browser drew the dialog as a real modal; without one it is laid over the page by hand. */
const nativeModal = ref(false)
let opener: HTMLElement | null = null

/** The caption joins the heading in the dialog's name when it says which step this is, which the heading alone does not. */
const labelledBy = computed(() => (props.steps > 1 ? `${captionId} ${headingId}` : headingId))

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
    class="dialog spot-dialog"
    :class="{ 'spot-dialog--fallback': !nativeModal }"
    :aria-labelledby="labelledBy"
    :aria-describedby="describedBy"
    @cancel.prevent="requestClose"
    @close="onNativeClose"
    @keydown="onKeydown"
  >
    <template v-if="isOpen">
      <header class="dialog__head">
        <p :id="captionId" class="dialog__subtitle spot-dialog__caption">
          {{ caption }}<template v-if="steps > 1"><span aria-hidden="true"> &bull; </span><span class="visually-hidden">, </span>Step {{ step }} of {{ steps }}</template>
        </p>
        <h2 :id="headingId" class="dialog__title spot-dialog__title" tabindex="-1">{{ title }}</h2>
        <button type="button" class="icon-button dialog__close" :aria-disabled="busy ? 'true' : undefined" @click="requestClose">
          <AppIcon name="close" :size="22" />
          <span class="visually-hidden">Close</span>
        </button>
      </header>

      <!-- The words in the caption already say the step; the bars only show it, so they are not read out. -->
      <div class="spot-dialog__progress" :class="{ 'spot-dialog__progress--danger': danger }" aria-hidden="true">
        <span v-for="bar in steps" :key="bar" class="spot-dialog__bar" :class="{ 'spot-dialog__bar--reached': bar <= step }"></span>
      </div>

      <div class="dialog__body spot-dialog__body">
        <p v-if="error" class="spot-dialog__error" role="alert">
          <AppIcon name="alert-circle" :size="20" />
          <span>{{ error }}</span>
        </p>
        <slot />
        <!-- Always there, so the words are spoken when they appear in it. -->
        <p class="spot-dialog__status" :class="{ 'spot-dialog__status--shown': status !== '' }" role="status">
          <span v-if="status !== ''" class="activity-indicator" aria-hidden="true"></span>{{ status }}
        </p>
      </div>

      <div class="dialog__foot">
        <slot name="foot" />
      </div>
    </template>
  </dialog>
</template>

<style scoped>
/* The browser centres a modal exactly; these three (Place, Rename, Remove) sit a little above that, to match the rest of the window. */
.spot-dialog[open] {
  translate: 0 -0.625rem;
}

/* A browser without modal dialogs gets the same box laid over the page by hand. */
.spot-dialog--fallback[open] {
  position: fixed;
  inset: 0;
  margin: auto;
  z-index: 50;
  height: fit-content;
}

/* The heading takes focus only so a screen reader starts reading at the top; it is not a control to point at. */
.spot-dialog__title:focus-visible {
  box-shadow: none;
  outline: none;
}

/* A long name in a title (a fill spot's own) breaks anywhere rather than running under the Close button. */
.spot-dialog__title {
  overflow-wrap: anywhere;
}

/* The line over the heading says what the dialog is and where it has got to, so it is a label, not reading text. */
.spot-dialog__caption {
  font-weight: var(--font-weight-medium);
}

/* One bar for each step, side by side across the dialog's content width; the ones reached are filled. */
.spot-dialog__progress {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: 0.375rem;
  flex: none;
  margin: 0.9375rem 1.5rem 0;
}

.spot-dialog__bar {
  block-size: 0.25rem;
  border-radius: var(--radius-pill);
  background: var(--color-track);
  transition: background-color var(--motion-base) var(--motion-ease);
}

.spot-dialog__bar--reached {
  background: var(--color-cocoa);
}

.spot-dialog__progress--danger .spot-dialog__bar--reached {
  background: var(--color-error);
}

/* A display that forces its own colours drops backgrounds, which would erase the bars; hand it system colours. */
@media (forced-colors: active) {
  .spot-dialog__bar {
    forced-color-adjust: none;
    background: GrayText;
  }

  .spot-dialog__bar--reached {
    background: Highlight;
  }
}

/* The space the bars and the buttons leave: 20px under the bars, and 16px over the 4px the foot adds above its buttons. */
.spot-dialog__body {
  padding-block: 1.25rem 1rem;
  overscroll-behavior: contain;
}

/* The icon beside a button's label is 18px, a hair apart from it, in the foot. */
.dialog__foot :deep(.button) {
  --button-icon: 1.125rem;
  --button-gap: 0.5rem;
}

.spot-dialog__error {
  display: flex;
  align-items: flex-start;
  gap: 0.5625rem;
  margin: 0 0 1.25rem;
  padding: 0.75rem 0.875rem;
  border-radius: var(--radius-md);
  background: var(--color-error-wash);
  color: var(--color-error);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.spot-dialog__error > svg {
  flex: none;
}

.spot-dialog__status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

/* Empty, it takes no room; with words in it, it sits a line below what it is about. */
.spot-dialog__status--shown {
  margin-block-start: 1rem;
}
</style>
