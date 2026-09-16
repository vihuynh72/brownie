<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import {
  ApiRequestError,
  acceptPatchProposal,
  allocateUpload,
  answerQuestion,
  applyGenerationResult,
  approveExport,
  artifactDownloadUrl,
  attachSource,
  completeUpload,
  exportDocument,
  extractArtifact,
  getDocument,
  getDocumentRevision,
  getExtractionResult,
  getGenerationQuestions,
  getJob,
  getLatestExportApproval,
  getLatestExportReceipt,
  getLatestValidation,
  listDocumentRevisions,
  recordReviewDecision,
  resumeGeneration,
  setFieldLock,
  startExtraction,
  uploadArtifactContent,
  validateDocument,
  type DocumentResponse,
  type DocumentRevisionResponse,
  type ExportApprovalResponse,
  type ExportFormat,
  type ExportReceiptResponse,
  type FieldLock,
  type PatchAcceptResponse,
  type PatchProposalResponse,
  type QuestionResponse,
  type ReviewDecision,
  type SnapshotResponse,
  type ValidationManifestResponse,
} from '@/api/client'

const props = defineProps<{ documentId: number }>()

const session = useSessionStore()
const document = ref<DocumentResponse | null>(null)
const loadState = ref<'loading' | 'loaded' | 'error'>('loading')

const INSPECTOR_TABS = ['assist', 'rules', 'sources', 'checks', 'history'] as const
type InspectorTab = (typeof INSPECTOR_TABS)[number]
const activeTab = ref<InspectorTab>('sources')

function selectTab(tab: InspectorTab): void {
  activeTab.value = tab
  if (tab === 'history') void loadRevisionHistory()
  if (tab === 'checks') void hydrateChecksState()
}

/** Template refs for each tab button, in tab order -- Vue keeps this array in sync with the v-for automatically. */
const tabButtonEls = ref<HTMLButtonElement[]>([])

function focusTab(index: number): void {
  tabButtonEls.value[index]?.focus()
}

/**
 * Roving-tabindex arrow-key navigation for the inspector tablist (WAI-ARIA tabs pattern): the
 * arrow keys both move focus and activate the target tab, Home/End jump to the first/last tab,
 * and every other key is left alone.
 */
function onTabKeydown(event: KeyboardEvent, index: number): void {
  let nextIndex: number
  switch (event.key) {
    case 'ArrowRight':
    case 'ArrowDown':
      nextIndex = (index + 1) % INSPECTOR_TABS.length
      break
    case 'ArrowLeft':
    case 'ArrowUp':
      nextIndex = (index - 1 + INSPECTOR_TABS.length) % INSPECTOR_TABS.length
      break
    case 'Home':
      nextIndex = 0
      break
    case 'End':
      nextIndex = INSPECTOR_TABS.length - 1
      break
    default:
      return
  }
  event.preventDefault()
  selectTab(INSPECTOR_TABS[nextIndex])
  focusTab(nextIndex)
}

// Below the 720px breakpoint the inspector pane (tab-strip + tabpanel) collapses into a
// togglable drawer instead of always being visible beneath the preview pane. The collapse
// itself is CSS-only (see .inspector-drawer--collapsed, scoped inside that same media query),
// so this ref just tracks open/closed state -- it has no visible effect at or above the
// breakpoint, and none of the logic below needs to know the actual viewport width.
const drawerOpen = ref(false)
const drawerToggleRef = ref<HTMLButtonElement | null>(null)
const drawerRef = ref<HTMLDivElement | null>(null)
const drawerHeadingRef = ref<HTMLHeadingElement | null>(null)

function toggleDrawer(): void {
  drawerOpen.value = !drawerOpen.value
}

function closeDrawer(): void {
  drawerOpen.value = false
  drawerToggleRef.value?.focus()
}

watch(drawerOpen, async (open) => {
  if (!open) return
  await nextTick()
  drawerHeadingRef.value?.focus()
})

const DRAWER_FOCUSABLE_SELECTOR = 'a[href], button, input, select, textarea, [tabindex]'

/**
 * Elements that are actual tab stops inside the drawer right now -- the broad selector above,
 * narrowed to what a real Tab key press would actually land on: nothing disabled, and nothing on
 * tabindex="-1" (the roving-tabindex tab buttons this same keydown-Tab check would otherwise wrongly
 * treat as separate stops, since e.g. "button:not([disabled])" alone can't see their tabindex).
 */
function drawerFocusable(): HTMLElement[] {
  if (!drawerRef.value) return []
  return Array.from(drawerRef.value.querySelectorAll<HTMLElement>(DRAWER_FOCUSABLE_SELECTOR)).filter(
    (el) => !el.hasAttribute('disabled') && el.tabIndex >= 0,
  )
}

/**
 * Escape closes the drawer; Tab/Shift+Tab wrap at the drawer's own first/last focusable element so
 * focus can never leave it while open. Gated on drawerOpen itself, not just on the drawer being
 * visible -- above the 720px breakpoint .inspector-drawer--collapsed has no effect at all (it's
 * scoped inside that media query), so the drawer's contents stay visible and focusable regardless
 * of drawerOpen's value; without this guard, Tab would trap a desktop keyboard user inside this
 * region forever; a real WCAG 2.1.2 violation this component's own test suite never caught because
 * jsdom's default viewport already satisfies the >=720px case every test runs under.
 */
function onDrawerKeydown(event: KeyboardEvent): void {
  if (!drawerOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDrawer()
    return
  }
  if (event.key !== 'Tab' || !drawerRef.value) return

  const focusable = drawerFocusable()
  if (focusable.length === 0) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]

  // `document` above is this component's own ref (the loaded DocumentResponse), not the DOM
  // global -- window.document is needed here to read the real activeElement.
  if (event.shiftKey && window.document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && window.document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

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

type ValidationStage = 'idle' | 'validating' | 'validated' | 'failed'
const validationStage = ref<ValidationStage>('idle')
const validationError = ref<string | null>(null)
const validationManifest = ref<ValidationManifestResponse | null>(null)
/** The revision id `hydrateChecksState` has already tried to rehydrate check state for -- never re-fetched a second time for the same revision. */
const checksHydratedForRevisionId = ref<number | null>(null)

const exportFormat = ref<ExportFormat>('BOTH')

type ApprovalStage = 'idle' | 'approving' | 'approved' | 'failed'
const approvalStage = ref<ApprovalStage>('idle')
const approvalError = ref<string | null>(null)
const exportApproval = ref<ExportApprovalResponse | null>(null)

type ExportStage = 'idle' | 'exporting' | 'exported' | 'failed'
const exportStage = ref<ExportStage>('idle')
const exportError = ref<string | null>(null)
const exportReceipt = ref<ExportReceiptResponse | null>(null)

const revisions = ref<DocumentRevisionResponse[]>([])
type RevisionsLoadState = 'idle' | 'loading' | 'loaded' | 'error'
const revisionsLoadState = ref<RevisionsLoadState>('idle')
const revisionsError = ref<string | null>(null)

const compareRevisionId = ref<number | null>(null)
type CompareStage = 'idle' | 'loading' | 'loaded' | 'error'
const compareStage = ref<CompareStage>('idle')
const compareError = ref<string | null>(null)
const compareRevision = ref<DocumentRevisionResponse | null>(null)

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

function fieldDisplayValue(field: DocumentRevisionResponse['fields'][string] | undefined): string {
  if (!field) return '—'
  return field.value ?? ((field.values ?? []).join(', ') || '—')
}

function resetApprovalAndExportState(): void {
  approvalStage.value = 'idle'
  approvalError.value = null
  exportApproval.value = null
  exportStage.value = 'idle'
  exportError.value = null
  exportReceipt.value = null
}

function resetChecksState(): void {
  validationStage.value = 'idle'
  validationError.value = null
  validationManifest.value = null
  resetApprovalAndExportState()
}

// A revision changes here either because another action already reloaded the document (accepting
// a patch proposal, a field review decision, a lock toggle -- any validate/approve/export state on
// screen was scoped to the revision that just stopped being current, so it can no longer be
// trusted), or because validateCurrentRevision() itself just refreshed the document after the new
// revision its own POST /validate created -- in that one case validationManifest.value already
// names the new revision, so it (and the approval/export state validate just recomputed) must
// survive rather than be wiped a moment after being set. Either way, the revision list any earlier
// History-tab visit cached is now stale and must be re-fetched next time that tab is opened.
watch(() => document.value?.currentRevision.id, (newRevisionId) => {
  revisionsLoadState.value = 'idle'
  revisions.value = []
  if (newRevisionId !== undefined && validationManifest.value?.revisionId === newRevisionId) {
    return
  }
  resetChecksState()
})

/**
 * Shared by validate, approve, and export.
 *
 * A 412 means whatever this action was scoped to (the revision validate ran against, or the
 * manifest/approval approve or export relied on) is no longer current -- the only sound recovery
 * is a fresh validate, so every checks/approval/export ref is cleared here. The message is also
 * written directly to validationError (in addition to being returned) because approvalError and
 * exportError live inside the `validationManifest`-gated part of the template, which this same
 * reset just made disappear -- validationError is the only place left that stays visible.
 *
 * A 404 means only the specific manifest/approval/receipt this action pointed at is gone (e.g.
 * superseded by another approval); the validation manifest itself is still meaningful, so only the
 * approval/export state is reset.
 *
 * A 422 carries a single server message rather than an itemized list.
 */
function checksFailureMessage(error: unknown): string {
  if (error instanceof ApiRequestError) {
    if (error.status === 412) {
      resetChecksState()
      const message = 'This document changed since you last validated it -- validate again.'
      validationError.value = message
      return message
    }
    if (error.status === 404) {
      resetApprovalAndExportState()
      return error.problem?.detail ?? 'That approval is no longer available -- validate again.'
    }
    if (error.status === 422) {
      return error.problem?.detail ?? error.message
    }
  }
  return 'Something went wrong. Try again.'
}

async function validateCurrentRevision(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return

  validationStage.value = 'validating'
  validationError.value = null
  try {
    const manifest = await validateDocument(
      workspaceId,
      props.documentId,
      document.value.currentRevision.id,
      crypto.randomUUID(),
    )
    validationManifest.value = manifest
    resetApprovalAndExportState()
    // /validate always appends a brand-new current revision (it records per-field validation
    // results onto it) distinct from the one just validated -- resync document.value with that new
    // revision so the rest of the page (and the currentRevision.id watcher above) sees it too.
    await loadDocument()
    validationStage.value = 'validated'
  } catch (error) {
    validationStage.value = 'failed'
    validationError.value = checksFailureMessage(error)
  }
}

// Approving again (same format re-approved, or a new one after the watcher below cleared a prior
// approval) must never leave a stale receipt from an earlier approval visible next to it.
watch(exportFormat, () => {
  if (approvalStage.value !== 'idle') {
    resetApprovalAndExportState()
  }
})

async function approveCurrentExport(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const manifest = validationManifest.value
  if (workspaceId === undefined || !manifest) return

  exportStage.value = 'idle'
  exportError.value = null
  exportReceipt.value = null
  approvalStage.value = 'approving'
  approvalError.value = null
  try {
    exportApproval.value = await approveExport(workspaceId, props.documentId, manifest.id, exportFormat.value)
    approvalStage.value = 'approved'
  } catch (error) {
    approvalStage.value = 'failed'
    approvalError.value = checksFailureMessage(error)
  }
}

async function exportApprovedDocument(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !exportApproval.value) return

  exportStage.value = 'exporting'
  exportError.value = null
  try {
    exportReceipt.value = await exportDocument(workspaceId, props.documentId)
    exportStage.value = 'exported'
  } catch (error) {
    exportStage.value = 'failed'
    exportError.value = checksFailureMessage(error)
  }
}

/**
 * Best-effort rehydration for a document that already has validate/approve/export state recorded
 * against its current revision from an earlier session (or before a page reload) -- Validate,
 * Approve for export and Export all remain available and authoritative regardless, so any failure
 * here (including a 404 for "nothing recorded yet", the common case) is silently ignored rather
 * than surfaced as an error.
 */
async function hydrateChecksState(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || !document.value) return
  const revisionId = document.value.currentRevision.id
  if (checksHydratedForRevisionId.value === revisionId) return
  checksHydratedForRevisionId.value = revisionId
  if (validationStage.value !== 'idle') return

  let manifest: ValidationManifestResponse
  try {
    manifest = await getLatestValidation(workspaceId, props.documentId, revisionId)
  } catch {
    return
  }
  validationManifest.value = manifest
  validationStage.value = 'validated'

  let approval: ExportApprovalResponse
  try {
    approval = await getLatestExportApproval(workspaceId, props.documentId)
  } catch {
    return
  }
  if (approval.validationManifestId !== manifest.id) return
  exportApproval.value = approval
  approvalStage.value = 'approved'

  try {
    const receipt = await getLatestExportReceipt(workspaceId, props.documentId)
    if (receipt.exportApprovalId === approval.id) {
      exportReceipt.value = receipt
      exportStage.value = 'exported'
    }
  } catch {
    // No export recorded against this approval yet -- fine, the Export button covers that.
  }
}

async function loadRevisionHistory(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || revisionsLoadState.value === 'loading' || revisionsLoadState.value === 'loaded') return

  revisionsLoadState.value = 'loading'
  revisionsError.value = null
  try {
    revisions.value = await listDocumentRevisions(workspaceId, props.documentId)
    revisionsLoadState.value = 'loaded'
  } catch (error) {
    revisionsLoadState.value = 'error'
    revisionsError.value = error instanceof ApiRequestError ? error.message : "Could not load this document's revision history."
  }
}

// Stamps each call so an out-of-order response (an earlier compare that resolves after a later
// one) can never overwrite the result of a more recent request -- compareRevisionId and
// compareRevision are otherwise two independently-written refs with no other guard tying them together.
let compareRequestToken = 0

async function compareToRevision(revisionId: number): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return

  const requestToken = ++compareRequestToken
  compareRevisionId.value = revisionId
  compareStage.value = 'loading'
  compareError.value = null
  try {
    const revision = await getDocumentRevision(workspaceId, props.documentId, revisionId)
    if (requestToken !== compareRequestToken) return
    compareRevision.value = revision
    compareStage.value = 'loaded'
  } catch (error) {
    if (requestToken !== compareRequestToken) return
    compareStage.value = 'error'
    compareError.value = error instanceof ApiRequestError ? error.message : 'Could not load that revision.'
  }
}

const orderedRevisions = computed<DocumentRevisionResponse[]>(() => [...revisions.value].reverse())

const compareFieldIds = computed<string[]>(() => {
  if (!compareRevision.value || !document.value) return []
  return Array.from(
    new Set([...Object.keys(compareRevision.value.fields), ...Object.keys(document.value.currentRevision.fields)]),
  ).sort()
})

function fieldChanged(fieldId: string): boolean {
  if (!compareRevision.value || !document.value) return false
  return (
    fieldDisplayValue(compareRevision.value.fields[fieldId]) !==
    fieldDisplayValue(document.value.currentRevision.fields[fieldId])
  )
}

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
              <span>{{ fieldDisplayValue(field) }}</span>
            </div>
            <div v-if="field.fieldState" class="field-row__state">
              <span class="badge">{{ field.fieldState.review }}</span>
              <span class="badge">{{ field.fieldState.lock }}</span>
              <div class="field-row__actions">
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === fieldId"
                  :aria-label="`Accept ${fieldId}`"
                  @click="recordFieldReview(fieldId, 'ACCEPTED')"
                >
                  Accept
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === fieldId"
                  :aria-label="`Reject ${fieldId}`"
                  @click="recordFieldReview(fieldId, 'REJECTED')"
                >
                  Reject
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === fieldId"
                  :aria-label="`Mark ${fieldId} as needing clarification`"
                  @click="recordFieldReview(fieldId, 'NEEDS_CLARIFICATION')"
                >
                  Needs clarification
                </button>
                <button
                  class="button"
                  type="button"
                  :disabled="fieldActionPending === fieldId"
                  :aria-label="`${field.fieldState.lock === 'EXPLICITLY_LOCKED' ? 'Unlock' : 'Lock'} ${fieldId}`"
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
        <button
          ref="drawerToggleRef"
          type="button"
          class="button drawer-toggle"
          :aria-expanded="drawerOpen"
          aria-controls="inspector-drawer"
          @click="toggleDrawer"
        >
          {{ drawerOpen ? 'Hide details' : 'Show details' }}
        </button>

        <div
          id="inspector-drawer"
          ref="drawerRef"
          class="inspector-drawer"
          :class="{ 'inspector-drawer--collapsed': !drawerOpen }"
          role="region"
          aria-labelledby="inspector-drawer-heading"
          @keydown="onDrawerKeydown"
        >
          <h2 id="inspector-drawer-heading" ref="drawerHeadingRef" class="visually-hidden" tabindex="-1">
            Document details
          </h2>

          <div class="tab-strip" role="tablist" aria-label="Document inspector">
            <button
              v-for="(tab, index) in INSPECTOR_TABS"
              :key="tab"
              ref="tabButtonEls"
              type="button"
              role="tab"
              :aria-selected="activeTab === tab"
              :tabindex="activeTab === tab ? 0 : -1"
              class="tab-button"
              :class="{ 'tab-button--active': activeTab === tab }"
              @click="selectTab(tab)"
              @keydown="onTabKeydown($event, index)"
            >
              {{
                tab === 'assist'
                  ? 'Assist'
                  : tab === 'rules'
                    ? 'Rules'
                    : tab === 'sources'
                      ? 'Sources'
                      : tab === 'checks'
                        ? 'Checks'
                        : 'History'
              }}
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
                      aria-live="polite"
                    >
                      Some fields could not be applied automatically (conflicting or locked).
                    </p>
                  </div>
                </div>
              </template>
            </div>

            <div v-else-if="activeTab === 'checks'">
              <button
                class="button button--primary"
                type="button"
                :disabled="validationStage === 'validating'"
                @click="validateCurrentRevision"
              >
                {{ validationStage === 'validating' ? 'Validating…' : 'Validate this revision' }}
              </button>
              <p v-if="validationStage === 'validating'" aria-live="polite">Validating…</p>
              <p v-if="validationError" class="field-error" role="alert">{{ validationError }}</p>

              <template v-if="validationManifest">
                <p v-if="validationManifest.hasUnresolvedBlocking" class="field-error" role="alert">
                  Cannot export -- unresolved blocking findings
                </p>
                <p v-else aria-live="polite">Ready to export</p>

                <ul v-if="validationManifest.findings.length > 0" class="finding-list">
                  <li v-for="(finding, index) in validationManifest.findings" :key="index" class="finding-row">
                    <span class="badge">{{ finding.severity }}</span>
                    <span v-if="finding.fieldId" class="field-row__label">{{ finding.fieldId }}</span>
                    <span>{{ finding.message }}</span>
                  </li>
                </ul>
                <p v-else class="field-hint">No findings.</p>

                <template v-if="!validationManifest.hasUnresolvedBlocking">
                  <label class="field-label" for="export-format">Export format</label>
                  <select id="export-format" v-model="exportFormat">
                    <option value="DOCX">DOCX</option>
                    <option value="PDF">PDF</option>
                    <option value="BOTH">Both</option>
                  </select>
                  <button
                    class="button button--primary"
                    type="button"
                    :disabled="approvalStage === 'approving'"
                    @click="approveCurrentExport"
                  >
                    {{ approvalStage === 'approving' ? 'Approving…' : 'Approve for export' }}
                  </button>
                  <p v-if="approvalError" class="field-error" role="alert">{{ approvalError }}</p>
                </template>

                <div v-if="exportApproval">
                  <p class="field-hint">Approved for: {{ exportApproval.format }}</p>
                  <button
                    class="button button--primary"
                    type="button"
                    :disabled="exportStage === 'exporting'"
                    @click="exportApprovedDocument"
                  >
                    {{ exportStage === 'exporting' ? 'Exporting…' : 'Export' }}
                  </button>
                  <p v-if="exportError" class="field-error" role="alert">{{ exportError }}</p>
                </div>

                <div v-if="exportReceipt">
                  <p aria-live="polite">
                    {{ exportReceipt.isCompletePair ? 'Both files exported.' : 'Only one file could be exported.' }}
                  </p>
                  <ul class="source-list">
                    <li>
                      <a :href="artifactDownloadUrl(session.personalWorkspaceId!, exportReceipt.docxArtifactId)">Download DOCX</a>
                    </li>
                    <li v-if="exportReceipt.pdfArtifactId != null">
                      <a :href="artifactDownloadUrl(session.personalWorkspaceId!, exportReceipt.pdfArtifactId)">Download PDF</a>
                    </li>
                  </ul>
                </div>
              </template>
            </div>

            <div v-else-if="activeTab === 'history'">
              <p v-if="revisionsLoadState === 'loading'" aria-live="polite">Loading revision history…</p>
              <p v-if="revisionsError" class="field-error" role="alert">{{ revisionsError }}</p>

              <ul v-if="orderedRevisions.length > 0" class="revision-list">
                <li v-for="revision in orderedRevisions" :key="revision.id" class="revision-row">
                  <div class="revision-row__body">
                    <span class="field-row__label">Revision {{ revision.revisionNumber }}</span>
                    <span class="field-hint">{{ revision.editReason }}</span>
                    <span class="field-hint">{{ new Date(revision.createdAt).toLocaleString() }}</span>
                  </div>
                  <button
                    class="button"
                    type="button"
                    :disabled="compareStage === 'loading' && compareRevisionId === revision.id"
                    :aria-label="`Compare revision ${revision.revisionNumber} to current`"
                    @click="compareToRevision(revision.id)"
                  >
                    Compare to current
                  </button>
                </li>
              </ul>
              <p v-else-if="revisionsLoadState === 'loaded'" class="field-hint">No earlier revisions yet.</p>

              <div v-if="compareStage !== 'idle'" class="compare-panel">
                <p v-if="compareStage === 'loading'" aria-live="polite">Loading revision {{ compareRevisionId }}…</p>
                <p v-if="compareError" class="field-error" role="alert">{{ compareError }}</p>

                <template v-if="compareStage === 'loaded' && compareRevision && document">
                  <h3>Revision {{ compareRevision.revisionNumber }} vs. current</h3>
                  <div class="compare-row">
                    <span></span>
                    <div class="compare-row__values">
                      <span class="field-hint">Revision {{ compareRevision.revisionNumber }}</span>
                      <span class="field-hint">Current (revision {{ document.currentRevision.revisionNumber }})</span>
                    </div>
                  </div>
                  <div
                    v-for="fieldId in compareFieldIds"
                    :key="fieldId"
                    class="compare-row"
                    :class="{ 'compare-row--changed': fieldChanged(fieldId) }"
                  >
                    <span class="field-row__label">{{ fieldId }}</span>
                    <div class="compare-row__values">
                      <span>{{ fieldDisplayValue(compareRevision.fields[fieldId]) }}</span>
                      <span>{{ fieldDisplayValue(document.currentRevision.fields[fieldId]) }}</span>
                    </div>
                  </div>
                </template>
              </div>
            </div>

            <p v-else class="field-hint">Coming in a later phase.</p>
          </div>
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

/* Hidden above the 720px breakpoint -- the inspector pane is a normal, always-visible column there. */
.drawer-toggle {
  display: none;
}

@media (max-width: 720px) {
  .workspace-layout {
    grid-template-columns: 1fr;
  }

  .compare-row {
    grid-template-columns: 1fr;
  }

  .compare-row__values {
    grid-template-columns: 1fr;
  }

  .drawer-toggle {
    display: inline-flex;
    margin-bottom: var(--space-4);
  }

  .inspector-drawer--collapsed {
    display: none;
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

.finding-list {
  list-style: none;
  margin: var(--space-3) 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.finding-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-border);
}

.revision-list {
  list-style: none;
  margin: var(--space-3) 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.revision-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2) var(--space-4);
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.revision-row__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.compare-panel {
  margin-top: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.compare-row {
  display: grid;
  grid-template-columns: 1fr 2fr;
  align-items: center;
  gap: var(--space-2) var(--space-4);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--color-border);
}

.compare-row__values {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-2);
}

.compare-row--changed {
  background: var(--color-honey-soft);
  border-radius: var(--radius);
}

.compare-row--changed .compare-row__values span {
  font-weight: 600;
}
</style>
