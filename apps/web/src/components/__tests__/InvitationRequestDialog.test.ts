import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import InvitationRequestDialog from '@/components/InvitationRequestDialog.vue'
import { useSessionStore } from '@/stores/session'
import { axe } from '@/test/axe'
import { ApiRequestError, requestInvitation } from '@/api/client'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, requestInvitation: vi.fn() }
})

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

const mounted: VueWrapper[] = []

async function openDialog(): Promise<VueWrapper> {
  const wrapper = mount(InvitationRequestDialog, { attachTo: document.body })
  mounted.push(wrapper)
  wrapper.vm.open()
  await flushPromises()
  return wrapper
}

describe('InvitationRequestDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const session = useSessionStore()
    session.status = 'authenticated'
    session.identity = {
      userId: 1, issuer: 'i', subject: 's', allowance: 'FREE_DAILY', invitationRequested: false,
      memberships: [{ workspaceId: 42, role: 'OWNER' }],
    }
    vi.mocked(requestInvitation).mockReset().mockResolvedValue(undefined)
    document.body.innerHTML = ''
  })

  afterEach(() => {
    for (const wrapper of mounted.splice(0)) wrapper.unmount()
  })

  it('counts the note against its 500-character maximum', async () => {
    const wrapper = await openDialog()
    const note = wrapper.get('textarea')

    await note.setValue('a'.repeat(100))
    expect(wrapper.get('.invitation-request__count').text()).toBe('100/500')

    await note.setValue('a'.repeat(500))
    expect(wrapper.get('.invitation-request__count').text()).toBe('500/500')
    expect(note.attributes('maxlength')).toBe('500')
    expect(note.attributes('aria-describedby')).toBeDefined()
  })

  it('shows the sending state while the request is out', async () => {
    vi.mocked(requestInvitation).mockReturnValue(new Promise(() => {}))
    const wrapper = await openDialog()

    await wrapper.get('form').trigger('submit.prevent')

    const primary = wrapper.get('button.button--primary')
    expect(primary.text()).toBe('Sending…')
    expect(primary.attributes('aria-disabled')).toBeDefined()
  })

  it('says why the request could not be sent', async () => {
    vi.mocked(requestInvitation).mockRejectedValue(new ApiRequestError(503, { detail: 'The database is away.' } as never))
    const wrapper = await openDialog()

    await wrapper.get('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('database')
  })

  it('falls back to a plain message when there is nothing better to say', async () => {
    vi.mocked(requestInvitation).mockRejectedValue(new ApiRequestError(409, undefined))
    const wrapper = await openDialog()

    await wrapper.get('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('Brownie could not send your request. Try again in a moment.')
  })

  it('sends the note and announces it was sent', async () => {
    const wrapper = await openDialog()
    await wrapper.get('textarea').setValue('a reason')

    await wrapper.get('form').trigger('submit.prevent')
    await flushPromises()

    expect(requestInvitation).toHaveBeenCalledWith('a reason')
    expect(wrapper.emitted('sent')).toHaveLength(1)
    expect(useSessionStore().invitationRequested).toBe(true)
  })

  it('has no automatically-detectable accessibility violations', async () => {
    const wrapper = await openDialog()
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
