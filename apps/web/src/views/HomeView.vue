<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { listDocuments, type DocumentSummaryResponse } from '@/api/client'

const session = useSessionStore()
const route = useRoute()
const documents = ref<DocumentSummaryResponse[]>([])

// A sign-in that the identity provider refused comes back here as /?signin=failed&reason=<code>
// (see the API's own sign-in failure handling). The code is rendered as text only.
const signInFailed = computed(() => route.query.signin === 'failed')
const signInFailureReason = computed(() => (typeof route.query.reason === 'string' ? route.query.reason : null))
const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

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
  } catch {
    loadState.value = 'error'
  }
}

onMounted(loadDocuments)
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
    <p v-if="signInFailed" class="card home__signin-error" role="alert">
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

      <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading documents…</p>
      <p v-else-if="loadState === 'error'" class="field-error" role="alert">
        Something went wrong loading your documents. Try reloading the page.
      </p>
      <p v-else-if="documents.length === 0" class="field-hint">
        You don't have any documents yet. Upload your notes above, or
        <RouterLink to="/documents/new">start one from a template</RouterLink>.
      </p>

      <div v-for="day in recentDays" :key="day.key" class="home__day">
        <h3 class="home__day-heading">{{ day.heading }}</h3>
        <ul class="home__list">
          <li v-for="entry in day.documents" :key="entry.document.id">
            <RouterLink class="home__row" :to="`/documents/${entry.document.id}`">
              <span class="home__tile" aria-hidden="true"><AppIcon name="document" :size="20" /></span>
              <span class="home__row-title" :title="entry.document.title">{{ entry.document.title }}</span>
              <span class="home__row-time">{{ entry.time }}</span>
            </RouterLink>
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
