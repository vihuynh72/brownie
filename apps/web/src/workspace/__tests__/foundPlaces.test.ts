import { describe, expect, it } from 'vitest'
import { foundPlacesWords } from '@/workspace/foundPlaces'
import type { EditableField } from '@/workspace/layout'

function field(fieldId: string, overrides: Partial<EditableField> = {}): EditableField {
  return { fieldId, type: 'TEXT', cardinality: 'SCALAR', requiredness: null, origin: 'FOUND_BY_BROWNIE', ...overrides }
}

const column = (fieldId: string, overrides: Partial<EditableField> = {}) => field(fieldId, { cardinality: 'REPEATED', ...overrides })

describe('foundPlacesWords', () => {
  const FORM_ONLY = [field('title', { origin: 'FORM' }), field('date', { origin: 'FORM' })]
  const everyOne = (fields: EditableField[]) => new Set(fields.filter((item) => item.origin === 'FOUND_BY_BROWNIE').map((item) => item.fieldId))

  it('says how many places Brownie found, counting the table of rows as one', () => {
    const fields = [field('title'), field('org'), field('date'), field('place'), field('who'), field('decisions'), column('owner'), column('task'), column('due'), field('by')]

    expect(foundPlacesWords({ fields, unchecked: everyOne(fields), kind: 'DOCX' })).toBe(
      'Brownie found 8 places to fill in. Check the ones marked Found by Brownie, then keep them.',
    )
  })

  it('says "more" where the form had places of its own', () => {
    const fields = [...FORM_ONLY, field('place'), field('who'), field('decisions')]

    expect(foundPlacesWords({ fields, unchecked: everyOne(fields), kind: 'DOCX' })).toBe(
      'Brownie found 3 more places to fill in. Check the ones marked Found by Brownie, then keep them.',
    )
  })

  it('says one place in the singular, with its own way to tell it on a PDF', () => {
    const fields = [...FORM_ONLY, field('company')]

    expect(foundPlacesWords({ fields, unchecked: everyOne(fields), kind: 'PDF' })).toBe(
      'Brownie found 1 more place to fill in. Check the one with a dashed outline, then keep it.',
    )
  })

  it('says how many are left to check once some are kept, counting the table once', () => {
    const fields = [field('place'), field('who'), column('owner'), column('task')]

    expect(foundPlacesWords({ fields, unchecked: new Set(['who', 'owner', 'task']), kind: 'DOCX' })).toBe(
      '2 of the 3 places Brownie found are not checked yet. Check the ones marked Found by Brownie, then keep them.',
    )
    expect(foundPlacesWords({ fields, unchecked: new Set(['owner']), kind: 'DOCX' })).toBe(
      '1 of the 3 places Brownie found is not checked yet. Check the one marked Found by Brownie, then keep it.',
    )
  })

  it('is empty when nothing is left to check, or there is nothing Brownie found', () => {
    const fields = [field('place'), column('owner')]

    expect(foundPlacesWords({ fields, unchecked: new Set(), kind: 'DOCX' })).toBe('')
    expect(foundPlacesWords({ fields: FORM_ONLY, unchecked: new Set(), kind: 'DOCX' })).toBe('')
  })

  it('takes a place with no origin as one the form came with, and a place the person added as one it did not', () => {
    const found = field('place')

    expect(foundPlacesWords({ fields: [field('date', { origin: null }), found], unchecked: new Set(['place']), kind: 'DOCX' })).toBe(
      'Brownie found 1 more place to fill in. Check the one marked Found by Brownie, then keep it.',
    )
    expect(foundPlacesWords({ fields: [field('title', { origin: 'ADDED_BY_PERSON' }), found], unchecked: new Set(['place']), kind: 'DOCX' })).toBe(
      'Brownie found 1 place to fill in. Check the one marked Found by Brownie, then keep it.',
    )
  })
})
