import { Sandbox } from 'railway'

const MAX_BYTES = 20 * 1024 * 1024
const FILTERS = new Map([
  ['MS Word 97', 'input.doc'], ['MS Word 95', 'input.doc'],
  ['Rich Text Format', 'input.rtf'], ['writer8', 'input.odt'],
  ['writer8_template', 'input.ott'], ['Apple Pages', 'input.pages'],
])
export class JobFailure extends Error {
  constructor(step, details = {}) { super(step); this.step = step; Object.assign(this, details) }
}
const fail = (step) => { throw new JobFailure(step) }
const quote = (value) => `'${String(value).replaceAll("'", "'\\''")}'`

export function validateJob(job, imageId) {
  if (!job || job.expectedImageId !== imageId || !/^sha256:[a-f0-9]{64}$/.test(imageId)) fail('IMAGE_NOT_APPROVED')
  if (!Number.isSafeInteger(job.outputMaxBytes) || job.outputMaxBytes < 1 || job.outputMaxBytes > MAX_BYTES ||
      !Number.isSafeInteger(job.deadlineSeconds) || job.deadlineSeconds < 1 || job.deadlineSeconds > 60) fail('STAGING')
  const pdf = job.convertTo === 'pdf' && job.inputName === 'input.docx' && job.inputFilter === null &&
    job.outputName === 'input.pdf' && job.outputSignature === Buffer.from('%PDF-').toString('base64')
  const word = job.convertTo === 'docx:MS Word 2007 XML' && FILTERS.get(job.inputFilter) === job.inputName &&
    job.outputName === 'input.docx' && job.outputSignature === Buffer.from([0x50, 0x4b, 3, 4]).toString('base64')
  if (!pdf && !word) fail('STAGING')
  if (typeof job.input !== 'string' || job.input.length > Math.ceil(MAX_BYTES / 3) * 4) fail('STAGING')
  const input = Buffer.from(job.input, 'base64')
  if (!input.length || input.length > MAX_BYTES || input.toString('base64') !== job.input) fail('STAGING')
  return input
}

export function dockerCommand(job, imageId) {
  const args = ['docker', 'run', '--rm', '--name', 'brownie-render', '--network', 'none', '--read-only',
    '--tmpfs', '/tmp:rw,size=64m,mode=1777',
    '--tmpfs', '/home/renderer:rw,size=32m,mode=0700,uid=10001,gid=10001',
    '--memory=512m', '--memory-swap=512m', '--pids-limit=128', '--cpus=1', '--cap-drop=ALL',
    '--security-opt', 'no-new-privileges', '--ulimit', `fsize=${job.outputMaxBytes}`,
    '-v', '/job/in:/in:ro', '-v', '/job/out:/out', imageId,
    'soffice', '--headless', '--norestore', '--nolockcheck', '--nodefault',
    '-env:UserInstallation=file:///home/renderer/.lo-profile']
  if (job.inputFilter !== null) args.push(`--infilter=${job.inputFilter}`)
  args.push('--convert-to', job.convertTo, '--outdir', '/out', `/in/${job.inputName}`)
  return args.map(quote).join(' ')
}

// The container has exited before this runs; O_NOFOLLOW also refuses a planted output link.
function readBackCommand(job) {
  const script = `import os,stat,json\np='/job/out/${job.outputName}'\ntry:\n fd=os.open(p,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)\nexcept FileNotFoundError:\n print(json.dumps({'step':'NO_OUTPUT'})); raise SystemExit\nexcept OSError:\n print(json.dumps({'step':'NOT_A_PLAIN_FILE'})); raise SystemExit\nwith os.fdopen(fd,'rb') as f:\n if not stat.S_ISREG(os.fstat(f.fileno()).st_mode):\n  print(json.dumps({'step':'NOT_A_PLAIN_FILE'})); raise SystemExit\n data=f.read(${job.outputMaxBytes})\nif len(data)>=${job.outputMaxBytes}:\n print(json.dumps({'step':'OVER_QUOTA','quota':${job.outputMaxBytes}})); raise SystemExit\nimport base64\nif not data.startswith(base64.b64decode('${job.outputSignature}')):\n print(json.dumps({'step':'WRONG_SIGNATURE'})); raise SystemExit\nwith open('/job/checked.bin','wb') as out: out.write(data)\nprint(json.dumps({'size':len(data),'strayEntries':[n for n in os.listdir('/job/out') if n!='${job.outputName}'][:16]}))`
  return `python3 -c ${quote(script)}`
}

async function untilAborted(promise, signal) {
  signal.throwIfAborted()
  let listener
  const aborted = new Promise((_, reject) => { listener = () => reject(signal.reason); signal.addEventListener('abort', listener, { once: true }) })
  try { return await Promise.race([promise, aborted]) }
  finally { signal.removeEventListener('abort', listener) }
}

async function destroy(sandbox) {
  let timer
  try {
    await Promise.race([sandbox.destroy(), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Teardown timed out')), 10_000)
    })])
  } catch { console.error('Sandbox teardown failed; the two-minute idle expiry remains active:', sandbox.id) }
  finally { clearTimeout(timer) }
}

export async function runJob(job, { checkpoint, imageId, signal, create = (...args) => Sandbox.create(...args) }) {
  const input = validateJob(job, imageId)
  let sandbox
  const creating = create(checkpoint, { idleTimeoutMinutes: 2, networkIsolation: 'ISOLATED', resources: { cpu: 1, memoryGB: 1 } })
  // A create call may finish after its caller timed out. That VM must still be destroyed.
  creating.then((created) => { if (signal.aborted) void destroy(created) }, () => {})
  try {
    sandbox = await untilAborted(creating, signal)
    let result = await untilAborted(sandbox.exec('mkdir -p /job/in /job/out && chmod 700 /job && chmod 777 /job/out && docker info >/dev/null',
      { timeoutSec: 20, maxOutputBytes: 4096, ephemeral: true, signal }), signal)
    if (result.exitCode !== 0 || result.timedOut) fail('START_FAILED')
    await untilAborted(sandbox.files.write(`/job/in/${job.inputName}`, input, { mode: 0o444 }), signal)
    result = await untilAborted(sandbox.exec(dockerCommand(job, imageId),
      { timeoutSec: job.deadlineSeconds, maxOutputBytes: 64 * 1024, ephemeral: true, signal }), signal)
    if (result.timedOut) fail('TIMED_OUT')
    if (result.exitCode !== 0) throw new JobFailure('EXITED', { exitStatus: result.exitCode ?? -1 })
    result = await untilAborted(sandbox.exec(readBackCommand(job), { timeoutSec: 10, maxOutputBytes: 4096, ephemeral: true, signal }), signal)
    if (result.exitCode !== 0 || result.timedOut || result.truncated) fail('STAGING')
    const checked = JSON.parse(result.stdout)
    if (checked.step) throw new JobFailure(checked.step, { quota: checked.quota })
    if (!Number.isSafeInteger(checked.size) || checked.size < 1 || checked.size >= job.outputMaxBytes) fail('STAGING')
    const bytes = await untilAborted(sandbox.files.read('/job/checked.bin', { format: 'bytes', length: job.outputMaxBytes }), signal)
    if (bytes.length !== checked.size || !Buffer.from(bytes).subarray(0, Buffer.from(job.outputSignature, 'base64').length)
      .equals(Buffer.from(job.outputSignature, 'base64'))) fail('WRONG_SIGNATURE')
    return { bytes: Buffer.from(bytes).toString('base64'), imageId, strayEntries: checked.strayEntries }
  } finally {
    if (sandbox) await destroy(sandbox)
  }
}
