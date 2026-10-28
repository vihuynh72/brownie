<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import { useSessionStore } from '@/stores/session'
import { takeSignInIntent } from '@/auth/intent'

const session = useSessionStore()
const router = useRouter()
const route = useRoute()

/**
 * The width at which the sidebar stops floating over the page and becomes
 * a column beside it. Below it, three columns of workspace plus a sidebar
 * leave nothing usable, so the sidebar becomes a drawer and the page keeps
 * the whole window.
 *
 * matchMedia can be absent (a server render, or a test environment that
 * does not implement it), in which case the interface behaves as the
 * docked desktop it was designed for.
 */
const DOCK_QUERY = '(min-width: 64rem)'
const dockQuery =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(DOCK_QUERY) : null
const docked = ref(dockQuery ? dockQuery.matches : true)

/**
 * Whether the sidebar is showing. Docked, it starts open and the choice
 * to collapse it is remembered for next time; as a drawer it always
 * starts closed, because a drawer covering the page on arrival is not a
 * choice anyone made.
 */
const SIDEBAR_KEY = 'brownie.sidebarOpen'
const sidebarOpen = ref(docked.value ? readDockedPreference() : false)

/** A page reached through a link of its own stands alone: the sidebar and its bar are not for it. */
const bare = computed(() => route.meta.bare === true)

function readDockedPreference(): boolean {
  try {
    return window.localStorage.getItem(SIDEBAR_KEY) !== 'false'
  } catch {
    return true
  }
}

function rememberDockedPreference(open: boolean): void {
  try {
    window.localStorage.setItem(SIDEBAR_KEY, String(open))
  } catch {
    /* A browser with site data blocked simply starts open every time. */
  }
}

const openButtonRef = ref<HTMLButtonElement | null>(null)
const sidebarRef = ref<InstanceType<typeof AppSidebar> | null>(null)

/** "Open menu" over the page, "Show sidebar" beside it: the same control, but not the same act. */
const openLabel = computed(() => (docked.value ? 'Show sidebar' : 'Open menu'))

async function openSidebar(): Promise<void> {
  sidebarOpen.value = true
  if (docked.value) {
    rememberDockedPreference(true)
    // The control the person just used is about to be hidden; the sidebar's own
    // toggle takes its place, so focus follows it there rather than falling to the page.
    await nextTick()
    sidebarRef.value?.focusToggle()
  }
}

async function closeSidebar(): Promise<void> {
  sidebarOpen.value = false
  if (docked.value) rememberDockedPreference(false)
  await nextTick()
  openButtonRef.value?.focus()
}

/**
 * Crossing the breakpoint changes what the panel is, not just how it
 * looks: a drawer left open must not become a docked column the person
 * never asked for, and a collapsed column must not reappear as a drawer
 * over the page. Each direction lands on that shape's own default.
 */
function onDockChange(event: MediaQueryListEvent): void {
  docked.value = event.matches
  sidebarOpen.value = event.matches ? readDockedPreference() : false
}

onMounted(() => {
  if (session.status === 'unknown') {
    void session.loadIdentity()
  }
  dockQuery?.addEventListener('change', onDockChange)
})

onBeforeUnmount(() => dockQuery?.removeEventListener('change', onDockChange))

/**
 * Signing in leaves this origin and comes back to the home address, so
 * the page the person was actually trying to reach is picked up here, the
 * first time a session exists, and never again for that sign-in.
 */
watch(
  () => session.status,
  (status) => {
    if (status !== 'authenticated') return
    const intended = takeSignInIntent()
    if (intended && intended !== router.currentRoute.value.fullPath) {
      void router.replace(intended)
    }
  },
  { immediate: true },
)

/**
 * A session can end while a page is open: it expired, or it was ended
 * elsewhere. Whichever request finds out, the page it was on swaps to its
 * signed-out state at once, taking with it whatever the person was
 * pressing and any message about it. So what happened is said here, once,
 * for every page, and focus comes here rather than being lost.
 */
const sessionEndedRef = ref<HTMLElement | null>(null)
watch(
  () => session.sessionEnded,
  async (ended) => {
    if (!ended) return
    await nextTick()
    sessionEndedRef.value?.focus()
  },
)

/** A drawer over the page: the page behind it must not scroll under the person's finger. */
watch([sidebarOpen, docked], ([open, isDocked]) => {
  if (typeof document === 'undefined') return
  document.body.style.overflow = open && !isDocked ? 'hidden' : ''
})

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') document.body.style.overflow = ''
})
</script>

<template>
  <a class="visually-hidden skip-link" href="#main-content">Skip to main content</a>

  <div class="shell" :class="{ 'shell--drawer': !docked }">
    <!-- It asks to be opened when something the person started in it ends while it is closed, such as a refused file. -->
    <AppSidebar
      v-if="!bare"
      ref="sidebarRef"
      :open="sidebarOpen"
      :docked="docked"
      @close="closeSidebar"
      @open="openSidebar"
    />

    <!-- Only ever present while the drawer is: a tap anywhere on the dimmed page closes it. -->
    <div v-if="!bare && !docked && sidebarOpen" class="shell__scrim" @click="closeSidebar" />

    <div class="shell__body">
      <header v-if="!bare" class="shell__bar" :class="{ 'shell__bar--tucked': docked && sidebarOpen, 'shell__bar--floating': docked }">
        <button
          ref="openButtonRef"
          type="button"
          class="icon-button"
          aria-expanded="false"
          aria-controls="app-sidebar"
          @click="openSidebar"
        >
          <AppIcon :name="docked ? 'panel-expand' : 'menu'" :size="24" />
          <span class="visually-hidden">{{ openLabel }}</span>
        </button>
        <RouterLink v-if="!docked" class="shell__brand" to="/">Brownie</RouterLink>
      </header>

      <main
        id="main-content"
        class="shell__main"
        :class="{ 'shell__main--under-bar': docked && !sidebarOpen, 'shell__main--after-bar': !docked }"
      >
        <section v-if="session.status === 'error'" class="panel shell__notice" role="alert">
          <h1 class="shell__notice-title">Could not confirm your sign-in</h1>
          <p class="shell__notice-text">{{ session.lastError }}</p>
          <button type="button" class="button button--primary" @click="session.loadIdentity()">Try again</button>
        </section>
        <!-- Every page reads the workspace from the identity; with none, each would sit on its loading message. -->
        <section
          v-else-if="session.status === 'authenticated' && session.personalWorkspaceId === undefined"
          class="panel shell__notice"
          role="alert"
        >
          <h1 class="shell__notice-title">No workspace for this account</h1>
          <p class="shell__notice-text">
            You are signed in, but Brownie found no workspace for you. Signing out and in again creates one.
          </p>
        </section>
        <template v-else>
          <section
            v-if="session.sessionEnded"
            ref="sessionEndedRef"
            class="panel shell__notice shell__notice--banner"
            role="alert"
            tabindex="-1"
          >
            <p class="shell__notice-text">
              Your session has ended, so nothing more can be saved or changed until you sign in again.
            </p>
            <RouterLink
              class="button button--primary"
              :to="{ name: 'signin', query: { next: router.currentRoute.value.fullPath } }"
            >
              Sign in again
            </RouterLink>
          </section>
          <!--
            A document's page is drawn afresh for each document: the same page reused for another one
            would show the first document under the second's address and save edits to the wrong one.
          -->
          <RouterView v-slot="{ Component, route: shown }">
            <component :is="Component" :key="shown.name === 'workspace' ? `document-${String(shown.params.documentId)}` : undefined" />
          </RouterView>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  align-items: flex-start;
  min-block-size: 100dvh;
}

.shell__body {
  position: relative;
  flex: 1;
  min-inline-size: 0;
  display: flex;
  flex-direction: column;
  min-block-size: 100dvh;
}

.shell__scrim {
  position: fixed;
  inset: 0;
  z-index: 30;
  background: var(--scrim);
  animation: scrim-in var(--motion-base) var(--motion-ease);
}

@keyframes scrim-in {
  from {
    opacity: 0;
  }
}

.shell__bar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4) 0;
}

/*
 * Beside a collapsed docked sidebar the only control here is the one that brings it back, so it sits
 * in the page's top corner instead of taking a row of its own. Its glyph is centred 26px from the
 * top: a page that shares this row (the document's toolbar) centres its own controls on that line,
 * so the two read as one row. The page below is told how much of that corner it covers (see
 * .shell__main--under-bar).
 */
.shell__bar--floating {
  position: absolute;
  inset-block-start: 0.5625rem;
  inset-inline-start: var(--space-3);
  z-index: 5;
  padding: 0;
}

/* The glyph is the text colour at 60%, like the icons in the sidebar it brings back, and full strength under the pointer. */
.shell__bar .icon-button {
  color: color-mix(in srgb, var(--color-text) 60%, transparent);
}

.shell__bar .icon-button:hover {
  color: var(--color-text);
}

/* Docked and open, the sidebar carries its own collapse control and this one has nothing to say. */
.shell__bar--tucked {
  display: none;
}

.shell__brand {
  font-weight: var(--font-weight-medium);
  font-size: var(--font-size-lg);
  color: var(--color-text);
  text-decoration: none;
}

/*
 * A container, so the pages inside can lay themselves out against the room
 * they actually have rather than against the window: with the sidebar
 * docked the two differ by its whole width, and the workspace's three
 * columns are the difference between fitting and painting over each other.
 *
 * Its padding is published as custom properties, with the corner the floating sidebar button
 * covers, so a page that wants more of the window than a page of text needs (the workspace) can
 * reach past the padding exactly, and can share its first row with that button. Every other page
 * just starts below the button.
 */
.shell__main {
  --shell-gutter-block-start: var(--space-5);
  --shell-gutter-inline: var(--space-5);
  --shell-gutter-block-end: var(--space-8);
  /* How far down, and how far in, the floating sidebar button reaches from the page's top corner. */
  --shell-bar-block: 0px;
  --shell-bar-inline: 0px;
  /* How tall the bar is when it is a row of its own above this region instead (the sidebar as a drawer). */
  --shell-bar-row: 0px;
  flex: 1;
  container-type: inline-size;
  container-name: main;
  padding: calc(var(--shell-bar-block) + var(--shell-gutter-block-start)) var(--shell-gutter-inline)
    var(--shell-gutter-block-end);
}

.shell__main--under-bar {
  --shell-bar-block: calc(var(--space-3) + var(--icon-button-size));
  --shell-bar-inline: calc(var(--space-3) + var(--icon-button-size) + var(--space-2));
}

/*
 * --icon-button-size is a minimum, not the open-sidebar button's real height: its 24px glyph plus the
 * icon button's own padding and border (--space-1 and 1px on each side) renders at 34px, two pixels past
 * that minimum. A page that fills the window height (the chat and document pages) reads this row's
 * height from here, so it is published as what the button actually measures rather than what the token
 * promises -- a page that trusted the token would come up two pixels short and scroll where it should not.
 */
.shell__main--after-bar {
  --shell-bar-row: calc(var(--space-3) + 1.5rem + 2 * var(--space-1) + 2px);
}

@media (min-width: 40rem) {
  .shell__main {
    --shell-gutter-inline: var(--space-6);
  }
}

/*
 * The shell's own messages: the identity could not be confirmed, the account has no workspace, the
 * session ended. The first two stand in for the page, so they sit where a page's title would; the
 * third is a banner above whatever page is still showing, in the width of a page of text.
 */
.shell__notice {
  inline-size: min(100%, 32rem);
  margin: var(--space-6) auto 0;
  padding: var(--space-5);
}

.shell__notice-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-medium);
  line-height: 1.2;
}

.shell__notice-text {
  margin: var(--space-2) 0 0;
  color: var(--color-text-muted);
}

.shell__notice > .button {
  margin-block-start: var(--space-4);
}

.shell__notice--banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3) var(--space-4);
  inline-size: min(100%, 47.5rem);
  margin-block: 0 var(--space-4);
  padding: var(--space-3) var(--space-4);
}

.shell__notice--banner > .shell__notice-text {
  flex: 1 1 18rem;
  margin: 0;
  color: var(--color-text);
}

.shell__notice--banner > .button {
  margin: 0;
}
</style>
