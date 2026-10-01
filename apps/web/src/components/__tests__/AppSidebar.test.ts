import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory, type Router } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'
import { useSessionStore } from '@/stores/session'
import { useTemplatesStore } from '@/stores/templates'
import { axe } from '@/test/axe'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, listTemplates: vi.fn(), logout: vi.fn(), createDocument: vi.fn(), trashTemplate: vi.fn() }
})

import { ApiRequestError, createDocument, listTemplates, trashTemplate, type DocumentResponse, type TemplateResponse } from '@/api/client'

const stub = { template: '<div />' }

function makeRouter(): Router {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: stub },
      { path: '/signin', name: 'signin', component: stub },
      { path: '/chat', name: 'chat', component: stub },
      { path: '/trash', name: 'trash', component: stub },
      { path: '/your-data', name: 'your-data', component: stub },
      { path: '/connections', name: 'connections', component: stub },
      { path: '/templates/new', name: 'new-template', component: stub },
      { path: '/documents/:documentId', name: 'workspace', component: stub },
    ],
  })
}

let router: Router

async function mountSidebar(props: { open: boolean; docked: boolean }, path = '/') {
  router = makeRouter()
  router.push(path)
  await router.isReady()
  return mount(AppSidebar, { props, global: { plugins: [router] }, attachTo: document.body })
}

function pressKey(key: string, shiftKey = false): void {
  document.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true, cancelable: true }))
}

/** A key pressed on whatever holds focus, bubbling up from there the way a real one does. */
function pressOnFocused(key: string, options: KeyboardEventInit = {}): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...options })
  ;(document.activeElement ?? document.body).dispatchEvent(event)
  return event
}

function flushPromises(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

function signIn(): void {
  const session = useSessionStore()
  session.status = 'authenticated'
  session.identity = {
    userId: 1,
    issuer: 'x',
    subject: 'y',
    displayName: 'Vi Huynh',
    memberships: [{ workspaceId: 7, role: 'OWNER' }],
  }
}

function template(id: number, displayName: string, overrides: Partial<TemplateResponse> = {}): TemplateResponse {
  return { id, displayName, status: 'ACTIVE', currentActiveVersionId: id * 10, createdAt: '2026-09-01T10:00:00Z', trashedAt: null, ...overrides }
}

const CLUB = template(1, 'Club minutes')
const GRANT = template(2, 'Grant report')
const INVOICE = template(3, 'Invoice')

async function mountWithTemplates(list: TemplateResponse[], props = { open: true, docked: true }, path = '/') {
  vi.mocked(listTemplates).mockResolvedValue(list)
  signIn()
  const wrapper = await mountSidebar(props, path)
  await flushPromises()
  return wrapper
}

function startButton(id: number): HTMLElement {
  const button = document.getElementById(`template-start-${id}`)
  if (!button) throw new Error(`No start button for template ${id}`)
  return button
}

function moreButton(id: number): HTMLElement {
  const button = document.getElementById(`template-more-${id}`)
  if (!button) throw new Error(`No More button for template ${id}`)
  return button
}

function openMenuElement(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[role="menu"]')
}

function trashItem(): HTMLElement {
  const item = document.querySelector<HTMLElement>('[role="menuitem"]')
  if (!item) throw new Error('The menu is not open')
  return item
}

function created(id: number): DocumentResponse {
  return { id } as DocumentResponse
}

function noticeElement(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.sidebar__notice')
}

/** Opens the row's menu from its "More" button, the way a keyboard does, and chooses "Move to Trash Bin". */
async function trashFromMenu(id: number): Promise<void> {
  moreButton(id).focus()
  moreButton(id).click()
  await flushPromises()
  trashItem().click()
  await flushPromises()
}

function problem(status: number, code: string, detail: string) {
  return { status, title: 't', code, detail, correlationId: 'c', fields: [], recoveryActions: [] }
}

describe('AppSidebar', () => {
  let wrapper: VueWrapper | null = null

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(listTemplates).mockReset().mockResolvedValue([])
    vi.mocked(createDocument).mockReset()
    vi.mocked(trashTemplate).mockReset()
    document.body.innerHTML = ''
    useSessionStore().status = 'anonymous'
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('offers sign-in instead of an account while nobody is signed in, and says why the template list is empty', async () => {
    wrapper = await mountSidebar({ open: true, docked: true })

    expect(wrapper.find('a[href="/signin"]').text()).toContain('Sign In')
    expect(wrapper.findAll('button').find((b) => b.text() === 'Sign out')).toBeUndefined()
    expect(wrapper.text()).toContain('Sign in to see your templates')
    expect(listTemplates).not.toHaveBeenCalled()
  })

  /** On every page load a signed-in person is "loading" first; telling them to sign in then is simply wrong. */
  it('says it is loading while it does not yet know who is signed in, not that they should sign in', async () => {
    useSessionStore().status = 'loading'
    wrapper = await mountSidebar({ open: true, docked: true })

    expect(wrapper.text()).not.toContain('Sign in to see your templates')
    expect(wrapper.find('.sidebar__note').text()).toBe('Loading…')
  })

  it('lists the workspace templates that can actually start a document, each as a button', async () => {
    wrapper = await mountWithTemplates([
      CLUB,
      template(2, 'Half-taught draft', { status: 'DRAFT', currentActiveVersionId: null }),
    ])

    expect(listTemplates).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('Club minutes')
    expect(wrapper.text()).not.toContain('Half-taught draft')
    expect(startButton(1).tagName).toBe('BUTTON')
    expect(startButton(1).textContent?.trim()).toBe('Club minutes')
    expect(document.getElementById(startButton(1).getAttribute('aria-describedby')!)?.textContent).toBe(
      'Starts a new document from this template.',
    )
    // Nothing links to a separate new-document page any more.
    expect(wrapper.find('a[href^="/documents/new"]').exists()).toBe(false)
    // Where what is kept, for how long, and how to delete all of it can always be found.
    expect(wrapper.find('a[href="/your-data"]').text()).toBe('Your data')
    expect(wrapper.find('a[href="/connections"]').text()).toBe('Connections')
    expect(wrapper.find('a[href="/trash"]').text()).toBe('Trash Bin')
  })

  it('says so, without promising anything, when there are no templates', async () => {
    wrapper = await mountWithTemplates([])

    expect(wrapper.find('.sidebar__note').text()).toBe('You have no templates.')
  })

  it('starts a document from a template at once, named after it and today, and opens it', async () => {
    vi.mocked(createDocument).mockResolvedValue(created(55))
    wrapper = await mountWithTemplates([CLUB])

    startButton(1).click()
    await flushPromises()

    const today = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date())
    expect(createDocument).toHaveBeenCalledTimes(1)
    const [workspaceId, idempotencyKey, body] = vi.mocked(createDocument).mock.calls[0]!
    expect(workspaceId).toBe(7)
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(body).toEqual({
      title: `Club minutes, ${today}`,
      templateId: 1,
      templateVersionId: 10,
      fields: {},
      initialRevisionReason: 'Started from a template in the sidebar.',
    })
    expect(router.currentRoute.value.fullPath).toBe('/documents/55')
    // Docked, the sidebar stays where it is.
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('closes a drawer once the new document is open, as following a link does', async () => {
    vi.mocked(createDocument).mockResolvedValue(created(56))
    wrapper = await mountWithTemplates([CLUB], { open: true, docked: false })

    startButton(1).click()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/documents/56')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  /** Two presses in quick succession would otherwise make two documents. */
  it('marks the row busy while the document is being made and ignores a second press', async () => {
    let finish: (document: DocumentResponse) => void = () => {}
    vi.mocked(createDocument).mockReturnValue(new Promise((resolve) => (finish = resolve)))
    wrapper = await mountWithTemplates([CLUB, GRANT])

    startButton(1).click()
    await flushPromises()
    expect(startButton(1).closest('li')!.getAttribute('aria-busy')).toBe('true')
    expect(startButton(2).closest('li')!.hasAttribute('aria-busy')).toBe(false)
    // Still focusable: disabling it would drop the focus it holds.
    expect(startButton(1).hasAttribute('disabled')).toBe(false)
    expect(startButton(1).getAttribute('aria-disabled')).toBe('true')

    startButton(1).click()
    startButton(2).click()
    await flushPromises()
    expect(createDocument).toHaveBeenCalledTimes(1)

    finish(created(57))
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/documents/57')
  })

  it('says in words why a document could not be started, keeps the row, and stays on the page', async () => {
    vi.mocked(createDocument).mockRejectedValue(new ApiRequestError(503, undefined))
    wrapper = await mountWithTemplates([CLUB])
    startButton(1).focus()

    startButton(1).click()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Could not start a document from "Club minutes". Brownie is briefly unavailable. Try again in a minute.',
    )
    expect(startButton(1).closest('li')!.hasAttribute('aria-busy')).toBe(false)
    expect(document.activeElement).toBe(startButton(1))
    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  /** Trashed from another tab since this list was loaded: the row is stale, and the server's own words say what to do. */
  it('takes a template that is already in the Trash Bin off the list, says so, and moves focus to the next row', async () => {
    vi.mocked(createDocument).mockRejectedValue(
      new ApiRequestError(
        409,
        problem(409, 'TEMPLATE_TRASHED', 'This template is in the Trash Bin. Restore it to start a document from it.'),
      ),
    )
    wrapper = await mountWithTemplates([CLUB, GRANT])
    startButton(1).focus()

    startButton(1).click()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Could not start a document from "Club minutes". This template is in the Trash Bin. Restore it to start a document from it.',
    )
    expect(document.getElementById('template-start-1')).toBeNull()
    expect(document.activeElement).toBe(startButton(2))
    // A Trash Bin page open at the same time lists it too.
    expect(useTemplatesStore().trashedSerial).toBe(1)
  })

  it('when a template found in the Trash Bin already was the last row, moves focus to the row above, and to the + when none are left', async () => {
    vi.mocked(createDocument).mockRejectedValue(
      new ApiRequestError(409, problem(409, 'TEMPLATE_TRASHED', 'This template is in the Trash Bin.')),
    )
    wrapper = await mountWithTemplates([CLUB, GRANT])

    startButton(2).focus()
    startButton(2).click()
    await flushPromises()
    expect(document.activeElement).toBe(startButton(1))

    startButton(1).click()
    await flushPromises()
    expect(document.activeElement).toBe(document.getElementById('sidebar-add-template'))
    expect(wrapper.find('.sidebar__note').text()).toBe('You have no templates.')
  })

  /** A slow answer lands after the person has gone somewhere else; the document must not drag them back to it. */
  it('offers a link to a document that was made after the person moved on to another page, instead of opening it', async () => {
    let finish: (document: DocumentResponse) => void = () => {}
    vi.mocked(createDocument).mockReturnValue(new Promise((resolve) => (finish = resolve)))
    wrapper = await mountWithTemplates([CLUB], { open: true, docked: false })
    startButton(1).click()
    await flushPromises()

    await router.push('/chat')
    await flushPromises()
    const elsewhere = document.createElement('button')
    document.body.appendChild(elsewhere)
    elsewhere.focus()
    finish(created(58))
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/chat')
    expect(wrapper.emitted('close')).toBeUndefined()
    const today = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date())
    const notice = noticeElement()!
    expect(notice.textContent?.replace(/\s+/g, ' ').trim()).toBe(`Started "Club minutes, ${today}". Open it`)
    expect(notice.querySelector('a')!.getAttribute('href')).toBe('/documents/58')
    // Said where the person can find it, not announced over whatever they have moved on to.
    expect(notice.hasAttribute('role')).toBe(false)
    expect(notice.hasAttribute('aria-live')).toBe(false)
    expect(document.activeElement).toBe(elsewhere)
    expect(startButton(1).closest('li')!.hasAttribute('aria-busy')).toBe(false)
    // The sidebar's own status line tells a screen reader, since nothing took focus.
    expect(wrapper.find('[role="status"]').text()).toBe(`Started "Club minutes, ${today}". It can be opened from the sidebar.`)
    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  /** A page with unsaved work may keep the person where they are; the document made meanwhile is offered, not lost. */
  it('offers the new document when the page the person is on declines to be left', async () => {
    vi.mocked(createDocument).mockResolvedValue(created(59))
    wrapper = await mountWithTemplates([CLUB], { open: true, docked: false })
    router.beforeEach((to) => !to.path.startsWith('/documents/'))
    startButton(1).click()
    await flushPromises()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
    expect(noticeElement()!.querySelector('a')!.getAttribute('href')).toBe('/documents/59')
    expect(wrapper.find('[role="status"]').text()).toContain('It can be opened from the sidebar.')
  })

  it('says in words that a row is starting a document, and keeps its menu shut until the answer comes', async () => {
    let finish: (document: DocumentResponse) => void = () => {}
    vi.mocked(createDocument).mockReturnValue(new Promise((resolve) => (finish = resolve)))
    wrapper = await mountWithTemplates([CLUB, GRANT])
    startButton(1).focus()

    startButton(1).click()
    await flushPromises()

    expect(startButton(1).getAttribute('aria-label')).toBe('Starting a document from Club minutes…')
    expect(startButton(1).querySelector('.template-row__status')!.textContent).toBe('Starting…')
    expect(startButton(2).hasAttribute('aria-label')).toBe(false)
    expect(startButton(2).querySelector('.template-row__status')).toBeNull()
    expect(moreButton(1).getAttribute('aria-disabled')).toBe('true')
    expect(moreButton(2).hasAttribute('aria-disabled')).toBe(false)
    expect(await axe(wrapper.element)).toHaveNoViolations()

    moreButton(1).click()
    await flushPromises()
    expect(openMenuElement()).toBeNull()
    pressOnFocused('ArrowDown')
    await flushPromises()
    expect(openMenuElement()).toBeNull()
    pressOnFocused('F10', { shiftKey: true })
    await flushPromises()
    expect(openMenuElement()).toBeNull()
    const rightClick = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 120, clientY: 300, button: 2 })
    startButton(1).dispatchEvent(rightClick)
    await flushPromises()
    expect(openMenuElement()).toBeNull()
    expect(moreButton(1).getAttribute('aria-expanded')).toBe('false')

    finish(created(59))
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/documents/59')
    expect(startButton(1).hasAttribute('aria-label')).toBe(false)
    expect(startButton(1).querySelector('.template-row__status')).toBeNull()
    expect(moreButton(1).hasAttribute('aria-disabled')).toBe(false)
  })

  it('gives every row a "More" button, named for its template, that opens a menu on its one item', async () => {
    wrapper = await mountWithTemplates([CLUB])
    const more = moreButton(1)

    expect(more.textContent?.trim()).toBe('More for Club minutes')
    expect(more.getAttribute('aria-haspopup')).toBe('menu')
    expect(more.getAttribute('aria-expanded')).toBe('false')
    expect(more.hasAttribute('aria-controls')).toBe(false)
    // Reachable by Tab, never taken out of the order to hide it.
    expect(more.tabIndex).toBe(0)

    more.focus()
    more.click()
    await flushPromises()

    const menu = openMenuElement()!
    expect(menu.getAttribute('aria-labelledby')).toBe(more.id)
    expect(more.getAttribute('aria-expanded')).toBe('true')
    expect(more.getAttribute('aria-controls')).toBe(menu.id)
    const items = menu.querySelectorAll('[role="menuitem"]')
    expect(Array.from(items).map((item) => item.textContent?.trim())).toEqual(['Move to Trash Bin'])
    expect(document.activeElement).toBe(items[0])
  })

  it('closes the menu on Escape and hands focus back to the "More" button, without closing the drawer it sits in', async () => {
    wrapper = await mountWithTemplates([CLUB], { open: true, docked: false })
    moreButton(1).focus()

    pressOnFocused('ArrowDown')
    await flushPromises()
    expect(document.activeElement).toBe(trashItem())

    const escape = pressOnFocused('Escape')
    await flushPromises()

    expect(escape.defaultPrevented).toBe(true)
    expect(openMenuElement()).toBeNull()
    expect(document.activeElement).toBe(moreButton(1))
    expect(moreButton(1).getAttribute('aria-expanded')).toBe('false')
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('closes the menu when its button is pressed again, and keeps focus on the button', async () => {
    wrapper = await mountWithTemplates([CLUB])
    moreButton(1).click()
    await flushPromises()
    expect(openMenuElement()).not.toBeNull()

    moreButton(1).click()
    await flushPromises()

    expect(openMenuElement()).toBeNull()
    expect(document.activeElement).toBe(moreButton(1))
  })

  it.each([
    ['Shift+F10', { key: 'F10', shiftKey: true }],
    ['the context-menu key', { key: 'ContextMenu' }],
  ])('opens the same menu from a focused row with %s, and Escape goes back to that row', async (_name, key) => {
    wrapper = await mountWithTemplates([CLUB])
    startButton(1).focus()

    const event = pressOnFocused(key.key, { shiftKey: 'shiftKey' in key ? key.shiftKey : false })
    await flushPromises()

    expect(event.defaultPrevented).toBe(true)
    expect(openMenuElement()!.getAttribute('aria-labelledby')).toBe('template-more-1')
    expect(document.activeElement).toBe(trashItem())

    pressOnFocused('Escape')
    await flushPromises()
    expect(document.activeElement).toBe(startButton(1))
  })

  it('opens the menu at the pointer on a right-click, instead of the browser\'s own', async () => {
    Object.defineProperty(document.documentElement, 'clientWidth', { value: 1000, configurable: true })
    Object.defineProperty(document.documentElement, 'clientHeight', { value: 800, configurable: true })
    wrapper = await mountWithTemplates([CLUB])

    const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 120, clientY: 300, button: 2 })
    startButton(1).dispatchEvent(event)
    await flushPromises()

    expect(event.defaultPrevented).toBe(true)
    const menu = openMenuElement()!
    expect(menu.style.top).toBe('300px')
    expect(menu.style.left).toBe('120px')
    expect(document.activeElement).toBe(trashItem())
    Reflect.deleteProperty(document.documentElement, 'clientWidth')
    Reflect.deleteProperty(document.documentElement, 'clientHeight')
  })

  it('closes the menu on a press anywhere else, leaving focus to wherever that press puts it', async () => {
    wrapper = await mountWithTemplates([CLUB])
    moreButton(1).click()
    await flushPromises()

    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
    await flushPromises()

    expect(openMenuElement()).toBeNull()
    expect(moreButton(1).getAttribute('aria-expanded')).toBe('false')
  })

  it('closes the menu when the sidebar is collapsed under it', async () => {
    wrapper = await mountWithTemplates([CLUB])
    moreButton(1).click()
    await flushPromises()

    await wrapper.setProps({ open: false })
    await flushPromises()

    expect(openMenuElement()).toBeNull()
  })

  /**
   * Focus goes to the sentence rather than to the next row: a screen reader reads it out without it
   * being a second live region beside the page's own, and its link is the next Tab stop.
   */
  it('moves a template to the Trash Bin from the menu, says where it went, and moves focus to that sentence', async () => {
    vi.mocked(trashTemplate).mockResolvedValue({ ...CLUB, trashedAt: '2026-09-29T12:00:00Z' })
    wrapper = await mountWithTemplates([CLUB, GRANT, INVOICE])

    await trashFromMenu(1)

    expect(trashTemplate).toHaveBeenCalledWith(7, 1)
    expect(openMenuElement()).toBeNull()
    expect(document.getElementById('template-start-1')).toBeNull()
    const notice = noticeElement()!
    expect(notice.textContent?.replace(/\s+/g, ' ').trim()).toBe('Moved "Club minutes" to the Trash Bin.')
    expect(notice.hasAttribute('role')).toBe(false)
    expect(notice.getAttribute('tabindex')).toBe('-1')
    expect(document.activeElement).toBe(notice)
    const link = notice.querySelector('a')!
    expect(link.getAttribute('href')).toBe('/trash')
    expect(link.textContent).toBe('the Trash Bin')
    // A Trash Bin page open at the same time lists it too.
    expect(useTemplatesStore().trashedSerial).toBe(1)
  })

  it('leaves focus where the person put it while the template was on its way to the Trash Bin', async () => {
    let finish: () => void = () => {}
    vi.mocked(trashTemplate).mockReturnValue(
      new Promise((resolve) => (finish = () => resolve({ ...CLUB, trashedAt: '2026-09-29T12:00:00Z' }))),
    )
    wrapper = await mountWithTemplates([CLUB, GRANT])
    moreButton(1).focus()
    moreButton(1).click()
    await flushPromises()
    trashItem().click()
    await flushPromises()

    startButton(2).focus()
    finish()
    await flushPromises()

    expect(document.getElementById('template-start-1')).toBeNull()
    expect(noticeElement()).not.toBeNull()
    expect(document.activeElement).toBe(startButton(2))
  })

  it('says "the Trash Bin" without a link to it when that is the page already open', async () => {
    vi.mocked(trashTemplate).mockResolvedValue({ ...CLUB, trashedAt: '2026-09-29T12:00:00Z' })
    wrapper = await mountWithTemplates([CLUB, GRANT], { open: true, docked: true }, '/trash')

    await trashFromMenu(1)

    const notice = noticeElement()!
    expect(notice.textContent?.replace(/\s+/g, ' ').trim()).toBe('Moved "Club minutes" to the Trash Bin.')
    expect(notice.querySelector('a')).toBeNull()
    expect(document.activeElement).toBe(notice)
    expect(useTemplatesStore().trashedSerial).toBe(1)
  })

  it('keeps the row and says why when the server cannot move templates to the Trash Bin', async () => {
    vi.mocked(trashTemplate).mockRejectedValue(
      new ApiRequestError(404, problem(404, 'NOT_FOUND', 'No static resource api/v1/workspaces/7/templates/1/trash.')),
    )
    wrapper = await mountWithTemplates([CLUB])
    moreButton(1).focus()
    moreButton(1).click()
    await flushPromises()

    trashItem().click()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe(
      'Could not move "Club minutes" to the Trash Bin. The Brownie server that answered is older than this page and ' +
        'does not have a Trash Bin for templates yet. Reloading will not change that: the server needs to be updated and restarted.',
    )
    expect(startButton(1)).toBeTruthy()
    expect(document.activeElement).toBe(moreButton(1))
    expect(noticeElement()).toBeNull()
    expect(useTemplatesStore().trashedSerial).toBe(0)
  })

  it('forgets the notice once the person moves on to another page', async () => {
    vi.mocked(trashTemplate).mockResolvedValue({ ...CLUB, trashedAt: '2026-09-29T12:00:00Z' })
    wrapper = await mountWithTemplates([CLUB, GRANT])
    moreButton(1).click()
    await flushPromises()
    trashItem().click()
    await flushPromises()
    expect(noticeElement()).not.toBeNull()

    await router.push('/chat')
    await flushPromises()

    expect(noticeElement()).toBeNull()
  })

  /** Opening a drawer that nothing has focus in leaves a keyboard user tabbing through the page behind it. */
  it('takes focus when it opens as a drawer', async () => {
    wrapper = await mountSidebar({ open: false, docked: false })

    await wrapper.setProps({ open: true })
    await flushPromises()

    const close = wrapper.findAll('button').find((b) => b.text() === 'Close menu')
    expect(document.activeElement).toBe(close!.element)
  })

  it('closes a drawer on Escape, and when a link inside it is followed', async () => {
    wrapper = await mountSidebar({ open: true, docked: false })

    pressKey('Escape')
    expect(wrapper.emitted('close')).toHaveLength(1)

    await wrapper.find('a[href="/trash"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  /** Docked it is ordinary page furniture: Escape belongs to whatever else is open, and a link goes nowhere special. */
  it('does not answer Escape, or close itself on a link, while it is docked', async () => {
    wrapper = await mountSidebar({ open: true, docked: true })

    pressKey('Escape')
    await wrapper.find('a[href="/trash"]').trigger('click')

    expect(wrapper.emitted('close')).toBeUndefined()
  })

  /** Tab must not walk out of a drawer onto the page it is covering. */
  it('wraps Tab at its own ends while it is an open drawer', async () => {
    wrapper = await mountSidebar({ open: true, docked: false })
    const root = document.getElementById('app-sidebar')!
    const stops = Array.from(root.querySelectorAll<HTMLElement>('a[href], button, [tabindex]')).filter(
      (element) => !element.hasAttribute('disabled') && element.tabIndex >= 0,
    )
    expect(stops.length).toBeGreaterThan(2)
    const first = stops[0]!
    const last = stops[stops.length - 1]!

    last.focus()
    pressKey('Tab')
    expect(document.activeElement).toBe(first)

    pressKey('Tab', true)
    expect(document.activeElement).toBe(last)
  })

  /** The page behind can still hold focus as the drawer opens; the first Tab either way must land inside it. */
  it('brings focus into an open drawer on Tab or Shift+Tab from outside it', async () => {
    const outside = document.createElement('button')
    document.body.appendChild(outside)
    wrapper = await mountSidebar({ open: true, docked: false })
    const root = document.getElementById('app-sidebar')!
    const stops = Array.from(root.querySelectorAll<HTMLElement>('a[href], button, [tabindex]')).filter(
      (element) => !element.hasAttribute('disabled') && element.tabIndex >= 0,
    )

    outside.focus()
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    document.dispatchEvent(tab)
    expect(tab.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(stops[0])

    outside.focus()
    pressKey('Tab', true)
    expect(document.activeElement).toBe(stops[stops.length - 1])
  })

  it('leaves Tab alone from outside while it is docked', async () => {
    const outside = document.createElement('button')
    document.body.appendChild(outside)
    wrapper = await mountSidebar({ open: true, docked: true })

    outside.focus()
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    document.dispatchEvent(tab)

    expect(tab.defaultPrevented).toBe(false)
    expect(document.activeElement).toBe(outside)
  })

  it('has no automatically-detectable accessibility violations signed in', async () => {
    wrapper = await mountWithTemplates([CLUB, GRANT])

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })

  it('has no automatically-detectable accessibility violations with a template menu open', async () => {
    wrapper = await mountWithTemplates([CLUB, GRANT])
    moreButton(2).click()
    await flushPromises()
    expect(openMenuElement()).not.toBeNull()

    // The whole page: without the Popover API the menu sits at the end of it, outside the sidebar.
    expect(await axe(document.body)).toHaveNoViolations()
  })

  it('has no automatically-detectable accessibility violations with a notice showing', async () => {
    vi.mocked(trashTemplate).mockResolvedValue({ ...CLUB, trashedAt: '2026-09-29T12:00:00Z' })
    wrapper = await mountWithTemplates([CLUB, GRANT])
    moreButton(1).click()
    await flushPromises()
    trashItem().click()
    await flushPromises()

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
