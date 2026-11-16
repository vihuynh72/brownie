<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import BrownieBrand from '@/components/BrownieBrand.vue'
import { getWelcome, type WelcomeResponse } from '@/api/client'
import { isInAppPath, rememberSignInIntent } from '@/auth/intent'

const route = useRoute()

/** Where the person was going when this page interrupted them; anything that is not an in-app path is ignored. */
const next = computed(() => (isInAppPath(route.query.next) ? route.query.next : null))
const selectedForm = computed(() => {
  if (!next.value) return false
  const destination = new URL(next.value, window.location.origin)
  const forms = destination.searchParams.getAll('form')
  return destination.origin === window.location.origin && destination.pathname === '/' && forms.length === 1 && /^[a-z0-9]+-[a-z0-9-]{1,70}$/.test(forms[0]!)
})

/**
 * Says what signing in is for, in the words of the thing they just tried
 * to do. A destination with no sentence of its own falls back to the
 * general one rather than guessing at a description.
 */
const PURPOSE: Record<string, string> = {
  // Home's upload button sends a signed-out visitor here, and back to Home to choose the file.
  '/': 'to upload your documents and start a new one',
  '/trash': 'to open your trash bin',
  '/your-data': 'to see what Brownie keeps and for how long',
  '/connections': 'to see and manage the accounts you have connected',
  '/chat': 'to work on a document with Brownie',
}

/** Set by the page that deleted the workspace, so the first thing the person reads is that it worked. */
const workspaceDeleted = computed(() => route.query.deleted === '1')

const welcome = ref<WelcomeResponse | null>(null)
const welcomeState = ref<'loading' | 'loaded' | 'error'>('loading')
async function loadWelcome(): Promise<void> {
  welcomeState.value = 'loading'
  try {
    welcome.value = await getWelcome()
    welcomeState.value = 'loaded'
  } catch {
    welcomeState.value = 'error'
  }
}
onMounted(loadWelcome)

/** Google as the big button only while it carries the flag; otherwise it is a small early-access link. */
const googlePrimary = computed(() => welcome.value?.signInProviders?.[0] === 'google')
const emailCodeAvailable = computed(() => welcome.value?.signInProviders?.includes('entra') ?? true)
const googleSecondary = computed(
  () => (welcome.value?.signInProviders?.includes('google') ?? false) && !googlePrimary.value)

/**
 * What the deployment says about sign-up before anyone is in: a free daily allowance, an open
 * door, or invitation only. A welcome answer that never comes says the quiet thing, which is
 * what the page always said.
 */
const accessNote = computed(() => {
  if (welcome.value?.signUp === 'OPEN_WITH_DAILY_ALLOWANCE')
    return 'Free to use every day; invited people get more. Your workspace is private to you.'
  if (welcome.value?.signUp === 'OPEN') return 'Your workspace is private to you.'
  return 'Open to invited people only. Your workspace is private to you.'
})

const purpose = computed(() => {
  if (!next.value) return null
  const path = next.value.split('?')[0] ?? ''
  if (PURPOSE[path]) return PURPOSE[path]
  return /^\/documents\/\d+$/.test(path) ? 'to open that document' : null
})

const ONE_PAGE_FOR_BOTH = 'Sign in or create an account to continue.'

/**
 * One paragraph under the title: why the person is here, then how the provider's page works, which is one page
 * for signing in and for signing up. Someone who has just deleted their workspace is told that in a notice of its
 * own, and "you need to be signed in" would read oddly right after it, so that sentence is left out for them.
 */
const lede = computed(() => {
  if (workspaceDeleted.value) return ONE_PAGE_FOR_BOTH
  if (selectedForm.value) return 'Sign in to continue with your selected form.'
  return `You need to be signed in ${purpose.value ?? 'to open your documents'}. ${ONE_PAGE_FOR_BOTH}`
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
  <section class="page-column page-column--narrow signin">
    <header class="page-head page-head--center">
      <!-- The title right below says the same word, so a screen reader skips the mark rather than read it twice. -->
      <p class="signin__brand" aria-hidden="true">
        <BrownieBrand />
      </p>

      <h1 class="page-title">Sign in to Brownie</h1>

      <p v-if="workspaceDeleted" class="signin__notice" role="status">
        <AppIcon name="check-circle" :size="18" />
        <span>Your workspace and everything in it was deleted. Signing in again starts a new, empty one.</span>
      </p>

      <p class="page-lede signin__lede">{{ lede }}</p>
    </header>

    <p v-if="welcomeState === 'loading'" class="signin__provider-status" role="status">Loading sign-in options…</p>
    <div v-else-if="welcomeState === 'error'" class="signin__provider-status" role="alert">
      <p>Sign-in options could not be loaded.</p>
      <button type="button" class="button button--secondary button--small" @click="loadWelcome">Try again</button>
    </div>
    <a v-if="welcomeState === 'loaded' && googlePrimary" class="signin__google" href="/oauth2/authorization/google" @click="keepDestination">
      <svg class="signin__google-mark" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
        <path fill="#4285F4" d="M23.49 12.27c0-.79-.07-1.54-.19-2.27H12v4.51h6.47a5.57 5.57 0 0 1-2.4 3.58v3h3.86c2.26-2.09 3.56-5.17 3.56-8.82Z"/>
        <path fill="#34A853" d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.86-3c-1.08.72-2.45 1.16-4.07 1.16-3.13 0-5.78-2.11-6.73-4.96H1.29v3.09A11.99 11.99 0 0 0 12 24Z"/>
        <path fill="#FBBC05" d="M5.27 14.29A7.2 7.2 0 0 1 4.89 12c0-.8.14-1.57.38-2.29V6.62H1.29a12 12 0 0 0 0 10.76l3.98-3.09Z"/>
        <path fill="#EA4335" d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42A11.97 11.97 0 0 0 12 0 11.99 11.99 0 0 0 1.29 6.62l3.98 3.09C6.22 6.86 8.87 4.75 12 4.75Z"/>
      </svg>
      <span>Continue with Google</span>
    </a>
    <a v-if="welcomeState === 'loaded' && googlePrimary && emailCodeAvailable" class="signin__email-code" href="/oauth2/authorization/entra" @click="keepDestination">
      Use an email code instead
    </a>
    <a v-if="welcomeState === 'loaded' && !googlePrimary && emailCodeAvailable" class="button button--primary signin__action" href="/oauth2/authorization/entra" @click="keepDestination">
      <span>Sign in or sign up</span>
      <AppIcon name="sign-in" :size="22" />
    </a>
    <a v-if="welcomeState === 'loaded' && googleSecondary" class="signin__email-code signin__google-secondary" href="/oauth2/authorization/google" @click="keepDestination">
      <svg class="signin__google-mark" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
        <path fill="#4285F4" d="M23.49 12.27c0-.79-.07-1.54-.19-2.27H12v4.51h6.47a5.57 5.57 0 0 1-2.4 3.58v3h3.86c2.26-2.09 3.56-5.17 3.56-8.82Z"/>
        <path fill="#34A853" d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.86-3c-1.08.72-2.45 1.16-4.07 1.16-3.13 0-5.78-2.11-6.73-4.96H1.29v3.09A11.99 11.99 0 0 0 12 24Z"/>
        <path fill="#FBBC05" d="M5.27 14.29A7.2 7.2 0 0 1 4.89 12c0-.8.14-1.57.38-2.29V6.62H1.29a12 12 0 0 0 0 10.76l3.98-3.09Z"/>
        <path fill="#EA4335" d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42A11.97 11.97 0 0 0 12 0 11.99 11.99 0 0 0 1.29 6.62l3.98 3.09C6.22 6.86 8.87 4.75 12 4.75Z"/>
      </svg>
      <span>Continue with Google (early access)</span>
    </a>

    <p v-if="welcomeState === 'loaded' && !googlePrimary && !emailCodeAvailable && !googleSecondary" class="signin__provider-status" role="status">No sign-in options are available. Contact whoever gave you access to Brownie.</p>

    <p v-if="welcomeState === 'loaded'" class="signin__note">
      <AppIcon name="shield-check" :size="16" />
      {{ accessNote }}
    </p>

    <p class="signin__guidance">Bring a form, add your notes, and review each value before exporting.</p>
  </section>
</template>

<style scoped>
.signin__provider-status {
  margin: 0 auto;
  text-align: center;
  color: var(--color-text-muted);
}
.signin__provider-status p { margin: 0 0 var(--space-3); }

/*
 * One block, centred in the room the shell gives the page, which with the sidebar docked is the window's middle line.
 * The shell keeps some room clear above the page (more when the sidebar button floats over its corner) and a
 * different amount below it. So the page fills the height that is left, less a little slack in case the bar above
 * it turns out a pixel or two taller than the shell says (a page that overshoots would scroll for nothing), and
 * then moves by half the difference between what is clear above and below. The move only changes where the block is
 * drawn, so it never adds height to the page: taller than the room (a phone, a zoomed window), the block simply
 * grows and the page scrolls.
 */
.signin {
  --signin-above: calc(var(--shell-bar-block, 0px) + var(--shell-gutter-block-start, 1.5rem));
  --signin-below: var(--shell-gutter-block-end, 3rem);
  --signin-slack: 0.5rem;
  /* The shell's own margins already keep the block off the window edge. */
  --page-gutter: 0px;
  position: relative;
  inset-block-start: calc((var(--signin-below) - var(--signin-above) + var(--signin-slack)) / 2);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-block-size: calc(
    100dvh - var(--shell-bar-row, 0px) - var(--signin-above) - var(--signin-below) - var(--signin-slack)
  );
  padding-block: 0;
}

/* Each stacked part (mark, title, paragraph, button, note, steps) is given the room it wants by its own margin. */
.signin__brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  margin: 0 0 1.3125rem;
}

/* About 80 characters a line: at this width the wording without a destination runs to three lines. */
.signin__lede {
  max-inline-size: 38.625rem;
  margin: 1.25rem auto 0;
}

.signin__notice {
  display: flex;
  align-items: flex-start;
  gap: 0.625rem;
  inline-size: fit-content;
  max-inline-size: min(42rem, 100%);
  margin: 1.25rem auto 0;
  padding: 0.75rem 1rem;
  border-radius: var(--radius-md);
  background: var(--color-success-wash);
  font-size: var(--font-size-sm);
  line-height: 1.45;
  text-align: start;
  /* On a narrow screen the sentence breaks into even lines, not a full one and a stray word. */
  text-wrap: balance;
}

/* Google's button keeps the white it comes in: the brand mark only reads on light, and a tinted primary would be a different brand. */
.signin__google {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  margin: 0 auto;
  padding: 0.75rem 1.5rem;
  border-radius: var(--radius-md);
  background: #fff;
  border: 1px solid var(--color-hairline);
  color: #1f1f1f;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
}

.signin__google:hover {
  background: #f8f9fa;
}

.signin__google-mark {
  flex-shrink: 0;
}

.signin__email-code {
  display: block;
  margin: 0.75rem auto 0;
  font-size: var(--font-size-sm);
  color: var(--color-secondary);
  text-align: center;
}

.signin__notice > svg {
  margin-block-start: 0.0625rem;
  color: var(--color-success);
}

/* The one main action: the shared pill, drawn larger. The label stays at the weight every button uses. */
.signin__action {
  --button-height: 3.5rem;
  --button-pad: 1.875rem;
  --button-icon: 1.375rem;
  --button-gap: 0.75rem;
  margin-block-start: 1.0625rem;
  font-size: var(--font-size-lg);
}

/*
 * Small print under the button, set a step above the 12px of tags so the sentence reads as a sentence. The icon
 * sits in the text itself, so on a narrow screen it stays with the first line instead of being left at the edge.
 */
.signin__note {
  margin: 1.125rem 0 0;
  color: var(--color-text-muted);
  font-size: 0.8125rem;
  line-height: 1.4;
  text-align: center;
}

.signin__note > svg {
  /* The space after the icon in the markup is about 3px; this makes up the rest of the gap. */
  margin-inline-end: 0.3125rem;
  vertical-align: middle;
}

.signin__guidance {
  max-inline-size: 32rem;
  margin: 2.5rem 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.5;
  text-align: center;
}

.signin__google { transition: background-color 160ms ease, border-color 160ms ease; }
</style>
