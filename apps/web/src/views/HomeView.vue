<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { ApiRequestError, listDocuments, trashDocument, type DocumentSummaryResponse } from '@/api/client'

const session = useSessionStore()
const route = useRoute()
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

const greeting = computed(() =>
  session.firstName ? `What's on your mind today, ${session.firstName}?` : "What's on your mind today?",
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
      trashError.value = `This Brownie server cannot move documents to the trash yet, so nothing was changed and "${document.title}" is still here. The server needs to be updated first.`
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

    <!--
      The upload action is the same link whoever is looking at it: a signed-out
      visitor following it is sent to the sign-in page and brought back here to
      the document they were starting, rather than being told in advance what
      they are not allowed to do.
    -->
    <RouterLink class="button button--primary home__upload" to="/documents/new">
      <AppIcon name="upload" :size="22" />
      <span>Upload your documents</span>
    </RouterLink>

    <section v-if="session.status === 'authenticated'" class="home__recent" aria-labelledby="recent-heading">
      <h2 id="recent-heading" class="home__recent-title">Recent documents</h2>

      <p v-if="trashNotice" ref="trashNoticeElement" class="home__notice" tabindex="-1">
        {{ trashNotice }} <RouterLink to="/trash">Restore it from the trash bin</RouterLink>.
      </p>
      <p v-if="trashError" ref="trashErrorElement" class="field-error" role="alert" tabindex="-1">{{ trashError }}</p>

      <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading documents…</p>
      <p v-else-if="loadState === 'error'" class="field-error" role="alert">
        {{ loadError }}
      </p>
      <p v-else-if="documents.length === 0 && removedFromList" class="field-hint">
        There are no documents here now. Anything moved to the trash can be restored from the
        <RouterLink to="/trash">trash bin</RouterLink>.
      </p>
      <p v-else-if="documents.length === 0" class="field-hint">
        You don't have any documents yet. Upload your notes above, or
        <RouterLink to="/documents/new">start one from a template</RouterLink>.
      </p>

      <div v-for="day in recentDays" :key="day.key" class="home__day">
        <h3 class="home__day-heading">{{ day.heading }}</h3>
        <ul class="home__list">
          <!-- The action sits beside the link, never inside it: a button nested in a link is neither. -->
          <li v-for="entry in day.documents" :key="entry.document.id" class="home__item">
            <RouterLink class="home__row" :to="`/documents/${entry.document.id}`">
              <span class="home__tile" aria-hidden="true"><AppIcon name="document" :size="20" /></span>
              <span class="home__row-title" :title="entry.document.title">{{ entry.document.title }}</span>
              <span class="home__row-time">{{ entry.time }}</span>
            </RouterLink>
            <button
              :id="`home-trash-${entry.document.id}`"
              type="button"
              class="icon-button"
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
.home {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-5);
  inline-size: 100%;
  max-inline-size: 46rem;
  margin-inline: auto;
  padding-block-start: var(--space-6);
}

.home__signin-error {
  inline-size: 100%;
  border-color: var(--color-error);
}

.home__greeting {
  margin: 0;
  font-size: var(--font-size-display);
  font-weight: 600;
  line-height: 1.15;
  text-align: center;
  text-wrap: balance;
}

.home__upload {
  gap: var(--space-3);
  min-block-size: 3.5rem;
  padding-inline: var(--space-6);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  font-weight: 500;
  /* A small lift under the pointer: enough to read as a control, not enough to be a performance. */
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    translate var(--motion-fast) var(--motion-ease),
    box-shadow var(--motion-fast) var(--motion-ease);
}

.home__upload:hover {
  translate: 0 -1px;
  box-shadow: 0 0.5rem 1rem rgb(42 41 36 / 0.12);
}

.home__upload:active {
  translate: 0 0;
  box-shadow: none;
}

.home__recent {
  inline-size: 100%;
  margin-block-start: var(--space-5);
}

.home__recent-title {
  margin: 0 0 var(--space-3);
  font-size: var(--font-size-sm);
  font-weight: 600;
  color: var(--color-text-muted);
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
  gap: var(--space-4);
  min-block-size: 3rem;
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
  inline-size: 2.25rem;
  block-size: 2.25rem;
  border-radius: var(--radius);
  background: var(--color-cocoa-tile);
  color: var(--color-surface);
}

.home__row-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.home__row-time {
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

/* On a narrow screen the time moves under the title instead of squeezing it. */
@container main (max-width: 26rem) {
  .home__row {
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--space-3);
  }

  /* The tile spans both rows so it stays beside the pair, not above the time. */
  .home__tile {
    grid-row: 1 / -1;
  }

  .home__row-time {
    grid-column: 2;
  }

  .home__upload {
    inline-size: 100%;
  }
}
</style>
