import type { ActionResponse, ConnectionResponse } from '@/api/client'
import type { IconName } from '@/components/icons'

/**
 * What the chip on a card says about where one change, or one connection, stands. Each is a word and an icon as
 * well as a colour, so a state is never told by colour alone; the longer sentence about it sits in the card. Honey
 * is for what needs the person (a decision, or a look for themselves), green for what is done, red for what did not
 * happen, blue for what is under way, and grey for what is over.
 */
export interface Standing {
  label: string
  /** The shared chip class that colours it; grey has none. */
  tone: '' | 'tag--honey' | 'tag--success' | 'tag--error' | 'tag--info'
  icon: IconName | null
  /** How its card's edge is drawn: the action colour while it waits for approval, honey while it needs a look. */
  edge: 'approval' | 'attention' | null
}

/** The two outcomes of one kind of change, as its chip words them: "Saved" and "Not saved", "Added" and "Not added". */
export interface StandingWords {
  done: string
  notDone: string
}

export const SAVE_STANDING: StandingWords = { done: 'Saved', notDone: 'Not saved' }
export const ADD_STANDING: StandingWords = { done: 'Added', notDone: 'Not added' }

/**
 * Where a change stands, read only from the change itself. A state this page does not know (a newer server may
 * have more) gets no chip at all rather than a wrong one.
 */
export function standingOf(action: ActionResponse, words: StandingWords): Standing | null {
  switch (action.state) {
    case 'AWAITING_APPROVAL':
      return { label: 'Waiting for your approval', tone: 'tag--honey', icon: 'clock', edge: 'approval' }
    case 'APPROVED':
      return { label: `${words.notDone} yet`, tone: 'tag--honey', icon: 'warning', edge: 'attention' }
    case 'EXECUTING':
    case 'RECONCILING':
      // An attempt that stopped no longer holds the change, so it is not "in progress": only the person can settle it.
      return action.attemptStopped === true
        ? { label: 'Not finished', tone: 'tag--honey', icon: 'warning', edge: 'attention' }
        : { label: 'In progress', tone: 'tag--info', icon: 'spinner', edge: null }
    case 'SUCCEEDED':
      if (action.verification === 'REMOVED_AFTERWARDS') return { label: `${words.done}, then deleted`, tone: '', icon: null, edge: null }
      if (action.verification === 'CONVERSION_DIFFERS') return { label: `${words.done}, check it`, tone: 'tag--honey', icon: 'warning', edge: null }
      return { label: words.done, tone: 'tag--success', icon: 'check-circle', edge: null }
    case 'FAILED':
      // Google made something that is not what was approved: it is there to look at, so "not saved" would be untrue.
      return action.failure === 'READBACK_MISMATCH'
        ? { label: 'Needs a look', tone: 'tag--honey', icon: 'warning', edge: 'attention' }
        : { label: words.notDone, tone: 'tag--error', icon: 'close-circle', edge: null }
    case 'OUTCOME_UNKNOWN':
      if (!action.sent) return { label: words.notDone, tone: '', icon: null, edge: null }
      // Once the person has looked there is nothing left to ask of them, though Brownie still cannot say.
      return action.outcomeAcknowledged
        ? { label: 'Not confirmed', tone: '', icon: null, edge: null }
        : { label: 'Not confirmed', tone: 'tag--honey', icon: 'warning', edge: 'attention' }
    case 'CANCELLED':
      return { label: 'Cancelled', tone: '', icon: null, edge: null }
    case 'EXPIRED':
      return { label: 'Expired', tone: '', icon: 'clock', edge: null }
    default:
      return null
  }
}

/** What the chip of a card whose connection cannot be used yet says: one Google ended is "Paused", anything else "Not connected". */
export function connectionStanding(connection: ConnectionResponse | null): Standing {
  return connection?.state === 'RECONNECT_REQUIRED'
    ? { label: 'Paused', tone: 'tag--honey', icon: 'warning', edge: 'attention' }
    : { label: 'Not connected', tone: '', icon: null, edge: null }
}
