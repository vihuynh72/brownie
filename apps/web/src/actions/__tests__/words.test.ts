import { describe, expect, it } from 'vitest'
import { ApiRequestError, type ActionResponse } from '@/api/client'
import {
  actionLink,
  approvalSentNothing,
  conversionSentence,
  describeActionFailure,
  driveSaveAfterwards,
  driveSavePreview,
  driveSaveSentence,
  driveSaveWhat,
  driveSaveWhere,
  docAppendSentence,
  needsAttention,
  offeredActions,
  proposalRecordedNothing,
} from '@/actions/words'

function payload(overrides: { type?: string; conversion?: string; format?: string; place?: string; sharing?: string; email?: string | null } = {}) {
  return {
    schema: 'brownie.action/1',
    type: overrides.type ?? 'DRIVE_SAVE_FILE',
    nonce: 'n',
    proposedBy: 3,
    workspace: 7,
    document: { id: 42, revision: 9, title: 'Spring Budget Planning minutes' },
    account: { connection: 5, email: overrides.email === undefined ? 'me@example.org' : overrides.email },
    target: { place: overrides.place ?? 'MY_DRIVE_TOP', sharing: overrides.sharing ?? 'NOBODY' },
    content: {
      exportReceipt: 12,
      artifact: 13,
      format: overrides.format ?? 'DOCX',
      fileName: 'Spring Budget Planning minutes.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      bytes: 24_700,
      sha256: 'a'.repeat(64),
      md5: 'b'.repeat(32),
    },
    effect: { creates: 'NEW_FILE', conversion: overrides.conversion ?? 'NONE' },
  }
}

function action(overrides: Partial<ActionResponse> = {}): ActionResponse {
  return {
    id: 1,
    type: 'DRIVE_SAVE_FILE',
    documentId: 42,
    state: 'AWAITING_APPROVAL',
    payload: payload(),
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

function failure(status: number, code: string, reason?: string): ApiRequestError {
  return new ApiRequestError(status, {
    status,
    title: 't',
    code,
    detail: 'The server said something else.',
    correlationId: 'c',
    fields: [],
    recoveryActions: [],
    ...(reason === undefined ? {} : { reason }),
  })
}

describe('what a save to Google Drive would do, read from its payload', () => {
  it('states an exact file: its name, size, account and place', () => {
    const preview = driveSavePreview(action())
    expect(preview).toEqual({
      kind: 'WORD_FILE',
      fileName: 'Spring Budget Planning minutes.docx',
      bytes: 24_700,
      sha256: 'a'.repeat(64),
      accountEmail: 'me@example.org',
      documentTitle: 'Spring Budget Planning minutes',
    })
    expect(driveSaveWhat(preview!)).toBe(
      'A new Word file named "Spring Budget Planning minutes.docx", 24.1 KB: exactly the file you exported.',
    )
    expect(driveSaveWhere(preview!)).toBe('At the top of My Drive in the Google Drive of me@example.org, shared with no one.')
    expect(driveSaveAfterwards(preview!)).toContain('reads back only this file')
  })

  it('states a conversion as Google making it, and warns that it can look different', () => {
    const preview = driveSavePreview(
      action({ type: 'DRIVE_SAVE_AS_GOOGLE_DOC', payload: payload({ type: 'DRIVE_SAVE_AS_GOOGLE_DOC', conversion: 'GOOGLE_DOC' }) }),
    )
    expect(preview?.kind).toBe('GOOGLE_DOC')
    expect(driveSaveWhat(preview!)).toContain('which Google makes from the Word file you exported')
    expect(driveSaveWhat(preview!)).toContain("Google's conversion can change how it looks")
    expect(driveSaveAfterwards(preview!)).toContain('look for your filled-in values in its text')
  })

  it('says "your Google Drive" when the account was not named', () => {
    expect(driveSaveWhere(driveSavePreview(action({ payload: payload({ email: null }) }))!)).toBe(
      'At the top of My Drive in your Google Drive, shared with no one.',
    )
  })

  it('refuses to state any payload it cannot state in full, so that it is never offered for approval', () => {
    expect(driveSavePreview(action({ payload: payload({ sharing: 'ANYONE_WITH_LINK' }) }))).toBeNull()
    expect(driveSavePreview(action({ payload: payload({ place: 'SHARED_FOLDER' }) }))).toBeNull()
    expect(driveSavePreview(action({ payload: payload({ type: 'DRIVE_SAVE_AS_GOOGLE_DOC' }) }))).toBeNull()
    expect(driveSavePreview(action({ payload: payload({ conversion: 'GOOGLE_DOC' }) }))).toBeNull()
    expect(driveSavePreview(action({ payload: payload({ format: 'XLSX' }) }))).toBeNull()
    expect(
      driveSavePreview(
        action({ type: 'DRIVE_SAVE_AS_GOOGLE_DOC', payload: payload({ type: 'DRIVE_SAVE_AS_GOOGLE_DOC', conversion: 'GOOGLE_DOC', format: 'PDF' }) }),
      ),
    ).toBeNull()
    expect(driveSavePreview(action({ payload: { ...payload(), schema: 'brownie.action/2' } }))).toBeNull()
    expect(driveSavePreview(action({ type: 'CALENDAR_CREATE_EVENT', payload: payload({ type: 'CALENDAR_CREATE_EVENT' }) }))).toBeNull()
    expect(driveSavePreview(action({ payload: { ...payload(), content: { ...payload().content, bytes: '24700' } } }))).toBeNull()
    expect(driveSavePreview(action({ payload: { ...payload(), content: null } as unknown as ActionResponse['payload'] }))).toBeNull()
    expect(driveSavePreview(action({ payload: { ...payload(), content: { ...payload().content, sha256: 'not a hash' } } }))).toBeNull()
  })
})

describe('where a save stands', () => {
  it('says each state in words, and never that an unknown outcome did not happen', () => {
    const preview = driveSavePreview(action())
    expect(driveSaveSentence(action(), preview)).toMatch(
      /^Ready to save "Spring Budget Planning minutes.docx". Nothing is saved until you approve it, which you can do until .+\.$/,
    )
    expect(driveSaveSentence(action({ state: 'SUCCEEDED' }), preview)).toBe('Saved "Spring Budget Planning minutes.docx" to your Google Drive.')
    const unknown = driveSaveSentence(action({ state: 'OUTCOME_UNKNOWN', sent: true }), preview)
    expect(unknown).toContain('cannot tell yet whether')
    expect(unknown).toContain('Brownie did not send it again.')
    expect(unknown).not.toContain('nothing was saved')
    expect(driveSaveSentence(action({ state: 'OUTCOME_UNKNOWN', sent: true, outcomeAcknowledged: true }), preview)).toContain(
      'You said you looked in your Google Drive',
    )
    expect(driveSaveSentence(action({ state: 'OUTCOME_UNKNOWN', sent: false, outcomeAcknowledged: true }), preview)).toBe(
      'Nothing was sent for "Spring Budget Planning minutes.docx", so it was not saved. The try that stopped is closed.',
    )
    expect(driveSaveSentence(action({ state: 'SUCCEEDED', verification: 'REMOVED_AFTERWARDS' }), preview)).toBe(
      '"Spring Budget Planning minutes.docx" was saved to your Google Drive, and has since been deleted there.',
    )
    expect(driveSaveSentence(action({ state: 'CANCELLED' }), null)).toBe('Cancelled. This file was not saved.')
    expect(driveSaveSentence(action({ state: 'EXPIRED' }), preview)).toBe('This was not approved in time, so nothing was saved.')
    expect(driveSaveSentence(action({ state: 'EXPIRED', approvedAt: '2026-09-28T10:05:00Z' }), preview)).toContain('The approval ran out')
    expect(driveSaveSentence(action({ state: 'APPROVED', approvalExpiresAt: '2026-09-28T10:20:00Z' }), preview)).toMatch(
      /^".+" was not saved: it did not reach Google, or Google turned it away for now. You can try again until .+, or cancel\.$/,
    )
    expect(driveSaveSentence(action({ state: 'EXECUTING' }), preview)).toContain('or was when this page last asked')
  })

  it('gives each failure its own sentence, and says a mismatched file was made and left alone', () => {
    expect(driveSaveSentence(action({ state: 'FAILED', failure: 'STORAGE_FULL' }), null)).toContain('has no room left')
    expect(driveSaveSentence(action({ state: 'FAILED', failure: 'EXPORT_CHANGED' }), null)).toContain('exported again')
    const mismatch = driveSaveSentence(action({ state: 'FAILED', failure: 'READBACK_MISMATCH', sent: true }), null)
    expect(mismatch).toContain('Google made a file')
    expect(mismatch).toContain('Brownie did not change or remove it')
  })

  it('keeps on the list whatever still needs the person', () => {
    expect(needsAttention(action())).toBe(true)
    expect(needsAttention(action({ state: 'OUTCOME_UNKNOWN' }))).toBe(true)
    expect(needsAttention(action({ state: 'OUTCOME_UNKNOWN', outcomeAcknowledged: true }))).toBe(false)
    expect(needsAttention(action({ state: 'SUCCEEDED' }))).toBe(false)
  })
})

describe("a converted Google Doc's check", () => {
  it('says how many filled-in values were found, and that formatting was not compared', () => {
    expect(conversionSentence(null)).toBeNull()
    expect(conversionSentence({ total: 4, found: 4 })).toBe(
      "Brownie found all 4 filled-in values in the Google Doc's text. Layout, tables, lists and formatting were not compared.",
    )
    expect(conversionSentence({ total: 1, found: 1 })).toContain('found the filled-in value')
    expect(conversionSentence({ total: 4, found: 3 })).toContain('found 3 of 4 filled-in values')
    expect(conversionSentence({ total: 4, found: 3 })).toContain('compare the Google Doc with the Word file')
    expect(conversionSentence({ total: 0, found: 0 })).toContain('No filled-in value was long enough to look for')
  })
})

describe('the link to what a save made', () => {
  it("is only ever an https address on Google's own sites", () => {
    expect(actionLink(action({ externalLink: 'https://drive.google.com/file/d/abc/view' }))).toBe('https://drive.google.com/file/d/abc/view')
    expect(actionLink(action({ externalLink: 'https://docs.google.com/document/d/abc/edit' }))).toBe(
      'https://docs.google.com/document/d/abc/edit',
    )
    expect(actionLink(action({ externalLink: 'http://drive.google.com/file/d/abc' }))).toBeNull()
    expect(actionLink(action({ externalLink: 'https://drive.google.com.example.org/x' }))).toBeNull()
    expect(actionLink(action({ externalLink: 'javascript:alert(1)' }))).toBeNull()
    expect(actionLink(action({ externalLink: 'not a url' }))).toBeNull()
    expect(actionLink(action())).toBeNull()
  })
})

describe('failures of requests about a save', () => {
  it('tells refusals that sent nothing from failures that may have come after sending', () => {
    expect(approvalSentNothing(failure(409, 'ACTION_SIBLING_UNRESOLVED'))).toBe(true)
    expect(approvalSentNothing(failure(409, 'CONNECTION_RECONNECT_REQUIRED'))).toBe(true)
    expect(approvalSentNothing(failure(503, 'CONNECTOR_PROVIDER_UNAVAILABLE'))).toBe(true)
    expect(approvalSentNothing(failure(429, 'RATE_LIMITED'))).toBe(true)
    expect(approvalSentNothing(new ApiRequestError(401, undefined))).toBe(true)
    expect(approvalSentNothing(failure(404, 'NOT_FOUND'))).toBe(false)
    expect(approvalSentNothing(failure(500, 'INTERNAL_ERROR'))).toBe(false)
    expect(approvalSentNothing(failure(503, 'SERVICE_UNAVAILABLE'))).toBe(false)
    expect(approvalSentNothing(new ApiRequestError(502, undefined))).toBe(false)
    expect(approvalSentNothing(new TypeError('Failed to fetch'))).toBe(false)
  })

  it("words Brownie's own refusals, and names saving for a connection's", () => {
    expect(describeActionFailure(failure(409, 'ACTION_NOT_PROPOSABLE', 'EXPORT_STALE'), 'x')).toBe(
      'The document changed after it was last exported. Export it again first.',
    )
    expect(describeActionFailure(failure(409, 'ACTION_NOT_PROPOSABLE', 'SOMETHING_NEWER'), 'x')).toBe(
      'This cannot be saved to Google Drive as it is.',
    )
    expect(describeActionFailure(failure(409, 'ACTION_SIBLING_UNRESOLVED'), 'x')).toContain('Check that one first')
    expect(describeActionFailure(failure(404, 'CONNECTION_NOT_FOUND'), 'x')).toBe('Google Drive for saving is not connected. Connect it first.')
    expect(describeActionFailure(failure(403, 'FORBIDDEN'), 'x')).toBe('Your role here does not allow changes in connected accounts.')
  })

  it('reads which changes this Brownie makes, and nothing from a server that does not say', () => {
    const base = { maxUploadBytes: 1, uploadMediaTypes: [], assistSourceMediaTypes: [], templateMediaTypes: [], trashRetentionDays: 30 }
    expect(offeredActions({ ...base, googleActions: ['DRIVE_SAVE_FILE'] })).toEqual(['DRIVE_SAVE_FILE'])
    expect(offeredActions(base)).toBeNull()
    expect(offeredActions(null)).toBeNull()
  })
})

describe('what a failed request to prepare a change leaves', () => {
  it('says nothing was prepared only for a refusal that came before anything was kept', () => {
    expect(proposalRecordedNothing(failure(409, 'ACTION_NOT_PROPOSABLE', 'EXPORT_STALE'))).toBe(true)
    expect(proposalRecordedNothing(failure(404, 'NOT_FOUND'))).toBe(true)
    expect(proposalRecordedNothing(failure(503, 'CONNECTOR_PROVIDER_UNAVAILABLE'))).toBe(true)
    expect(proposalRecordedNothing(new ApiRequestError(401, undefined))).toBe(true)
    expect(proposalRecordedNothing(failure(500, 'INTERNAL_ERROR'))).toBe(false)
    expect(proposalRecordedNothing(failure(503, 'DATABASE_UNAVAILABLE'))).toBe(false)
    expect(proposalRecordedNothing(new ApiRequestError(502, undefined))).toBe(false)
    expect(proposalRecordedNothing(new TypeError('Failed to fetch'))).toBe(false)
  })
})

describe('where an addition to a Google Doc stands', () => {
  function addition(overrides: Partial<ActionResponse> = {}): ActionResponse {
    return action({ type: 'GOOGLE_DOC_APPEND', ...overrides })
  }
  const preview = { savedBy: 31, documentRevision: 9, docTitle: 'Minutes', text: 'Budget', shared: false, accountEmail: null }

  it('speaks of the text as exported when it was prepared, and says so when nothing was sent', () => {
    expect(docAppendSentence(addition(), preview)).toContain("Ready to add the document's text, as exported when this was prepared")
    expect(docAppendSentence(addition({ state: 'SUCCEEDED', sent: true }), preview)).toContain(
      "Added the document's text, as exported when this was prepared, to the end of \"Minutes\"",
    )
    expect(docAppendSentence(addition({ state: 'OUTCOME_UNKNOWN', sent: false, outcomeAcknowledged: true }), preview)).toBe(
      'Nothing was sent for this addition, so nothing was added to "Minutes". The try that stopped is closed.',
    )
    expect(docAppendSentence(addition({ state: 'OUTCOME_UNKNOWN', sent: true }), preview)).toContain('Brownie did not send it again.')
  })
})
