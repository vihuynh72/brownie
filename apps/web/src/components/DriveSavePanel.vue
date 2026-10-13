<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { proposeDocAppend, proposeDriveSave, type ActionResponse, type DriveSaveKind, type ExportReceiptResponse } from '@/api/client'
import { stateSentence } from '@/connections/words'
import {
  DOC_APPEND_WORDS,
  DRIVE_PANEL_TYPES,
  DRIVE_SAVE_CHOICES,
  DRIVE_SAVE_WORDS,
  actionLink,
  characterCount,
  conversionSentence,
  docAppendPreview,
  docAppendSentence,
  driveSaveAfterwards,
  driveSavePreview,
  driveSaveSentence,
  driveSaveWhat,
  driveSaveWhere,
  exactTimeOf,
  mayAcknowledge,
  timeOf,
  withdrawable,
  type DocAppendPreview,
  type DriveSavePreview,
} from '@/actions/words'
import { useActionList, type ActionWords } from '@/actions/useActionList'
import { ADD_STANDING, SAVE_STANDING, connectionStanding, standingOf, type Standing } from '@/actions/standing'

/**
 * Saving the document's latest export to the person's own Google Drive: the
 * exact Word or PDF file, or a Google Doc that Google converts the Word file
 * into. Preparing a save shows exactly what approving it would do, read from
 * the payload the approval covers; nothing is saved until the person
 * approves that. A save whose outcome Brownie cannot know stays on the list,
 * with the way to ask Google, until the person says they have checked it; it
 * is never sent again from here by itself.
 *
 * The list does not depend on the export: a save made from an earlier export
 * stays reachable after the document changes. Only preparing a new save needs
 * the latest export, and so does adding the current version's text to the end
 * of a Google Doc a save made, which is offered on that save.
 *
 * Each save is a card of its own, with where it stands in a chip at the top.
 * The form that prepares a save gives way to a save waiting for approval, so
 * there is one thing to decide at a time, and comes back once it is settled.
 */
const SAVE_WORDS: ActionWords = {
  kind: DRIVE_SAVE_WORDS,
  thing: 'save',
  outcome: 'the file was saved',
  lookIn: 'your Google Drive',
  what: 'a way to save to Google Drive',
}

const APPEND_WORDS: ActionWords = {
  kind: DOC_APPEND_WORDS,
  thing: 'addition',
  outcome: 'the text was added',
  lookIn: 'the Google Doc',
  what: 'a way to add to a Google Doc',
}

const props = defineProps<{
  workspaceId: number
  documentId: number
  /** The document's latest export for its current version, whose files are the only ones that can be saved; null when there is none. */
  receipt: ExportReceiptResponse | null
  /** Changes not saved yet: connecting leaves the page, so it waits for them rather than have the browser ask. */
  unsavedWork: boolean
}>()

const {
  offered,
  offerUnknown,
  loadState,
  connection,
  actions,
  unread,
  busy,
  error,
  typesOffered: savingOffered,
  shown,
  visible,
  reconnectOffered,
  load,
  upsert,
  touch,
  focusOn,
  connect: connectFor,
  approve,
  ask,
  settle,
  approvable,
  noLongerOffered,
  checkAgain,
  proposalFailed,
} = useActionList({
  workspaceId: () => props.workspaceId,
  documentId: () => props.documentId,
  types: DRIVE_PANEL_TYPES,
  ...SAVE_WORDS,
  wordsFor: (action) => (action.type === 'GOOGLE_DOC_APPEND' ? APPEND_WORDS : SAVE_WORDS),
  heading: 'drive-save-heading',
  itemPrefix: 'drive-save-',
})

const kind = ref<DriveSaveKind | null>(null)

/** The files this export offers, as its download links do, and only kinds of save this Brownie makes. */
const kinds = computed<DriveSaveKind[]>(() => {
  const receipt = props.receipt
  if (receipt === null) return []
  const types = offered.value ?? []
  // A PDF form's export has no Word file at all, so neither a Word file nor a Google Doc made from one is offered.
  const wordOffered = receipt.docxArtifactId != null && (receipt.format !== 'PDF' || receipt.pdfArtifactId == null)
  const choices: DriveSaveKind[] = []
  if (types.includes('DRIVE_SAVE_FILE') && wordOffered) choices.push('WORD_FILE')
  if (types.includes('DRIVE_SAVE_FILE') && receipt.pdfArtifactId != null) choices.push('PDF_FILE')
  if (types.includes('DRIVE_SAVE_AS_GOOGLE_DOC') && wordOffered) choices.push('GOOGLE_DOC')
  return choices
})

watch(
  kinds,
  (choices) => {
    if (kind.value === null || !choices.includes(kind.value)) kind.value = choices[0] ?? null
  },
  { immediate: true },
)

const previews = computed(() => new Map(actions.value.map((action) => [action.id, driveSavePreview(action)])))
const appendPreviews = computed(() => new Map(actions.value.map((action) => [action.id, docAppendPreview(action)])))

function previewOf(action: ActionResponse): DriveSavePreview | null {
  return previews.value.get(action.id) ?? null
}

function appendPreviewOf(action: ActionResponse): DocAppendPreview | null {
  return appendPreviews.value.get(action.id) ?? null
}

/** Whether the page can state in full what approving this one does, which it must before offering to approve it. */
function stated(action: ActionResponse): boolean {
  return action.type === 'GOOGLE_DOC_APPEND' ? appendPreviewOf(action) !== null : previewOf(action) !== null
}

function sentenceOf(action: ActionResponse): string {
  return action.type === 'GOOGLE_DOC_APPEND' ? docAppendSentence(action, appendPreviewOf(action)) : driveSaveSentence(action, previewOf(action))
}

/** A Google Doc a save made, which this version's text can be added to: offered only with an export of this version. */
function appendable(action: ActionResponse): boolean {
  return (
    action.type === 'DRIVE_SAVE_AS_GOOGLE_DOC' &&
    action.state === 'SUCCEEDED' &&
    props.receipt !== null &&
    // Adding a version's text to a Google Doc is for Word forms; a PDF form's export, which has no Word file, is not one.
    props.receipt.docxArtifactId != null &&
    (offered.value ?? []).includes('GOOGLE_DOC_APPEND') &&
    connection.value?.state === 'ACTIVE'
  )
}

async function prepareAppend(target: ActionResponse): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  let prepared: ActionResponse | null = null
  try {
    prepared = await proposeDocAppend(props.workspaceId, props.documentId, target.id)
    touch(prepared.id)
    upsert(prepared)
  } catch (failure) {
    await proposalFailed(failure, APPEND_WORDS)
  } finally {
    busy.value = false
  }
  await focusOn(prepared ? `drive-save-${prepared.id}` : `drive-save-${target.id}`, 'drive-save-heading')
}

/** The save that made the Google Doc an addition goes into, when it is on this page. */
function savedFor(action: ActionResponse): ActionResponse | null {
  const preview = appendPreviewOf(action)
  return preview === null ? null : (actions.value.find((other) => other.id === preview.savedBy) ?? null)
}

/**
 * An addition of this very text to this very Doc that already happened, or
 * may have: approving another puts the text there a second time. For a save,
 * the addition of the current version's text to the Doc it made.
 */
function alreadyAdded(action: ActionResponse): ActionResponse | null {
  const preview = appendPreviewOf(action)
  if (action.type === 'GOOGLE_DOC_APPEND' && (preview === null || !withdrawable(action))) return null
  const savedBy = preview?.savedBy ?? action.id
  return (
    actions.value.find((other) => {
      const theirs = appendPreviewOf(other)
      return (
        other.id !== action.id &&
        madeOrMayHave(other) &&
        theirs !== null &&
        theirs.savedBy === savedBy &&
        (preview !== null ? theirs.text === preview.text : theirs.documentRevision === props.receipt?.revisionId)
      )
    }) ?? null
  )
}

onMounted(load)

function connect(): Promise<void> {
  return connectFor(props.unsavedWork, 'Google Drive for saving')
}

async function prepare(): Promise<void> {
  if (busy.value || kind.value === null) return
  busy.value = true
  error.value = null
  let prepared: ActionResponse | null = null
  try {
    prepared = await proposeDriveSave(props.workspaceId, props.documentId, kind.value)
    touch(prepared.id)
    upsert(prepared)
  } catch (failure) {
    await proposalFailed(failure, SAVE_WORDS)
  } finally {
    busy.value = false
  }
  // After a failure the form may have given way to the button that connects again, or to nothing at all.
  await (prepared ? focusOn(`drive-save-${prepared.id}`) : focusOn('drive-save-prepare', 'drive-save-connect', 'drive-save-heading'))
}

/**
 * An earlier change that made what it was for and has not been deleted since,
 * or that Google made although it did not read back as approved, or whose
 * outcome is unknown after it was sent.
 */
function madeOrMayHave(other: ActionResponse): boolean {
  return (
    (other.state === 'SUCCEEDED' && other.verification !== 'REMOVED_AFTERWARDS') ||
    (other.state === 'FAILED' && other.failure === 'READBACK_MISMATCH') ||
    (other.state === 'OUTCOME_UNKNOWN' && other.sent)
  )
}

/** A save of this very file that already succeeded, so approving another would make a second copy. */
function alreadySaved(action: ActionResponse): ActionResponse | null {
  const preview = previewOf(action)
  if (preview === null || (action.state !== 'AWAITING_APPROVAL' && action.state !== 'APPROVED')) return null
  return (
    actions.value.find((other) => {
      const theirs = previewOf(other)
      return (
        other.id !== action.id &&
        madeOrMayHave(other) &&
        other.type === action.type &&
        theirs !== null &&
        theirs.sha256 === preview.sha256 &&
        theirs.fileName === preview.fileName
      )
    }) ?? null
  )
}

function linkLabel(action: ActionResponse): string {
  return action.type === 'DRIVE_SAVE_FILE' ? 'Open in Google Drive' : 'Open in Google Docs'
}

/** How a save is told apart from another of the same file: its name and when it was prepared, to the second. */
function nameOf(action: ActionResponse): string {
  const prepared = exactTimeOf(action.createdAt)
  const when = prepared ? `, prepared at ${prepared}` : ''
  if (action.type === 'GOOGLE_DOC_APPEND') {
    const append = appendPreviewOf(action)
    return `the text for ${append ? append.docTitle : 'the Google Doc'}${when}`
  }
  const preview = previewOf(action)
  return `${preview ? preview.fileName : 'this file'}${when}`
}

/** What the region holding an addition's text is called: the Doc it goes into, and when it was prepared. */
function regionLabel(action: ActionResponse): string {
  const prepared = exactTimeOf(action.createdAt)
  return `The text to add to "${appendPreviewOf(action)?.docTitle ?? 'the Google Doc'}"${prepared ? `, prepared at ${prepared}` : ''}`
}

/** The button that settles a try Brownie cannot finish: saying one looked, or, where nothing was sent, closing it. */
function acknowledgeLabel(action: ActionResponse): string {
  if (!action.sent) return 'Close this try'
  return action.type === 'GOOGLE_DOC_APPEND' ? 'I looked at the Google Doc' : 'I looked in my Google Drive'
}

/** What the main button of a card says: to approve it, or, where approving did not get through, to try again. */
function approveLabel(action: ActionResponse): string {
  if (action.type === 'GOOGLE_DOC_APPEND') return action.state === 'APPROVED' ? 'Try adding again' : 'Add to the Google Doc'
  return action.state === 'APPROVED' ? 'Try saving again' : 'Save to Google Drive'
}

const connectHint = computed(() =>
  connection.value === null ? 'Saving to Google Drive is not connected.' : stateSentence(connection.value),
)

/** One card for each change on the list, with the chip that says where it stands. */
const cards = computed(() =>
  shown.value.map((action) => ({
    action,
    standing: standingOf(action, action.type === 'GOOGLE_DOC_APPEND' ? ADD_STANDING : SAVE_STANDING),
  })),
)

/** A save waiting for the person's say, which the form to prepare another gives way to. */
const waiting = computed(() => actions.value.some(withdrawable))

type Entry = 'loading' | 'check' | 'connect' | 'noExport' | 'noFile' | 'form'

/**
 * What the card for starting a save holds, in the order the checks run: waiting to hear what is offered, asking
 * again after a failure, connecting, saying why there is nothing to save yet, or the form. Nothing, once a save is
 * waiting for approval or where saving is not offered, so that card is simply not there.
 */
const entry = computed<Entry | null>(() => {
  if (loadState.value === 'loading') return 'loading'
  if (loadState.value === 'error' || offerUnknown.value) return 'check'
  if (loadState.value !== 'loaded' || !savingOffered.value) return null
  if (reconnectOffered.value) return 'connect'
  if (props.receipt === null) return 'noExport'
  if (kinds.value.length === 0) return 'noFile'
  return waiting.value ? null : 'form'
})

const connectionChip = computed(() => connectionStanding(connection.value))

function edgeClass(standing: Standing | null): string {
  return standing?.edge === 'approval' ? 'save-card--approval' : standing?.edge === 'attention' ? 'save-card--attention' : ''
}
</script>

<template>
  <section v-if="visible" class="drive-save" aria-labelledby="drive-save-heading">
    <p v-if="error" class="save-alert" role="alert">
      <AppIcon name="alert-circle" :size="16" />
      <span>{{ error }}</span>
    </p>

    <ul v-if="cards.length > 0" class="drive-save__list">
      <li v-for="({ action, standing }, index) in cards" :key="action.id" class="panel save-card" :class="edgeClass(standing)">
        <div class="save-card__head">
          <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="cloud" :size="18" /></span>
          <h3
            :id="index === 0 ? 'drive-save-heading' : undefined"
            :tabindex="index === 0 ? -1 : undefined"
            class="save-card__title"
            >{{ action.type === 'GOOGLE_DOC_APPEND' ? 'Add to a Google Doc' : 'Save to Google Drive' }}</h3
          >
          <span v-if="standing" class="tag" :class="standing.tone">
            <AppIcon v-if="standing.icon" :name="standing.icon" :size="14" />
            {{ standing.label }}
          </span>
        </div>

        <div class="save-card__summary">
          <p :id="`drive-save-${action.id}`" class="save-card__state" tabindex="-1">{{ sentenceOf(action) }}</p>
          <span v-if="timeOf(action.createdAt)" class="save-card__prepared">Prepared at {{ timeOf(action.createdAt) }}.</span>
        </div>

        <template v-if="action.state === 'AWAITING_APPROVAL'">
          <dl v-if="previewOf(action)" class="panel--quiet save-facts">
            <div class="save-facts__row">
              <dt>What</dt>
              <dd>{{ driveSaveWhat(previewOf(action)!) }}</dd>
            </div>
            <div class="save-facts__row">
              <dt>Where</dt>
              <dd>{{ driveSaveWhere(previewOf(action)!) }}</dd>
            </div>
            <div class="save-facts__row">
              <dt>Afterwards</dt>
              <dd>{{ driveSaveAfterwards(previewOf(action)!) }}</dd>
            </div>
          </dl>
          <dl v-else-if="appendPreviewOf(action)" class="panel--quiet save-facts">
            <div class="save-facts__row">
              <dt>What</dt>
              <dd>
                This text, {{ characterCount(appendPreviewOf(action)!.text) }} characters, as new paragraphs. They take the style of
                the Doc's last paragraph, such as a heading or a list, if it ends in one.
                <pre
                  class="drive-save__text"
                  role="region"
                  tabindex="0"
                  :aria-label="regionLabel(action)"
                  >{{ appendPreviewOf(action)!.text }}</pre
                >
              </dd>
            </div>
            <div class="save-facts__row">
              <dt>Where</dt>
              <dd>
                At the end of the first tab of "{{ appendPreviewOf(action)!.docTitle }}", the Google Doc Brownie saved<template
                  v-if="timeOf(savedFor(action)?.finishedAt)"
                >
                  at {{ timeOf(savedFor(action)?.finishedAt) }}</template
                >
                in the Google Drive of {{ appendPreviewOf(action)!.accountEmail ?? 'your Google account' }}.
                <template v-if="appendPreviewOf(action)!.shared">
                  This Google Doc is shared: everyone it is shared with will see the added text.</template
                >
                <a
                  v-if="savedFor(action) && actionLink(savedFor(action)!)"
                  class="save-facts__link"
                  :href="actionLink(savedFor(action)!) ?? undefined"
                  target="_blank"
                  rel="noopener noreferrer"
                  >Open that Google Doc<span class="visually-hidden"> ({{ nameOf(action) }}, opens in a new tab)</span></a
                >
              </dd>
            </div>
            <div class="save-facts__row">
              <dt>Only if unchanged</dt>
              <dd>
                Google adds it only if nobody has changed the Doc since this was prepared. If someone has, nothing is added, and
                you can prepare it again.
              </dd>
            </div>
            <div class="save-facts__row">
              <dt>Afterwards</dt>
              <dd>
                When you approve, Brownie sends it, then reads the Doc back to check the text is at the end of its first tab and the
                rest of that tab's text is as it was.
              </dd>
            </div>
          </dl>
          <p v-else class="save-note">
            Brownie cannot show everything this {{ action.type === 'GOOGLE_DOC_APPEND' ? 'addition' : 'save' }} would do, so it is
            not offered for approval here.
          </p>
        </template>
        <p v-if="alreadySaved(action)?.state === 'SUCCEEDED'" class="save-note save-note--warning">
          <AppIcon name="warning" :size="14" />
          <span>
            You already saved this exact file to Google Drive<template v-if="timeOf(alreadySaved(action)!.finishedAt)">
              at {{ timeOf(alreadySaved(action)!.finishedAt) }}</template
            >. Approving this one saves another copy.
          </span>
        </p>
        <p v-else-if="alreadySaved(action)" class="save-note save-note--warning">
          <AppIcon name="warning" :size="14" />
          <span>An earlier try may already have saved this exact file. Look in your Google Drive before approving this one.</span>
        </p>
        <template v-if="action.type === 'GOOGLE_DOC_APPEND' || appendable(action)">
          <p v-if="alreadyAdded(action)?.state === 'SUCCEEDED'" class="save-note save-note--warning">
            <AppIcon name="warning" :size="14" />
            <span>
              {{ action.type === 'GOOGLE_DOC_APPEND' ? 'This text' : "This version's text" }} was already added to this Google Doc<template
                v-if="timeOf(alreadyAdded(action)!.finishedAt)"
              >
                at {{ timeOf(alreadyAdded(action)!.finishedAt) }}</template
              >. Adding it again puts it there a second time.
            </span>
          </p>
          <p v-else-if="alreadyAdded(action)" class="save-note save-note--warning">
            <AppIcon name="warning" :size="14" />
            <span>
              An earlier try may already have added {{ action.type === 'GOOGLE_DOC_APPEND' ? 'this text' : "this version's text" }} to
              this Google Doc. Look at the end of the Doc before adding it again.
            </span>
          </p>
        </template>
        <p v-if="noLongerOffered(action)" class="save-note">
          This Brownie no longer {{ action.type === 'GOOGLE_DOC_APPEND' ? 'adds text to Google Docs' : 'makes this kind of save' }}, so
          this can only be cancelled.
        </p>
        <p v-if="mayAcknowledge(action) && action.state !== 'OUTCOME_UNKNOWN'" class="save-note">
          <template v-if="action.sent">
            That try stopped before Brownie heard how it ended. Check again to ask Google; if Brownie cannot ask, look for yourself
            and say so.
          </template>
          <template v-else>
            That try stopped before anything was sent to Google. Check again to try once more, or close it with the button below.
          </template>
        </p>

        <p v-if="action.state === 'SUCCEEDED' && conversionSentence(action.conversionCheck)" class="save-note">
          {{ conversionSentence(action.conversionCheck) }}
        </p>
        <p v-if="unread.has(action.id)" class="save-note">
          Brownie could not be asked where this {{ action.type === 'GOOGLE_DOC_APPEND' ? 'addition' : 'save' }} stands.
        </p>

        <div class="save-card__actions">
          <a
            v-if="actionLink(action)"
            class="button button--secondary button--small"
            :href="actionLink(action) ?? undefined"
            target="_blank"
            rel="noopener noreferrer"
          >
            <AppIcon name="external" :size="16" />
            <span>{{ linkLabel(action) }}<span class="visually-hidden"> ({{ nameOf(action) }}, opens in a new tab)</span></span>
          </a>
          <button
            v-if="approvable(action, stated(action))"
            type="button"
            class="button button--primary button--small"
            :aria-disabled="busy"
            @click="approve(action)"
          >
            <AppIcon :name="action.state === 'APPROVED' ? 'refresh' : action.type === 'GOOGLE_DOC_APPEND' ? 'plus' : 'cloud'" :size="16" />
            <span>{{ approveLabel(action) }} <span class="visually-hidden">{{ nameOf(action) }}</span></span>
          </button>
          <button v-if="appendable(action)" type="button" class="button button--secondary button--small" :aria-disabled="busy" @click="prepareAppend(action)">
            <AppIcon name="plus" :size="16" />
            <span>Add this version's text to this Google Doc <span class="visually-hidden">{{ nameOf(action) }}</span></span>
          </button>
          <button
            v-if="withdrawable(action)"
            type="button"
            class="button button--quiet button--small"
            :aria-disabled="busy"
            @click="settle(action, 'cancel')"
          >
            <span>Cancel <span class="visually-hidden">{{ action.type === 'GOOGLE_DOC_APPEND' ? 'adding' : 'saving' }} {{ nameOf(action) }}</span></span>
          </button>
          <button
            v-if="action.state === 'OUTCOME_UNKNOWN' || action.state === 'EXECUTING' || action.state === 'RECONCILING'"
            type="button"
            class="button button--secondary button--small"
            :aria-disabled="busy"
            @click="ask(action, 'reconcile')"
          >
            <AppIcon name="refresh" :size="16" />
            <span
              >{{ action.state === 'OUTCOME_UNKNOWN' ? 'Ask Google what happened' : 'Check again' }}
              <span class="visually-hidden">to {{ nameOf(action) }}</span></span
            >
          </button>
          <button
            v-if="mayAcknowledge(action)"
            type="button"
            class="button button--secondary button--small"
            :aria-disabled="busy"
            @click="settle(action, 'acknowledge')"
          >
            <span
              >{{ acknowledgeLabel(action) }}
              <span class="visually-hidden">for {{ nameOf(action) }}</span></span
            >
          </button>
          <button
            v-if="unread.has(action.id)"
            type="button"
            class="button button--secondary button--small"
            :aria-disabled="busy"
            @click="ask(action, 'read')"
          >
            <AppIcon name="refresh" :size="16" />
            <span
              >Check again
              <span class="visually-hidden"
                >where {{ action.type === 'GOOGLE_DOC_APPEND' ? 'adding' : 'saving' }} {{ nameOf(action) }} stands</span
              ></span
            >
          </button>
        </div>
      </li>
    </ul>

    <div v-if="entry !== null" class="panel save-card" :class="{ 'save-card--attention': entry === 'connect' && connectionChip.edge === 'attention' }">
      <div class="save-card__head">
        <span class="doc-tile doc-tile--medium" aria-hidden="true"><AppIcon name="cloud" :size="18" /></span>
        <h3
          :id="cards.length === 0 ? 'drive-save-heading' : undefined"
          :tabindex="cards.length === 0 ? -1 : undefined"
          class="save-card__title"
          >Save to Google Drive</h3
        >
        <span v-if="entry === 'connect'" class="tag" :class="connectionChip.tone">
          <AppIcon v-if="connectionChip.icon" :name="connectionChip.icon" :size="14" />
          {{ connectionChip.label }}
        </span>
      </div>

      <p v-if="entry === 'loading'" class="save-card__summary" aria-live="polite">Checking your Google Drive connection…</p>

      <template v-else-if="entry === 'check'">
        <p v-if="offerUnknown" class="save-card__summary">
          Brownie could not check whether saving to Google Drive is offered here, so only earlier saves are shown.
        </p>
        <div class="save-card__actions">
          <button type="button" class="button button--secondary button--small" @click="checkAgain">
            <AppIcon name="refresh" :size="16" />
            <span>Check again<span class="visually-hidden"> for saving to Google Drive</span></span>
          </button>
        </div>
      </template>

      <template v-else-if="entry === 'connect'">
        <p class="save-card__summary">{{ connectHint }}</p>
        <p class="save-note">
          <span>
            Saving uses a Google connection of its own, which Brownie uses only for saving a file you approve and for adding text you
            approve to a Google Doc it saved.
            <RouterLink to="/connections">More about connections</RouterLink>
          </span>
        </p>
        <div class="save-card__actions">
          <button id="drive-save-connect" type="button" class="button button--primary button--small" :disabled="busy" @click="connect">
            <AppIcon name="cloud" :size="16" />
            {{ connection?.state === 'RECONNECT_REQUIRED' ? 'Connect Google Drive for saving again' : 'Connect Google Drive for saving' }}
          </button>
        </div>
      </template>

      <p v-else-if="entry === 'noExport'" class="save-card__summary">
        Brownie saves the exported file. Once this version is approved and exported, it can be saved to Google Drive.
      </p>

      <p v-else-if="entry === 'noFile'" class="save-card__summary">The latest export has no file that can be saved to Google Drive here.</p>

      <template v-else-if="entry === 'form'">
        <p class="save-card__summary">You see exactly what would be saved first. Nothing is saved until you approve it.</p>
        <form class="drive-save__form" @submit.prevent="prepare">
          <fieldset class="drive-save__choices">
            <legend class="field-label">What to save</legend>
            <label v-for="choice in kinds" :key="choice" class="drive-save__choice">
              <input v-model="kind" type="radio" name="drive-save-kind" :value="choice" />
              <span>{{ DRIVE_SAVE_CHOICES[choice] }}</span>
            </label>
          </fieldset>
          <!-- Not disabled while preparing: that would drop the focus it holds. A second press is ignored instead. -->
          <button id="drive-save-prepare" type="submit" class="button button--secondary button--small" :aria-disabled="busy">
            <AppIcon name="cloud" :size="16" />
            Prepare the save
          </button>
        </form>
      </template>
    </div>
  </section>
</template>

<style scoped>
/* One card for each save and one for starting another, a rem apart as the other blocks of the dialog are. */
.drive-save {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--space-4);
}

.drive-save p {
  margin: 0;
}

.drive-save__list {
  display: grid;
  gap: var(--space-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

/*
 * A save or the form to start one, drawn as the cards of connected accounts are: a tile, what it is, a chip for where
 * it stands, a sentence, and the one thing to do. A save waiting for approval has the action colour for its edge, a
 * pixel and a half thick, and one that needs a look has honey, so either stands out from the plain hairline of the rest.
 */
.save-card {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1rem;
  /* The head and the table lay themselves out by the card's width, which depends on the dialog and not on the window. */
  container-type: inline-size;
}

.save-card--approval {
  border-color: var(--color-cocoa);
  box-shadow: inset 0 0 0 0.5px var(--color-cocoa);
}

.save-card--attention {
  border-color: var(--color-honey);
  box-shadow: inset 0 0 0 0.5px var(--color-honey);
}

/* The tile, what it is and the chip on one line; a name that is long wraps inside its own column and never under the chip. */
.save-card__head {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-2) 0.75rem;
}

.save-card__title {
  min-inline-size: 0;
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

/* The chip carries its icon a little smaller, with a little more air, than a chip that only labels. */
.save-card__head > .tag {
  justify-self: end;
  gap: 0.3125rem;
}

.save-card__head > .tag > svg {
  inline-size: 0.875rem;
  block-size: 0.875rem;
}

/* So little room that the name would be squeezed to a few letters: the chip goes on a line of its own, under the name. */
@container (max-width: 24rem) {
  .save-card__head {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .save-card__head > .tag {
    grid-column: 2;
    justify-self: start;
  }
}

/* The sentence and, run on after it in quieter type, when the save was prepared, so the pair costs no line of its own. */
.save-card__summary {
  font-size: var(--font-size-sm);
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.save-card__state {
  display: inline;
}

.save-card__prepared {
  margin-inline-start: 0.25rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  /* All of it or none of it on the line, so a time is never split from the words before it. */
  white-space: nowrap;
}

/*
 * What approving does, as label and value rows in an inset panel separated by hairlines. Narrow, the label sits above
 * its value; wide enough, the rows share two columns so the values line up, however long the longest label is.
 */
.save-facts {
  margin: 0;
  padding: 0;
}

.save-facts__row {
  display: grid;
  /* Only the space between a label and its value when they are stacked: side by side the table's own column gap holds them apart. */
  row-gap: var(--space-1);
  padding: 0.5625rem 0.75rem;
}

.save-facts__row + .save-facts__row {
  border-block-start: 1px solid var(--color-hairline);
}

.save-facts dt {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  /* Tighter than the value beside it, so the label's first line sits a little above the value's, as a caption does. */
  line-height: 1.2;
}

.save-facts dd {
  margin: 0;
  font-size: var(--font-size-xs);
  line-height: 1.45;
  overflow-wrap: anywhere;
}

@container (min-width: 25rem) {
  .save-facts {
    display: grid;
    /* The first track also holds the row's own side padding, since a row takes its columns from here. */
    grid-template-columns: minmax(5.625rem, max-content) minmax(0, 1fr);
    column-gap: 0.75rem;
  }

  .save-facts__row {
    grid-template-columns: subgrid;
    grid-column: 1 / -1;
  }
}

/* On its own line under the sentence it follows, which ends in a full stop and would otherwise run into it. */
.save-facts__link {
  display: block;
  inline-size: fit-content;
  margin-block-start: var(--space-1);
}

.drive-save__text {
  max-block-size: 12rem;
  overflow: auto;
  margin: var(--space-2) 0 0;
  padding: var(--space-2) 0.625rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  color: var(--color-text);
}

/* A line of small print about this save; the ones that warn before a second copy is made have a honey panel and an icon. */
.save-note {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.45;
}

.save-note > svg {
  flex: none;
  margin-block-start: 0.0625rem;
}

.save-note--warning {
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-md);
  background: var(--color-honey-soft);
  color: var(--color-text);
}

.save-note--warning > svg {
  color: var(--color-honey-ink);
}

.save-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.625rem;
}

/* A card with nothing left to do has no buttons, and then no row for them either, so no gap is left under its last line. */
.save-card__actions:empty {
  display: none;
}

/* Something went wrong with a request: a wash of the error colour, an icon and the words in the main text colour, so it reads without the colour. */
.save-alert {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.625rem 0.75rem;
  border-radius: var(--radius-md);
  background: var(--color-error-wash);
  font-size: var(--font-size-sm);
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.save-alert > svg {
  flex: none;
  margin-block-start: 0.125rem;
  color: var(--color-error);
}

.drive-save__form {
  display: grid;
  gap: 0.75rem;
  justify-items: start;
}

.drive-save__choices {
  display: grid;
  gap: var(--space-2);
  inline-size: 100%;
  min-inline-size: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.drive-save__choices legend {
  padding: 0;
  margin-block-end: var(--space-2);
}

/* Each choice is a row to press anywhere on; the chosen one has the cocoa edge and wash the format cards have, thickened inside so nothing moves. */
.drive-save__choice {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-block-size: 2.5rem;
  padding: 0.5rem 0.875rem;
  border: 1px solid var(--color-outline);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  font-size: var(--font-size-sm);
  line-height: 1.4;
  cursor: pointer;
  transition:
    background-color var(--motion-fast) var(--motion-ease),
    border-color var(--motion-fast) var(--motion-ease),
    box-shadow var(--motion-fast) var(--motion-ease);
}

.drive-save__choice:hover {
  background: var(--color-paper-soft);
}

.drive-save__choice:has(input:checked) {
  border-color: var(--color-cocoa);
  background: var(--color-cocoa-soft);
  box-shadow: inset 0 0 0 1px var(--color-cocoa);
}

.drive-save__choice:has(input:focus-visible) {
  box-shadow: var(--focus-ring);
}

.drive-save__choice:has(input:checked:focus-visible) {
  box-shadow:
    inset 0 0 0 1px var(--color-cocoa),
    var(--focus-ring);
}

.drive-save__choice input {
  flex: none;
  inline-size: 1.125rem;
  block-size: 1.125rem;
  margin: 0;
  accent-color: var(--color-cocoa);
  cursor: pointer;
}

/* The row carries the focus ring, so the small circle draws none of its own. */
.drive-save__choice input:focus-visible {
  box-shadow: none;
  outline: none;
}

/* A display that forces its own colours drops backgrounds and shadows, which would erase which choice is made. */
@media (forced-colors: active) {
  .drive-save__choice:has(input:checked) {
    outline: 2px solid Highlight;
    outline-offset: -2px;
  }
}
</style>
