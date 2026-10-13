import { afterEach, describe, expect, it } from 'vitest'
import { documentHandoffState, forgetDocumentRequest, forgetFormNotes, readDocumentHandoff } from '@/router/handoff'

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

  describe('a request made in the chat', () => {
    const request = { text: 'Make minutes for Tuesday’s meeting from my transcript', sourceId: 12 }

    it('is read back with its sentence and the source to read, beside everything else in the handoff', () => {
      window.history.replaceState(documentHandoffState({ attachedSources: [], sourceWarning: null, formNotes: [], request }), '')

      expect(readDocumentHandoff()).toEqual({ attachedSources: [], sourceWarning: null, formNotes: [], request })
    })

    it('has no source to read when it came with none', () => {
      window.history.replaceState(
        documentHandoffState({ attachedSources: [], sourceWarning: null, formNotes: [], request: { text: 'Start the minutes', sourceId: null } }),
        '',
      )

      expect(readDocumentHandoff()?.request).toEqual({ text: 'Start the minutes', sourceId: null })
    })

    it('is left out of a handoff that has none, so the handoff reads exactly as it did before requests existed', () => {
      window.history.replaceState({ documentHandoff: { attachedSources: [], sourceWarning: null, formNotes: [] } }, '')

      const handoff = readDocumentHandoff()

      expect(handoff).toEqual({ attachedSources: [], sourceWarning: null, formNotes: [] })
      expect(handoff).not.toHaveProperty('request')
    })

    it('is ignored when it is not a sentence, and a source that is not a whole number is no source', () => {
      for (const bad of [null, 'Make minutes', 7, { text: 5, sourceId: 1 }, { text: '   ', sourceId: 1 }, { sourceId: 1 }]) {
        window.history.replaceState({ documentHandoff: { attachedSources: [], sourceWarning: null, formNotes: [], request: bad } }, '')
        expect(readDocumentHandoff()).not.toHaveProperty('request')
      }

      window.history.replaceState({ documentHandoff: { request: { text: 'Fill it in', sourceId: '12' } } }, '')
      expect(readDocumentHandoff()?.request).toEqual({ text: 'Fill it in', sourceId: null })
      window.history.replaceState({ documentHandoff: { request: { text: 'Fill it in', sourceId: 1.5 } } }, '')
      expect(readDocumentHandoff()?.request).toEqual({ text: 'Fill it in', sourceId: null })
    })

    it('stays when the form notes are dismissed', () => {
      window.history.replaceState(
        documentHandoffState({ attachedSources: [], sourceWarning: null, formNotes: ['A note.'], request }),
        '',
      )

      forgetFormNotes()

      expect(readDocumentHandoff()).toEqual({ attachedSources: [], sourceWarning: null, formNotes: [], request })
    })

    it('is forgotten once used, leaving the sources, the warning, the notes and the router’s own state as they were', () => {
      const source = { id: 12, artifactId: 5, kind: 'ARTIFACT', fetchedAt: '2026-10-07T22:58:00Z' }
      window.history.replaceState(
        { position: 4, current: '/documents/9', documentHandoff: { attachedSources: [source], sourceWarning: 'Not attached.', formNotes: ['A note.'], request } },
        '',
      )

      forgetDocumentRequest()

      expect(window.history.state).toEqual({
        position: 4,
        current: '/documents/9',
        documentHandoff: { attachedSources: [source], sourceWarning: 'Not attached.', formNotes: ['A note.'] },
      })
      expect(readDocumentHandoff()).not.toHaveProperty('request')
      expect(window.location.pathname).toBe('/')
    })

    it('leaves a history entry with no handoff, or a handoff with no request, alone', () => {
      window.history.replaceState({ position: 2 }, '')
      forgetDocumentRequest()
      expect(window.history.state).toEqual({ position: 2 })

      window.history.replaceState({ position: 3, documentHandoff: { attachedSources: [], sourceWarning: null, formNotes: [] } }, '')
      forgetDocumentRequest()
      expect(window.history.state).toEqual({ position: 3, documentHandoff: { attachedSources: [], sourceWarning: null, formNotes: [] } })
    })
  })
})
