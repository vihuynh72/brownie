<script setup lang="ts">
import { ref, useId } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import SpotDialogShell from '@/components/workspace/SpotDialogShell.vue'
import { removeQuestion, workingWords } from '@/workspace/fillSpotWords'

/**
 * The question before a fill spot is taken away. The value it held stays in the version history, and
 * a spot that came with the form keeps its own box in the file: Brownie only stops filling it. The page
 * makes the change (`send`), and the dialog says why it was refused, if it was.
 */
const props = defineProps<{
  /** Makes the change; null when it was made, else why not. */
  send: () => Promise<string | null>
}>()

const emit = defineEmits<{
  /** The spot is gone and the dialog closed without giving focus back: what opened it went with the spot. */
  removed: []
}>()

const shell = ref<InstanceType<typeof SpotDialogShell> | null>(null)
const ids = useId()
const label = ref('')
const question = ref<string[]>([])
const busy = ref(false)
const status = ref('')
const error = ref<string | null>(null)

function open(spotLabel: string, hasValue: boolean, fromTheForm: boolean, returnTo?: HTMLElement | null): void {
  label.value = spotLabel
  question.value = removeQuestion(spotLabel, hasValue, fromTheForm)
  busy.value = false
  status.value = ''
  error.value = null
  // The safe answer takes focus first.
  shell.value?.open(() => document.getElementById(`${ids}-cancel`), returnTo)
}

defineExpose({ open })

async function confirm(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  status.value = workingWords('remove')
  let refusal: string | null
  try {
    refusal = await props.send()
  } finally {
    busy.value = false
    status.value = ''
  }
  if (refusal === null) {
    shell.value?.close(false)
    emit('removed')
    return
  }
  error.value = refusal
}
</script>

<template>
  <SpotDialogShell
    ref="shell"
    caption="Remove a fill spot"
    :title="`Remove ${label}?`"
    danger
    :busy="busy"
    :status="status"
    :error="error"
    :described-by="`${ids}-question`"
  >
    <div :id="`${ids}-question`" class="panel panel--quiet spot-remove__question">
      <p v-for="line in question" :key="line" class="spot-remove__line">{{ line }}</p>
    </div>

    <template #foot>
      <button :id="`${ids}-cancel`" type="button" class="button button--secondary" :aria-disabled="busy ? 'true' : undefined" @click="!busy && shell?.close()">
        Cancel
      </button>
      <button type="button" class="button button--danger" :aria-disabled="busy ? 'true' : undefined" @click="confirm">
        <AppIcon name="trash" :size="18" />{{ busy ? 'Removing…' : 'Remove the fill spot' }}
      </button>
    </template>
  </SpotDialogShell>
</template>

<style scoped>
/* The question on the shared quiet panel: the first line, which asks, in red; what stays afterwards in plain words. */
.spot-remove__question {
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

.spot-remove__line {
  margin: 0;
}

.spot-remove__line + .spot-remove__line {
  margin-block-start: 0.375rem;
}

.spot-remove__line:first-child {
  color: var(--color-error);
  font-weight: var(--font-weight-medium);
}
</style>
