import { describe, expect, it } from 'vitest'
import type { RulePayloadRequest, RuleScopeRequest } from '@/api/client'
import { describePayload, describeScope } from '@/rules/describeRule'

/** What a newer server can send: a value this page's types do not list yet. */
function fromNewerServer(payload: Record<string, unknown>): RulePayloadRequest {
  return payload as unknown as RulePayloadRequest
}

describe('describePayload', () => {
  it('keeps the wording people already read for the ordinary rules', () => {
    expect(describePayload({ kind: 'REQUIRED_FIELDS', fieldIds: ['meeting.title', 'meeting.date'] })).toBe(
      'Require: meeting.title, meeting.date',
    )
    expect(describePayload({ kind: 'MAX_TEXT_LENGTH', fieldId: 'meeting.title', maxCharacters: 500 })).toBe(
      'meeting.title: at most 500 characters',
    )
    expect(describePayload({ kind: 'MAX_ITEM_COUNT', fieldId: 'attendees', maxItems: 12 })).toBe('attendees: at most 12 items')
    expect(describePayload({ kind: 'ALLOWED_SECTION_ORDER', orderedSectionIds: ['intro', 'body'] })).toBe(
      'Section order: intro → body',
    )
    expect(describePayload({ kind: 'DATE_DISPLAY_FORMAT', fieldId: 'meeting.date', dateFormatStyle: 'SHORT' })).toBe(
      'meeting.date: show dates as short (9/10/2026)',
    )
    expect(describePayload({ kind: 'MISSING_VALUE_BEHAVIOR', fieldId: 'notes', emptyValueResolution: 'OMIT' })).toBe(
      'notes: when empty, omit it',
    )
    expect(describePayload({ kind: 'ALLOWED_OVERFLOW_BEHAVIOR', fieldId: 'notes', overflowResolution: 'ALLOW_REFLOW' })).toBe(
      'notes: on overflow, allow the layout to reflow',
    )
    expect(
      describePayload({ kind: 'REPEATABLE_REGION_EMPTY_BEHAVIOR', fieldId: 'attendees', emptyValueResolution: 'PLACEHOLDER_TEXT' }),
    ).toBe('attendees: when no items, show placeholder text')
    expect(
      describePayload({ kind: 'PROTECTED_REGION', protectedRegionTarget: { kind: 'CONTENT_CONTROL_TAG', tag: 'signature-block' } }),
    ).toBe('Protect content control "signature-block" from edits')
  })

  it('says "1 character" and "1 item", not "1 characters" and "1 items"', () => {
    expect(describePayload({ kind: 'MAX_TEXT_LENGTH', fieldId: 'initials', maxCharacters: 1 })).toBe(
      'initials: at most 1 character',
    )
    expect(describePayload({ kind: 'MAX_ITEM_COUNT', fieldId: 'chair', maxItems: 1 })).toBe('chair: at most 1 item')
  })

  it('names where a value may come from in words, not as the server code', () => {
    const text = describePayload({ kind: 'ALLOWED_SOURCE_KINDS', fieldId: 'summary', allowedSourceKinds: ['ARTIFACT'] })

    expect(text).toBe('summary: only allow values from uploaded files')
    expect(text).not.toMatch(/artifact/i)
  })

  /** Leaving an unknown kind out would describe a narrower rule than the one that applies, so none is named. */
  it('does not name sources when the list is missing, empty or holds a kind this page has no words for', () => {
    for (const allowedSourceKinds of [undefined, null, [], ['ARTIFACT', 'CONNECTOR']]) {
      const text = describePayload(fromNewerServer({ kind: 'ALLOWED_SOURCE_KINDS', fieldId: 'summary', allowedSourceKinds }))

      expect(text).toBe('summary: limit where its values may come from')
    }
  })

  it('never shows "undefined" or a server code for a style or resolution this page does not know', () => {
    const fromNewer = [
      [{ kind: 'DATE_DISPLAY_FORMAT', fieldId: 'meeting.date', dateFormatStyle: 'RELATIVE' }, 'meeting.date: show dates in one set format'],
      [{ kind: 'DATE_DISPLAY_FORMAT', fieldId: 'meeting.date' }, 'meeting.date: show dates in one set format'],
      [{ kind: 'MISSING_VALUE_BEHAVIOR', fieldId: 'notes', emptyValueResolution: 'ASK' }, 'notes: a set way to handle it when empty'],
      [{ kind: 'ALLOWED_OVERFLOW_BEHAVIOR', fieldId: 'notes', overflowResolution: 'SHRINK' }, 'notes: a set way to handle overflow'],
      [
        { kind: 'REPEATABLE_REGION_EMPTY_BEHAVIOR', fieldId: 'attendees', emptyValueResolution: 'ASK' },
        'attendees: a set way to handle having no items',
      ],
      [{ kind: 'SOMETHING_NEW', fieldId: 'notes' }, 'A kind of rule this page cannot describe'],
    ] as const

    for (const [payload, expected] of fromNewer) {
      const text = describePayload(fromNewerServer(payload))

      expect(text).toBe(expected)
      expect(text).not.toMatch(/undefined|null|RELATIVE|ASK|SHRINK|SOMETHING_NEW/)
    }
  })

  it('describes a protected region that has no content control tag without an empty pair of quotes', () => {
    for (const protectedRegionTarget of [
      { kind: 'STRUCTURAL_NODE', part: 'word/document.xml', nodeId: 'p-12' },
      { kind: 'CONTENT_CONTROL_TAG', tag: '   ' },
      null,
    ]) {
      const text = describePayload(fromNewerServer({ kind: 'PROTECTED_REGION', protectedRegionTarget }))

      expect(text).toBe('Protect a region of the template from edits')
    }
  })

  it('leaves out a count, a field or a list that is missing instead of printing it', () => {
    expect(describePayload(fromNewerServer({ kind: 'MAX_TEXT_LENGTH', fieldId: 'notes' }))).toBe('notes: a length limit')
    expect(describePayload(fromNewerServer({ kind: 'MAX_ITEM_COUNT', fieldId: 'attendees', maxItems: 0 }))).toBe(
      'attendees: a limit on how many items',
    )
    expect(describePayload(fromNewerServer({ kind: 'MAX_ITEM_COUNT', maxItems: 3 }))).toBe('At most 3 items')
    expect(describePayload(fromNewerServer({ kind: 'REQUIRED_FIELDS' }))).toBe('Require fields to be filled in')
    expect(describePayload(fromNewerServer({ kind: 'ALLOWED_SECTION_ORDER', orderedSectionIds: [] }))).toBe(
      'Keep sections in a set order',
    )
  })
})

describe('describeScope', () => {
  it('names the field a rule applies to, and never prints a missing one', () => {
    expect(describeScope({ kind: 'WHOLE_TEMPLATE' })).toBe('Whole template')
    expect(describeScope({ kind: 'SINGLE_FIELD', fieldId: 'meeting.title' })).toBe('Field: meeting.title')
    expect(describeScope({ kind: 'SINGLE_FIELD' } as RuleScopeRequest)).toBe('One field')
  })
})
