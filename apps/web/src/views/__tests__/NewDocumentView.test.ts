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

  async function chooseAFile(wrapper: Awaited<ReturnType<typeof mountWithRouter>>) {
    const input = wrapper.find('#source')
    const file = new File(['notes'], 'notes.txt', { type: 'text/plain' })
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
