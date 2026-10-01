import { test, expect, type Page } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { crc32 } from 'node:zlib'

/**
 * Uploading the form to fill, from Home, against the real API: Brownie
 * learns the Word file on the spot, adds it to My Templates, and opens a
 * new document made from it -- and a file it cannot fill is refused in
 * plain words, with nothing left behind. No model call is on this path;
 * learning a form reads its content controls, which is deterministic.
 */

const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
// The built-in table-led minutes: every field a tagged content control, its action items the last row of a table.
const TAGGED_FORM = fileURLToPath(new URL('../../../fixtures/public/templates/table-led-meeting-minutes.docx', import.meta.url))

async function chooseForm(page: Page, file: { name: string; mimeType: string; buffer: Buffer }): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload your documents', exact: true }).click()
  await (await chooser).setFiles(file)
}

test('a Word form uploaded from Home is learned, added to My Templates, and opened as a new document', async ({ page, context }) => {
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

  // Leaves the shared test workspace's My Templates as it found it: the learned template goes to the Trash Bin.
  const workspaceId = (await (await context.request.get('/api/v1/me')).json()).memberships[0].workspaceId
  const templates = (await (await context.request.get(`/api/v1/workspaces/${workspaceId}/templates`)).json()) as { id: number; displayName: string }[]
  const xsrf = (await context.cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
  for (const template of templates.filter((candidate) => candidate.displayName === name)) {
    await context.request.post(`/api/v1/workspaces/${workspaceId}/templates/${template.id}/trash`, { headers: { 'X-XSRF-TOKEN': xsrf } })
  }
})

test('a PDF, and a Word file with no content controls, are refused on Home in plain words, and nothing is added', async ({ page }) => {
  const allocations: string[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/uploads')) allocations.push(request.url())
  })
  await page.goto('/')
  const upload = page.getByRole('button', { name: 'Upload your documents', exact: true })
  await expect(upload).toBeVisible({ timeout: 15_000 })

  // A PDF is offered by the file chooser, so the person is told why it cannot be filled; nothing is sent.
  await chooseForm(page, { name: 'Membership form.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n%%EOF\n') })
  await expect(page.getByRole('alert')).toHaveText('Brownie can fill Word (.docx) forms. It cannot fill a PDF.')
  expect(allocations).toHaveLength(0)
  await expect(upload).toBeFocused()
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  // A real Word file goes all the way to the server, which finds nowhere in it to write a value.
  const name = `E2E plain letter ${Date.now()}`
  await chooseForm(page, { name: `${name}.docx`, mimeType: DOCX_TYPE, buffer: wordFileWithoutContentControls() })
  await expect(page.getByRole('alert')).toHaveText(
    "Brownie found no content control with a tag in this Word file, and the tag is how Brownie knows which value goes where, so Brownie cannot fill it. In Word's Developer tab, add a content control where each value goes and give each one a tag under Properties, then upload the file again.",
    { timeout: 30_000 },
  )
  expect(allocations).toHaveLength(1)
  await expect(page).toHaveURL(/\/$/)
  await expect(upload).not.toHaveAttribute('aria-disabled', 'true')

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
 * The smallest Word file there is: one paragraph of text and no content
 * controls. Written as an uncompressed zip here, so the suite needs no zip
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
