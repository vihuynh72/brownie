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
      <header class="shell__bar" :class="{ 'shell__bar--tucked': docked && sidebarOpen }">
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

      <main id="main-content" class="shell__main">
        <section v-if="session.status === 'error'" class="card" role="alert">
          <h1>Could not confirm your sign-in</h1>
          <p>{{ session.lastError }} Try again in a moment, or reload the page.</p>
          <button type="button" class="button button--primary" @click="session.loadIdentity()">Try again</button>
        </section>
        <RouterView v-else />
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
 */
.shell__main {
  flex: 1;
  container-type: inline-size;
  container-name: main;
  padding: var(--space-5) var(--space-5) var(--space-8);
}

@media (min-width: 40rem) {
  .shell__main {
    padding-inline: var(--space-6);
  }
}
</style>
