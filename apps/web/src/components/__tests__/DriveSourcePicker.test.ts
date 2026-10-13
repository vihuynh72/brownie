import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import DriveSourcePicker from '@/components/DriveSourcePicker.vue'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    getCapabilities: vi.fn(),
    listConnections: vi.fn(),
    startDrivePick: vi.fn(),
    forgetDriveFile: vi.fn(),
  }
})
vi.mock('@/navigation', () => ({ navigateTo: vi.fn(), releaseIfStillHere: vi.fn() }))

import {
  ApiRequestError,
  forgetDriveFile,
  getCapabilities,
  listConnections,
  startDrivePick,
  type CapabilitiesResponse,
  type ConnectionResponse,
  type DocumentSourceResponse,
  type DriveImportResponse,
  type ResourceGrantResponse,
} from '@/api/client'
import { navigateTo, releaseIfStillHere } from '@/navigation'
import { resetCapabilitiesCache } from '@/capabilities'

const stub = { template: '<div />' }
const mounted: { unmount(): void }[] = []
const copyFile = vi.fn<(grantId: number) => Promise<DriveImportResponse>>()

async function mountPicker(startOpen = false, unsavedWork = false) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: stub },
      { path: '/connections', component: stub },
    ],
  })
  router.push('/')
  await router.isReady()
  const wrapper = mount(DriveSourcePicker, {
    props: { workspaceId: 7, documentId: 42, startOpen, unsavedWork, copyFile },
    attachTo: document.body,
    global: { plugins: [router] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountPicker>>

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function problem(status: number, code: string, reason?: string) {
  return {
    status,
    title: 't',
    code,
    detail: 'The server said something else.',
    correlationId: 'c',
    fields: [],
    recoveryActions: [],
    ...(reason === undefined ? {} : { reason }),
  }
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

function file(id: number, displayName: string | null): ResourceGrantResponse {
  return { id, type: 'DRIVE_FILE', displayName, grantedAt: '2026-09-22T10:00:00Z' }
}

function drive(overrides: Partial<ConnectionResponse> = {}): ConnectionResponse {
  return {
    id: 5,
    provider: 'GOOGLE',
    access: 'DRIVE_FILES',
    state: 'ACTIVE',
    accountEmail: 'me@example.org',
    grantedScopes: ['https://www.googleapis.com/auth/drive.file'],
    reconnectReason: null,
    connectedAt: '2026-09-20T10:00:00Z',
    tokenIssuedAt: '2026-09-20T10:00:00Z',
    disconnectedAt: null,
    providerRevocation: null,
    grants: [file(11, 'Minutes'), file(12, 'notes.txt')],
    ...overrides,
  }
}

function copied(newCopy: boolean, conversion: 'GOOGLE_DOC_AS_TEXT' | null): DriveImportResponse {
  const source: DocumentSourceResponse = {
    id: 90,
    artifactId: 91,
    kind: 'GOOGLE_DRIVE',
    displayFilename: 'Minutes.txt',
    fetchedAt: '2026-09-23T08:00:00Z',
    attachedAt: '2026-09-23T08:00:00Z',
    origin: { provider: 'GOOGLE', title: 'Minutes', link: null, modifiedAt: null, conversion },
  }
  return { source, newCopy }
}

function text(wrapper: Wrapper): string {
  return wrapper.text().replace(/\s+/g, ' ')
}

/** What a button is called: its label when it has one (the row's button does), else the words on it, hidden ones included. */
function nameOf(button: { attributes(key: string): string | undefined; text(): string }): string {
  return (button.attributes('aria-label') ?? button.text()).replace(/\s+/g, ' ').trim()
}

function buttonNamed(wrapper: Wrapper, name: string) {
  const found = wrapper.findAll('button').find((candidate) => nameOf(candidate) === name)
  if (!found) {
    throw new Error(`No button named "${name}" among: ${wrapper.findAll('button').map(nameOf).join(' | ')}`)
  }
  return found
}

async function openWith(connections: ConnectionResponse[]): Promise<Wrapper> {
  vi.mocked(listConnections).mockResolvedValue(connections)
  const wrapper = await mountPicker()
  await buttonNamed(wrapper, 'Copy a file from Google Drive').trigger('click')
  await flushPromises()
  return wrapper
}

describe('DriveSourcePicker', () => {
  beforeEach(() => {
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockReset().mockResolvedValue(capabilities(['CALENDAR_EVENTS', 'DRIVE_FILES']))
    vi.mocked(listConnections).mockReset()
    vi.mocked(startDrivePick).mockReset()
    vi.mocked(forgetDriveFile).mockReset()
    copyFile.mockReset()
    vi.mocked(navigateTo).mockReset()
    vi.mocked(releaseIfStillHere).mockReset()
  })

  afterEach(() => {
    while (mounted.length > 0) mounted.pop()?.unmount()
  })

  it('is not offered until this Brownie offers Google Drive, whatever else it offers', async () => {
    for (const offered of [['CALENDAR_EVENTS'], [], undefined] as const) {
      resetCapabilitiesCache()
      vi.mocked(getCapabilities).mockResolvedValue(capabilities(offered === undefined ? undefined : [...offered]))
      expect((await mountPicker()).find('button').exists(), String(offered)).toBe(false)
    }
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockRejectedValue(new Error('network'))
    expect((await mountPicker()).find('button').exists()).toBe(false)
  })

  it('asks nothing of the server until the person opens it, and never lists their Drive', async () => {
    vi.mocked(listConnections).mockResolvedValue([drive()])
    const wrapper = await mountPicker()
    const toggle = buttonNamed(wrapper, 'Copy a file from Google Drive')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(listConnections).not.toHaveBeenCalled()

    await toggle.trigger('click')
    await flushPromises()

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(listConnections).toHaveBeenCalledWith(7)
    expect(text(wrapper)).toContain('Minutes')
    expect(text(wrapper)).toContain('notes.txt')
    expect(buttonNamed(wrapper, 'Copy Minutes').exists()).toBe(true)
    expect(buttonNamed(wrapper, 'Stop reading Minutes').exists()).toBe(true)
    expect(buttonNamed(wrapper, 'Choose more files in Google Drive').exists()).toBe(true)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('is a row of the Add a source panel: its title names the button, its sentence describes it, and it opens what it controls', async () => {
    vi.mocked(listConnections).mockResolvedValue([drive()])
    const wrapper = await mountPicker()
    const toggle = buttonNamed(wrapper, 'Copy a file from Google Drive')

    // The name is the title alone; the sentence under it is read after the name, not run into it.
    expect(toggle.attributes('aria-label')).toBe('Copy a file from Google Drive')
    const about = document.getElementById(toggle.attributes('aria-describedby') ?? '')
    expect(about?.textContent).toBe('Google Docs and .txt files you choose in Google Drive, up to 10 at a time.')
    expect(toggle.text()).toContain(about?.textContent)

    const panel = wrapper.get('#drive-picker-panel')
    expect(toggle.attributes('aria-controls')).toBe('drive-picker-panel')
    expect(panel.isVisible()).toBe(false)
    expect(wrapper.classes()).not.toContain('drive-picker--open')
    expect(await axe(wrapper.element)).toHaveNoViolations()

    await toggle.trigger('click')
    await flushPromises()
    expect(panel.isVisible()).toBe(true)
    expect(wrapper.classes()).toContain('drive-picker--open')

    await toggle.trigger('click')
    expect(panel.isVisible()).toBe(false)
    expect(toggle.attributes('aria-expanded')).toBe('false')
  })

  it('says it is checking the connection while it waits', async () => {
    let known: (value: ConnectionResponse[]) => void = () => {}
    vi.mocked(listConnections).mockReturnValue(new Promise((resolve) => (known = resolve)))
    const wrapper = await mountPicker()
    await buttonNamed(wrapper, 'Copy a file from Google Drive').trigger('click')

    expect(text(wrapper)).toContain('Checking your Google Drive connection…')
    expect(wrapper.find('#drive-pick').exists()).toBe(false)
    known([drive()])
    await flushPromises()
    expect(text(wrapper)).not.toContain('Checking your Google Drive connection…')
    expect(wrapper.find('#drive-pick').exists()).toBe(true)
  })

  it('puts what choosing files means, and the way to choose, together in one quiet card until a file is chosen', async () => {
    let wrapper = await openWith([])
    let card = wrapper.get('.msg-card')
    expect(card.text().replace(/\s+/g, ' ')).toContain(
      "Choose files in Google's own file picker. Brownie can then read only those files, and only Google Docs and plain-text (.txt) files, and copies one only when you ask.",
    )
    expect(card.get('a[href="/connections"]').text()).toBe('More about connections')
    expect(card.get('#drive-pick').text()).toBe('Choose files in Google Drive')
    expect(await axe(wrapper.element)).toHaveNoViolations()

    wrapper = await openWith([drive({ grants: [] })])
    card = wrapper.get('.msg-card')
    expect(card.text()).toContain('No files chosen yet.')
    expect(card.get('#drive-pick').text()).toBe('Choose files in Google Drive')

    // Once there are files, they are the list, and choosing more is a quieter button below it.
    wrapper = await openWith([drive()])
    expect(wrapper.find('.msg-card').exists()).toBe(false)
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.get('#drive-pick').text()).toBe('Choose more files in Google Drive')
    expect(wrapper.get('#drive-pick').classes()).not.toContain('button--primary')
  })

  it('offers to choose files where Drive is not connected, is waiting to be connected again, or has none chosen', async () => {
    let wrapper = await openWith([])
    expect(text(wrapper)).toContain("Choose files in Google's own file picker")
    expect(buttonNamed(wrapper, 'Choose files in Google Drive').exists()).toBe(true)
    expect(wrapper.find('[id^="drive-copy-"]').exists()).toBe(false)

    wrapper = await openWith([drive({ state: 'RECONNECT_REQUIRED', reconnectReason: 'TOKEN_REJECTED', grants: [] })])
    expect(text(wrapper)).toContain('Needs connecting again.')
    expect(buttonNamed(wrapper, 'Choose files in Google Drive').exists()).toBe(true)

    wrapper = await openWith([drive({ grants: [] })])
    expect(text(wrapper)).toContain('No files chosen yet.')

    // A server from before grants were listed with a connection says nothing about them.
    const older = drive()
    delete (older as Partial<ConnectionResponse>).grants
    wrapper = await openWith([older])
    expect(text(wrapper)).toContain('No files chosen yet.')
  })

  it("leaves for Google's picker with a pick that returns to this document", async () => {
    vi.mocked(startDrivePick).mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?trigger_onepick=true' })
    const wrapper = await openWith([drive()])

    await buttonNamed(wrapper, 'Choose more files in Google Drive').trigger('click')
    await flushPromises()

    expect(startDrivePick).toHaveBeenCalledWith(7, '/documents/42')
    expect(navigateTo).toHaveBeenCalledWith('https://accounts.google.com/o/oauth2/v2/auth?trigger_onepick=true')
    expect(wrapper.emitted('used')).toBeTruthy()
  })

  it('does not leave the page while the document has unsaved changes, and says why', async () => {
    vi.mocked(listConnections).mockResolvedValue([drive()])
    const wrapper = await mountPicker(false, true)
    await buttonNamed(wrapper, 'Copy a file from Google Drive').trigger('click')
    await flushPromises()

    await buttonNamed(wrapper, 'Choose more files in Google Drive').trigger('click')
    await flushPromises()

    expect(startDrivePick).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('changes that are not saved yet')
  })

  it('says plainly when the picker cannot be opened', async () => {
    vi.mocked(startDrivePick).mockRejectedValue(new ApiRequestError(409, problem(409, 'CONNECTOR_NOT_CONFIGURED')))
    const wrapper = await openWith([drive()])

    await buttonNamed(wrapper, 'Choose more files in Google Drive').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toBe(
      "Could not open Google's file picker. Copying files from Google Drive is not offered on this Brownie at the moment.",
    )
    expect(navigateTo).not.toHaveBeenCalled()
  })

  it('copies the one file chosen through the page and says what happened', async () => {
    copyFile.mockResolvedValueOnce(copied(true, 'GOOGLE_DOC_AS_TEXT')).mockResolvedValueOnce(copied(false, 'GOOGLE_DOC_AS_TEXT'))
    const wrapper = await openWith([drive()])

    await buttonNamed(wrapper, 'Copy Minutes').trigger('click')
    await flushPromises()

    expect(copyFile).toHaveBeenCalledWith(11)
    const notice = wrapper.find('[role="status"]')
    expect(notice.text()).toBe('Copied "Minutes" into this document\'s sources, as the text Google exports it as.')
    expect(document.activeElement).toBe(notice.element)

    await buttonNamed(wrapper, 'Copy Minutes').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="status"]').text()).toBe('"Minutes" had already been copied as it is now, so this document uses that copy.')
  })

  it('names a file without a name for what it is', async () => {
    copyFile.mockResolvedValue(copied(true, null))
    const wrapper = await openWith([drive({ grants: [file(13, null)] })])

    await buttonNamed(wrapper, 'Copy A file from Google Drive').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="status"]').text(), 'a placeholder is not quoted as if it were a name').toBe(
      "Copied that file into this document's sources.",
    )
  })

  it('says why a copy failed, and reads the list again when the file left it', async () => {
    copyFile.mockRejectedValueOnce(new ApiRequestError(409, problem(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'TRASHED')))
    const wrapper = await openWith([drive()])

    await buttonNamed(wrapper, 'Copy Minutes').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toBe('"Minutes" is in the trash in Google Drive. Take it out of the trash to copy it.')
    expect(listConnections).toHaveBeenCalledTimes(1)
    expect(document.activeElement?.id).toBe('drive-copy-11')

    copyFile.mockRejectedValueOnce(new ApiRequestError(409, problem(409, 'CONNECTOR_RESOURCE_UNAVAILABLE', 'GONE')))
    vi.mocked(listConnections).mockResolvedValue([drive({ grants: [file(12, 'notes.txt')] })])
    await buttonNamed(wrapper, 'Copy Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('so it was taken off your list')
    expect(listConnections).toHaveBeenCalledTimes(2)
    expect(text(wrapper)).not.toContain('Copy Minutes')
    expect(document.activeElement?.id, 'focus moves to what is still there').toBe('drive-pick')
  })

  it('stops reading a file on request, and says copies already made stay', async () => {
    vi.mocked(forgetDriveFile).mockResolvedValue([drive({ grants: [file(12, 'notes.txt')] })])
    const wrapper = await openWith([drive()])

    await buttonNamed(wrapper, 'Stop reading Minutes').trigger('click')
    await flushPromises()

    expect(forgetDriveFile).toHaveBeenCalledWith(7, 11)
    expect(wrapper.find('[role="status"]').text()).toBe(
      'Brownie will no longer read "Minutes". Copies already made from it stay where they are.',
    )
    expect(text(wrapper)).not.toContain('Stop reading Minutes')
    expect(text(wrapper)).toContain('notes.txt')
  })

  it('says a file already off the list is off it, and reads the list again', async () => {
    vi.mocked(forgetDriveFile).mockRejectedValue(new ApiRequestError(404, problem(404, 'CONNECTOR_RESOURCE_NOT_FOUND')))
    const wrapper = await openWith([drive()])
    vi.mocked(listConnections).mockResolvedValue([drive({ grants: [file(12, 'notes.txt')] })])

    await buttonNamed(wrapper, 'Stop reading Minutes').trigger('click')
    await flushPromises()

    const notice = wrapper.find('[role="status"]')
    expect(notice.text()).toBe('"Minutes" was already off your list, so Brownie no longer reads it.')
    expect(document.activeElement).toBe(notice.element)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(listConnections).toHaveBeenCalledTimes(2)
    expect(text(wrapper)).not.toContain('Stop reading Minutes')
  })

  it('keeps the file listed, says so, and keeps the keyboard on its button when stopping fails', async () => {
    vi.mocked(forgetDriveFile).mockRejectedValue(new ApiRequestError(503, problem(503, 'STORAGE_UNAVAILABLE')))
    const wrapper = await openWith([drive()])

    await buttonNamed(wrapper, 'Stop reading Minutes').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toMatch(/^Brownie still has "Minutes" on your list\. /)
    expect(listConnections).toHaveBeenCalledTimes(1)
    expect(document.activeElement?.id).toBe('drive-forget-11')
  })

  it('opens by itself when Google has just sent the person back here from its picker', async () => {
    vi.mocked(listConnections).mockResolvedValue([drive()])
    const wrapper = await mountPicker(true)

    expect(buttonNamed(wrapper, 'Copy a file from Google Drive').attributes('aria-expanded')).toBe('true')
    expect(listConnections).toHaveBeenCalledWith(7)
    expect(buttonNamed(wrapper, 'Copy Minutes').exists()).toBe(true)
  })

  it("loads no Google script anywhere: Google's picker is a page Google serves, reached by leaving this one", () => {
    const sources = import.meta.glob(['../../**/*.{ts,vue}', '!../../**/__tests__/**', '../../../index.html'], {
      query: '?raw',
      import: 'default',
      eager: true,
    }) as Record<string, string>
    expect(Object.keys(sources).length).toBeGreaterThan(20)
    expect(Object.keys(sources)).toContain('../../../index.html')
    for (const [file, text] of Object.entries(sources)) {
      expect(text, file).not.toMatch(/apis\.google\.com|accounts\.google\.com\/gsi|\bgapi\b|google\.picker/)
    }
  })

  it('keeps nothing in browser storage', async () => {
    window.localStorage.clear()
    window.sessionStorage.clear()
    copyFile.mockResolvedValue(copied(true, null))
    const wrapper = await openWith([drive()])
    await buttonNamed(wrapper, 'Copy Minutes').trigger('click')
    await flushPromises()

    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })
})
