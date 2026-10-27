<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const token = computed(() => String(route.params.token ?? ''))

type Phase = 'loading' | 'ready' | 'sending' | 'done' | 'failed' | 'gone'

const phase = ref<Phase>('loading')
const clientName = ref('')
const expiresAt = ref('')
const doneMessage = ref('')
const dragOver = ref(false)

const ACCEPTED = '.docx,.pdf'
const ACCEPTED_WORDS = 'a Word document (.docx) or a PDF form (.pdf)'

async function peek() {
  try {
    const response = await fetch(`/api/v1/mcp-uploads/${token.value}`)
    if (response.status === 404) {
      phase.value = 'gone'
      return
    }
    const data = await response.json()
    clientName.value = data.client_name ?? 'the chat app'
    expiresAt.value = data.expires_at ?? ''
    phase.value = 'ready'
  } catch {
    phase.value = 'gone'
  }
}

async function send(file: File | undefined) {
  if (!file || phase.value !== 'ready') return
  phase.value = 'sending'
  try {
    const response = await fetch(`/api/v1/mcp-uploads/${token.value}?filename=${encodeURIComponent(file.name)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: file,
    })
    if (response.status === 404) {
      phase.value = 'gone'
      return
    }
    const data = await response.json()
    if (data.state === 'READY') {
      doneMessage.value =
        typeof data.spot_count === 'number'
          ? `Brownie found ${data.spot_count} place${data.spot_count === 1 ? '' : 's'} to fill in ${file.name}.`
          : `${file.name} is in Brownie.`
      phase.value = 'done'
    } else {
      doneMessage.value = data.reason ?? 'Brownie could not use that file.'
      phase.value = 'failed'
    }
  } catch {
    doneMessage.value = 'The file did not reach Brownie. Check your connection and try again.'
    phase.value = 'failed'
  }
}

function onDrop(event: DragEvent) {
  dragOver.value = false
  send(event.dataTransfer?.files?.[0])
}

function onPick(event: Event) {
  send((event.target as HTMLInputElement).files?.[0])
}

onMounted(peek)
</script>

<template>
  <section class="page-column page-column--narrow form-upload">
    <header class="page-head page-head--center">
      <h1 class="page-title page-title--small">Share a form with Brownie</h1>
      <p v-if="phase === 'ready' || phase === 'sending'" class="page-lede">
        {{ clientName }} asked you to share a form with Brownie. Drop {{ ACCEPTED_WORDS }} below and it finds the
        places it can fill.
      </p>
    </header>

    <p v-if="phase === 'loading'" class="form-upload__note">Opening your link…</p>

    <label
      v-else-if="phase === 'ready' || phase === 'sending'"
      class="form-upload__zone"
      :class="{ 'form-upload__zone--over': dragOver }"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="onDrop"
    >
      <input class="form-upload__input" type="file" :accept="ACCEPTED" @change="onPick" />
      <span class="form-upload__call">{{ phase === 'sending' ? 'Sending…' : 'Drop your form here or choose a file' }}</span>
      <span class="form-upload__kinds">{{ ACCEPTED_WORDS }}</span>
    </label>

    <p v-else-if="phase === 'done'" class="form-upload__note">
      {{ doneMessage }} You can go back to {{ clientName }}.
    </p>

    <template v-else-if="phase === 'failed'">
      <p class="form-upload__note">{{ doneMessage }}</p>
      <p class="form-upload__note">Ask {{ clientName }} for a new link.</p>
    </template>

    <p v-else class="form-upload__note">This link is no longer available — it may be used already or have expired.</p>
  </section>
</template>

<style scoped>
.form-upload {
  padding-block-start: clamp(var(--space-6), 14.25vh, 8.75rem);
}

.form-upload__zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-6);
  border: 2px dashed var(--color-border, #ccc);
  border-radius: var(--radius-2, 8px);
  cursor: pointer;
  text-align: center;
}

.form-upload__zone--over {
  border-style: solid;
}

.form-upload__input {
  /* The input stays invisible; the whole zone is its target. */
  position: absolute;
  inline-size: 1px;
  block-size: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

.form-upload__kinds {
  font-size: 0.9em;
  opacity: 0.7;
}

.form-upload__note {
  margin-inline: auto;
  max-inline-size: 36rem;
  text-align: center;
}
</style>
