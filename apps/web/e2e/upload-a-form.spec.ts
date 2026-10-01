import { test, expect, type Page } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { crc32 } from 'node:zlib'

/**
 * Uploading the form to fill, from Home, against the real API: Brownie
 * makes the file ready to fill on the spot, finding its places, adds it to
 * My Templates, and opens a new document made from it -- and a file it
 * cannot fill is refused in plain words, with nothing left behind. Where
 * the deployment has the AI service name the places Brownie finds, their
 * names are its choice, so these tests find a found place by its mark and
 * name only the places a form marked itself.
 */

const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
// The built-in table-led minutes: every field a tagged content control, its action items the last row of a table.
const TAGGED_FORM = fileURLToPath(new URL('../../../fixtures/public/templates/table-led-meeting-minutes.docx', import.meta.url))
// A one-page PDF with fields of its own to type in, among them "Full name", and check boxes and a signature field.
const FILLABLE_PDF = fileURLToPath(new URL('../../../fixtures/public/pdf/fillable-application.pdf', import.meta.url))

/** Leaves the shared test workspace's My Templates as it found it: a template this test made goes to the Trash Bin. */
async function trashTemplatesNamed(page: Page, name: string): Promise<void> {
  const workspaceId = (await (await page.request.get('/api/v1/me')).json()).memberships[0].workspaceId
  const templates = (await (await page.request.get(`/api/v1/workspaces/${workspaceId}/templates`)).json()) as { id: number; displayName: string }[]
  const xsrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
  for (const template of templates.filter((candidate) => candidate.displayName === name)) {
    await page.request.post(`/api/v1/workspaces/${workspaceId}/templates/${template.id}/trash`, { headers: { 'X-XSRF-TOKEN': xsrf } })
  }
}

async function chooseForm(page: Page, file: { name: string; mimeType: string; buffer: Buffer }): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload your documents', exact: true }).click()
  await (await chooser).setFiles(file)
}

test('a Word form uploaded from Home is learned, added to My Templates, and opened as a new document', async ({ page }) => {
  // Learning a form renders it once through the isolated renderer before it is ready.
  test.setTimeout(90_000)
  const name = `E2E uploaded minutes ${Date.now()}`

  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Upload your documents', exact: true })).toBeVisible({ timeout: 15_000 })
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await chooseForm(page, { name: `${name}.docx`, mimeType: DOCX_TYPE, buffer: await readFile(TAGGED_FORM) })

  // Named after the file and the day, and laid out as the form itself, with a fill spot at each content control.
  await page.waitForURL(/\/documents\/\d+$/, { timeout: 60_000 })
  const firstId = Number(new URL(page.url()).pathname.split('/').pop())
  await expect(page.locator('.workspace-title')).toHaveText(new RegExp(`^${name}, `), { timeout: 15_000 })
  await expect(page.getByLabel(/^Meeting title/)).toBeVisible({ timeout: 15_000 })
  await expect(page.getByLabel(/^Meeting date/)).toBeVisible()

  // The last row of the form's table is learned as a repeating row, as the built-in template has it.
  await page.getByRole('button', { name: 'Add row' }).click()
  await expect(page.getByLabel('Action item task, row 1', { exact: true })).toBeFocused()

  // My Templates has it now, and it starts another document the same way any template does.
  const learned = page.locator('#app-sidebar').getByRole('button', { name, exact: true })
  await expect(learned).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
  // The row just added cannot be saved with a blank date, so leaving for another document asks first.
  let asked = ''
  page.once('dialog', async (dialog) => {
    asked = dialog.message()
    await dialog.accept()
  })
  await learned.click()
  // A new document, drawn afresh: not the first one left on screen under a new address.
  await page.waitForURL((url) => {
    const match = /\/documents\/(\d+)$/.exec(url.pathname)
    return match !== null && Number(match[1]) !== firstId
  })
  await expect(page.locator('.workspace-title')).toHaveText(new RegExp(`^${name}, `), { timeout: 15_000 })
  await expect(page.getByRole('button', { name: 'Add row' })).toBeVisible()
  await expect(page.getByLabel('Action item task, row 1', { exact: true })).toHaveCount(0)
  expect(asked).toBe('You have unsaved changes on this document. Leave anyway?')

  await trashTemplatesNamed(page, name)
})

test('a form added with the + beside My Templates is listed there, and starts a document only when asked', async ({ page }) => {
  test.setTimeout(90_000)
  const name = `E2E added minutes ${Date.now()}`
  await page.goto('/')
  const sidebar = page.locator('#app-sidebar')
  const add = sidebar.getByRole('button', { name: 'Add a template from a file', exact: true })
  await expect(add).toBeVisible({ timeout: 15_000 })

  const chooser = page.waitForEvent('filechooser')
  await add.click()
  await (await chooser).setFiles({ name: `${name}.docx`, mimeType: DOCX_TYPE, buffer: await readFile(TAGGED_FORM) })

  // The same steps as Home, said in the sidebar; it ends at the template, with no document opened.
  await expect(sidebar.getByText(`Added "${name}" to My Templates.`)).toBeVisible({ timeout: 60_000 })
  await expect(page).toHaveURL(/\/$/)
  await expect(sidebar.getByRole('button', { name, exact: true })).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await sidebar.getByRole('button', { name: `Start a document from ${name}`, exact: true }).click()
  await page.waitForURL(/\/documents\/\d+$/, { timeout: 30_000 })
  await expect(page.locator('.workspace-title')).toHaveText(new RegExp(`^${name}, `), { timeout: 15_000 })
  await expect(page.getByLabel(/^Meeting title/)).toBeVisible({ timeout: 15_000 })

  await trashTemplatesNamed(page, name)
})

test('a Word file with a blank and no content controls opens with the place Brownie found, marked until it is kept', async ({ page }) => {
  test.setTimeout(90_000)
  const name = `E2E plain letter ${Date.now()}`
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Upload your documents', exact: true })).toBeVisible({ timeout: 15_000 })

  await chooseForm(page, { name: `${name}.docx`, mimeType: DOCX_TYPE, buffer: wordFileWithoutContentControls() })

  await page.waitForURL(/\/documents\/\d+$/, { timeout: 60_000 })
  await expect(page.locator('.workspace-title')).toHaveText(new RegExp(`^${name}, `), { timeout: 15_000 })
  // What Brownie found is news in one line over the page, not an error.
  const strip = page.getByRole('region', { name: 'About this document' })
  await expect(strip.locator('.form-strip__text')).toHaveText('Brownie found 1 place to fill in. Check the one marked Found by Brownie.')
  await expect(page.getByRole('alert')).toHaveCount(0)
  // The blank on the line is the place, marked in words beside it, and in view without scrolling.
  await expect(page.locator('.fill-spot__found')).toHaveText(['Found by Brownie'])
  await expect(page.locator('.fill-spot__found')).toBeInViewport()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // Kept, the mark goes, and stays gone for the next document made from the form. The upload had nothing else
  // to say, so the line goes with its button, and focus moves on to the document.
  await strip.getByRole('button', { name: 'Keep all the places Brownie found' }).click()
  await expect(page.locator('.fill-spot__found')).toHaveCount(0)
  await expect(strip).toHaveCount(0)
  await expect(page.locator('#document-pane')).toBeFocused()

  await trashTemplatesNamed(page, name)
})

test('a PDF form uploaded from Home opens with its own fields to fill', async ({ page }) => {
  test.setTimeout(90_000)
  const name = `E2E membership application ${Date.now()}`
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Upload your documents', exact: true })).toBeVisible({ timeout: 15_000 })

  await chooseForm(page, { name: `${name}.pdf`, mimeType: 'application/pdf', buffer: await readFile(FILLABLE_PDF) })

  await page.waitForURL(/\/documents\/\d+$/, { timeout: 60_000 })
  await expect(page.locator('.workspace-title')).toHaveText(new RegExp(`^${name}, `), { timeout: 15_000 })
  // The form's own fields keep the names the form gives them, and carry no mark: nothing about them needs checking.
  await expect(page.getByLabel(/^Full name/)).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.fill-spot__found')).toHaveCount(0)
  // The notes about the upload wait behind Details, so the form's first fields stay in view.
  const strip = page.getByRole('region', { name: 'About this document' })
  await strip.getByRole('button', { name: 'Details about this document' }).click()
  await expect(strip.getByRole('listitem').filter({ hasText: 'It leaves the check boxes and lists for you to set in your PDF reader.' })).toBeVisible()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await trashTemplatesNamed(page, name)
})

test('a file that is not a document is refused on Home in plain words, and nothing is added', async ({ page }) => {
  await page.goto('/')
  const upload = page.getByRole('button', { name: 'Upload your documents', exact: true })
  await expect(upload).toBeVisible({ timeout: 15_000 })

  // The chooser does not offer plain text, but a file can still be dropped or picked by name; the server judges its bytes.
  const name = `E2E notes ${Date.now()}`
  await chooseForm(page, { name: `${name}.txt`, mimeType: 'text/plain', buffer: Buffer.from('Just some notes from the meeting.') })
  await expect(page.getByRole('alert')).toHaveText(
    'This file is not a document Brownie can fill in. Brownie can fill Word, RTF, OpenDocument and Pages documents, and PDF forms.',
    { timeout: 30_000 },
  )
  await expect(page).toHaveURL(/\/$/)
  await expect(upload).not.toHaveAttribute('aria-disabled', 'true')
  await expect(upload).toBeFocused()

  // Nothing half-made shows: not in the sidebar, and not as a usable template on the server either.
  await expect(page.locator('#app-sidebar').getByRole('button', { name, exact: true })).toHaveCount(0)
  const workspaceId = (await (await page.request.get('/api/v1/me')).json()).memberships[0].workspaceId
  const templates: { displayName: string; currentActiveVersionId: number | null }[] = await (
    await page.request.get(`/api/v1/workspaces/${workspaceId}/templates`)
  ).json()
  expect(templates.filter((template) => template.displayName === name && template.currentActiveVersionId != null)).toEqual([])
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])
})

/**
 * The smallest Word file there is: one paragraph of text with a blank to
 * fill, and no content controls. Written as an uncompressed zip here, so the suite needs no zip
 * tool and no binary fixture that says the same thing.
 */
function wordFileWithoutContentControls(): Buffer {
  return storedZip({
    '[Content_Types].xml':
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
      '<Default Extension="xml" ContentType="application/xml"/>' +
      '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
      '</Types>',
    '_rels/.rels':
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>' +
      '</Relationships>',
    'word/document.xml':
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
      '<w:p><w:r><w:t xml:space="preserve">Dear member, please write your name here: ______</w:t></w:r></w:p>' +
      '</w:body></w:document>',
  })
}

/** A zip archive whose entries are stored as they are, which every zip reader accepts. */
function storedZip(entries: Record<string, string>): Buffer {
  const parts: Buffer[] = []
  const directory: Buffer[] = []
  let offset = 0
  for (const [path, text] of Object.entries(entries)) {
    const name = Buffer.from(path, 'utf8')
    const data = Buffer.from(text, 'utf8')
    const checksum = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt32LE(checksum, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(name.length, 26)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt32LE(checksum, 16)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(name.length, 28)
    central.writeUInt32LE(offset, 42)
    parts.push(local, name, data)
    directory.push(central, name)
    offset += local.length + name.length + data.length
  }
  const directorySize = directory.reduce((total, part) => total + part.length, 0)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(Object.keys(entries).length, 8)
  end.writeUInt16LE(Object.keys(entries).length, 10)
  end.writeUInt32LE(directorySize, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...parts, ...directory, end])
}
