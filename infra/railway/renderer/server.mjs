import { createServer } from 'node:http'
import { createHash, timingSafeEqual } from 'node:crypto'
import { JobFailure, runJob } from './renderer.mjs'

const checkpoint = process.env.BROWNIE_RENDER_CHECKPOINT
const imageId = process.env.BROWNIE_RENDER_EXPECTED_IMAGE_ID
const token = process.env.BROWNIE_RENDER_SERVICE_TOKEN
if (!checkpoint || !/^sha256:[a-f0-9]{64}$/.test(imageId ?? '') || !token || token.length < 32) {
  throw new Error('Renderer checkpoint, immutable image ID and service token (at least 32 characters) are required')
}
const hash = (value) => createHash('sha256').update(value).digest()
const credential = hash(`Bearer ${token}`)
const running = new Set()
let active = 0
const respond = (response, status, body) => {
  if (!response.destroyed) response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }).end(JSON.stringify(body))
}

const server = createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/healthz') return respond(response, 200, { status: 'up' })
  if (request.method !== 'POST' || request.url !== '/render') return respond(response, 404, {})
  if (!timingSafeEqual(credential, hash(request.headers.authorization ?? ''))) return respond(response, 401, {})
  if (active >= 2) return respond(response, 503, { step: 'START_FAILED' })
  active++
  const controller = new AbortController()
  running.add(controller)
  const timer = setTimeout(() => controller.abort(new JobFailure('TIMED_OUT')), 120_000)
  const disconnect = () => { if (!response.writableEnded) controller.abort(new JobFailure('INTERRUPTED')) }
  response.once('close', disconnect)
  try {
    let size = 0
    const chunks = []
    for await (const chunk of request) {
      size += chunk.length
      if (size > 28 * 1024 * 1024) return respond(response, 413, { step: 'STAGING' })
      chunks.push(chunk)
    }
    const job = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    const output = await runJob(job, { checkpoint, imageId, signal: controller.signal })
    respond(response, 200, output)
  } catch (error) {
    const failure = error instanceof JobFailure ? error : new JobFailure('START_FAILED')
    respond(response, 502, { step: failure.step, exitStatus: failure.exitStatus, quota: failure.quota })
  } finally {
    clearTimeout(timer)
    response.removeListener('close', disconnect)
    running.delete(controller)
    active--
  }
})
server.requestTimeout = 15_000
server.headersTimeout = 10_000
server.listen(Number(process.env.PORT ?? 8080), '::', () => console.log('Renderer listening on port', server.address().port))

process.once('SIGTERM', () => {
  for (const controller of running) controller.abort(new JobFailure('INTERRUPTED'))
  server.close()
  setTimeout(() => process.exit(0), 15_000).unref()
})
