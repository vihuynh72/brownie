<script setup lang="ts">
import { ref, useId } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
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
/** The form the Rename button, which sits in the dialog's foot, submits. */
const formId = `${ids}-form`
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
  <SpotDialogShell ref="shell" caption="Rename a fill spot" :title="`Rename ${current}`" :busy="busy" :status="status" :error="error">
    <form :id="formId" class="spot-rename" novalidate @submit.prevent="submit">
      <label class="field-label" :for="`${ids}-label`">New name</label>
      <input
        :id="`${ids}-label`"
        v-model="label"
        class="text-field"
        type="text"
        autocomplete="off"
        :aria-invalid="labelError ? 'true' : undefined"
        :aria-describedby="labelError ? `${ids}-error ${ids}-hint` : `${ids}-hint`"
        @input="labelError = null"
      />
      <p :id="`${ids}-hint`" class="field-hint">
        Up to {{ MAX_SPOT_LABEL }} characters. What it holds stays as it is, and new documents from this form use the new name.
      </p>
      <p v-if="labelError" :id="`${ids}-error`" class="field-error spot-rename__error" role="alert">{{ labelError }}</p>
    </form>

    <template #foot>
      <button type="button" class="button button--secondary" :aria-disabled="busy ? 'true' : undefined" @click="!busy && shell?.close()">Cancel</button>
      <button type="submit" class="button button--primary" :form="formId" :aria-disabled="busy ? 'true' : undefined">
        <AppIcon name="pencil" :size="18" />{{ busy ? 'Renaming…' : 'Rename' }}
      </button>
    </template>
  </SpotDialogShell>
</template>

<style scoped>
/* The hint takes its room above it from the shared field styles; the dialog's own padding spaces what follows. */
.spot-rename .field-hint {
  margin-block-end: 0;
}

.spot-rename__error {
  margin: 0.25rem 0 0;
}
</style>
