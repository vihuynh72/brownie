import { afterEach, describe, expect, it } from 'vitest'
import { documentHandoffState, forgetFormNotes, readDocumentHandoff } from '@/router/handoff'

describe('the handoff to a new document', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/')
  })

  it('carries the notes about an uploaded form as sentences, and nothing else in their place', () => {
    window.history.replaceState(
      { ...documentHandoffState({ attachedSources: [], sourceWarning: null, formNotes: ['Brownie found 2 places to fill in.'] }) },
      '',
    )
    expect(readDocumentHandoff()).toEqual({ attachedSources: [], sourceWarning: null, formNotes: ['Brownie found 2 places to fill in.'] })

    window.history.replaceState({ documentHandoff: { attachedSources: [], sourceWarning: null, formNotes: ['A note.', 7, '', null] } }, '')
    expect(readDocumentHandoff()?.formNotes).toEqual(['A note.'])
  })

  it('reads a handoff written before there were notes as one with none', () => {
    window.history.replaceState({ documentHandoff: { attachedSources: [], sourceWarning: 'Not attached.' } }, '')

    expect(readDocumentHandoff()).toEqual({ attachedSources: [], sourceWarning: 'Not attached.', formNotes: [] })
  })

  it("forgets the notes once dismissed, leaving the rest of the handoff and the router's own state as they were", () => {
    window.history.replaceState(
      { position: 4, current: '/documents/9', documentHandoff: { attachedSources: [], sourceWarning: 'Not attached.', formNotes: ['A note.'] } },
      '',
    )

    forgetFormNotes()

    expect(window.history.state).toEqual({
      position: 4,
      current: '/documents/9',
      documentHandoff: { attachedSources: [], sourceWarning: 'Not attached.', formNotes: [] },
    })
    expect(window.location.pathname).toBe('/')
  })

  it('leaves a history entry with no handoff alone', () => {
    window.history.replaceState({ position: 2 }, '')

    forgetFormNotes()

    expect(window.history.state).toEqual({ position: 2 })
  })
})
