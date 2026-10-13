import { describe, expect, it } from 'vitest'
import { suggestTemplates } from '@/chat/templateMatch'

function named(displayName: string, createdAt = '2026-09-21T10:05:00Z') {
  return { displayName, createdAt }
}

const FLOWING = named('Flowing meeting minutes', '2026-09-21T10:05:00Z')
const TABLE_LED = named('Table-led meeting minutes', '2026-09-21T10:05:01Z')
const CLUB = named('Club equipment request', '2026-10-07T15:54:00Z')

function names(request: string, list: Parameters<typeof suggestTemplates>[1]): string[] {
  return suggestTemplates(request, list).ranked.map((template) => template.displayName)
}

describe('suggesting a template for a request', () => {
  it('puts first the template whose name shares the most words with the request', () => {
    const suggestion = suggestTemplates('I need the equipment request for the club', [FLOWING, TABLE_LED, CLUB])

    expect(suggestion.ranked[0]).toBe(CLUB)
    expect(suggestion.certainty).toBe('clear')
  })

  it('does not mind capitals, and counts a plural and its singular as one word', () => {
    expect(names('MINUTE for the TABLE-LED one', [CLUB, FLOWING, TABLE_LED])[0]).toBe('Table-led meeting minutes')
    expect(names('equipment requests', [FLOWING, CLUB])[0]).toBe('Club equipment request')
    expect(names('every policy we have', [named('Club policies'), FLOWING])[0]).toBe('Club policies')
    expect(names('the boxes we shipped', [FLOWING, named('Box inventory')])[0]).toBe('Box inventory')
  })

  it('reads an apostrophe-s and a typographic apostrophe as part of the word before it', () => {
    expect(names('the robotics club’s equipment', [FLOWING, CLUB])[0]).toBe('Club equipment request')
    expect(names("Tuesday's minutes", [CLUB, named('Tuesday notes'), FLOWING])[0]).toBe('Tuesday notes')
  })

  it('ignores the words of the request itself, which every request has', () => {
    // Nothing but "make", "a", "new", "form", "for" and "me" is shared, and none of those says what the document is.
    const suggestion = suggestTemplates('Make a new form for me', [named('New form for me'), FLOWING])

    expect(suggestion.certainty).toBe('none')
  })

  it('keeps templates that fit equally well in the order they were given, and says it was a toss-up', () => {
    // Both minutes templates share "meeting" and "minutes" with this request; the club one shares nothing.
    const suggestion = suggestTemplates('Make minutes for Tuesday’s robotics meeting from my transcript', [FLOWING, TABLE_LED, CLUB])

    expect(suggestion.ranked).toEqual([FLOWING, TABLE_LED, CLUB])
    expect(suggestion.certainty).toBe('tied')
  })

  it('orders the rest after the best by how much they share, then by the order given', () => {
    const three = [named('Meeting'), named('Meeting minutes agenda'), named('Minutes')]

    expect(names('meeting minutes', three)).toEqual(['Meeting minutes agenda', 'Meeting', 'Minutes'])
  })

  it('counts a word once however often the request or the name repeats it', () => {
    const suggestion = suggestTemplates('minutes minutes minutes and more minutes', [named('Minutes of minutes'), named('Budget minutes notes')])

    // One shared word each, so a tie, not a win for the name that says "minutes" twice.
    expect(suggestion.certainty).toBe('tied')
    expect(suggestion.ranked[0]!.displayName).toBe('Minutes of minutes')
  })

  it('falls back on the newest template when no name shares a word, and says it had nothing to go on', () => {
    const suggestion = suggestTemplates('Something unrelated', [FLOWING, CLUB, TABLE_LED])

    expect(suggestion.ranked.map((template) => template.displayName)).toEqual([
      'Club equipment request',
      'Table-led meeting minutes',
      'Flowing meeting minutes',
    ])
    expect(suggestion.certainty).toBe('none')
  })

  it('treats a date that cannot be read as the oldest, and keeps equal dates in the order given', () => {
    const unreadable = named('Unreadable date', 'not a date')
    const same = [named('First', '2026-01-01T00:00:00Z'), named('Second', '2026-01-01T00:00:00Z')]

    expect(names('anything', [unreadable, FLOWING])).toEqual(['Flowing meeting minutes', 'Unreadable date'])
    expect(names('anything', same)).toEqual(['First', 'Second'])
  })

  it('gives an empty request nothing to go on, and an empty list nothing to rank', () => {
    expect(suggestTemplates('', [FLOWING, CLUB]).certainty).toBe('none')
    expect(suggestTemplates('   ', [FLOWING, CLUB]).ranked[0]).toBe(CLUB)
    expect(suggestTemplates('meeting minutes', [])).toEqual({ ranked: [], certainty: 'none' })
  })

  it('leaves the list it was given as it was', () => {
    const list = [FLOWING, TABLE_LED, CLUB]

    suggestTemplates('equipment', list)

    expect(list).toEqual([FLOWING, TABLE_LED, CLUB])
  })

  it('matches names in any script, and numbers', () => {
    expect(names('Rapport 2026 pour le café', [named('Budget'), named('Rapport café 2026')])[0]).toBe('Rapport café 2026')
  })
})
