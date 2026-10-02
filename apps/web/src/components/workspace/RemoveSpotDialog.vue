<script setup lang="ts">
import { ref, useId } from 'vue'
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
  <SpotDialogShell ref="shell" :title="`Remove ${label}?`" :busy="busy" :described-by="`${ids}-question`">
    <div class="spot-change">
      <div :id="`${ids}-question`">
        <p v-for="line in question" :key="line" class="spot-change__line">{{ line }}</p>
      </div>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
      <p class="spot-change__status" role="status">{{ status }}</p>
      <div class="spot-change__buttons">
        <button :id="`${ids}-cancel`" type="button" class="button button--secondary" :aria-disabled="busy ? 'true' : undefined" @click="!busy && shell?.close()">
          Cancel
        </button>
        <button type="button" class="button button--primary" :aria-disabled="busy ? 'true' : undefined" @click="confirm">
          {{ busy ? 'Removing…' : 'Remove the fill spot' }}
        </button>
      </div>
    </div>
  </SpotDialogShell>
</template>

<style scoped>
.spot-change {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.spot-change__line {
  margin: 0 0 var(--space-2);
}

.spot-change__status {
  margin: 0;
}

.spot-change__buttons {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-block-start: var(--space-2);
}
</style>
