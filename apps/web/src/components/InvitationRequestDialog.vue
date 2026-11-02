<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import SpotDialogShell from '@/components/workspace/SpotDialogShell.vue'
import { useSessionStore } from '@/stores/session'
import { describeCommonFailure } from '@/api/failures'

/**
 * The one ask a free-allowance workspace can make of whoever runs this
 * Brownie: a note, at most 500 characters, saying what Brownie would be
 * used for. The server keeps the request; the dialog only says whether
 * it was recorded.
 */
const emit = defineEmits<{ sent: [] }>()

const session = useSessionStore()
const shell = ref<InstanceType<typeof SpotDialogShell> | null>(null)
const ids = useId()
const formId = `${ids}-form`
const counterId = `${ids}-counter`
const note = ref('')
const busy = ref(false)
const error = ref<string | null>(null)

const NOTE_LIMIT = 500
const noteLength = computed(() => note.value.length)

function open(returnTo?: HTMLElement | null): void {
  note.value = ''
  error.value = null
  busy.value = false
  shell.value?.open(undefined, returnTo)
}

defineExpose({ open })

async function send(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  try {
    await session.requestInvitation(note.value.trim() === '' ? undefined : note.value)
  } catch (sendError) {
    busy.value = false
    error.value =
      describeCommonFailure(sendError, 'a way to ask for an invitation') ??
      'Brownie could not send your request. Try again in a moment.'
    return
  }
  busy.value = false
  shell.value?.close()
  emit('sent')
}
</script>

<template>
  <SpotDialogShell
    ref="shell"
    title="Ask for an invitation"
    caption="A note to whoever runs this Brownie"
    :busy="busy"
    :error="error"
  >
    <p class="invitation-request__lede">
      Invited people get the full allowance instead of the free daily one. If you like, say what you’ll use Brownie
      for.
    </p>
    <form :id="formId" class="invitation-request" novalidate @submit.prevent="send">
      <label class="field-label" :for="`${ids}-note`">Note (optional)</label>
      <textarea
        :id="`${ids}-note`"
        v-model="note"
        class="field-input invitation-request__note"
        rows="4"
        :maxlength="NOTE_LIMIT"
        :aria-describedby="counterId"
      ></textarea>
      <p :id="counterId" class="invitation-request__count">{{ noteLength }}/{{ NOTE_LIMIT }}</p>
    </form>
    <template #foot>
      <button type="button" class="button" :disabled="busy" @click="shell?.close()">Cancel</button>
      <button type="submit" class="button button--primary" :form="formId" :aria-disabled="busy">
        {{ busy ? 'Sending…' : 'Send request' }}
      </button>
    </template>
  </SpotDialogShell>
</template>

<style scoped>
.invitation-request__lede {
  margin-block-end: var(--space-4);
  color: var(--color-text-muted);
}

.invitation-request__note {
  width: 100%;
  resize: vertical;
}

.invitation-request__count {
  margin-block-start: var(--space-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  text-align: end;
}
</style>
