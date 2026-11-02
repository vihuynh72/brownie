import { describe, expect, it } from 'vitest'
import { installTargets } from '@/landing/installTargets'

const URL = 'https://api.example.com/mcp'

describe('installTargets', () => {
  it('lists the five places Brownie can be used, browser first', () => {
    const targets = installTargets(URL)

    expect(targets.map((t) => t.id)).toEqual(['browser', 'claude', 'chatgpt', 'claude-code', 'codex'])
  })

  it('writes the exact commands the two terminal agents need', () => {
    const targets = installTargets(URL)
    const claudeCode = targets.find((t) => t.id === 'claude-code')!
    const codex = targets.find((t) => t.id === 'codex')!

    expect(claudeCode.line).toBe(`claude mcp add --transport http brownie ${URL}`)
    expect(codex.line).toBe(`codex mcp add brownie --url ${URL}`)
  })

  it('gives the two chat apps the server address to paste', () => {
    const targets = installTargets(URL)
    const claude = targets.find((t) => t.id === 'claude')!
    const chatgpt = targets.find((t) => t.id === 'chatgpt')!

    expect(claude.line).toBe(URL)
    expect(chatgpt.line).toBe(URL)
    expect(claude.copies).toBe('address')
    expect(chatgpt.copies).toBe('address')
  })

  it('offers only the browser when the deployment has no chat-app address', () => {
    const targets = installTargets(null)

    expect(targets.map((t) => t.id)).toEqual(['browser'])
    expect(targets[0]!.copies).toBeNull()
    expect(targets[0]!.line).toBeNull()
  })
})
