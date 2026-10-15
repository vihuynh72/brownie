import type { McpGrantResponse, McpScope, McpToolCallResponse } from '@/api/client'

/**
 * The wording every place that speaks about chat apps shares: what each
 * scope lets an app do, what one credential is called, what a recorded
 * call reads as in a document's history, and what a refusal's code means
 * in a sentence. Kept out of the views so the history and the panel never
 * drift on a word.
 */

export const MCP_SCOPE_WORDS: Record<McpScope, string> = {
  read: 'See your templates and documents',
  fill: 'Fill documents, with your confirmation',
  export: 'Export documents',
}

/** "See your templates and documents, fill documents, with your confirmation": the scopes of a grant, in words. */
export function mcpScopeList(scopes: readonly McpScope[]): string {
  return scopes.map((scope) => MCP_SCOPE_WORDS[scope] ?? scope).join(', ')
}

/** A pasted credential is "Token"; one the chat app got by signing in itself is "Connected app". */
export function mcpGrantKindWords(kind: McpGrantResponse['kind']): string {
  return kind === 'PERSONAL_TOKEN' ? 'Token' : 'Connected app'
}

/** What a refusal code means, for "<name>'s request was refused: <reason>". */
export function mcpRefusalReason(code: string | null | undefined): string {
  switch (code) {
    case 'PAYLOAD_CHANGED':
      return 'the proposal changed after it was shown'
    case 'PROPOSAL_NOT_OPEN':
      return 'the proposal was already settled'
    case 'PROPOSAL_PENDING':
      return 'a proposal was still waiting'
    case 'UNREVIEWED_FIELDS':
      return 'some proposed values were never confirmed'
    case 'VALIDATION_BLOCKING':
      return 'a check blocks export'
    case 'NOT_FOUND':
      return 'something it named was not there'
    case 'INVALID_ARGUMENT':
      return 'it asked in a way Brownie could not use'
    case 'INSUFFICIENT_SCOPE':
      return 'it was not allowed to do that'
    case 'CONFLICT':
      return 'the document changed in the meantime'
    default:
      return 'Brownie refused it'
  }
}

/** The sentence one recorded call reads as in a document's history, naming the grant that made it. */
export function mcpCallSentence(call: McpToolCallResponse, grantName: string | null): string {
  const name = grantName ?? 'A chat app'
  if (call.outcome === 'REFUSED') {
    return `${name}'s request was refused: ${mcpRefusalReason(call.code)}`
  }
  if (call.outcome === 'ERROR') {
    return `${name}'s request failed`
  }
  switch (call.tool) {
    case 'start_document':
      return `${name} started this document`
    case 'add_text_source':
      return `${name} added a text source`
    case 'propose_fill':
      return `${name} asked Brownie to propose values`
    case 'answer_question':
      return `${name} answered a question`
    case 'confirm_fill':
      return `Confirmed in ${name}`
    case 'export_document':
      return `${name} exported it`
    case 'get_document':
      return `${name} read it`
    case 'get_export_link':
      return `${name} asked for a download link`
    case 'get_fill_status':
      return `${name} asked how a proposal was doing`
    case 'list_templates':
      return `${name} listed your templates`
    default:
      return `${name} used Brownie`
  }
}
