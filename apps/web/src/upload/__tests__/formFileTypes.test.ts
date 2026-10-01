import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetCapabilitiesCache } from '@/capabilities'
import {
  FALLBACK_FORM_FILE_TYPES,
  FORM_FILE_ACCEPT,
  fileKind,
  formFileAccept,
  formFileTypesFrom,
  loadFormFileTypes,
  type FormFileType,
} from '@/upload/formFileTypes'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, getCapabilities: vi.fn() }
})

import { getCapabilities, type CapabilitiesResponse } from '@/api/client'

const BASE: CapabilitiesResponse = {
  maxUploadBytes: 10485760,
  uploadMediaTypes: [],
  assistSourceMediaTypes: [],
  templateMediaTypes: [],
  trashRetentionDays: 30,
}

function file(name: string, type = ''): Pick<File, 'name' | 'type'> {
  return { name, type }
}

beforeEach(() => {
  resetCapabilitiesCache()
  vi.mocked(getCapabilities).mockReset()
})

describe('the chooser', () => {
  it('offers every extension, then every media type, each once and in order', () => {
    expect(FORM_FILE_ACCEPT).toBe(
      '.docx,.dotx,.docm,.dotm,.doc,.dot,.rtf,.odt,.ott,.pages,.pdf,' +
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document,' +
        'application/vnd.openxmlformats-officedocument.wordprocessingml.template,' +
        'application/vnd.ms-word.document.macroEnabled.12,application/vnd.ms-word.template.macroEnabled.12,' +
        'application/msword,application/rtf,text/rtf,application/vnd.oasis.opendocument.text,' +
        'application/vnd.oasis.opendocument.text-template,application/vnd.apple.pages,' +
        'application/x-iwork-pages-sffpages,application/pdf',
    )
  })

  it("builds its list from the server's when there is one", () => {
    const types: FormFileType[] = [
      { extension: 'docx', mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', route: 'NATIVE' },
      { extension: 'pdf', mediaType: 'application/pdf', route: 'PDF' },
    ]

    expect(formFileAccept(types)).toBe('.docx,.pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/pdf')
  })
})

describe("the server's list", () => {
  it('is used when the server sends one', () => {
    const offered: FormFileType[] = [{ extension: 'docx', mediaType: 'application/x-test', route: 'NATIVE' }]

    expect(formFileTypesFrom({ ...BASE, formFileTypes: offered })).toEqual(offered)
  })

  it('falls back when an older server sends none, or nothing usable', () => {
    expect(formFileTypesFrom(BASE)).toBe(FALLBACK_FORM_FILE_TYPES)
    expect(formFileTypesFrom(null)).toBe(FALLBACK_FORM_FILE_TYPES)
    expect(formFileTypesFrom({ ...BASE, formFileTypes: [] })).toBe(FALLBACK_FORM_FILE_TYPES)
    const odd = [
      { extension: '.docx', mediaType: 'application/x', route: 'NATIVE' },
      { extension: 'doc', mediaType: 'nothing', route: 'CONVERTED' },
      { extension: 'odt', mediaType: 'application/x', route: 'SOMEDAY' },
    ] as unknown as FormFileType[]
    expect(formFileTypesFrom({ ...BASE, formFileTypes: odd })).toBe(FALLBACK_FORM_FILE_TYPES)
  })

  it('leaves out a row it cannot use and keeps the rest', () => {
    const rows = [
      { extension: 'rtf', mediaType: 'application/rtf', route: 'CONVERTED' },
      { extension: 42, mediaType: 'application/x', route: 'NATIVE' },
    ] as unknown as FormFileType[]

    expect(formFileTypesFrom({ ...BASE, formFileTypes: rows })).toEqual([rows[0]])
  })

  it('is asked of the server once, and the fallback answers when it cannot be asked', async () => {
    const offered: FormFileType[] = [{ extension: 'pdf', mediaType: 'application/pdf', route: 'PDF' }]
    vi.mocked(getCapabilities).mockResolvedValue({ ...BASE, formFileTypes: offered })
    expect(await loadFormFileTypes()).toEqual(offered)

    resetCapabilitiesCache()
    vi.mocked(getCapabilities).mockRejectedValue(new TypeError('Failed to fetch'))
    expect(await loadFormFileTypes()).toBe(FALLBACK_FORM_FILE_TYPES)
  })
})

describe('what a chosen file looks like', () => {
  it.each(['Form.docx', 'Form.DOTX', 'Form.docm', 'Form.dotm', 'Form.doc', 'Form.dot', 'Form.rtf', 'Form.odt', 'Form.ott', 'Form.pages'])(
    'is a word-processing document by its extension (%s)',
    (name) => {
      expect(fileKind(file(name))).toBe('word-processing')
    },
  )

  it('is a PDF by its extension or, with no extension, by the type the browser reports', () => {
    expect(fileKind(file('Form.pdf'))).toBe('pdf')
    expect(fileKind(file('Form', 'application/pdf'))).toBe('pdf')
    expect(fileKind(file('Form', 'application/vnd.ms-word.document.macroenabled.12'))).toBe('word-processing')
  })

  it('goes by the extension before the reported type', () => {
    expect(fileKind(file('Form.pdf', 'application/msword'))).toBe('pdf')
  })

  it('is left for the server to judge when this page does not know it', () => {
    expect(fileKind(file('Form'))).toBe('other')
    expect(fileKind(file('notes.txt', 'text/plain'))).toBe('other')
    expect(fileKind(file('Budget.xlsx'))).toBe('other')
    expect(fileKind(file('.docx'))).toBe('other')
  })

  it("follows the server's list when given one", () => {
    const pdfOnly: FormFileType[] = [{ extension: 'pdf', mediaType: 'application/pdf', route: 'PDF' }]

    expect(fileKind(file('Form.doc'), pdfOnly)).toBe('other')
  })
})
