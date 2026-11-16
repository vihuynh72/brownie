import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import LandingView from '@/views/LandingView.vue'
import { getWelcome } from '@/api/client'
import { findForms, findFormsForPurpose } from '@/landing/findForms'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, getWelcome: vi.fn(), searchForms: vi.fn() }
})
vi.mock('@/landing/findForms', async () => {
  const actual = await vi.importActual<typeof import('@/landing/findForms')>('@/landing/findForms')
  return { ...actual, findForms: vi.fn(), findFormsForPurpose: vi.fn() }
})

const MCP_URL = 'https://api.example.com/mcp'
const SIGN_IN = '/oauth2/authorization/entra'
const GOOGLE_SIGN_IN = '/oauth2/authorization/google'

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

const mounted: VueWrapper[] = []

async function mountLanding(): Promise<VueWrapper> {
  const wrapper = mount(LandingView, { attachTo: document.body })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

function tabs(wrapper: VueWrapper) {
  return wrapper.findAll('[role="tab"]')
}

describe('LandingView', () => {
  beforeEach(() => {
    vi.mocked(getWelcome).mockReset()
    vi.mocked(findForms).mockReset().mockResolvedValue([])
    vi.mocked(findFormsForPurpose).mockReset().mockResolvedValue([])
    document.body.innerHTML = ''
  })

  afterEach(() => {
    for (const wrapper of mounted.splice(0)) wrapper.unmount()
    Reflect.deleteProperty(navigator, 'clipboard')
  })

  /** The library is offered before sign-in: the popular row first, a Sign-in link on each. */
  it('offers popular forms before the install choices, with a Sign in link on each', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    vi.mocked(findForms).mockResolvedValue([
      { id: 'irs-w9', title: 'Form W-9', agency: 'Internal Revenue Service', category: 'Tax', description: 'x' },
      { id: 'uscis-i9', title: 'Form I-9', agency: 'U.S. Citizenship and Immigration Services', category: 'Employment', description: 'x' },
    ])
    const wrapper = await mountLanding()

    expect(wrapper.get('.landing-forms h2').text()).toBe('Search the form library.')
    expect(wrapper.get('.landing-forms__popular').text()).toBe('Popular forms')
    const rows = wrapper.findAll('.landing-forms__row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Form W-9')
    expect(rows[0].text()).toContain('Internal Revenue Service')
    expect(rows[0].get('a.landing-forms__start').attributes('href')).toBe(SIGN_IN)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('names the selected purpose instead of showing an empty search phrase', async () => {
    vi.mocked(findFormsForPurpose).mockResolvedValue([
      { id: 'state-ds11', title: 'Form DS-11', agency: 'State Department', category: 'Travel', description: 'First application.' },
    ])
    const wrapper = await mountLanding()
    await wrapper.findAll('.purpose-picker__option').find((button) => button.attributes('aria-label') === 'Passports & travel')!.trigger('click')
    await flushPromises()
    expect(findFormsForPurpose).toHaveBeenCalledWith('travel')
    expect(wrapper.get('.landing-forms__popular').text()).toBe('Forms for Passports & travel')
  })

  /** Typing exposes matching entries beyond the shorter popular row. */
  it('searches the library as the person types without cutting matches to eight', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()
    vi.useFakeTimers()

    vi.mocked(findForms).mockResolvedValue(
      Array.from({ length: 10 }, (_, i) => ({
        id: `f${i}`, title: `Form ${i}`, agency: 'Agency', category: 'Tax', description: 'x',
      })),
    )
    await wrapper.get('.landing-forms__search').setValue('va')
    await vi.advanceTimersByTimeAsync(300)
    vi.useRealTimers()
    await flushPromises()

    expect(findForms).toHaveBeenLastCalledWith('va')
    expect(wrapper.findAll('.landing-forms__row')).toHaveLength(10)
  })

  it('lets the visitor retry when the catalog cannot be reached', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    vi.mocked(findForms).mockRejectedValue(new Error('down'))
    const wrapper = await mountLanding()

    expect(wrapper.get('.landing-forms').text()).toContain('form library is unavailable')
    vi.mocked(findForms).mockResolvedValue([{ id: 'w9', title: 'Form W-9', agency: 'IRS', category: 'Tax', description: 'x' }])
    await wrapper.get('.landing-forms__empty button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.landing-forms__row').text()).toContain('Form W-9')
    expect(wrapper.text()).toContain('Works where you already are')
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('searches the real catalog when an example is chosen', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()
    await wrapper.findAll('.landing-forms__suggestions button').find((button) => button.text() === 'passport')!.trigger('click')
    expect(findForms).toHaveBeenLastCalledWith('passport')
    expect((wrapper.get('.landing-forms__search').element as HTMLInputElement).value).toBe('passport')
  })

  it('submits a described task and labels the returned results as possible matches', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()
    vi.mocked(findForms).mockResolvedValue([{ id: 'w9', title: 'Form W-9', agency: 'IRS', category: 'Tax', description: 'x' }])
    await wrapper.get('.landing-forms__search').setValue('self-employed tax form')
    await wrapper.get('.landing-forms__composer').trigger('submit')
    await flushPromises()
    expect(findForms).toHaveBeenLastCalledWith('self-employed tax form')
    expect(wrapper.text()).toContain('Possible matches for “self-employed tax form”')
    expect(wrapper.text()).toContain('without personal details')
  })

  it('says the day is free when sign-up is open with a daily allowance', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN_WITH_DAILY_ALLOWANCE', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()

    const primary = wrapper.findAll('a.button--primary').find((a) => a.text() === 'Try it free')
    expect(primary).toBeDefined()
    expect(primary!.attributes('href')).toBe(SIGN_IN)
    expect(wrapper.text()).toContain('Free to use every day')
  })

  it('invites anyone to start when sign-up is simply open', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()

    const primary = wrapper.findAll('a.button--primary').find((a) => a.text() === 'Get started')
    expect(primary).toBeDefined()
    expect(wrapper.text()).not.toContain('Free to use every day')
  })

  it('says the deployment is by invitation only', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'INVITATION_ONLY', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()

    const primary = wrapper.findAll('a.button--primary').find((a) => a.text() === 'Sign in')
    expect(primary).toBeDefined()
    expect(wrapper.text()).toContain('invited people')
  })

  it('does not invent connection details when welcome fails', async () => {
    vi.mocked(getWelcome).mockRejectedValue(new Error('offline'))
    const wrapper = await mountLanding()

    const primary = wrapper.findAll('a.button--primary').find((a) => a.text() === 'Sign in')
    expect(primary).toBeDefined()
    expect(tabs(wrapper).map((t) => t.attributes('data-target'))).toEqual(['browser'])
    expect(wrapper.text()).not.toContain('Add to Claude or ChatGPT')
    expect(wrapper.text()).not.toContain('invited people')
    expect(wrapper.get('[role="status"]').text()).toContain('Refresh the page to try again')
  })

  it('offers only the browser when the deployment serves no chat apps', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: null })
    const wrapper = await mountLanding()

    expect(tabs(wrapper).map((t) => t.attributes('data-target'))).toEqual(['browser'])
    expect(wrapper.text()).toContain('Nothing to install')
  })

  it('switches panel when a tab is clicked', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()

    await wrapper.get('#install-tab-codex').trigger('click')

    expect(wrapper.get('#install-tab-codex').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#install-tab-browser').attributes('aria-selected')).toBe('false')
    expect(wrapper.get('#install-panel').attributes('aria-labelledby')).toBe('install-tab-codex')
    expect(wrapper.get('.install__line').text()).toContain(`codex mcp add brownie --url ${MCP_URL}`)
  })

  it('moves selection and focus with the arrow keys, Home and End', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()
    const tablist = wrapper.get('[role="tablist"]')

    await tablist.trigger('keydown', { key: 'ArrowRight' })
    expect(activeTab(wrapper)).toBe('claude')
    expect(document.activeElement).toBe(wrapper.get('#install-tab-claude').element)

    await tablist.trigger('keydown', { key: 'ArrowRight' })
    expect(activeTab(wrapper)).toBe('chatgpt')

    await tablist.trigger('keydown', { key: 'ArrowLeft' })
    expect(activeTab(wrapper)).toBe('claude')

    await tablist.trigger('keydown', { key: 'End' })
    expect(activeTab(wrapper)).toBe('codex')
    expect(document.activeElement).toBe(wrapper.get('#install-tab-codex').element)

    await tablist.trigger('keydown', { key: 'Home' })
    expect(activeTab(wrapper)).toBe('browser')
    expect(document.activeElement).toBe(wrapper.get('#install-tab-browser').element)

    // Past the last tab it wraps to the first, as a tab list does.
    await tablist.trigger('keydown', { key: 'ArrowLeft' })
    expect(activeTab(wrapper)).toBe('codex')
  })

  it('keeps only the selected tab in the tab order and labelled to the panel', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()

    const selected = tabs(wrapper).filter((t) => t.attributes('tabindex') === '0')
    expect(selected).toHaveLength(1)
    expect(selected[0]!.attributes('data-target')).toBe('browser')
    expect(wrapper.get('#install-panel').attributes('aria-labelledby')).toBe('install-tab-browser')

    await wrapper.get('#install-tab-codex').trigger('click')

    const selectedAfter = tabs(wrapper).filter((t) => t.attributes('tabindex') === '0')
    expect(selectedAfter).toHaveLength(1)
    expect(selectedAfter[0]!.attributes('data-target')).toBe('codex')
    expect(wrapper.get('#install-panel').attributes('aria-labelledby')).toBe('install-tab-codex')
  })

  it('copies the exact install command of the Claude Code tab', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true, writable: true })
    const wrapper = await mountLanding()

    await wrapper.get('#install-tab-claude-code').trigger('click')
    await wrapper.get('.install__copy').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(`claude mcp add --transport http brownie ${MCP_URL}`)
    expect(wrapper.get('.install__copy').text()).toContain('Copied')
  })

  it('selects the line for the keyboard when the clipboard refuses', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true, writable: true })
    const wrapper = await mountLanding()

    await wrapper.get('#install-tab-codex').trigger('click')
    await wrapper.get('.install__copy').trigger('click')
    await flushPromises()

    expect(wrapper.get('.install__copy').text()).toContain('Selected: copy it with your keyboard')
  })

  it('selects the Claude tab from the hero link', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()

    const heroLink = wrapper.findAll('a').find((a) => a.text() === 'Add to Claude or ChatGPT')!
    await heroLink.trigger('click')

    expect(activeTab(wrapper)).toBe('claude')
  })

  it('sends every sign-in affordance to the identity provider, none to an app page', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'INVITATION_ONLY', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()

    const signIns = wrapper.findAll(`a[href="${SIGN_IN}"]`)
    expect(signIns.length).toBeGreaterThanOrEqual(3)
    expect(wrapper.find('a[href="/signin"]').exists()).toBe(false)
  })

  it('has no automatically-detectable accessibility violations', async () => {
    vi.mocked(getWelcome).mockResolvedValue({ signUp: 'OPEN_WITH_DAILY_ALLOWANCE', mcpServerUrl: MCP_URL })
    const wrapper = await mountLanding()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

it('points every sign-in action at Google when the deployment runs it', async () => {
    vi.mocked(getWelcome).mockResolvedValue({
      signUp: 'OPEN_WITH_DAILY_ALLOWANCE', mcpServerUrl: MCP_URL, signInProviders: ['google', 'entra'],
    })
    const wrapper = await mountLanding()

    const tryIt = wrapper.findAll('a.button--primary').find((a) => a.text() === 'Try it free')
    expect(tryIt?.attributes('href')).toBe(GOOGLE_SIGN_IN)
    const signIn = wrapper.findAll('a').find((a) => a.text() === 'Sign in')
    expect(signIn?.attributes('href')).toBe(GOOGLE_SIGN_IN)

    const emailCode = wrapper.get('a.landing__email-code')
    expect(emailCode.attributes('href')).toBe(SIGN_IN)
    expect(emailCode.text()).toContain('Use an email code instead')
  })

  it('does not offer an email code when only Google is available', async () => {
    vi.mocked(getWelcome).mockResolvedValue({
      signUp: 'OPEN_WITH_DAILY_ALLOWANCE', mcpServerUrl: MCP_URL, signInProviders: ['google'],
    })
    const wrapper = await mountLanding()
    expect(wrapper.find('a[href="/oauth2/authorization/entra"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('code sent to your email')
    expect(wrapper.text()).toContain('Sign in to Brownie.')
  })

  it('does not point at Entra while sign-in providers are still loading', async () => {
    let resolveWelcome!: (value: Awaited<ReturnType<typeof getWelcome>>) => void
    vi.mocked(getWelcome).mockReturnValue(new Promise((resolve) => { resolveWelcome = resolve }))
    const wrapper = mount(LandingView, { attachTo: document.body })
    mounted.push(wrapper)
    expect(wrapper.find('a[href="/oauth2/authorization/entra"]').exists()).toBe(false)
    resolveWelcome({ signUp: 'OPEN', mcpServerUrl: MCP_URL, signInProviders: ['google'] })
    await flushPromises()
    expect(wrapper.get('.landing__nav a:last-child').attributes('href')).toBe(GOOGLE_SIGN_IN)
  })

  it('keeps the email-code button primary with Google as a small early-access link', async () => {
    vi.mocked(getWelcome).mockResolvedValue({
      signUp: 'OPEN_WITH_DAILY_ALLOWANCE', mcpServerUrl: MCP_URL, signInProviders: ['entra', 'google'],
    })
    const wrapper = await mountLanding()

    const tryIt = wrapper.findAll('a.button--primary').find((a) => a.text() === 'Try it free')
    expect(tryIt?.attributes('href')).toBe(SIGN_IN)
    const emailCode = wrapper.get('a.landing__email-code')
    expect(emailCode.attributes('href')).toBe(GOOGLE_SIGN_IN)
    expect(emailCode.text()).toContain('Continue with Google (early access)')
  })

  it('keeps the single email-code action when Google is not enabled', async () => {
    vi.mocked(getWelcome).mockResolvedValue({
      signUp: 'OPEN_WITH_DAILY_ALLOWANCE', mcpServerUrl: MCP_URL, signInProviders: ['entra'],
    })
    const wrapper = await mountLanding()

    const tryIt = wrapper.findAll('a.button--primary').find((a) => a.text() === 'Try it free')
    expect(tryIt?.attributes('href')).toBe(SIGN_IN)
    expect(wrapper.find('a.landing__email-code').exists()).toBe(false)
  })
})

function activeTab(wrapper: VueWrapper): string | undefined {
  return tabs(wrapper).find((t) => t.attributes('aria-selected') === 'true')?.attributes('data-target')
}
