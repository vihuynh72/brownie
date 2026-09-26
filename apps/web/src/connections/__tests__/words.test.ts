import { describe, expect, it } from 'vitest'
import { ApiRequestError, type DocumentSourceResponse } from '@/api/client'
import {
  CONSENT_QUERY_KEYS,
  PERMISSION_WORDS,
  consentOutcome,
  describeConnectorFailure,
  describeDriveCopyFailure,
  driveBrownieWords,
  mentionFile,
  originLink,
  originLinkLabel,
  originSentence,
} from '@/connections/words'

function failure(status: number, code: string, reason?: string, detail = 'The server said something else.'): ApiRequestError {
  return new ApiRequestError(status, {
    status,
    title: 't',
    code,
    detail,
    correlationId: 'c',
    fields: [],
    recoveryActions: [],
    ...(reason === undefined ? {} : { reason }),
  })
}

function driveSource(overrides: Partial<DocumentSourceResponse> = {}): DocumentSourceResponse {
  return {
    id: 90,
    artifactId: 91,
    kind: 'GOOGLE_DRIVE',
    displayFilename: 'Minutes.txt',
    fetchedAt: '2026-09-23T08:00:00Z',
    attachedAt: '2026-09-23T08:00:00Z',
    origin: {
      provider: 'GOOGLE',
      title: 'Minutes',
      link: 'https://docs.google.com/document/d/abc/edit',
      modifiedAt: '2026-09-21T08:00:00Z',
      conversion: 'GOOGLE_DOC_AS_TEXT',
    },
    ...overrides,
  }
}

describe('what a pick says when Google sends the person back', () => {
  it('says how many of the files picked are on the list now and why each other one is not', () => {
    const outcome = consentOutcome({
      google: 'picked',
      access: 'drive_files',
      added: '2',
      unsupported: '1',
      unavailable: '1',
      unchecked: '3',
      over_limit: '4',
    })

    expect(outcome?.tone).toBe('success')
    expect(outcome?.access).toBe('DRIVE_FILES')
    expect(outcome?.added).toBe(2)
    expect(outcome?.text).toBe(
      "2 files you chose are now on your list of files Brownie may read. Brownie reads a file's content only when you copy it into a document. " +
        '1 file was not added: Brownie reads only Google Docs and plain-text (.txt) files. ' +
        '1 file could not be opened in Google Drive, so it was not added. ' +
        '3 files could not be checked because Google Drive did not answer. Choose them again. ' +
        '4 files were not added: one pick adds at most 10 files. Choose the rest in another pick.',
    )
  })

  it('says one file in the singular, and leaves out every reason that did not happen', () => {
    expect(consentOutcome({ google: 'picked', added: '1', unsupported: '0', unavailable: '0', unchecked: '0', over_limit: '0' })?.text).toBe(
      "1 file you chose is now on your list of files Brownie may read. Brownie reads a file's content only when you copy it into a document.",
    )
    expect(consentOutcome({ google: 'picked', added: '0', unsupported: '2' })?.text).toBe(
      'None of the files you chose was put on your list of files Brownie may read. 2 files were not added: Brownie reads only Google Docs and plain-text (.txt) files.',
    )
  })

  it('says a pick that stopped partway as a problem, with what it added, why it stopped and what to do', () => {
    const refused = consentOutcome({ google: 'picked', added: '1', unchecked: '2', stopped: 'token_refused' })
    expect(refused?.tone).toBe('failure')
    expect(refused?.text).toBe(
      "1 file you chose is now on your list of files Brownie may read. Brownie reads a file's content only when you copy it into a document. " +
        "2 files were not checked. Google stopped accepting Brownie's access while your files were being added, so Google Drive needs " +
        'connecting again. Choose them again to connect it again.',
    )
    expect(consentOutcome({ google: 'picked', added: '0', unchecked: '3', stopped: 'blocked_by_organization' })?.text).toBe(
      'None of the files you chose was put on your list of files Brownie may read. 3 files were not checked: the organization that ' +
        'manages this Google account does not allow Brownie to read its Drive files.',
    )
    expect(consentOutcome({ google: 'picked', added: '0', unchecked: '1', stopped: 'not_configured' })?.text).toContain(
      "1 file was not checked: Brownie's connection to Google Drive is not set up correctly.",
    )
    expect(consentOutcome({ google: 'picked', added: '0', unchecked: '1', stopped: 'something_newer' })?.text).toContain(
      '1 file was not checked. Choose it again.',
    )
    expect(consentOutcome({ google: 'picked', added: '1', unchecked: '1' })?.tone, 'Drive not answering is not a stop').toBe('success')
  })

  it('says a pick with nothing chosen still connected Google Drive', () => {
    expect(consentOutcome({ google: 'picked', access: 'drive_files', added: '0', unsupported: '0' })?.text).toBe(
      "Google Drive is connected. Nothing was chosen in Google's file picker, so no file was added.",
    )
  })

  it('never shows a count that is not a small whole number, whatever the address holds', () => {
    const outcome = consentOutcome({ google: 'picked', added: '<b>9</b>', unsupported: ['1', '2'], unavailable: '-1', unchecked: '99999' })
    expect(outcome?.added).toBe(0)
    expect(outcome?.text).toBe("Google Drive is connected. Nothing was chosen in Google's file picker, so no file was added.")
  })

  it('names every parameter the answer puts in the address, so a page can take them all out', () => {
    expect(CONSENT_QUERY_KEYS).toEqual(
      expect.arrayContaining(['google', 'access', 'reason', 'picked', 'added', 'unsupported', 'unavailable', 'unchecked', 'over_limit', 'stopped']),
    )
  })

  it('has words for a pick that could not be recorded because Drive was disconnected meanwhile', () => {
    expect(consentOutcome({ google: 'failed', access: 'drive_files', reason: 'not_connected' })?.text).toBe(
      'Google Drive was disconnected while your files were being added, so none was added. Choose them again.',
    )
  })

  it('still reads the answer of an older server, which only counted what its picker test returned', () => {
    expect(consentOutcome({ google: 'connected', access: 'drive_files', picked: '3' })?.text).toContain('Google sent back 3 chosen files')
  })
})

describe('why a Drive file was not copied', () => {
  const cases: [ApiRequestError, string][] = [
    [failure(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'GONE'), 'Google Drive no longer has "Minutes", or no longer lets you open it, so it was taken off your list. Nothing was copied.'],
    [failure(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'ACCESS_LOST'), 'Brownie may no longer open "Minutes", so it was taken off your list. Choose it again in Google Drive to copy it.'],
    [failure(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'TRASHED'), '"Minutes" is in the trash in Google Drive. Take it out of the trash to copy it.'],
    [failure(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'DOWNLOAD_RESTRICTED'), 'Downloading and copying "Minutes" are turned off for you in Google Drive, so Brownie cannot copy it.'],
    [failure(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'CHANGED_DURING_COPY'), '"Minutes" changed in Google Drive while Brownie was copying it, so nothing was kept. Try again.'],
    [failure(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'SOMETHING_NEW'), '"Minutes" cannot be copied from Google Drive now. Nothing was copied.'],
    [failure(422, 'CONNECTOR_RESOURCE_UNSUPPORTED', 'TYPE'), 'Brownie copies only Google Docs and plain-text (.txt) files, so "Minutes" was not copied.'],
    [failure(422, 'CONNECTOR_RESOURCE_UNSUPPORTED', 'NOT_UTF8'), '"Minutes" is not saved as UTF-8, the only text encoding Brownie reads, so it was not copied.'],
    [failure(422, 'CONNECTOR_RESOURCE_REFUSED', 'MALWARE_DETECTED'), '"Minutes" was not copied: Brownie\'s checks refused it.'],
    [failure(413, 'CONNECTOR_RESOURCE_TOO_LARGE'), '"Minutes" is larger than Brownie accepts as a source, so it was not copied.'],
    [failure(404, 'CONNECTOR_RESOURCE_NOT_FOUND'), '"Minutes" is no longer on your list of files Brownie may read. Choose it again in Google Drive.'],
    [failure(404, 'NOT_FOUND'), 'This document is no longer available, so nothing was copied.'],
  ]

  it.each(cases)('says it in Brownie\'s own words (%#)', (error, expected) => {
    expect(describeDriveCopyFailure(error, 'Minutes')).toBe(expected)
  })

  it('names Google Drive, not the calendar, for a connection that is missing or must be made again', () => {
    expect(describeDriveCopyFailure(failure(404, 'CONNECTION_NOT_FOUND'), 'Minutes')).toBe(
      '"Minutes" was not copied. Google Drive is not connected. Choose files in Google Drive first.',
    )
    expect(describeDriveCopyFailure(failure(409, 'CONNECTOR_NOT_CONFIGURED'), 'Minutes')).toBe(
      '"Minutes" was not copied. Copying files from Google Drive is not offered on this Brownie at the moment.',
    )
  })

  it('never quotes a placeholder as if it were the file\'s name', () => {
    expect(describeDriveCopyFailure(failure(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'TRASHED'), null)).toBe(
      'That file is in the trash in Google Drive. Take it out of the trash to copy it.',
    )
    expect(describeDriveCopyFailure(failure(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'GONE'), '  ')).toBe(
      'Google Drive no longer has that file, or no longer lets you open it, so it was taken off your list. Nothing was copied.',
    )
    expect(mentionFile('Minutes')).toBe('"Minutes"')
    expect(mentionFile(undefined, true)).toBe('That file')
  })

  it('treats a server without the route as older than the page, not as a missing file', () => {
    const older = new ApiRequestError(404, {
      status: 404,
      title: 'Not Found',
      code: 'NOT_FOUND',
      detail: 'No static resource api/v1/workspaces/7/connections/google/drive/imports.',
      correlationId: 'c',
      fields: [],
      recoveryActions: [],
    })
    expect(describeDriveCopyFailure(older, 'Minutes')).not.toContain('no longer available')
    expect(describeDriveCopyFailure(older, 'Minutes')).toMatch(/^"Minutes" was not copied\. /)
  })
})

describe('connector failures, for Drive and for the calendar', () => {
  it('keeps the calendar sentences for callers that do not name the access', () => {
    expect(describeConnectorFailure(failure(404, 'CONNECTION_NOT_FOUND'), 'x')).toBe('Google Calendar is not connected. Connect it first.')
    expect(describeConnectorFailure(failure(413, 'CONNECTOR_RESOURCE_TOO_LARGE'), 'x')).toBe(
      "Google's answer was larger than Brownie reads. Choose fewer days.",
    )
    expect(describeConnectorFailure(failure(409, 'CONNECTOR_NOT_CONFIGURED'), 'x')).toBe('Connecting a Google account is not set up on this Brownie.')
  })

  it('says Drive when Drive is meant', () => {
    expect(describeConnectorFailure(failure(413, 'CONNECTOR_RESOURCE_TOO_LARGE'), 'x', 'DRIVE_FILES')).toBe(
      'That file is larger than Brownie accepts as a source.',
    )
    expect(describeConnectorFailure(failure(404, 'CONNECTOR_RESOURCE_NOT_FOUND'), 'x', 'DRIVE_FILES')).toBe(
      'That file is no longer on your list of files Brownie may read. Choose it again in Google Drive.',
    )
  })
})

describe("a Drive copy's origin", () => {
  it('says a Doc was copied as text, and where to open it', () => {
    const source = driveSource()
    expect(originSentence(source)).toMatch(/^Copied from Google Drive as text on .+, when it had last been changed there on .+\.$/)
    expect(originLink(source)).toBe('https://docs.google.com/document/d/abc/edit')
    expect(originLinkLabel(source)).toBe('Open in Google Drive')
  })

  it('says a text file was copied as it is', () => {
    const source = driveSource({
      displayFilename: 'notes.txt',
      origin: { provider: 'GOOGLE', title: 'notes.txt', link: null, modifiedAt: null, conversion: null },
    })
    expect(originSentence(source)).toMatch(/^Copied from Google Drive on [^.]+\.$/)
    expect(originSentence(source)).not.toContain('as text')
    expect(originSentence(source)).not.toContain('changed')
    expect(originLink(source)).toBeNull()
  })

  it('keeps the calendar wording for a calendar copy', () => {
    expect(originLinkLabel(driveSource({ kind: 'GOOGLE_CALENDAR' }))).toBe('Open in Google Calendar')
    expect(originLinkLabel(driveSource({ kind: 'SOMETHING_NEWER' as DocumentSourceResponse['kind'] }))).toBe('Open in Google')
  })
})

describe("what Brownie says it does with Drive", () => {
  it('says Drive is not used until this Brownie offers it, and what it does once it does', () => {
    expect(driveBrownieWords(false)).toBe(PERMISSION_WORDS.DRIVE_FILES.brownie)
    expect(driveBrownieWords(false)).toContain('Brownie cannot read files from Drive yet')
    expect(driveBrownieWords(true)).toContain('Brownie asks Google Drive what each one is and keeps its name')
    expect(driveBrownieWords(true)).toContain("reads a file's content only when you copy that file into a document")
    expect(driveBrownieWords(true)).toContain('Google has no read-only form of this permission')
    expect(driveBrownieWords(true)).not.toContain('cannot read files')
  })
})
