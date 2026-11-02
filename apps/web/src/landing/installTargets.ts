/**
 * The places a person can use Brownie from, and what each one asks of them: nothing for the browser, Brownie's server
 * address for a chat app's connector screen, or one command for a terminal agent. The address comes from the
 * deployment itself (the welcome answer), so the page never shows a server it is not.
 */
import type { IconName } from '@/components/icons'

export type InstallTargetId = 'browser' | 'claude' | 'chatgpt' | 'claude-code' | 'codex'

export interface InstallTarget {
  id: InstallTargetId
  label: string
  icon: IconName
  /** What the copy button copies: a terminal command, the server address, or nothing when there is nothing to install. */
  copies: 'command' | 'address' | null
  /** The line shown large and copied as it is. */
  line: string | null
  /** The same line in the pieces it is set in, so a long command breaks where a reader expects rather than mid-word. */
  lineParts: string[]
  steps: string[]
}

/** Every place Brownie can be used from, in the order a newcomer is most likely to want them. */
export function installTargets(mcpServerUrl: string | null): InstallTarget[] {
  const browser: InstallTarget = {
    id: 'browser',
    label: 'Browser',
    icon: 'document-upload',
    copies: null,
    line: null,
    lineParts: [],
    steps: [
      'Sign in with a code sent to your email. There is no password to make up.',
      'Drop in a form: Word, PDF, Pages, OpenDocument, RTF, or a photo of the paper.',
      'Type or say your answers, approve each value, then export.',
    ],
  }
  if (!mcpServerUrl) return [browser]
  return [
    browser,
    {
      id: 'claude',
      label: 'Claude',
      icon: 'chat',
      copies: 'address',
      line: mcpServerUrl,
      lineParts: [mcpServerUrl],
      steps: [
        'In Claude, open Settings, then Connectors.',
        'Choose Add custom connector, name it Brownie, and paste this address.',
        'Ask Claude to fill a form. It gives you a one-time link to upload the file.',
      ],
    },
    {
      id: 'chatgpt',
      label: 'ChatGPT',
      icon: 'chat',
      copies: 'address',
      line: mcpServerUrl,
      lineParts: [mcpServerUrl],
      steps: [
        'In ChatGPT, open Plugins, then Add, then Add custom MCP server.',
        'Name it Brownie, paste this address as the server URL, and keep OAuth.',
        'Attach a form and tell ChatGPT what to fill.',
      ],
    },
    {
      id: 'claude-code',
      label: 'Claude Code',
      icon: 'terminal',
      copies: 'command',
      line: `claude mcp add --transport http brownie ${mcpServerUrl}`,
      lineParts: ['claude mcp add --transport http brownie', mcpServerUrl],
      steps: [
        'Run this in your terminal.',
        'In Claude Code, type /mcp and sign in to Brownie.',
        'Ask it to fill a form. Brownie gives it a one-time link for the file.',
      ],
    },
    {
      id: 'codex',
      label: 'Codex',
      icon: 'terminal',
      copies: 'command',
      line: `codex mcp add brownie --url ${mcpServerUrl}`,
      lineParts: ['codex mcp add brownie --url', mcpServerUrl],
      steps: [
        'Run this in your terminal.',
        'Then run codex mcp login brownie and sign in.',
        'Ask Codex to fill a form. Brownie gives it a one-time link for the file.',
      ],
    },
  ]
}
