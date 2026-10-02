<script setup lang="ts">
import { ref, useId } from 'vue'
import SpotDialogShell from '@/components/workspace/SpotDialogShell.vue'
import { MAX_SPOT_LABEL, spotLabelProblem, tidySpotLabel, workingWords } from '@/workspace/fillSpotWords'

/**
 * A new name for a fill spot. The value it holds stays; the form gets a new version with the name, so
 * documents started from it later use it too. The page makes the change (`send`), and the dialog says
 * why it was refused, if it was.
 */
const props = defineProps<{
  /** Makes the change; null when it was made, else why not. */
  send: (label: string) => Promise<string | null>
}>()

const emit = defineEmits<{ renamed: [label: string] }>()

const shell = ref<InstanceType<typeof SpotDialogShell> | null>(null)
const ids = useId()
const current = ref('')
const label = ref('')
const labelError = ref<string | null>(null)
const busy = ref(false)
const status = ref('')
const error = ref<string | null>(null)

function open(currentLabel: string, returnTo?: HTMLElement | null): void {
  current.value = currentLabel
  label.value = currentLabel
  labelError.value = null
  busy.value = false
  status.value = ''
  error.value = null
  shell.value?.open(() => {
    const input = document.getElementById(`${ids}-label`) as HTMLInputElement | null
    input?.select()
    return input
  }, returnTo)
}

defineExpose({ open })

async function submit(): Promise<void> {
  if (busy.value) return
  const problem = spotLabelProblem(label.value)
  const tidy = tidySpotLabel(label.value)
  labelError.value = problem ?? (tidy === current.value ? 'That is its name already. Type a new name, or choose Cancel.' : null)
  if (labelError.value) {
    document.getElementById(`${ids}-label`)?.focus()
    return
  }
  busy.value = true
  error.value = null
  status.value = workingWords('rename')
  let refusal: string | null
  try {
    refusal = await props.send(tidy)
  } finally {
    busy.value = false
    status.value = ''
  }
  if (refusal === null) {
    shell.value?.close()
    emit('renamed', tidy)
    return
  }
  error.value = refusal
}
</script>

<template>
  <SpotDialogShell ref="shell" :title="`Rename ${current}`" :busy="busy">
    <form class="spot-change" novalidate @submit.prevent="submit">
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
      <label class="field-label" :for="`${ids}-label`">New name</label>
      <input
        :id="`${ids}-label`"
        v-model="label"
        class="spot-change__input"
        type="text"
        autocomplete="off"
        :aria-invalid="labelError ? 'true' : undefined"
        :aria-describedby="labelError ? `${ids}-error ${ids}-hint` : `${ids}-hint`"
        @input="labelError = null"
      />
      <p :id="`${ids}-hint`" class="field-hint">
        Up to {{ MAX_SPOT_LABEL }} characters. What it holds stays as it is, and new documents from this form use the new name.
      </p>
      <p v-if="labelError" :id="`${ids}-error`" class="field-error" role="alert">{{ labelError }}</p>
      <p class="spot-change__status" role="status">{{ status }}</p>
      <div class="spot-change__buttons">
        <button type="button" class="button button--secondary" :aria-disabled="busy ? 'true' : undefined" @click="!busy && shell?.close()">Cancel</button>
        <button type="submit" class="button button--primary" :aria-disabled="busy ? 'true' : undefined">{{ busy ? 'Renaming…' : 'Rename' }}</button>
      </div>
    </form>
  </SpotDialogShell>
</template>

<style scoped>
.spot-change {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.spot-change__input {
  inline-size: 100%;
  min-block-size: var(--control-height);
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  color: var(--color-text);
  font: inherit;
}

.spot-change__input[aria-invalid='true'] {
  border-color: var(--color-error);
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
