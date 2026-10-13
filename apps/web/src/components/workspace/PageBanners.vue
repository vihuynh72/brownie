<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

/**
 * The notes between a page's status line and its sheet, one under another: what Brownie found in the form, the
 * values it proposes (drawn on the page as a preview until they are approved), and a newer version of the form.
 * What Brownie is doing right now (reading, or waiting for answers) is a small chip that floats over the sheet's
 * corner, so the sheet does not move when the chip comes and goes.
 *
 * It holds no state of its own: the workspace decides what each note says and what its buttons do.
 */
const props = defineProps<{
  /** The line about the form: the places Brownie found while any is unchecked, else the notes about the upload. Empty for none. */
  stripText: string
  /** How many places Brownie found are still unchecked; while any is, the line offers "Keep all". */
  foundCount: number
  /** Keeping places is under way, so a second press does nothing. */
  keepBusy: boolean
  /** The notes about the form, listed under the line behind "Details". */
  notes: readonly string[]
  /** The notes are offered behind "Details" rather than said by the line itself. */
  notesOffered: boolean
  notesOpen: boolean
  /** Nothing is left to check, so the line only carries the notes and can be dismissed for good. */
  canDismiss: boolean
  /** The values waiting for approval, drawn on the page; null for none. `shown` is whether the page draws them. */
  preview: { count: number; shown: boolean } | null
  /** A newer version of the form the document can move to; null for none. */
  newer: { text: string; error: string | null; moving: boolean } | null
  /** What the move to the newest version just did. */
  moved: string | null
  /** What Brownie is doing now; null for nothing. */
  chip: { icon: 'sparkle' | 'help-circle'; text: string } | null
  /** Whether the chip floats over the sheet; a page drawn another way keeps it in the flow above. */
  chipOverSheet: boolean
}>()

const emit = defineEmits<{
  'keep-all': []
  'toggle-notes': []
  'dismiss-notes': []
  'toggle-preview': []
  move: []
  'set-aside': []
}>()

const visible = computed(() => props.stripText !== '' || props.preview !== null || props.newer !== null || props.moved !== null || props.chip !== null)
const chipOnly = computed(() => props.chip !== null && props.stripText === '' && props.preview === null && props.newer === null && props.moved === null)

/** The line's first sentence says what it is about; whatever follows it says what to do. */
const stripLines = computed(() => {
  const split = /^(.+?[.!?])\s+(\S.*)$/s.exec(props.stripText)
  return split ? { title: split[1]!, rest: split[2]! } : { title: props.stripText, rest: '' }
})

const previewWords = computed(() => {
  const spots = `${props.preview?.count ?? 0} fill ${props.preview?.count === 1 ? 'spot' : 'spots'}`
  return props.preview?.shown
    ? { title: `Previewing ${spots} from Brownie`, note: 'Dashed values are not in the document yet. Nothing is filled until you approve.' }
    : { title: `Brownie has ${spots} for you to approve`, note: 'The preview is hidden. Nothing is filled until you approve.' }
})

const buttons = ref<HTMLElement | null>(null)
const moveButton = ref<HTMLElement | null>(null)

defineExpose({
  /** Moves focus to the first button left on the line about the form; false when it has none. */
  focusStripButton(): boolean {
    const next = buttons.value?.querySelector<HTMLElement>('button')
    next?.focus()
    return next != null
  },
  /** Moves focus to the button that moves the document to the newest version of its form. */
  focusMove(): void {
    moveButton.value?.focus()
  },
})
</script>

<template>
  <div v-if="visible" class="page-banners" :class="{ 'page-banners--chip-only': chipOnly }">
    <!--
      Not a live region: it holds its buttons, and the page's live line says once that the notes are there.
    -->
    <section v-if="stripText" class="form-strip" :class="{ 'form-strip--found': foundCount > 0 }" aria-label="About this document">
      <div class="form-strip__line">
        <span class="banner-tile" :class="{ 'banner-tile--found': foundCount > 0 }" aria-hidden="true">
          <AppIcon :name="foundCount > 0 ? 'sparkle' : 'info'" :size="18" />
        </span>
        <p class="form-strip__text">
          <span class="form-strip__title">{{ stripLines.title }}</span>
          <span v-if="stripLines.rest" class="form-strip__rest">{{ ` ${stripLines.rest}` }}</span>
        </p>
        <div ref="buttons" class="form-strip__buttons">
          <button v-if="foundCount > 0" type="button" class="button button--secondary button--small" :aria-disabled="keepBusy" @click="emit('keep-all')">
            <AppIcon name="check" />Keep all<span class="visually-hidden"> the places Brownie found</span>
          </button>
          <button
            v-if="notesOffered"
            type="button"
            class="button button--quiet button--small"
            :aria-expanded="notesOpen ? 'true' : 'false'"
            aria-controls="form-notes-list"
            @click="emit('toggle-notes')"
          >
            Details<span class="visually-hidden"> about this document</span>
          </button>
          <button v-if="canDismiss" type="button" class="button button--quiet button--small" @click="emit('dismiss-notes')">
            Dismiss<span class="visually-hidden"> the notes about this document</span>
          </button>
        </div>
      </div>
      <ul v-if="notesOffered" v-show="notesOpen" id="form-notes-list" class="form-strip__notes">
        <li v-for="note in notes" :key="note">{{ note }}</li>
      </ul>
    </section>

    <!-- What approving would put in the document, drawn on the page as dashed values; hiding it only hides the drawing. -->
    <section v-if="preview" class="preview-band" aria-label="Preview of Brownie’s values">
      <AppIcon name="eye" :size="18" />
      <div class="preview-band__text">
        <p class="preview-band__title">{{ previewWords.title }}</p>
        <p class="preview-band__note">{{ previewWords.note }}</p>
      </div>
      <button type="button" class="button button--secondary button--small" @click="emit('toggle-preview')">
        <AppIcon name="eye" />{{ preview.shown ? 'Hide preview' : 'Show preview' }}
      </button>
    </section>

    <!-- A newer version of this document's form (a fill spot added from another document): what it changes, and the move. -->
    <div v-if="newer" class="version-banner">
      <span class="banner-tile" aria-hidden="true"><AppIcon name="layout" :size="18" /></span>
      <p class="version-banner__text" role="status">{{ newer.text }}</p>
      <div class="version-banner__buttons">
        <button
          ref="moveButton"
          type="button"
          class="button button--primary button--small"
          :aria-disabled="newer.moving ? 'true' : undefined"
          @click="!newer.moving && emit('move')"
        >
          {{ newer.moving ? 'Moving…' : 'Move this document to it' }}
        </button>
        <button type="button" class="button button--secondary button--small" :aria-disabled="newer.moving ? 'true' : undefined" @click="!newer.moving && emit('set-aside')">
          Not now
        </button>
      </div>
      <p v-if="newer.error" class="field-error version-banner__error" role="alert">{{ newer.error }}</p>
    </div>
    <p v-if="moved" class="version-banner__moved">{{ moved }}</p>

    <div v-if="chip" class="page-chip-anchor" :class="{ 'page-chip-anchor--over': chipOverSheet }">
      <p class="page-chip" role="status">
        <AppIcon :name="chip.icon" :size="14" />
        <span>{{ chip.text }}</span>
      </p>
    </div>
  </div>
</template>

<style scoped>
/*
 * The gap between the banner stack and the sheet under it is the page's own; the chip that floats over the sheet's
 * corner is placed from the point where the sheet starts, so it needs to know that gap.
 */
.page-banners {
  --banner-gap: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--banner-gap);
  min-inline-size: 0;
}

/* Alone, the chip takes no room: the sheet stays exactly where it would be with nothing above it. */
.page-banners--chip-only {
  margin-block-end: calc(-1 * var(--banner-gap));
}

/* One note: a white card, as wide as the sheet, with a tile for its sign, its words, and its buttons at the end. */
.form-strip,
.version-banner {
  padding: 0.75rem 0.875rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.form-strip__line,
.version-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) 0.75rem;
}

/* The circle holds Brownie's sparkle while places are to check, and a plain sign for the other notes. */
.banner-tile {
  display: inline-grid;
  flex: none;
  place-items: center;
  inline-size: 2rem;
  block-size: 2rem;
  border-radius: 50%;
  background: var(--color-paper-deep);
  color: var(--color-text-muted);
}

.banner-tile--found {
  background: var(--color-honey-soft);
  color: var(--color-honey-ink);
}

.form-strip__text,
.version-banner__text {
  flex: 1 1 14rem;
  min-inline-size: 0;
  margin: 0;
  font-size: var(--font-size-xs);
  line-height: 1.4;
  color: var(--color-text-muted);
}

.form-strip__title {
  display: block;
  color: var(--color-text);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.form-strip__rest {
  display: block;
}

.form-strip__buttons,
.version-banner__buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  margin-inline-start: auto;
}

.form-strip__notes {
  margin: 0.75rem 0 0;
  padding: 0.75rem 0 0 1.25rem;
  border-block-start: 1px solid var(--color-hairline);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

.form-strip__notes li + li {
  margin-block-start: var(--space-1);
}

/* A newer version is the one note that asks something of the person, so its words are main text. */
.version-banner__text {
  color: var(--color-text);
  font-size: var(--font-size-sm);
}

.version-banner__error {
  flex: 1 1 100%;
  margin: 0;
}

.version-banner__moved {
  margin: 0;
  padding: calc(0.5rem - 1px) calc(0.75rem - 1px);
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  background: var(--color-cocoa-soft);
  font-size: var(--font-size-sm);
}

/* The values waiting for approval: honey, like the dashed values they draw on the page. */
.preview-band {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) 0.75rem;
  /* The edge is transparent so a forced-colours display can still draw it; the padding is less by its width. */
  padding: calc(0.5625rem - 1px) calc(0.625rem - 1px) calc(0.5625rem - 1px) calc(0.875rem - 1px);
  border: 1px solid transparent;
  border-radius: var(--radius-lg);
  background: var(--color-honey-soft);
  color: var(--color-honey-ink);
}

.preview-band > svg {
  flex: none;
}

.preview-band__text {
  flex: 1 1 14rem;
  min-inline-size: 0;
}

.preview-band__title,
.preview-band__note {
  margin: 0;
  line-height: 1.4;
}

.preview-band__title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.preview-band__note {
  font-size: var(--font-size-xs);
}

/* The chip: a small honey pill. In the flow it sits at the end of its line; floating, over the sheet's top corner. */
.page-chip-anchor {
  display: flex;
  justify-content: flex-end;
}

.page-chip-anchor--over {
  position: relative;
  block-size: 0;
  margin-block-end: calc(-1 * var(--banner-gap));
}

.page-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  min-block-size: 1.625rem;
  margin: 0;
  padding: calc(0.125rem - 1px) calc(0.6875rem - 1px);
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: var(--color-honey-soft);
  color: var(--color-honey-ink);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: 1.25;
}

.page-chip > svg {
  flex: none;
}

/* Pressing through it reaches whatever the sheet has under it; it only tells, and takes nothing. */
.page-chip-anchor--over .page-chip {
  position: absolute;
  z-index: 1;
  pointer-events: none;
  inset-block-start: 1.125rem;
  inset-inline-end: 1.125rem;
  max-inline-size: calc(100% - 2.25rem);
}

/*
 * The chip's own sentence does not get any shorter as the sheet narrows, so past this width the corner it floats
 * over is not wide enough to hold it beside the document's own first line without the two crossing: it joins the
 * stack above the sheet instead, exactly where it already sits on a page that never floats it, and the sheet
 * moves down to leave room the way it does for any other note. The sheet is this narrow twice: once the window
 * itself is a phone's width, and again, briefly, while the second column is still making room for itself and the
 * sheet has not yet settled at its own full width.
 */
@container main (max-width: 33rem) {
  .page-chip-anchor--over {
    position: static;
    block-size: auto;
    margin-block-end: 0;
  }

  .page-chip-anchor--over .page-chip {
    position: static;
    pointer-events: auto;
    max-inline-size: none;
  }
}

@container main (min-width: 50rem) and (max-width: 68rem) {
  .page-chip-anchor--over {
    position: static;
    block-size: auto;
    margin-block-end: 0;
  }

  .page-chip-anchor--over .page-chip {
    position: static;
    pointer-events: auto;
    max-inline-size: none;
  }
}
</style>
