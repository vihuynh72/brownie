<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { ApiRequestError, listDocuments, trashDocument, type DocumentSummaryResponse } from '@/api/client'
import { documentHandoffState } from '@/router/handoff'
import { FORM_FILE_ACCEPT, learnFormAndStartDocument, type LearnStep } from '@/upload/learnAndStart'
import { formUploadError, formUploadFinished, formUploadStep } from '@/upload/formUploadState'

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

// A typographer's apostrophe, as the design sets it.
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
}

onMounted(loadDocuments)

// ---- Uploading a form ------------------------------------------------------------------------
//
// The file chooser belongs to a hidden input, opened by the big button, so the button can look
// like the design and still be one ordinary control. While a form is being learned the button
// says it is unavailable with aria-disabled rather than the disabled attribute, which would drop
// the keyboard focus it holds; a press in that time does nothing.

const STEP_WORDS: Record<LearnStep, string> = {
  uploading: 'Uploading…',
  checking: 'Checking the file…',
  learning: 'Learning where the values go…',
  preparing: 'Getting the template ready…',
  opening: 'Opening your document…',
}

const fileInput = ref<HTMLInputElement | null>(null)
// Kept outside the page (see formUploadState), so leaving Home mid-way loses neither the step nor how it ended.
const uploadStep = formUploadStep
const uploadError = formUploadError
const uploadFinished = formUploadFinished
const uploading = computed(() => uploadStep.value !== null)
const uploadStatus = computed(() => (uploadStep.value ? STEP_WORDS[uploadStep.value] : ''))
// Someone who moves on while a form is being learned is not pulled back to it when it is ready: the
// document is in their recent documents, and the template under My Templates, either way.
let leftHome = false
onBeforeUnmount(() => {
  leftHome = true
  // A refusal already shown here is not said again on the next visit; one that comes while the person is away is.
  if (!uploading.value) uploadError.value = null
})

function chooseForm(): void {
  if (uploading.value) return
  fileInput.value?.click()
}

async function onFormChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // Cleared at once, so choosing the same file again after a refusal is still a change.
  input.value = ''
  const workspaceId = session.personalWorkspaceId
  if (!file || workspaceId === undefined || uploading.value) return

  uploadError.value = null
  uploadFinished.value = null
  uploadStep.value = 'uploading'
  const outcome = await learnFormAndStartDocument(workspaceId, file, (step) => {
    uploadStep.value = step
  })
  if (!outcome.ok) {
    uploadStep.value = null
    uploadError.value = outcome.message
    return
  }
  if (leftHome) {
    // Said on Home when the person comes back, with a way to the document, instead of lost.
    uploadFinished.value = { documentId: outcome.documentId, name: outcome.name, note: outcome.note }
    uploadStep.value = null
    return
  }
  // A note about the form rides along in the pushed route's history state, so it is read on the
  // page the person lands on, and still there if they reload it.
  await router.push({
    path: `/documents/${outcome.documentId}`,
    state: outcome.note ? documentHandoffState({ attachedSources: [], sourceWarning: outcome.note }) : undefined,
  })
  uploadStep.value = null
}

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
    }
  },
)

const timeFormatter = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' })
const thisYearFormatter = new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'short', day: 'numeric' })
const otherYearFormatter = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' })

/** "Today" for today, otherwise the day itself -- the same heading the design groups rows under. */
function dayHeading(date: Date, now: Date): string {
  const sameDay =
    date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
  if (sameDay) return 'Today'
  return date.getFullYear() === now.getFullYear() ? thisYearFormatter.format(date) : otherYearFormatter.format(date)
}

interface DayGroup {
  key: string
  heading: string
  documents: { document: DocumentSummaryResponse; time: string }[]
}

/**
 * Newest first, split into the days they were created on. The grouping key
 * is the local calendar date rather than the timestamp, so two documents
 * made minutes apart either side of midnight land under different days --
 * which is how the person who made them remembers it.
 */
const recentDays = computed<DayGroup[]>(() => {
  const now = new Date()
  const groups = new Map<string, DayGroup>()
  const sorted = [...documents.value].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
  for (const document of sorted) {
    const created = new Date(document.createdAt)
    const key = `${created.getFullYear()}-${created.getMonth()}-${created.getDate()}`
    let group = groups.get(key)
    if (!group) {
      group = { key, heading: dayHeading(created, now), documents: [] }
      groups.set(key, group)
    }
    group.documents.push({ document, time: timeFormatter.format(created) })
  }
  return [...groups.values()]
})
</script>

<template>
  <div class="home">
    <p v-if="signInRefusedAsUninvited" class="card home__signin-error" role="alert">
      This Brownie is open to invited people only, and the account you signed in with is not on its list. If you think it
      should be, ask whoever invited you; signing in again with the same account will not change it.
    </p>
    <p v-else-if="signInFailed" class="card home__signin-error" role="alert">
      Sign-in did not complete<template v-if="signInFailureReason"> ({{ signInFailureReason }})</template>. Try again; if
      it keeps failing, pass that reason on to whoever runs this Brownie.
      <RouterLink to="/signin">Try again</RouterLink>
    </p>

    <h1 class="home__greeting">
      {{ session.status === 'authenticated' ? greeting : 'Welcome to Brownie!' }}
    </h1>

    <div class="home__upload-area">
      <!--
        Signed in, the button opens the file chooser for the form to fill. Signed out it is a link
        to the sign-in page that brings the visitor back here, rather than a control that refuses.
      -->
      <template v-if="session.status === 'authenticated'">
        <button
          type="button"
          class="button button--primary home__upload"
          :aria-disabled="uploading ? 'true' : undefined"
          @click="chooseForm"
        >
          <AppIcon name="upload" :size="40" />
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
        <AppIcon name="upload" :size="40" />
        <span>Upload your documents</span>
      </RouterLink>

      <!-- The page's one polite live region: each step is read out as it starts. -->
      <p class="home__upload-status" role="status">{{ uploadStatus }}</p>
      <p v-if="uploadError" class="field-error home__upload-error" role="alert">{{ uploadError }}</p>
      <p v-if="uploadFinished" class="home__notice home__upload-finished">
        Brownie learned "{{ uploadFinished.name }}" and started a document from it.
        <RouterLink :to="`/documents/${uploadFinished.documentId}`" @click="uploadFinished = null">Open it</RouterLink>.
        <template v-if="uploadFinished.note"> {{ uploadFinished.note }}</template>
      </p>
    </div>

    <section v-if="session.status === 'authenticated'" class="home__recent" aria-labelledby="recent-heading">
      <h2 id="recent-heading" class="home__recent-title">Recent documents</h2>

      <p v-if="trashNotice" ref="trashNoticeElement" class="home__notice" tabindex="-1">
        {{ trashNotice }} <RouterLink to="/trash">Restore it from the trash bin</RouterLink>.
      </p>
      <p v-if="trashError" ref="trashErrorElement" class="field-error" role="alert" tabindex="-1">{{ trashError }}</p>

      <p v-if="loadState === 'loading'" class="field-hint home__hint">Loading documents…</p>
      <p v-else-if="loadState === 'error'" class="field-error" role="alert">
        {{ loadError }}
      </p>
      <p v-else-if="documents.length === 0 && removedFromList" class="field-hint home__hint">
        There are no documents here now. Anything moved to the trash can be restored from the
        <RouterLink to="/trash">trash bin</RouterLink>.
      </p>
      <p v-else-if="documents.length === 0" class="field-hint home__hint">
        There are no documents here. Upload a Word form above to start one, or choose a template under My Templates.
      </p>

      <div v-for="day in recentDays" :key="day.key" class="home__day">
        <h3 class="home__day-heading">{{ day.heading }}</h3>
        <ul class="home__list">
          <!-- The action sits beside the link, never inside it: a button nested in a link is neither. -->
          <li v-for="entry in day.documents" :key="entry.document.id" class="home__item">
            <RouterLink class="home__row" :to="`/documents/${entry.document.id}`">
              <span class="home__tile" aria-hidden="true"><AppIcon name="document" :size="30" /></span>
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
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
/*
 * Measured from the design at 1512 by 982: a 48px regular-weight greeting
 * whose capitals start 174px down the window, a 78px pill 44px under it,
 * and a list whose drawn part (its heading to the times) is 672px wide, with
 * rows 59px apart. The design's column sits a little right of the middle of
 * the page; this one is centred, so with the sidebar open it is about 15px
 * left of where the drawing has it.
 */
.home {
  display: flex;
  flex-direction: column;
  align-items: center;
  inline-size: 100%;
  max-inline-size: 64rem;
  margin-inline: auto;
  padding-block-start: clamp(var(--space-6), 14.25vh, 8.75rem);
}

.home__signin-error {
  inline-size: 100%;
  max-inline-size: 45rem;
  margin-block-end: var(--space-5);
  border-color: var(--color-error);
}

.home__greeting {
  margin: 0;
  font-size: var(--font-size-display);
  font-weight: 400;
  line-height: 1.15;
  text-align: center;
  text-wrap: balance;
}

/* The button, the line under it that says what is happening, and why it stopped, as one block. */
.home__upload-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  inline-size: 100%;
  margin-block-start: 2.625rem;
}

/*
 * The design's own colour, not the darker action colour: white on it is
 * 3.53:1, under the 4.5:1 ordinary text needs but over the 3:1 that text
 * of 24px and up needs, and this label is 24px. The label must not shrink
 * below that for this pairing to stay readable.
 */
.home__upload {
  gap: var(--space-5);
  min-block-size: 4.875rem;
  padding-block: var(--space-3);
  padding-inline: var(--space-6) 4.5rem;
  border-radius: var(--radius-pill);
  background: var(--color-cocoa-tile);
  font-size: var(--font-size-xl);
  font-weight: 400;
  /* A small lift under the pointer: enough to read as a control, not enough to be a performance. */
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    translate var(--motion-fast) var(--motion-ease),
    box-shadow var(--motion-fast) var(--motion-ease);
}

.home__upload:hover:not([aria-disabled='true']) {
  background: var(--color-cocoa);
  translate: 0 -1px;
  box-shadow: 0 0.5rem 1rem rgb(42 41 36 / 0.12);
}

.home__upload:active:not([aria-disabled='true']) {
  translate: 0 0;
  box-shadow: none;
}

/* Busy, it answers a pointer no more than it answers a press. */
.home__upload[aria-disabled='true']:hover {
  background: var(--color-cocoa-tile);
}

/*
 * Empty until a form is chosen, and then one line that changes. It stays in
 * the page while empty, because a live region that only appears with its
 * first message is not reliably read out.
 */
.home__upload-status {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  text-align: center;
}

.home__upload-error {
  max-inline-size: 36rem;
  margin: 0;
  text-align: center;
}

/*
 * Each row carries its trash control at its end, outside the part the design
 * draws; the same room is left at the start, so the drawn part (heading,
 * tiles, titles, times) is what sits in the middle of the page.
 */
.home__recent {
  --home-row-action: calc(2rem + var(--space-1));

  inline-size: 100%;
  max-inline-size: calc(42.75rem + 2 * var(--home-row-action));
  margin-block-start: 2.25rem;
  padding-inline-start: var(--home-row-action);
}

.home__recent-title {
  margin: 0 0 var(--space-5);
  font-size: var(--font-size-base);
  font-weight: 500;
  color: var(--color-text-muted);
}

.home__hint {
  margin: 0;
  padding-inline-start: var(--space-4);
}

/* The day headings and their rows sit in from the section's own heading, as the design indents them. */
.home__day {
  padding-inline-start: var(--space-4);
}

.home__day + .home__day {
  margin-block-start: var(--space-4);
}

.home__day-heading {
  margin: 0 0 var(--space-1);
  padding-inline-start: var(--space-3);
  font-size: var(--font-size-sm);
  font-weight: 500;
  color: var(--color-text-muted);
}

.home__list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.home__item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-1);
}

.home__notice {
  margin: 0 0 var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
}

/* The default link blue measures 4.48:1 on this wash, just under AA; the text colour is 11.96:1 and the underline still says "link". */
.home__notice a {
  color: var(--color-text);
}

.home__row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-5);
  min-block-size: 3.6875rem;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  color: var(--color-text);
  text-decoration: none;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.home__row:hover {
  background: var(--color-cocoa-wash);
}

.home__row:hover .home__row-title {
  text-decoration: underline;
}

.home__tile {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  inline-size: 2.3125rem;
  block-size: 2.3125rem;
  border-radius: var(--radius);
  background: var(--color-cocoa-tile);
  color: var(--color-surface);
}

.home__row-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--font-size-lg);
}

.home__row-time {
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
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

/* A finger's larger control (see the shared icon button) takes more room at the row's end. */
@media (pointer: coarse) {
  .home__recent {
    --home-row-action: calc(2.75rem + var(--space-1));
  }
}

/* On a narrow screen the time moves under the title instead of squeezing it. */
@container main (max-width: 26rem) {
  .home__row {
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--space-1) var(--space-3);
  }

  /* The tile spans both rows so it stays beside the pair, not above the time. */
  .home__tile {
    grid-row: 1 / -1;
  }

  .home__row-time {
    grid-column: 2;
  }

  .home__recent {
    padding-inline-start: 0;
  }

  .home__day {
    padding-inline-start: 0;
  }

  /* The label keeps its 24px (see the button itself), so the room it needs comes out of the padding. */
  .home__upload {
    inline-size: 100%;
    gap: var(--space-3);
    padding-inline: var(--space-4);
    text-wrap: balance;
  }
}
</style>
