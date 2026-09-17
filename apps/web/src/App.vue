<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { navigateTo } from '@/navigation'

const session = useSessionStore()
const signingOut = ref(false)
const signOutError = ref<string | null>(null)

onMounted(() => {
  if (session.status === 'unknown') {
    void session.loadIdentity()
  }
})

/**
 * A button, not a link: signing out is a CSRF-checked POST (see the
 * repository README's CSRF section), which a plain anchor to /logout can
 * never make -- the server answers that GET with a 404 and the session
 * quietly stays alive. Once the server confirms the session is gone, the
 * page navigates to the identity provider's end-session URL it returned,
 * which signs the person out there too and brings them back here.
 */
async function signOut(): Promise<void> {
  signingOut.value = true
  signOutError.value = null
  try {
    const redirectUrl = await session.signOut()
    navigateTo(redirectUrl || '/')
  } catch {
    signOutError.value = 'Could not sign out. Try again.'
  } finally {
    signingOut.value = false
  }
}
</script>

<template>
  <a class="visually-hidden" href="#main-content">Skip to main content</a>
  <header class="app-header">
    <RouterLink to="/" class="app-brand">Brownie</RouterLink>
    <div v-if="session.status === 'authenticated'" class="app-account">
      <span>{{ session.identity?.displayName ?? session.identity?.email }}</span>
      <button type="button" class="button button--secondary" :disabled="signingOut" @click="signOut">
        {{ signingOut ? 'Signing out…' : 'Sign out' }}
      </button>
    </div>
  </header>
  <main id="main-content">
    <p v-if="signOutError" class="field-error" role="alert">{{ signOutError }}</p>
    <section v-if="session.status === 'error'" class="card" role="alert">
      <h1>Could not confirm your sign-in</h1>
      <p>{{ session.lastError }} Try again in a moment, or reload the page.</p>
      <button type="button" class="button button--primary" @click="session.loadIdentity()">Try again</button>
    </section>
    <RouterView v-else />
  </main>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}

.app-brand {
  font-weight: 700;
  font-size: var(--font-size-lg);
  color: var(--color-text);
  text-decoration: none;
}

.app-account {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  color: var(--color-text-secondary);
}

main {
  max-width: 960px;
  margin: 0 auto;
  padding: var(--space-6) var(--space-5);
}
</style>
