<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import {
  acceptPatchProposal,
  allocateUpload,
  answerQuestion,
  applyGenerationResult,
  attachSource,
  completeUpload,
  extractArtifact,
  getDocument,
  getExtractionResult,
  getGenerationQuestions,
  getJob,
  recordReviewDecision,
  resumeGeneration,
  setFieldLock,
  startExtraction,
  uploadArtifactContent,
  type DocumentResponse,
  type FieldLock,
  type PatchAcceptResponse,
  type PatchProposalResponse,
  type QuestionResponse,
  type ReviewDecision,
  type SnapshotResponse,
} from '@/api/client'

const props = defineProps<{ documentId: number }>()

const session = useSessionStore()
const document = ref<DocumentResponse | null>(null)
const loadState = ref<'loading' | 'loaded' | 'error'>('loading')

type InspectorTab = 'assist' | 'rules' | 'sources' | 'checks'
const activeTab = ref<InspectorTab>('sources')

const attachedSources = ref<SnapshotResponse[]>([])
const sourceUploadState = ref<'idle' | 'uploading' | 'error'>('idle')
const sourceUploadError = ref<string | null>(null)

type ExtractionStage = 'idle' | 'starting' | 'running' | 'waiting-for-input' | 'resuming' | 'succeeded' | 'failed'
const extractionStage = ref<ExtractionStage>('idle')
const extractionJobState = ref<string | null>(null)
const extractionError = ref<string | null>(null)
const extractionResultArtifactId = ref<number | null>(null)
const extractionJobId = ref<number | null>(null)
const openQuestions = ref<QuestionResponse[]>([])
const answerDrafts = ref<Record<number, string>>({})
const answeringQuestionId = ref<number | null>(null)

type ApplyStage = 'idle' | 'applying' | 'proposed' | 'accepting' | 'accepted' | 'failed'
const applyStage = ref<ApplyStage>('idle')
const applyError = ref<string | null>(null)
const patchProposal = ref<PatchProposalResponse | null>(null)
const acceptResult = ref<PatchAcceptResponse | null>(null)

const fieldActionError = ref<string | null>(null)
const fieldActionPending = ref<string | null>(null)

async function loadDocument(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  loadState.value = 'loading'
  try {
    document.value = await getDocument(workspaceId, props.documentId)
    loadState.value = 'loaded'
  } catch {
    loadState.value = 'error'
  }
}

onMounted(loadDocument)
watch(() => session.status, (status) => {
  if (status === 'authenticated') void loadDocument()
})

async function onSourceFileChosen(event: Event): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (workspaceId === undefined || !file) return

  sourceUploadState.value = 'uploading'
  sourceUploadError.value = null
  try {
    const allocated = await allocateUpload(workspaceId, file.name)
    await uploadArtifactContent(workspaceId, allocated.id, file)
    const completed = await completeUpload(workspaceId, allocated.id)
    if (completed.status !== 'READY') {
      sourceUploadError.value = `File was not accepted (${completed.rejectionReason ?? completed.status}).`
      sourceUploadState.value = 'error'
      return
    }
    await extractArtifact(workspaceId, allocated.id)
    const snapshot = await attachSource(workspaceId, allocated.id)
    attachedSources.value = [...attachedSources.value, snapshot]
    sourceUploadState.value = 'idle'
  } catch {
    sourceUploadError.value = 'Could not attach that file. Try again.'
    sourceUploadState.value = 'error'
  }
}

/**
 * Extraction only, for now: this asks the trusted worker to pull typed
 * candidate facts out of the first attached source, then shows the raw
 * result as a download. Applying an accepted candidate onto this
 * document's own fields is a later phase's job -- structured field
 * editing does not exist yet either.
 */
async function tryGroundedExtraction(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const source = attachedSources.value[0]
  if (workspaceId === undefined || !source) return

  extractionStage.value = 'starting'
  extractionError.value = null
  extractionResultArtifactId.value = null
  openQuestions.value = []
  applyStage.value = 'idle'
  applyError.value = null
  patchProposal.value = null
  acceptResult.value = null
  try {
    const receipt = await startExtraction(workspaceId, props.documentId, source.artifactId, crypto.randomUUID())
    extractionJobId.value = receipt.jobId
    extractionStage.value = 'running'
    await pollJobUntilTerminal(workspaceId, receipt.jobId)
  } catch {
    extractionStage.value = 'failed'
    extractionError.value = 'Could not start extraction. Try again.'
  }
}

async function pollJobUntilTerminal(workspaceId: number, jobId: number): Promise<void> {
  const terminalStates = new Set(['SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED'])
  for (let attempt = 0; attempt < 40; attempt++) {
    const job = await getJob(workspaceId, jobId)
    extractionJobState.value = job.state
    if (job.state === 'WAITING_FOR_INPUT') {
      openQuestions.value = await getGenerationQuestions(workspaceId, props.documentId, jobId)
      extractionStage.value = 'waiting-for-input'
      return
    }
    if (terminalStates.has(job.state)) {
      if (job.state === 'SUCCEEDED') {
        const result = await getExtractionResult(workspaceId, props.documentId, jobId)
        extractionResultArtifactId.value = result.artifactId
        extractionStage.value = 'succeeded'
      } else {
        extractionStage.value = 'failed'
        extractionError.value = `Extraction did not succeed (${job.state}).`
      }
      return
    }
    await new Promise((resolve) => setTimeout(resolve, 1500))
  }
  extractionStage.value = 'failed'
  extractionError.value = 'Extraction is taking longer than expected.'
}

async function submitAnswer(questionId: number): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const answerValue = (answerDrafts.value[questionId] ?? '').trim()
  if (workspaceId === undefined || !answerValue) return

  answeringQuestionId.value = questionId
  extractionError.value = null
  try {
    const answered = await answerQuestion(workspaceId, questionId, answerValue)
    openQuestions.value = openQuestions.value.map((question) => (question.id === answered.id ? answered : question))
  } catch {
    extractionError.value = 'Could not save that answer. Try again.'
  } finally {
    answeringQuestionId.value = null
  }
}

async function resumeAfterAnswers(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const jobId = extractionJobId.value
  if (workspaceId === undefined || jobId === null) return

  extractionStage.value = 'resuming'
  extractionError.value = null
  try {
    await resumeGeneration(workspaceId, props.documentId, jobId, crypto.randomUUID())
    extractionStage.value = 'running'
    await pollJobUntilTerminal(workspaceId, jobId)
  } catch {
    extractionStage.value = 'waiting-for-input'
    extractionError.value = 'Could not resume extraction. Try again.'
  }
}

async function applyResultToDocument(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const jobId = extractionJobId.value
  if (workspaceId === undefined || jobId === null) return

  applyStage.value = 'applying'
  applyError.value = null
  try {
    patchProposal.value = await applyGenerationResult(workspaceId, props.documentId, jobId)
    applyStage.value = 'proposed'
  } catch {
    applyStage.value = 'failed'
    applyError.value = 'Could not turn this result into a proposal. Try again.'
  }
}

async function acceptProposal(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const proposal = patchProposal.value
  if (workspaceId === undefined || !proposal || !document.value) return

  applyStage.value = 'accepting'
  applyError.value = null
  try {
    acceptResult.value = await acceptPatchProposal(
      workspaceId,
      props.documentId,
      proposal.id,
      document.value.currentRevision.id,
      crypto.randomUUID(),
    )
    applyStage.value = 'accepted'
    await loadDocument()
  } catch {
    applyStage.value = 'proposed'
    applyError.value = 'Could not apply this proposal. Try again.'
  }
}

async function recordFieldReview(fieldId: string, decision: ReviewDecision): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return

  fieldActionPending.value = fieldId
  fieldActionError.value = null
  try {
    await recordReviewDecision(workspaceId, props.documentId, document.value.currentRevision.id, fieldId, decision, crypto.randomUUID())
    await loadDocument()
  } catch {
    fieldActionError.value = `Could not record a review decision for ${fieldId}. Try again.`
  } finally {
    fieldActionPending.value = null
  }
}

async function toggleFieldLock(fieldId: string, currentLock: FieldLock): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return

  const nextLock: FieldLock = currentLock === 'EXPLICITLY_LOCKED' ? 'EDITABLE' : 'EXPLICITLY_LOCKED'
  fieldActionPending.value = fieldId
  fieldActionError.value = null
  try {
    await setFieldLock(workspaceId, props.documentId, document.value.currentRevision.id, fieldId, nextLock, crypto.randomUUID())
    await loadDocument()
  } catch {
    fieldActionError.value = `Could not change the lock for ${fieldId}. Try again.`
  } finally {
    fieldActionPending.value = null
  }
}
</script>

<template>
  <section v-if="session.status === 'anonymous'" class="card">
    <p>Sign in to view this document.</p>
    <a class="button button--primary" href="/oauth2/authorization/entra">Sign in</a>
  </section>

  <section v-else-if="loadState === 'loading'" aria-live="polite">
    <p>Loading document…</p>
  </section>

  <section v-else-if="loadState === 'error'" class="field-error" role="alert">
    <p>Could not load this document.</p>
    <RouterLink to="/">Back to your documents</RouterLink>
  </section>

  <section v-else-if="document">
    <div class="workspace-topbar">
      <div>
        <RouterLink to="/" class="field-hint">&larr; Your documents</RouterLink>
        <h1>{{ document.title }}</h1>
      </div>
    </div>

    <div class="workspace-layout">
      <div class="card preview-pane">
        <h2>Content</h2>
        <p class="field-hint">
          Value editing is read-only for now -- reviewing and locking a field is already real, below.
        </p>
        <p v-if="fieldActionError" class="field-error" role="alert">{{ fieldActionError }}</p>
        <div v-if="Object.keys(document.currentRevision.fields).length > 0" class="field-list">
          <div v-for="(field, fieldId) in document.currentRevision.fields" :key="fieldId" class="field-row">
            <div class="field-row__value">
              <span class="field-row__label">{{ fieldId }}</span>
              <span>{{ field.value ?? ((field.values ?? []).join(', ') || '—') }}</span>
            </div>
            <div v-if="field.fieldState" class="field-row__state">
              <span class="badge">{{ field.fieldState.review }}</span>
              <span class="badge">{{ field.fieldState.lock }}</span>
              <div class="field-row__actions">
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === fieldId"
                  @click="recordFieldReview(fieldId, 'ACCEPTED')"
                >
                  Accept
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === fieldId"
                  @click="recordFieldReview(fieldId, 'REJECTED')"
                >
                  Reject
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === fieldId"
                  @click="recordFieldReview(fieldId, 'NEEDS_CLARIFICATION')"
                >
                  Needs clarification
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === fieldId"
                  @click="toggleFieldLock(fieldId, field.fieldState.lock as FieldLock)"
                >
                  {{ field.fieldState.lock === 'EXPLICITLY_LOCKED' ? 'Unlock' : 'Lock' }}
                </button>
              </div>
            </div>
            <p v-else class="field-hint">Per-item review and locking for repeated fields arrives in a later phase.</p>
          </div>
        </div>
        <p v-else class="field-hint">No content yet.</p>
      </div>

      <div class="card inspector-pane">
        <div class="tab-strip" role="tablist" aria-label="Document inspector">
          <button
            v-for="tab in (['assist', 'rules', 'sources', 'checks'] as const)"
            :key="tab"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab"
            class="tab-button"
            :class="{ 'tab-button--active': activeTab === tab }"
            @click="activeTab = tab"
          >
            {{ tab === 'assist' ? 'Assist' : tab === 'rules' ? 'Rules' : tab === 'sources' ? 'Sources' : 'Checks' }}
          </button>
        </div>

        <div role="tabpanel">
          <div v-if="activeTab === 'sources'">
            <label class="field-label" for="attach-source">Attach a source</label>
            <input id="attach-source" type="file" :disabled="sourceUploadState === 'uploading'" @change="onSourceFileChosen" />
            <p v-if="sourceUploadState === 'uploading'" aria-live="polite">Uploading…</p>
            <p v-if="sourceUploadError" class="field-error" role="alert">{{ sourceUploadError }}</p>

            <ul v-if="attachedSources.length > 0" class="source-list">
              <li v-for="source in attachedSources" :key="source.id">Source #{{ source.id }} attached.</li>
            </ul>
            <p v-else class="field-hint">No sources attached in this session yet.</p>
          </div>

          <div v-else-if="activeTab === 'assist'">
            <p class="field-hint">
              Experimental: pulls typed candidate facts from your first attached source. Applying a result onto
              this document's own fields arrives in a later phase -- for now this only shows the raw extracted
              result.
            </p>
            <p v-if="attachedSources.length === 0" class="field-hint">Attach a source first, on the Sources tab.</p>
            <template v-else>
              <button
                class="button button--primary"
                type="button"
                :disabled="extractionStage === 'starting' || extractionStage === 'running' || extractionStage === 'waiting-for-input' || extractionStage === 'resuming'"
                @click="tryGroundedExtraction"
              >
                {{ extractionStage === 'starting' || extractionStage === 'running' ? 'Extracting…' : 'Try grounded extraction' }}
              </button>
              <p v-if="extractionStage === 'running'" aria-live="polite">Job status: {{ extractionJobState }}</p>
              <p v-if="extractionError" class="field-error" role="alert">{{ extractionError }}</p>

              <div v-if="extractionStage === 'waiting-for-input' || extractionStage === 'resuming'" class="question-list" aria-live="polite">
                <p class="field-hint">A few things need your input before this can finish.</p>
                <div v-for="question in openQuestions" :key="question.id" class="question-item">
                  <p class="field-label">
                    {{ question.fieldId }}
                    <span class="field-hint">({{ question.reason === 'CONFLICT' ? 'conflicts with the current value' : 'missing' }})</span>
                  </p>
                  <template v-if="question.status === 'ANSWERED'">
                    <p>Answered: {{ question.answerValue }}</p>
                  </template>
                  <template v-else>
                    <ul v-if="question.candidates.length > 0" class="source-list">
                      <li v-for="(candidate, index) in question.candidates" :key="index">
                        <button type="button" class="button" @click="answerDrafts[question.id] = candidate.value">
                          {{ candidate.value }}
                        </button>
                      </li>
                    </ul>
                    <label class="field-label" :for="`answer-${question.id}`">Your answer</label>
                    <input
                      :id="`answer-${question.id}`"
                      type="text"
                      v-model="answerDrafts[question.id]"
                      :disabled="answeringQuestionId === question.id"
                    />
                    <button
                      class="button"
                      type="button"
                      :disabled="answeringQuestionId === question.id || !(answerDrafts[question.id] ?? '').trim()"
                      @click="submitAnswer(question.id)"
                    >
                      Save answer
                    </button>
                  </template>
                </div>
                <button
                  class="button button--primary"
                  type="button"
                  :disabled="extractionStage === 'resuming' || !openQuestions.every((q) => q.status === 'ANSWERED')"
                  @click="resumeAfterAnswers"
                >
                  {{ extractionStage === 'resuming' ? 'Resuming…' : 'Continue' }}
                </button>
              </div>

              <div v-if="extractionStage === 'succeeded' && extractionResultArtifactId !== null">
                <p>
                  Done —
                  <a :href="`/api/v1/workspaces/${session.personalWorkspaceId}/uploads/${extractionResultArtifactId}/download`">
                    download the raw result
                  </a>.
                </p>
                <button
                  v-if="applyStage === 'idle' || applyStage === 'failed'"
                  class="button button--primary"
                  type="button"
                  @click="applyResultToDocument"
                >
                  Apply to document
                </button>
                <p v-if="applyStage === 'applying'" aria-live="polite">Preparing proposal…</p>
                <p v-if="applyError" class="field-error" role="alert">{{ applyError }}</p>

                <div v-if="patchProposal && applyStage !== 'idle' && applyStage !== 'failed'" class="question-item">
                  <p class="field-label">Proposed changes</p>
                  <dl>
                    <template v-for="(field, fieldId) in patchProposal.proposedValues" :key="fieldId">
                      <dt>{{ fieldId }}</dt>
                      <dd>{{ field.value }}</dd>
                    </template>
                  </dl>
                  <button
                    v-if="applyStage !== 'accepted'"
                    class="button button--primary"
                    type="button"
                    :disabled="applyStage === 'accepting'"
                    @click="acceptProposal"
                  >
                    {{ applyStage === 'accepting' ? 'Applying…' : 'Accept and update document' }}
                  </button>
                  <p v-if="applyStage === 'accepted'" aria-live="polite">Applied to the document.</p>
                  <p
                    v-if="acceptResult && Object.values(acceptResult.fieldStatuses).some((s) => s !== 'CLEAN')"
                    class="field-hint"
                  >
                    Some fields could not be applied automatically (conflicting or locked).
                  </p>
                </div>
              </div>
            </template>
          </div>

          <p v-else class="field-hint">Coming in a later phase.</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.workspace-topbar {
  margin-bottom: var(--space-5);
}

.workspace-layout {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: var(--space-5);
}

@media (max-width: 720px) {
  .workspace-layout {
    grid-template-columns: 1fr;
  }
}

dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--space-2) var(--space-4);
}

dt {
  font-weight: 600;
  color: var(--color-text-secondary);
}

.tab-strip {
  display: flex;
  gap: var(--space-2);
  border-bottom: 1px solid var(--color-border);
  margin-bottom: var(--space-4);
}

.tab-button {
  background: none;
  border: none;
  padding: var(--space-2) var(--space-3);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.tab-button--active {
  color: var(--color-text);
  border-bottom-color: var(--color-honey);
}

.source-list {
  padding-left: var(--space-5);
}

.field-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.field-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2) var(--space-4);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.field-row__value {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.field-row__label {
  font-weight: 600;
  color: var(--color-text-secondary);
}

.field-row__state {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.field-row__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.badge {
  display: inline-block;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius);
  background: var(--color-honey-soft);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.question-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  margin-top: var(--space-4);
}

.question-item {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
</style>
