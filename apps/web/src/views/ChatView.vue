<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { listDocuments, type DocumentSummaryResponse } from '@/api/client'

/**
 * Where a conversation with Brownie starts.
 *
 * Brownie's assistant reads and changes one document: it works against
 * that document's fields, its sources, and its checks, and every change it
 * proposes is accepted or rejected there. So this page does not hold a
 * conversation of its own -- it takes you to the document the conversation
 * would be about.
 */
const session = useSessionStore()
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

onMounted(loadDocuments)
watch(
  () => session.personalWorkspaceId,
  (workspaceId) => {
    if (workspaceId !== undefined) void loadDocuments()
  },
)
</script>

<template>
  <section class="chat">
    <h1 class="chat__title">Chat with Brownie</h1>
    <p>
      Brownie works on one document at a time. Open a document and its Assist tab takes the same instructions this page
      would: draft from the sources you attached, change a field to a value, shorten or rewrite a passage, or explain a
      check that is blocking your export. It shows you what it would change before anything is applied.
    </p>

    <h2 class="chat__subtitle">Open a document</h2>
    <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Loading documents…</p>
    <p v-else-if="loadState === 'error'" class="field-error" role="alert">
      Something went wrong loading your documents. Try reloading the page.
    </p>
    <p v-else-if="recent.length === 0" class="field-hint">You don't have any documents yet.</p>
    <ul v-else class="chat__list">
      <li v-for="document in recent" :key="document.id">
        <RouterLink class="chat__row" :to="`/documents/${document.id}`">
          <span class="chat__tile" aria-hidden="true"><AppIcon name="document" :size="20" /></span>
          <span class="chat__row-title">{{ document.title }}</span>
        </RouterLink>
      </li>
    </ul>

    <RouterLink class="button button--primary chat__start" to="/documents/new">
      <AppIcon name="upload" :size="18" />
      <span>Start a new document</span>
    </RouterLink>
  </section>
</template>

<style scoped>
.chat {
  max-inline-size: 42rem;
  margin-inline: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.chat__title {
  margin: 0;
  font-size: var(--font-size-xl);
}

.chat__subtitle {
  margin: var(--space-4) 0 0;
  font-size: var(--font-size-base);
}

.chat__list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.chat__row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-block-size: 3rem;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  color: var(--color-text);
  text-decoration: none;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.chat__row:hover {
  background: var(--color-cocoa-wash);
}

.chat__row:hover .chat__row-title {
  text-decoration: underline;
}

.chat__tile {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  inline-size: 2.25rem;
  block-size: 2.25rem;
  border-radius: var(--radius);
  background: var(--color-cocoa-tile);
  color: var(--color-surface);
}

.chat__row-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.chat__start {
  inline-size: fit-content;
  margin-block-start: var(--space-4);
  border-radius: var(--radius-pill);
  padding-inline: var(--space-5);
}
</style>
