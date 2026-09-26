import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import ConnectionsView from '@/views/ConnectionsView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    listConnections: vi.fn(),
    startGoogleConsent: vi.fn(),
    startDrivePick: vi.fn(),
    forgetDriveFile: vi.fn(),
    disconnectGoogle: vi.fn(),
    getCapabilities: vi.fn(),
  }
})
vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))

import {
  ApiRequestError,
  disconnectGoogle,
  forgetDriveFile,
  getCapabilities,
  listConnections,
  startDrivePick,
  startGoogleConsent,
  type CapabilitiesResponse,
  type ConnectionResponse,
} from '@/api/client'
import { navigateTo, releaseIfStillHere } from '@/navigation'
import { resetCapabilitiesCache } from '@/capabilities'

const stub = { template: '<div />' }
let router: Router

async function mountAt(path = '/connections') {
  router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: stub },
      { path: '/connections', component: ConnectionsView },
      { path: '/signin', component: stub },
    ],
  })
  router.push(path)
  await router.isReady()
  const wrapper = mount(ConnectionsView, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function problem(status: number, detail: string, code = 'X', extra: Record<string, string> = {}) {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [], ...extra }
}

function capabilities(googleConnectorAccess?: ('CALENDAR_EVENTS' | 'DRIVE_FILES')[]): CapabilitiesResponse {
  return {
    maxUploadBytes: 10485760,
    uploadMediaTypes: [],
    assistSourceMediaTypes: [],
    templateMediaTypes: [],
    trashRetentionDays: 30,
    ...(googleConnectorAccess === undefined ? {} : { googleConnectorAccess }),
  }
}

function connection(overrides: Partial<ConnectionResponse> = {}): ConnectionResponse {
  return {
    id: 1,
    provider: 'GOOGLE',
    access: 'CALENDAR_EVENTS',
    state: 'ACTIVE',
    accountEmail: 'me@example.org',
    grantedScopes: ['openid', 'https://www.googleapis.com/auth/calendar.events.owned.readonly'],
    reconnectReason: null,
    connectedAt: '2026-09-20T10:00:00Z',
    tokenIssuedAt: '2026-09-20T10:00:00Z',
    disconnectedAt: null,
    providerRevocation: null,
    grants: [],
    ...overrides,
  }
}

function text(wrapper: Awaited<ReturnType<typeof mountAt>>): string {
  return wrapper.text().replace(/\s+/g, ' ')
}

function buttonNamed(wrapper: Awaited<ReturnType<typeof mountAt>>, name: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text().replace(/\s+/g, ' ').trim() === name)
  if (!found) {
    throw new Error(`No button named "${name}" among: ${wrapper.findAll('button').map((b) => b.text()).join(' | ')}`)
  }
  return found
}

describe('ConnectionsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    vi.mocked(listConnections).mockReset().mockResolvedValue([])
    vi.mocked(startGoogleConsent).mockReset()
    vi.mocked(startDrivePick).mockReset()
    vi.mocked(forgetDriveFile).mockReset()
    vi.mocked(disconnectGoogle).mockReset()
    vi.mocked(getCapabilities).mockReset().mockResolvedValue(capabilities(['CALENDAR_EVENTS']))
    vi.mocked(navigateTo).mockReset()
    vi.mocked(releaseIfStillHere).mockReset()
    window.localStorage.clear()
    window.sessionStorage.clear()
    signIn()
  })

  it('offers to connect Google Calendar, quoting Google and saying what Brownie does, and passes an accessibility scan', async () => {
    const wrapper = await mountAt()

    expect(listConnections).toHaveBeenCalledWith(7)
    expect(wrapper.find('section[aria-labelledby="connections-calendar"] p').text()).toBe('Not connected.')
    expect(text(wrapper)).toContain('"See the events on Google calendars you own."')
    expect(text(wrapper)).toContain('It never creates, changes or deletes an event')
    expect(wrapper.find('#connections-drive').exists()).toBe(false)
    expect(buttonNamed(wrapper, 'Connect Google Calendar').exists()).toBe(true)
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  it('leaves for Google with a consent that returns here, and keeps nothing of it in browser storage', async () => {
    vi.mocked(startGoogleConsent).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?state=s' })
    const wrapper = await mountAt()

    await buttonNamed(wrapper, 'Connect Google Calendar').trigger('click')
    await flushPromises()

    expect(startGoogleConsent).toHaveBeenCalledWith(7, 'CALENDAR_EVENTS', '/connections')
    expect(navigateTo).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?state=s')
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    wrapper.unmount()
  })

  it('shows a connected calendar with its account and what Brownie may read, and does not offer to connect it twice', async () => {
    vi.mocked(listConnections).mockResolvedValue([
      connection({ grants: [{ id: 3, type: 'CALENDAR', displayName: 'Primary calendar', grantedAt: '2026-09-21T10:00:00Z' }] }),
    ])
    const wrapper = await mountAt()

    expect(text(wrapper)).toMatch(/Connected as me@example\.org since /)
    expect(text(wrapper)).toMatch(/Primary calendar, since /)
    expect(text(wrapper)).toContain('Copy an event from Google Calendar')
    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain('Connect Google Calendar')
    expect(await axe(wrapper.element)).toHaveNoViolations()
    wrapper.unmount()
  })

  it.each([
    ['?google=connected&access=calendar_events', 'status', 'Google Calendar is connected.'],
    ['?google=failed&access=calendar_events&reason=permission_not_granted', 'alert', 'leave that permission ticked'],
    ['?google=failed&reason=signed_out', 'alert', 'Your Brownie session had ended by the time Google sent you back'],
    ['?google=failed&access=calendar_events&reason=access_denied', 'alert', "You chose not to allow access on Google's page"],
    ['?google=failed&access=calendar_events&reason=consent_expired', 'alert', "Google stopped accepting Brownie's access moments after you agreed"],
    ['?google=failed&access=calendar_events&reason=not_configured', 'alert', 'Whoever runs this Brownie has to fix it'],
    ['?google=failed&access=calendar_events&reason=blocked_by_organization', 'alert', 'Its administrator can change that.'],
  ])('says what Google answered (%s) once, then takes it out of the address', async (query, role, expected) => {
    const wrapper = await mountAt(`/connections${query}`)

    const notice = wrapper.find(`[role="${role}"]`)
    expect(notice.text()).toContain(expected)
    expect(document.activeElement).toBe(notice.element)
    expect(router.currentRoute.value.fullPath).toBe('/connections')
    wrapper.unmount()
  })

  it('never shows a reason code it has no words for', async () => {
    const wrapper = await mountAt('/connections?google=failed&access=calendar_events&reason=something_new')

    expect(wrapper.find('[role="alert"]').text()).toBe('Google did not complete the connection, so nothing was connected. Try again.')
    expect(text(wrapper)).not.toContain('something_new')
    wrapper.unmount()
  })

  it('explains a connection Google stopped accepting, including the seven-day limit while testing, and offers to connect again', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection({ state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_REJECTED' })])
    const wrapper = await mountAt()

    expect(text(wrapper)).toContain('Needs connecting again.')
    expect(text(wrapper)).toContain('seven days after you connected')
    expect(buttonNamed(wrapper, 'Connect Google Calendar again').exists()).toBe(true)
    wrapper.unmount()
  })

  it('describes a Drive connection honestly: Google has no read-only form, Brownie only reads, and nothing reads from Drive yet', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection({ id: 2, access: 'DRIVE_FILES', grantedScopes: [] })])
    const wrapper = await mountAt()

    const drive = wrapper.find('section[aria-labelledby="connections-drive"]')
    expect(drive.text()).toContain('"See, edit, create, and delete only the specific Google Drive files you use with this app."')
    expect(drive.text()).toContain('Google has no read-only form of this permission.')
    expect(drive.text()).toContain('Brownie cannot read files from Drive yet')
    expect(drive.findAll('button')).toHaveLength(0)
    wrapper.unmount()
  })

  it('offers nothing to connect where Google is not set up, and says so', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities([]))
    const wrapper = await mountAt()

    expect(text(wrapper)).toContain('Connecting other accounts is not set up on this Brownie.')
    expect(wrapper.find('#connections-calendar').exists()).toBe(false)
    expect(wrapper.findAll('button')).toHaveLength(0)
    wrapper.unmount()
  })

  it('offers nothing new to a server that does not say what can be connected, but still lists what exists', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities())
    vi.mocked(listConnections).mockResolvedValue([connection({ state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_REJECTED' })])
    const wrapper = await mountAt()

    expect(text(wrapper)).toContain('does not say which accounts can be connected')
    expect(text(wrapper)).toContain('Needs connecting again.')
    expect(wrapper.findAll('button').map((button) => button.text())).not.toContain('Connect Google Calendar again')
    wrapper.unmount()
  })

  it('tells a server older than the page apart from a failure', async () => {
    vi.mocked(listConnections).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'No static resource api/v1/workspaces/7/connections.', 'NOT_FOUND')),
    )
    const wrapper = await mountAt()

    expect(wrapper.find('[role="alert"]').text()).toContain('older than this page and does not have connected accounts yet')
    expect(wrapper.find('[role="alert"]').text()).toContain('Reloading will not change that')
    wrapper.unmount()
  })

  it.each([
    [new ApiRequestError(429, problem(429, 'Too many requests in a short time. Wait 20 seconds and try again.')), 'Wait 20 seconds'],
    [new ApiRequestError(401, problem(401, 'Sign in.')), 'Your session has ended.'],
    [new TypeError('Failed to fetch'), 'Brownie could not be reached.'],
    [new ApiRequestError(500, problem(500, 'boom', 'INTERNAL_ERROR')), 'let whoever runs this Brownie know'],
  ])('says what went wrong when the list cannot be loaded (%s)', async (error, expected) => {
    vi.mocked(listConnections).mockRejectedValue(error)
    const wrapper = await mountAt()

    expect(wrapper.find('[role="alert"]').text()).toContain(expected)
    expect(text(wrapper)).not.toMatch(/reloading the page|something went wrong/i)
    wrapper.unmount()
  })

  it('says plainly when starting a connection is refused', async () => {
    vi.mocked(startGoogleConsent).mockRejectedValue(
      new ApiRequestError(409, problem(409, 'Connecting a Google account is not set up on this Brownie.', 'CONNECTOR_NOT_CONFIGURED')),
    )
    const wrapper = await mountAt()

    await buttonNamed(wrapper, 'Connect Google Calendar').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe(
      'Could not start connecting Google Calendar. Connecting a Google account is not set up on this Brownie.',
    )
    expect(navigateTo).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('disconnects only after asking, says copies stay, and says whether Google confirmed', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection()])
    vi.mocked(disconnectGoogle).mockResolvedValue([
      connection({ state: 'DISCONNECTED', disconnectedAt: '2026-09-23T10:00:00Z', providerRevocation: 'FAILED', grants: [] }),
    ])
    const wrapper = await mountAt()

    expect(text(wrapper)).toContain('Copies already brought into your documents stay with those documents')
    await buttonNamed(wrapper, 'Disconnect Google…').trigger('click')
    expect(document.activeElement?.id).toBe('connections-disconnect-confirm')

    await wrapper.find('[role="group"]').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(disconnectGoogle).not.toHaveBeenCalled()
    expect(document.activeElement?.id).toBe('connections-disconnect')

    await buttonNamed(wrapper, 'Disconnect Google…').trigger('click')
    await buttonNamed(wrapper, 'Yes, disconnect').trigger('click')
    await flushPromises()

    expect(disconnectGoogle).toHaveBeenCalledWith(7)
    const notice = wrapper.find('[role="status"]')
    expect(notice.text()).toContain('Brownie could not get Google to confirm it took the access back, but deleted the access it held.')
    expect(document.activeElement).toBe(notice.element)
    expect(text(wrapper)).toMatch(/Disconnected on /)
    expect(wrapper.find('#connections-disconnect').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each([
    [
      'Google confirmed this time, whatever an older disconnect said',
      [connection({ id: 1, state: 'DISCONNECTED', disconnectedAt: '2026-06-01T10:00:00Z', providerRevocation: 'FAILED' }), connection({ id: 2 })],
      [
        connection({ id: 1, state: 'DISCONNECTED', disconnectedAt: '2026-06-01T10:00:00Z', providerRevocation: 'FAILED' }),
        connection({ id: 2, state: 'DISCONNECTED', disconnectedAt: '2026-09-23T10:00:00Z', providerRevocation: 'REVOKED' }),
      ],
      "Disconnected. Google confirmed it took Brownie's access back, and Brownie deleted what it held.",
    ],
    [
      'there was nothing Brownie could still use, so Google was not asked',
      [connection({ id: 3, state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_UNREADABLE' })],
      [connection({ id: 3, state: 'DISCONNECTED', disconnectedAt: '2026-09-23T10:00:00Z', providerRevocation: 'NOT_NEEDED' })],
      'Disconnected. Brownie held no access it could still use, so there was nothing it could ask Google to take back. If Brownie still appears in your Google account, you can remove it there.',
    ],
  ])('says what this disconnect did when %s', async (_case, before, after, sentence) => {
    vi.mocked(listConnections).mockResolvedValue(before)
    vi.mocked(disconnectGoogle).mockResolvedValue(after)
    const wrapper = await mountAt()
    await buttonNamed(wrapper, 'Disconnect Google…').trigger('click')
    await buttonNamed(wrapper, 'Yes, disconnect').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="status"]').text()).toBe(sentence)
    wrapper.unmount()
  })

  it('says which access was taken back when one connection was revoked and another held nothing usable', async () => {
    vi.mocked(listConnections).mockResolvedValue([
      connection({ id: 4 }),
      connection({ id: 5, access: 'DRIVE_FILES', state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_UNREADABLE' }),
    ])
    vi.mocked(disconnectGoogle).mockResolvedValue([
      connection({ id: 4, state: 'DISCONNECTED', disconnectedAt: '2026-09-23T10:00:00Z', providerRevocation: 'REVOKED' }),
      connection({ id: 5, access: 'DRIVE_FILES', state: 'DISCONNECTED', disconnectedAt: '2026-09-23T10:00:00Z', providerRevocation: 'NOT_NEEDED' }),
    ])
    const wrapper = await mountAt()
    await buttonNamed(wrapper, 'Disconnect Google…').trigger('click')
    await buttonNamed(wrapper, 'Yes, disconnect').trigger('click')
    await flushPromises()

    const said = wrapper.find('[role="status"]').text()
    expect(said).toContain('Google confirmed it took back the access Brownie still held')
    expect(said).toContain('if Brownie still appears in that Google account, remove it there')
    wrapper.unmount()
  })

  it('counts a connection made in another tab since this page loaded as one this disconnect closed', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection({ id: 1 })])
    // Meanwhile, in another tab: 1 disconnected (Google confirmed), then 2 connected. This disconnect closes 2, and Google cannot be reached.
    vi.mocked(disconnectGoogle).mockResolvedValue([
      connection({ id: 2, state: 'DISCONNECTED', disconnectedAt: '2026-09-23T11:00:00Z', providerRevocation: 'FAILED' }),
      connection({ id: 1, state: 'DISCONNECTED', disconnectedAt: '2026-09-23T10:00:00Z', providerRevocation: 'REVOKED' }),
    ])
    const wrapper = await mountAt()
    await buttonNamed(wrapper, 'Disconnect Google…').trigger('click')
    await buttonNamed(wrapper, 'Yes, disconnect').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="status"]').text()).toContain('Brownie could not get Google to confirm')
    wrapper.unmount()
  })

  it('does not point to a copy control this Brownie no longer offers', async () => {
    vi.mocked(getCapabilities).mockResolvedValue(capabilities([]))
    vi.mocked(listConnections).mockResolvedValue([connection()])
    const wrapper = await mountAt()

    expect(text(wrapper)).not.toContain('use "Copy an event from Google Calendar"')
    expect(text(wrapper)).toContain('Copying events from Google Calendar is not offered on this Brownie at the moment.')
    wrapper.unmount()
  })

  it('says Google also asks to see the email address, and that a copy stays until every document holding it is deleted', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection()])
    const wrapper = await mountAt()

    expect(text(wrapper)).toContain('Google also asks to let Brownie see your email address')
    expect(text(wrapper)).toContain('delete every document it was copied into')
    expect(text(wrapper)).toContain('for that one Brownie cannot ask Google')
    wrapper.unmount()
  })

  it('lets the person try connecting again if the page did not in fact leave for Google', async () => {
    vi.mocked(listConnections).mockResolvedValue([])
    vi.mocked(startGoogleConsent).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?x=1' })
    const wrapper = await mountAt()
    await wrapper.find('#connections-connect-calendar').trigger('click')
    await flushPromises()
    expect(wrapper.find('#connections-connect-calendar').attributes('disabled')).toBeDefined()

    vi.mocked(releaseIfStillHere).mock.calls[0]![0]()
    await flushPromises()
    expect(wrapper.find('#connections-connect-calendar').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('keeps the connection and says why when disconnecting fails', async () => {
    vi.mocked(listConnections).mockResolvedValue([connection()])
    vi.mocked(disconnectGoogle).mockRejectedValue(new ApiRequestError(503, problem(503, 'Brownie is briefly unavailable.')))
    const wrapper = await mountAt()

    await buttonNamed(wrapper, 'Disconnect Google…').trigger('click')
    await buttonNamed(wrapper, 'Yes, disconnect').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe('Nothing was disconnected. Brownie is briefly unavailable.')
    expect(document.activeElement?.id).toBe('connections-disconnect-confirm')
    wrapper.unmount()
  })

  describe('once this Brownie offers Google Drive', () => {
    function driveConnection(overrides: Partial<ConnectionResponse> = {}): ConnectionResponse {
      return connection({
        id: 5,
        access: 'DRIVE_FILES',
        grantedScopes: ['https://www.googleapis.com/auth/drive.file'],
        grants: [
          { id: 11, type: 'DRIVE_FILE', displayName: 'Minutes', grantedAt: '2026-09-22T10:00:00Z' },
          { id: 12, type: 'DRIVE_FILE', displayName: null, grantedAt: '2026-09-22T11:00:00Z' },
        ],
        ...overrides,
      })
    }

    beforeEach(() => {
      vi.mocked(getCapabilities).mockResolvedValue(capabilities(['CALENDAR_EVENTS', 'DRIVE_FILES']))
    })

    it('says what Brownie does with Drive and lists the files it may read, each with a way to stop reading it', async () => {
      vi.mocked(listConnections).mockResolvedValue([driveConnection()])
      const wrapper = await mountAt()

      const section = wrapper.find('section[aria-labelledby="connections-drive"]')
      expect(section.text()).toContain("reads a file's content only when you copy that file into a document")
      expect(section.text()).not.toContain('cannot read files from Drive yet')
      expect(section.text()).toContain('Minutes, since')
      expect(section.text()).toContain('A file from Google Drive, since')
      expect(section.text()).toContain('Copies already made from a file stay in your documents when you stop reading it.')
      expect(buttonNamed(wrapper, 'Stop reading this file Minutes').exists()).toBe(true)
      expect(buttonNamed(wrapper, 'Choose more files in Google Drive').exists()).toBe(true)
      expect(text(wrapper)).toContain('Every file you chose in Google Drive is taken off your list too.')
      expect(await axe(wrapper.element)).toHaveNoViolations()
      wrapper.unmount()
    })

    it("chooses files in Google's own picker, which comes back here", async () => {
      vi.mocked(listConnections).mockResolvedValue([])
      vi.mocked(startDrivePick).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?trigger_onepick=true' })
      const wrapper = await mountAt()

      expect(wrapper.find('section[aria-labelledby="connections-drive"]').text()).toContain(
        "Nothing yet. Files you choose in Google's file picker are listed here.",
      )
      await buttonNamed(wrapper, 'Choose files in Google Drive').trigger('click')
      await flushPromises()

      expect(startDrivePick).toHaveBeenCalledWith(7, '/connections')
      expect(navigateTo).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?trigger_onepick=true')
      expect(startGoogleConsent).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('offers to connect again by choosing files when Google stopped accepting Drive', async () => {
      vi.mocked(listConnections).mockResolvedValue([driveConnection({ state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_REJECTED' })])
      const wrapper = await mountAt()

      expect(buttonNamed(wrapper, 'Connect Google Drive again and choose files').exists()).toBe(true)
      expect(wrapper.find('section[aria-labelledby="connections-drive"]').text()).toContain('Needs connecting again.')
      wrapper.unmount()
    })

    it('stops reading one file on request, says copies stay, and shows the list as it now is', async () => {
      vi.mocked(listConnections).mockResolvedValue([driveConnection()])
      vi.mocked(forgetDriveFile).mockResolvedValue([
        driveConnection({ grants: [{ id: 12, type: 'DRIVE_FILE', displayName: null, grantedAt: '2026-09-22T11:00:00Z' }] }),
      ])
      const wrapper = await mountAt()

      await buttonNamed(wrapper, 'Stop reading this file Minutes').trigger('click')
      await flushPromises()

      expect(forgetDriveFile).toHaveBeenCalledWith(7, 11)
      const notice = wrapper.find('[role="status"]')
      expect(notice.text()).toBe('Brownie will no longer read "Minutes". Copies already made from it stay where they are.')
      expect(document.activeElement).toBe(notice.element)
      expect(wrapper.find('section[aria-labelledby="connections-drive"]').text()).not.toContain('Minutes, since')
      wrapper.unmount()
    })

    it('says a file already off the list is off it, and shows the list as it now is', async () => {
      vi.mocked(listConnections).mockResolvedValueOnce([driveConnection()]).mockResolvedValue([
        driveConnection({ grants: [{ id: 12, type: 'DRIVE_FILE', displayName: null, grantedAt: '2026-09-22T11:00:00Z' }] }),
      ])
      vi.mocked(forgetDriveFile).mockRejectedValue(new ApiRequestError(404, problem(404, 'Gone.', 'CONNECTOR_RESOURCE_NOT_FOUND')))
      const wrapper = await mountAt()

      await buttonNamed(wrapper, 'Stop reading this file Minutes').trigger('click')
      await flushPromises()

      const notice = wrapper.find('[role="status"]')
      expect(notice.text()).toBe('"Minutes" was already off your list, so Brownie no longer reads it.')
      expect(document.activeElement).toBe(notice.element)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(listConnections).toHaveBeenCalledTimes(2)
      expect(wrapper.find('section[aria-labelledby="connections-drive"]').text()).not.toContain('Minutes, since')
      wrapper.unmount()
    })

    it('never quotes a placeholder as if it were the name of a file Drive gave no name', async () => {
      vi.mocked(listConnections).mockResolvedValueOnce([driveConnection()]).mockResolvedValue([driveConnection({ grants: [] })])
      vi.mocked(forgetDriveFile).mockRejectedValue(new ApiRequestError(404, problem(404, 'Gone.', 'CONNECTOR_RESOURCE_NOT_FOUND')))
      const wrapper = await mountAt()

      await buttonNamed(wrapper, 'Stop reading this file from Google Drive').trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="status"]').text()).toBe('That file was already off your list, so Brownie no longer reads it.')
      wrapper.unmount()
    })

    it('keeps the file, says so, and keeps the keyboard on its button when stopping fails', async () => {
      vi.mocked(listConnections).mockResolvedValue([driveConnection()])
      vi.mocked(forgetDriveFile).mockRejectedValue(new ApiRequestError(503, problem(503, 'Down.', 'STORAGE_UNAVAILABLE')))
      const wrapper = await mountAt()

      await buttonNamed(wrapper, 'Stop reading this file Minutes').trigger('click')
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toMatch(/^"Minutes" is still on your list\. /)
      expect(document.activeElement?.id).toBe('connections-forget-11')
      wrapper.unmount()
    })

    it('says what a pick added when Google sends the person back, and takes it out of the address', async () => {
      vi.mocked(listConnections).mockResolvedValue([driveConnection()])
      const wrapper = await mountAt(
        '/connections?google=picked&access=drive_files&added=2&unsupported=1&unavailable=0&unchecked=0&over_limit=0',
      )

      expect(wrapper.find('[role="status"]').text()).toBe(
        "2 files you chose are now on your list of files Brownie may read. Brownie reads a file's content only when you copy it into a document. " +
          '1 file was not added: Brownie reads only Google Docs and plain-text (.txt) files.',
      )
      expect(router.currentRoute.value.query).toEqual({})
      wrapper.unmount()
    })
  })

  it('keeps saying Drive is not used where it is not offered, and still lets a listed file be stopped', async () => {
    vi.mocked(listConnections).mockResolvedValue([
      connection({
        id: 5,
        access: 'DRIVE_FILES',
        grants: [{ id: 11, type: 'DRIVE_FILE', displayName: 'Minutes', grantedAt: '2026-09-22T10:00:00Z' }],
      }),
    ])
    const wrapper = await mountAt()

    const section = wrapper.find('section[aria-labelledby="connections-drive"]')
    expect(section.text()).toContain('Brownie cannot read files from Drive yet')
    expect(section.text()).not.toContain('Choose files in Google Drive')
    expect(buttonNamed(wrapper, 'Stop reading this file Minutes').exists()).toBe(true)
    wrapper.unmount()
  })

  it('asks a signed-out visitor to sign in and come back here', async () => {
    useSessionStore().status = 'anonymous'
    const wrapper = await mountAt()

    expect(wrapper.find('a').attributes('href')).toBe('/signin?next=/connections')
    expect(listConnections).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
