import { test, expect, type Page, type BrowserContext } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Filling many documents from a spreadsheet, end to end against the real
 * API: a template's menu opens the fill page, a CSV's columns are matched
 * to the certificate's places, and a batch fills, accepts and exports
 * into a ZIP that downloads from the batch page. No model is involved:
 * the suite runs with the fill-spots naming model disabled, so every row
 * is a copy of a column's value and the workspace's monthly model count
 * stays at zero.
 */

const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
const PDF_TYPE = 'application/pdf'
const CSV_TYPE = 'text/csv'
// The certificate batch fixtures: a one-page PDF form and a tagged Word document with the same five places.
const CERTIFICATE_PDF = fileURLToPath(new URL('../../../fixtures/public/pdf/certificate-of-participation.pdf', import.meta.url))
const CERTIFICATE_DOCX = fileURLToPath(
  new URL('../../../fixtures/public/templates/certificate-of-participation.docx', import.meta.url),
)
const MEMBERS_200 = fileURLToPath(new URL('../../../fixtures/public/tables/members-200.csv', import.meta.url))

/** The phone size the layouts are checked at. */
const PHONE = { width: 390, height: 844 }
const DESKTOP = { width: 1280, height: 720 }

/**
 * The page itself must never scroll sideways, whatever its content's width.
 * A resize crosses the sidebar's dock/drawer breakpoint a frame before Vue
 * repaints it, so the check polls through that brief settle.
 */
async function expectNoSidewaysScroll(page: Page): Promise<void> {
  const measure = () =>
    page.evaluate(() => {
      const vw = window.innerWidth
      const offenders: string[] = []
      for (const el of document.querySelectorAll('body *')) {
        const rect = el.getBoundingClientRect()
        const style = getComputedStyle(el)
        if (style.visibility === 'hidden' || rect.width === 0 || rect.right <= vw + 1) continue
        // A table inside its own horizontal scroll region is fine: report only
        // what overflows without an overflowing ancestor to clip it.
        let clipped = false
        for (let p = el.parentElement; p; p = p.parentElement) {
          if (getComputedStyle(p).overflowX !== 'visible') {
            clipped = true
            break
          }
        }
        if (!clipped) offenders.push(`${el.tagName}.${String(el.className)} w=${Math.round(rect.width)}`)
      }
      return { overflow: document.documentElement.scrollWidth - vw, offenders }
    })
  await expect
    .poll(async () => (await measure()).overflow, { timeout: 5_000 })
    .toBeLessThanOrEqual(0)
  const { overflow, offenders } = await measure()
  expect(
    overflow,
    `page must not be wider than the viewport; wide elements: ${offenders.join(' | ')}`,
  ).toBeLessThanOrEqual(0)
}

/** Rows 2..n+1 of members-200.csv as a file of its own, header kept. */
function memberRows(all: string, count: number): Buffer {
  const lines = all.trimEnd().split('\n')
  return Buffer.from([lines[0], ...lines.slice(1, count + 1)].join('\n') + '\n', 'utf8')
}

async function workspaceId(context: BrowserContext): Promise<number> {
  return (await (await context.request.get('/api/v1/me')).json()).memberships[0].workspaceId
}

/** Uploads the form from Home and answers the template's display name (the file name without its extension). */
async function learnForm(page: Page, name: string, file: { mimeType: string; buffer: Buffer }): Promise<void> {
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Upload your documents', exact: true })).toBeVisible({ timeout: 15_000 })
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Upload your documents', exact: true }).click()
  await (await chooser).setFiles({ name, mimeType: file.mimeType, buffer: file.buffer })
  await page.waitForURL(/\/documents\/\d+$/, { timeout: 90_000 })
}

/** Opens the fill page from the template's "..." menu under My Templates. */
async function openFillMany(page: Page, templateName: string): Promise<void> {
  const openMenu = page.getByRole('button', { name: 'Open menu', exact: true })
  if (await openMenu.isVisible()) await openMenu.click()
  const sidebar = page.locator('#app-sidebar')
  const row = sidebar.getByRole('button', { name: templateName, exact: true })
  if (!(await row.isVisible())) {
    await page.goto('/')
    if (await openMenu.isVisible()) await openMenu.click()
  }
  // The sidebar's list settles after mount; a re-render can detach the menu
  // mid-click, so the whole open is tried again once if the first goes stale.
  for (let attempt = 0; attempt < 2; attempt++) {
    await sidebar.getByRole('button', { name: `More for ${templateName}` }).click()
    try {
      await page.getByRole('menuitem', { name: 'Fill from a spreadsheet' }).click({ timeout: 10_000 })
      break
    } catch (error) {
      if (attempt === 1) throw error
      await page.keyboard.press('Escape')
    }
  }
  await page.waitForURL(/\/templates\/\d+\/fill-many$/, { timeout: 15_000 })
  await expect(page.locator('h1')).toBeFocused()
}

/** Chooses the spreadsheet and waits for the columns and mapping to appear. */
async function chooseSpreadsheet(page: Page, file: { name: string; buffer: Buffer }): Promise<void> {
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Choose a spreadsheet' }).click()
  await (await chooser).setFiles({ name: file.name, mimeType: CSV_TYPE, buffer: file.buffer })
  await expect(page.getByText('Match the columns to the places to fill')).toBeVisible({ timeout: 30_000 })
}

/**
 * Matches every certificate place to its column by the place's own field id
 * (the selects are named for them, and the PDF form's learned labels are
 * ambiguous -- two of its places are both called "Name"). The member's own
 * place auto-matches; the rest are chosen here.
 */
async function mapColumns(page: Page, fields: Record<string, string>): Promise<void> {
  for (const [fieldId, column] of Object.entries(fields)) {
    await page.locator(`select[id="map-${fieldId}"]`).selectOption({ label: column })
  }
  await page.getByLabel('Name each document after').selectOption({ label: 'A · Member name' })
}

/** The tagged Word certificate keeps its content-control tags as field ids. */
const DOCX_FIELDS = {
  'award.title': 'B · Award',
  'award.date': 'C · Date awarded',
  'club.name': 'D · Club',
  citation: 'E · Citation notes',
}
/** The PDF form's nested field names come out flat, the second "name" suffixed. */
const PDF_FIELDS = {
  name: 'A · Member name',
  title: 'B · Award',
  date: 'C · Date awarded',
  'name.2': 'D · Club',
  citation: 'E · Citation notes',
}

/** Presses "Make N documents", waits for the batch page and answers the batch's id. */
async function makeDocuments(page: Page, count: number): Promise<number> {
  await page.getByRole('button', { name: `Make ${count} documents`, exact: true }).click()
  await page.waitForURL(/\/batches\/\d+$/, { timeout: Math.max(60_000, count * 1500) })
  return Number(new URL(page.url()).pathname.split('/').pop())
}

/** Accepts every row and waits for the batch to say each accepted, which the steps only answer piecewise. */
async function acceptRows(page: Page, context: BrowserContext, workspace: number, batch: number): Promise<void> {
  await page.getByRole('button', { name: 'Accept rows that are ready', exact: true }).click()
  await expect
    .poll(
      async () => {
        const answer = await (await context.request.get(`/api/v1/workspaces/${workspace}/batches/${batch}`)).json()
        return (answer.rows as { accepted: boolean }[]).filter((row) => row.accepted).length
      },
      { timeout: 120_000 },
    )
    .toBe((await (await context.request.get(`/api/v1/workspaces/${workspace}/batches/${batch}`)).json()).rows.length)
}

/** Exports in the named format and answers the download link once the run completes. */
async function exportBatch(page: Page, format: string, documents: number): Promise<import('@playwright/test').Locator> {
  await page.getByRole('button', { name: 'Export…', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('Brownie checks each document first.')).toBeVisible()
  await dialog.getByLabel(format, { exact: true }).check()
  await dialog.getByRole('button', { name: `Approve and export ${documents} documents`, exact: true }).click()
  const download = page.getByRole('link', { name: new RegExp(`Download ZIP \\(${documents} documents\\)`) })
  await expect(download).toBeVisible({ timeout: Math.max(60_000, documents * 3000) })
  return download
}

/** Downloads the ZIP into a temp directory, lists its entries, and answers the lot. */
async function downloadAndList(page: Page, download: import('@playwright/test').Locator): Promise<{ dir: string; entries: string[] }> {
  const dir = await mkdtemp(join(tmpdir(), 'brownie-fill-many-'))
  const event = page.waitForEvent('download')
  await download.click()
  const file = join(dir, 'batch.zip')
  await (await event).saveAs(file)
  // unzip is present on the supported macOS/Linux development hosts, as in the other specs.
  const entries = execFileSync('unzip', ['-Z1', file], { encoding: 'utf8' }).trim().split('\n')
  return { dir, entries }
}

async function monthRequests(context: BrowserContext, workspace: number): Promise<number> {
  const usage = await (await context.request.get(`/api/v1/workspaces/${workspace}/usage`)).json()
  return usage.monthRequests
}

test('a Word template filled from a CSV makes one document per row and exports them together', async ({
  page,
  context,
}) => {
  test.setTimeout(300_000)
  const templateName = `E2E batch certificate ${Date.now()}`
  const csv = memberRows(await readFile(MEMBERS_200, 'utf8'), 12)

  await learnForm(page, `${templateName}.docx`, { mimeType: DOCX_TYPE, buffer: await readFile(CERTIFICATE_DOCX) })
  await page.goto('/')
  await openFillMany(page, templateName)
  await chooseSpreadsheet(page, { name: 'members-12.csv', buffer: csv })
  await mapColumns(page, DOCX_FIELDS)

  // At phone width the preview table scrolls inside its own region; the page never does.
  await page.setViewportSize(PHONE)
  await expectNoSidewaysScroll(page)
  await page.setViewportSize(DESKTOP)

  const workspace = await workspaceId(context)
  const batch = await makeDocuments(page, 12)

  // The batch page lists every row; each is a document that can be opened.
  await expect(page.locator('.batch__table tbody tr')).toHaveCount(12)
  await page.setViewportSize(PHONE)
  await expectNoSidewaysScroll(page)
  await page.setViewportSize(DESKTOP)
  await acceptRows(page, context, workspace, batch)
  const link = await exportBatch(page, 'Word and PDF', 12)

  const { dir, entries } = await downloadAndList(page, link)
  try {
    // Twelve Word files, twelve PDFs and the rows.csv ledger.
    expect(entries.filter((name) => name.endsWith('.docx'))).toHaveLength(12)
    expect(entries.filter((name) => name.endsWith('.pdf'))).toHaveLength(12)
    expect(entries).toContain('rows.csv')
    expect(entries).toHaveLength(25)

    const docxName = entries.find((name) => name.endsWith('.docx'))!
    const zip = join(dir, 'batch.zip')
    // The certificate is a zip of its own inside the archive: out first, then into it.
    execFileSync('unzip', ['-o', '-q', zip, docxName, '-d', dir])
    const inner = execFileSync('unzip', ['-p', join(dir, docxName), 'word/document.xml'], { encoding: 'utf8' })
    expect(inner.replace(/<[^>]*>/g, '')).toContain('Amara Alvesson 1')
    const ledger = execFileSync('unzip', ['-p', zip, 'rows.csv'], { encoding: 'utf8' })
    expect(ledger).toContain('Amara Alvesson 1')
  } finally {
    await rm(dir, { recursive: true, force: true })
  }

  // Home folds the batch's documents into one entry that opens the batch page.
  await page.goto('/')
  const openMenu = page.getByRole('button', { name: 'Open menu', exact: true })
  if (await openMenu.isVisible()) await openMenu.click()
  const batchEntry = page.locator(`.home__row[href="/batches/${batch}"]`)
  await expect(batchEntry).toBeVisible({ timeout: 15_000 })
  await expect(batchEntry).toContainText('12 documents')

  // Nothing asked the model for anything all the way through.
  expect(await monthRequests(context, workspace)).toBe(0)
})

test('the PDF certificate fills two hundred rows, exports them as PDFs and reports its timings', async ({
  page,
  context,
}) => {
  test.setTimeout(600_000)
  const templateName = `E2E batch pdf ${Date.now()}`
  const startedAt = Date.now()

  await learnForm(page, `${templateName}.pdf`, { mimeType: PDF_TYPE, buffer: await readFile(CERTIFICATE_PDF) })
  const learnedAt = Date.now()

  await page.goto('/')
  await openFillMany(page, templateName)
  await chooseSpreadsheet(page, { name: 'members-200.csv', buffer: await readFile(MEMBERS_200) })
  await mapColumns(page, PDF_FIELDS)
  const workspace = await workspaceId(context)
  const batch = await makeDocuments(page, 200)
  const filledAt = Date.now()

  await expect(page.locator('.batch__table tbody tr')).toHaveCount(200)
  await acceptRows(page, context, workspace, batch)
  const acceptedAt = Date.now()

  const link = await exportBatch(page, 'PDF', 200)
  const exportedAt = Date.now()

  const { dir, entries } = await downloadAndList(page, link)
  try {
    expect(entries.filter((name) => name.endsWith('.pdf'))).toHaveLength(200)
    expect(entries).toContain('rows.csv')
    expect(entries).toHaveLength(201)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
  const doneAt = Date.now()

  console.log(
    `[fill-many 200-row] learn ${learnedAt - startedAt} ms, fill ${filledAt - learnedAt} ms, ` +
      `accept ${acceptedAt - filledAt} ms, export ${exportedAt - acceptedAt} ms, ` +
      `download ${doneAt - exportedAt} ms, total ${doneAt - startedAt} ms`,
  )
  expect(await monthRequests(context, workspace)).toBe(0)
})
