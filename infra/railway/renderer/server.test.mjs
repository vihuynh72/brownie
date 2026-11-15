import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'
import { once } from 'node:events'

const imageId = `sha256:${'a'.repeat(64)}`
const token = 'test-token-' + 'x'.repeat(32)
test('HTTP boundary requires authentication and refuses bad jobs before allocating a sandbox', async () => {
  const process = spawn(globalThis.process.execPath, ['server.mjs'], {
    cwd: import.meta.dirname,
    env: { ...globalThis.process.env, PORT: '0', BROWNIE_RENDER_CHECKPOINT: 'test-checkpoint',
      BROWNIE_RENDER_EXPECTED_IMAGE_ID: imageId, BROWNIE_RENDER_SERVICE_TOKEN: token },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  try {
    const lines = createInterface({ input: process.stdout })
    const [line] = await once(lines, 'line')
    const port = Number(line.match(/port (\d+)/)[1])
    const base = `http://127.0.0.1:${port}`
    const health = await fetch(base + '/healthz')
    assert.equal(health.status, 200)
    const unauthorized = await fetch(base + '/render', { method: 'POST', body: '{}' })
    assert.equal(unauthorized.status, 401)
    const invalid = await fetch(base + '/render', { method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: '{}' })
    assert.equal(invalid.status, 502)
    assert.deepEqual(await invalid.json(), { step: 'IMAGE_NOT_APPROVED' })
    assert.equal((await fetch(base + '/other')).status, 404)
  } finally {
    process.kill('SIGTERM')
    await once(process, 'exit')
  }
})
