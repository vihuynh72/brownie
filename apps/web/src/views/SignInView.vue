<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { isInAppPath, rememberSignInIntent } from '@/auth/intent'

const route = useRoute()

/** Where the person was going when this page interrupted them; anything that is not an in-app path is ignored. */
const next = computed(() => (isInAppPath(route.query.next) ? route.query.next : null))

/**
 * Says what signing in is for, in the words of the thing they just tried
 * to do. A destination with no sentence of its own falls back to the
 * general one rather than guessing at a description.
 */
const PURPOSE: Record<string, string> = {
  '/documents/new': 'to upload your documents and start a new one',
  '/templates/new': 'to teach Brownie one of your templates',
  '/trash': 'to open your trash bin',
  '/your-data': 'to see what Brownie keeps and for how long',
  '/connections': 'to see and manage the accounts you have connected',
  '/chat': 'to work on a document with Brownie',
}

/** Set by the page that deleted the workspace, so the first thing the person reads is that it worked. */
const workspaceDeleted = computed(() => route.query.deleted === '1')

const purpose = computed(() => {
  if (!next.value) return null
  const path = next.value.split('?')[0] ?? ''
  if (PURPOSE[path]) return PURPOSE[path]
  return /^\/documents\/\d+$/.test(path) ? 'to open that document' : null
})

/**
 * Signing in is a full-page navigation to the identity provider, so this
 * page cannot hold on to anything in memory. The destination is parked
 * before the browser leaves, and the shell picks it up when the session
 * comes back. The anchor's own navigation is deliberately not prevented.
 */
function keepDestination(): void {
  if (next.value) rememberSignInIntent(next.value)
}
</script>

<template>
  <section class="card signin">
    <h1 class="signin__title">Sign in to Brownie</h1>

    <p v-if="workspaceDeleted" class="signin__notice" role="status">
      Your workspace and everything in it was deleted. Signing in again starts a new, empty one.
    </p>
    <p v-else-if="purpose">You need to be signed in {{ purpose }}.</p>
    <p v-else>Sign in with your account to see your documents.</p>

    <p class="field-hint">
      Brownie uses one page for both: sign in with an account you already have, or create one there if this is your
      first time.
    </p>

    <a class="button button--primary signin__action" href="/oauth2/authorization/entra" @click="keepDestination">
      <span>Sign in or sign up</span>
      <AppIcon name="sign-in" :size="18" />
    </a>
  </section>
</template>

<style scoped>
.signin {
  max-inline-size: 30rem;
  margin-inline: auto;
  margin-block-start: var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.signin__title {
  margin: 0;
  font-size: var(--font-size-xl);
}

.signin__notice {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
}

.signin__action {
  inline-size: fit-content;
  border-radius: var(--radius-pill);
  padding-inline: var(--space-5);
}
</style>
