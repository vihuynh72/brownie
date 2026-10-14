<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import {
  ApiRequestError,
  issueMcpPersonalToken,
  listMcpGrants,
  revokeMcpGrant,
  type McpGrantResponse,
  type McpScope,
} from '@/api/client'
import { brownieSaysNotThere, describeCommonFailure } from '@/api/failures'
import { mcpGrantKindWords, mcpScopeList, MCP_SCOPE_WORDS } from '@/mcp/words'

/**
 * The chat apps a person has let into this workspace, and the way to let one
 * in: the server address to give it, the personal token a developer tool
 * pastes, and the grants already standing, with their scopes in words and a
 * Revoke that asks first.
 *
 * A token leaves the server exactly once, in the answer to Create, so the
 * dialog that shows it is the only place it will ever appear: it is kept in
 * one ref, dropped when the dialog closes, and nothing about it is written
 * anywhere a reload could find.
 */
const props = defineProps<{
  workspaceId: number
  /** Whether this deployment serves MCP at all; the panel is nothing without it. */
  enabled: boolean
  /** The MCP endpoint's public address, straight from the deployment's capabilities. */
  serverUrl: string | null | undefined
}>()

const emit = defineEmits<{
  /** Something the page's own status line should say once, e.g. a successful revocation. */
  outcome: [payload: { tone: 'success' | 'failure'; text: string }]
}>()

const dayFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })
function day(value: string): string {
  return dayFormatter.format(new Date(value))
}

// ---- The grants ------------------------------------------------------------------------

const grants = ref<McpGrantResponse[]>([])
const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const loadError = ref<string | null>(null)

/** What the list shows: every grant whose access still stands. A revoked one is gone from it at once. */
const standingGrants = computed(() => grants.value.filter((grant) => grant.revokedAt == null))

async function load(): Promise<void> {
  if (!props.enabled) return
  loadState.value = 'loading'
  loadError.value = null
  try {
    grants.value = await listMcpGrants(props.workspaceId)
    loadState.value = 'loaded'
  } catch (error) {
    loadState.value = 'error'
    loadError.value =
      describeCommonFailure(error, 'chat apps') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      'Brownie could not load your chat apps. Try again.'
  }
}

onMounted(load)
watch(() => props.workspaceId, load)
watch(
  () => props.enabled,
  (enabled) => {
    if (enabled) void load()
  },
)

// ---- Making a token --------------------------------------------------------------------

const SCOPE_CHOICES: McpScope[] = ['read', 'fill', 'export']
const tokenName = ref('')
const pickedScopes = ref<Record<McpScope, boolean>>({ read: true, fill: true, export: true })
const creating = ref(false)
const createError = ref<string | null>(null)
const createButton = ref<HTMLButtonElement | null>(null)

const anyScopePicked = computed(() => SCOPE_CHOICES.some((scope) => pickedScopes.value[scope]))
const nameId = useId()

async function createToken(): Promise<void> {
  const name = tokenName.value.trim()
  if (creating.value) return
  if (name === '') {
    createError.value = 'Name the token, so you can tell it apart later.'
    await nextTick()
    document.getElementById(nameId)?.focus()
    return
  }
  if (name.length > 80) {
    createError.value = 'That name is too long; 80 letters at most.'
    await nextTick()
    document.getElementById(nameId)?.focus()
    return
  }
  const scopes = SCOPE_CHOICES.filter((scope) => pickedScopes.value[scope])
  if (scopes.length === 0) {
    createError.value = 'Choose at least one thing the token may do.'
    return
  }
  creating.value = true
  createError.value = null
  try {
    const issued = await issueMcpPersonalToken(props.workspaceId, name, scopes)
    issuedToken.value = issued.token
    tokenName.value = ''
    pickedScopes.value = { read: true, fill: true, export: true }
    openTokenDialog()
    void load()
  } catch (error) {
    createError.value =
      describeCommonFailure(error, 'chat apps') ??
      (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
      'Could not create the token. Try again.'
  } finally {
    creating.value = false
  }
}

// ---- The one-time token dialog ----------------------------------------------------------

const issuedToken = ref<string | null>(null)
const tokenDialog = ref<HTMLDialogElement | null>(null)
const dialogStatus = ref('')

const claudeCodeLine = computed(
  () => `claude mcp add --transport http brownie ${props.serverUrl ?? ''} --header "Authorization: Bearer ${issuedToken.value ?? ''}"`,
)
const vsCodeJson = computed(
  () =>
    `{"servers":{"brownie":{"type":"http","url":"${props.serverUrl ?? ''}","headers":{"Authorization":"Bearer ${issuedToken.value ?? ''}"}}}}`,
)
const cursorJson = computed(
  () =>
    `{"mcpServers":{"brownie":{"url":"${props.serverUrl ?? ''}","headers":{"Authorization":"Bearer ${issuedToken.value ?? ''}"}}}}`,
)

function openTokenDialog(): void {
  void nextTick(() => {
    const element = tokenDialog.value
    if (element === null) return
    if (typeof element.showModal === 'function') {
      element.showModal()
    } else {
      element.setAttribute('open', '')
      dialogStatus.value = ''
    }
  })
}

/** Closing is the token's end: it leaves the page's memory with the dialog. */
function closeTokenDialog(): void {
  const element = tokenDialog.value
  if (element !== null) {
    if (typeof element.close === 'function' && element.open) element.close()
    element.removeAttribute('open')
  }
  issuedToken.value = null
  dialogStatus.value = ''
  void nextTick(() => createButton.value?.focus())
}

async function copyText(text: string, announcement: string): Promise<void> {
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(text)
    } else {
      window.document.execCommand('copy')
    }
    dialogStatus.value = announcement
  } catch {
    dialogStatus.value = 'The copy did not work; select the line and copy it yourself.'
  }
}

const urlCopied = ref(false)
async function copyServerUrl(): Promise<void> {
  if (props.serverUrl == null) return
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(props.serverUrl)
    } else {
      window.document.execCommand('copy')
    }
    urlCopied.value = true
    setTimeout(() => {
      urlCopied.value = false
    }, 2000)
  } catch {
    urlCopied.value = false
  }
}

// ---- Revoking a grant -------------------------------------------------------------------

const revokingId = ref<number | null>(null)
const confirmingRevokeId = ref<number | null>(null)
const revokeError = ref<string | null>(null)

function askToRevoke(grant: McpGrantResponse): void {
  confirmingRevokeId.value = grant.id
  revokeError.value = null
  void nextTick(() => document.getElementById(`chat-apps-revoke-confirm-${grant.id}`)?.focus())
}

function keepGrant(grant: McpGrantResponse): void {
  confirmingRevokeId.value = null
  revokeError.value = null
  void nextTick(() => document.getElementById(`chat-apps-revoke-${grant.id}`)?.focus())
}

async function revoke(grant: McpGrantResponse): Promise<void> {
  if (revokingId.value !== null) return
  revokingId.value = grant.id
  revokeError.value = null
  try {
    await revokeMcpGrant(props.workspaceId, grant.id)
    confirmingRevokeId.value = null
    emit('outcome', { tone: 'success', text: `${grant.name} no longer has access to Brownie.` })
    await load()
  } catch (error) {
    confirmingRevokeId.value = null
    if (brownieSaysNotThere(error)) {
      // Already revoked elsewhere: what was asked is done; read the list again to show it.
      emit('outcome', { tone: 'success', text: `${grant.name} no longer has access to Brownie.` })
      await load()
      return
    }
    revokeError.value =
      `${grant.name} still has access. ` +
      (describeCommonFailure(error, 'a way to revoke it') ??
        (error instanceof ApiRequestError ? error.problem?.detail : undefined) ??
        'Try again.')
    await nextTick()
    document.getElementById(`chat-apps-revoke-${grant.id}`)?.focus()
  } finally {
    revokingId.value = null
  }
}
</script>

<template>
  <section v-if="enabled" class="panel chat-apps" aria-labelledby="connections-chat-apps">
    <div class="chat-apps__head">
      <span class="doc-tile" aria-hidden="true"><AppIcon name="chat" :size="22" /></span>
      <h2 id="connections-chat-apps" class="chat-apps__title">Chat apps</h2>
    </div>

    <p class="chat-apps__lede">
      A chat app can read your templates and documents, propose fills and export them -- only what you allow, and
      nothing is filled or exported until you confirm the exact proposal it was shown.
    </p>

    <div v-if="loadState === 'loading'" class="chat-apps__loading" aria-live="polite">
      <span class="activity-indicator" aria-hidden="true"></span>
      <span>Loading your chat apps…</span>
    </div>
    <p v-else-if="loadState === 'error'" class="field-error" role="alert">{{ loadError }}</p>

    <template v-else>
      <div class="chat-apps__url">
        <label class="field-label" :for="`${nameId}-url`">Brownie's address for chat apps</label>
        <div class="chat-apps__url-row">
          <input :id="`${nameId}-url`" class="text-field" type="text" readonly :value="serverUrl ?? ''" />
          <button type="button" class="button button--secondary button--small" :disabled="serverUrl == null" @click="copyServerUrl">
            <AppIcon name="copy" :size="16" />
            {{ urlCopied ? 'Copied' : 'Copy' }}
          </button>
        </div>
      </div>

      <div class="chat-apps__howtos">
        <p class="field-hint">
          Developer tools (Claude Code, VS Code, Cursor): create a token below and paste it with the URL.
        </p>
        <p class="field-hint">
          Claude or ChatGPT: add Brownie's URL as a custom connector (Claude) or an app in developer mode (ChatGPT);
          you will sign in to Brownie and choose what it may do.
        </p>
      </div>

      <form class="chat-apps__create" @submit.prevent="createToken">
        <label class="field-label" :for="nameId">A name for the token</label>
        <input
          :id="nameId"
          v-model="tokenName"
          class="text-field"
          type="text"
          maxlength="80"
          placeholder="Claude Code on my laptop"
          :disabled="creating"
        />
        <fieldset class="field chat-apps__scopes">
          <legend class="field-label">What it may do</legend>
          <label v-for="scope in SCOPE_CHOICES" :key="scope" class="field--inline chat-apps__check">
            <input v-model="pickedScopes[scope]" type="checkbox" :disabled="creating" />
            <span>{{ MCP_SCOPE_WORDS[scope] }}</span>
          </label>
        </fieldset>
        <p v-if="!anyScopePicked" class="field-hint">Choose at least one thing the token may do.</p>
        <p v-if="createError" class="field-error" role="alert">{{ createError }}</p>
        <div class="chat-apps__actions">
          <button
            ref="createButton"
            type="submit"
            class="button button--primary button--small"
            :disabled="creating || !anyScopePicked"
          >
            <AppIcon name="key" :size="16" />
            {{ creating ? 'Creating…' : 'Create a token' }}
          </button>
        </div>
      </form>

      <div v-if="standingGrants.length > 0" class="chat-apps__grants">
        <h3 class="chat-apps__grants-title">Tokens and connected apps</h3>
        <ul class="chat-apps__grant-list">
          <li v-for="grant in standingGrants" :key="grant.id" class="chat-apps__grant">
            <div class="chat-apps__grant-main">
              <span class="chat-apps__grant-name">{{ grant.name }}</span>
              <span class="chat-apps__grant-kind">{{ mcpGrantKindWords(grant.kind) }}</span>
            </div>
            <p class="chat-apps__grant-facts">
              Since {{ day(grant.createdAt) }} · Last used {{ grant.lastUsedAt ? day(grant.lastUsedAt) : 'Never' }}
            </p>
            <p class="chat-apps__grant-scopes">{{ mcpScopeList(grant.scopes) }}</p>
            <div v-if="confirmingRevokeId === grant.id" class="chat-apps__revoke-confirm" role="group" :aria-label="`Revoke ${grant.name}`">
              <p :id="`chat-apps-revoke-question-${grant.id}`" class="chat-apps__revoke-question" tabindex="-1">
                {{ grant.name }} loses access to Brownie at once.
              </p>
              <div class="chat-apps__actions">
                <button
                  :id="`chat-apps-revoke-confirm-${grant.id}`"
                  type="button"
                  class="button button--danger button--small"
                  :aria-describedby="`chat-apps-revoke-question-${grant.id}`"
                  :disabled="revokingId !== null"
                  @click="revoke(grant)"
                >
                  {{ revokingId === grant.id ? 'Revoking…' : 'Yes, revoke' }}
                </button>
                <button
                  type="button"
                  class="button button--secondary button--small"
                  :disabled="revokingId !== null"
                  @click="keepGrant(grant)"
                >
                  Keep it
                </button>
              </div>
            </div>
            <button
              v-else
              :id="`chat-apps-revoke-${grant.id}`"
              type="button"
              class="button button--secondary button--small chat-apps__revoke"
              :disabled="revokingId !== null"
              @click="askToRevoke(grant)"
            >
              Revoke
            </button>
          </li>
        </ul>
        <p v-if="revokeError" class="field-error" role="alert">{{ revokeError }}</p>
      </div>
      <p v-else class="field-hint">No tokens or connected apps yet.</p>
    </template>

    <dialog
      ref="tokenDialog"
      class="dialog chat-apps__token-dialog"
      aria-labelledby="chat-apps-token-heading"
      @cancel.prevent="closeTokenDialog"
      @close="closeTokenDialog"
    >
      <template v-if="issuedToken !== null">
        <header class="dialog__head dialog__head--ruled">
          <h2 :id="`chat-apps-token-heading`" class="dialog__title">Your new token</h2>
          <p class="dialog__subtitle">Brownie will not show this token again.</p>
          <button class="icon-button dialog__close" type="button" @click="closeTokenDialog">
            <AppIcon name="close" :size="22" />
            <span class="visually-hidden">Close</span>
          </button>
        </header>
        <div class="dialog__body chat-apps__token-body">
          <p class="visually-hidden" aria-live="polite" aria-atomic="true">{{ dialogStatus }}</p>

          <div class="chat-apps__token-value">
            <code class="chat-apps__token-text">{{ issuedToken }}</code>
            <button
              type="button"
              class="button button--secondary button--small"
              @click="copyText(issuedToken ?? '', 'Token copied.')"
            >
              <AppIcon name="copy" :size="16" />
              Copy
            </button>
          </div>

          <dl class="chat-apps__lines">
            <div class="chat-apps__line">
              <dt>Claude Code</dt>
              <dd>
                <code>{{ claudeCodeLine }}</code>
                <button
                  type="button"
                  class="button button--quiet button--small"
                  :aria-label="`Copy the Claude Code line`"
                  @click="copyText(claudeCodeLine, 'The Claude Code line is copied.')"
                >
                  <AppIcon name="copy" :size="14" />
                </button>
              </dd>
            </div>
            <div class="chat-apps__line">
              <dt>VS Code -- <code>.vscode/mcp.json</code></dt>
              <dd>
                <code>{{ vsCodeJson }}</code>
                <button
                  type="button"
                  class="button button--quiet button--small"
                  aria-label="Copy the VS Code entry"
                  @click="copyText(vsCodeJson, 'The VS Code entry is copied.')"
                >
                  <AppIcon name="copy" :size="14" />
                </button>
              </dd>
            </div>
            <div class="chat-apps__line">
              <dt>Cursor -- <code>~/.cursor/mcp.json</code></dt>
              <dd>
                <code>{{ cursorJson }}</code>
                <button
                  type="button"
                  class="button button--quiet button--small"
                  aria-label="Copy the Cursor entry"
                  @click="copyText(cursorJson, 'The Cursor entry is copied.')"
                >
                  <AppIcon name="copy" :size="14" />
                </button>
              </dd>
            </div>
          </dl>
        </div>
      </template>
    </dialog>
  </section>
</template>

<style scoped>
.chat-apps {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.chat-apps__head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.chat-apps__title {
  min-inline-size: 0;
  margin: 0;
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.chat-apps__lede {
  margin: 0;
  font-size: var(--font-size-sm);
  line-height: 1.4;
}

.chat-apps__loading {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}

.chat-apps > .field-hint,
.chat-apps__create > .field-hint,
.chat-apps__grants > .field-hint,
.chat-apps__howtos > .field-hint {
  margin: 0;
}

.chat-apps__url {
  display: grid;
  gap: var(--space-1);
}

.chat-apps__url-row {
  display: flex;
  gap: var(--space-2);
}

.chat-apps__url-row > .text-field {
  flex: 1;
  min-inline-size: 0;
}

.chat-apps__howtos {
  display: grid;
  gap: var(--space-2);
}

.chat-apps__create {
  display: grid;
  gap: var(--space-2);
  padding-block-start: var(--space-2);
  border-block-start: 1px solid var(--color-hairline);
}

.chat-apps__create > .text-field {
  max-inline-size: 26rem;
}

.chat-apps__scopes {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  border: 0;
}

.chat-apps__check {
  font-size: var(--font-size-sm);
}

.chat-apps__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.chat-apps__grants {
  display: grid;
  gap: var(--space-2);
  padding-block-start: var(--space-2);
  border-block-start: 1px solid var(--color-hairline);
}

.chat-apps__grants-title {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
}

.chat-apps__grant-list {
  display: grid;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.chat-apps__grant {
  display: grid;
  gap: var(--space-1);
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
}

.chat-apps__grant-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.chat-apps__grant-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.chat-apps__grant-kind {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}

.chat-apps__grant-facts,
.chat-apps__grant-scopes {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  line-height: 1.4;
}

.chat-apps__revoke {
  justify-self: start;
  margin-block-start: var(--space-1);
}

.chat-apps__revoke-confirm {
  display: grid;
  gap: var(--space-2);
  margin-block-start: var(--space-1);
  padding: 0.5rem 0.75rem;
  border-radius: var(--radius-md);
  background: var(--color-paper-soft);
}

.chat-apps__revoke-question {
  margin: 0;
  color: var(--color-error);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  line-height: 1.4;
}

.chat-apps__revoke-question:focus-visible {
  box-shadow: none;
  outline: none;
}

/* The one-time token: wide enough for a pasted line to read on one screen, like the other dialogs. */
.chat-apps__token-body {
  display: grid;
  gap: var(--space-4);
}

.chat-apps__token-value {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.chat-apps__token-text {
  flex: 1;
  min-inline-size: 0;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
  background: var(--color-paper-soft);
  font-size: var(--font-size-sm);
  overflow-wrap: anywhere;
  user-select: all;
}

.chat-apps__lines {
  display: grid;
  gap: var(--space-3);
  margin: 0;
}

.chat-apps__line {
  display: grid;
  gap: var(--space-1);
}

.chat-apps__line > dt {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.chat-apps__line > dd {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin: 0;
}

.chat-apps__line > dd > code {
  flex: 1;
  min-inline-size: 0;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--color-hairline);
  border-radius: var(--radius-md);
  background: var(--color-paper-soft);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  overflow-wrap: anywhere;
  user-select: all;
}
</style>
