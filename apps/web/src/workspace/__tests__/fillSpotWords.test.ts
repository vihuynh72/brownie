import { describe, expect, it } from 'vitest'
import { ApiRequestError } from '@/api/client'
import {
  addedWords,
  movedWords,
  removeQuestion,
  removedWords,
  renamedWords,
  spotLabelProblem,
  spotRefusal,
  tidySpotLabel,
  undoneWords,
  versionDifferenceWords,
  workingWords,
} from '@/workspace/fillSpotWords'

function refusal(status: number, code: string, extra: Record<string, unknown> = {}): ApiRequestError {
  return new ApiRequestError(status, { status, title: 'Refused', code, detail: 'The server says why.', correlationId: 'c', fields: [], recoveryActions: [], ...extra })
}

const labelOf = (fieldId: string) => ({ company: 'Company', fax: 'Fax' })[fieldId] ?? fieldId

describe('a fill spot name', () => {
  it('is one line of 1 to 60 characters with a letter or a number', () => {
    expect(spotLabelProblem('Company')).toBeNull()
    expect(spotLabelProblem('   ')).toBe('Give the fill spot a name.')
    expect(spotLabelProblem('a'.repeat(61))).toBe('Keep the name to 60 characters or fewer.')
    expect(spotLabelProblem(`${String.fromCodePoint(0x1f600).repeat(60)}x`)).toBe('Keep the name to 60 characters or fewer.')
    expect(spotLabelProblem('---')).toBe('Use at least one letter or number in the name.')
    expect(spotLabelProblem('Two\nlines')).toBe('Keep the name on one line.')
    expect(spotLabelProblem('Ng\u00e0y sinh')).toBeNull()
  })

  it('is sent tidied', () => {
    expect(tidySpotLabel('  Company \u00a0 name ')).toBe('Company name')
    expect(tidySpotLabel('Cafe\u0301')).toBe('Caf\u00e9')
  })
})

describe('what the page says while and after a change', () => {
  it('says the form is being checked while a spot is added or removed', () => {
    expect(workingWords('add')).toBe('Adding the fill spot… Brownie is checking the form still prints correctly.')
    expect(workingWords('remove')).toBe('Removing the fill spot… Brownie is checking the form still prints correctly.')
    expect(workingWords('rename')).toBe('Renaming the fill spot…')
    expect(workingWords('change')).toBe('Changing the fill spot… Brownie is checking the form still prints correctly.')
  })

  it('says what Undo took back, a moved or resized box as a change to its spot', () => {
    expect(undoneWords('add', 'Company')).toBe('Undone: Company is no longer a fill spot.')
    expect(undoneWords('remove', 'Company')).toBe('Undone: the fill spot Company is back.')
    expect(undoneWords('change', 'Company')).toBe('Undone: the change to Company is taken back.')
  })

  it('says what was done, and that new documents follow', () => {
    expect(addedWords('Company', 'after "Company:"')).toBe('Added a fill spot for Company after "Company:". New documents from this form will have it too.')
    expect(addedWords('Company', 'after "Company:"', 2)).toContain('2 other documents from this form keep the earlier version until you move them.')
    expect(renamedWords('Company', 'Employer', 1)).toBe(
      'Renamed the fill spot Company to Employer. New documents from this form will use the new name too. One other document from this form keeps the earlier version until you move it.',
    )
    expect(removedWords('Fax')).toBe('Removed the fill spot Fax. Its value stays in the version history. New documents from this form will not have it.')
  })

  it('asks before a spot is removed, and says the form keeps its own box', () => {
    expect(removeQuestion('Company', true, false)).toEqual(['Remove the fill spot Company? Its value stays in the version history.'])
    expect(removeQuestion('Company', false, true)).toEqual(['Remove the fill spot Company?', 'The form keeps its own box; Brownie just stops filling it.'])
  })

  it('names the values a move to the newest version dropped', () => {
    expect(movedWords([])).toBe('Moved this document to the newest version of its form.')
    expect(movedWords(['Fax'])).toBe(
      'Moved this document to the newest version of its form. Fax has no fill spot in it, so its value was dropped. It stays in the version history.',
    )
    expect(movedWords(['Fax', 'Pager', 'Telex'])).toContain('Fax, Pager and Telex have no fill spot in it, so their values were dropped.')
  })
})

describe('why a change was refused', () => {
  const words = (error: unknown, action: 'add' | 'rename' | 'remove' | 'move' = 'add') => spotRefusal(error, action, labelOf)

  it('asks for the place again when the page changed', () => {
    expect(words(refusal(409, 'FILL_SPOT_ANCHOR_STALE'))).toEqual({ message: 'The page changed; select the place again.', reload: true, staleRevision: false })
    expect(words(refusal(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason: 'NOT_FOUND' }))).toMatchObject({ message: 'The page changed; select the place again.', reload: true })
  })

  it('says why the place cannot take a spot, reason by reason', () => {
    const place = (reason: string) => words(refusal(422, 'FILL_SPOT_PLACE_NOT_ALLOWED', { reason })).message
    expect(place('HEADER_FOOTER')).toBe('Brownie fills only the body of the form, not its header or footer.')
    expect(place('REPEATING_REGION')).toBe('This part of the form repeats for each row, so a single fill spot cannot go here.')
    expect(place('INSIDE_LINK')).toBe('Brownie cannot put a fill spot inside a link. Choose a place next to it.')
    expect(place('INSIDE_FIELD_CODE')).toBe('Brownie cannot put a fill spot inside a page number or another Word field.')
    expect(place('PROTECTED')).toBe('That part of the form is kept as it is, so a fill spot cannot go there. Choose a place next to it.')
    expect(place('SOMETHING_NEW')).toBe('The server says why.')
  })

  it('reloads when the form or the document moved on', () => {
    expect(words(refusal(409, 'TEMPLATE_VERSION_MOVED_ON'))).toEqual({
      message: 'This form changed while you were working, so it was reloaded. Try again.',
      reload: true,
      staleRevision: false,
    })
    expect(words(refusal(412, 'STALE_REVISION'))).toMatchObject({ reload: true, staleRevision: true })
    expect(words(refusal(409, 'DOCUMENT_TEMPLATE_VERSION_MOVED')).message).toBe(
      'This document is on an older version of its form. Move it to the newest version first, then try again.',
    )
  })

  it('names a locked value that would be lost', () => {
    expect(words(refusal(409, 'FILL_SPOT_LOCKED', { fieldId: 'fax' }), 'remove').message).toBe('Unlock Fax first; its value would be lost.')
    expect(words(refusal(409, 'FILL_SPOT_LOCKED', { fieldId: 'company' }), 'move').message).toBe('Unlock Company first; its value would be lost.')
  })

  it('says nothing changed when the form would not print correctly', () => {
    expect(words(refusal(422, 'FILL_SPOT_WOULD_NOT_PRINT')).message).toBe(
      'Brownie could not add the fill spot, because the form would not print correctly with it. Nothing was changed.',
    )
    expect(words(refusal(422, 'FILL_SPOT_WOULD_NOT_PRINT'), 'remove').message).toBe(
      'Brownie could not remove the fill spot, because the form would not print correctly without it. Nothing was changed.',
    )
  })

  it("uses the server's words for a change it refused as not one, and the common words for the rest", () => {
    expect(words(refusal(422, 'FILL_SPOT_CHANGE_INVALID')).message).toBe('The server says why.')
    expect(words(new TypeError('Failed to fetch')).message).toBe('Brownie could not be reached. Check your connection, then try again.')
    expect(words(refusal(404, 'NOT_FOUND')).message).toBe(
      'That was not done: this document is no longer available, for example because it was moved to the trash.',
    )
    expect(words(new ApiRequestError(500, undefined)).message).toBe('Brownie could not be reached. It may not be running; try again in a minute.')
  })
})

describe('how a newer version of the form differs', () => {
  const current = [{ fieldId: 'name' }, { fieldId: 'fax', label: 'Fax' }, { fieldId: 'phone', label: 'Phone' }]

  it('names the spots it adds, takes away and renames, by field', () => {
    expect(versionDifferenceWords(current, [...current, { fieldId: 'company', label: 'Company' }])).toBe('It adds the fill spot "Company".')
    expect(versionDifferenceWords(current, [{ fieldId: 'name' }, { fieldId: 'phone', label: 'Mobile' }, { fieldId: 'a', label: 'A' }, { fieldId: 'b', label: 'B' }])).toBe(
      'It adds the fill spots "A" and "B", takes away "Fax" and renames "Phone" to "Mobile".',
    )
  })

  it('says nothing when no spot changed', () => {
    expect(versionDifferenceWords(current, current)).toBe('')
  })
})
