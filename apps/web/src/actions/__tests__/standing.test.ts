import { describe, expect, it } from 'vitest'
import type { ActionResponse, ConnectionResponse } from '@/api/client'
import { ADD_STANDING, SAVE_STANDING, connectionStanding, standingOf } from '@/actions/standing'

function action(overrides: Partial<ActionResponse> = {}): ActionResponse {
  return {
    id: 1,
    type: 'DRIVE_SAVE_FILE',
    documentId: 42,
    state: 'AWAITING_APPROVAL',
    payload: {},
    payloadHash: 'c'.repeat(64),
    createdAt: '2026-09-28T10:00:00Z',
    expiresAt: '2026-09-28T10:30:00Z',
    approvedAt: null,
    approvalExpiresAt: null,
    sent: false,
    verification: null,
    conversionCheck: null,
    failure: null,
    outcomeAcknowledged: false,
    externalLink: null,
    finishedAt: null,
    ...overrides,
  }
}

describe('what the chip of a change says', () => {
  it('tells a change waiting for approval from every other, and gives it the edge of the action colour', () => {
    expect(standingOf(action(), SAVE_STANDING)).toEqual({
      label: 'Waiting for your approval',
      tone: 'tag--honey',
      icon: 'clock',
      edge: 'approval',
    })
  })

  it('words the done and not-done outcomes for the kind of change', () => {
    expect(standingOf(action({ state: 'SUCCEEDED', sent: true, verification: 'MATCHED' }), SAVE_STANDING)?.label).toBe('Saved')
    expect(standingOf(action({ state: 'SUCCEEDED', sent: true, verification: 'MATCHED' }), ADD_STANDING)?.label).toBe('Added')
    expect(standingOf(action({ state: 'FAILED', failure: 'PROVIDER_REFUSED' }), SAVE_STANDING)).toMatchObject({ label: 'Not saved', tone: 'tag--error' })
    expect(standingOf(action({ state: 'FAILED', failure: 'PROVIDER_REFUSED' }), ADD_STANDING)?.label).toBe('Not added')
    expect(standingOf(action({ state: 'APPROVED' }), ADD_STANDING)).toMatchObject({ label: 'Not added yet', edge: 'attention' })
  })

  it('says a save that was made and has been deleted since, or that was converted and differs, is not plainly done', () => {
    expect(standingOf(action({ state: 'SUCCEEDED', sent: true, verification: 'REMOVED_AFTERWARDS' }), SAVE_STANDING)).toMatchObject({
      label: 'Saved, then deleted',
      tone: '',
    })
    expect(standingOf(action({ state: 'SUCCEEDED', sent: true, verification: 'CONVERSION_DIFFERS' }), SAVE_STANDING)).toMatchObject({
      label: 'Saved, check it',
      tone: 'tag--honey',
    })
  })

  it('never says nothing was saved of a file Google made that did not read back as approved', () => {
    expect(standingOf(action({ state: 'FAILED', failure: 'READBACK_MISMATCH', sent: true }), SAVE_STANDING)).toMatchObject({
      label: 'Needs a look',
      edge: 'attention',
    })
  })

  it('keeps an outcome Brownie cannot know honey until the person has looked, and a try that sent nothing plainly not done', () => {
    expect(standingOf(action({ state: 'OUTCOME_UNKNOWN', sent: true }), SAVE_STANDING)).toMatchObject({
      label: 'Not confirmed',
      tone: 'tag--honey',
      edge: 'attention',
    })
    expect(standingOf(action({ state: 'OUTCOME_UNKNOWN', sent: true, outcomeAcknowledged: true }), SAVE_STANDING)).toMatchObject({
      label: 'Not confirmed',
      tone: '',
      edge: null,
    })
    expect(standingOf(action({ state: 'OUTCOME_UNKNOWN', sent: false }), SAVE_STANDING)).toMatchObject({ label: 'Not saved', tone: '' })
  })

  it('calls a change under way in progress, but one whose attempt stopped not finished, since only the person can settle it', () => {
    expect(standingOf(action({ state: 'EXECUTING', sent: true }), SAVE_STANDING)).toMatchObject({ label: 'In progress', tone: 'tag--info', edge: null })
    expect(standingOf(action({ state: 'RECONCILING', sent: true }), SAVE_STANDING)?.label).toBe('In progress')
    for (const state of ['EXECUTING', 'RECONCILING'] as const) {
      expect(standingOf(action({ state, sent: true, attemptStopped: true }), SAVE_STANDING)).toMatchObject({
        label: 'Not finished',
        tone: 'tag--honey',
        edge: 'attention',
      })
    }
  })

  it('shows what is over in grey, and nothing at all for a state it does not know', () => {
    expect(standingOf(action({ state: 'CANCELLED' }), SAVE_STANDING)).toMatchObject({ label: 'Cancelled', tone: '', edge: null })
    expect(standingOf(action({ state: 'EXPIRED' }), SAVE_STANDING)).toMatchObject({ label: 'Expired', tone: '', icon: 'clock' })
    expect(standingOf(action({ state: 'PAUSED' as ActionResponse['state'] }), SAVE_STANDING)).toBeNull()
  })

  it('gives every chip a word, so no state is told by its colour alone', () => {
    const states: ActionResponse['state'][] = [
      'AWAITING_APPROVAL',
      'APPROVED',
      'EXECUTING',
      'SUCCEEDED',
      'FAILED',
      'OUTCOME_UNKNOWN',
      'RECONCILING',
      'CANCELLED',
      'EXPIRED',
    ]
    for (const state of states) {
      const standing = standingOf(action({ state, sent: true }), SAVE_STANDING)
      expect(standing?.label).toBeTruthy()
      if (standing?.tone !== '') expect(standing?.icon).not.toBeNull()
    }
  })
})

describe('what the chip of a connection that cannot be used yet says', () => {
  const connection = (state: ConnectionResponse['state']): ConnectionResponse => ({
    id: 5,
    provider: 'GOOGLE',
    access: 'DRIVE_SAVING',
    state,
    accountEmail: 'me@example.org',
    grantedScopes: [],
    reconnectReason: null,
    connectedAt: '2026-09-20T10:00:00Z',
    tokenIssuedAt: '2026-09-20T10:00:00Z',
    disconnectedAt: null,
    providerRevocation: null,
    grants: [],
  })

  it('calls a connection Google ended paused, and anything else not connected', () => {
    expect(connectionStanding(connection('RECONNECT_REQUIRED'))).toMatchObject({ label: 'Paused', tone: 'tag--honey', edge: 'attention' })
    expect(connectionStanding(connection('DISCONNECTED'))).toMatchObject({ label: 'Not connected', tone: '' })
    expect(connectionStanding(null)).toMatchObject({ label: 'Not connected', tone: '', edge: null })
  })
})
