import { expect, type Page, type TestInfo } from '@playwright/test'
import { AxeBuilder } from '@axe-core/playwright'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs'

/*
 * What the PDF form specs share: uploading a form from Home, exporting it (a PDF, and only a PDF),
 * reading the exported file back with PDF.js, and leaving My Templates as it was found.
 */

export const FILLABLE_PDF = fileURLToPath(new URL('../../../fixtures/public/pdf/fillable-application.pdf', import.meta.url))
export const FLAT_PDF = fileURLToPath(new URL('../../../fixtures/public/pdf/flat-application.pdf', import.meta.url))

const STANDARD_FONTS = fileURLToPath(new URL('../node_modules/pdfjs-dist/standard_fonts/', import.meta.url))

/** Uploads a PDF from Home and waits for the document made from it; answers with its number. */
export async function uploadPdfFromHome(page: Page, path: string, name: string): Promise<number> {
  await page.goto('/')
  const upload = page.getByRole('button', { name: 'Upload your documents', exact: true })
  await expect(upload).toBeVisible({ timeout: 15_000 })
  const chooser = page.waitForEvent('filechooser')
  await upload.click()
  await (await chooser).setFiles({ name: `${name}.pdf`, mimeType: 'application/pdf', buffer: await readFile(path) })
  await page.waitForURL(/\/documents\/\d+$/, { timeout: 60_000 })
  await expect(page.locator('.workspace-title')).toHaveText(new RegExp(`^${name}, `), { timeout: 15_000 })
  // The pages are drawn by PDF.js in this browser, each an image named by its page.
  await expect(page.getByRole('img', { name: /^Page 1 of \d+$/ })).toBeVisible({ timeout: 15_000 })
  return Number(new URL(page.url()).pathname.split('/').pop())
}

/** Leaves the shared test workspace's My Templates as it found it: a template this test made goes to the Trash Bin. */
export async function trashTemplatesNamed(page: Page, name: string): Promise<void> {
  const workspaceId = (await (await page.request.get('/api/v1/me')).json()).memberships[0].workspaceId
  const templates = (await (await page.request.get(`/api/v1/workspaces/${workspaceId}/templates`)).json()) as { id: number; displayName: string }[]
  const xsrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
  for (const template of templates.filter((candidate) => candidate.displayName === name)) {
    await page.request.post(`/api/v1/workspaces/${workspaceId}/templates/${template.id}/trash`, { headers: { 'X-XSRF-TOKEN': xsrf } })
  }
}

/**
 * Exports the open document the only way a PDF form is offered, and downloads the file: the dialog
 * says why there is no Word file, and offers no choice of format.
 */
export async function exportAsPdf(page: Page, testInfo: TestInfo, fileName: string): Promise<Uint8Array> {
  await page.getByRole('button', { name: 'Export', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Export' })
  await expect(dialog.getByText('Ready to export.')).toBeVisible({ timeout: 90_000 })
  await expect(dialog.getByText('This is a PDF form, so Brownie fills it and exports it as a PDF. It does not turn it into a Word file.')).toBeVisible()
  await expect(dialog.getByRole('radio')).toHaveCount(0)
  // The dialog fades in; a scan made while it is still faint measures colours it never settles on.
  await dialog.evaluate((element) =>
    Promise.all(
      element
        .getAnimations({ subtree: true })
        .filter((animation) => animation.effect?.getComputedTiming().endTime !== Infinity)
        .map((animation) => animation.finished),
    ),
  )
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([])

  await dialog.getByRole('button', { name: 'Approve and export' }).click()
  await expect(dialog.getByText('PDF exported.', { exact: true })).toBeVisible({ timeout: 90_000 })
  await expect(dialog.getByRole('link', { name: 'Download Word file (.docx)' })).toHaveCount(0)
  const downloadPromise = page.waitForEvent('download')
  await dialog.getByRole('link', { name: 'Download PDF', exact: true }).click()
  const download = await downloadPromise
  expect(await download.failure()).toBeNull()
  const filePath = testInfo.outputPath(fileName)
  await download.saveAs(filePath)
  await dialog.getByRole('button', { name: 'Close' }).click()
  const bytes = new Uint8Array(await readFile(filePath))
  expect(new TextDecoder().decode(bytes.subarray(0, 5))).toBe('%PDF-')
  return bytes
}

/** A PDF's own form fields and their values, by full name, as PDF.js reads them. */
export async function pdfFieldValues(bytes: Uint8Array): Promise<Map<string, unknown>> {
  const task = getDocument({ data: bytes.slice(), standardFontDataUrl: STANDARD_FONTS })
  try {
    const document = await task.promise
    const fields = ((await document.getFieldObjects()) ?? new Map()) as Map<string, { value?: unknown }[]> | Record<string, { value?: unknown }[]>
    const entries = fields instanceof Map ? [...fields] : Object.entries(fields)
    return new Map(entries.map(([name, widgets]) => [name, widgets.find((widget) => widget.value !== undefined)?.value]))
  } finally {
    await task.destroy()
  }
}

/** Every word on every page of a PDF, as PDF.js reads them. */
export async function pdfText(bytes: Uint8Array): Promise<string> {
  const task = getDocument({ data: bytes.slice(), standardFontDataUrl: STANDARD_FONTS })
  try {
    const document = await task.promise
    const pages: string[] = []
    for (let number = 1; number <= document.numPages; number++) {
      const content = await (await document.getPage(number)).getTextContent()
      pages.push(content.items.map((item) => ('str' in item ? item.str : '')).join(' '))
    }
    return pages.join('\n')
  } finally {
    await task.destroy()
  }
}

/**
 * Scrolls so a height of a page, in points from its top as shown, is in the middle of the window, so the
 * pointer can reach it: in a short window, the notes above the page push its lower half below the fold.
 * Work out the points to press after this.
 */
export async function scrollPageTo(page: Page, pageNumber: number, y: number, pageWidthPoints = 612): Promise<void> {
  await page.locator(`[data-page="${pageNumber}"]`).evaluate(
    (element, [top, width]) => {
      const probe = document.createElement('div')
      probe.style.cssText = `position: absolute; left: 0; top: ${(top * element.getBoundingClientRect().width) / width}px; width: 1px; height: 1px;`
      element.appendChild(probe)
      probe.scrollIntoView({ block: 'center' })
      probe.remove()
    },
    [y, pageWidthPoints] as const,
  )
}

/**
 * Where a point of a page, in points from its top-left as shown, is on the screen. The public
 * fixtures are US Letter pages, 612 points wide, not turned.
 */
export async function screenPoint(page: Page, pageNumber: number, x: number, y: number, pageWidthPoints = 612): Promise<{ x: number; y: number }> {
  const box = await page.locator(`[data-page="${pageNumber}"]`).boundingBox()
  if (!box) throw new Error(`Page ${pageNumber} is not on the screen.`)
  const scale = box.width / pageWidthPoints
  return { x: box.x + x * scale, y: box.y + y * scale }
}
