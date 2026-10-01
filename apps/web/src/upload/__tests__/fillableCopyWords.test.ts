import { describe, expect, it } from 'vitest'
import {
  PAGES_PACKAGE_SENTENCE,
  PDF_REFUSAL_SENTENCES,
  REFUSAL_SENTENCES,
  cannotOpenSentence,
  convertedFormatName,
  fillableFormRefusal,
  formNoteSentences,
  isPagesPackage,
  pdfRefusalSentence,
  refusalSentence,
  type FormNoteSource,
} from '@/upload/fillableCopyWords'

describe('why a file was refused', () => {
  it.each([
    ['PASSWORD_PROTECTED', "This file is locked with a password, so Brownie can't open it. Open it, remove the password, save it, and upload it again."],
    ['RIGHTS_PROTECTED', "This file is protected by your organization's rights management, so Brownie can't open it."],
    ['SPREADSHEET', 'This is a spreadsheet, not a document. Brownie can fill Word, RTF, OpenDocument and Pages documents, and PDF forms.'],
    ['PRESENTATION', 'This is a presentation, not a document. Brownie can fill Word, RTF, OpenDocument and Pages documents, and PDF forms.'],
    [
      'NOT_A_DOCUMENT',
      'Brownie can fill Word (.docx, .doc), RTF, OpenDocument (.odt) and Pages documents, and PDF forms. It could not recognize this file as any of them.',
    ],
    [
      'REMOTE_CONTENT',
      'This file asks the computer that opens it to fetch something from the internet (a linked template, picture or object), ' +
        'and Brownie does not accept files that do that. Open it in Word, save a copy without the links, and upload that.',
    ],
    ['DAMAGED', 'Brownie could not read this file. It may be damaged: open it, save it again, and upload it again.'],
  ])('is said in plain words for %s', (reason, sentence) => {
    expect(refusalSentence(reason)).toBe(sentence)
  })

  it('has a sentence for every reason the server gives', () => {
    expect(Object.keys(REFUSAL_SENTENCES).sort()).toEqual(
      ['DAMAGED', 'NOT_A_DOCUMENT', 'PASSWORD_PROTECTED', 'PRESENTATION', 'REMOTE_CONTENT', 'RIGHTS_PROTECTED', 'SPREADSHEET'],
    )
  })

  it('is left to the caller when the reason is missing or unknown', () => {
    expect(refusalSentence(undefined)).toBeNull()
    expect(refusalSentence('SOMETHING_NEW')).toBeNull()
    expect(refusalSentence('toString')).toBeNull()
    expect(refusalSentence(7)).toBeNull()
  })

  it('never uses the words people do not know', () => {
    for (const sentence of Object.values(REFUSAL_SENTENCES)) {
      expect(sentence).not.toMatch(/\b(OOXML|content control|AcroForm|DRM|OLE|MIME)\b/i)
    }
  })
})

describe('a file Brownie could not make a Word copy of', () => {
  it.each([
    ['DOC', 'Brownie could not open this Word 97-2003 file. Open it in Word and save it as a Word document (.docx), then upload that.'],
    ['RTF', 'Brownie could not open this RTF file. Open it in a word processor and save it as a Word document (.docx), then upload that.'],
    ['ODT', 'Brownie could not open this OpenDocument file. Open it in LibreOffice and save it as a Word document (.docx), then upload that.'],
    [
      'PAGES',
      'Brownie could not open this Pages file. Open it in Pages and save it as a Word document (.docx) (File > Export To > Word), then upload that.',
    ],
  ])('names the format and where to save it as Word (%s)', (mediaType, sentence) => {
    expect(cannotOpenSentence(mediaType)).toBe(sentence)
  })

  it('still says how to go on when the format is not known', () => {
    expect(cannotOpenSentence(null)).toBe(
      'Brownie could not open this file. Open it in the program that made it and save it as a Word document (.docx), then upload that.',
    )
  })
})

describe('a Pages document saved as a package', () => {
  it('arrives as an empty file with a .pages name', () => {
    expect(isPagesPackage({ name: 'Lease.pages', size: 0 })).toBe(true)
    expect(isPagesPackage({ name: 'Lease.PAGES', size: 0 })).toBe(true)
    expect(isPagesPackage({ name: 'Lease.pages', size: 20480 })).toBe(false)
    expect(isPagesPackage({ name: 'Lease.docx', size: 0 })).toBe(false)
  })

  it('is told how to export it from Pages', () => {
    expect(PAGES_PACKAGE_SENTENCE).toBe(
      "This Pages document is saved as a package, which a browser can't upload. In Pages, choose File > Export To > Word " +
        'and upload that file, or choose File > Advanced > Change File Type > Single File.',
    )
  })
})

describe('an accepted file Brownie could not make ready to fill', () => {
  it('has a sentence for every reason a PDF cannot be filled, and none for a reason it does not know', () => {
    expect(Object.keys(PDF_REFUSAL_SENTENCES).sort()).toEqual(
      ['DAMAGED', 'DOCUMENT_JAVASCRIPT', 'EMBEDDED_FILES', 'ENCRYPTED', 'LAUNCH_ACTION', 'SIGNED', 'TOO_LARGE', 'XFA'],
    )
    expect(pdfRefusalSentence('SOMETHING_NEW')).toBeNull()
    expect(pdfRefusalSentence('constructor')).toBeNull()
  })

  it('leaves a refusal that is not about the file to the caller', () => {
    expect(fillableFormRefusal({ code: 'RENDERER_BUSY' }, 'DOC')).toBeNull()
    expect(fillableFormRefusal({ code: 'NOT_FOUND' }, 'DOC')).toBeNull()
    expect(fillableFormRefusal(undefined, 'DOC')).toBeNull()
  })

  it('names the format a server has switched off, by its own name for it or by what the file is', () => {
    expect(fillableFormRefusal({ code: 'FORMAT_DISABLED', format: 'WORD_97' }, 'DOC')).toBe(
      'This Brownie does not open Word 97-2003 files right now. Open the file, save it as a Word document (.docx) and upload that.',
    )
    expect(fillableFormRefusal({ code: 'FORMAT_DISABLED' }, 'RTF')).toBe(
      'This Brownie does not open RTF files right now. Open the file, save it as a Word document (.docx) and upload that.',
    )
    expect(fillableFormRefusal({ code: 'FORMAT_DISABLED' }, null)).toBe(
      'This Brownie does not open files of this kind right now. Open the file, save it as a Word document (.docx) and upload that.',
    )
  })

  it('still words a PDF refusal whose reason it does not know', () => {
    expect(fillableFormRefusal({ code: 'PDF_FORM_NOT_FILLABLE', reason: 'SOMETHING_NEW' }, 'PDF')).toBe(PDF_REFUSAL_SENTENCES.DAMAGED)
  })

  it('names only the formats that are converted', () => {
    expect(convertedFormatName('DOC')).toBe('Word 97-2003')
    expect(convertedFormatName('DOCX')).toBeNull()
    expect(convertedFormatName('PDF')).toBeNull()
    expect(convertedFormatName('toString')).toBeNull()
    expect(convertedFormatName(null)).toBeNull()
  })

  it('never uses the words people do not know', () => {
    const sentences = [
      ...Object.values(PDF_REFUSAL_SENTENCES),
      ...['NOT_A_WORD_PROCESSING_DOCUMENT', 'FORMAT_DISABLED', 'CONVERTER_UNAVAILABLE'].map(
        (code) => fillableFormRefusal({ code }, 'ODT')!,
      ),
    ]
    for (const sentence of sentences) {
      expect(sentence).not.toMatch(/\b(OOXML|content control|AcroForm|DRM|OLE|MIME|XFA|converter|sandbox)\b/i)
    }
  })
})

describe('the notes about a form Brownie made ready', () => {
  const word = (notices: FormNoteSource['notices'], spots: FormNoteSource['spots'] = []): FormNoteSource => ({ kind: 'DOCX', notices, spots })
  const found = { origin: 'FOUND_BY_BROWNIE', binding: { kind: 'CONTENT_CONTROL_TAG' } }
  const own = { origin: 'FORM', binding: { kind: 'CONTENT_CONTROL_TAG' } }

  it.each([
    ['TRACKED_CHANGES_AND_COMMENTS', 3, null, "Brownie's copy has the tracked changes accepted and the comments left out; your original file is unchanged."],
    ['MACROS_REMOVED', 1, null, "Brownie's copy leaves out the file's macros, so nothing in it can run; your original file is unchanged."],
    ['SIGNATURE_REMOVED', 1, null, "Brownie's copy has no digital signature, because filling it in changes the file; your original file keeps its signature."],
    ['LINKED_CONTENT_REMOVED', 2, null, "Brownie's copy leaves out links to things outside the file, such as a linked picture; your original file is unchanged."],
    ['FIELDS_FROZEN', 4, null, "Brownie's copy shows parts that fetch or work out their own text as the text they showed; they no longer update."],
    ['EMBEDDED_FILES_TO_PICTURES', 1, null, "Brownie's copy shows files embedded in the document as pictures of them; they can no longer be opened from it."],
    ['EDITING_RESTRICTION_REMOVED', 1, null, "Brownie's copy has the file's editing restrictions turned off so it can be filled in; your original file is unchanged."],
    ['CONVERTED', 1, 'WORD_97', 'Brownie turned your Word 97-2003 file into a Word copy to fill; check that it looks right.'],
    ['CONVERTED', 1, 'ODT_TEMPLATE', 'Brownie turned your OpenDocument file into a Word copy to fill; check that it looks right.'],
    ['CONVERTED', 1, 'PAGES', 'Brownie turned your Pages file into a Word copy to fill; check that it looks right.'],
    ['CONVERTED', 1, null, 'Brownie turned your file into a Word copy to fill; check that it looks right.'],
    ['NO_SPOTS_FOUND', 0, null, 'Brownie did not find any blanks. Choose Add a fill spot, or select a place on the page and choose Fill in here.'],
    ['SIGNATURE_LINES_LEFT', 1, null, 'Brownie left the signature line empty, for signing by hand.'],
    ['SIGNATURE_LINES_LEFT', 2, null, 'Brownie left 2 signature lines empty, for signing by hand.'],
    ['CHECKBOXES_LEFT', 1, null, 'Brownie does not tick check boxes, so it left the one in this form for you to tick in the exported file.'],
    ['CHECKBOXES_LEFT', 5, null, 'Brownie does not tick check boxes, so it left the 5 in this form for you to tick in the exported file.'],
    ['SOME_NAMED_BY_RULES', 1, null, 'Brownie named one place from the words next to it, so check that its name makes sense.'],
    ['SOME_NAMED_BY_RULES', 3, null, 'Brownie named 3 places from the words next to them, so check that their names make sense.'],
    ['SPOTS_SKIPPED', 1, null, 'Brownie found one more blank but could not make it a place to fill in. Add it yourself if you need it.'],
    ['SPOTS_SKIPPED', 2, null, 'Brownie found 2 more blanks but could not make them places to fill in. Add them yourself if you need them.'],
    ['PLACES_LEFT_OUT', 1, null, 'Brownie left out one blank that did not look like a place for you to fill in. Add it yourself if you need it.'],
    ['PLACES_LEFT_OUT', 3, null, 'Brownie left out 3 blanks that did not look like places for you to fill in. Add them yourself if you need them.'],
    ['BLANKS_OUTSIDE_BODY', 1, null, 'Brownie left one blank outside the main text, such as in a header or footer, as it is; it fills only the main text.'],
    ['BLANKS_OUTSIDE_BODY', 2, null, 'Brownie left 2 blanks outside the main text, such as in a header or footer, as they are; it fills only the main text.'],
    ['TABLE_ROWS_GROW', 1, null, 'A table in this form gets a new row for each item you add.'],
  ])('says %s (count %s, detail %s) in one plain sentence', (code, count, detail, sentence) => {
    expect(formNoteSentences(word([{ code, count, detail }]))).toEqual([sentence])
  })

  it('says how many places there are, and which carry the mark, whoever put them there', () => {
    expect(formNoteSentences(word([{ code: 'SPOTS_FOUND', count: 7 }], Array(7).fill(found)))).toEqual([
      'Brownie found 7 places to fill in. Each is marked "Found by Brownie" so you can check it.',
    ])
    expect(formNoteSentences(word([{ code: 'SPOTS_FOUND', count: 1 }], [found]))).toEqual([
      'Brownie found 1 place to fill in. It is marked "Found by Brownie" so you can check it.',
    ])
    expect(formNoteSentences(word([{ code: 'SPOTS_FOUND', count: 4 }], [found, found, own, own]))).toEqual([
      'Brownie found 4 places to fill in. The 2 it added itself are marked "Found by Brownie" so you can check them.',
    ])
    // A form whose every place was already marked in it has nothing to check.
    expect(formNoteSentences(word([{ code: 'SPOTS_FOUND', count: 2 }], [own, own]))).toEqual(['Brownie found 2 places to fill in.'])
  })

  it('leaves the count out where the page says it itself, and keeps every other note', () => {
    const notices = [
      { code: 'TRACKED_CHANGES_AND_COMMENTS', count: 1 },
      { code: 'SPOTS_FOUND', count: 2 },
    ]
    expect(formNoteSentences(word(notices, [found, own]), { foundShownOnPage: true })).toEqual([
      "Brownie's copy has the tracked changes accepted and the comments left out; your original file is unchanged.",
    ])
    expect(formNoteSentences(word(notices, [found, own]), { foundShownOnPage: false })).toHaveLength(2)
  })

  it("names a PDF's mark for the places found as its page shows it: a dashed outline, not a label over the form", () => {
    const pdf = (notices: FormNoteSource['notices'], spots: FormNoteSource['spots']): FormNoteSource => ({ kind: 'PDF', notices, spots })
    expect(formNoteSentences(pdf([{ code: 'SPOTS_FOUND', count: 3 }], Array(3).fill(found)))).toEqual([
      'Brownie found 3 places to fill in. Each has a dashed outline so you can check it.',
    ])
    expect(formNoteSentences(pdf([{ code: 'SPOTS_FOUND', count: 3 }], [found, found, own]))).toEqual([
      'Brownie found 3 places to fill in. The 2 it added itself have a dashed outline so you can check them.',
    ])
  })

  it('lists everything kept as it is in one sentence, where the first of them came', () => {
    const notes = formNoteSentences(
      word([
        { code: 'TRACKED_CHANGES_AND_COMMENTS', count: 1 },
        { code: 'KEPT_AS_IS', count: 2, detail: 'FLOATING_SHAPE' },
        { code: 'KEPT_AS_IS', count: 1, detail: 'NESTED_TABLE' },
        { code: 'KEPT_AS_IS', count: 1, detail: 'SOMETHING_NEW' },
        { code: 'SPOTS_FOUND', count: 1 },
      ], [found]),
    )

    expect(notes).toEqual([
      "Brownie's copy has the tracked changes accepted and the comments left out; your original file is unchanged.",
      'Brownie kept floating shapes and tables inside tables as they are in your file. Check how they look in Print preview.',
      'Brownie found 1 place to fill in. It is marked "Found by Brownie" so you can check it.',
    ])
  })

  it('says nothing for a code it does not know, or for kept things it cannot name', () => {
    expect(formNoteSentences(word([{ code: 'SOMETHING_NEW', count: 1 }, { code: 'KEPT_AS_IS', count: 1, detail: null }]))).toEqual([])
  })

  it('tells a PDF what to do with a scan, with no blanks, and with the fields it leaves', () => {
    const pdf = (notices: FormNoteSource['notices'], spots: FormNoteSource['spots'] = []): FormNoteSource => ({ kind: 'PDF', notices, spots })
    const field = { origin: 'FORM', binding: { kind: 'ACROFORM_FIELD' } }

    // The page says a scan is a scan itself, over its pages, so the note does not say it again.
    expect(formNoteSentences(pdf([{ code: 'SCANNED_PDF', count: 0 }]))).toEqual([])
    expect(formNoteSentences(pdf([{ code: 'NO_SPOTS_FOUND', count: 0 }]))).toEqual([
      'Brownie did not find any blanks. Choose Add a fill spot, or draw a box wherever something should be filled in.',
    ])
    expect(formNoteSentences(pdf([{ code: 'PDF_FIELDS_LEFT', count: 4 }], [field, field, field]))).toEqual([
      "Brownie will fill 3 of this form's fields. It leaves the check boxes and lists for you to set in your PDF reader.",
    ])
    expect(formNoteSentences(pdf([{ code: 'PDF_FIELDS_LEFT', count: 4 }]))).toEqual([
      "Brownie leaves this form's check boxes and lists for you to set in your PDF reader.",
    ])
  })

  it('never uses the words people do not know', () => {
    const codes = ['TRACKED_CHANGES_AND_COMMENTS', 'MACROS_REMOVED', 'SIGNATURE_REMOVED', 'LINKED_CONTENT_REMOVED', 'FIELDS_FROZEN',
      'EMBEDDED_FILES_TO_PICTURES', 'EDITING_RESTRICTION_REMOVED', 'CONVERTED', 'NO_SPOTS_FOUND', 'SIGNATURE_LINES_LEFT', 'CHECKBOXES_LEFT',
      'SOME_NAMED_BY_RULES', 'SPOTS_SKIPPED', 'PLACES_LEFT_OUT', 'BLANKS_OUTSIDE_BODY', 'TABLE_ROWS_GROW', 'PDF_FIELDS_LEFT', 'SPOTS_FOUND']
    const notes = formNoteSentences(word([
      ...codes.map((code) => ({ code, count: 2 })),
      ...['FLOATING_SHAPE', 'NESTED_TABLE', 'EMBEDDED_OBJECT', 'DYNAMIC_FIELD', 'TRACKED_FORMATTING_CHANGE'].map((detail) => ({ code: 'KEPT_AS_IS', count: 1, detail })),
    ], [found]))
    expect(notes).toHaveLength(codes.length + 1)
    for (const note of notes) {
      expect(note).not.toMatch(/\b(OOXML|content control|AcroForm|OLE|field code|sdt|DOCX|PDF_|_)\b/i)
    }
  })
})
