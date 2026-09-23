import type { LocationQuery } from 'vue-router'
import {
  ApiRequestError,
  type CapabilitiesResponse,
  type ConnectionResponse,
  type ConnectorAccess,
  type DocumentSourceResponse,
} from '@/api/client'
import { describeCommonFailure } from '@/api/failures'

/**
 * Everything the pages say about connected accounts, in one place, so that
 * the Connections page and a document's Sources tab never describe the same
 * permission, state or failure in two different ways.
 */

export const ACCESS_NAMES: Record<ConnectorAccess, string> = {
  CALENDAR_EVENTS: 'Google Calendar',
  DRIVE_FILES: 'Google Drive',
}

/**
 * The permission as Google words it to the person, and what Brownie itself
 * does with it. Google's wording is quoted as it is shown on Google's own
 * consent page; Brownie's reading-only rule is stated beside it, never as if
 * it were a limit of the permission itself.
 */
export const PERMISSION_WORDS: Record<ConnectorAccess, { google: string; alsoAsked: string | null; brownie: string }> = {
  CALENDAR_EVENTS: {
    google: 'See the events on Google calendars you own.',
    // Calendar's consent also asks who the account is (the sign-in permissions); Drive's finds that out through Drive.
    alsoAsked: 'Google also asks to let Brownie see your email address, so this page can show which account is connected.',
    brownie:
      'Brownie reads only your main calendar, only the days you ask it to list, and copies only the event you choose, ' +
      'as text, into the document you are working on. It never creates, changes or deletes an event, and never reads who was invited.',
  },
  DRIVE_FILES: {
    google: 'See, edit, create, and delete only the specific Google Drive files you use with this app.',
    alsoAsked: null,
    brownie:
      'Google has no read-only form of this permission. Brownie only ever reads, and only files you pick: that is ' +
      "Brownie's own rule, kept by its database, not a limit Google puts on the permission. Brownie cannot read files " +
      'from Drive yet, so this connection is not used.',
  },
}

/** What this deployment offers to connect, or null when the server did not say (one from before connections existed). */
export function offeredAccess(capabilities: CapabilitiesResponse | null): ConnectorAccess[] | null {
  const offered = capabilities?.googleConnectorAccess
  return Array.isArray(offered) ? offered : null
}

/** The most recent connection for one kind of access; the list comes newest first. */
export function latestConnection(connections: ConnectionResponse[], access: ConnectorAccess): ConnectionResponse | null {
  return connections.find((connection) => connection.access === access) ?? null
}

const dayFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

function day(value: string | null | undefined): string | null {
  if (!value) {
    return null
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : dayFormatter.format(parsed)
}

const RECONNECT_REASONS: Record<string, string> = {
  TOKEN_REJECTED:
    "Google stopped accepting Brownie's access. That happens when Brownie is removed in your Google account and, " +
    "while this Brownie's Google project is still in testing, seven days after you connected. Connect again to carry on.",
  TOKEN_UNREADABLE:
    'Brownie can no longer read the access it stored, usually because whoever runs this Brownie changed its key. ' +
    'Connect again to carry on.',
  PERMISSION_MISSING:
    "The access Google gives Brownie no longer includes the permission this needs. Connect again, and leave that permission ticked on Google's page.",
}

const REVOCATIONS: Record<string, string> = {
  REVOKED: "Google confirmed it took Brownie's access back.",
  FAILED:
    'Brownie could not get Google to confirm it took the access back, but deleted the access it held. To be sure, remove Brownie in your Google account.',
  NOT_NEEDED:
    'Brownie held no access it could still use, so there was nothing it could ask Google to take back. If Brownie still appears in your Google account, you can remove it there.',
}

/**
 * What one disconnect did, judged only by the connections it closed: the
 * answer lists every connection this person ever had, and an older one's
 * outcome is not this one's.
 */
export function disconnectSentence(knownBefore: ReadonlyMap<number, ConnectionResponse['state']>, after: ConnectionResponse[]): string {
  // Open before, or not known at all (made in another tab since this page loaded): either may be one this call closed.
  const closed = after.filter(
    (connection) => connection.state === 'DISCONNECTED' && (knownBefore.get(connection.id) ?? 'ACTIVE') !== 'DISCONNECTED',
  )
  const outcomes = new Set(closed.map((connection) => connection.providerRevocation))
  if (outcomes.has('FAILED')) {
    return `Disconnected. ${REVOCATIONS.FAILED}`
  }
  if (outcomes.has('REVOKED') && outcomes.has('NOT_NEEDED')) {
    return (
      "Disconnected. Google confirmed it took back the access Brownie still held, and Brownie deleted it. A connection that was " +
      'waiting to be connected again held nothing Brownie could use; if Brownie still appears in that Google account, remove it there.'
    )
  }
  if (outcomes.has('REVOKED')) {
    return "Disconnected. Google confirmed it took Brownie's access back, and Brownie deleted what it held."
  }
  return `Disconnected. ${REVOCATIONS.NOT_NEEDED}`
}

/** One or two sentences on where a connection stands, leaving out whatever the server did not say. */
export function stateSentence(connection: ConnectionResponse): string {
  switch (connection.state) {
    case 'ACTIVE': {
      const who = connection.accountEmail ? ` as ${connection.accountEmail}` : ''
      const since = day(connection.connectedAt)
      return `Connected${who}${since ? ` since ${since}` : ''}.`
    }
    case 'RECONNECT_REQUIRED': {
      const why = connection.reconnectReason ? RECONNECT_REASONS[connection.reconnectReason] : undefined
      return `Needs connecting again. ${why ?? 'Connect again to carry on.'}`
    }
    case 'DISCONNECTED': {
      const when = day(connection.disconnectedAt)
      const revocation = connection.providerRevocation ? REVOCATIONS[connection.providerRevocation] : undefined
      return [`Disconnected${when ? ` on ${when}` : ''}.`, revocation].filter(Boolean).join(' ')
    }
    default:
      return ''
  }
}

const CONSENT_FAILURES: Record<string, string> = {
  no_pending_request: 'Brownie was not expecting an answer from Google just then, so nothing was connected. Start again from here.',
  state_mismatch: "Google's answer did not match the request this browser started, so nothing was connected. Start again from here.",
  expired: "More than ten minutes passed on Google's page, so nothing was connected. Start again.",
  not_allowed: 'Your account may not connect Google accounts in this workspace, so nothing was connected.',
  not_configured:
    "Brownie's connection to Google is not set up correctly, so nothing was connected. Whoever runs this Brownie has to fix it; its log names Google's reason.",
  issuer_mismatch: 'That answer did not come from Google, so nothing was connected.',
  no_code: 'Google sent you back without an agreement, so nothing was connected.',
  permission_not_granted:
    "Google did not give Brownie the permission it asked for, so nothing was connected. On Google's page, leave that permission ticked.",
  no_refresh_token:
    'Google did not give Brownie lasting access, so nothing was connected. Try again; if it keeps happening, remove Brownie in your Google account first.',
  different_account:
    'That is a different Google account from the one already connected for this, so nothing was changed. Disconnect first to use another account.',
  blocked_by_organization:
    'The organization that manages that Google account does not allow Brownie to read it, so nothing was connected. Its administrator can change that.',
  consent_expired: "Google stopped accepting Brownie's access moments after you agreed, so nothing was connected. Try again.",
  google_unavailable: 'Google could not be reached, so nothing was connected. Try again in a minute.',
  signed_out:
    'Your Brownie session had ended by the time Google sent you back, so nothing was connected. Connect again from here.',
  access_denied: "You chose not to allow access on Google's page, so nothing was connected.",
}

/**
 * What to tell the person when Google has just sent them back here, read
 * from the address the server redirected to; null when this visit is not
 * such a return. A reason code the page has no words for is not shown raw.
 */
export function consentOutcome(query: LocationQuery): { tone: 'success' | 'failure'; access: ConnectorAccess | null; text: string } | null {
  const google = query.google
  if (google !== 'connected' && google !== 'failed') {
    return null
  }
  const access = query.access === 'calendar_events' ? 'CALENDAR_EVENTS' : query.access === 'drive_files' ? 'DRIVE_FILES' : null
  if (google === 'connected') {
    const name = access ? ACCESS_NAMES[access] : 'Your Google account'
    return {
      tone: 'success',
      access,
      text:
        access === 'CALENDAR_EVENTS'
          ? `${name} is connected. Brownie lists the days you ask for and copies only the event you choose.`
          : `${name} is connected.`,
    }
  }
  const reason = typeof query.reason === 'string' ? query.reason : ''
  return {
    tone: 'failure',
    access,
    text: CONSENT_FAILURES[reason] ?? 'Google did not complete the connection, so nothing was connected. Try again.',
  }
}

/**
 * The sentence for a failed connection request. Brownie's connector codes
 * come first, because some of them arrive as a 503 or 409 whose generic
 * reading would be wrong (waiting does not fix a setup problem); everything
 * else falls through to the shared wording.
 */
export function describeConnectorFailure(error: unknown, what: string): string | null {
  if (error instanceof ApiRequestError && !error.routeMissing) {
    const code = error.problem?.code
    switch (code) {
      case 'CONNECTOR_NOT_CONFIGURED':
        return 'Connecting a Google account is not set up on this Brownie.'
      case 'CONNECTOR_MISCONFIGURED':
        return "Brownie's connection to Google is not set up correctly. Whoever runs this Brownie has to fix it."
      case 'CONNECTION_NOT_FOUND':
        return 'Google Calendar is not connected. Connect it first.'
      case 'CONNECTION_RECONNECT_REQUIRED': {
        const why = error.problem?.reason ? RECONNECT_REASONS[error.problem.reason] : undefined
        return why ?? "Brownie can no longer use this Google connection. Connect again to carry on."
      }
      case 'CONNECTOR_BLOCKED_BY_ORGANIZATION':
        return 'The organization that manages this Google account does not allow Brownie to read it. Its administrator can change that.'
      case 'CONNECTOR_PROVIDER_UNAVAILABLE':
        return 'Google could not be reached. Nothing was changed; try again in a minute.'
      case 'CONNECTOR_RESOURCE_TOO_LARGE':
        return "Google's answer was larger than Brownie reads. Choose fewer days."
      case 'FORBIDDEN':
        return 'Your account may not connect Google accounts in this workspace.'
      default:
        break
    }
  }
  return describeCommonFailure(error, what)
}

/** A copied source's origin in a few words, or null for an upload (or a server that does not say). */
export function originSentence(source: DocumentSourceResponse): string | null {
  const origin = source.origin ?? null
  if (origin === null || origin.provider !== 'GOOGLE') {
    return null
  }
  const where = source.kind === 'GOOGLE_CALENDAR' ? 'Google Calendar' : 'Google'
  const copied = day(source.fetchedAt)
  const changed = day(origin.modifiedAt)
  // The copy is never refreshed, so when the event was changed is said as of the copy, not as if it were still true.
  return `Copied from ${where}${origin.conversion === 'CALENDAR_EVENT_AS_TEXT' ? ' as text' : ''}${copied ? ` on ${copied}` : ''}${
    changed ? `, when it had last been changed there on ${changed}` : ''
  }.`
}

/** The page at the provider for a copied source, only ever as an https address, whatever the server sent. */
export function originLink(source: DocumentSourceResponse): string | null {
  const link = source.origin?.link
  return typeof link === 'string' && link.startsWith('https://') ? link : null
}
