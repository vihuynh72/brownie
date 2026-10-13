import { describe, expect, it } from 'vitest'
import { fieldStateWords } from '@/workspace/layout'
import { nextToReview, stateChipLook, type ReviewTarget } from '@/workspace/decisionPopover'

const spot = (fieldId: string, rowIndex: number | null = null): ReviewTarget => ({ fieldId, rowIndex })

const PAGE = [spot('title'), spot('date'), spot('place'), spot('task', 0), spot('task', 1)]

describe('nextToReview', () => {
  it('goes to the next spot down the page that is waiting for a decision', () => {
    const waiting = new Set(['date', 'task:1'])
    const wants = (target: ReviewTarget) => waiting.has(target.rowIndex === null ? target.fieldId : `${target.fieldId}:${target.rowIndex}`)

    expect(nextToReview(PAGE, spot('title'), wants)).toEqual(spot('date'))
    expect(nextToReview(PAGE, spot('date'), wants)).toEqual(spot('task', 1))
  })

  it('wraps from the last spot to the first', () => {
    const wants = (target: ReviewTarget) => target.fieldId === 'title' || target.fieldId === 'place'

    expect(nextToReview(PAGE, spot('task', 1), wants)).toEqual(spot('title'))
    expect(nextToReview(PAGE, spot('place'), wants)).toEqual(spot('title'))
  })

  it('never offers the spot it starts from, so the only one waiting leaves nowhere to go', () => {
    const wants = (target: ReviewTarget) => target.fieldId === 'date'

    expect(nextToReview(PAGE, spot('date'), wants)).toBeNull()
    expect(nextToReview(PAGE, spot('title'), wants)).toEqual(spot('date'))
  })

  it('is null when no spot is waiting, and for a page with no spots', () => {
    expect(nextToReview(PAGE, spot('title'), () => false)).toBeNull()
    expect(nextToReview([], null, () => true)).toBeNull()
  })

  it('starts from the top when nothing is selected, or the selected spot is no longer on the page', () => {
    expect(nextToReview(PAGE, null, () => true)).toEqual(spot('title'))
    expect(nextToReview(PAGE, spot('gone'), () => true)).toEqual(spot('title'))
  })

  it('tells the rows of a table apart', () => {
    expect(nextToReview(PAGE, spot('task', 0), () => true)).toEqual(spot('task', 1))
  })
})

describe('stateChipLook', () => {
  it('draws what passed in green with a tick, and what needs another look in honey or red with a mark', () => {
    expect(stateChipLook('Checks passed')).toEqual({ tone: 'success', icon: 'check' })
    expect(stateChipLook('Accepted')).toEqual({ tone: 'success', icon: 'check' })
    expect(stateChipLook('Needs clarification')).toEqual({ tone: 'honey', icon: 'help-circle' })
    expect(stateChipLook('Blocks export')).toEqual({ tone: 'error', icon: 'alert-circle' })
    expect(stateChipLook('Rejected')).toEqual({ tone: 'error', icon: 'close-circle' })
  })

  it('marks a value from Brownie with its sparkle and a cited source with a quotation mark', () => {
    expect(stateChipLook('From Brownie')).toEqual({ tone: 'honey', icon: 'sparkle' })
    expect(stateChipLook('Source cited, reworded')).toEqual({ tone: 'neutral', icon: 'quote' })
  })

  it('leaves a plain chip for a word it has no look for, such as "Not reviewed"', () => {
    expect(stateChipLook('Not reviewed')).toEqual({ tone: 'neutral', icon: null })
    expect(stateChipLook('Something the server added later')).toEqual({ tone: 'neutral', icon: null })
  })

  it('has a look of its own for every word a state can say apart from the plain ones', () => {
    const words = new Set<string>()
    for (const authorship of ['IMPORTED', 'AI_COMPOSED', 'USER_AUTHORED', 'MIXED'] as const) {
      for (const evidenceSupport of ['DIRECT', 'TRANSFORMED', 'AMBIGUOUS', 'UNSUPPORTED', 'MISSING'] as const) {
        for (const validation of ['NOT_RUN', 'PASSED', 'WARNING', 'BLOCKING', 'UNAVAILABLE'] as const) {
          for (const review of ['UNREVIEWED', 'ACCEPTED', 'REJECTED', 'NEEDS_CLARIFICATION'] as const) {
            for (const lock of ['EDITABLE', 'PRESERVE_ON_REGENERATION', 'EXPLICITLY_LOCKED'] as const) {
              for (const word of fieldStateWords({ authorship, evidenceSupport, validation, review, lock })) words.add(word)
            }
          }
        }
      }
    }
    // Every one of them is either drawn with a picture or a plain chip on purpose, never an accident of spelling.
    const plain = ['Imported', 'No source cited', 'Not checked', 'Not reviewed']
    for (const word of words) {
      if (plain.includes(word)) expect(stateChipLook(word).icon, word).toBeNull()
      else expect(stateChipLook(word).icon, word).not.toBeNull()
    }
  })
})
