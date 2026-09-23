<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from './AppIcon.vue'
import { useSessionStore } from '@/stores/session'
import { useTemplatesStore } from '@/stores/templates'
import { navigateTo } from '@/navigation'

/**
 * The application's own navigation: where you are, what you have taught
 * Brownie, and who you are signed in as.
 *
 * It has two shapes, and the shell tells it which one it is in rather
 * than working it out again from its own media query -- the shell already
 * decides that, and two independent answers to the same question drift.
 * Docked, it is a column beside the page that can be collapsed out of the
 * way. As a drawer, it floats over the page, takes focus while it is
 * open, and closes on Escape, on a tap outside, and on following a link.
 */
const props = defineProps<{ open: boolean; docked: boolean }>()
const emit = defineEmits<{ close: [] }>()

const session = useSessionStore()
const templates = useTemplatesStore()

const rootRef = ref<HTMLElement | null>(null)
const closeButtonRef = ref<HTMLButtonElement | null>(null)
const signingOut = ref(false)
const signOutError = ref<string | null>(null)

/** Open as a floating drawer: the only state in which this panel owns focus and Escape. */
const trapping = computed(() => props.open && !props.docked)

function loadTemplatesWhenPossible(): void {
  const workspaceId = session.personalWorkspaceId
  if (workspaceId !== undefined) void templates.load(workspaceId)
}

onMounted(loadTemplatesWhenPossible)
// The workspace id arrives with the identity response, which is still in flight on a hard page load.
watch(() => session.personalWorkspaceId, loadTemplatesWhenPossible)

// Opening the drawer moves focus into it, so the next Tab lands on a link the person can see.
watch(trapping, async (isTrapping) => {
  if (!isTrapping) return
  await nextTick()
  closeButtonRef.value?.focus()
})

function close(): void {
  emit('close')
}

/**
 * Lets the shell hand focus to this panel's collapse control after the
 * control that opened it has been hidden, so a keyboard user is left on
 * the button that undoes what they just did rather than at the top of the
 * page.
 */
function focusToggle(): void {
  closeButtonRef.value?.focus()
}

defineExpose({ focusToggle })

/** Following a link inside a drawer leaves it covering the page it just navigated to; docked, there is nothing to close. */
function closeIfDrawer(): void {
  if (!props.docked) emit('close')
}

const FOCUSABLE_SELECTOR = 'a[href], button, input, select, textarea, [tabindex]'

/** The broad selector above, narrowed to what a real Tab press would land on: nothing disabled, nothing on tabindex="-1". */
function focusableItems(): HTMLElement[] {
  if (!rootRef.value) return []
  return Array.from(rootRef.value.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    (element) => !element.hasAttribute('disabled') && element.tabIndex >= 0,
  )
}

/**
 * Escape closes; Tab and Shift+Tab wrap at the drawer's own ends so focus
 * cannot walk out onto the page behind it. Gated on the drawer actually
 * floating: docked, this panel is ordinary page furniture and trapping
 * Tab inside it would strand a keyboard user.
 */
function onKeydown(event: KeyboardEvent): void {
  if (!trapping.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab' || !rootRef.value) return
  const items = focusableItems()
  if (items.length === 0) return
  const first = items[0]!
  const last = items[items.length - 1]!
  const active = document.activeElement
  if (event.shiftKey && (active === first || !rootRef.value.contains(active))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

/**
 * A button, not a link: signing out is a CSRF-checked POST, which a plain
 * anchor to /logout can never make -- the server answers that GET with a
 * 404 and the session quietly stays alive. Once the server confirms the
 * session is gone, the page navigates to the identity provider's
 * end-session URL it returned, which signs the person out there too and
 * brings them back here.
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
  <aside
    id="app-sidebar"
    ref="rootRef"
    class="sidebar"
    :class="{ 'sidebar--drawer': !props.docked, 'sidebar--open': props.open, 'sidebar--collapsed': props.docked && !props.open }"
    :aria-label="props.docked ? 'Brownie' : 'Brownie menu'"
    :inert="props.open ? undefined : true"
  >
    <div class="sidebar__inner">
      <div class="sidebar__head">
        <RouterLink v-if="!props.docked" class="sidebar__brand" to="/" @click="closeIfDrawer">Brownie</RouterLink>
        <button
          ref="closeButtonRef"
          type="button"
          class="icon-button sidebar__toggle"
          aria-expanded="true"
          aria-controls="app-sidebar"
          @click="close"
        >
          <AppIcon name="panel-collapse" />
          <span class="visually-hidden">{{ props.docked ? 'Hide sidebar' : 'Close menu' }}</span>
        </button>
      </div>

      <nav class="sidebar__nav" aria-label="Main">
        <ul class="sidebar__list">
          <li>
            <RouterLink class="sidebar__link" to="/" @click="closeIfDrawer">
              <AppIcon name="home" />
              <span>Home</span>
            </RouterLink>
          </li>
          <li>
            <RouterLink class="sidebar__link" to="/chat" @click="closeIfDrawer">
              <AppIcon name="chat" />
              <span>Chat</span>
            </RouterLink>
          </li>
        </ul>
      </nav>

      <hr class="sidebar__rule" />

      <section class="sidebar__section" aria-labelledby="sidebar-templates-heading">
        <div class="sidebar__section-head">
          <h2 id="sidebar-templates-heading" class="sidebar__section-title">My Templates</h2>
          <RouterLink class="icon-button" to="/templates/new" @click="closeIfDrawer">
            <AppIcon name="plus" />
            <span class="visually-hidden">Teach a template</span>
          </RouterLink>
        </div>

        <p v-if="session.status === 'anonymous'" class="sidebar__note">Sign in to see your templates.</p>
        <p v-else-if="session.status === 'error'" class="sidebar__note">Your templates could not be loaded.</p>
        <p v-else-if="session.status !== 'authenticated' || templates.status === 'loading'" class="sidebar__note">Loading…</p>
        <p v-else-if="templates.status === 'error'" class="sidebar__note">Your templates could not be loaded.</p>
        <p v-else-if="templates.usable.length === 0" class="sidebar__note">Nothing taught yet.</p>
        <ul v-else class="sidebar__list">
          <li v-for="template in templates.usable" :key="template.id">
            <RouterLink
              class="sidebar__link sidebar__link--quiet"
              :to="{ path: '/documents/new', query: { templateId: template.id } }"
              :title="template.displayName"
              @click="closeIfDrawer"
            >
              <AppIcon name="document" :size="18" />
              <span class="sidebar__link-text">{{ template.displayName }}</span>
            </RouterLink>
          </li>
        </ul>
      </section>

      <div class="sidebar__foot">
        <RouterLink class="sidebar__link" to="/trash" @click="closeIfDrawer">
          <AppIcon name="trash" />
          <span>Trash Bin</span>
        </RouterLink>
        <RouterLink class="sidebar__link" to="/connections" @click="closeIfDrawer">
          <AppIcon name="link" />
          <span>Connections</span>
        </RouterLink>
        <RouterLink class="sidebar__link" to="/your-data" @click="closeIfDrawer">
          <AppIcon name="shield" />
          <span>Your data</span>
        </RouterLink>

        <hr class="sidebar__rule" />

        <p v-if="signOutError" class="field-error" role="alert">{{ signOutError }}</p>

        <div v-if="session.status === 'authenticated'" class="sidebar__account">
          <span class="sidebar__account-name" :title="session.accountLabel ?? undefined">{{
            session.accountLabel ?? 'Your account'
          }}</span>
          <button type="button" class="icon-button" :disabled="signingOut" @click="signOut">
            <AppIcon name="sign-out" />
            <span class="visually-hidden">{{ signingOut ? 'Signing out' : 'Sign out' }}</span>
          </button>
        </div>
        <RouterLink v-else-if="session.status === 'anonymous'" class="button button--primary sidebar__signin" to="/signin" @click="closeIfDrawer">
          <span>Sign In</span>
          <AppIcon name="sign-in" :size="18" />
        </RouterLink>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  flex: none;
  inline-size: var(--sidebar-width);
  block-size: 100dvh;
  position: sticky;
  inset-block-start: 0;
  background: var(--color-sidebar);
  border-inline-end: 1px solid var(--color-sidebar-border);
  /* The inner column keeps its own width while this box narrows, so collapsing
     slides the navigation out of view instead of squashing every label first. */
  overflow: hidden;
  transition:
    inline-size var(--motion-base) var(--motion-ease),
    border-color var(--motion-base) var(--motion-ease),
    visibility var(--motion-base) var(--motion-ease);
}

/*
 * Clipping alone would leave the navigation off-screen but still rendered,
 * which find-in-page and some assistive technology still reach. Visibility
 * transitions as a step at the end of the duration, so the panel finishes
 * sliding away and only then stops existing for anything looking at the page.
 */
.sidebar--collapsed {
  inline-size: 0;
  border-inline-end-color: transparent;
  visibility: hidden;
}

.sidebar__inner {
  inline-size: var(--sidebar-width);
  block-size: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4) var(--space-4);
  overflow-y: auto;
}

/*
 * As a drawer it leaves the page's flow entirely and floats above it, so
 * the page underneath keeps its full width and nothing reflows when the
 * drawer opens. Sliding is a translate, which the compositor can do on
 * its own; visibility is part of the same transition so the panel stops
 * being reachable by Tab only once it has finished leaving.
 */
.sidebar--drawer {
  position: fixed;
  inset-block: 0;
  inset-inline-start: 0;
  z-index: 40;
  inline-size: min(17rem, 85vw);
  border-inline-end: 1px solid var(--color-sidebar-border);
  box-shadow: 0 0 2.5rem rgb(42 41 36 / 0.18);
  translate: -100% 0;
  visibility: hidden;
  transition:
    translate var(--motion-base) var(--motion-ease),
    visibility var(--motion-base) var(--motion-ease);
}

.sidebar--drawer.sidebar--open {
  translate: 0 0;
  visibility: visible;
}

.sidebar--drawer .sidebar__inner {
  inline-size: 100%;
}

.sidebar__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  min-block-size: 2.25rem;
}

.sidebar__brand {
  font-weight: 700;
  font-size: var(--font-size-lg);
  color: var(--color-text);
  text-decoration: none;
}

/* The collapse control sits at the panel's own edge, as far from the content as it can. */
.sidebar__toggle {
  margin-inline-start: auto;
}

.sidebar__nav {
  margin-block-start: var(--space-4);
}

.sidebar__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.sidebar__link {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  min-block-size: 2.5rem;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  color: var(--color-text);
  font-weight: 500;
  text-decoration: none;
  transition: background-color var(--motion-fast) var(--motion-ease);
}

.sidebar__link:hover {
  background: var(--color-cocoa-wash);
}

.sidebar__link--quiet {
  font-weight: 400;
  color: var(--color-text-muted);
}

/* A long template name is cut off rather than pushing the panel wider; the full name is its title. */
.sidebar__link-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* The current page is marked by weight and a wash, never by color alone. */
.sidebar__link.router-link-exact-active {
  background: var(--color-cocoa-wash);
  font-weight: 600;
  color: var(--color-text);
}

.sidebar__rule {
  inline-size: 100%;
  block-size: 1px;
  margin: var(--space-3) 0;
  border: 0;
  background: var(--color-sidebar-border);
}

.sidebar__section {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-block-size: 0;
}

.sidebar__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.sidebar__section-title {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: 600;
  color: var(--color-text-muted);
}

.sidebar__note {
  margin: 0;
  padding-inline: var(--space-3);
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}

/* Pins the trash and account rows to the bottom of the panel, as the design has them. */
.sidebar__foot {
  margin-block-start: auto;
  padding-block-start: var(--space-4);
}

.sidebar__account {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding-inline: var(--space-3);
}

.sidebar__account-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar__signin {
  inline-size: fit-content;
  border-radius: var(--radius-pill);
}
</style>
