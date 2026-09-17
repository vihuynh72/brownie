<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
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
watch(() => session.status, (status) => {
  if (status === 'authenticated') {
    void loadDocuments()
  }
})

const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })
function formatDate(iso: string): string {
  return dateFormatter.format(new Date(iso))
}
</script>

<template>
  <section v-if="session.status === 'anonymous'" class="card">
    <h1>Sign in to Brownie</h1>
    <p v-if="signInFailed" class="field-error" role="alert">
      Sign-in did not complete<template v-if="signInFailureReason"> ({{ signInFailureReason }})</template>. Try again; if it
      keeps failing, pass that reason on to whoever runs this Brownie.
    </p>
    <p v-else>Sign in with your account to see your documents.</p>
    <a class="button button--primary" href="/oauth2/authorization/entra">{{ signInFailed ? 'Try again' : 'Sign in' }}</a>
  </section>

  <section v-else-if="session.status === 'loading' || session.status === 'unknown'" aria-live="polite">
    <p>Loading your account…</p>
  </section>

  <section v-else>
    <div class="list-header">
      <h1>Your documents</h1>
      <div class="list-header__actions">
        <RouterLink class="button" to="/templates/new">Teach a template</RouterLink>
        <RouterLink class="button button--primary" to="/documents/new">New document</RouterLink>
      </div>
    </div>

    <p v-if="loadState === 'loading'" aria-live="polite">Loading documents…</p>
    <p v-else-if="loadState === 'error'" class="field-error" role="alert">
      Something went wrong loading your documents. Try reloading the page.
    </p>
    <p v-else-if="documents.length === 0">
      You don't have any documents yet.
      <RouterLink to="/documents/new">Create your first one</RouterLink>.
    </p>
    <ul v-else class="document-list">
      <li v-for="document in documents" :key="document.id" class="card document-row">
        <RouterLink :to="`/documents/${document.id}`" class="document-title">{{ document.title }}</RouterLink>
        <span class="field-hint">Created {{ formatDate(document.createdAt) }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
}

.list-header__actions {
  display: flex;
  gap: var(--space-3);
}

.document-list {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.document-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-4) var(--space-5);
}

.document-title {
  font-weight: 600;
  color: var(--color-text);
  text-decoration: none;
}

.document-title:hover {
  text-decoration: underline;
}
</style>
