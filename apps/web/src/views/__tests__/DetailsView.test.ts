import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import DetailsView from '@/views/DetailsView.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    listDetails: vi.fn(),
    saveDetail: vi.fn(),
    deleteDetail: vi.fn(),
    deleteAllDetails: vi.fn(),
  }
})

import { deleteAllDetails, deleteDetail, listDetails, saveDetail, type SavedDetailResponse } from '@/api/client'

function detail(over: Partial<SavedDetailResponse>): SavedDetailResponse {
  return {
    key: 'person.first_name',
    label: 'First name',
    group: 'IDENTITY',
    sensitive: false,
    value: 'Maya',
    hint: null,
    origin: 'AUTO',
    source: { documentId: 42, label: null, title: 'W-9' },
    savedAt: '2026-10-28T12:00:00Z',
    confirmedAt: '2026-10-28T12:00:00Z',
    ...over,
  }
}

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = { userId: 1, issuer: 'x', subject: 'y', allowance: 'FULL', invitationRequested: false, memberships: [{ workspaceId: 7, role: 'OWNER' }] }
}

async function mountPage(sealingAvailable = true, rows: SavedDetailResponse[] = [detail({})]) {
  vi.mocked(listDetails).mockResolvedValue({ details: rows, sealingAvailable })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/details', name: 'details', component: DetailsView },
      { path: '/documents/:id', name: 'document', component: { template: '<div/>' } },
    ],
  })
  await router.push('/details')
  await router.isReady()
  const wrapper = mount(DetailsView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  setActivePinia(createPinia())
  signIn()
  vi.clearAllMocks()
})

describe('DetailsView', () => {
  it('lists saved details grouped under their section', async () => {
    const rows = [
      detail({ key: 'person.first_name', label: 'First name', group: 'IDENTITY', value: 'Maya' }),
      detail({ key: 'contact.email', label: 'Email', group: 'CONTACT', value: 'maya@example.com', origin: 'MANUAL', source: undefined }),
    ]
    const wrapper = await mountPage(true, rows)
    expect(wrapper.get('h2').text()).toBe('Identity')
    expect(wrapper.text()).toContain('Maya')
    expect(wrapper.text()).toContain('maya@example.com')
    expect(wrapper.text()).toContain('Added by you')
    expect(wrapper.text()).toContain('W-9')
  })

  it('lets a non-sensitive row be edited inline and saved', async () => {
    const saved = detail({ value: 'Jordan' })
    vi.mocked(saveDetail).mockResolvedValue(saved)
    const wrapper = await mountPage(true, [detail({ value: 'Maya' })])
    await wrapper.findAll('button').find((b) => b.text() === 'Edit')?.trigger('click')
    await flushPromises()
    await wrapper.get('input.details__edit-input').setValue('Jordan')
    await flushPromises()
    const save = wrapper.findAll('button').find((b) => b.text() === 'Save')
    await save?.trigger('click')
    await flushPromises()
    expect(saveDetail).toHaveBeenCalledWith('person.first_name', 'Jordan')
    expect(wrapper.text()).toContain('Jordan')
  })

  it('confirms before deleting a row', async () => {
    vi.mocked(deleteDetail).mockResolvedValue(undefined)
    const wrapper = await mountPage(true, [detail({})])
    const buttons = wrapper.findAll('button')
    const deleteBtn = buttons.find((b) => b.text() === 'Delete')
    await deleteBtn?.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Delete "First name"?')
    const confirm = wrapper.findAll('button').find((b) => b.text() === 'Delete' && b.classes().includes('button--danger'))
    await confirm?.trigger('click')
    await flushPromises()
    expect(deleteDetail).toHaveBeenCalledWith('person.first_name')
    expect(wrapper.text()).toContain('Nothing here yet')
  })

  it('masks a sensitive row and never offers to edit it', async () => {
    const sensitive = detail({ key: 'sensitive.ssn', label: 'Social security number', group: 'SENSITIVE', sensitive: true, value: null, hint: '•••-••-6789', origin: 'MANUAL', source: undefined })
    const wrapper = await mountPage(true, [sensitive])
    expect(wrapper.text()).toContain('•••-••-6789')
    expect(wrapper.text()).not.toContain('123-45-6789')
    const edit = wrapper.findAll('button').find((b) => b.text() === 'Edit')
    expect(edit).toBeUndefined()
  })

  it('disables sensitive options in the picker when sealing is unavailable', async () => {
    const wrapper = await mountPage(false, [detail({})])
    await wrapper.get('.details__add .button--small').trigger('click') // open picker
    await flushPromises()
    const sensitive = wrapper.findAll('option').find((o) => o.attributes('value') === 'sensitive.ssn')
    expect(sensitive?.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('cannot be saved')
  })

  it('confirms before deleting every detail', async () => {
    vi.mocked(deleteAllDetails).mockResolvedValue(undefined)
    const wrapper = await mountPage(true, [detail({})])
    await wrapper.get('.details__danger .button--ghost').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('cannot be undone')
    const confirm = wrapper.findAll('button').find((b) => b.text() === 'Delete all')
    await confirm?.trigger('click')
    await flushPromises()
    expect(deleteAllDetails).toHaveBeenCalled()
    expect(wrapper.text()).toContain('Nothing here yet')
  })

  it('shows the empty state with no rows', async () => {
    const wrapper = await mountPage(true, [])
    expect(wrapper.text()).toContain('Brownie saves the details from forms you approve, so the next form fills faster. Nothing here yet.')
  })

  it('stays accessible', async () => {
    const wrapper = await mountPage(true, [detail({})])
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
