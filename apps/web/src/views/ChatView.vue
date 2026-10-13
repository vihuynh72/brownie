<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRaw, watch } from 'vue'
import { RouterLink, isNavigationFailure, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import ChoiceList from '@/components/workspace/ChoiceList.vue'
import {
  ApiRequestError,
  allocateUpload,
  attachDocumentSource,
  completeUpload,
  createDocument,
  extractArtifact,
  listDocuments,
  uploadArtifactContent,
  type ArtifactResponse,
  type DocumentResponse,
  type DocumentSourceResponse,
  type DocumentSummaryResponse,
} from '@/api/client'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { suggestTemplates } from '@/chat/templateMatch'
import { documentHandoffState } from '@/router/handoff'
import { useSessionStore } from '@/stores/session'
import { documentTitleFor, useTemplatesStore } from '@/stores/templates'

/**
 * Where a conversation with Brownie starts: say what you need filled, and Brownie shows what it would do.
 *
 * Brownie's assistant reads and changes one document, so this page does not hold that conversation itself. It
 * gets one started. It suggests one of your templates by comparing the words of your request with the words in
 * the templates' names (it does not understand the request, and says so when the match is weak), lays out its
 * plan, and makes nothing until you agree. Agreeing creates the document from the template, attaches the file
 * you added, and opens the document with the request alongside, which is where the reading happens and where
 * every value is approved.
 *
 * The turns of the conversation live only in this page. Nothing is kept on the server until you agree to a
 * plan, apart from a file you add, which is uploaded the moment you choose it, exactly as on the document page.
 */
const session = useSessionStore()
const templates = useTemplatesStore()
const router = useRouter()

/** What a new document's first revision gives as the reason it exists. */
const INITIAL_REVISION_REASON = 'Started from a request in the chat.'

// ---- What the page offers before any conversation ----------------------------------------------

const documents = ref<DocumentSummaryResponse[]>([])
const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

const recent = computed(() =>
  [...documents.value].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)).slice(0, 6),
)

async function loadDocuments(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  loadState.value = 'loading'
  try {
    documents.value = await listDocuments(workspaceId)
    loadState.value = 'loaded'
  } catch {
    loadState.value = 'error'
  }
}

/** The templates are what Brownie suggests from; the sidebar loads them too, and the store asks the server only once. */
function loadTemplates(): void {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId !== undefined) void templates.load(workspaceId)
}

onMounted(() => {
  void loadDocuments()
  loadTemplates()
})
watch(
  () => session.personalWorkspaceId,
  (workspaceId) => {
    if (workspaceId === undefined) return
    void loadDocuments()
    loadTemplates()
  },
)

/** Known to be empty, so the opening can say so before anyone has to ask. */
const noTemplates = computed(() => templates.status === 'loaded' && templates.usable.length === 0)

// ---- The conversation ---------------------------------------------------------------------------

/** A template as a plan holds it: what to call it, and which version a new document is made from. */
interface PlanTemplate {
  id: number
  name: string
  versionId: number
}

/** A file added with +, accepted by the server and waiting to be attached to the document. */
interface AttachedFile {
  artifactId: number
  name: string
}

/** What Brownie would do, as it is laid out for the person to agree to or change. */
interface Plan {
  /** What Brownie says before showing it. */
  intro: string
  /** The sentence the plan answers. */
  request: string
  template: PlanTemplate
  /** The other templates that could be used, the best fit first. */
  others: PlanTemplate[]
  /** Set when the template was Brownie's pick among several and the words gave little to go on; null when it was clear or the person's own choice. */
  doubt: 'tied' | 'none' | null
  file: AttachedFile | null
  /** What the new document would be called. */
  title: string
  /** All the other templates are on offer in place of the usual choices. */
  choosing: boolean
}

/** Why starting the document stopped, in words, and what the person can do about it. */
interface Failure {
  text: string
  retry: boolean
  /** The document exists but its file could not be attached: it can be opened without it. */
  openWithout: boolean
}

/** One try at making the document and opening it. It remembers how far it got, so trying again never makes a second one. */
interface Start {
  plan: Plan
  /** Sent with the creation, so a retry after an answer was lost makes no second document. */
  key: string
  step: 'creating' | 'attaching' | 'opening'
  failure: Failure | null
  document: DocumentResponse | null
  source: DocumentSourceResponse | null
  /** The person chose to open the document without the file that could not be attached. */
  withoutFile: boolean
}

type Turn =
  | { id: number; kind: 'file'; name: string; what: string; size: string | null; uploading: boolean }
  | { id: number; kind: 'person'; text: string }
  | { id: number; kind: 'plan'; plan: Plan }
  | { id: number; kind: 'start'; start: Start }
  /** Something that went wrong before a plan could be made, with the request to plan again when trying again can help. */
  | { id: number; kind: 'problem'; text: string; retryRequest: string | null }
  | { id: number; kind: 'no-templates' }

type PlanTurn = Extract<Turn, { kind: 'plan' }>
type StartTurn = Extract<Turn, { kind: 'start' }>
type FileTurn = Extract<Turn, { kind: 'file' }>
type NewTurn = Turn extends infer T ? (T extends { id: number } ? Omit<T, 'id'> : never) : never

const turns = ref<Turn[]>([])
let lastTurnId = 0

/** Adds a turn at the end and hands back the one the page shows, so changes made to it afterwards are seen. */
function add(turn: NewTurn): Turn {
  turns.value.push({ ...turn, id: (lastTurnId += 1) } as Turn)
  return turns.value[turns.value.length - 1]!
}

/**
 * The newest turn that can still be acted on. Whatever came before it has been answered or passed by, except that a
 * file chip or a problem with nothing to retry after a plan does not answer it: a refused file leaves the plan as it was.
 */
function liveTurn(): Turn | null {
  for (let index = turns.value.length - 1; index >= 0; index -= 1) {
    const turn = turns.value[index]!
    if (turn.kind !== 'file' && !(turn.kind === 'problem' && turn.retryRequest === null)) return turn
  }
  return null
}

function isLive(turn: Turn): boolean {
  return liveTurn()?.id === turn.id
}

/** The file the next plan would read: the last one added and accepted. */
const attached = ref<AttachedFile | null>(null)
const uploading = ref(false)
const planning = ref(false)
const starting = ref(false)
const busy = computed(() => planning.value || starting.value)
/** A plan's controls wait for whatever is under way, and for a file on its way, which may change the plan. */
const locked = computed(() => busy.value || uploading.value)
/** Waiting on the templates, which is the one wait long enough to be seen. */
const lookingAtTemplates = computed(() => planning.value && templates.status === 'loading')

/** Set when the person has gone to another page, so nothing opens over where they went. */
let leftPage = false
onBeforeUnmount(() => {
  leftPage = true
})

const logRef = ref<HTMLElement | null>(null)
const dockRef = ref<HTMLElement | null>(null)

/**
 * The message box is pinned over the bottom of the window, so that strip must not count as in view when the
 * browser scrolls something into sight, or to a control reached with the keyboard; otherwise such a control can
 * be left entirely hidden under the box. The strip is as tall as the box, which grows with what is typed.
 */
function keepClearOfDock(): void {
  const dock = dockRef.value
  const page = document.documentElement.style
  if (dock !== null && window.getComputedStyle(dock).position === 'sticky') page.setProperty('scroll-padding-block-end', `${dock.offsetHeight}px`)
  else page.removeProperty('scroll-padding-block-end')
}

let dockObserver: ResizeObserver | null = null
onMounted(() => {
  keepClearOfDock()
  window.addEventListener('resize', keepClearOfDock)
  if (dockRef.value && typeof ResizeObserver !== 'undefined') {
    dockObserver = new ResizeObserver(keepClearOfDock)
    dockObserver.observe(dockRef.value)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', keepClearOfDock)
  dockObserver?.disconnect()
  document.documentElement.style.removeProperty('scroll-padding-block-end')
})

function lastTurnElement(): HTMLElement | null {
  return (logRef.value?.lastElementChild as HTMLElement | null) ?? null
}

function reducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * Brings something into view above the message box that is pinned over the bottom of the window: all of it when
 * it fits in what is left, otherwise its top, so the start of a long reply is never scrolled out of sight.
 */
function reveal(element: HTMLElement | null): void {
  if (!element) return
  const room = window.innerHeight - (dockRef.value?.offsetHeight ?? 0)
  element.scrollIntoView?.({ block: element.offsetHeight > room ? 'start' : 'end', behavior: reducedMotion() ? 'auto' : 'smooth' })
}

watch(() => turns.value.length, () => reveal(lastTurnElement()), { flush: 'post' })

/**
 * After a choice replaces the list the person was in, focus goes to the list that replaces it rather than falling
 * to the page. Only the live turn draws a list of choices, so the first one in the conversation is the one.
 */
async function focusChoices(): Promise<void> {
  await nextTick()
  logRef.value?.querySelector<HTMLElement>('.choices__option')?.focus()
}

// ---- The words ----------------------------------------------------------------------------------

const DOUBT_WORDS = {
  tied: 'I picked the closest template to your words; change it if it is wrong.',
  none: 'No template name matches your words, so I picked your newest one; change it if it is wrong.',
} as const

function planOptions(plan: Plan): string[] {
  const other = plan.others[0]
  return other ? ['Start the document', `Use ${other.name} instead`] : ['Start the document']
}

function nextSteps(plan: Plan): string[] {
  return [
    plan.file
      ? 'I start the document and read the file you attached.'
      : 'I start the document. You can add notes or a transcript there, and I read them.',
    'I ask you about anything missing or unclear.',
    'I show you what I found. Nothing is filled until you approve it.',
  ]
}

function progressWords(start: Start): string {
  switch (start.step) {
    case 'creating':
      return `Starting “${start.plan.title}”…`
    case 'attaching':
      return `Attaching ${start.plan.file?.name ?? 'your file'}…`
    case 'opening':
      return 'Opening the document…'
  }
}

/**
 * Why a request failed, as the last sentence of a line. Never the error's own message: for an answer with no
 * explanation that is "Request failed with status 502".
 */
function reasonFor(error: unknown, what: string): string {
  return (
    describeCommonFailure(error, what) ?? (error instanceof ApiRequestError ? error.problem?.detail : undefined) ?? 'Try again.'
  )
}

// ---- Adding a file --------------------------------------------------------------------------------

const fileInputRef = ref<HTMLInputElement | null>(null)
/** The file on its way, so a request typed meanwhile waits for it instead of planning without it. */
let uploadUnderWay: Promise<void> | null = null

function chooseFile(): void {
  if (!canAttach()) return
  fileInputRef.value?.click()
}

function onFileChosen(event: Event): Promise<void> | undefined {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // Cleared at once, so choosing the same file again after a refusal is still a change.
  input.value = ''
  const workspaceId = session.personalWorkspaceId
  if (!file || workspaceId === undefined || !canAttach()) return undefined
  const work = attachFile(workspaceId, file).finally(() => {
    uploadUnderWay = null
  })
  uploadUnderWay = work
  return work
}

/** Why a finished upload was not accepted. The reasons are the scanner's and the content inspector's own codes. */
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

/** The deployment's limit in bytes, or null when the server did not say (an older one) or could not be asked. */
async function uploadLimitBytes(): Promise<number | null> {
  try {
    const { maxUploadBytes } = await loadCapabilities()
    return typeof maxUploadBytes === 'number' && maxUploadBytes > 0 ? maxUploadBytes : null
  } catch {
    return null
  }
}

/** Why an upload the server refused was not attached, in the words the document page uses for the same refusals. */
async function uploadFailureMessage(error: unknown, file: File): Promise<string> {
  if (error instanceof ApiRequestError && error.status === 413) {
    const limit = await uploadLimitBytes()
    const limitText = limit !== null && file.size > limit ? formatBytes(limit) : null
    if (limitText) return `That file was not attached: it is larger than the ${limitText} upload limit.`
    return error.problem?.detail
      ? `That file was not attached. ${error.problem.detail}`
      : 'That file was not attached: it is larger than Brownie accepts.'
  }
  if (error instanceof ApiRequestError && error.status === 415) {
    return 'That file was not attached: Brownie cannot use that kind of file as a source. Attach a plain-text (.txt) file.'
  }
  const why = describeCommonFailure(error, 'a way to attach sources')
  return why ? `That file was not attached. ${why}` : 'Could not attach that file. Try again.'
}

/** What kind of file the chip says it is: only what the server confirmed, or a name that says so on an older server. */
function kindWords(artifact: ArtifactResponse, file: File): string {
  const detected = artifact.detectedMediaType
  const looksLikeText = /\.txt$/i.test(file.name) || file.type === 'text/plain'
  return detected === 'PLAIN_TEXT' || (detected == null && looksLikeText) ? 'Text file' : 'File'
}

/**
 * The same steps as adding a source on a document -- upload, scan, read its structure -- stopping short of the last
 * one, because there is no document to attach it to until a plan is agreed. The chip shows at once and is taken
 * back, with the reason, if the file is refused.
 */
async function attachFile(workspaceId: number, file: File): Promise<void> {
  uploading.value = true
  const chip = add({ kind: 'file', name: file.name, what: 'File', size: formatBytes(file.size), uploading: true }) as FileTurn
  let accepted = false
  try {
    const allocated = await allocateUpload(workspaceId, file.name)
    await uploadArtifactContent(workspaceId, allocated.id, file)
    const completed = await completeUpload(workspaceId, allocated.id)
    if (completed.status !== 'READY') {
      refuseFile(chip, `That file was not attached: ${whyFileWasRefused(completed)}.`)
      return
    }
    await extractArtifact(workspaceId, allocated.id)
    const name = completed.displayFilename ?? allocated.displayFilename ?? file.name
    attached.value = { artifactId: allocated.id, name }
    chip.name = name
    chip.what = kindWords(completed, file)
    chip.uploading = false
    accepted = true
  } catch (error) {
    refuseFile(chip, await uploadFailureMessage(error, file))
  } finally {
    uploading.value = false
  }
  if (!accepted) return
  // The file chooser gave focus back to the + (or to nothing); what comes next is saying what is needed.
  const active = document.activeElement
  if (active === null || active === document.body || active.closest('.chat__attach') !== null) composerRef.value?.focus()
  await replanWithFile()
}

/** Takes the chip back and says why, since a file Brownie does not have must not look as though it does. */
function refuseFile(chip: FileTurn, text: string): void {
  turns.value = turns.value.filter((turn) => turn.id !== chip.id)
  add({ kind: 'problem', text, retryRequest: null })
}

/**
 * The plan on show: the live plan, or the one whose try at starting stopped before anything was made. Once a
 * document exists a new plan would only make a second one, so then there is none.
 */
function livePlan(): Plan | null {
  const live = liveTurn()
  if (live?.kind === 'plan') return live.plan
  if (live?.kind === 'start' && live.start.failure !== null && live.start.document === null) return live.start.plan
  return null
}

/** Not while a document is being made, or after one was made and only its file is missing: the file goes in the document then. */
function canAttach(): boolean {
  const live = liveTurn()
  return !uploading.value && !starting.value && !(live?.kind === 'start' && live.start.document !== null)
}

/** A plan made before the file was added did not include it, so a new one does, with the template as it stood. */
async function replanWithFile(): Promise<void> {
  const live = livePlan()
  if (!live || busy.value) return
  planning.value = true
  try {
    await planFor(live.request, { intro: 'Thanks, I have it. Here is my updated plan:', template: live.template, doubt: live.doubt })
  } finally {
    planning.value = false
  }
}

// ---- Sending a request and planning ---------------------------------------------------------------

const composerText = ref('')
const composerRef = ref<HTMLTextAreaElement | null>(null)
const canSend = computed(() => composerText.value.trim() !== '' && !busy.value)

function onComposerKeydown(event: KeyboardEvent): void {
  // Enter sends and Shift+Enter starts a new line, as in most chat boxes; a key that finishes an input-method
  // composition (Japanese, Chinese, Korean) is left to finish it.
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void sendMessage()
}

/** One line from the person, from the message box or the "Other" line of a choice, and Brownie's plan for it. */
async function sendMessage(given?: string): Promise<void> {
  const text = (given ?? composerText.value).trim()
  if (text === '' || busy.value) return
  if (given === undefined) composerText.value = ''
  add({ kind: 'person', text })
  planning.value = true
  try {
    // A file still on its way is part of the plan, so the plan waits for it.
    await uploadUnderWay
    await planFor(text)
  } finally {
    planning.value = false
  }
  if (given !== undefined) await focusChoices()
}

interface PlanOptions {
  intro?: string
  /** The template to plan with, when the person chose it or the plan is only being brought up to date. */
  template?: PlanTemplate
  doubt?: Plan['doubt']
}

/** Adds Brownie's plan for the request, or says why there can be none. */
async function planFor(request: string, options: PlanOptions = {}): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) return
  if (templates.status !== 'loaded') await templates.refresh(workspaceId)
  if (templates.status !== 'loaded') {
    add({ kind: 'problem', text: 'I could not look at your templates just now, so I cannot suggest one. Try again in a moment.', retryRequest: request })
    return
  }
  const { ranked, certainty } = suggestTemplates(request, templates.usable)
  const choices = ranked.flatMap((template): PlanTemplate[] =>
    template.currentActiveVersionId == null ? [] : [{ id: template.id, name: template.displayName, versionId: template.currentActiveVersionId }],
  )
  if (choices.length === 0) {
    add({ kind: 'no-templates' })
    return
  }
  const given = options.template ? choices.find((choice) => choice.id === options.template?.id) : undefined
  const template = given ?? choices[0]!
  // No template name shares a word with the request, and nobody picked one: the request might not be about
  // filling in a document at all, so the opening says that instead of claiming an understanding Brownie lacks.
  const unsureOfIntent = !given && certainty === 'none'
  add({
    kind: 'plan',
    plan: {
      intro:
        options.intro ??
        (unsureOfIntent
          ? "I'm not sure that's something to fill in, but if it is, here's my best guess:"
          : 'I can do that in a new document. Here is my plan:'),
      request,
      template,
      others: choices.filter((choice) => choice.id !== template.id),
      // With one template there was nothing to choose between, and a template the person chose is not a guess.
      doubt: given ? (options.doubt ?? null) : choices.length > 1 && certainty !== 'clear' ? certainty : null,
      file: attached.value,
      title: documentTitleFor(template.name),
      choosing: false,
    },
  })
}

async function retryPlanning(request: string): Promise<void> {
  if (busy.value) return
  planning.value = true
  try {
    await planFor(request)
  } finally {
    planning.value = false
  }
  await focusChoices()
}

// ---- Answering a plan -----------------------------------------------------------------------------

function onPlanChoose(turn: PlanTurn, index: number): void {
  if (locked.value) return
  if (index === 0) {
    void startDocument(turn.plan)
    return
  }
  const other = turn.plan.others[0]
  if (other) void useTemplate(turn.plan, other, `Use ${other.name} instead`)
}

/** The person chose another template: said back to them as they would say it, then a plan that uses it. */
async function useTemplate(plan: Plan, template: PlanTemplate, said: string): Promise<void> {
  if (locked.value) return
  add({ kind: 'person', text: said })
  planning.value = true
  try {
    await planFor(plan.request, { intro: 'Here is my updated plan:', template })
  } finally {
    planning.value = false
  }
  await focusChoices()
}

async function toggleChoosing(turn: PlanTurn): Promise<void> {
  turn.plan.choosing = !turn.plan.choosing
  await nextTick()
  if (turn.plan.choosing) {
    // The list that takes the place of the usual choices is taller, and must not end up under the message box.
    reveal(logRef.value?.querySelector<HTMLElement>('.chat-plan__picker') ?? null)
    logRef.value?.querySelector<HTMLElement>('.chat-plan__picker .choices__option')?.focus()
  } else {
    logRef.value?.querySelector<HTMLElement>('.chat-plan__change')?.focus()
  }
}

function onPickTemplate(turn: PlanTurn, index: number): void {
  const template = turn.plan.others[index]
  if (template) void useTemplate(turn.plan, template, `Use ${template.name}`)
}

// ---- Starting the document ------------------------------------------------------------------------

async function startDocument(plan: Plan): Promise<void> {
  if (locked.value) return
  add({ kind: 'person', text: 'Start the document' })
  const turn = add({
    kind: 'start',
    start: { plan, key: crypto.randomUUID(), step: 'creating', failure: null, document: null, source: null, withoutFile: false },
  }) as StartTurn
  // The choice that was pressed has gone with its plan; focus waits on the line that says what is happening.
  await nextTick()
  lastTurnElement()?.focus()
  await runStart(turn)
}

/** Why the template cannot start a document: moved to the Trash Bin from somewhere else, or gone altogether. */
function templateGone(error: unknown): 'trashed' | 'missing' | null {
  if (!(error instanceof ApiRequestError)) return null
  if (error.status === 409 && error.problem?.code === 'TEMPLATE_TRASHED') return 'trashed'
  return brownieSaysNotThere(error) ? 'missing' : null
}

/**
 * Makes the document, attaches the file, and opens it, each step only once however often this is called: a try
 * that stopped partway carries on from where it stopped. Whatever goes wrong becomes words and, where trying
 * again can help, a button.
 */
async function runStart(turn: StartTurn): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || starting.value) return
  const start = turn.start
  const { plan } = start
  starting.value = true
  start.failure = null
  try {
    if (start.document === null) {
      start.step = 'creating'
      try {
        start.document = await createDocument(workspaceId, start.key, {
          title: plan.title,
          templateId: plan.template.id,
          templateVersionId: plan.template.versionId,
          fields: {},
          initialRevisionReason: INITIAL_REVISION_REASON,
        })
      } catch (error) {
        await creationFailed(start, error)
        return
      }
    }
    if (plan.file !== null && start.source === null && !start.withoutFile) {
      start.step = 'attaching'
      try {
        start.source = await attachDocumentSource(workspaceId, start.document.id, plan.file.artifactId)
      } catch (error) {
        start.failure = {
          text: `I started “${plan.title}”, but could not attach ${plan.file.name}. ${reasonFor(error, 'a way to attach sources')}`,
          retry: true,
          openWithout: true,
        }
        await focusFailure()
        return
      }
    }
    start.step = 'opening'
    await openDocument(start)
  } finally {
    starting.value = false
  }
}

/** The template could not start the document: said so, and when it is gone for good, a new plan with what is left. */
async function creationFailed(start: Start, error: unknown): Promise<void> {
  const gone = templateGone(error)
  const { plan } = start
  if (gone === null) {
    start.failure = { text: `I could not start the document. ${reasonFor(error, 'a way to create documents')}`, retry: true, openWithout: false }
    await focusFailure()
    return
  }
  // The same list the sidebar shows, so it stops offering a template that cannot be used.
  if (gone === 'trashed') templates.markTrashed(plan.template.id)
  else templates.forget(plan.template.id)
  start.failure = {
    text: `“${plan.template.name}” ${gone === 'trashed' ? 'is in the Trash Bin now' : 'is not there any more'}, so I cannot start a document from it. Nothing was made.`,
    retry: false,
    openWithout: false,
  }
  await planFor(plan.request, { intro: 'Here is a new plan:' })
  await focusChoices()
}

/**
 * Opens the document the request started, with the request and the file along, so the document can show what was
 * asked and read the file. When the person chose to go on without a file that could not be attached, a warning
 * goes instead of the file.
 */
async function openDocument(start: Start): Promise<void> {
  const document = start.document
  if (document === null || leftPage) return
  // The browser's history can only keep plain data, and what the page holds is wrapped for tracking changes: a
  // wrapped object is refused, and the router then loads the address afresh, which drops everything it carried.
  const source = start.source === null ? null : toRaw(start.source)
  const left = start.withoutFile ? start.plan.file : null
  const warning = left ? `${left.name} was not attached, so Brownie has nothing to read yet. You can add it again with Add a source.` : null
  try {
    const failure = await router.push({
      path: `/documents/${document.id}`,
      state: documentHandoffState({
        attachedSources: source ? [source] : [],
        sourceWarning: warning,
        formNotes: [],
        request: { text: start.plan.request, sourceId: source?.id ?? null },
      }),
    })
    if (isNavigationFailure(failure)) throw failure
  } catch {
    start.failure = { text: `I started “${start.plan.title}”, but it did not open.`, retry: true, openWithout: false }
    await focusFailure()
  }
}

/** The file could not be attached and the person would rather go on without it: the document is already made, so it is only opened. */
function openWithoutFile(turn: StartTurn): Promise<void> {
  if (starting.value) return Promise.resolve()
  turn.start.withoutFile = true
  return runStart(turn)
}

/** A failure that offers a button has the button focused, so a keyboard needs no search for the way on. */
async function focusFailure(): Promise<void> {
  await nextTick()
  logRef.value?.querySelector<HTMLElement>('.chat__retry')?.focus()
}
</script>

<template>
  <div class="chat page-column">
    <div class="chat__head page-head page-head--center">
      <h1 class="page-title page-title--small">Chat with Brownie</h1>
      <p class="page-lede page-lede--small chat__lede">
        Tell Brownie what you need filled. It suggests a template, shows its plan, and starts only when you agree.
      </p>
    </div>

    <div class="chat__conversation">
      <div v-if="turns.length === 0" class="chat__start">
        <div class="msg--brownie">
          <p class="msg__who">Brownie</p>
          <div class="msg__body">
            <p>
              Tell me what you need filled in. Attach notes or a transcript with +, and I will show you a plan before I
              start anything.
            </p>
            <p v-if="noTemplates">
              You don’t have a template to start from yet. <RouterLink to="/">Upload a form on Home</RouterLink> and I
              can start documents from it.
            </p>
          </div>
        </div>

        <div class="chat__recent">
          <h2 class="chat__recent-title">Open a document</h2>
          <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading documents…</p>
          <p v-else-if="loadState === 'error'" class="field-error" role="alert">
            Something went wrong loading your documents. Try reloading the page.
          </p>
          <p v-else-if="recent.length === 0" class="field-hint">You don’t have any documents yet.</p>
          <ul v-else class="chat__list">
            <li v-for="document in recent" :key="document.id">
              <RouterLink class="chat__row" :to="`/documents/${document.id}`">
                <span class="doc-tile doc-tile--medium" aria-hidden="true"></span>
                <span class="chat__row-title">{{ document.title }}</span>
              </RouterLink>
            </li>
          </ul>
        </div>
      </div>

      <!-- Always on the page, so what is added to it is read out as it arrives. -->
      <div ref="logRef" class="chat__log" role="log" aria-label="Conversation with Brownie">
        <template v-for="turn in turns" :key="turn.id">
          <div v-if="turn.kind === 'file'" class="chat__turn chat__turn--person">
            <div class="source-chip">
              <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="document-text" :size="18" /></span>
              <span class="source-chip__text">
                <span class="source-chip__name"><span class="visually-hidden">You attached </span>{{ turn.name }}</span>
                <span class="source-chip__meta">
                  <template v-if="turn.uploading"><span class="activity-indicator" aria-hidden="true"></span> Uploading…</template>
                  <template v-else>
                    {{ turn.what }}<template v-if="turn.size"><span aria-hidden="true"> • </span><span class="visually-hidden">, </span>{{ turn.size }}</template>
                  </template>
                </span>
              </span>
            </div>
          </div>

          <p v-else-if="turn.kind === 'person'" class="chat__turn msg--user">
            <span class="visually-hidden">You said: </span>{{ turn.text }}
          </p>

          <div v-else-if="turn.kind === 'plan'" class="chat__turn msg--brownie">
            <p class="msg__who">Brownie</p>
            <div class="msg__body">
              <p>{{ turn.plan.intro }}</p>
            </div>

            <div class="msg-card chat-plan" role="group" aria-label="My plan for the new document">
              <dl class="chat-plan__rows">
                <div class="msg-card__section chat-plan__row">
                  <dt class="chat-plan__label">Template</dt>
                  <dd class="chat-plan__value">
                    <span class="doc-tile doc-tile--small" aria-hidden="true"><AppIcon name="layout" :size="14" /></span>
                    <span class="chat-plan__text">
                      <span class="chat-plan__strong">{{ turn.plan.template.name }}</span>
                      <span v-if="turn.plan.doubt" class="chat-plan__hint">{{ DOUBT_WORDS[turn.plan.doubt] }}</span>
                    </span>
                    <button
                      v-if="turn.plan.others.length > 0 && isLive(turn)"
                      type="button"
                      class="button button--quiet button--small chat-plan__change"
                      :aria-expanded="turn.plan.choosing"
                      @click="toggleChoosing(turn)"
                    >
                      Change<span class="visually-hidden"> template</span>
                    </button>
                  </dd>
                </div>
                <div class="msg-card__section chat-plan__row">
                  <dt class="chat-plan__label">Brownie reads</dt>
                  <dd class="chat-plan__value">
                    <template v-if="turn.plan.file">
                      <span class="doc-tile doc-tile--small" aria-hidden="true"><AppIcon name="document-text" :size="14" /></span>
                      <span class="chat-plan__text">{{ turn.plan.file.name }}, and nothing else</span>
                    </template>
                    <span v-else class="chat-plan__text">No source yet: you can add one in the document</span>
                  </dd>
                </div>
                <div class="msg-card__section chat-plan__row">
                  <dt class="chat-plan__label">New document</dt>
                  <dd class="chat-plan__value"><span class="chat-plan__text">{{ turn.plan.title }}</span></dd>
                </div>
              </dl>
              <div class="msg-card__section msg-card__section--soft chat-plan__next">
                <p :id="`plan-next-${turn.id}`" class="chat-plan__label">What happens next</p>
                <ol class="chat-plan__steps" role="list" :aria-labelledby="`plan-next-${turn.id}`">
                  <li v-for="(step, index) in nextSteps(turn.plan)" :key="index">
                    <span class="chat-plan__number" aria-hidden="true">{{ index + 1 }}</span>
                    <span>{{ step }}</span>
                  </li>
                </ol>
              </div>
            </div>

            <template v-if="isLive(turn)">
              <ChoiceList
                v-if="!turn.plan.choosing"
                :options="planOptions(turn.plan)"
                name="Your choice about my plan"
                :id-prefix="`plan-choice-${turn.id}`"
                :busy="locked"
                @choose="(index) => onPlanChoose(turn, index)"
                @other="(text) => sendMessage(text)"
              />
              <div v-else class="chat-plan__picker" @keydown.esc="toggleChoosing(turn)">
                <p class="chat-plan__picker-title">Which template should I use?</p>
                <ChoiceList
                  :options="turn.plan.others.map((other) => other.name)"
                  name="The templates I can use"
                  :id-prefix="`plan-pick-${turn.id}`"
                  :allow-other="false"
                  :busy="locked"
                  @choose="(index) => onPickTemplate(turn, index)"
                />
                <button type="button" class="button button--quiet button--small chat-plan__keep" @click="toggleChoosing(turn)">
                  Keep {{ turn.plan.template.name }}
                </button>
              </div>
            </template>
          </div>

          <div v-else-if="turn.kind === 'start'" class="chat__turn msg--brownie chat__start-turn" tabindex="-1">
            <p class="msg__who">Brownie</p>
            <div class="msg__body">
              <p v-if="turn.start.failure === null" class="chat__progress">
                <span class="activity-indicator" aria-hidden="true"></span>{{ progressWords(turn.start) }}
              </p>
              <template v-else>
                <p class="chat__problem"><AppIcon name="alert-circle" :size="18" /><span>{{ turn.start.failure.text }}</span></p>
                <div v-if="turn.start.failure.retry && isLive(turn)" class="chat__actions">
                  <button type="button" class="button button--secondary chat__retry" :aria-disabled="starting" @click="runStart(turn)">
                    Try again
                  </button>
                  <button
                    v-if="turn.start.failure.openWithout"
                    type="button"
                    class="button button--quiet"
                    :aria-disabled="starting"
                    @click="openWithoutFile(turn)"
                  >
                    Open the document without it
                  </button>
                </div>
              </template>
            </div>
          </div>

          <div v-else-if="turn.kind === 'no-templates'" class="chat__turn msg--brownie">
            <p class="msg__who">Brownie</p>
            <div class="msg__body">
              <p>
                You don’t have a template to start from yet, so I have nothing to plan with.
                <RouterLink to="/">Upload a form on Home</RouterLink> and I can start documents from it.
              </p>
            </div>
          </div>

          <div v-else class="chat__turn msg--brownie">
            <p class="msg__who">Brownie</p>
            <div class="msg__body">
              <p class="chat__problem"><AppIcon name="alert-circle" :size="18" /><span>{{ turn.text }}</span></p>
              <div v-if="turn.retryRequest !== null && isLive(turn)" class="chat__actions">
                <button type="button" class="button button--secondary chat__retry" :aria-disabled="busy" @click="retryPlanning(turn.retryRequest)">
                  Try again
                </button>
              </div>
            </div>
          </div>
        </template>

        <div v-if="lookingAtTemplates" class="chat__turn msg--brownie">
          <p class="msg__who">Brownie</p>
          <div class="msg__body">
            <p class="chat__progress"><span class="activity-indicator" aria-hidden="true"></span>Looking at your templates…</p>
          </div>
        </div>
      </div>
    </div>

    <div ref="dockRef" class="chat__dock">
      <form class="composer" @submit.prevent="sendMessage()">
        <label class="visually-hidden" for="chat-composer">How may I help you?</label>
        <textarea
          id="chat-composer"
          ref="composerRef"
          v-model="composerText"
          class="composer__input"
          rows="1"
          maxlength="1000"
          placeholder="How may I help you?"
          @keydown="onComposerKeydown"
        ></textarea>
        <div class="composer__tools">
          <button
            type="button"
            class="icon-button chat__attach"
            title="Plain-text files (.txt)"
            :aria-disabled="!canAttach()"
            @click="chooseFile"
          >
            <AppIcon name="plus" :size="24" />
            <span class="visually-hidden">Attach notes or a transcript</span>
          </button>
          <input
            ref="fileInputRef"
            class="visually-hidden"
            type="file"
            accept=".txt,text/plain"
            tabindex="-1"
            aria-hidden="true"
            @change="onFileChosen"
          />
          <button type="submit" class="composer__send" :aria-disabled="!canSend">
            <AppIcon name="send" :size="20" />
            <span class="visually-hidden">Send</span>
          </button>
        </div>
      </form>
      <p class="chat__disclaimer">Brownie can make mistakes. It never fills, sends or saves anything without your approval.</p>
    </div>
  </div>
</template>

<style scoped>
/*
 * The column is the whole height of the window under the shell's padding, so the message box rests at the bottom
 * even before there is a conversation to push it there. It reaches down over the shell's bottom padding too,
 * which lets the box sit flush with the window's edge while it is pinned. The shell's own gutters are read, not
 * repeated, so the sum stays right wherever the shell moves them.
 */
.chat {
  --page-gutter: 0px;
  display: flex;
  flex-direction: column;
  min-block-size: calc(100dvh - var(--shell-bar-row, 0px) - var(--shell-bar-block, 0px) - var(--shell-gutter-block-start, 1.5rem));
  padding-block: var(--space-4) 0;
  margin-block-end: calc(-1 * var(--shell-gutter-block-end, 3rem));
}

.chat__head {
  margin-block-end: 3rem;
}

.chat__lede {
  margin-block: 0.375rem 0;
}

/* The room under the last turn is the conversation's own, not the message box's, so the fade above the box never covers it. */
.chat__conversation {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  padding-block-end: var(--space-4);
}

.chat__log {
  display: flex;
  flex-direction: column;
  gap: 0.875rem;
}

/* The room left around whatever is scrolled into view; the pinned message box's own height is kept clear in the script. */
.chat__turn {
  margin-block: 0;
  scroll-margin-block: var(--space-4);
}

.chat__log :deep(:is(a, button, input)) {
  scroll-margin-block-end: var(--space-2);
}

.chat__turn--person {
  align-self: flex-end;
  max-inline-size: 100%;
}

/* The line that says what is under way takes focus, so it is shown the way a button is, not with a box around it. */
.chat__start-turn:focus-visible {
  box-shadow: none;
  outline: none;
}

.chat__progress,
.chat__problem {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
}

.chat__progress .activity-indicator {
  /* Level with the first line of text, whose line box is a little taller than the ring. */
  margin-block-start: 0.4em;
}

.chat__problem > svg {
  margin-block-start: 0.1875rem;
  color: var(--color-error);
}

.chat__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

/* ---- Before a conversation: Brownie's opening and the documents to go back to ---- */

.chat__start {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.chat__recent-title {
  margin: 0 0 var(--space-2);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.chat__list {
  display: grid;
  gap: 2px;
  margin: 0 calc(-1 * var(--space-3));
  padding: 0;
  list-style: none;
}

/*
 * Each row is a grid item of the list above; without this a row holding a long, unbroken title (its own
 * text keeps to one line so it can be cut short with an ellipsis instead of wrapping) would keep the grid
 * column, and so the whole list, as wide as that title, overflowing the column that holds it.
 */
.chat__list > li {
  min-inline-size: 0;
}

.chat__row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-inline-size: 0;
  min-block-size: 2.75rem;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  color: var(--color-text);
  text-decoration: none;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.chat__row:hover {
  background: var(--color-cocoa-wash);
}

.chat__row-title {
  flex: 1 1 auto;
  min-inline-size: 0;
  overflow: hidden;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- Brownie's plan ---- */

.chat-plan__rows {
  margin: 0;
}

.chat-plan__row {
  display: grid;
  grid-template-columns: 8.25rem minmax(0, 1fr);
  align-items: center;
}

.chat-plan__label {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.chat-plan__value {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-inline-size: 0;
  margin: 0;
  font-size: var(--font-size-sm);
  line-height: 1.43;
}

.chat-plan__text {
  display: grid;
  gap: 0.125rem;
  min-inline-size: 0;
  overflow-wrap: anywhere;
}

.chat-plan__strong {
  font-weight: var(--font-weight-medium);
}

.chat-plan__hint {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.35;
}

/*
 * A text button at the end of the row. Its label lines up with the card's edge, as plain text would, and its
 * height is taken back from the row so the row keeps its size.
 */
.chat-plan__change {
  --button-ink: var(--color-cocoa);
  --button-bg-hover: var(--color-cocoa-soft);
  margin-block: -0.25rem;
  margin-inline: auto -0.875rem;
  font-size: var(--font-size-xs);
}

.chat-plan__change:hover:not([aria-disabled='true']) {
  --button-ink: var(--color-cocoa-strong);
}

.chat-plan__next {
  display: grid;
  gap: 0.625rem;
}

.chat-plan__steps {
  display: grid;
  gap: 0.625rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.chat-plan__steps li {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

.chat-plan__number {
  flex: none;
  display: inline-grid;
  place-items: center;
  inline-size: 1.25rem;
  block-size: 1.25rem;
  border-radius: 0.3125rem;
  background: var(--color-paper-deep);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.chat-plan__picker {
  display: grid;
  justify-items: start;
  gap: var(--space-2);
  scroll-margin-block-end: var(--space-4);
}

.chat-plan__picker > * {
  margin: 0;
}

.chat-plan__picker .choices {
  justify-self: stretch;
}

.chat-plan__picker-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

/* A text button under the list: its label lines up with the list's edge, as plain text would. */
.chat-plan__keep {
  margin-inline-start: -0.875rem;
}

/* A narrow window has no room for the label beside its value. */
@container main (max-width: 32rem) {
  .chat-plan__row {
    grid-template-columns: minmax(0, 1fr);
    row-gap: 0.25rem;
  }
}

/* ---- The message box, pinned under the conversation ---- */

/*
 * It stays in view while the conversation scrolls behind it, so it needs a background of its own. A shadow the
 * colour of the page, spreading upward, fades the conversation out above it so its first covered line does not
 * end in a hard edge.
 */
.chat__dock {
  position: sticky;
  inset-block-end: 0;
  z-index: 2;
  padding-block: 0 2.5rem;
  background: var(--color-paper);
  box-shadow: 0 -0.25rem 0.75rem 0.25rem var(--color-paper);
}

/* In a window this short the box would cover a third of it or more, so it goes back to the end of the page. */
@media (max-height: 30rem) {
  .chat__dock {
    position: static;
  }
}

.chat__disclaimer {
  margin: 1.125rem 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.35;
  text-align: center;
}

.chat__attach[aria-disabled='true'] {
  cursor: not-allowed;
  opacity: 0.4;
}
</style>
