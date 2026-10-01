/// <reference types="vitest/config" />
import { createReadStream, readdirSync, readFileSync, statSync } from 'node:fs'
import { extname, join } from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * PDF.js fetches some files itself, by URL, rather than through an import: the WebAssembly decoders
 * for scanned pictures (JBIG2 and JPEG 2000) with their plain-script fallbacks and the colour
 * management module, the fonts a PDF names without embedding them, the character maps of Asian
 * fonts, and a colour profile. This copies them from pdfjs-dist into the build under
 * assets/pdfjs-<version>/, and serves the same paths while developing. The version in the path keeps
 * a browser's long-lived cache of /assets/ from ever mixing two releases.
 */
function pdfjsAssets(): Plugin {
  const source = fileURLToPath(new URL('./node_modules/pdfjs-dist/', import.meta.url))
  const version = (JSON.parse(readFileSync(join(source, 'package.json'), 'utf8')) as { version: string }).version
  const base = `assets/pdfjs-${version}`
  const folders = ['wasm', 'standard_fonts', 'cmaps', 'iccs']
  // The engine for a PDF's own scripts is left out: this app never runs them.
  const wanted = (file: string) => !file.startsWith('quickjs')
  const types: Record<string, string> = {
    '.wasm': 'application/wasm',
    '.js': 'text/javascript',
    '.ttf': 'font/ttf',
  }
  return {
    name: 'brownie-pdfjs-assets',
    configureServer(server) {
      const prefix = `/${base}/`
      server.middlewares.use((request, response, next) => {
        const path = (request.url ?? '').split('?')[0] ?? ''
        if (!path.startsWith(prefix)) return next()
        const parts = path.slice(prefix.length).split('/')
        const [folder, file] = parts
        if (parts.length !== 2 || !folder || !file || !folders.includes(folder) || !wanted(file) || file.startsWith('.')) return next()
        const onDisk = join(source, folder, file)
        try {
          if (!statSync(onDisk).isFile()) return next()
        } catch {
          return next()
        }
        response.setHeader('Content-Type', types[extname(file)] ?? 'application/octet-stream')
        createReadStream(onDisk).pipe(response)
      })
    },
    generateBundle() {
      for (const folder of folders) {
        for (const file of readdirSync(join(source, folder)).filter(wanted)) {
          this.emitFile({ type: 'asset', fileName: `${base}/${folder}/${file}`, source: readFileSync(join(source, folder, file)) })
        }
      }
    },
  }
}

// In production, NGINX routes /api, /oauth2, and /logout to the API under
// one public origin (see the repository README's "Browser and session
// boundary" notes). This dev-server proxy recreates that same-origin
// arrangement locally, so a session cookie set by the API is usable by
// fetch calls the dev server itself serves on a different port.
// Where the dev server proxies API calls to. Overridable so a second API instance (for example one
// started on other ports while yours keeps running) can be driven through the same dev server.
const apiOrigin = process.env.BROWNIE_API_ORIGIN ?? 'http://localhost:8081'

export default defineConfig({
  plugins: [vue(), pdfjsAssets()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': apiOrigin,
      '/oauth2': apiOrigin,
      '/login': apiOrigin,
      '/logout': apiOrigin,
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    // e2e/ holds real Playwright specs (a different test runner, a different `test`/`expect`) --
    // vitest's own default include glob would otherwise try, and fail, to run them too.
    exclude: ['**/node_modules/**', '**/e2e/**'],
  },
})
