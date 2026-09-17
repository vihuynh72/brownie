<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { documentHandoffState } from '@/router/handoff'
import {
  ApiRequestError,
  allocateUpload,
  attachSource,
  completeUpload,
  createDocument,
  extractArtifact,
  listTemplates,
  uploadArtifactContent,
  type SnapshotResponse,
  type TemplateResponse,
} from '@/api/client'

const session = useSessionStore()
const router = useRouter()

const templates = ref<TemplateResponse[]>([])
const templatesState = ref<'loading' | 'loaded' | 'error'>('loading')
const selectedTemplateId = ref<number | null>(null)
const title = ref('')
const sourceFile = ref<File | null>(null)
const submitState = ref<'idle' | 'submitting' | 'error'>('idle')
const submitError = ref<string | null>(null)
const sourceWarning = ref<string | null>(null)

const selectableTemplates = computed(() => templates.value.filter((t) => t.currentActiveVersionId != null))

async function loadTemplates(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  templatesState.value = 'loading'
  try {
    templates.value = await listTemplates(workspaceId)
    templatesState.value = 'loaded'
    if (selectableTemplates.value.length > 0 && selectedTemplateId.value === null) {
      selectedTemplateId.value = selectableTemplates.value[0]!.id
    }
  } catch {
    templatesState.value = 'error'
  }
}

onMounted(loadTemplates)
// session.personalWorkspaceId can still be undefined the instant this component mounts -- App.vue's
// own onMounted also calls loadIdentity(), and on a hard page load there is no guarantee the router's
// beforeEach guard's own identity check wins that race (see main.ts: app.mount() is not gated on
// router.isReady()). Without this, a document/workspace ID that resolves a moment later than this
// mount never gets a retry and the page is stuck on "Loading templates…" forever, matching the same
// defensive watch DocumentListView.vue and WorkspaceView.vue already carry for the same reason.
watch(() => session.status, (status) => {
  if (status === 'authenticated') void loadTemplates()
})

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  sourceFile.value = input.files?.[0] ?? null
}

/** The attached snapshot on success, or null -- with `sourceWarning` set -- when the file was refused or the upload failed. */
async function attachSelectedSource(workspaceId: number): Promise<SnapshotResponse | null> {
  const file = sourceFile.value
  if (!file) return null
  try {
    const allocated = await allocateUpload(workspaceId, file.name)
    await uploadArtifactContent(workspaceId, allocated.id, file)
    const completed = await completeUpload(workspaceId, allocated.id)
    if (completed.status !== 'READY') {
      sourceWarning.value = `Your source file was not accepted (${completed.rejectionReason ?? completed.status}). The document was still created.`
      return null
    }
    await extractArtifact(workspaceId, allocated.id)
    return await attachSource(workspaceId, allocated.id)
  } catch {
    sourceWarning.value =
      'Your source file could not be attached. The document was still created; attach it again from the Sources tab.'
    return null
  }
}

async function submit(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const templateId = selectedTemplateId.value
  const template = templates.value.find((t) => t.id === templateId)
  if (workspaceId === undefined || templateId === null || !template?.currentActiveVersionId || title.value.trim() === '') {
    return
  }
  submitState.value = 'submitting'
  submitError.value = null
  try {
    const document = await createDocument(workspaceId, crypto.randomUUID(), {
      title: title.value.trim(),
      templateId,
      templateVersionId: template.currentActiveVersionId,
      fields: {},
      initialRevisionReason: 'Created from the new-document workspace flow.',
    })
    const attached = await attachSelectedSource(workspaceId)
    // The workspace screen cannot discover this source on its own (nothing links a source to one
    // document server-side yet), and this component is unmounted the moment the route changes --
    // so both the just-attached source and any warning about it ride along in the pushed route's
    // own history state rather than dying with this component's local refs.
    await router.push({
      path: `/documents/${document.id}`,
      state: documentHandoffState({ attachedSources: attached ? [attached] : [], sourceWarning: sourceWarning.value }),
    })
  } catch (error) {
    submitState.value = 'error'
    submitError.value = error instanceof ApiRequestError ? error.message : 'Something went wrong creating the document.'
  }
}
</script>

<template>
  <section v-if="session.status === 'anonymous'" class="card">
    <p>Sign in to create a document.</p>
    <a class="button button--primary" href="/oauth2/authorization/entra">Sign in</a>
  </section>

  <section v-else class="card new-document">
    <h1>New document</h1>

    <p v-if="templatesState === 'loading'" aria-live="polite">Loading templates…</p>
    <p v-else-if="templatesState === 'error'" class="field-error" role="alert">
      Could not load your templates. Try reloading the page.
    </p>
    <p v-else-if="selectableTemplates.length === 0">
      No templates are available to choose from yet.
    </p>

    <form v-else @submit.prevent="submit">
      <div class="field">
        <label class="field-label" for="template">Template</label>
        <select id="template" v-model.number="selectedTemplateId">
          <option v-for="template in selectableTemplates" :key="template.id" :value="template.id">
            {{ template.displayName }}
          </option>
        </select>
      </div>

      <div class="field">
        <label class="field-label" for="title">Title</label>
        <input id="title" v-model="title" type="text" required placeholder="e.g. March club meeting" />
        <p class="field-hint">A name for your documents list. The minutes' own title is filled in from your notes.</p>
      </div>

      <div class="field">
        <label class="field-label" for="source">Notes or transcript (optional)</label>
        <input id="source" type="file" @change="onFileChange" />
        <p class="field-hint">
          Optional. Attach a text, DOCX, or PDF file and Assist can fill the title, date, attendees, decisions,
          and action items from it; you accept each value before it lands. You can also attach one later.
        </p>
      </div>

      <p v-if="submitError" class="field-error" role="alert">{{ submitError }}</p>
      <p v-if="sourceWarning" class="field-error" role="alert">{{ sourceWarning }}</p>

      <button class="button button--primary" type="submit" :disabled="submitState === 'submitting' || title.trim() === ''">
        {{ submitState === 'submitting' ? 'Creating…' : 'Create document' }}
      </button>
      <p v-if="submitState === 'submitting'" aria-live="polite">Creating…</p>
    </form>
  </section>
</template>

<style scoped>
.new-document {
  max-width: 32rem;
}

.field {
  margin-bottom: var(--space-4);
}

.field select,
.field input[type='text'] {
  width: 100%;
  min-height: var(--control-height);
  padding: 0 var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
}
</style>
