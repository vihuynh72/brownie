<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { documentHandoffState } from '@/router/handoff'
import {
  ApiRequestError,
  allocateUpload,
  attachDocumentSource,
  completeUpload,
  createDocument,
  extractArtifact,
  listTemplates,
  uploadArtifactContent,
  type ArtifactResponse,
  type DocumentSourceResponse,
  type TemplateResponse,
} from '@/api/client'
import { describeCommonFailure } from '@/api/failures'

const session = useSessionStore()
const router = useRouter()
const route = useRoute()

const templates = ref<TemplateResponse[]>([])
const templatesState = ref<'loading' | 'loaded' | 'error'>('loading')
const templatesError = ref<string | null>(null)
const selectedTemplateId = ref<number | null>(null)
const title = ref('')
const sourceFile = ref<File | null>(null)
const submitState = ref<'idle' | 'submitting' | 'error'>('idle')
const submitError = ref<string | null>(null)
const sourceWarning = ref<string | null>(null)
const uploadLimit = ref<string | null>(null)
/** The same limit in bytes, to tell a file refused for its size from one refused for what it unpacks to. */
const uploadLimitBytes = ref<number | null>(null)

const selectableTemplates = computed(() => templates.value.filter((t) => t.currentActiveVersionId != null))

async function loadTemplates(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  templatesState.value = 'loading'
  try {
    templates.value = await listTemplates(workspaceId)
    templatesState.value = 'loaded'
    if (selectableTemplates.value.length > 0 && selectedTemplateId.value === null) {
      // The template screen links here with the template it just activated; otherwise the first one.
      const requested = Number(route.query.templateId)
      const preselected = selectableTemplates.value.find((t) => t.id === requested) ?? selectableTemplates.value[0]!
      selectedTemplateId.value = preselected.id
    }
  } catch (error) {
    templatesState.value = 'error'
    // A server without the route, or one that cannot be reached, will not be fixed by reloading this page.
    templatesError.value = describeCommonFailure(error, 'a way to list templates') ?? 'Could not load your templates. Try reloading the page.'
  }
}

onMounted(loadTemplates)
onMounted(async () => {
  try {
    const { maxUploadBytes } = await loadCapabilities()
    // An older server may not send the limit at all; then neither the hint nor a size refusal names one.
    if (typeof maxUploadBytes === 'number' && maxUploadBytes > 0) {
      uploadLimitBytes.value = maxUploadBytes
      uploadLimit.value = formatBytes(maxUploadBytes)
    }
  } catch {
    uploadLimit.value = null
    uploadLimitBytes.value = null
  }
})
// session.personalWorkspaceId can still be undefined the instant this component mounts -- App.vue's
// own onMounted also calls loadIdentity(), and on a hard page load there is no guarantee the router's
// beforeEach guard's own identity check wins that race (see main.ts: app.mount() is not gated on
// router.isReady()). Without this, a document/workspace ID that resolves a moment later than this
// mount never gets a retry and the page is stuck on "Loading templates…" forever, matching the same
// defensive watch HomeView.vue and WorkspaceView.vue already carry for the same reason.
watch(() => session.status, (status) => {
  if (status === 'authenticated') void loadTemplates()
})

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  sourceFile.value = input.files?.[0] ?? null
}

/**
 * Why a finished upload was not accepted, as a clause. The rejection reasons are the scanner's and
 * the content inspector's own codes, and a file still waiting for its scan has none at all.
 */
function whyFileWasRefused(artifact: ArtifactResponse): string {
  if (artifact.status !== 'REJECTED') return 'Brownie could not finish checking it'
  switch (artifact.rejectionReason) {
    case 'MALWARE_DETECTED':
      return 'the malware scan flagged it'
    case 'UNSUPPORTED_MEDIA_TYPE':
      return 'Brownie cannot use that kind of file'
    case 'DECOMPRESSION_LIMIT_EXCEEDED':
      return 'it unpacks to far more than Brownie accepts'
    case 'EXPIRED_ABANDONED_UPLOAD':
      return 'the upload took too long to finish'
    default:
      return "it did not pass Brownie's checks"
  }
}

/**
 * What to say when the source file could not go with the new document, which exists either way.
 * The Sources tab is offered only where attaching from there could work: a file too large or of the
 * wrong kind, or a server without the route, would be refused there in just the same way.
 */
function attachFailureWarning(error: unknown, file: File): string {
  if (error instanceof ApiRequestError && error.status === 413) {
    const limit = uploadLimitBytes.value
    const limitText = limit !== null && file.size > limit ? formatBytes(limit) : null
    if (limitText) {
      return `Your source file was not attached: it is larger than the ${limitText} upload limit. The document was still created.`
    }
    return error.problem?.detail
      ? `Your source file was not attached. ${error.problem.detail} The document was still created.`
      : 'Your source file was not attached: it is larger than Brownie accepts. The document was still created.'
  }
  if (error instanceof ApiRequestError && error.status === 415) {
    return (
      'Your source file was not attached: Brownie cannot use that kind of file as a source. The document was still ' +
      'created; attach a plain-text (.txt) file from the Sources tab instead.'
    )
  }
  const why = describeCommonFailure(error, 'a way to attach sources')
  if (!why) {
    return 'Your source file could not be attached. The document was still created; attach it again from the Sources tab.'
  }
  if (error instanceof ApiRequestError && error.routeMissing) {
    return `Your source file could not be attached. ${why} The document was still created.`
  }
  return `Your source file could not be attached. ${why} The document was still created; attach it again from the Sources tab.`
}

/** The document's new source on success, or null -- with `sourceWarning` set -- when the file was refused or the upload failed. */
async function attachSelectedSource(workspaceId: number, documentId: number): Promise<DocumentSourceResponse | null> {
  const file = sourceFile.value
  if (!file) return null
  try {
    const allocated = await allocateUpload(workspaceId, file.name)
    await uploadArtifactContent(workspaceId, allocated.id, file)
    const completed = await completeUpload(workspaceId, allocated.id)
    if (completed.status !== 'READY') {
      sourceWarning.value = `Your source file was not attached: ${whyFileWasRefused(completed)}. The document was still created.`
      return null
    }
    await extractArtifact(workspaceId, allocated.id)
    return await attachDocumentSource(workspaceId, documentId, allocated.id)
  } catch (error) {
    sourceWarning.value = attachFailureWarning(error, file)
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
    const attached = await attachSelectedSource(workspaceId, document.id)
    // The source is linked to the document server-side, so the workspace will find it on its own;
    // the just-attached copy and any warning about it still ride along in the pushed route's own
    // history state so the first paint and the warning survive this component being unmounted.
    await router.push({
      path: `/documents/${document.id}`,
      state: documentHandoffState({ attachedSources: attached ? [attached] : [], sourceWarning: sourceWarning.value }),
    })
  } catch (error) {
    submitState.value = 'error'
    // Never the error's own message: for an answer with no explanation that is "Request failed with status 502".
    submitError.value =
      describeCommonFailure(error, 'a way to create documents') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      'Something went wrong creating the document.'
  }
}
</script>

<template>
  <!-- The router sends a signed-out visitor to the sign-in page before this view mounts; this is what shows if a session ends while it is open. -->
  <section v-if="session.status === 'anonymous'" class="card">
    <p>Sign in to create a document.</p>
    <RouterLink class="button button--primary" :to="{ path: '/signin', query: { next: '/documents/new' } }">Sign in</RouterLink>
  </section>

  <section v-else class="card new-document">
    <h1>New document</h1>

    <p v-if="templatesState === 'loading'" aria-live="polite">Loading templates…</p>
    <p v-else-if="templatesState === 'error'" class="field-error" role="alert">{{ templatesError }}</p>
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
        <input id="source" type="file" accept=".txt,text/plain" @change="onFileChange" />
        <p class="field-hint">
          Optional. Attach a plain-text (.txt) file<span v-if="uploadLimit"> up to {{ uploadLimit }}</span> and Assist can fill this
          template's fields from it; you accept each value before it lands. You can also attach one from the
          workspace, or type every value in yourself.
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
