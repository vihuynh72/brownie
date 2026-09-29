import { computed, nextTick, ref } from 'vue'
import { loadCapabilities } from '@/capabilities'
import { navigateTo, releaseIfStillHere } from '@/navigation'
import { brownieSaysNotThere } from '@/api/failures'
import {
  ApiRequestError,
  acknowledgeAction,
  approveAction,
  cancelAction,
  getAction,
  listActions,
  listConnections,
  reconcileAction,
  startGoogleConsent,
  type ActionResponse,
  type ConnectionResponse,
} from '@/api/client'
import { latestConnection } from '@/connections/words'
import {
  approvalSentNothing,
  describeActionFailure,
  needsAttention,
  offeredActions,
  proposalRecordedNothing,
  withdrawable,
  type ActionKindWords,
  type ActionType,
} from '@/actions/words'

/** The saves or events listed: the most recent few, any older one that still needs the person, and any they acted on here. */
const RECENT = 5

/** How one kind of change is spoken of when something goes wrong with it. */
export interface ActionWords {
  kind: ActionKindWords
  /** What one of them is called: "save", "event". */
  thing: string
  /** What an unknown outcome leaves open, after "Whether": "the file was saved". */
  outcome: string
  /** Where the person can look for themselves: "your Google Drive". */
  lookIn: string
  /** What the page could not find, for an older server: "a way to save to Google Drive". */
  what: string
}

export interface ActionListOptions extends ActionWords {
  workspaceId: () => number
  documentId: () => number
  /** The kinds of change this list shows and proposes. */
  types: readonly ActionType[]
  /** Words for one change, where a list holds more than one kind; the list's own words otherwise. */
  wordsFor?: (action: ActionResponse) => ActionWords
  /** The id of the element to fall back on for focus when the one acted on is gone. */
  heading: string
  /** The id each item's sentence carries, before its number. */
  itemPrefix: string
}

/**
 * The part every panel of approved changes shares: what this Brownie offers,
 * the person's connection for it, the changes proposed for this document, and
 * approving, asking about, cancelling and acknowledging one. Nothing here
 * proposes, approves or sends anything by itself: every request is one the
 * person asked for, and an approval whose answer is lost is only read again.
 */
export function useActionList(options: ActionListOptions) {
  const offered = ref<ActionType[] | null>(null)
  /** The server could not be asked what it offers; earlier changes are still shown if they can be listed. */
  const offerUnknown = ref(false)
  const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
  const connection = ref<ConnectionResponse | null>(null)
  const actions = ref<ActionResponse[]>([])
  /** Changes this page could not read again after a failed request, so it offers to read them once more. */
  const unread = ref<Set<number>>(new Set())
  /** Changes the person did something to on this page: kept on the list, so the outcome stays where they looked. */
  const touched = ref<Set<number>>(new Set())
  const busy = ref(false)
  const error = ref<string | null>(null)
  /** Counts every answer about one change, so a listing that set off earlier never puts back an older state of it. */
  let answered = 0
  const answeredAt = new Map<number, number>()

  function wordsOf(action: ActionResponse): ActionWords {
    return options.wordsFor?.(action) ?? options
  }

  const typesOffered = computed(() => (offered.value ?? []).some((type) => options.types.includes(type)))
  const shown = computed(() =>
    actions.value.filter((action, index) => index < RECENT || needsAttention(action) || touched.value.has(action.id)),
  )
  /** Shown where these changes are offered, and wherever earlier ones of this document are still there to see or check. */
  const visible = computed(() => typesOffered.value || actions.value.length > 0)
  const connected = computed(() => connection.value?.state === 'ACTIVE')
  /** The connection is missing or must be made again, so the button to make it is offered in place of the form. */
  const reconnectOffered = computed(() => typesOffered.value && loadState.value === 'loaded' && !connected.value)

  async function load(): Promise<void> {
    loadState.value = 'loading'
    error.value = null
    offerUnknown.value = false
    try {
      offered.value = offeredActions(await loadCapabilities())
    } catch {
      offered.value = null
      offerUnknown.value = true
    }
    // A server that says nothing about changes it makes is from before any: it has nothing to list either.
    if (offered.value === null && !offerUnknown.value) {
      loadState.value = 'loaded'
      return
    }
    const listedFrom = answered
    const [listed, connections] = await Promise.allSettled([
      listActions(options.workspaceId(), options.documentId()),
      typesOffered.value ? listConnections(options.workspaceId()) : Promise.resolve(null),
    ])
    let failed: unknown = null
    if (listed.status === 'fulfilled') {
      // Only a list is read as one: an answer of any other shape lists nothing.
      const fresh = Array.isArray(listed.value) ? listed.value.filter((action) => options.types.includes(action.type)) : []
      // A change answered for while the listing was on its way keeps that newer answer.
      const newer = actions.value.filter((action) => (answeredAt.get(action.id) ?? -1) >= listedFrom)
      actions.value = [...newer, ...fresh.filter((action) => !newer.some((kept) => kept.id === action.id))].sort((a, b) => b.id - a.id)
    } else if (typesOffered.value) {
      failed = listed.reason
    }
    if (connections.status === 'fulfilled') {
      if (Array.isArray(connections.value)) connection.value = latestConnection(connections.value, options.kind.access)
    } else {
      failed = failed ?? connections.reason
    }
    if (failed !== null) {
      loadState.value = 'error'
      error.value = describeActionFailure(failed, options.what, options.kind) ?? `Brownie could not check your ${options.thing}s. Try again.`
    } else {
      loadState.value = 'loaded'
    }
  }

  async function loadConnection(): Promise<void> {
    if (!typesOffered.value) return
    connection.value = latestConnection(await listConnections(options.workspaceId()), options.kind.access)
  }

  /** Puts one change's latest state on the list, newest first. */
  function upsert(action: ActionResponse): void {
    answeredAt.set(action.id, answered++)
    const rest = actions.value.filter((each) => each.id !== action.id)
    actions.value = [action, ...rest].sort((a, b) => b.id - a.id)
    const stillUnread = new Set(unread.value)
    stillUnread.delete(action.id)
    unread.value = stillUnread
  }

  function touch(actionId: number): void {
    touched.value = new Set([...touched.value, actionId])
  }

  /** Focus on the first of these that is on the page once it has been redrawn. */
  async function focusOn(...ids: string[]): Promise<void> {
    await nextTick()
    for (const id of ids) {
      const element = window.document.getElementById(id)
      if (element) {
        element.focus()
        return
      }
    }
  }

  function itemId(action: ActionResponse): string {
    return `${options.itemPrefix}${action.id}`
  }

  /** A failure that means the connection is not what this panel last saw: asked again, so it offers the right button. */
  async function recheckAfter(failure: unknown): Promise<void> {
    const code = failure instanceof ApiRequestError ? failure.problem?.code : undefined
    if (code === 'CONNECTION_RECONNECT_REQUIRED' || code === 'CONNECTION_NOT_FOUND') {
      try {
        await loadConnection()
      } catch {
        // The button already shown stays; the message above says what went wrong.
      }
    }
  }

  /** Reads one change again after a request about it failed, since a failure alone does not say where it stands. */
  async function reread(actionId: number): Promise<boolean> {
    try {
      upsert(await getAction(options.workspaceId(), actionId))
      return true
    } catch {
      unread.value = new Set([...unread.value, actionId])
      return false
    }
  }

  /** Google's consent page for this kind of connection, then back to this document; it waits for unsaved changes. */
  async function connect(unsavedWork: boolean, connectionName: string): Promise<void> {
    if (busy.value) return
    if (unsavedWork) {
      error.value = "This document has changes that are not saved yet. Connecting takes you to Google's page, so connect once they are saved."
      return
    }
    busy.value = true
    error.value = null
    try {
      const started = await startGoogleConsent(options.workspaceId(), options.kind.access, `/documents/${options.documentId()}`)
      // Google's page, then back to this document; the button works again if Back brings this page back.
      navigateTo(started.authorizationUrl)
      releaseIfStillHere(() => {
        busy.value = false
      })
    } catch (failure) {
      error.value = `Could not start connecting ${connectionName}. ${
        describeActionFailure(failure, 'a way to connect Google accounts', options.kind) ?? 'Try again.'
      }`
      busy.value = false
    }
  }

  /**
   * Approves exactly the change shown, by the hash of its payload; the answer
   * is where it then stands. When the answer is a failure that may have come
   * after the change was sent, the page reads it again rather than say what
   * happened, and never approves or proposes it again by itself.
   */
  async function approve(action: ActionResponse): Promise<void> {
    if (busy.value) return
    busy.value = true
    error.value = null
    touch(action.id)
    const words = wordsOf(action)
    try {
      upsert(await approveAction(options.workspaceId(), action.id, action.payloadHash))
    } catch (failure) {
      if (brownieSaysNotThere(failure) && failure instanceof ApiRequestError && failure.problem?.code === 'NOT_FOUND') {
        // Something it needs is gone, or the change itself, with its document: which, only reading it again says.
        error.value = (await reread(action.id))
          ? `Something this ${words.thing} needs is no longer in Brownie. Where it stands now is shown below.`
          : `This ${words.thing} is no longer in Brownie, so what became of it cannot be shown. Look in ${words.lookIn}.`
      } else if (approvalSentNothing(failure)) {
        error.value = `Nothing was sent this time. ${describeActionFailure(failure, words.what, words.kind) ?? 'Try again.'}`
        await reread(action.id)
        await recheckAfter(failure)
      } else {
        const known = await reread(action.id)
        error.value = known
          ? "Brownie's answer to your approval did not arrive. What Brownie knows now is shown below."
          : `Brownie's answer to your approval did not arrive, and Brownie could not be asked again. Whether ${words.outcome} ` +
            `is not known here yet: check again in a moment, and do not prepare the same ${words.thing} again until then.`
      }
    } finally {
      busy.value = false
    }
    await focusOn(itemId(action), options.heading)
  }

  /** Asks Google what became of a change, or reads it again where Brownie could not be asked. */
  async function ask(action: ActionResponse, how: 'reconcile' | 'read'): Promise<void> {
    if (busy.value) return
    busy.value = true
    error.value = null
    touch(action.id)
    const words = wordsOf(action)
    try {
      upsert(how === 'reconcile' ? await reconcileAction(options.workspaceId(), action.id) : await getAction(options.workspaceId(), action.id))
    } catch (failure) {
      error.value = `Brownie could not find out more. ${describeActionFailure(failure, `a way to check ${words.thing}s`, words.kind) ?? 'Try again.'}`
      await recheckAfter(failure)
    } finally {
      busy.value = false
    }
    await focusOn(itemId(action), options.heading)
  }

  async function settle(action: ActionResponse, how: 'cancel' | 'acknowledge'): Promise<void> {
    if (busy.value) return
    busy.value = true
    error.value = null
    touch(action.id)
    const words = wordsOf(action)
    try {
      upsert(how === 'cancel' ? await cancelAction(options.workspaceId(), action.id) : await acknowledgeAction(options.workspaceId(), action.id))
    } catch (failure) {
      error.value = `${how === 'cancel' ? 'Not cancelled.' : 'Not recorded.'} ${
        describeActionFailure(failure, `a way to change ${words.thing}s`, words.kind) ?? 'Try again.'
      }`
      await reread(action.id)
    } finally {
      busy.value = false
    }
    await focusOn(itemId(action), options.heading)
  }

  /** Only a change of a kind this Brownie makes now, and one the page can state in full, can be approved. */
  function approvable(action: ActionResponse, stated: boolean): boolean {
    return withdrawable(action) && stated && (offered.value ?? []).includes(action.type)
  }

  /** A change waiting for approval of a kind this Brownie says it no longer makes: it can only be withdrawn. */
  function noLongerOffered(action: ActionResponse): boolean {
    return offered.value !== null && withdrawable(action) && !offered.value.includes(action.type)
  }

  /**
   * A request to prepare a change that failed: when it certainly recorded
   * nothing, why; otherwise the failure may have come after the proposal was
   * kept (a lost answer, or an error Brownie answered with), so the list is
   * read again, and the page says whether it could be.
   */
  async function proposalFailed(failure: unknown, words: ActionWords): Promise<void> {
    if (proposalRecordedNothing(failure)) {
      error.value = `Nothing was prepared. ${describeActionFailure(failure, words.what, words.kind) ?? 'Try again.'}`
      await recheckAfter(failure)
      return
    }
    const answered = failure instanceof ApiRequestError && failure.problem !== undefined
    const why = answered
      ? `Brownie answered with an error, so this ${words.thing} may or may not have been prepared. ${
          describeActionFailure(failure, words.what, words.kind) ?? ''
        }`.trim()
      : `Brownie's answer did not arrive, so this ${words.thing} may have been prepared all the same.`
    await load()
    error.value =
      loadState.value === 'loaded' && !offerUnknown.value
        ? `${why} Anything prepared is on the list below. Nothing is sent until you approve it.`
        : `${why} The list could not be read again: check again before preparing it a second time. Nothing is sent until you approve it.`
  }

  /** Reads everything again, then puts the focus back where the button that asked for it was. */
  async function checkAgain(): Promise<void> {
    await load()
    await focusOn(options.heading)
  }

  return {
    offered,
    offerUnknown,
    loadState,
    connection,
    actions,
    unread,
    busy,
    error,
    typesOffered,
    shown,
    visible,
    reconnectOffered,
    load,
    upsert,
    touch,
    focusOn,
    recheckAfter,
    connect,
    approve,
    ask,
    settle,
    approvable,
    noLongerOffered,
    checkAgain,
    proposalFailed,
  }
}
