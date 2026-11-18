import test from 'node:test'
import assert from 'node:assert/strict'
import { dockerCommand, runJob, validateJob } from './renderer.mjs'

const imageId = `sha256:${'a'.repeat(64)}`
const bytes = Buffer.from('%PDF-1.7 example')
const job = {
  input: Buffer.from('docx package').toString('base64'), inputName: 'input.docx', inputFilter: null,
  convertTo: 'pdf', outputName: 'input.pdf', outputSignature: Buffer.from('%PDF-').toString('base64'),
  outputMaxBytes: 1024, deadlineSeconds: 60, expectedImageId: imageId,
}
const options = (create, signal = new AbortController().signal) => ({ checkpoint: 'renderer', imageId, signal, create })
const result = (stdout = '') => ({ exitCode: 0, timedOut: false, truncated: false, stdout })
function fakeSandbox(secondResult = result()) {
  let calls = 0
  return {
    destroyed: 0, id: 'test-vm',
    async exec() { calls++; return calls === 2 ? secondResult : calls === 3 ? result(JSON.stringify({ size: bytes.length, strayEntries: [] })) : result() },
    files: { async write() {}, async read() { return bytes } },
    async destroy() { this.destroyed++ },
  }
}

test('accepts only fixed conversion contracts, strict base64, bounded inputs and the approved image', () => {
  assert.deepEqual(validateJob(job, imageId), Buffer.from('docx package'))
  for (const changed of [
    { inputName: '../../secret' }, { inputFilter: 'arbitrary' }, { outputName: 'other.pdf' },
    { input: job.input + '\n' }, { outputMaxBytes: 21 * 1024 * 1024 }, { deadlineSeconds: 61 },
    { expectedImageId: `sha256:${'b'.repeat(64)}` },
  ]) assert.throws(() => validateJob({ ...job, ...changed }, imageId))
  assert.doesNotThrow(() => validateJob({ ...job, inputName: 'input.doc', inputFilter: 'MS Word 97',
    convertTo: 'docx:MS Word 2007 XML', outputName: 'input.docx', outputSignature: 'UEsDBA==' }, imageId))
})

test('keeps every existing container isolation limit and runs by immutable ID', () => {
  const command = dockerCommand(job, imageId)
  for (const flag of ['--network', 'none', '--read-only', '--memory=512m', '--memory-swap=512m',
    '--pids-limit=128', '--cpus=1', '--cap-drop=ALL', 'no-new-privileges', 'fsize=1024', '/job/in:/in:ro', imageId]) {
    assert.ok(command.includes(`'${flag}'`), flag)
  }
})

test('fresh VM has no private network or credentials and is destroyed after successful readback', async () => {
  const sandbox = fakeSandbox()
  const answer = await runJob(job, options(async (checkpoint, config) => {
    assert.equal(checkpoint, 'renderer')
    assert.deepEqual(config, { idleTimeoutMinutes: 2, networkIsolation: 'ISOLATED', resources: { cpu: 1, memoryGB: 1 } })
    return sandbox
  }))
  assert.equal(answer.bytes, bytes.toString('base64'))
  assert.equal(answer.imageId, imageId)
  assert.equal(sandbox.destroyed, 1)
})

test('timeout and container failures both destroy the VM', async () => {
  for (const failed of [{ exitCode: -1, timedOut: true }, { exitCode: 127, timedOut: false }]) {
    const sandbox = fakeSandbox(failed)
    await assert.rejects(runJob(job, options(async () => sandbox)), (error) => error.step === (failed.timedOut ? 'TIMED_OUT' : 'EXITED'))
    assert.equal(sandbox.destroyed, 1)
  }
})

test('a VM created after cancellation is still destroyed', async () => {
  const sandbox = fakeSandbox()
  const controller = new AbortController()
  let resolve
  const creating = new Promise((done) => { resolve = done })
  const running = runJob(job, options(() => creating, controller.signal))
  controller.abort(new Error('cancelled'))
  await assert.rejects(running, /cancelled/)
  resolve(sandbox)
  await new Promise((done) => setImmediate(done))
  assert.equal(sandbox.destroyed, 1)
})
