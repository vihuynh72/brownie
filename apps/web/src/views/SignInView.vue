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
  // Home's upload button sends a signed-out visitor here, and back to Home to choose the file.
  '/': 'to upload your documents and start a new one',
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

const ONE_PAGE_FOR_BOTH =
  'Brownie uses one page for both: sign in with an account you already have, ' +
  'or create one there if this is your first time.'

/**
 * One paragraph under the title: why the person is here, then how the provider's page works, which is one page
 * for signing in and for signing up. Someone who has just deleted their workspace is told that in a notice of its
 * own, and "you need to be signed in" would read oddly right after it, so that sentence is left out for them.
 */
const lede = computed(() => {
  if (workspaceDeleted.value) return ONE_PAGE_FOR_BOTH
  return `You need to be signed in ${purpose.value ?? 'to open your documents'}. ${ONE_PAGE_FOR_BOTH}`
})

/** What Brownie does, in three steps, so a visitor can see what the product is before they sign in. */
const STEPS = [
  {
    icon: 'document-upload',
    title: 'Upload a form',
    text: 'Brownie learns where the values go and keeps the form’s own look.',
  },
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
] as const

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
        <span class="doc-tile"><AppIcon name="document-text" :size="22" /></span>
        <span class="signin__wordmark">Brownie</span>
      </p>

      <h1 class="page-title">Sign in to Brownie</h1>

      <p v-if="workspaceDeleted" class="signin__notice" role="status">
        <AppIcon name="check-circle" :size="18" />
        <span>Your workspace and everything in it was deleted. Signing in again starts a new, empty one.</span>
      </p>

      <p class="page-lede signin__lede">{{ lede }}</p>
    </header>

    <a class="button button--primary signin__action" href="/oauth2/authorization/entra" @click="keepDestination">
      <span>Sign in or sign up</span>
      <AppIcon name="sign-in" :size="22" />
    </a>

    <p class="signin__note">
      <AppIcon name="shield-check" :size="16" />
      Open to invited people only. Your workspace is private to you.
    </p>

    <!--
      The explicit role keeps this a list for Safari's screen reader, which otherwise drops the list once its
      markers are hidden.
    -->
    <ol class="signin__steps" role="list" aria-label="How Brownie works">
      <li v-for="(step, index) in STEPS" :key="step.title" class="panel">
        <p class="signin__step-head">
          <span class="doc-tile doc-tile--medium"><AppIcon :name="step.icon" :size="18" /></span>
          <span class="signin__step-label">Step {{ index + 1 }}</span>
        </p>
        <h2 class="signin__step-title">{{ step.title }}</h2>
        <p class="signin__step-text">{{ step.text }}</p>
      </li>
    </ol>
  </section>
</template>

<style scoped>
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

/* A wordmark rather than interface text, so it is set heavier than the 500 that labels use. */
.signin__wordmark {
  color: var(--color-cocoa);
  font-size: var(--font-size-xl);
  font-weight: 600;
  line-height: 1.2;
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

/* The three cards fill the column and are as tall as their own words need, so a shorter one is not stretched. */
.signin__steps {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 1rem;
  align-items: start;
  inline-size: 100%;
  margin: 3.625rem 0 0;
  padding: 0;
  list-style: none;
}

.signin__step-head {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin: 0;
}

.signin__step-label {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.signin__step-title {
  margin: 0.5rem 0 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.signin__step-text {
  margin: 0.625rem 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.42;
}

@container main (min-width: 40rem) {
  .signin__steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
