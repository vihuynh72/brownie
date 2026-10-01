import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import DocumentPage from '@/components/workspace/DocumentPage.vue'
import { axe } from '@/test/axe'
import type { TemplateLayoutBlockResponse, TemplateLayoutInlineResponse, TemplateLayoutResponse } from '@/api/client'
import type { EditableField } from '@/workspace/layout'

// ---- A small Word form whose page carries where places can be chosen --------------------------------

function text(value: string, anchorStart: number | null, controlNodeId: string | null = null): TemplateLayoutInlineResponse {
  return { kind: 'TEXT', text: value, anchorStart, controlNodeId }
}

function paragraph(nodeId: string, inlines: TemplateLayoutInlineResponse[], extra: Partial<TemplateLayoutBlockResponse> = {}): TemplateLayoutBlockResponse {
  return { kind: 'PARAGRAPH', repeating: false, inlines, nodeId, anchorable: true, anchorTextHash: `hash-${nodeId}`, ...extra }
}

const LAYOUT: TemplateLayoutResponse = {
  templateId: 1,
  versionId: 3,
  parserVersion: 'brownie-docx-graph-v3+poi-5.5.1',
  parts: [
    {
      kind: 'MAIN_DOCUMENT',
      blocks: [
        paragraph('p0', [text('Company: ', 0), text('________', 9)]),
        paragraph('p1', [text('Title: ', 0), { kind: 'FILL_SPOT', fieldId: 'meeting.title', placeholder: '[title]' }]),
        paragraph('p2', [text('Ref ', 0), text('[ref]', null, 'p2/sdt0')]),
        paragraph('p3', [text('Task: ', null), { kind: 'FILL_SPOT', fieldId: 'task' }], { repeating: true, anchorable: false, anchorTextHash: null }),
      ],
    },
    { kind: 'HEADER', blocks: [paragraph('p0', [text('Letterhead', null)], { anchorable: false, anchorTextHash: null })] },
  ],
  unplacedFieldIds: [],
}

const FIELDS: EditableField[] = [
  { fieldId: 'meeting.title', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
  { fieldId: 'task', type: 'TEXT', cardinality: 'REPEATED', requiredness: 'OPTIONAL' },
]

let wrapper: VueWrapper | null = null

function mountPage(canAddSpots = true, extra: Record<string, unknown> = {}): VueWrapper {
  wrapper = mount(DocumentPage, {
    props: {
      ...extra,
      layout: LAYOUT,
      layoutState: 'ready',
      fields: FIELDS,
      drafts: { 'meeting.title': '', task: ['Book the room'] },
      revisionFields: null,
      requiredFieldIds: new Set<string>(),
      lockedFieldIds: new Set<string>(),
      rowsLocked: false,
      selected: null,
      canAddSpots,
    },
    attachTo: document.body,
  })
  return wrapper
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  window.getSelection()?.removeAllRanges()
  document.body.innerHTML = ''
  Reflect.deleteProperty(document, 'caretPositionFromPoint')
  vi.restoreAllMocks()
})

async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

const pieceOf = (page: VueWrapper, words: string) => page.findAll('.document-page__text').find((span) => span.element.textContent === words)!.element
const textNode = (element: Element) => element.firstChild as Text

/** Selects part of the page the way a pointer does, and lets the page hear of it. */
async function select(startNode: Node, startOffset: number, endNode: Node = startNode, endOffset: number = startOffset): Promise<void> {
  const range = document.createRange()
  range.setStart(startNode, startOffset)
  range.setEnd(endNode, endOffset)
  const selection = window.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  document.dispatchEvent(new Event('selectionchange'))
  await flush()
}

function pageMenu(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.spot-menu')
}

describe('DocumentPage: where a fill spot can be added', () => {
  it('marks each line and each piece of text with what a place chosen in it needs', () => {
    const page = mountPage()

    const line = page.get('p[data-node-id="p0"][data-part="MAIN_DOCUMENT"]')
    expect(line.attributes()).toMatchObject({ 'data-anchorable': 'true', 'data-anchor-hash': 'hash-p0' })
    expect(line.findAll('[data-anchor-start]').map((piece) => [piece.element.textContent, piece.attributes('data-anchor-start')])).toEqual([
      ['Company: ', '0'],
      ['________', '9'],
    ])
    expect(page.get('[data-control-node-id="p2/sdt0"]').text()).toBe('[ref]')
    expect(page.get('[data-control-node-id="p2/sdt0"]').attributes('data-anchor-start')).toBeUndefined()
    expect(page.get('.document-page__header-part p').attributes()).toMatchObject({ 'data-part': 'HEADER', 'data-anchorable': 'false' })
    expect(page.get('p[data-repeats="true"]').attributes('data-anchorable')).toBe('false')
  })

  it('offers "Add a fill spot" beside the other page actions only where spots can be added', async () => {
    const page = mountPage()
    const add = page.findAll('button').find((button) => button.text() === 'Add a fill spot')!
    await add.trigger('click')
    expect(page.emitted('add-spot')).toHaveLength(1)
    wrapper!.unmount()

    const plain = mountPage(false)
    expect(plain.findAll('button').some((button) => button.text() === 'Add a fill spot')).toBe(false)
    expect(plain.find('.document-page__tool-message').exists()).toBe(false)
  })
})

describe('DocumentPage: "Fill in here" beside a selection', () => {
  it('appears for words selected in a line, and sends that stretch of the line', async () => {
    const page = mountPage()
    const blank = pieceOf(page, '________')
    await select(textNode(blank), 0, textNode(blank), 8)

    const bar = page.get('.fill-here-bar button')
    expect(bar.text()).toBe('Fill in here')
    expect(page.emitted('place')!.at(-1)).toEqual([{ nodeId: 'p0', anchorTextHash: 'hash-p0', kind: 'selection', start: 9, end: 17, controlNodeId: null }])
    expect(await axe(page.element)).toHaveNoViolations()
    await bar.trigger('click')
    expect(page.emitted('fill-here')![0]).toEqual([
      { nodeId: 'p0', anchorTextHash: 'hash-p0', kind: 'selection', start: 9, end: 17, controlNodeId: null },
      bar.element,
    ])
  })

  it('goes away when the selection is only a caret, but still says where "here" is', async () => {
    const page = mountPage()
    const label = pieceOf(page, 'Company: ')
    await select(textNode(label), 0, textNode(label), 3)
    expect(page.find('.fill-here-bar').exists()).toBe(true)
    await select(textNode(label), 8)
    expect(page.find('.fill-here-bar').exists()).toBe(false)
    expect(page.emitted('place')!.at(-1)).toEqual([{ nodeId: 'p0', anchorTextHash: 'hash-p0', kind: 'caret', start: 8, end: 8, controlNodeId: null }])
  })

  it('says why a selection over two lines cannot take a spot, in a status line', async () => {
    const page = mountPage()
    await select(textNode(pieceOf(page, 'Company: ')), 2, textNode(pieceOf(page, 'Title: ')), 3)
    await page.get('.fill-here-bar button').trigger('click')

    expect(page.emitted('fill-here')).toBeUndefined()
    const status = page.get('.document-page__tool-message')
    expect(status.attributes('role')).toBe('status')
    expect(status.text()).toBe('Select within one paragraph to add a fill spot there.')
    expect(status.classes()).not.toContain('visually-hidden')
  })

  describe('where it sits', () => {
    const rect = (top: number, left: number, width: number, height: number) =>
      ({ top, left, width, height, right: left + width, bottom: top + height, x: left, y: top, toJSON: () => ({}) }) as DOMRect

    /** The page's body and sheet at (100, 100), 600 wide; the words selected at `box`, in the window's pixels. */
    function laidOut(box: { top: number; left: number; right: number; height?: number }): void {
      vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(rect(100, 100, 600, 800))
      const height = box.height ?? 20
      Range.prototype.getBoundingClientRect = vi.fn(() => rect(box.top, box.left, box.right - box.left, height))
    }

    function barAt(page: VueWrapper): { top: number; left: number } {
      const style = (page.get('.fill-here-bar').element as HTMLElement).style
      return { top: parseFloat(style.top), left: parseFloat(style.left) }
    }

    afterEach(() => {
      Reflect.deleteProperty(Range.prototype, 'getBoundingClientRect')
      Reflect.deleteProperty(Range.prototype, 'getClientRects')
    })

    it('sits beside the end of the words selected, on their line, where the line has room', async () => {
      laidOut({ top: 300, left: 250, right: 330 })
      const page = mountPage()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)

      // Level with the words (centred on their 20 pixels, 200 down the body), just after their end.
      expect(barAt(page)).toEqual({ top: 200 + (20 - 32) / 2, left: 230 + 6 })
    })

    it('goes above the words, clear of every other word, when their line and the one below are full', async () => {
      laidOut({ top: 300, left: 250, right: 330 })
      // The words of each line, as the page lays them out: the selected line goes on after the blank, and so does the next.
      const wordsOf: Record<string, DOMRect[]> = { p0: [rect(300, 120, 360, 20)], p1: [rect(324, 120, 480, 20)] }
      Range.prototype.getClientRects = function (this: Range) {
        const container = this.startContainer
        if (container instanceof HTMLElement && container.dataset.nodeId) return wordsOf[container.dataset.nodeId] ?? []
        return container instanceof HTMLElement ? [] : [rect(300, 250, 80, 20)]
      } as unknown as Range['getClientRects']
      const page = mountPage()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)

      // 32 high and 6 clear of the words' top (200 down the body), ending where they end.
      expect(barAt(page)).toEqual({ top: 200 - 32 - 6, left: 230 - 104 })
    })

    it('stands down while a dialog about a new spot is over the page, and comes back where it was', async () => {
      const page = mountPage()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)
      await page.setProps({ fillHereHidden: true })

      const bar = page.get('.fill-here-bar').element as HTMLElement
      expect(bar.style.display).toBe('none')
      await page.setProps({ fillHereHidden: false })
      await flush()
      // The same button, still in the page, so focus can go back to it when the dialog closes.
      expect(page.get('.fill-here-bar').element).toBe(bar)
      expect(bar.style.display).toBe('')
    })
  })

  describe('goes whenever no words on the page are selected', () => {
    /** The page inside a scrolling area that takes focus, as the workspace holds it. */
    function mountInPane(extra: Record<string, unknown> = {}): VueWrapper {
      const pane = document.createElement('div')
      pane.tabIndex = 0
      pane.id = 'pane'
      document.body.append(pane)
      wrapper = mount(DocumentPage, {
        props: {
          layout: LAYOUT,
          layoutState: 'ready',
          fields: FIELDS,
          drafts: { 'meeting.title': '', task: ['Book the room'] },
          revisionFields: null,
          requiredFieldIds: new Set<string>(),
          lockedFieldIds: new Set<string>(),
          rowsLocked: false,
          selected: null,
          canAddSpots: true,
          ...extra,
        },
        attachTo: pane,
      })
      return wrapper
    }

    it('says the bar came up, so the bar about a spot can give way to it', async () => {
      const page = mountPage()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)
      await select(textNode(blank), 0, textNode(blank), 4)
      // Once as it appears, not again as the selection changes.
      expect(page.emitted('words-selected')).toHaveLength(1)
    })

    it('after a dialog closed without a change: the words are selected again, with focus back on the bar', async () => {
      const page = mountInPane()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)
      await page.setProps({ fillHereHidden: true })
      // The dialog takes the selection away while it is open.
      window.getSelection()!.removeAllRanges()
      document.dispatchEvent(new Event('selectionchange'))
      await flush()
      expect(page.find('.fill-here-bar').exists()).toBe(true)

      ;(page.get('.fill-here-bar button').element as HTMLElement).focus()
      await page.setProps({ fillHereHidden: false })
      await flush()
      expect(window.getSelection()!.toString()).toBe('________')
      expect(page.get('.fill-here-bar').isVisible()).toBe(true)
      expect(document.activeElement).toBe(page.get('.fill-here-bar button').element)
    })

    it('after a dialog closed with focus elsewhere and nothing selected: gone', async () => {
      const page = mountInPane()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)
      await page.setProps({ fillHereHidden: true })
      window.getSelection()!.removeAllRanges()
      document.dispatchEvent(new Event('selectionchange'))
      await flush()

      await page.setProps({ fillHereHidden: false })
      await flush()
      expect(page.find('.fill-here-bar').exists()).toBe(false)
    })

    it('when the selection goes while the bar has focus, and focus moves to the page', async () => {
      const page = mountInPane()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)
      ;(page.get('.fill-here-bar button').element as HTMLElement).focus()

      window.getSelection()!.removeAllRanges()
      document.dispatchEvent(new Event('selectionchange'))
      await flush()
      expect(page.find('.fill-here-bar').exists()).toBe(false)
      expect(document.activeElement?.id).toBe('pane')
    })

    it('when focus moves into a fill spot, even with the words still selected', async () => {
      const page = mountPage()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)
      expect(page.find('.fill-here-bar').exists()).toBe(true)

      ;(page.get('[id="edit-meeting.title"]').element as HTMLElement).focus()
      await flush()
      expect(page.find('.fill-here-bar').exists()).toBe(false)
    })

    it('on Escape: the words are let go and focus goes back to the page', async () => {
      const page = mountInPane()
      const blank = pieceOf(page, '________')
      await select(textNode(blank), 0, textNode(blank), 8)
      const button = page.get('.fill-here-bar button')
      ;(button.element as HTMLElement).focus()

      await button.trigger('keydown', { key: 'Escape' })
      await flush()
      expect(window.getSelection()!.toString()).toBe('')
      expect(page.find('.fill-here-bar').exists()).toBe(false)
      expect(document.activeElement?.id).toBe('pane')
    })
  })

  it('never takes typing in a fill spot for a place', async () => {
    const page = mountPage()
    const spot = page.get('[id="edit-meeting.title"]').element as HTMLTextAreaElement
    spot.focus()
    await select(textNode(pieceOf(page, 'Company: ')), 0, textNode(pieceOf(page, 'Company: ')), 3)
    expect(page.emitted('place')).toBeUndefined()
    expect(page.find('.fill-here-bar').exists()).toBe(false)
  })
})

describe('DocumentPage: a spot and the label before it', () => {
  const SIGNED: TemplateLayoutResponse = {
    ...LAYOUT,
    parts: [
      {
        kind: 'MAIN_DOCUMENT',
        blocks: [
          paragraph('p5', [text('Signature: ________ Date: ', 0), { kind: 'FILL_SPOT', fieldId: 'signed.on' }]),
          paragraph('p6', [text('Your details. Date of birth: ', 0), { kind: 'FILL_SPOT', fieldId: 'born.on' }]),
          paragraph('p7', [text('How did you hear about the garden? ', 0), { kind: 'FILL_SPOT', fieldId: 'heard.about' }]),
        ],
      },
    ],
  }

  function mountSigned(): VueWrapper {
    wrapper = mount(DocumentPage, {
      props: {
        layout: SIGNED,
        layoutState: 'ready',
        fields: [
          { fieldId: 'signed.on', type: 'DATE', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
          { fieldId: 'born.on', type: 'DATE', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
          { fieldId: 'heard.about', type: 'TEXT', cardinality: 'SCALAR', requiredness: 'OPTIONAL' },
        ],
        drafts: { 'signed.on': '', 'born.on': '', 'heard.about': '' },
        revisionFields: null,
        requiredFieldIds: new Set<string>(),
        lockedFieldIds: new Set<string>(),
        rowsLocked: false,
        selected: null,
        canAddSpots: true,
      },
      attachTo: document.body,
    })
    return wrapper
  }

  it('draws the label just before a spot with it, as one piece, so a full line sends them to the next together', () => {
    const page = mountSigned()

    const kept = page.get('p[data-node-id="p5"] .document-page__kept')
    expect(kept.get('.document-page__text').element.textContent).toBe('Date: ')
    expect(kept.find('[id="edit-signed.on"]').exists()).toBe(true)
    // The rest of the line is drawn as before, and every piece keeps where it starts in the paragraph.
    expect(page.findAll('p[data-node-id="p5"] [data-anchor-start]').map((piece) => [piece.element.textContent, piece.attributes('data-anchor-start')])).toEqual([
      ['Signature: ________ ', '0'],
      ['Date: ', '20'],
    ])
  })

  it('keeps a label of several words whole, and lets other words before a spot, such as a question, flow as they are', () => {
    const page = mountSigned()

    expect(page.get('p[data-node-id="p6"] .document-page__kept .document-page__text').element.textContent).toBe('Date of birth: ')
    expect(page.find('p[data-node-id="p7"] .document-page__kept').exists()).toBe(false)
    expect(page.get('p[data-node-id="p7"] .document-page__text').element.textContent).toBe('How did you hear about the garden? ')
  })

  it('reads a place chosen in the label that moved where it is in the paragraph', async () => {
    const page = mountSigned()
    const label = page.get('p[data-node-id="p5"] .document-page__kept .document-page__text').element
    await select(textNode(label), 0, textNode(label), 4)

    expect(page.emitted('place')!.at(-1)).toEqual([{ nodeId: 'p5', anchorTextHash: 'hash-p5', kind: 'selection', start: 20, end: 24, controlNodeId: null }])
  })
})

describe('DocumentPage: the menu on the page text', () => {
  function rightClick(target: Element, init: MouseEventInit = {}): MouseEvent {
    const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 40, clientY: 50, button: 2, ...init })
    target.dispatchEvent(event)
    return event
  }

  it('replaces the browser menu over the page text, keyboard and all, and gives focus back on Escape', async () => {
    const page = mountPage()
    const pane = document.createElement('button')
    pane.textContent = 'Document area'
    document.body.prepend(pane)
    pane.focus()
    const label = pieceOf(page, 'Company: ')
    Object.defineProperty(document, 'caretPositionFromPoint', { value: () => ({ offsetNode: textNode(label), offset: 4 }), configurable: true })

    const event = rightClick(label)
    await flush()
    expect(event.defaultPrevented).toBe(true)
    const menu = pageMenu()!
    expect(menu.querySelector('[role="menu"]')!.getAttribute('aria-describedby')).toBe(menu.querySelector('.spot-menu__hint')!.id)
    expect(menu.querySelector('.spot-menu__hint')!.textContent).toBe("Shift+right-click shows your browser's menu")
    const items = [...menu.querySelectorAll('[role="menuitem"]')]
    // Nothing is selected, so there is nothing to copy.
    expect(items.map((item) => item.textContent)).toEqual(['Fill in here'])
    expect(document.activeElement).toBe(items[0])
    expect(await axe(document.body)).toHaveNoViolations()

    items[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush()
    expect(pageMenu()).toBeNull()
    expect(document.activeElement).toBe(pane)
  })

  it('without the Popover API, moves to the end of the page so nothing around it can clip it', async () => {
    const page = mountPage()
    const label = pieceOf(page, 'Company: ')
    Object.defineProperty(document, 'caretPositionFromPoint', { value: () => ({ offsetNode: textNode(label), offset: 4 }), configurable: true })
    rightClick(label)
    await flush()

    expect(pageMenu()!.parentElement).toBe(document.body)
    expect(pageMenu()!.hasAttribute('popover')).toBe(false)
  })

  describe('with the Popover API', () => {
    const showPopover = vi.fn()

    afterEach(() => {
      showPopover.mockReset()
      Reflect.deleteProperty(HTMLElement.prototype, 'popover')
      Reflect.deleteProperty(HTMLElement.prototype, 'showPopover')
    })

    it('stays in the page, inside its landmarks with its hint, and rises into the top layer', async () => {
      Object.defineProperty(HTMLElement.prototype, 'popover', { value: null, configurable: true, writable: true })
      Object.defineProperty(HTMLElement.prototype, 'showPopover', { value: showPopover, configurable: true, writable: true })
      const page = mountPage()
      const label = pieceOf(page, 'Company: ')
      Object.defineProperty(document, 'caretPositionFromPoint', { value: () => ({ offsetNode: textNode(label), offset: 4 }), configurable: true })
      rightClick(label)
      await flush()

      const menu = pageMenu()!
      expect(page.element.contains(menu)).toBe(true)
      // Set as the property, which a browser reflects to the attribute.
      expect((menu as HTMLElement & { popover: unknown }).popover).toBe('manual')
      expect(showPopover).toHaveBeenCalledTimes(1)
      expect(document.activeElement).toBe(menu.querySelector('[role="menuitem"]'))
    })
  })

  it('sends the point clicked, moved to the end of the word it is in', async () => {
    const page = mountPage()
    const label = pieceOf(page, 'Company: ')
    Object.defineProperty(document, 'caretPositionFromPoint', { value: () => ({ offsetNode: textNode(label), offset: 4 }), configurable: true })
    rightClick(label)
    await flush()
    ;(pageMenu()!.querySelector('[role="menuitem"]') as HTMLElement).click()
    await flush()

    expect(pageMenu()).toBeNull()
    expect(page.emitted('fill-here')![0]![0]).toEqual({ nodeId: 'p0', anchorTextHash: 'hash-p0', kind: 'caret', start: 4, end: 4, controlNodeId: null })
  })

  it('offers Copy for words selected, and copies them', async () => {
    const writeText = vi.fn(async () => undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const page = mountPage()
    const label = pieceOf(page, 'Company: ')
    await select(textNode(label), 0, textNode(label), 7)
    label.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 2 }))
    rightClick(label)
    await flush()

    const items = [...pageMenu()!.querySelectorAll<HTMLElement>('[role="menuitem"]')]
    expect(items.map((item) => item.textContent)).toEqual(['Fill in here', 'Copy'])
    items[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    expect(document.activeElement).toBe(items[1])
    items[1]!.click()
    await flush()
    expect(writeText).toHaveBeenCalledWith('Company')
    expect(page.get('.document-page__tool-message').text()).toBe('Copied.')
    Reflect.deleteProperty(navigator, 'clipboard')
  })

  it('leaves the browser menu alone with Shift, over a fill spot, and outside the text', async () => {
    const page = mountPage()
    expect(rightClick(pieceOf(page, 'Company: '), { shiftKey: true }).defaultPrevented).toBe(false)
    expect(rightClick(page.get('[id="edit-meeting.title"]').element).defaultPrevented).toBe(false)
    expect(rightClick(page.get('.document-page__sheet').element).defaultPrevented).toBe(false)
    await flush()
    expect(pageMenu()).toBeNull()
  })

  it('says why a header cannot take a spot', async () => {
    const page = mountPage()
    const header = page.get('.document-page__header-part .document-page__text').element
    Object.defineProperty(document, 'caretPositionFromPoint', { value: () => ({ offsetNode: textNode(header), offset: 2 }), configurable: true })
    rightClick(header)
    await flush()
    ;(pageMenu()!.querySelector('[role="menuitem"]') as HTMLElement).click()
    await flush()

    expect(page.emitted('fill-here')).toBeUndefined()
    expect(page.get('.document-page__tool-message').text()).toBe('Brownie fills only the body of the form, not its header or footer.')
  })

  it('opens at the selection with the ContextMenu key or Shift+F10, and makes a control no field names the spot', async () => {
    const page = mountPage()
    const control = page.get('[data-control-node-id="p2/sdt0"]').element
    await select(textNode(control), 1, textNode(control), 3)

    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', shiftKey: true, bubbles: true, cancelable: true }))
    await flush()
    expect(pageMenu()).not.toBeNull()
    // The contextmenu event the key sends after it does not open a second menu, or the browser's.
    expect(rightClick(control).defaultPrevented).toBe(true)
    ;(pageMenu()!.querySelector('[role="menuitem"]') as HTMLElement).click()
    await flush()
    expect(page.emitted('fill-here')![0]![0]).toEqual({ nodeId: 'p2', anchorTextHash: 'hash-p2', kind: 'control', start: 0, end: 0, controlNodeId: 'p2/sdt0' })

    await select(textNode(control), 0, textNode(control), 2)
    document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'ContextMenu', bubbles: true, cancelable: true }))
    await flush()
    expect(pageMenu()).not.toBeNull()
  })
})
