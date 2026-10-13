<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import type { IconName } from '@/components/icons'
import { useSessionStore } from '@/stores/session'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import {
  ApiRequestError,
  listBatches,
  listDocuments,
  trashDocument,
  type DocumentSummaryResponse,
} from '@/api/client'
import { documentHandoffState } from '@/router/handoff'
import { formatBytes, loadCapabilities } from '@/capabilities'
import { isPagesPackage } from '@/upload/fillableCopyWords'
import { FORM_FILE_ACCEPT, learnFormAndStartDocument, learnRows, learnStepWords, refusalParts } from '@/upload/learnAndStart'
import { formUploadError, formUploadFinished, formUploadStep, type ChosenForm } from '@/upload/formUploadState'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const documents = ref<DocumentSummaryResponse[]>([])

// A sign-in that the identity provider refused comes back here as /?signin=failed&reason=<code>
// (see the API's own sign-in failure handling). The code is rendered as text only.
const signInFailed = computed(() => route.query.signin === 'failed')
const signInFailureReason = computed(() => (typeof route.query.reason === 'string' ? route.query.reason : null))
// One refusal is not worth trying again for: this Brownie is open to invited
// people only, and nothing the person does at the sign-in page changes that.
// Telling them to try again would be false, and would send them round a loop.
const signInRefusedAsUninvited = computed(() => signInFailureReason.value === 'not_invited')
const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const loadError = ref('')

// A batch page that deleted itself sends the person here with what it was called; the notice says what stays.
const deletedBatch = computed(() => (typeof route.query.batchDeleted === 'string' ? route.query.batchDeleted : null))

// A typographer's apostrophe, as the rest of the interface sets it.
const greeting = computed(() =>
  session.firstName ? `What’s on your mind today, ${session.firstName}?` : 'What’s on your mind today?',
)

async function loadDocuments(): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined) {
    return
  }
  loadState.value = 'loading'
  try {
    documents.value = await listDocuments(workspaceId)
    loadState.value = 'loaded'
  } catch (error) {
    loadError.value =
      describeCommonFailure(error, 'a way to list documents') ??
      'Brownie could not load your documents. If this keeps happening, let whoever runs this Brownie know.'
    loadState.value = 'error'
  }
  void loadBatches(workspaceId)
}

/**
 * Which of the listed documents each batch made: documentId to the batch's
 * id, title and made-count, so the recent list can fold a batch's
 * documents into one entry. The list answer names each batch's documents
 * itself, so no batch is read in detail for this. A server from before
 * batches has no route to ask -- the list then simply stays one row per
 * document, which is the same answer the map being empty gives.
 */
const batchOf = ref<Map<number, { batchId: number; title: string; count: number }>>(new Map())

async function loadBatches(workspaceId: number): Promise<void> {
  try {
    const summaries = await listBatches(workspaceId)
    const listed = new Set(documents.value.map((document) => document.id))
    const map = new Map<number, { batchId: number; title: string; count: number }>()
    for (const summary of summaries) {
      // A batch's count on Home is how many of its documents are still
      // listed here; the trashed ones are the trash bin's to show.
      const kept = summary.documentIds.filter((id) => listed.has(id))
      for (const id of kept) {
        map.set(id, { batchId: summary.id, title: summary.title, count: kept.length })
      }
    }
    batchOf.value = map
  } catch (error) {
    // routeMissing or any other failure: the document list stands on its own rather than half-grouped.
    if (!(error instanceof ApiRequestError && error.routeMissing)) return
    batchOf.value = new Map()
  }
}

onMounted(loadDocuments)

// ---- Uploading a form ------------------------------------------------------------------------
//
// The file chooser belongs to a hidden input, opened by the big button, so the button can look
// like a single large control and still be one ordinary control. While a form is being learned the
// button says it is unavailable with aria-disabled rather than the disabled attribute, which would
// drop the keyboard focus it holds; a press in that time does nothing.

const uploadButton = ref<HTMLButtonElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
// Kept outside the page (see formUploadState), so leaving Home mid-way loses neither the step nor how it ended.
const uploadStep = formUploadStep
const uploadError = formUploadError
const uploadFinished = formUploadFinished
const uploading = computed(() => uploadStep.value !== null)
const authenticated = computed(() => session.status === 'authenticated')

// What this deployment says about uploads: how large a file may be, and whether the AI service names the places in
// a form. Asked only once signed in: both are about the person's own uploads. Until it answers, or when it cannot,
// nothing is claimed that it did not say.
const namingByModel = ref(false)
const uploadLimit = ref<string | null>(null)
async function loadCapabilityNotes(): Promise<void> {
  try {
    const capabilities = await loadCapabilities()
    namingByModel.value = capabilities.fillSpotNaming === 'MODEL'
    uploadLimit.value = capabilities.maxUploadBytes > 0 ? formatBytes(capabilities.maxUploadBytes) : null
  } catch {
    namingByModel.value = false
    uploadLimit.value = null
  }
}
onMounted(() => {
  if (authenticated.value) void loadCapabilityNotes()
})

/** The kinds of file the button takes, and how large one may be where the server said. */
const kindsLine = computed(
  () => `Word, PDF, Pages, OpenDocument or RTF${uploadLimit.value ? `, up to ${uploadLimit.value}` : ''}`,
)
// Where the AI service names the places in an uploaded form, the form's text goes to it, and the person is told so
// before they choose a file. While a form is being learned there is no file left to choose, so the note waits.
const showDisclosure = computed(() => authenticated.value && namingByModel.value && !uploading.value)
const uploadDescribedBy = computed(() => (showDisclosure.value ? 'home-upload-kinds home-upload-disclosure' : 'home-upload-kinds'))

const DROP_TITLE = 'Drop your form to start'
const dropExplanation = computed(() => `${kindsLine.value}. Brownie makes a template and a document from it.`)

// The page's one polite live region: each step is read out as it starts, and so is the drop zone. It stays in the
// page while empty, because a live region that only appears with its first message is not reliably read out.
const announcement = computed(() => {
  if (dragging.value) return `${DROP_TITLE}. ${dropExplanation.value}`
  if (uploadStep.value) return learnStepWords(uploadStep.value.step, uploadStep.value.detail)
  return finishedAnnouncement.value
})

// A form can finish while this page is showing but its upload began on an earlier visit, so nothing opens the
// document for the person; the notice appears, and the live region says so once. A notice that was already there
// when the page opened is only there to be found.
const finishedAnnouncement = ref('')
watch(
  () => formUploadFinished.value,
  (now) => {
    finishedAnnouncement.value = now ? finishedWords(now.fileName) : ''
  },
)

function finishedWords(fileName: string): string {
  return `Brownie learned “${fileName}” and started a document from it.`
}

// Someone who moves on while a form is being learned is not pulled back to it when it is ready: the
// document is in their recent documents, and the template under My Templates, either way.
let leftHome = false
onBeforeUnmount(() => {
  leftHome = true
  // A refusal already shown here is not said again on the next visit; one that comes while the person is away is.
  if (!uploading.value) uploadError.value = null
})

const KIND_BY_EXTENSION = new Map([
  ['docx', 'Word'],
  ['dotx', 'Word'],
  ['docm', 'Word'],
  ['dotm', 'Word'],
  ['doc', 'Word'],
  ['dot', 'Word'],
  ['rtf', 'RTF'],
  ['odt', 'OpenDocument'],
  ['ott', 'OpenDocument'],
  ['pages', 'Pages'],
  ['pdf', 'PDF'],
])

/** The file as the cards name it. The kind comes from the name, which is what the person sees, not from the bytes. */
function describeForm(file: File): ChosenForm {
  const dot = file.name.lastIndexOf('.')
  const extension = dot > 0 ? file.name.slice(dot + 1).toLowerCase() : ''
  return { name: file.name, kind: KIND_BY_EXTENSION.get(extension) ?? null, size: formatBytes(file.size) }
}

/** "Word • 48 KB • becomes a template and a document": what is known of the file, then what is happening to it. */
function metaWords(form: ChosenForm, tail: string): string {
  return [form.kind, form.size, tail].filter((part): part is string => part !== null).join(' • ')
}

function chooseForm(): void {
  if (uploading.value) return
  fileInput.value?.click()
}

function onFormChosen(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // Cleared at once, so choosing the same file again after a refusal is still a change.
  input.value = ''
  void uploadForm(file)
}

/** Choosing a file and dropping one both end here, so both are refused, and worded, alike. */
async function uploadForm(file: File | undefined): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (!file || workspaceId === undefined || uploading.value) return

  const form = describeForm(file)
  const replacesACard = uploadError.value !== null || uploadFinished.value !== null
  uploadError.value = null
  uploadFinished.value = null
  uploadStep.value = { step: 'uploading', detail: { mediaType: null, waiting: false }, form }
  if (replacesACard) void keepFocusOnPage()
  const outcome = await learnFormAndStartDocument(workspaceId, file, (step, detail) => {
    uploadStep.value = { step, detail, form }
  })
  if (!outcome.ok) {
    uploadStep.value = null
    uploadError.value = { form, message: outcome.message }
    return
  }
  if (leftHome) {
    // Said on Home when the person comes back, with a way to the document, instead of lost.
    uploadFinished.value = { documentId: outcome.documentId, fileName: file.name, notes: outcome.notes }
    uploadStep.value = null
    return
  }
  await router.push(documentLocation(outcome.documentId, outcome.notes))
  uploadStep.value = null
}

/**
 * A card or notice the form was chosen from ("Choose another file") is replaced by the next one, and takes the
 * focus it held with it, which would leave a keyboard user at the top of the page. The button that starts every
 * upload is the place to put it back.
 */
async function keepFocusOnPage(): Promise<void> {
  await nextTick()
  if (document.activeElement === document.body) uploadButton.value?.focus()
}

/**
 * The notes about the form ride along in the pushed route's history state, so they are read on
 * the page the person lands on, and still there if they reload it.
 */
function documentLocation(documentId: number, notes: string[]) {
  return {
    path: `/documents/${documentId}`,
    state: notes.length > 0 ? documentHandoffState({ attachedSources: [], sourceWarning: null, formNotes: notes }) : undefined,
  }
}

// What a person watching a form being learned sees: the file, then each step as it is reached.
const learningRowsNow = computed(() => (uploadStep.value ? learnRows(uploadStep.value.step, uploadStep.value.detail) : []))
// A marker alone is not announced, so each line says where it stands in words only a screen reader hears.
const ROW_STATE_WORDS = { done: 'Done', active: 'In progress', pending: 'Not started' } as const

/** A refusal in the two parts it can be shown in: why the file was not taken, and what the person can do next. */
const refusal = computed(() => {
  const failed = uploadError.value
  return failed ? { form: failed.form, ...refusalParts(failed.message) } : null
})

/** "Open it, remove the password..." after "What you can do:" reads as the rest of that sentence. */
function afterColon(sentence: string): string {
  return sentence.charAt(0).toLowerCase() + sentence.slice(1)
}

/** Closing a card takes its buttons away, so focus goes back to the button that starts the next try. */
async function dismissRefusal(): Promise<void> {
  uploadError.value = null
  await nextTick()
  uploadButton.value?.focus()
}

async function dismissFinished(): Promise<void> {
  uploadFinished.value = null
  await nextTick()
  uploadButton.value?.focus()
}

// ---- Dropping a form on the page -------------------------------------------------------------
//
// A file dragged over the page turns it into one large drop zone, and dropping it does what choosing it would. The
// listeners are on the window, because a drag over any part of the page must be answered, and because a file that
// is dropped where nothing answers is opened by the browser in place of the page, which would end a form being learned.
// Only drags that carry files are looked at: a dragged link or a piece of selected text is none of this page's business.

const dragging = ref(false)
// The drop zone is drawn over the main region, which starts where the sidebar ends; measured when the drag arrives.
const dropLeft = ref(0)
const dropStyle = computed(() => ({ '--home-drop-left': `${dropLeft.value}px` }))
// Every element the pointer crosses sends its own enter and leave, so the drag has left the page only when they balance.
let dragDepth = 0

function carriesFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files')
}

/** Whether a dropped form would be taken: someone signed in, with nothing else being learned. */
function canTakeForm(): boolean {
  return authenticated.value && !uploading.value
}

function onDragEnter(event: DragEvent): void {
  if (!carriesFiles(event)) return
  event.preventDefault()
  dragDepth += 1
  if (!canTakeForm() || dragging.value) return
  dropLeft.value = document.getElementById('main-content')?.getBoundingClientRect().left ?? 0
  dragging.value = true
}

function onDragOver(event: DragEvent): void {
  if (!carriesFiles(event)) return
  // Without this the browser does not allow a drop here at all.
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = canTakeForm() ? 'copy' : 'none'
}

function onDragLeave(event: DragEvent): void {
  if (!carriesFiles(event)) return
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) dragging.value = false
}

function onDrop(event: DragEvent): void {
  if (!carriesFiles(event)) return
  // Always claimed, so a form that is not taken is not opened by the browser in place of this page.
  event.preventDefault()
  dragDepth = 0
  dragging.value = false
  if (!canTakeForm()) return
  const dropped = droppedForms(event)
  if (dropped.length === 1) {
    void uploadForm(dropped[0])
  } else if (dropped.length > 1) {
    uploadError.value = {
      form: { name: `${dropped.length} files`, kind: null, size: null },
      message: 'Brownie fills one form at a time. Drop or choose a single file.',
    }
  }
}

/**
 * The files in a drop, without any folder. A folder arrives as a file with nothing in it, so it is told apart by what
 * the browser says it is; a Pages document is one, and is kept so that the person is told why it cannot be used.
 */
function droppedForms(event: DragEvent): File[] {
  const items = Array.from(event.dataTransfer?.items ?? []).filter((item) => item.kind === 'file')
  const files = Array.from(event.dataTransfer?.files ?? [])
  return files.filter((file, index) => !(items[index]?.webkitGetAsEntry?.()?.isDirectory && !isPagesPackage(file)))
}

watch(uploading, (now) => {
  if (!now) return
  dragging.value = false
  dragDepth = 0
})

onMounted(() => {
  window.addEventListener('dragenter', onDragEnter)
  window.addEventListener('dragover', onDragOver)
  window.addEventListener('dragleave', onDragLeave)
  window.addEventListener('drop', onDrop)
})
onBeforeUnmount(() => {
  window.removeEventListener('dragenter', onDragEnter)
  window.removeEventListener('dragover', onDragOver)
  window.removeEventListener('dragleave', onDragLeave)
  window.removeEventListener('drop', onDrop)
})

// ---- How Brownie works ----------------------------------------------------------------------

/** The same three steps the sign-in page shows, so a visitor sees what the product does before they sign in. */
const HOW_BROWNIE_WORKS: readonly { icon: IconName; title: string; text: string }[] = [
  { icon: 'document-upload', title: 'Upload a form', text: 'Brownie learns where the values go and keeps the form’s own look.' },
  {
    icon: 'document-text',
    title: 'Add your notes',
    text: 'A transcript, notes, a calendar event or a Google Doc. Brownie reads only what you add.',
  },
  {
    icon: 'document-check',
    title: 'Check and export',
    text: 'You approve every value. Brownie exports exactly the version you approved.',
  },
]

// ---- Recent documents ------------------------------------------------------------------------

// One document at a time: the list is rebuilt from what the server accepted,
// so a second click cannot race the first one's answer.
const trashingId = ref<number | null>(null)
const trashNotice = ref<string | null>(null)
const trashError = ref<string | null>(null)
const trashNoticeElement = ref<HTMLElement | null>(null)
const trashErrorElement = ref<HTMLElement | null>(null)
// Set once a row leaves this list, so an empty list no longer reads as though there were never any documents.
const removedFromList = ref(false)

/**
 * Moving a document to the trash asks for no confirmation because it loses
 * nothing: the trash bin restores it exactly as it was. The row's own button
 * disappears with the row, so focus goes to the sentence that says what just
 * happened and where to undo it, rather than falling back to the top of the
 * page for someone who is not looking at the screen.
 */
async function moveToTrash(document: DocumentSummaryResponse): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId === undefined || trashingId.value !== null) {
    return
  }
  trashingId.value = document.id
  trashError.value = null
  let retryable = false
  try {
    await trashDocument(workspaceId, document.id)
    documents.value = documents.value.filter((candidate) => candidate.id !== document.id)
    removedFromList.value = true
    trashNotice.value = `Moved "${document.title}" to the trash.`
  } catch (error) {
    trashNotice.value = null
    if (error instanceof ApiRequestError && error.routeMissing) {
      // A server older than this page answers 404 for the route itself: the document is untouched and still listed.
      trashError.value = `This Brownie server cannot move documents to the trash, so nothing was changed and "${document.title}" is still here. The server needs to be updated first.`
    } else if (brownieSaysNotThere(error)) {
      // Deleted for good from another tab or device: trying again could never work, so the row goes and the sentence says why.
      // Only Brownie's own explained answer says that; a bare 404 came from something in front of it.
      documents.value = documents.value.filter((candidate) => candidate.id !== document.id)
      removedFromList.value = true
      trashError.value = `"${document.title}" was already deleted, so it was taken off this list.`
    } else {
      // An ended session cannot be tried again from here; the page turns into its signed-out state instead.
      retryable = !(error instanceof ApiRequestError && error.status === 401)
      const reason = describeCommonFailure(error, 'a trash bin') ?? 'Try again.'
      trashError.value = `Could not move "${document.title}" to the trash. ${reason}`
    }
  } finally {
    trashingId.value = null
  }
  // The pressed button was disabled while the request ran, which drops keyboard focus; it is
  // put back on that button when trying again makes sense, and on the explanation otherwise.
  await nextTick()
  if (retryable) {
    globalThis.document.getElementById(`home-trash-${document.id}`)?.focus()
  } else if (trashNotice.value) {
    trashNoticeElement.value?.focus()
  } else {
    trashErrorElement.value?.focus()
  }
}
watch(
  () => session.status,
  (status) => {
    if (status === 'authenticated') {
      void loadDocuments()
      void loadCapabilityNotes()
    }
  },
)

const timeFormatter = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' })
const thisYearFormatter = new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
const otherYearFormatter = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' })

/** "Today" for today, otherwise the day itself. */
function dayHeading(date: Date, now: Date): string {
  const sameDay =
    date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
  if (sameDay) return 'Today'
  return date.getFullYear() === now.getFullYear() ? thisYearFormatter.format(date) : otherYearFormatter.format(date)
}

/** One row in the recent list: a document on its own, or every document one batch made folded into its batch. */
type RecentEntry =
  | { kind: 'document'; document: DocumentSummaryResponse; time: string; at: number }
  | { kind: 'batch'; batchId: number; title: string; count: number; time: string; at: number }

interface DayGroup {
  key: string
  heading: string
  entries: RecentEntry[]
}

const newestFirst = computed(() => [...documents.value].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)))

/**
 * The documents as the list shows them, newest first: every document a
 * batch made folds into one entry naming the batch, placed where its
 * newest document would have stood.
 */
const recentEntries = computed<RecentEntry[]>(() => {
  const entries: RecentEntry[] = []
  const grouped = new Map<number, { title: string; count: number; newest: DocumentSummaryResponse }>()
  for (const document of newestFirst.value) {
    const batch = batchOf.value.get(document.id)
    if (!batch) {
      entries.push({ kind: 'document', document, time: timeFormatter.format(new Date(document.createdAt)), at: Date.parse(document.createdAt) })
      continue
    }
    const group = grouped.get(batch.batchId)
    if (group === undefined) {
      grouped.set(batch.batchId, { title: batch.title, count: batch.count, newest: document })
    }
  }
  for (const [batchId, group] of grouped) {
    entries.push({
      kind: 'batch',
      batchId,
      title: group.title,
      count: group.count,
      time: timeFormatter.format(new Date(group.newest.createdAt)),
      at: Date.parse(group.newest.createdAt),
    })
  }
  return entries.sort((a, b) => b.at - a.at)
})

/**
 * Newest first, split into the days they were created on. The grouping key
 * is the local calendar date rather than the timestamp, so two documents
 * made minutes apart either side of midnight land under different days --
 * which is how the person who made them remembers it.
 */
const recentDays = computed<DayGroup[]>(() => {
  const now = new Date()
  const groups = new Map<string, DayGroup>()
  for (const entry of recentEntries.value) {
    const created = new Date(entry.at)
    const key = `${created.getFullYear()}-${created.getMonth()}-${created.getDate()}`
    let group = groups.get(key)
    if (!group) {
      group = { key, heading: dayHeading(created, now), entries: [] }
      groups.set(key, group)
    }
    group.entries.push(entry)
  }
  return [...groups.values()]
})

/**
 * What to offer first: the newest entry. Where that is a batch of
 * documents the offer is the batch itself, which is where that work
 * continues. The list says only when each was made, so that is what
 * "where you left off" can honestly rest on, and the line under its title
 * says when it was started, not when it was last worked on.
 */
const resume = computed(() => {
  const entry = recentEntries.value[0]
  if (!entry) return null
  const created = new Date(entry.at)
  const day = dayHeading(created, new Date())
  const started = `Started ${day === 'Today' ? 'today' : day} at ${timeFormatter.format(created)}`
  if (entry.kind === 'batch') {
    return { kind: 'batch' as const, batchId: entry.batchId, title: entry.title, count: entry.count, started }
  }
  return { kind: 'document' as const, document: entry.document, title: entry.document.title, started }
})

/** The three cards that say how Brownie works are for someone with nothing yet; they make way for a form being learned. */
const showHowItWorks = computed(
  () => loadState.value === 'loaded' && documents.value.length === 0 && !uploading.value && uploadError.value === null,
)
</script>

<template>
  <div class="page-column page-column--narrow home" :class="{ 'home--wide': showHowItWorks }">
    <p v-if="signInRefusedAsUninvited" class="card home__signin-error" role="alert">
      This Brownie is open to invited people only, and the account you signed in with is not on its list. If you think it
      should be, ask whoever invited you; signing in again with the same account will not change it.
    </p>
    <p v-else-if="signInFailed" class="card home__signin-error" role="alert">
      Sign-in did not complete<template v-if="signInFailureReason"> ({{ signInFailureReason }})</template>. Try again; if
      it keeps failing, pass that reason on to whoever runs this Brownie.
      <RouterLink to="/signin">Try again</RouterLink>
    </p>

    <!-- A form that was finished while the person was elsewhere: a notice at the corner of the page, kept until it is used or closed. -->
    <div v-if="uploadFinished" class="home__toast">
      <AppIcon class="home__toast-check" name="check-circle" :size="18" />
      <p class="home__toast-text">{{ finishedWords(uploadFinished.fileName) }}</p>
      <RouterLink
        class="home__toast-link"
        :to="documentLocation(uploadFinished.documentId, uploadFinished.notes)"
        @click="uploadFinished = null"
        >Open it</RouterLink
      >
      <button type="button" class="icon-button" @click="dismissFinished">
        <AppIcon name="close" :size="16" />
        <span class="visually-hidden">Dismiss</span>
      </button>
    </div>

    <h1 class="page-title home__greeting">
      {{ authenticated ? greeting : 'Welcome to Brownie!' }}
    </h1>

    <div class="home__upload-area">
      <!--
        Signed in, the button opens the file chooser for the form to fill. Signed out it is a link
        to the sign-in page that brings the visitor back here, rather than a control that refuses.
      -->
      <template v-if="authenticated">
        <button
          ref="uploadButton"
          type="button"
          class="button button--primary home__upload"
          :aria-disabled="uploading ? 'true' : undefined"
          :aria-describedby="uploadDescribedBy"
          @click="chooseForm"
        >
          <AppIcon name="upload" :size="30" />
          <span>Upload your documents</span>
        </button>
        <input
          id="home-upload-input"
          ref="fileInput"
          class="visually-hidden"
          type="file"
          :accept="FORM_FILE_ACCEPT"
          tabindex="-1"
          aria-hidden="true"
          @change="onFormChosen"
        />
      </template>
      <RouterLink v-else class="button button--primary home__upload" :to="{ name: 'signin', query: { next: '/' } }">
        <AppIcon name="upload" :size="30" />
        <span>Upload your documents</span>
      </RouterLink>
      <p id="home-upload-kinds" class="home__upload-kinds">{{ kindsLine }}</p>
      <p v-if="showDisclosure" id="home-upload-disclosure" class="home__upload-disclosure">
        <AppIcon name="info" :size="14" />
        When you upload a form, its text is sent to our AI service so Brownie can find the places to fill.
      </p>
      <p class="visually-hidden home__status" role="status">{{ announcement }}</p>
    </div>

    <!-- While a form is being learned: which form, and each step as it is reached. -->
    <div v-if="uploadStep" class="msg-card home__card home__card--learning">
      <div class="home__card-head">
        <span class="doc-tile" aria-hidden="true"><AppIcon name="document-text" :size="22" /></span>
        <div class="home__card-text">
          <p class="home__card-name" :title="uploadStep.form.name">{{ uploadStep.form.name }}</p>
          <p class="home__card-meta">{{ metaWords(uploadStep.form, 'becomes a template and a document') }}</p>
        </div>
      </div>
      <!-- How far along it is cannot be known, so the bar only shows that something is moving; the steps say what. -->
      <div class="home__progress" aria-hidden="true"><span class="home__progress-bar"></span></div>
      <ol class="step-list home__steps">
        <li v-for="row in learningRowsNow" :key="row.id" class="step" :class="`step--${row.state}`">
          <span class="step__marker" aria-hidden="true"></span>
          <span class="step__label"><span class="visually-hidden">{{ ROW_STATE_WORDS[row.state] }}: </span>{{ row.label }}</span>
        </li>
      </ol>
      <div class="panel--quiet home__note">
        <AppIcon name="info" :size="16" />
        <p>
          You can leave this page, but keep this tab open. Brownie opens the document when it is ready, or it waits for you in
          Recent documents.
        </p>
      </div>
    </div>

    <!-- After a form was refused: which form, why, and what to do. The reason is the alert; focus stays on the button that was pressed. -->
    <div v-else-if="refusal" class="msg-card home__card home__card--refused">
      <div class="home__card-head">
        <span class="doc-tile home__alert-tile" aria-hidden="true"><AppIcon name="alert-circle" :size="20" /></span>
        <div class="home__card-text">
          <p class="home__card-name" :title="refusal.form.name">{{ refusal.form.name }}</p>
          <p class="home__card-meta">{{ metaWords(refusal.form, 'not added') }}</p>
        </div>
        <button type="button" class="icon-button home__card-close" @click="dismissRefusal">
          <AppIcon name="close" :size="18" />
          <span class="visually-hidden">Close</span>
        </button>
      </div>
      <p class="home__refusal-reason" role="alert">{{ refusal.reason }}</p>
      <div v-if="refusal.advice" class="panel--quiet home__note home__note--advice">
        <AppIcon name="info" :size="16" />
        <p>What you can do: {{ afterColon(refusal.advice) }}</p>
      </div>
      <div class="home__card-actions">
        <button type="button" class="button button--primary button--small" @click="chooseForm">
          <AppIcon name="upload" :size="16" />
          <span>Choose another file</span>
        </button>
        <button type="button" class="button button--quiet button--small" @click="dismissRefusal">Dismiss</button>
      </div>
    </div>

    <section v-if="authenticated && resume" class="home__resume" aria-labelledby="resume-heading">
      <h2 id="resume-heading" class="home__resume-label">Continue where you left off</h2>
      <div class="home__resume-row">
        <span class="doc-tile" aria-hidden="true">
          <AppIcon :name="resume.kind === 'batch' ? 'table' : 'document-text'" :size="22" />
        </span>
        <div class="home__resume-text">
          <p class="home__resume-title" :title="resume.title">{{ resume.title }}</p>
          <p class="home__resume-started">
            {{ resume.kind === 'batch' ? `${resume.count} documents · ` : '' }}{{ resume.started }}
          </p>
        </div>
        <RouterLink
          class="button button--primary button--small"
          :to="resume.kind === 'batch' ? `/batches/${resume.batchId}` : `/documents/${resume.document.id}`"
        >
          <AppIcon name="arrow-right" :size="16" />
          <span>Open <span class="visually-hidden">{{ resume.title }}</span></span>
        </RouterLink>
      </div>
    </section>

    <section v-if="authenticated && showHowItWorks" class="home__how" aria-labelledby="how-heading">
      <h2 id="how-heading" class="home__section-title">How Brownie works</h2>
      <ol class="home__how-cards">
        <li v-for="(step, index) in HOW_BROWNIE_WORKS" :key="step.title" class="panel home__how-card">
          <p class="home__how-head">
            <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon :name="step.icon" :size="18" /></span>
            <span class="home__how-step">Step {{ index + 1 }}</span>
          </p>
          <h3 class="home__how-title">{{ step.title }}</h3>
          <p class="home__how-text">{{ step.text }}</p>
        </li>
      </ol>
    </section>

    <section v-if="authenticated" class="home__recent" aria-labelledby="recent-heading">
      <h2 id="recent-heading" class="home__section-title">Recent documents</h2>

      <p v-if="deletedBatch" class="home__notice" tabindex="-1">
        Deleted the batch "{{ deletedBatch }}" and its spreadsheet. The documents it made are still in your documents.
      </p>
      <p v-if="trashNotice" ref="trashNoticeElement" class="home__notice" tabindex="-1">
        {{ trashNotice }} <RouterLink to="/trash">Restore it from the trash bin</RouterLink>.
      </p>
      <p v-if="trashError" ref="trashErrorElement" class="field-error home__problem" role="alert" tabindex="-1">{{ trashError }}</p>

      <p v-if="loadState === 'loading'" class="home__hint">Loading documents…</p>
      <p v-else-if="loadState === 'error'" class="field-error home__problem" role="alert">
        {{ loadError }}
      </p>
      <!-- While a first form is being learned, this box would say there are no documents right under the card making one. -->
      <div v-else-if="documents.length === 0 && !(uploading && loadState === 'loaded')" class="home__empty">
        <AppIcon name="document-blank" :size="24" />
        <p v-if="removedFromList" class="home__hint">
          There are no documents here now. Anything moved to the trash can be restored from the
          <RouterLink to="/trash">trash bin</RouterLink>.
        </p>
        <p v-else class="home__hint">
          There are no documents here. Upload a form above to start one, or choose a template under My Templates.
        </p>
      </div>

      <div v-for="day in recentDays" :key="day.key" class="home__day">
        <h3 class="home__day-heading">{{ day.heading }}</h3>
        <ul class="home__list">
          <!-- The action sits beside the link, never inside it: a button nested in a link is neither. -->
          <li v-for="entry in day.entries" :key="entry.kind === 'batch' ? `batch-${entry.batchId}` : entry.document.id" class="home__item">
            <RouterLink
              v-if="entry.kind === 'batch'"
              class="home__row"
              :to="`/batches/${entry.batchId}`"
            >
              <span class="doc-tile" aria-hidden="true"><AppIcon name="table" :size="22" /></span>
              <span class="home__row-title" :title="entry.title">{{ entry.title }}</span>
              <span class="home__row-count">{{ entry.count }} documents</span>
              <span class="home__row-time">{{ entry.time }}</span>
            </RouterLink>
            <template v-else>
              <RouterLink class="home__row" :to="`/documents/${entry.document.id}`">
                <span class="doc-tile" aria-hidden="true"><AppIcon name="document-blank" :size="22" /></span>
                <span class="home__row-title" :title="entry.document.title">{{ entry.document.title }}</span>
                <span class="home__row-time">{{ entry.time }}</span>
              </RouterLink>
              <button
                :id="`home-trash-${entry.document.id}`"
                type="button"
                class="icon-button home__trash"
                :disabled="trashingId !== null"
                @click="moveToTrash(entry.document)"
              >
                <AppIcon name="trash" :size="18" />
                <span class="visually-hidden">Move {{ entry.document.title }} to the trash</span>
              </button>
            </template>
          </li>
        </ul>
      </div>
    </section>

    <!--
      Drawn on the body rather than inside the page, so that it is fixed to the window whatever sits between here and
      the window: a container, a transform or a filter on an ancestor would make "fixed" mean fixed to that instead.
      Decoration, so it is hidden from assistive technology; the button above stays the way to choose a file, and the
      live region says the drop zone is open.
    -->
    <Teleport to="body">
      <div v-if="dragging" class="home__drop" aria-hidden="true" :style="dropStyle">
        <div class="home__drop-body">
          <span class="home__drop-icon"><AppIcon name="document-upload" :size="40" /></span>
          <p class="home__drop-title">{{ DROP_TITLE }}</p>
          <p class="home__drop-text">{{ dropExplanation }}</p>
          <p class="home__drop-note">
            <AppIcon name="shield-check" :size="14" />
            Locked, signed or damaged files are refused with the reason.
          </p>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/*
 * One centred column, 728px wide, whose blocks each take the width they read best at: a form being learned is a
 * narrower card than the list under it, and while there is nothing in the list yet the three cards that say how
 * Brownie works use the full column. The top gap is a share of the window's height, so the greeting sits at the
 * same place on a tall screen as on a short one.
 */
.home {
  /* The shell's own side margins already keep the column off the window edge. */
  --page-gutter: 0px;
  --home-column: 42.375rem;
  /* The size of the quiet lines under the button: between the 12px of tags and the 14px of body text. */
  --home-fine: 0.8125rem;
  /* A row's trash control sits just outside the column, so the column itself lines up with the cards above it. */
  --home-row-action: calc(2rem + var(--space-3));
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-block: clamp(var(--space-6), 13.1vh, 9rem) var(--space-8);
}

.home--wide {
  --home-column: 45.5rem;
}

.home__signin-error {
  inline-size: 100%;
  max-inline-size: 45rem;
  margin-block-end: var(--space-5);
  border-color: var(--color-error);
}

.home__greeting {
  text-align: center;
  text-wrap: balance;
}

/* The button, the quiet lines under it, and the live region, as one block. */
.home__upload-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  inline-size: 100%;
  margin-block-start: 1.375rem;
}

/*
 * The big button is the shared primary pill, drawn larger: 451px by 78px with its content set from the left, and a
 * 20px label at the regular weight, which on this cocoa is 4.8:1 and so clear of the 4.5:1 any size of text needs.
 */
.home__upload {
  --button-height: 4.875rem;
  --button-pad: 1.75rem;
  --button-icon: 1.875rem;
  --button-gap: 1.5rem;
  justify-content: flex-start;
  inline-size: min(100%, 28.1875rem);
  font-size: var(--font-size-lg);
  font-weight: 400;
}

/* What the button takes, and where a form's text goes: quiet lines, still at full reading contrast. */
.home__upload-kinds,
.home__upload-disclosure {
  margin: 1.1875rem 0 0;
  max-inline-size: 100%;
  color: var(--color-text-muted);
  font-size: var(--home-fine);
  line-height: 1.4;
  text-align: center;
  text-wrap: balance;
}

/* The note sits a hair further from the line above it than that line does from the button. */
.home__upload-disclosure {
  margin-block-start: 1.25rem;
}

/* The icon sits in the text itself, so on a narrow screen it stays with the first line instead of being left at the edge. */
.home__upload-disclosure > svg,
.home__drop-note > svg {
  margin-inline-end: 0.25rem;
  vertical-align: middle;
}

/* ---- A form being learned, and a form that was refused: the same card, told two ways ---- */

.home__card {
  inline-size: min(100%, var(--home-column));
  margin-block-start: 2.5625rem;
  padding: 1.1875rem;
}

.home__card--learning {
  --home-column: 34.8125rem;
}

/* The card's red edge is what says it is a refusal, and the words and the alert icon say it too. */
.home__card--refused {
  border-color: var(--color-error);
}

.home__card-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.home__card-text {
  flex: 1;
  min-inline-size: 0;
}

.home__card-name {
  margin: 0;
  overflow: hidden;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home__card-meta {
  margin: 0.0625rem 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

/* The refused file's tile: the same rounded square as a document's, in the error colours. */
.home__alert-tile {
  background: var(--color-error-wash);
  color: var(--color-error);
}

.home__alert-tile > svg {
  inline-size: 1.25rem;
  block-size: 1.25rem;
}

.home__card-close {
  align-self: flex-start;
  margin-block-start: 0.1875rem;
  margin-inline-end: -0.3125rem;
}

/* A track with a short stretch of honey running along it: busy, with no claim about how far there is to go. */
.home__progress {
  margin-block: 1rem;
  block-size: 0.25rem;
  overflow: hidden;
  border-radius: var(--radius-pill);
  background: var(--color-track);
}

.home__progress-bar {
  display: block;
  inline-size: 29%;
  block-size: 100%;
  border-radius: inherit;
  background: var(--color-honey);
  animation: home-progress 1.8s var(--motion-ease) infinite alternate;
}

/* The distance is a share of the bar's own width: 245% of 29% is the rest of the track. */
@keyframes home-progress {
  from {
    translate: 0;
  }

  to {
    translate: 245%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .home__progress-bar {
    animation: none;
    translate: 113%;
  }
}

/* A display that forces its own colours drops backgrounds, which would erase the track and the bar. */
@media (forced-colors: active) {
  .home__progress {
    border: 1px solid CanvasText;
  }

  .home__progress-bar {
    forced-color-adjust: none;
    background: Highlight;
  }
}

.home__steps {
  margin-block-end: 1rem;
}

/* A note inside a card: an icon, then a sentence, on the quiet panel. */
.home__note {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: 0.625rem 0.75rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

.home__note > svg {
  flex: none;
}

.home__note > p {
  margin: 0;
}

.home__note--advice {
  gap: 0.625rem;
}

.home__refusal-reason {
  margin: 1.125rem 0 0;
  font-size: var(--font-size-base);
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.home__refusal-reason + .home__note {
  margin-block-start: 0.875rem;
}

.home__card-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4375rem;
  margin-block-start: 0.875rem;
}

/* ---- Where the person left off ---- */

.home__resume {
  inline-size: min(100%, var(--home-column));
  margin-block-start: 2.375rem;
  padding: 1rem 1.1875rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.home__resume-label {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.home__resume-row {
  display: flex;
  align-items: center;
  gap: 0.90625rem;
  margin-block-start: var(--space-2);
}

.home__resume-text {
  flex: 1;
  min-inline-size: 0;
}

/* One line when it fits and two when it does not, so beside the button on a narrow screen a title is not cut to a few letters. */
.home__resume-title {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

/* As tall as a line of tags would be, so the card keeps its height whichever of the two a document has to say. */
.home__resume-started {
  display: flex;
  align-items: center;
  min-block-size: 1.375rem;
  margin: var(--space-1) 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

/* On a narrow screen the button goes under the title, in line with it, so the title keeps the whole width beside the tile. */
@container main (max-width: 26rem) {
  .home__resume-row {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    align-items: start;
  }

  .home__resume-row > .button {
    grid-column: 2;
    justify-self: start;
  }
}

/* ---- How Brownie works ---- */

.home__how {
  inline-size: min(100%, var(--home-column));
  margin-block-start: 3.5rem;
}

.home__section-title {
  margin: 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

/* Each card is as tall as its own words need, so a shorter one is not stretched to match a longer. */
.home__how-cards {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1.0625rem;
  align-items: start;
  margin: 0.5625rem 0 0;
  padding: 0;
  list-style: none;
}

.home__how-head {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin: 0;
}

.home__how-step {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.home__how-title {
  margin: var(--space-2) 0 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.home__how-text {
  margin: 0.625rem 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

@container main (min-width: 40rem) {
  .home__how-cards {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

/* ---- Recent documents ---- */

.home__recent {
  inline-size: min(100%, var(--home-column));
  margin-block-start: 1.5625rem;
}

.home__how + .home__recent {
  margin-block-start: 1.8125rem;
}

/* Nothing yet: a dashed box rather than a card, so it reads as a place waiting to be filled and not as content. */
.home__empty {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-block-start: var(--space-2);
  padding: 1.125rem 1.375rem 1.125rem 1.25rem;
  border: 1px dashed var(--color-outline);
  border-radius: var(--radius-lg);
  color: var(--color-text-muted);
}

.home__empty > svg {
  flex: none;
}

.home__hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.45;
}

.home__problem {
  margin: var(--space-3) 0 0;
}

.home__notice {
  margin: var(--space-3) 0 0;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-md);
  background: var(--color-cocoa-wash);
}

/* The shared link colour measures 4.47:1 on this wash, just under AA; the text colour is 10.17:1 and the underline still says "link". */
.home__notice a {
  color: var(--color-text);
}

.home__day {
  margin-block-start: 0.78125rem;
}

.home__day + .home__day {
  margin-block-start: 0.6875rem;
}

.home__day-heading {
  margin: 0 0 0.4375rem;
  padding-inline-start: 0.21875rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.home__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.home__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  column-gap: var(--space-3);
}

/*
 * The content of a row sits a few pixels in from the column's edge, and the wash it takes under the pointer reaches
 * a little past that edge, so the tile lines up with the heading and the cards above while the row still has
 * room around what it holds.
 */
.home__row {
  display: grid;
  /* A document row is tile, title, time; a batch row adds its count between title and time. */
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 1.375rem;
  min-block-size: 3.625rem;
  margin-inline: -0.5rem;
  padding: var(--space-2) 1.21875rem var(--space-2) 0.71875rem;
  border-radius: var(--radius-md);
  color: var(--color-text);
  text-decoration: none;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.home__row:hover {
  background: var(--color-cocoa-wash);
}

.home__row-title {
  overflow: hidden;
  font-size: var(--font-size-lg);
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home__row-time {
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  font-variant-numeric: tabular-nums;
}

/* A batch entry's document count sits beside its title in its own element, so a long title truncates and the count always shows. */
.home__row-count {
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

/*
 * Quiet until wanted: the trash control shows when its row is under the
 * pointer or holds the keyboard focus. It stays in the layout and in the Tab
 * order while hidden, so focusing it is what shows it and nothing moves.
 */
.home__trash {
  opacity: 0;
  transition:
    opacity var(--motion-fast) var(--motion-ease),
    background-color var(--motion-fast) var(--motion-ease),
    color var(--motion-fast) var(--motion-ease);
}

.home__item:hover .home__trash,
.home__item:focus-within .home__trash {
  opacity: 1;
}

/* A touch screen has no hover to reveal it with, so there it is always shown. */
@media (hover: none), (pointer: coarse) {
  .home__trash {
    opacity: 1;
  }
}

/* With room to spare the control hangs outside the column; without it, it takes its share of the row. */
@container main (min-width: 48rem) {
  .home__item {
    margin-inline-end: calc(-1 * var(--home-row-action));
  }
}

@media (pointer: coarse) {
  .home {
    --home-row-action: calc(2.75rem + var(--space-3));
  }
}

/* On a narrow screen the time moves under the title instead of squeezing it. */
@container main (max-width: 26rem) {
  .home__row {
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--space-1) var(--space-3);
  }

  /* The tile spans both rows so it stays beside the pair, not above the time. */
  .home__row > .doc-tile {
    grid-row: 1 / -1;
  }

  .home__row-time {
    grid-column: 2;
  }
}

/* ---- A finished form, said at the corner of the page ---- */

/*
 * Placed against the region beside the sidebar, 25px in from its top right corner, so it keeps its place whatever the
 * width of the window or of the sidebar; below the bar that holds the menu button when the sidebar is a drawer. It
 * scrolls away with the top of the page instead of covering the list. A single line when there is room, and it
 * wraps when there is not.
 */
.home__toast {
  position: absolute;
  inset-block-start: calc(var(--shell-bar-row, 0px) + 1.5625rem);
  inset-inline-end: 1.5625rem;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  max-inline-size: calc(100% - 3.125rem);
  padding: 0.375rem 0.5rem 0.375rem 1rem;
  border-radius: var(--radius-md);
  background: var(--color-surface);
  box-shadow: var(--shadow-popover);
}

.home__toast-check {
  flex: none;
  color: var(--color-success);
}

.home__toast-text {
  margin: 0;
  font-size: var(--font-size-sm);
  line-height: 1.45;
  overflow-wrap: anywhere;
}

/* Cocoa on white is 4.8:1; against the text beside it the weight and the colour tell it apart, and hover underlines it. */
.home__toast-link {
  flex: none;
  color: var(--color-cocoa);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  text-decoration: none;
}

.home__toast-link:hover {
  text-decoration: underline;
}

/* ---- The drop zone ---- */

/*
 * Over the main region, 24px in from its edges and from the sidebar, in the page's own colour so that what is under
 * it does not show through. It takes no pointer events: the drag is followed on the window, and the zone only has to
 * be seen. The dashed frame is an outline drawn as a mask over a cocoa fill, which gives a dash 10px long and a gap
 * 8px long (a dashed border draws its own, shorter ones) while the colour still comes from the shared token.
 */
.home__drop {
  --home-fine: 0.8125rem;
  position: fixed;
  inset: 1.5rem 1.5rem 1.5rem calc(var(--home-drop-left, 0px) + 1.5rem);
  z-index: 20;
  display: grid;
  place-items: center;
  border-radius: 1.5rem;
  background: var(--color-paper);
  pointer-events: none;
  animation: home-drop-in var(--motion-base) var(--motion-ease);
}

@keyframes home-drop-in {
  from {
    opacity: 0;
  }
}

.home__drop::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: var(--color-cocoa);
  mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='100%25' height='100%25'%3E%3Crect width='100%25' height='100%25' rx='24' ry='24' fill='none' stroke='black' stroke-width='4' stroke-dasharray='10 8'/%3E%3C/svg%3E")
    center / 100% 100% no-repeat;
}

/* A display that forces its own colours drops backgrounds, which would erase a frame drawn as one. */
@media (forced-colors: active) {
  .home__drop::before {
    forced-color-adjust: none;
    background: CanvasText;
  }
}

.home__drop-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--space-5);
  text-align: center;
}

.home__drop-icon {
  display: grid;
  place-items: center;
  inline-size: 5.5rem;
  block-size: 5.5rem;
  border-radius: 50%;
  background: var(--color-cocoa-soft);
  color: var(--color-cocoa);
}

.home__drop-title {
  margin: var(--space-3) 0 0;
  font-size: var(--font-size-2xl);
  line-height: 1.2;
}

.home__drop-text {
  max-inline-size: 34rem;
  margin: 0.875rem 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-base);
  line-height: 1.45;
}

.home__drop-note {
  margin: var(--space-3) 0 0;
  color: var(--color-text-muted);
  font-size: var(--home-fine);
  line-height: 1.4;
}

/* On a narrow screen the label keeps its size, so the room it needs comes out of the gap and the padding. */
@container main (max-width: 26rem) {
  .home__upload {
    gap: var(--space-3);
    padding-inline: var(--space-4);
    text-wrap: balance;
  }
}
</style>
