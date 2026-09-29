<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
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
  const wordOffered = receipt.format !== 'PDF' || receipt.pdfArtifactId == null
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

const connectHint = computed(() =>
  connection.value === null ? 'Saving to Google Drive is not connected.' : stateSentence(connection.value),
)
</script>

<template>
  <section v-if="visible" class="drive-save" aria-labelledby="drive-save-heading">
    <h3 id="drive-save-heading" class="drive-save__heading" tabindex="-1">Save to Google Drive</h3>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-if="offerUnknown" class="field-hint">
      Brownie could not check whether saving to Google Drive is offered here, so only earlier saves are shown.
    </p>

    <p v-if="loadState === 'loading'" class="field-hint" aria-live="polite">Checking your Google Drive connection…</p>
    <button v-else-if="loadState === 'error' || offerUnknown" type="button" class="button button--secondary" @click="checkAgain">
      Check again<span class="visually-hidden"> for saving to Google Drive</span>
    </button>

    <template v-else-if="loadState === 'loaded' && savingOffered">
      <template v-if="reconnectOffered">
        <p class="field-hint">
          {{ connectHint }}
          Saving uses a Google connection of its own, which Brownie uses only for saving a file you approve and for adding text you
          approve to a Google Doc it saved.
          <RouterLink to="/connections">More about connections</RouterLink>
        </p>
        <button id="drive-save-connect" type="button" class="button button--primary" :disabled="busy" @click="connect">
          {{ connection?.state === 'RECONNECT_REQUIRED' ? 'Connect Google Drive for saving again' : 'Connect Google Drive for saving' }}
        </button>
      </template>

      <p v-else-if="receipt === null" class="field-hint">
        Brownie saves the exported file. Validate, approve and export this version of the document to save it to Google Drive.
      </p>

      <p v-else-if="kinds.length === 0" class="field-hint">The latest export has no file that can be saved to Google Drive here.</p>

      <form v-else class="drive-save__form" @submit.prevent="prepare">
        <fieldset class="drive-save__choices">
          <legend class="field-label">What to save</legend>
          <label v-for="choice in kinds" :key="choice" class="drive-save__choice">
            <input v-model="kind" type="radio" name="drive-save-kind" :value="choice" />
            {{ DRIVE_SAVE_CHOICES[choice] }}
          </label>
        </fieldset>
        <!-- Not disabled while preparing: that would drop the focus it holds. A second press is ignored instead. -->
        <button id="drive-save-prepare" type="submit" class="button button--secondary" :aria-disabled="busy">
          Prepare the save
        </button>
        <p class="field-hint">You see exactly what would be saved first. Nothing is saved until you approve it.</p>
      </form>
    </template>

    <ul v-if="shown.length > 0" class="drive-save__list">
      <li v-for="action in shown" :key="action.id" class="drive-save__item">
        <p :id="`drive-save-${action.id}`" class="drive-save__state" tabindex="-1">
          {{ sentenceOf(action) }}
        </p>
        <p v-if="timeOf(action.createdAt)" class="field-hint">Prepared at {{ timeOf(action.createdAt) }}.</p>

        <template v-if="action.state === 'AWAITING_APPROVAL'">
          <dl v-if="previewOf(action)" class="drive-save__facts">
            <dt>What</dt>
            <dd>{{ driveSaveWhat(previewOf(action)!) }}</dd>
            <dt>Where</dt>
            <dd>{{ driveSaveWhere(previewOf(action)!) }}</dd>
            <dt>Afterwards</dt>
            <dd>{{ driveSaveAfterwards(previewOf(action)!) }}</dd>
          </dl>
          <dl v-else-if="appendPreviewOf(action)" class="drive-save__facts">
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
              <a v-if="savedFor(action) && actionLink(savedFor(action)!)" :href="actionLink(savedFor(action)!) ?? undefined" target="_blank" rel="noopener noreferrer"
                >Open that Google Doc<span class="visually-hidden"> ({{ nameOf(action) }}, opens in a new tab)</span></a
              >
            </dd>
            <dt>Only if unchanged</dt>
            <dd>
              Google adds it only if nobody has changed the Doc since this was prepared. If someone has, nothing is added, and
              you can prepare it again.
            </dd>
            <dt>Afterwards</dt>
            <dd>
              When you approve, Brownie sends it, then reads the Doc back to check the text is at the end of its first tab and the
              rest of that tab's text is as it was.
            </dd>
          </dl>
          <p v-else class="field-hint">
            Brownie cannot show everything this {{ action.type === 'GOOGLE_DOC_APPEND' ? 'addition' : 'save' }} would do, so it is
            not offered for approval here.
          </p>
        </template>
        <p v-if="alreadySaved(action)?.state === 'SUCCEEDED'" class="field-hint">
          You already saved this exact file to Google Drive<template v-if="timeOf(alreadySaved(action)!.finishedAt)">
            at {{ timeOf(alreadySaved(action)!.finishedAt) }}</template
          >. Approving this one saves another copy.
        </p>
        <p v-else-if="alreadySaved(action)" class="field-hint">
          An earlier try may already have saved this exact file. Look in your Google Drive before approving this one.
        </p>
        <template v-if="action.type === 'GOOGLE_DOC_APPEND' || appendable(action)">
          <p v-if="alreadyAdded(action)?.state === 'SUCCEEDED'" class="field-hint">
            {{ action.type === 'GOOGLE_DOC_APPEND' ? 'This text' : "This version's text" }} was already added to this Google Doc<template
              v-if="timeOf(alreadyAdded(action)!.finishedAt)"
            >
              at {{ timeOf(alreadyAdded(action)!.finishedAt) }}</template
            >. Adding it again puts it there a second time.
          </p>
          <p v-else-if="alreadyAdded(action)" class="field-hint">
            An earlier try may already have added {{ action.type === 'GOOGLE_DOC_APPEND' ? 'this text' : "this version's text" }} to
            this Google Doc. Look at the end of the Doc before adding it again.
          </p>
        </template>
        <p v-if="noLongerOffered(action)" class="field-hint">
          This Brownie no longer {{ action.type === 'GOOGLE_DOC_APPEND' ? 'adds text to Google Docs' : 'makes this kind of save' }}, so
          this can only be cancelled.
        </p>
        <p v-if="mayAcknowledge(action) && action.state !== 'OUTCOME_UNKNOWN'" class="field-hint">
          <template v-if="action.sent">
            That try stopped before Brownie heard how it ended. Check again to ask Google; if Brownie cannot ask, look for yourself
            and say so.
          </template>
          <template v-else>
            That try stopped before anything was sent to Google. Check again to try once more, or close it with the button below.
          </template>
        </p>

        <p v-if="action.state === 'SUCCEEDED' && conversionSentence(action.conversionCheck)" class="field-hint">
          {{ conversionSentence(action.conversionCheck) }}
        </p>
        <p v-if="actionLink(action)">
          <a :href="actionLink(action) ?? undefined" target="_blank" rel="noopener noreferrer"
            >{{ linkLabel(action) }}<span class="visually-hidden"> ({{ nameOf(action) }}, opens in a new tab)</span></a
          >
        </p>
        <p v-if="unread.has(action.id)" class="field-hint">
          Brownie could not be asked where this {{ action.type === 'GOOGLE_DOC_APPEND' ? 'addition' : 'save' }} stands.
        </p>

        <div class="drive-save__actions">
          <button v-if="approvable(action, stated(action))" type="button" class="button button--primary" :aria-disabled="busy" @click="approve(action)">
            <span
              >{{
                action.type === 'GOOGLE_DOC_APPEND'
                  ? action.state === 'APPROVED'
                    ? 'Try adding again'
                    : 'Add to the Google Doc'
                  : action.state === 'APPROVED'
                    ? 'Try saving again'
                    : 'Save to Google Drive'
              }}
              <span class="visually-hidden">{{ nameOf(action) }}</span></span
            >
          </button>
          <button v-if="appendable(action)" type="button" class="button button--secondary" :aria-disabled="busy" @click="prepareAppend(action)">
            <span>Add this version's text to this Google Doc <span class="visually-hidden">{{ nameOf(action) }}</span></span>
          </button>
          <button
            v-if="withdrawable(action)"
            type="button"
            class="button button--secondary"
            :aria-disabled="busy"
            @click="settle(action, 'cancel')"
          >
            <span>Cancel <span class="visually-hidden">{{ action.type === 'GOOGLE_DOC_APPEND' ? 'adding' : 'saving' }} {{ nameOf(action) }}</span></span>
          </button>
          <button
            v-if="action.state === 'OUTCOME_UNKNOWN' || action.state === 'EXECUTING' || action.state === 'RECONCILING'"
            type="button"
            class="button button--secondary"
            :aria-disabled="busy"
            @click="ask(action, 'reconcile')"
          >
            <span
              >{{ action.state === 'OUTCOME_UNKNOWN' ? 'Ask Google what happened' : 'Check again' }}
              <span class="visually-hidden">to {{ nameOf(action) }}</span></span
            >
          </button>
          <button
            v-if="mayAcknowledge(action)"
            type="button"
            class="button button--secondary"
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
            class="button button--secondary"
            :aria-disabled="busy"
            @click="ask(action, 'read')"
          >
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
  </section>
</template>

<style scoped>
.drive-save {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  margin-block-start: var(--space-4);
}

.drive-save p {
  margin: 0;
}

.drive-save__heading {
  margin: 0;
  font-size: var(--font-size-base);
}

.drive-save__form {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}

.drive-save__choices {
  margin: 0;
  padding: 0;
  border: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.drive-save__choice {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.drive-save__list {
  list-style: none;
  inline-size: 100%;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.drive-save__item {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.drive-save__state {
  overflow-wrap: anywhere;
}

.drive-save__facts {
  margin: 0;
  display: grid;
  gap: var(--space-1);
}

.drive-save__facts dt {
  font-weight: 500;
}

.drive-save__facts dd {
  margin: 0 0 var(--space-1);
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.drive-save__text {
  max-block-size: 12rem;
  overflow: auto;
  margin: var(--space-1) 0 0;
  padding: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  color: var(--color-text);
}

.drive-save__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
</style>
