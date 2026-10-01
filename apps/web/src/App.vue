<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import { useSessionStore } from '@/stores/session'
import { takeSignInIntent } from '@/auth/intent'

const session = useSessionStore()
const router = useRouter()

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
    <AppSidebar ref="sidebarRef" :open="sidebarOpen" :docked="docked" @close="closeSidebar" />

    <!-- Only ever present while the drawer is: a tap anywhere on the dimmed page closes it. -->
    <div v-if="!docked && sidebarOpen" class="shell__scrim" @click="closeSidebar" />

    <div class="shell__body">
      <header class="shell__bar" :class="{ 'shell__bar--tucked': docked && sidebarOpen, 'shell__bar--floating': docked }">
        <button
          ref="openButtonRef"
          type="button"
          class="icon-button"
          aria-expanded="false"
          aria-controls="app-sidebar"
          @click="openSidebar"
        >
          <AppIcon :name="docked ? 'panel-expand' : 'menu'" />
          <span class="visually-hidden">{{ openLabel }}</span>
        </button>
        <RouterLink v-if="!docked" class="shell__brand" to="/">Brownie</RouterLink>
      </header>

      <main
        id="main-content"
        class="shell__main"
        :class="{ 'shell__main--under-bar': docked && !sidebarOpen, 'shell__main--after-bar': !docked }"
      >
        <section v-if="session.status === 'error'" class="card" role="alert">
          <h1>Could not confirm your sign-in</h1>
          <p>{{ session.lastError }}</p>
          <button type="button" class="button button--primary" @click="session.loadIdentity()">Try again</button>
        </section>
        <!-- Every page reads the workspace from the identity; with none, each would sit on its loading message. -->
        <section
          v-else-if="session.status === 'authenticated' && session.personalWorkspaceId === undefined"
          class="card"
          role="alert"
        >
          <h1>No workspace for this account</h1>
          <p>You are signed in, but Brownie found no workspace for you. Signing out and in again creates one.</p>
        </section>
        <template v-else>
          <section v-if="session.sessionEnded" ref="sessionEndedRef" class="card" role="alert" tabindex="-1">
            <p>Your session has ended, so nothing more can be saved or changed until you sign in again.</p>
            <RouterLink :to="{ name: 'signin', query: { next: router.currentRoute.value.fullPath } }">Sign in again</RouterLink>
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
  background: rgb(42 41 36 / 0.32);
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
 * in the page's top corner, as the design draws it, instead of taking a row of its own. The page
 * below is told how much of that corner it covers (see .shell__main--under-bar).
 */
.shell__bar--floating {
  position: absolute;
  inset-block-start: var(--space-3);
  inset-inline-start: var(--space-3);
  z-index: 5;
  padding: 0;
}

/* Docked and open, the sidebar carries its own collapse control and this one has nothing to say. */
.shell__bar--tucked {
  display: none;
}

.shell__brand {
  font-weight: 700;
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

.shell__main--after-bar {
  --shell-bar-row: calc(var(--space-3) + var(--icon-button-size));
}

@media (min-width: 40rem) {
  .shell__main {
    --shell-gutter-inline: var(--space-6);
  }
}
</style>
