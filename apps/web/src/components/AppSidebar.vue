<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, isNavigationFailure, useRoute, useRouter } from 'vue-router'
import AppIcon from './AppIcon.vue'
import TemplateActionsMenu from './TemplateActionsMenu.vue'
import { useSessionStore } from '@/stores/session'
import { documentTitleFor, useTemplatesStore } from '@/stores/templates'
import { navigateTo } from '@/navigation'
import { ApiRequestError, createDocument, trashTemplate, type TemplateResponse } from '@/api/client'
import { describeCommonFailure } from '@/api/failures'

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
 *
 * Each template is a button that starts a new document from it straight
 * away and opens it; when the person has moved on to another page before
 * the document is made, it is offered as a link instead of pulling them
 * back. Its "More" button, a right-click, or Shift+F10 on the row opens a
 * menu for the template itself, which is where it is moved to the Trash
 * Bin.
 */
const props = defineProps<{ open: boolean; docked: boolean }>()
const emit = defineEmits<{ close: [] }>()

const session = useSessionStore()
const templates = useTemplatesStore()
const router = useRouter()
const route = useRoute()

const rootRef = ref<HTMLElement | null>(null)
const closeButtonRef = ref<HTMLButtonElement | null>(null)
const signingOut = ref(false)
const signOutError = ref<string | null>(null)

/** What a new document's first revision gives as the reason it exists. */
const INITIAL_REVISION_REASON = 'Started from a template in the sidebar.'
const MENU_ID = 'template-actions-menu'

/**
 * The template whose request is running, and whether it is starting a document from it or moving it to the
 * Trash Bin. One at a time: a second press, on it or on another row, is ignored.
 */
const busy = ref<{ templateId: number; action: 'start' | 'trash' } | null>(null)
/** What went wrong with the last thing a template row was asked to do, in words. */
const templatesError = ref<string | null>(null)
/**
 * What the last thing a template row was asked to do came to, said once near the rows: the template just moved
 * to the Trash Bin, or the document started from one after the person had moved on to another page.
 */
const notice = ref<{ kind: 'trashed'; name: string } | { kind: 'started'; title: string; documentId: number } | null>(null)
/** Not a live region beside the page's own: it takes focus from the row it is about when that row had it, and otherwise waits to be read. */
const noticeRef = ref<HTMLElement | null>(null)
/**
 * What a screen reader is told when the notice appears without taking focus: the sidebar's own status
 * line, always in the page so what is put into it is read, and empty the rest of the time.
 */
const spokenNotice = ref('')

/** The open menu: whose it is, where it opens, and where focus goes back to when it closes. */
const menu = ref<{
  /** Counts every opening, so a menu opened for another row is a new one that places itself and takes focus again. */
  serial: number
  template: TemplateResponse
  anchor: HTMLElement
  point: { x: number; y: number } | null
  startAt: 'first' | 'last'
  returnTo: HTMLElement
} | null>(null)

/** Open as a floating drawer: the only state in which this panel owns focus and Escape. */
const trapping = computed(() => props.open && !props.docked)

/** On the Trash Bin page itself, a link to it would go nowhere. */
const onTrashPage = computed(() => route.name === 'trash')

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

// A menu left open would float over a panel that has slid away, or beside a row that is no longer there.
watch(
  () => props.open,
  (open) => {
    if (!open) menu.value = null
  },
)
watch(
  () => templates.usable,
  (usable) => {
    if (menu.value && !usable.some((template) => template.id === menu.value!.template.id)) menu.value = null
  },
)

// What the sidebar said about the last action belongs to the page it was said on.
watch(
  () => route.fullPath,
  () => {
    menu.value = null
    notice.value = null
    spokenNotice.value = ''
    templatesError.value = null
  },
)

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
 * cannot walk out onto the page behind it, and either one pressed from
 * outside the drawer (the page behind still had focus when it opened, say)
 * brings focus into it. Gated on the drawer actually floating: docked,
 * this panel is ordinary page furniture and trapping Tab inside it would
 * strand a keyboard user.
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
  } else if (!event.shiftKey && (active === last || !rootRef.value.contains(active))) {
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

function startId(templateId: number): string {
  return `template-start-${templateId}`
}

function moreId(templateId: number): string {
  return `template-more-${templateId}`
}

function menuIsOpenFor(template: TemplateResponse): boolean {
  return menu.value?.template.id === template.id
}

function rowIsBusy(template: TemplateResponse): boolean {
  return busy.value?.templateId === template.id
}

function isStarting(template: TemplateResponse): boolean {
  return rowIsBusy(template) && busy.value?.action === 'start'
}

/** Whether focus is on the template's row, or in the menu opened for it. */
function focusIsOnRow(template: TemplateResponse): boolean {
  const active = document.activeElement
  if (!active) return false
  const row = document.getElementById(startId(template.id))?.closest('li')
  const menuElement = menuIsOpenFor(template) ? document.getElementById(MENU_ID) : null
  return !!row?.contains(active) || !!menuElement?.contains(active)
}

/**
 * Takes the row of a template found to be in the Trash Bin already off the
 * list. When focus was on it, focus moves to the row that takes its place,
 * to the one above when it was the last, or to the + when no rows are
 * left, so a keyboard user is never dropped at the top of the page.
 */
async function removeRow(template: TemplateResponse): Promise<void> {
  const hadFocus = focusIsOnRow(template)
  const usable = templates.usable
  const index = usable.findIndex((candidate) => candidate.id === template.id)
  const neighbour = index === -1 ? undefined : (usable[index + 1] ?? usable[index - 1])
  templates.markTrashed(template.id)
  await nextTick()
  if (!hadFocus) return
  const next = neighbour ? document.getElementById(startId(neighbour.id)) : document.getElementById('sidebar-add-template')
  next?.focus()
}

/**
 * Starts a document from the template and opens it, named after the
 * template and the day, with every field still empty. The row says it is
 * starting while that happens, and a second press is ignored rather than
 * making a second document.
 *
 * An answer can be slow, and the person may have gone to another page in
 * the meantime. Opening the document then would pull them back from where
 * they chose to go, so the notice offers a link to it instead.
 */
async function startDocument(template: TemplateResponse): Promise<void> {
  const workspaceId = session.personalWorkspaceId
  const versionId = template.currentActiveVersionId
  if (workspaceId === undefined || versionId == null || busy.value !== null) return
  busy.value = { templateId: template.id, action: 'start' }
  templatesError.value = null
  notice.value = null
  spokenNotice.value = ''
  const startedFrom = route.fullPath
  const title = documentTitleFor(template.displayName)
  let trashedElsewhere = false
  try {
    const document = await createDocument(workspaceId, crypto.randomUUID(), {
      title,
      templateId: template.id,
      templateVersionId: versionId,
      fields: {},
      initialRevisionReason: INITIAL_REVISION_REASON,
    })
    // The person may have moved on meanwhile, or chosen to stay where unsaved work is: the new document
    // is then offered here rather than opened over what they are doing.
    const failure = route.fullPath === startedFrom ? await router.push(`/documents/${document.id}`) : null
    if (route.fullPath === `/documents/${document.id}` && !isNavigationFailure(failure)) {
      closeIfDrawer()
    } else {
      notice.value = { kind: 'started', title, documentId: document.id }
      spokenNotice.value = `Started "${title}". It can be opened from the sidebar.`
    }
  } catch (error) {
    const lead = `Could not start a document from "${template.displayName}".`
    if (error instanceof ApiRequestError && error.problem?.code === 'TEMPLATE_TRASHED') {
      // Moved to the Trash Bin from another tab or device since this list was loaded: the row is stale.
      trashedElsewhere = true
      templatesError.value = `${lead} ${error.problem.detail ?? 'This template is in the Trash Bin. Restore it to start a document from it.'}`
    } else {
      // Never the error's own message: for an answer with no explanation that is "Request failed with status 502".
      templatesError.value = `${lead} ${
        describeCommonFailure(error, 'a way to create documents') ??
        (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
        'Try again.'
      }`
    }
  } finally {
    busy.value = null
  }
  if (trashedElsewhere) await removeRow(template)
}

let menuOpenings = 0

/** A row whose request is still running has nothing to offer until it is answered, so its menu stays shut. */
function openMenu(
  template: TemplateResponse,
  options: { point?: { x: number; y: number } | null; startAt?: 'first' | 'last'; returnTo: HTMLElement },
): void {
  const anchor = document.getElementById(moreId(template.id))
  if (!anchor || rowIsBusy(template)) return
  menuOpenings += 1
  menu.value = {
    serial: menuOpenings,
    template,
    anchor,
    point: options.point ?? null,
    startAt: options.startAt ?? 'first',
    returnTo: options.returnTo,
  }
}

function closeMenu(returnFocus: boolean): void {
  const returnTo = menu.value?.returnTo
  menu.value = null
  if (returnFocus) returnTo?.focus()
}

/** The "More" button toggles: pressed again while its menu is open, it closes it and keeps focus. */
function onMoreClick(template: TemplateResponse, event: MouseEvent): void {
  const button = event.currentTarget as HTMLElement
  if (menuIsOpenFor(template)) {
    closeMenu(true)
    return
  }
  openMenu(template, { returnTo: button })
}

/** Enter and Space arrive as a click; the arrow keys open the menu on the first or the last item. */
function onMoreKeydown(template: TemplateResponse, event: KeyboardEvent): void {
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
  event.preventDefault()
  if (menuIsOpenFor(template)) return
  openMenu(template, { startAt: event.key === 'ArrowUp' ? 'last' : 'first', returnTo: event.currentTarget as HTMLElement })
}

/** Shift+F10 and the context-menu key are the keyboard's right-click. */
function onRowKeydown(template: TemplateResponse, event: KeyboardEvent): void {
  if (!(event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey))) return
  event.preventDefault()
  const focused = (event.target as HTMLElement).closest('button') ?? document.getElementById(startId(template.id))
  if (focused) openMenu(template, { returnTo: focused })
}

/**
 * A right-click, or a long press on a touch screen, opens the menu where it
 * happened. One that did not come from a pointer (some browsers raise it
 * from the context-menu key as well, at the window's corner) opens it under
 * the row's "More" button instead.
 */
function onRowContextMenu(template: TemplateResponse, event: MouseEvent): void {
  event.preventDefault()
  if (menuIsOpenFor(template)) return
  const fromPointer = event.clientX !== 0 || event.clientY !== 0
  const clicked = (event.target as HTMLElement).closest('button') ?? document.getElementById(startId(template.id))
  if (!clicked) return
  openMenu(template, { point: fromPointer ? { x: event.clientX, y: event.clientY } : null, returnTo: clicked })
}

/**
 * Moves the template the menu was opened for to the Trash Bin. Documents
 * already made from it keep working, so this asks nothing first, and the
 * notice says where to find it again.
 *
 * When focus was on the row, or in its menu, it moves to the notice rather
 * than to the next row: a screen reader reads it out, and its link is the
 * next Tab stop. Focus the person has taken somewhere else stays there.
 */
async function moveToTrash(): Promise<void> {
  const chosen = menu.value?.template
  closeMenu(true)
  const workspaceId = session.personalWorkspaceId
  if (!chosen || workspaceId === undefined) return
  if (busy.value !== null) {
    templatesError.value =
      busy.value.action === 'trash'
        ? 'Wait for the template already on its way to the Trash Bin, then try again.'
        : 'Wait for the document that is starting to open, then move the template to the Trash Bin.'
    return
  }
  busy.value = { templateId: chosen.id, action: 'trash' }
  templatesError.value = null
  notice.value = null
  let moved = false
  try {
    await trashTemplate(workspaceId, chosen.id)
    moved = true
  } catch (error) {
    templatesError.value = `Could not move "${chosen.displayName}" to the Trash Bin. ${
      describeCommonFailure(error, 'a Trash Bin for templates') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      'Try again.'
    }`
  } finally {
    busy.value = null
  }
  if (!moved) return
  const hadFocus = focusIsOnRow(chosen)
  templates.markTrashed(chosen.id)
  notice.value = { kind: 'trashed', name: chosen.displayName }
  await nextTick()
  if (hadFocus) noticeRef.value?.focus()
  else spokenNotice.value = `Moved "${chosen.displayName}" to the Trash Bin.`
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
          <AppIcon name="panel-collapse" :size="24" />
          <span class="visually-hidden">{{ props.docked ? 'Hide sidebar' : 'Close menu' }}</span>
        </button>
      </div>

      <nav class="sidebar__nav" aria-label="Main">
        <ul class="sidebar__list">
          <li>
            <RouterLink class="sidebar__link" to="/" @click="closeIfDrawer">
              <AppIcon name="home" :size="26" />
              <span>Home</span>
            </RouterLink>
          </li>
          <li>
            <RouterLink class="sidebar__link" to="/chat" @click="closeIfDrawer">
              <AppIcon name="chat" :size="26" />
              <span>Chat</span>
            </RouterLink>
          </li>
        </ul>
      </nav>

      <hr class="sidebar__rule sidebar__rule--nav" />

      <section class="sidebar__section" aria-labelledby="sidebar-templates-heading">
        <div class="sidebar__section-head">
          <h2 id="sidebar-templates-heading" class="sidebar__section-title">My Templates</h2>
          <RouterLink id="sidebar-add-template" class="icon-button" to="/templates/new" @click="closeIfDrawer">
            <AppIcon name="plus" :size="24" />
            <span class="visually-hidden">Teach a template</span>
          </RouterLink>
        </div>

        <p class="visually-hidden" role="status">{{ spokenNotice }}</p>
        <p v-if="notice" ref="noticeRef" class="sidebar__notice" tabindex="-1">
          <template v-if="notice.kind === 'started'">
            Started "{{ notice.title }}".
            <RouterLink :to="`/documents/${notice.documentId}`" @click="closeIfDrawer">Open it</RouterLink>
          </template>
          <template v-else-if="onTrashPage">Moved "{{ notice.name }}" to the Trash Bin.</template>
          <template v-else>
            Moved "{{ notice.name }}" to <RouterLink to="/trash" @click="closeIfDrawer">the Trash Bin</RouterLink>.
          </template>
        </p>
        <p v-if="templatesError" class="field-error sidebar__error" role="alert">{{ templatesError }}</p>

        <p v-if="session.status === 'anonymous'" class="sidebar__note">Sign in to see your templates.</p>
        <p v-else-if="session.status === 'error'" class="sidebar__note">Your templates could not be loaded.</p>
        <p v-else-if="session.status !== 'authenticated' || templates.status === 'loading'" class="sidebar__note">Loading…</p>
        <p v-else-if="templates.status === 'error'" class="sidebar__note">Your templates could not be loaded.</p>
        <p v-else-if="templates.usable.length === 0" class="sidebar__note">You have no templates.</p>
        <ul v-else class="sidebar__templates">
          <li
            v-for="template in templates.usable"
            :key="template.id"
            class="template-row"
            :aria-busy="rowIsBusy(template) ? 'true' : undefined"
            @keydown="onRowKeydown(template, $event)"
            @contextmenu="onRowContextMenu(template, $event)"
          >
            <!-- Not disabled while busy: that would drop the focus the button holds. A second press is ignored instead. -->
            <button
              :id="startId(template.id)"
              type="button"
              class="template-row__start"
              :title="template.displayName"
              :aria-label="isStarting(template) ? `Starting a document from ${template.displayName}…` : undefined"
              aria-describedby="sidebar-templates-hint"
              :aria-disabled="busy !== null ? 'true' : undefined"
              @click="startDocument(template)"
            >
              <span class="template-row__name">{{ template.displayName }}</span>
              <span v-if="isStarting(template)" class="template-row__status">Starting…</span>
            </button>
            <button
              :id="moreId(template.id)"
              type="button"
              class="icon-button template-row__more"
              aria-haspopup="menu"
              :aria-expanded="menuIsOpenFor(template) ? 'true' : 'false'"
              :aria-controls="menuIsOpenFor(template) ? MENU_ID : undefined"
              :aria-disabled="rowIsBusy(template) ? 'true' : undefined"
              @click="onMoreClick(template, $event)"
              @keydown="onMoreKeydown(template, $event)"
            >
              <AppIcon name="more" :size="24" />
              <span class="visually-hidden">More for {{ template.displayName }}</span>
            </button>
          </li>
        </ul>
        <span id="sidebar-templates-hint" hidden>Starts a new document from this template.</span>

        <TemplateActionsMenu
          v-if="menu"
          :id="MENU_ID"
          :key="menu.serial"
          :labelled-by="moreId(menu.template.id)"
          :anchor="menu.anchor"
          :point="menu.point"
          :start-at="menu.startAt"
          @trash="moveToTrash"
          @close="closeMenu"
        />
      </section>

      <div class="sidebar__foot">
        <RouterLink class="sidebar__link" to="/trash" @click="closeIfDrawer">
          <AppIcon name="trash" :size="26" />
          <span>Trash Bin</span>
        </RouterLink>
        <RouterLink class="sidebar__link" to="/connections" @click="closeIfDrawer">
          <AppIcon name="link" :size="26" />
          <span>Connections</span>
        </RouterLink>
        <RouterLink class="sidebar__link" to="/your-data" @click="closeIfDrawer">
          <AppIcon name="shield" :size="26" />
          <span>Your data</span>
        </RouterLink>

        <hr class="sidebar__rule sidebar__rule--foot" />

        <p v-if="signOutError" class="field-error sidebar__error" role="alert">{{ signOutError }}</p>

        <div v-if="session.status === 'authenticated'" class="sidebar__account">
          <AppIcon name="account" :size="24" />
          <span class="sidebar__account-name" :title="session.accountLabel ?? undefined">{{
            session.accountLabel ?? 'Your account'
          }}</span>
          <button type="button" class="icon-button" :disabled="signingOut" @click="signOut">
            <AppIcon name="sign-out" :size="24" />
            <span class="visually-hidden">{{ signingOut ? 'Signing out' : 'Sign out' }}</span>
          </button>
        </div>
        <RouterLink v-else-if="session.status === 'anonymous'" class="button button--primary sidebar__signin" to="/signin" @click="closeIfDrawer">
          <span>Sign In</span>
          <AppIcon name="sign-in" :size="24" />
        </RouterLink>
      </div>
    </div>
  </aside>
</template>

<style scoped>
/*
 * Measured against the design at its own 1512 by 982 frame: 20px type at
 * medium weight in the muted text colour, icons drawn a little larger than
 * the type, hairline rules in that same colour, and a soft inner shadow on
 * every edge in place of a border. The current page is marked for
 * assistive technology (aria-current) but not tinted, as the design has it.
 */
.sidebar {
  flex: none;
  inline-size: var(--sidebar-width);
  block-size: 100dvh;
  position: sticky;
  inset-block-start: 0;
  background: var(--color-sidebar);
  box-shadow: inset -3px 0 14px color-mix(in srgb, var(--color-text-muted) 55%, transparent);
  /* The inner column keeps its own width while this box narrows, so collapsing
     slides the navigation out of view instead of squashing every label first. */
  overflow: hidden;
  transition:
    inline-size var(--motion-base) var(--motion-ease),
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
  visibility: hidden;
}

/*
 * Opening is the other way round: visibility switches at once, as the
 * panel starts to arrive, because a hidden element cannot take focus and
 * focus moves into the panel the moment it opens. A transition runs by the
 * rules of the state it is heading into, so this reaches only the opening;
 * the delayed hide above still applies on the way out. The drawer's own
 * open rule below repeats it with its translate, and wins on specificity.
 */
.sidebar--open {
  transition:
    inline-size var(--motion-base) var(--motion-ease),
    visibility 0s;
}

.sidebar__inner {
  inline-size: var(--sidebar-width);
  block-size: 100%;
  display: flex;
  flex-direction: column;
  padding: var(--space-2) var(--space-2) var(--space-3);
  overflow-y: auto;
  color: var(--color-text-muted);
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
  box-shadow:
    inset -3px 0 14px color-mix(in srgb, var(--color-text-muted) 55%, transparent),
    0 0 2.5rem rgb(42 41 36 / 0.18);
  translate: -100% 0;
  visibility: hidden;
  transition:
    translate var(--motion-base) var(--motion-ease),
    visibility var(--motion-base) var(--motion-ease);
}

.sidebar--drawer.sidebar--open {
  translate: 0 0;
  visibility: visible;
  transition:
    translate var(--motion-base) var(--motion-ease),
    visibility 0s;
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
  padding-inline: var(--space-2);
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
  margin-block-start: var(--space-2);
}

.sidebar__list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.sidebar__link {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-block-size: 2.5rem;
  padding-inline: var(--space-2);
  border-radius: var(--radius);
  color: var(--color-text-muted);
  font-size: var(--font-size-lg);
  font-weight: 500;
  text-decoration: none;
  transition: color var(--motion-fast) var(--motion-ease);
}

.sidebar__link:hover {
  color: var(--color-text);
}

.sidebar__rule {
  flex: none;
  block-size: 1px;
  margin-inline: var(--space-4);
  border: 0;
  background: var(--color-text-muted);
}

/* The design leaves the navigation well clear of the templates beneath it. */
.sidebar__rule--nav {
  margin-block: calc(var(--space-8) + var(--space-5)) var(--space-8);
}

.sidebar__rule--foot {
  margin-block: var(--space-2) 0;
}

/*
 * The one part of the panel that grows: a long list scrolls inside it, and
 * the foot stays where it is. It keeps room for its heading and a few rows,
 * so a window too short for everything scrolls the whole panel instead of
 * squeezing the list shut under the foot.
 */
.sidebar__section {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-block-size: 11rem;
}

.sidebar__section > * {
  flex: none;
}

.sidebar__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding-inline: var(--space-2) var(--space-3);
}

.sidebar__section-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: 500;
  color: var(--color-text-muted);
}

.sidebar__note {
  margin: 0;
  padding-inline: var(--space-2);
  font-size: var(--font-size-sm);
}

.sidebar__notice,
.sidebar__error {
  margin: 0 var(--space-2);
  font-size: var(--font-size-sm);
}

.sidebar__notice {
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius);
  background: var(--color-cocoa-wash);
  color: var(--color-text);
}

/* The default link blue falls just under 4.5:1 on the wash; the text colour clears it easily, and the underline still says "link". */
.sidebar__notice a {
  color: var(--color-text);
}

/*
 * The list scrolls on its own. It reaches a ring's width out past the rows
 * on every side, so the focus ring of the first or last row is not cut off
 * by its own scroll box, while the rows themselves stay where they were.
 */
.sidebar__section > .sidebar__templates {
  flex: 0 1 auto;
  min-block-size: 0;
  list-style: none;
  margin: calc(-1 * var(--space-1));
  padding: var(--space-1);
  overflow-y: auto;
}

/*
 * Light rows: body-size type at regular weight, no tint, the name alone,
 * so the list reads quieter than the navigation above it. "More" floats
 * over the end of its row rather than taking a column of its own, and the
 * name stops short of it only where it shows; hidden, the name has the
 * whole width.
 */
.template-row {
  position: relative;
  display: flex;
  align-items: center;
}

.template-row__start {
  flex: 1;
  display: flex;
  align-items: center;
  min-inline-size: 0;
  min-block-size: 2.25rem;
  padding-block: 0;
  padding-inline: var(--space-2) calc(var(--icon-button-size) + var(--space-3) + var(--space-1));
  border: 0;
  border-radius: var(--radius);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--font-size-base);
  font-weight: 400;
  text-align: start;
  cursor: pointer;
  transition: color var(--motion-fast) var(--motion-ease);
}

.template-row:hover .template-row__start {
  color: var(--color-text);
}

.template-row__start[aria-disabled='true'],
.template-row__more[aria-disabled='true'] {
  cursor: progress;
}

/* A busy row's name dims; the word saying what it is doing keeps the full contrast of text. */
.template-row[aria-busy='true'] .template-row__name {
  opacity: 0.6;
}

/* A long template name is cut off rather than pushing the panel wider; the full name is its title. */
.template-row__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-row__status {
  flex: none;
  margin-inline-start: auto;
  padding-inline-start: var(--space-2);
  font-size: var(--font-size-sm);
}

.template-row__more {
  position: absolute;
  inset-inline-end: var(--space-3);
  inset-block-start: 50%;
  translate: 0 -50%;
}

/*
 * With a mouse, "More" shows only on the row under the pointer or holding
 * focus, the way a list of names stays quiet until one is pointed at. It
 * fades rather than disappears, so Tab still reaches it and shows it. A
 * device with a touch screen always shows it: there is nothing to hover
 * with a finger.
 */
@media (hover: hover) and (pointer: fine) {
  .template-row__more {
    opacity: 0;
    transition: opacity var(--motion-fast) var(--motion-ease);
  }

  .template-row__start {
    padding-inline-end: var(--space-2);
  }

  .template-row:hover .template-row__more,
  .template-row:focus-within .template-row__more,
  .template-row__more[aria-expanded='true'] {
    opacity: 1;
  }

  .template-row:hover .template-row__start,
  .template-row:focus-within .template-row__start {
    padding-inline-end: calc(var(--icon-button-size) + var(--space-3) + var(--space-1));
  }

  /* On its own: a browser without :has() drops only this rule, not the two above with it. */
  .template-row:has(.template-row__more[aria-expanded='true']) .template-row__start {
    padding-inline-end: calc(var(--icon-button-size) + var(--space-3) + var(--space-1));
  }
}

@media (any-pointer: coarse) {
  .template-row__more {
    opacity: 1;
  }

  .template-row__start {
    min-block-size: 2.75rem;
    padding-inline-end: calc(var(--icon-button-size) + var(--space-3) + var(--space-1));
  }
}

/* Pins the trash and account rows to the bottom of the panel, as the design has them. */
.sidebar__foot {
  flex: none;
  display: flex;
  flex-direction: column;
  padding-block-start: var(--space-4);
}

.sidebar__account {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-block-start: var(--space-4);
  padding-inline: var(--space-2) var(--space-3);
  font-size: var(--font-size-lg);
  font-weight: 500;
}

.sidebar__account-name {
  flex: 1;
  min-inline-size: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar__signin {
  align-self: flex-start;
  gap: var(--space-1);
  min-block-size: 2.75rem;
  margin: var(--space-2) 0 0 var(--space-2);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-lg);
  font-weight: 500;
}

.sidebar__foot .sidebar__error {
  margin-block-start: var(--space-2);
}
</style>
