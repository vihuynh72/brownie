<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { getWelcome, searchForms, type FormEntry, type WelcomeResponse } from '@/api/client'
import { installTargets, type InstallTargetId } from '@/landing/installTargets'

/**
 * What a visitor who is not signed in sees at Brownie's address. It leads with a request said the way a person
 * would say it and Brownie's answer to it, then shows where Brownie can be used (the browser, a chat app, a
 * terminal agent) with the one thing each needs. It says nothing the deployment did not: the sign-up wording and
 * the chat-app address both come from the welcome answer.
 */

/** The sign-in start for whatever providers this deployment runs: Google when it is enabled, the Entra email code otherwise. */
const signInHref = computed(() =>
  welcome.value?.signInProviders?.includes('google')
    ? '/oauth2/authorization/google'
    : '/oauth2/authorization/entra')
const EMAIL_CODE_HREF = '/oauth2/authorization/entra'

const welcome = ref<WelcomeResponse | null>(null)
onMounted(async () => {
  try {
    welcome.value = await getWelcome()
  } catch {
    // An older server, or a passing failure: claim nothing about sign-up, and point chat apps at this origin, which is
    // where the address lives whenever chat apps are offered at all.
    welcome.value = { signUp: 'INVITATION_ONLY', mcpServerUrl: `${window.location.origin}/mcp` }
  }
})

// ---- Official forms ---------------------------------------------------------------------------------------------

/**
 * The form library, offered before sign-in: the same search Home uses, capped at eight, each with a
 * "Sign in to start" link. A failed catalog call takes the whole section away rather than promising
 * a library that is not there.
 */
const formQuery = ref('')
const formResults = ref<FormEntry[]>([])
const formLibraryFailed = ref(false)
let formSearchTimer: ReturnType<typeof setTimeout> | undefined
let formSearchSeq = 0

async function runFormSearch(): Promise<void> {
  const seq = ++formSearchSeq
  try {
    const found = await searchForms(formQuery.value.trim())
    if (seq !== formSearchSeq) return
    formResults.value = found.slice(0, 8)
  } catch {
    if (seq !== formSearchSeq) return
    formLibraryFailed.value = true
  }
}

onMounted(() => void runFormSearch())

function onFormQueryInput(): void {
  if (formSearchTimer) clearTimeout(formSearchTimer)
  formSearchTimer = setTimeout(() => void runFormSearch(), 250)
}

onBeforeUnmount(() => {
  if (formSearchTimer) clearTimeout(formSearchTimer)
})

const signUp = computed(() => welcome.value?.signUp ?? null)
const primaryLabel = computed(() => {
  if (signUp.value === 'OPEN_WITH_DAILY_ALLOWANCE') return 'Try it free'
  if (signUp.value === 'OPEN') return 'Get started'
  return 'Sign in'
})
const accessNote = computed(() => {
  if (signUp.value === 'OPEN_WITH_DAILY_ALLOWANCE') return 'Free to use every day. You sign in with a code sent to your email.'
  if (signUp.value === 'OPEN') return 'You sign in with a code sent to your email.'
  if (signUp.value === 'INVITATION_ONLY') return 'Brownie is open to invited people for now.'
  return ''
})

// ---- Where to use it --------------------------------------------------------------------------------------------

const targets = computed(() => installTargets(welcome.value ? (welcome.value.mcpServerUrl ?? null) : null))
const activeId = ref<InstallTargetId>('browser')
const active = computed(() => targets.value.find((target) => target.id === activeId.value) ?? targets.value[0]!)
const tabRefs = ref<HTMLButtonElement[]>([])

async function choose(id: InstallTargetId, focus = false): Promise<void> {
  activeId.value = id
  copyState.value = 'idle'
  if (!focus) return
  await nextTick()
  tabRefs.value.find((tab) => tab.dataset.target === id)?.focus()
}

/** The hero's chat-app button lands on the install section with Claude already chosen, when chat apps are offered. */
function openChatAppTab(): void {
  if (targets.value.some((target) => target.id === 'claude')) void choose('claude')
}

/** The arrow keys move along the tabs, as a tab list does everywhere else; Home and End jump to either end. */
function onTabKey(event: KeyboardEvent): void {
  const ids = targets.value.map((target) => target.id)
  const at = ids.indexOf(activeId.value)
  const next: Record<string, number> = { ArrowRight: at + 1, ArrowLeft: at - 1, Home: 0, End: ids.length - 1 }
  if (!(event.key in next)) return
  event.preventDefault()
  const index = (next[event.key]! + ids.length) % ids.length
  void choose(ids[index]!, true)
}

const copyState = ref<'idle' | 'copied' | 'select'>('idle')
const lineRef = ref<HTMLElement | null>(null)
let copyTimer: ReturnType<typeof setTimeout> | undefined

async function copyLine(): Promise<void> {
  const line = active.value.line
  if (!line) return
  clearTimeout(copyTimer)
  try {
    await navigator.clipboard.writeText(line)
    copyState.value = 'copied'
    copyTimer = setTimeout(() => (copyState.value = 'idle'), 2400)
  } catch {
    // No clipboard here (an insecure origin, or the permission refused): select the line so one keystroke copies it.
    const selection = window.getSelection()
    if (lineRef.value && selection) {
      const range = document.createRange()
      range.selectNodeContents(lineRef.value)
      selection.removeAllRanges()
      selection.addRange(range)
    }
    copyState.value = 'select'
  }
}
onBeforeUnmount(() => clearTimeout(copyTimer))

const copyLabel = computed(() => {
  if (copyState.value === 'copied') return 'Copied'
  if (copyState.value === 'select') return 'Selected: copy it with your keyboard'
  return active.value.copies === 'command' ? 'Copy command' : 'Copy address'
})

// ---- Proof ------------------------------------------------------------------------------------------------------

/** Public forms Brownie was run against in testing, and how many places it found in each. Measured, not estimated. */
const TESTED_FORMS = [
  { form: 'Form W-9', what: 'Request for Taxpayer Identification Number', from: 'IRS', places: 23 },
  { form: 'Form W-4', what: 'Employee’s Withholding Certificate', from: 'IRS', places: 43 },
  { form: 'Form SS-4', what: 'Application for Employer Identification Number', from: 'IRS', places: 45 },
  { form: 'Form I-9', what: 'Employment Eligibility Verification', from: 'USCIS', places: 106 },
  {
    form: 'Uniform Hospital Financial Assistance Application',
    what: 'PDF and Word editions',
    from: 'New York State Department of Health',
    places: 36,
  },
] as const
</script>

<template>
  <div class="landing">
    <div v-if="$slots.notice" class="landing__notice"><slot name="notice" /></div>

    <header class="landing__bar">
      <a class="landing__brand" href="/" aria-label="Brownie, home">
        <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="document-text" :size="18" /></span>
        <span>Brownie</span>
      </a>
      <nav class="landing__nav" aria-label="Brownie">
        <a href="#how">How it works</a>
        <a href="#install">Install</a>
        <a :href="signInHref">Sign in</a>
      </nav>
      <a class="button button--primary landing__bar-action" :href="signInHref">{{ primaryLabel }}</a>
    </header>

    <section class="hero" aria-labelledby="landing-title">
      <figure class="exchange" aria-label="An example request and Brownie's answer">
        <blockquote class="exchange__you">
          <p>
            “Fill my rental application. I’m <mark class="exchange__fact" style="--i: 0">Maya Chen</mark>, I’ve worked
            at <mark class="exchange__fact" style="--i: 1">Harbor Freight Logistics</mark> since
            <mark class="exchange__fact" style="--i: 2">March 2022</mark>, and I make
            <mark class="exchange__fact" style="--i: 3">$4,800 a month</mark>.”
          </p>
        </blockquote>
        <figcaption class="exchange__brownie">
          <span class="exchange__who"><AppIcon name="sparkle" :size="16" />Brownie</span>
          <span class="exchange__reply">“Done. 14 places filled. Two are yours: your SSN and your signature.”</span>
          <span class="exchange__receipt">
            <span class="tag tag--honey"><AppIcon name="fill-here" :size="16" />14 filled from what you said</span>
            <span class="tag exchange__yours"><AppIcon name="pencil" :size="16" />2 left for you</span>
            <span class="tag tag--success"><AppIcon name="check-circle" :size="16" />Nothing exported until you approve</span>
          </span>
        </figcaption>
      </figure>

      <div class="hero__foot">
        <h1 id="landing-title" class="hero__title">
          Brownie fills your forms from what you tell it. You make the calls.
        </h1>
        <div class="hero__actions">
          <a class="button button--primary hero__action" :href="signInHref">{{ primaryLabel }}</a>
          <a v-if="welcome?.signInProviders?.includes('google')" :href="EMAIL_CODE_HREF" class="landing__email-code">Use an email code instead</a>
          <a class="button hero__action" href="#install" @click="openChatAppTab">Add to Claude or ChatGPT</a>
        </div>
        <p v-if="accessNote" class="hero__note">{{ accessNote }}</p>
      </div>
    </section>

    <section v-if="!formLibraryFailed" class="landing-forms" aria-labelledby="landing-forms-title">
      <div class="landing-forms__head">
        <h2 id="landing-forms-title" class="landing__h2">Start from an official form</h2>
        <p class="landing__lede">
          Tax, immigration, veterans and health forms, downloaded from the agency that publishes them.
        </p>
      </div>
      <input
        v-model="formQuery"
        class="landing-forms__search"
        type="search"
        placeholder="W-9, I-9, passport, VA…"
        aria-label="Find an official form"
        @input="onFormQueryInput"
      />
      <p v-if="!formQuery.trim()" class="landing-forms__popular">Popular forms</p>
      <ul v-if="formResults.length" class="landing-forms__list">
        <li v-for="entry in formResults" :key="entry.id" class="landing-forms__row">
          <div class="landing-forms__text">
            <p class="landing-forms__title">{{ entry.title }}</p>
            <p class="landing-forms__agency">{{ entry.agency }}</p>
          </div>
          <a class="button button--small landing-forms__start" :href="signInHref">Sign in to start</a>
        </li>
      </ul>
      <p v-else-if="formQuery.trim()" class="landing-forms__empty">No match. You can always upload your own copy.</p>
    </section>

    <section id="install" class="install" aria-labelledby="install-title">
      <div class="install__head">
        <h2 id="install-title" class="landing__h2">Works where you already are.</h2>
        <p class="landing__lede">
          Use Brownie in your browser, or add it to the assistant you already talk to. It's the same Brownie with the
          same rule everywhere: you approve before anything is exported.
        </p>
      </div>

      <div class="install__tabs" role="tablist" aria-label="Where to use Brownie" @keydown="onTabKey">
        <button
          v-for="target in targets"
          :id="`install-tab-${target.id}`"
          :key="target.id"
          ref="tabRefs"
          type="button"
          role="tab"
          class="install__tab"
          :data-target="target.id"
          :aria-selected="target.id === active.id"
          :aria-controls="`install-panel`"
          :tabindex="target.id === active.id ? 0 : -1"
          @click="choose(target.id)"
        >
          <AppIcon :name="target.icon" :size="18" />{{ target.label }}
        </button>
      </div>

      <div id="install-panel" class="install__panel" role="tabpanel" :aria-labelledby="`install-tab-${active.id}`">
        <div v-if="active.line" class="install__line-box">
          <code ref="lineRef" class="install__line">
            <span v-if="active.copies === 'command'" class="install__prompt" aria-hidden="true">$ </span>
            <template v-for="(part, index) in active.lineParts" :key="index"
              ><span class="install__part" :class="{ 'install__part--address': index > 0 || active.copies === 'address' }">{{
                part
              }}</span
              >{{ index < active.lineParts.length - 1 ? ' ' : '' }}</template
            >
          </code>
          <button type="button" class="button install__copy" @click="copyLine">
            <AppIcon :name="copyState === 'copied' ? 'check' : 'copy'" :size="16" />{{ copyLabel }}
          </button>
          <span class="visually-hidden" aria-live="polite">{{ copyState === 'copied' ? 'Copied to the clipboard.' : '' }}</span>
        </div>
        <div v-else class="install__line-box install__line-box--browser">
          <p class="install__browser">Nothing to install. Brownie runs right here.</p>
          <a class="button button--primary" :href="signInHref">{{ primaryLabel }}</a>
        </div>
        <ol class="install__steps">
          <li v-for="(step, index) in active.steps" :key="index">{{ step }}</li>
        </ol>
      </div>
    </section>

    <section id="how" class="how" aria-labelledby="how-title">
      <h2 id="how-title" class="landing__h2">Brownie leads. You decide.</h2>
      <ol class="how__steps">
        <li class="how__step">
          <span class="how__num" aria-hidden="true">1</span>
          <h3 class="how__title">Hand it the form</h3>
          <p>
            Word, PDF, Pages, OpenDocument, RTF, or a photo of the paper. Brownie finds every blank, box and checkbox in
            seconds, and keeps the form's own look.
          </p>
        </li>
        <li class="how__step">
          <span class="how__num" aria-hidden="true">2</span>
          <h3 class="how__title">Tell it what you know</h3>
          <p>
            Type it, say it out loud, or point it at a document you already have. If something is missing, Brownie
            asks you instead of guessing.
          </p>
        </li>
        <li class="how__step">
          <span class="how__num" aria-hidden="true">3</span>
          <h3 class="how__title">Approve, then export</h3>
          <p>
            Every value shows the words it came from. You accept or change each one. Then you download Word or PDF,
            and a fillable PDF stays fillable.
          </p>
        </li>
      </ol>
    </section>

    <section class="tested" aria-labelledby="tested-title">
      <div class="tested__head">
        <h2 id="tested-title" class="landing__h2">Tested on real forms.</h2>
        <p class="landing__lede">
          Public forms people actually have to fill in, uploaded as they are. The count is how many places Brownie
          found to fill.
        </p>
      </div>
      <table class="tested__table">
        <thead>
          <tr>
            <th scope="col">Form</th>
            <th scope="col">From</th>
            <th scope="col" class="tested__num">Places found</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in TESTED_FORMS" :key="row.form">
            <td>
              <span class="tested__form">{{ row.form }}</span>
              <span class="tested__what"><span class="tested__agency">{{ row.from }} · </span>{{ row.what }}</span>
            </td>
            <td class="tested__from">{{ row.from }}</td>
            <td class="tested__num"><span class="tested__count">{{ row.places }}</span></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="yours" aria-labelledby="yours-title">
      <h2 id="yours-title" class="landing__h2">What stays yours.</h2>
      <ul class="yours__list">
        <li><AppIcon name="pencil" :size="22" /><span><strong>Your signature.</strong> Brownie never signs for you.</span></li>
        <li>
          <AppIcon name="check-circle" :size="22" /><span
            ><strong>Every value.</strong> Nothing is exported until you approve it, and each value shows where it came
            from.</span
          >
        </li>
        <li>
          <AppIcon name="shield-check" :size="22" /><span
            ><strong>Your data.</strong> Your workspace is private to you, and you can delete everything from Your data
            at any time.</span
          >
        </li>
      </ul>
    </section>

    <footer class="landing__footer">
      <span class="landing__brand landing__brand--quiet">
        <span class="doc-tile doc-tile--small" aria-hidden="true"><AppIcon name="document-text" :size="14" /></span>
        <span>Brownie</span>
      </span>
      <span class="landing__footer-line">Fills your forms from what you tell it.</span>
      <a :href="signInHref">{{ primaryLabel }}</a>
    </footer>
  </div>
</template>

<style scoped>
.landing {
  --landing-gutter: clamp(1.25rem, 0.6rem + 3vw, 4rem);
  --landing-max: 90rem;
  min-block-size: 100dvh;
  background: var(--color-paper);
}

.landing > * {
  padding-inline: var(--landing-gutter);
}

.landing__notice {
  max-inline-size: var(--landing-max);
  margin-inline: auto;
  padding-block-start: var(--space-4);
}

.landing__bar {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  max-inline-size: var(--landing-max);
  margin-inline: auto;
  padding-block: var(--space-4);
}

.landing__brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  margin-inline-end: auto;
  color: var(--color-text);
  font-weight: var(--font-weight-medium);
  font-size: var(--font-size-lg);
  text-decoration: none;
}

.landing__nav {
  display: none;
  gap: var(--space-5);
}

.landing__nav a,
.landing__footer a {
  color: var(--color-text);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  text-decoration: none;
  text-underline-offset: 0.25em;
}

.landing__nav a:hover,
.landing__footer a:hover {
  text-decoration: underline;
}

@media (min-width: 48rem) {
  .landing__nav {
    display: flex;
  }
}

/* ---------- The exchange ---------- */

.hero {
  display: grid;
  gap: var(--space-6);
  max-inline-size: var(--landing-max);
  margin-inline: auto;
  padding-block: clamp(var(--space-6), 4vw, 5rem) clamp(var(--space-8), 6vw, 6rem);
}

.exchange {
  display: grid;
  gap: clamp(var(--space-5), 3vw, var(--space-8));
  margin: 0;
}

.exchange__you {
  margin: 0;
  max-inline-size: 22em;
  font-size: clamp(1.875rem, 1rem + 3.4vw, 3.5rem);
  line-height: 1.12;
  letter-spacing: -0.02em;
  text-wrap: pretty;
}

.exchange__you p {
  margin: 0;
}

/* A fact Brownie took from the sentence: marked as a highlighter would mark it, sweeping in one after another. */
.exchange__fact {
  padding: 0 0.08em;
  margin: 0 -0.04em;
  border-radius: 0.18em;
  color: inherit;
  background: linear-gradient(var(--color-honey-spot), var(--color-honey-spot)) no-repeat left center / 100% 100%;
  box-decoration-break: clone;
  -webkit-box-decoration-break: clone;
  animation: landing-mark 560ms cubic-bezier(0.16, 1, 0.3, 1) both;
  animation-delay: calc(260ms + var(--i, 0) * 160ms);
}

.exchange__brownie {
  display: grid;
  gap: var(--space-3);
  max-inline-size: 22em;
  font-size: clamp(1.75rem, 0.9rem + 3vw, 3.25rem);
}

.exchange__who {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-cocoa-strong);
}

.exchange__reply {
  font-weight: var(--font-weight-medium);
  line-height: 1.06;
  letter-spacing: -0.025em;
  color: var(--color-cocoa-strong);
  text-wrap: balance;
}

.exchange__receipt {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-block-start: var(--space-2);
}

/* The receipt reads at the size of the words around it, not the size of a chip in a list. */
.exchange__receipt .tag {
  min-block-size: 2rem;
  padding-inline: var(--space-3);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
}

/* What Brownie left for the person reads as an empty place on a form: a dashed edge, not a colour. */
.exchange__yours {
  background: transparent;
  border: 1px dashed var(--color-field-border);
}

.hero__foot {
  display: grid;
  gap: var(--space-4);
  align-content: start;
}

.hero__title {
  margin: 0;
  max-inline-size: 26ch;
  font-size: clamp(1.25rem, 1rem + 0.8vw, 1.625rem);
  font-weight: var(--font-weight-medium);
  line-height: 1.25;
  text-wrap: balance;
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.hero__action {
  --button-height: 3rem;
  --button-pad: 1.5rem;
  font-size: var(--font-size-base);
}

.hero__note {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}

@media (max-width: 29.99rem) {
  .hero__action {
    inline-size: 100%;
  }
}

/* On a wide screen the two voices answer each other across the page: the person on the left, Brownie on the right. */
@media (min-width: 64rem) {
  .hero {
    grid-template-columns: repeat(12, minmax(0, 1fr));
    column-gap: var(--space-5);
    row-gap: var(--space-6);
  }

  .exchange {
    grid-column: 1 / -1;
    grid-template-columns: repeat(12, minmax(0, 1fr));
    column-gap: var(--space-5);
  }

  .exchange__you {
    grid-column: 1 / span 9;
  }

  .exchange__brownie {
    grid-column: 6 / span 7;
  }

  .hero__foot {
    grid-column: 1 / span 6;
    grid-row: 2;
  }
}

@keyframes landing-mark {
  from {
    background-size: 0% 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .exchange__fact {
    animation: none;
  }
}

/* ---------- Shared section type ---------- */

.landing__h2 {
  margin: 0;
  font-size: clamp(2rem, 1.2rem + 2.6vw, 3.25rem);
  font-weight: var(--font-weight-medium);
  line-height: 1.04;
  letter-spacing: -0.025em;
  text-wrap: balance;
}

.landing__lede {
  margin: var(--space-3) 0 0;
  text-wrap: pretty;
  max-inline-size: 58ch;
  font-size: var(--font-size-lg);
  line-height: 1.45;
  color: var(--color-text-muted);
}

/* ---------- Where to use it ---------- */

.landing-forms {
  padding-block: clamp(var(--space-8), 6vw, 6rem);
}

.landing-forms > * {
  max-inline-size: var(--landing-max);
  margin-inline: auto;
  padding-inline: clamp(var(--space-4), 4vw, var(--space-10));
}

.landing-forms__search {
  display: block;
  margin-block-start: var(--space-4);
  max-inline-size: 28rem;
  inline-size: 100%;
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text);
  font: inherit;
}

.landing-forms__popular {
  margin: var(--space-4) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-faint);
}

.landing-forms__list {
  list-style: none;
  margin: var(--space-3) 0 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.landing-forms__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.landing-forms__title {
  margin: 0;
  font-weight: 500;
}

.landing-forms__agency {
  margin: var(--space-1) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}

.landing-forms__start {
  flex-shrink: 0;
  text-decoration: none;
}

.landing-forms__empty {
  margin: var(--space-3) 0 0;
  color: var(--color-text-muted);
}

.install {
  background: var(--color-surface);
  border-block: 1px solid var(--color-hairline);
  padding-block: clamp(var(--space-8), 6vw, 6rem);
}

.install > * {
  max-inline-size: var(--landing-max);
  margin-inline: auto;
}

.install__tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-block-start: var(--space-6);
}

.install__tab {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  min-block-size: 2.75rem;
  padding: 0 var(--space-4);
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  transition:
    background var(--motion-fast) var(--motion-ease),
    color var(--motion-fast) var(--motion-ease);
}

.install__tab:hover {
  background: var(--color-paper-soft);
  color: var(--color-text);
}

.install__tab[aria-selected='true'] {
  background: var(--color-text);
  border-color: var(--color-text);
  color: var(--color-paper);
}

.install__panel {
  display: grid;
  gap: var(--space-5);
  margin-block-start: var(--space-5);
}

.install__line-box {
  display: grid;
  gap: var(--space-4);
  justify-items: start;
  padding: clamp(var(--space-5), 3vw, var(--space-6));
  border-radius: var(--radius-lg);
  background: var(--color-paper);
}

.install__line {
  display: block;
  max-inline-size: 100%;
  font-family: var(--font-mono);
  font-size: clamp(1.0625rem, 0.6rem + 1.9vw, 2.25rem);
  line-height: 1.3;
  letter-spacing: -0.01em;
  color: var(--color-text);
  overflow-wrap: anywhere;
}

.install__line::selection,
.install__line *::selection {
  background: var(--color-honey-spot);
}

.install__prompt {
  color: var(--color-cocoa);
}

.install__part--address {
  color: var(--color-cocoa-strong);
}

.install__copy {
  --button-height: 2.75rem;
}

.install__browser {
  margin: 0;
  font-size: clamp(1.25rem, 0.9rem + 1.4vw, 2rem);
  font-weight: var(--font-weight-medium);
  letter-spacing: -0.015em;
}

.install__steps {
  display: grid;
  gap: var(--space-3);
  margin: 0;
  padding: 0;
  list-style: none;
  counter-reset: install-step;
  max-inline-size: 60ch;
}

.install__steps li {
  display: grid;
  text-wrap: pretty;
  grid-template-columns: 2rem minmax(0, 1fr);
  gap: var(--space-2);
  font-size: var(--font-size-base);
  line-height: 1.45;
  counter-increment: install-step;
}

.install__steps li::before {
  content: counter(install-step);
  display: grid;
  place-items: center;
  inline-size: 1.625rem;
  block-size: 1.625rem;
  border-radius: 50%;
  background: var(--color-cocoa-soft);
  color: var(--color-cocoa-strong);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

/* ---------- How it works ---------- */

.how,
.tested,
.yours {
  max-inline-size: var(--landing-max);
  margin-inline: auto;
  padding-block: clamp(var(--space-8), 6vw, 6rem) 0;
}

.how__steps {
  display: grid;
  gap: var(--space-6);
  margin: var(--space-6) 0 0;
  padding: 0;
  list-style: none;
}

.how__step {
  display: grid;
  gap: var(--space-2);
  padding-block-start: var(--space-4);
  border-block-start: 2px solid var(--color-text);
}

.how__num {
  font-size: clamp(2.5rem, 1.6rem + 2.6vw, 4rem);
  font-weight: var(--font-weight-medium);
  line-height: 1;
  color: var(--color-cocoa);
}

.how__title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-medium);
  line-height: 1.2;
}

.how__step p {
  margin: 0;
  max-inline-size: 42ch;
  text-wrap: pretty;
  color: var(--color-text-muted);
  line-height: 1.5;
}

@media (min-width: 56rem) {
  .how__steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: var(--space-6);
  }
}

/* ---------- Tested on real forms ---------- */

.tested__table {
  inline-size: 100%;
  margin-block-start: var(--space-6);
  border-collapse: collapse;
  font-size: var(--font-size-base);
}

.tested__table th {
  padding: 0 var(--space-3) var(--space-3) 0;
  border-block-end: 2px solid var(--color-text);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-muted);
  text-align: start;
}

.tested__table td {
  padding: var(--space-4) var(--space-3) var(--space-4) 0;
  border-block-end: 1px solid var(--color-hairline);
  vertical-align: top;
}

.tested__form {
  display: block;
  font-weight: var(--font-weight-medium);
}

.tested__what {
  display: block;
}

.tested__what,
.tested__from {
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}

.tested__agency {
  display: none;
}

/* On a phone the agency joins the form's own line, and the table keeps two columns it has room for. */
@media (max-width: 37.49rem) {
  .tested__table th:nth-child(2),
  .tested__table td:nth-child(2) {
    display: none;
  }

  .tested__agency {
    display: inline;
  }
}

.tested__num {
  text-align: end;
  white-space: nowrap;
  padding-inline-end: 0;
}

.tested__count {
  display: inline-block;
  min-inline-size: 3ch;
  padding: 0.125rem 0.625rem;
  border-radius: var(--radius-pill);
  background: var(--color-honey-spot);
  color: var(--color-honey-ink);
  font-family: var(--font-mono);
  font-size: var(--font-size-sm);
  text-align: center;
  font-variant-numeric: tabular-nums;
}

/* ---------- What stays yours ---------- */

.yours__list {
  display: grid;
  gap: var(--space-4);
  margin: var(--space-6) 0 0;
  padding: 0;
  list-style: none;
}

.yours__list li {
  display: grid;
  text-wrap: pretty;
  grid-template-columns: 1.75rem minmax(0, 1fr);
  gap: var(--space-3);
  align-items: start;
  max-inline-size: 60ch;
  font-size: var(--font-size-lg);
  line-height: 1.45;
}

.yours__list li > svg {
  margin-block-start: 0.15em;
  color: var(--color-cocoa);
}

.yours__list strong {
  font-weight: var(--font-weight-medium);
}

/* ---------- Footer ---------- */

.landing__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3) var(--space-5);
  max-inline-size: var(--landing-max);
  margin: clamp(var(--space-8), 6vw, 6rem) auto 0;
  padding-block: var(--space-5) var(--space-6);
  border-block-start: 1px solid var(--color-hairline);
}

.landing__brand--quiet {
  margin-inline-end: 0;
  font-size: var(--font-size-base);
}

.landing__footer-line {
  margin-inline-end: auto;
  font-size: var(--font-size-sm);
  color: var(--color-text-muted);
}
</style>
