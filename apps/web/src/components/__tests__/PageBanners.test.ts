import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import PageBanners from '@/components/workspace/PageBanners.vue'
import { axe } from '@/test/axe'

interface Props {
  stripText: string
  foundCount: number
  keepBusy: boolean
  notes: string[]
  notesOffered: boolean
  notesOpen: boolean
  canDismiss: boolean
  preview: { count: number; shown: boolean } | null
  newer: { text: string; error: string | null; moving: boolean } | null
  moved: string | null
  chip: { icon: 'sparkle' | 'help-circle'; text: string } | null
  chipOverSheet: boolean
}

const NONE: Props = {
  stripText: '',
  foundCount: 0,
  keepBusy: false,
  notes: [],
  notesOffered: false,
  notesOpen: false,
  canDismiss: false,
  preview: null,
  newer: null,
  moved: null,
  chip: null,
  chipOverSheet: true,
}

const mounted: VueWrapper[] = []

function show(props: Partial<Props> = {}) {
  const wrapper = mount(PageBanners, { props: { ...NONE, ...props }, attachTo: document.body })
  mounted.push(wrapper)
  return wrapper
}

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  document.body.innerHTML = ''
})

const norm = (value: string) => value.replace(/\s+/g, ' ').trim()
const names = (wrapper: VueWrapper) =>
  wrapper.findAll('button').map((button) => norm(button.element.textContent ?? ''))

describe('PageBanners', () => {
  it('draws nothing at all when there is nothing to say, so the page keeps its own spacing', () => {
    expect(show().html()).toBe('<!--v-if-->')
  })

  describe('the line about the form', () => {
    const FOUND = 'Brownie found 8 places to fill in. Check the ones marked Found by Brownie, then keep them.'

    it('puts what it is about first and what to do next under it, and offers Keep all while places are unchecked', async () => {
      const wrapper = show({ stripText: FOUND, foundCount: 8 })

      const line = wrapper.get('section.form-strip')
      expect(line.attributes('aria-label')).toBe('About this document')
      expect(line.classes()).toContain('form-strip--found')
      expect(line.get('.form-strip__title').text()).toBe('Brownie found 8 places to fill in.')
      expect(line.get('.form-strip__rest').text()).toBe('Check the ones marked Found by Brownie, then keep them.')
      expect(norm(line.get('.form-strip__text').text())).toBe(FOUND)
      expect(names(wrapper)).toEqual(['Keep all the places Brownie found'])

      await wrapper.get('button').trigger('click')
      expect(wrapper.emitted('keep-all')).toHaveLength(1)
    })

    it('draws a note that is one sentence as its title alone, with a plain sign instead of Brownie’s', () => {
      const wrapper = show({ stripText: 'Brownie left the signature line empty, for signing by hand.', canDismiss: true })

      expect(wrapper.find('.form-strip__rest').exists()).toBe(false)
      expect(wrapper.get('.form-strip__title').text()).toBe('Brownie left the signature line empty, for signing by hand.')
      expect(wrapper.get('section.form-strip').classes()).not.toContain('form-strip--found')
      expect(wrapper.get('.banner-tile').classes()).not.toContain('banner-tile--found')
      expect(names(wrapper)).toEqual(['Dismiss the notes about this document'])
    })

    it('says keeping is under way by the state of the button, which keeps the focus it holds', () => {
      const wrapper = show({ stripText: FOUND, foundCount: 2, keepBusy: true })

      expect(wrapper.get('button').attributes('aria-disabled')).toBe('true')
      expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
    })

    it('opens and folds the notes behind Details, and says which way it goes', async () => {
      const notes = ['The comments were left out.', 'The signature line is empty.']
      const wrapper = show({ stripText: 'About this document: 2 notes.', notes, notesOffered: true })

      const details = wrapper.get('button')
      expect(details.attributes('aria-expanded')).toBe('false')
      expect(details.attributes('aria-controls')).toBe('form-notes-list')
      expect((wrapper.get('#form-notes-list').element as HTMLElement).style.display).toBe('none')
      await details.trigger('click')
      expect(wrapper.emitted('toggle-notes')).toHaveLength(1)

      await wrapper.setProps({ notesOpen: true })
      expect(details.attributes('aria-expanded')).toBe('true')
      expect((wrapper.get('#form-notes-list').element as HTMLElement).style.display).toBe('')
      expect(wrapper.findAll('#form-notes-list li').map((item) => item.text())).toEqual(notes)
    })

    it('offers no list of notes when they are not offered behind Details', () => {
      const wrapper = show({ stripText: 'The comments were left out.', notes: ['The comments were left out.'], notesOffered: false })

      expect(wrapper.find('#form-notes-list').exists()).toBe(false)
      expect(wrapper.findAll('button')).toHaveLength(0)
    })

    it('reports a dismissal', async () => {
      const wrapper = show({ stripText: 'The comments were left out.', canDismiss: true })

      await wrapper.get('button').trigger('click')
      expect(wrapper.emitted('dismiss-notes')).toHaveLength(1)
    })

    it('moves the focus to its first button, and says so when it has none', async () => {
      const wrapper = show({ stripText: FOUND, foundCount: 1, notesOffered: true, notes: ['A note.'] })
      const exposed = wrapper.vm as unknown as { focusStripButton: () => boolean }

      expect(exposed.focusStripButton()).toBe(true)
      expect(document.activeElement?.textContent).toContain('Keep all')

      await wrapper.setProps({ foundCount: 0, notesOffered: false, stripText: 'A note.' })
      expect(exposed.focusStripButton()).toBe(false)
    })
  })

  describe('the preview of what Brownie proposes', () => {
    it('says how many fill spots it previews, that the values are not in the document yet, and offers to hide them', async () => {
      const wrapper = show({ preview: { count: 6, shown: true } })

      const band = wrapper.get('.preview-band')
      expect(band.attributes('aria-label')).toBe('Preview of Brownie’s values')
      expect(norm(band.text())).toContain('Previewing 6 fill spots from Brownie')
      expect(norm(band.text())).toContain('Dashed values are not in the document yet. Nothing is filled until you approve.')
      expect(names(wrapper)).toEqual(['Hide preview'])

      await wrapper.get('button').trigger('click')
      expect(wrapper.emitted('toggle-preview')).toHaveLength(1)
    })

    it('says one fill spot in the singular', () => {
      expect(norm(show({ preview: { count: 1, shown: true } }).get('.preview-band').text())).toContain('Previewing 1 fill spot from Brownie')
    })

    it('says what is waiting while the drawing is hidden, and offers to show it again', () => {
      const wrapper = show({ preview: { count: 2, shown: false } })

      expect(norm(wrapper.get('.preview-band').text())).toContain('Brownie has 2 fill spots for you to approve')
      expect(norm(wrapper.get('.preview-band').text())).toContain('The preview is hidden. Nothing is filled until you approve.')
      expect(names(wrapper)).toEqual(['Show preview'])
    })
  })

  describe('a newer version of the form', () => {
    const NEWER = { text: 'This form has a newer version. It adds the fill spot "Company".', error: null, moving: false }

    it('says what the newer version adds and offers the move and not now', async () => {
      const wrapper = show({ newer: NEWER })

      expect(wrapper.get('[role="status"]').text()).toBe(NEWER.text)
      expect(names(wrapper)).toEqual(['Move this document to it', 'Not now'])
      await wrapper.findAll('button')[0]!.trigger('click')
      await wrapper.findAll('button')[1]!.trigger('click')
      expect(wrapper.emitted('move')).toHaveLength(1)
      expect(wrapper.emitted('set-aside')).toHaveLength(1)
    })

    it('says it is moving, and ignores a press until it has finished', async () => {
      const wrapper = show({ newer: { ...NEWER, moving: true } })

      const [move, notNow] = wrapper.findAll('button')
      expect(move!.text()).toBe('Moving…')
      expect(move!.attributes('aria-disabled')).toBe('true')
      expect(notNow!.attributes('aria-disabled')).toBe('true')
      await move!.trigger('click')
      await notNow!.trigger('click')
      expect(wrapper.emitted('move')).toBeUndefined()
      expect(wrapper.emitted('set-aside')).toBeUndefined()
    })

    it('says why the move was refused, as an alert', () => {
      const wrapper = show({ newer: { ...NEWER, error: 'Unlock Title first; its value would be lost.' } })

      expect(wrapper.get('[role="alert"]').text()).toBe('Unlock Title first; its value would be lost.')
    })

    it('moves the focus to the move button', () => {
      const wrapper = show({ newer: NEWER })

      ;(wrapper.vm as unknown as { focusMove: () => void }).focusMove()

      expect(document.activeElement?.textContent?.trim()).toBe('Move this document to it')
    })

    it('says what a move just did', () => {
      expect(show({ moved: 'Moved this document to the newest version of its form.' }).get('.version-banner__moved').text()).toBe(
        'Moved this document to the newest version of its form.',
      )
    })
  })

  describe('what Brownie is doing now', () => {
    const READING = { icon: 'sparkle' as const, text: 'Brownie is reading. You can keep typing.' }

    it('is a status chip with a picture and its words', () => {
      const chip = show({ chip: READING }).get('.page-chip')

      expect(chip.attributes('role')).toBe('status')
      expect(chip.text()).toBe(READING.text)
      expect(chip.find('svg').exists()).toBe(true)
    })

    it('floats over the sheet when it is asked to, and takes no room of its own', () => {
      const wrapper = show({ chip: READING, chipOverSheet: true })

      expect(wrapper.get('.page-chip-anchor').classes()).toContain('page-chip-anchor--over')
      // Alone, it must not push the sheet down by even a gap.
      expect(wrapper.get('.page-banners').classes()).toContain('page-banners--chip-only')
    })

    it('stays in the flow above the page when the page is drawn another way', () => {
      expect(show({ chip: READING, chipOverSheet: false }).get('.page-chip-anchor').classes()).not.toContain('page-chip-anchor--over')
    })

    it('is not alone once another note is shown with it', () => {
      const wrapper = show({ chip: READING, stripText: 'Brownie found 2 places to fill in. Check them.', foundCount: 2 })

      expect(wrapper.get('.page-banners').classes()).not.toContain('page-banners--chip-only')
    })
  })

  it('has no accessibility violations with every note shown', async () => {
    const wrapper = show({
      stripText: 'Brownie found 8 places to fill in. Check the ones marked Found by Brownie, then keep them.',
      foundCount: 8,
      notes: ['The comments were left out.'],
      notesOffered: true,
      notesOpen: true,
      preview: { count: 6, shown: true },
      newer: { text: 'This form has a newer version.', error: 'Unlock Title first.', moving: false },
      moved: 'Moved.',
      chip: { icon: 'help-circle', text: 'Brownie has 2 questions. Answer them in the chat.' },
    })

    expect(await axe(wrapper.element)).toHaveNoViolations()
  })
})
