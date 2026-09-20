import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import NewDocumentView from '@/views/NewDocumentView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    listTemplates: vi.fn(),
    createDocument: vi.fn(),
    getCapabilities: vi.fn(),
    allocateUpload: vi.fn(),
    uploadArtifactContent: vi.fn(),
    completeUpload: vi.fn(),
    extractArtifact: vi.fn(),
    attachDocumentSource: vi.fn(),
  }
})

import {
  ApiRequestError,
  getCapabilities,
  allocateUpload,
  attachDocumentSource,
  completeUpload,
  createDocument,
  extractArtifact,
  listTemplates,
  uploadArtifactContent,
} from '@/api/client'
import { resetCapabilitiesCache } from '@/capabilities'
import { readDocumentHandoff } from '@/router/handoff'

async function mountWithRouter(path = '/documents/new') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/documents/new', component: NewDocumentView },
      { path: '/signin', component: { template: '<div />' } },
      { path: '/documents/:id', component: { template: '<div />' } },
    ],
  })
  router.push(path)
  await router.isReady()
  return mount(NewDocumentView, { global: { plugins: [router] } })
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

describe('NewDocumentView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockReset().mockRejectedValue(new Error('no capabilities in this test'))
    vi.mocked(listTemplates).mockReset()
    vi.mocked(createDocument).mockReset()
    vi.mocked(allocateUpload).mockReset()
    vi.mocked(uploadArtifactContent).mockReset()
    vi.mocked(completeUpload).mockReset()
    vi.mocked(extractArtifact).mockReset()
    vi.mocked(attachDocumentSource).mockReset()
  })

  it('prompts sign-in when the session is anonymous', async () => {
    const session = useSessionStore()
    session.status = 'anonymous'

    const wrapper = await mountWithRouter()

    expect(wrapper.text()).toContain('Sign in')
    expect(listTemplates).not.toHaveBeenCalled()
  })

  it('tells the person the upload limit before they choose a source file', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 1, createdAt: '2026-03-01T00:00:00Z' },
    ])
    vi.mocked(getCapabilities).mockResolvedValue({
      maxUploadBytes: 10485760,
      uploadMediaTypes: [{ mediaType: 'text/plain', extension: 'txt' }],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 30,
    })
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(getCapabilities).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('Attach a plain-text (.txt) file up to 10 MB')
  })

  it('preselects the template the address names, as the teaching screen links here with the one just activated', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 1, createdAt: '2026-03-01T00:00:00Z' },
      { id: 9, displayName: 'Club Minutes Test Template', status: 'ACTIVE', currentActiveVersionId: 4, createdAt: '2026-03-02T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter('/documents/new?templateId=9')
    await flushPromises()

    expect((wrapper.find('#template').element as HTMLSelectElement).value).toBe('9')
  })

  it('loads templates once already authenticated at mount', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 1, createdAt: '2026-03-01T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(listTemplates).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('Flowing meeting minutes')
  })

  /**
   * A real bug, caught only by a real browser hitting a real cold-started backend: on a hard page
   * load, App.vue's own onMounted and the router's beforeEach guard both race to call
   * session.loadIdentity() -- when the guard loses that race (status already flipped away from
   * 'unknown' by the time it checks), it skips its own await entirely and the route component mounts
   * while identity is still in flight. Every sibling view already carries a watch(session.status, ...)
   * fallback for exactly this reason; this one didn't, so it hung on "Loading templates..." forever
   * with no retry once identity actually resolved a moment later.
   */
  it('retries loading templates once the session resolves to authenticated after mount', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 1, createdAt: '2026-03-01T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'unknown'

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(listTemplates).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('Flowing meeting minutes')

    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    session.status = 'authenticated'
    await flushPromises()

    expect(listTemplates).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('Flowing meeting minutes')
  })

  it('creates a document for the selected template and title, then navigates to it', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 3, createdAt: '2026-03-01T00:00:00Z' },
    ])
    vi.mocked(createDocument).mockResolvedValue({
      id: 42,
      title: 'March Sync',
      templateId: 1,
      templateVersionId: 3,
      currentRevisionId: 1,
      createdAt: '2026-03-01T00:00:00Z',
      currentRevision: { id: 1, revisionNumber: 1, parentRevisionId: null, fields: {}, contentHash: 'a'.repeat(64), editReason: 'x', createdAt: '2026-03-01T00:00:00Z' },
    })
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    await wrapper.find('#title').setValue('March Sync')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createDocument).toHaveBeenCalledWith(
      7,
      expect.any(String),
      expect.objectContaining({ title: 'March Sync', templateId: 1, templateVersionId: 3 }),
    )
    expect(wrapper.vm.$router.currentRoute.value.fullPath).toBe('/documents/42')
  })

  const ONE_TEMPLATE = [
    { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE' as const, currentActiveVersionId: 3, createdAt: '2026-03-01T00:00:00Z' },
  ]
  const CREATED = {
    id: 42,
    title: 'March Sync',
    templateId: 1,
    templateVersionId: 3,
    currentRevisionId: 1,
    createdAt: '2026-03-01T00:00:00Z',
    currentRevision: { id: 1, revisionNumber: 1, parentRevisionId: null, fields: {}, contentHash: 'a'.repeat(64), editReason: 'x', createdAt: '2026-03-01T00:00:00Z' },
  }

  async function chooseAFile(wrapper: Awaited<ReturnType<typeof mountWithRouter>>, size?: number) {
    const input = wrapper.find('#source')
    const file = new File(['notes'], 'notes.txt', { type: 'text/plain' })
    if (size !== undefined) Object.defineProperty(file, 'size', { value: size })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
  }

  /**
   * A real browser finding: the old code set a local warning ref and then navigated away, so the
   * warning died with this component before anyone could read it. The document really was
   * created; the person was simply never told their file had not gone with it.
   */
  it('carries a failed optional source attachment into the destination route rather than losing it', async () => {
    vi.mocked(listTemplates).mockResolvedValue(ONE_TEMPLATE)
    vi.mocked(createDocument).mockResolvedValue(CREATED)
    vi.mocked(allocateUpload).mockRejectedValue(new Error('upload outage'))
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()
    await wrapper.find('#title').setValue('March Sync')
    await chooseAFile(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/documents/42')
    const handoff = readDocumentHandoff()
    expect(handoff?.sourceWarning).toMatch(/could not be attached/i)
    expect(handoff?.attachedSources).toEqual([])
  })

  it('hands a successfully attached source to the workspace so it can be used there straight away', async () => {
    vi.mocked(listTemplates).mockResolvedValue(ONE_TEMPLATE)
    vi.mocked(createDocument).mockResolvedValue(CREATED)
    vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'notes.txt' })
    vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'notes.txt' })
    vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'READY', displayFilename: 'notes.txt' })
    vi.mocked(extractArtifact).mockResolvedValue({ status: 'COMPLETE' })
    const snapshot = { id: 3, artifactId: 5, kind: 'ARTIFACT', fetchedAt: '2026-03-01T00:00:00Z', attachedAt: '2026-03-01T00:00:00Z' }
    vi.mocked(attachDocumentSource).mockResolvedValue(snapshot)
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()
    await wrapper.find('#title').setValue('March Sync')
    await chooseAFile(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(attachDocumentSource).toHaveBeenCalledWith(7, 42, 5)
    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/documents/42')
    const handoff = readDocumentHandoff()
    expect(handoff?.attachedSources).toEqual([snapshot])
    expect(handoff?.sourceWarning).toBeNull()
  })

  describe('says what went wrong, and what will help', () => {
    /** A refusal from a Brownie server, carrying its own explanation. */
    function refusal(status: number, code: string, detail?: string): ApiRequestError {
      return new ApiRequestError(status, { status, title: 'Refused', code, detail, correlationId: 'c', fields: [], recoveryActions: [] })
    }

    const TEN_MEGABYTE_LIMIT = {
      maxUploadBytes: 10485760,
      uploadMediaTypes: [{ mediaType: 'text/plain', extension: 'txt' }],
      assistSourceMediaTypes: ['text/plain'],
      templateMediaTypes: [],
      trashRetentionDays: 30,
    }
    const TOO_LARGE = refusal(413, 'CONTENT_TOO_LARGE', 'Upload exceeds the maximum allowed size of 10485760 bytes.')

    function signIn() {
      const session = useSessionStore()
      session.status = 'authenticated'
      session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }
    }

    /** Creates a document with a source file of the given size, and answers the warning the workspace is handed. */
    async function createWithAFile(size?: number): Promise<string | null> {
      vi.mocked(listTemplates).mockResolvedValue(ONE_TEMPLATE)
      vi.mocked(createDocument).mockResolvedValue(CREATED)
      signIn()
      const wrapper = await mountWithRouter()
      await flushPromises()
      await wrapper.find('#title').setValue('March Sync')
      await chooseAFile(wrapper, size)
      await wrapper.find('form').trigger('submit')
      await flushPromises()
      expect(wrapper.vm.$router.currentRoute.value.path).toBe('/documents/42')
      return readDocumentHandoff()?.sourceWarning ?? null
    }

    beforeEach(() => {
      vi.mocked(allocateUpload).mockResolvedValue({ id: 5, status: 'UPLOADING', displayFilename: 'notes.txt' })
      vi.mocked(uploadArtifactContent).mockResolvedValue({ id: 5, status: 'SCANNING', displayFilename: 'notes.txt' })
      vi.mocked(completeUpload).mockResolvedValue({ id: 5, status: 'READY', displayFilename: 'notes.txt' })
      vi.mocked(extractArtifact).mockResolvedValue({ status: 'COMPLETE' })
    })

    it('says the server is older than this page, rather than to reload, when it has no templates route', async () => {
      vi.mocked(listTemplates).mockRejectedValue(refusal(404, 'NOT_FOUND', 'No static resource api/v1/workspaces/7/templates.'))
      signIn()
      const wrapper = await mountWithRouter()
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('older than this page and does not have a way to list templates yet')
      expect(wrapper.text()).not.toContain('Try reloading')
      expect(wrapper.text()).not.toContain('No static resource')
    })

    it('says Brownie could not be reached when the templates get no answer', async () => {
      vi.mocked(listTemplates).mockRejectedValue(new TypeError('Failed to fetch'))
      signIn()
      const wrapper = await mountWithRouter()
      await flushPromises()

      expect(wrapper.find('[role="alert"]').text()).toContain('Brownie could not be reached.')
    })

    it('passes on the server\'s explanation when a document cannot be created, and never a bare status number', async () => {
      vi.mocked(listTemplates).mockResolvedValue(ONE_TEMPLATE)
      vi.mocked(createDocument).mockRejectedValueOnce(refusal(429, 'RATE_LIMITED', 'Too many requests in a short time. Wait 12 seconds and try again.'))
      signIn()
      const wrapper = await mountWithRouter()
      await flushPromises()
      await wrapper.find('#title').setValue('March Sync')

      await wrapper.find('form').trigger('submit')
      await flushPromises()
      expect(wrapper.find('[role="alert"]').text()).toBe('Too many requests in a short time. Wait 12 seconds and try again.')

      vi.mocked(createDocument).mockRejectedValueOnce(new ApiRequestError(502, undefined))
      await wrapper.find('form').trigger('submit')
      await flushPromises()
      expect(wrapper.find('[role="alert"]').text()).toContain('Brownie could not be reached. It may not be running')
      expect(wrapper.text()).not.toContain('502')
    })

    it('names the upload limit for a source file over it, and does not send the person to the Sources tab to fail again', async () => {
      vi.mocked(getCapabilities).mockReset().mockResolvedValue(TEN_MEGABYTE_LIMIT)
      vi.mocked(uploadArtifactContent).mockRejectedValue(TOO_LARGE)

      const warning = await createWithAFile(11 * 1024 * 1024)

      expect(warning).toBe('Your source file was not attached: it is larger than the 10 MB upload limit. The document was still created.')
    })

    it('passes on the server\'s explanation of a size refusal when the limit is not known here', async () => {
      vi.mocked(uploadArtifactContent).mockRejectedValue(TOO_LARGE)

      const warning = await createWithAFile(11 * 1024 * 1024)

      expect(warning).toBe(
        'Your source file was not attached. Upload exceeds the maximum allowed size of 10485760 bytes. The document was still created.',
      )
    })

    it('falls back to the server\'s explanation when an older server does not send its limit', async () => {
      vi.mocked(getCapabilities).mockReset().mockResolvedValue({
        uploadMediaTypes: [{ mediaType: 'text/plain', extension: 'txt' }],
        assistSourceMediaTypes: ['text/plain'],
        templateMediaTypes: [],
      } as never)
      vi.mocked(uploadArtifactContent).mockRejectedValue(TOO_LARGE)

      const warning = await createWithAFile(11 * 1024 * 1024)

      expect(warning).toBe(
        'Your source file was not attached. Upload exceeds the maximum allowed size of 10485760 bytes. The document was still created.',
      )
    })

    it('says a file of a kind Brownie cannot use was not attached, and what to attach instead', async () => {
      vi.mocked(uploadArtifactContent).mockRejectedValue(refusal(415, 'UNSUPPORTED_MEDIA_TYPE', 'Package is not a valid ZIP archive.'))

      const warning = await createWithAFile()

      expect(warning).toBe(
        'Your source file was not attached: Brownie cannot use that kind of file as a source. The document was still ' +
          'created; attach a plain-text (.txt) file from the Sources tab instead.',
      )
    })

    it('does not send the person to the Sources tab when the server has no route to attach sources', async () => {
      vi.mocked(attachDocumentSource).mockRejectedValue(refusal(404, 'NOT_FOUND', 'No static resource api/v1/workspaces/7/documents/42/sources.'))

      const warning = await createWithAFile()

      expect(warning).toContain('older than this page and does not have a way to attach sources yet')
      expect(warning).toContain('The document was still created.')
      expect(warning).not.toContain('Sources tab')
    })

    it.each([
      [{ id: 5, status: 'QUARANTINED' as const, rejectionReason: null }, 'Brownie could not finish checking it', 'QUARANTINED'],
      [{ id: 5, status: 'REJECTED' as const, rejectionReason: 'MALWARE_DETECTED' }, 'the malware scan flagged it', 'MALWARE_DETECTED'],
    ])('says why a finished upload was not accepted without its internal state (%o)', async (artifact, reason, internal) => {
      vi.mocked(completeUpload).mockResolvedValue(artifact)

      const warning = await createWithAFile()

      expect(warning).toBe(`Your source file was not attached: ${reason}. The document was still created.`)
      expect(warning).not.toContain(internal)
    })
  })

  it('has no automatically-detectable accessibility violations with templates loaded', async () => {
    vi.mocked(listTemplates).mockResolvedValue([
      { id: 1, displayName: 'Flowing meeting minutes', status: 'ACTIVE', currentActiveVersionId: 1, createdAt: '2026-03-01T00:00:00Z' },
    ])
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = { userId: 1, issuer: 'x', subject: 'y', memberships: [{ workspaceId: 7, role: 'OWNER' }] }

    const wrapper = await mountWithRouter()
    await flushPromises()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
