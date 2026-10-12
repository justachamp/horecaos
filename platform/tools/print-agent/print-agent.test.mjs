// Tests for the reference print agent: node --test tools/print-agent/
//
// The agent is proven against a fake platform (a real HTTP server that speaks the three agent calls, the
// device self-read, the token endpoint and the enrolment handshake) and a fake printer (a real TCP listener
// that answers DLE EOT and draws the raster it is sent). Nothing here is mocked at the function level, so a
// change to the wire contract breaks a test and not only a doc.

import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, statSync } from 'node:fs';
import { createServer as createTcpServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';

import {
  AGENT_BUILD,
  FAILURE,
  RefusedError,
  STATUS,
  UsageError,
  asciiPage,
  checkConfig,
  decodeEscPos,
  enrol,
  interpretStatus,
  isStatusQuery,
  makeLogger,
  probePrinter,
  run,
  startFakePrinter,
  writeCredential,
} from './print-agent.mjs';

const SILENT = makeLogger(() => {});
const TENANT = '018fc154-1000-7000-8000-0000000000a1';
const BRAND = '018fc154-1000-7000-8000-0000000000b1';
const LOCATION = '018fc154-1000-7000-8000-0000000000c1';
const JOB = '018fc154-1000-7000-8000-000000000f01';
const LEASE = '018fc154-1000-7000-8000-000000000e01';

/** One 24-dot-wide, 8-row page: a diagonal, framed the way EscPosEncoder frames it. */
function page(cut = true) {
  const rowBytes = 3;
  const rows = 8;
  const band = Buffer.alloc(rowBytes * rows);
  for (let row = 0; row < rows; row++) band[row * rowBytes + (row >> 3)] = 0x80 >> row;
  return Buffer.concat([
    Buffer.from([0x1b, 0x40]),
    Buffer.from([0x1d, 0x76, 0x30, 0x00, rowBytes, 0, rows, 0]),
    band,
    Buffer.from([0x1b, 0x64, 4]),
    ...(cut ? [Buffer.from([0x1d, 0x56, 0x42, 0x00])] : []),
  ]);
}

/** A platform that speaks just enough of the real one, and records what the agent said. */
async function fakePlatform() {
  const state = {
    requests: [],
    jobs: [], // claimable jobs: { jobId, printerKey, documentType, leaseToken }
    artifact: page(),
    reportStatus: 200,
    tokenStatus: 200,
    claimStatus: 200,
    enrolment: { polls: 0, approveAfter: 2 },
  };
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const raw = Buffer.concat(chunks).toString('utf8');
    const url = new URL(req.url, 'http://x');
    const entry = { method: req.method, path: url.pathname, query: url.search, headers: req.headers, raw };
    entry.json = raw && req.headers['content-type']?.includes('json') ? JSON.parse(raw) : undefined;
    state.requests.push(entry);
    const send = (status, body, type = 'application/json') => {
      res.writeHead(status, { 'Content-Type': type });
      res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
    };
    if (url.pathname === '/realms/h/token') {
      if (state.tokenStatus !== 200) return send(state.tokenStatus, { error: 'invalid_client' });
      return send(200, { access_token: 'token-' + state.requests.length, expires_in: 300 });
    }
    if (url.pathname === '/api/v1/control-plane/device-enrolments') {
      return send(200, { deviceCode: 'dev-code', userCode: 'ABCD-1234', expiresAt: '2026-10-09T12:00:00Z', pollIntervalSeconds: 1 });
    }
    if (url.pathname === '/api/v1/control-plane/device-enrolments/dev-code/poll') {
      state.enrolment.polls++;
      if (state.enrolment.polls < state.enrolment.approveAfter) return send(200, { status: 'PENDING', credential: null });
      return send(200, {
        status: 'APPROVED',
        credential: { tokenEndpoint: state.tokenEndpoint, clientId: 'agent-1', clientSecret: 'shh-do-not-log' },
      });
    }
    if (!req.headers.authorization?.startsWith('Bearer token-')) return send(401, { code: 'UNAUTHENTICATED' });
    if (url.pathname === '/api/v1/devices/me') {
      return send(200, { deviceId: 'd1', deviceClass: 'PRINT_AGENT', tenantId: TENANT, brandId: BRAND, locationId: LOCATION });
    }
    const base = `/api/v1/tenants/${TENANT}/brands/${BRAND}/locations/${LOCATION}/print`;
    if (url.pathname === `${base}/claims`) {
      if (state.claimStatus !== 200) return send(state.claimStatus, { code: 'INSUFFICIENT_CAPABILITY' });
      return send(200, { jobs: state.jobs.splice(0, entry.json.maxJobs ?? 1) });
    }
    const artifact = url.pathname.match(new RegExp(`^${base}/jobs/([^/]+)/artifact$`));
    if (artifact) return send(200, state.artifact, 'application/octet-stream');
    if (new RegExp(`^${base}/jobs/[^/]+/results$`).test(url.pathname)) {
      return send(state.reportStatus, state.reportStatus === 200 ? { result: 'RECORDED' } : { code: 'LEASE_MISMATCH' });
    }
    return send(404, { code: 'RESOURCE_NOT_FOUND' });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const apiBase = `http://127.0.0.1:${server.address().port}`;
  state.tokenEndpoint = `${apiBase}/realms/h/token`;
  state.apiBase = apiBase;
  state.close = () => new Promise((resolve) => server.close(resolve));
  state.of = (suffix) => state.requests.filter((request) => request.path.endsWith(suffix));
  return state;
}

function configFor(platform, printers, dir, extra = {}) {
  const config = checkConfig(
    { apiBase: platform.apiBase, printers, credentialFile: join(dir, 'credential.json'), connectTimeoutMs: 800, statusTimeoutMs: 300, ...extra },
    dir,
  );
  writeCredential(config.credentialFile, { tokenEndpoint: platform.tokenEndpoint, clientId: 'agent-1', clientSecret: 'shh-do-not-log' });
  return config;
}

function claimable(overrides = {}) {
  return { jobId: JOB, printerKey: 'counter', documentType: 'ORDER_SLIP', leaseToken: LEASE, leaseExpiresAt: '2026-10-09T12:01:00Z', ...overrides };
}

describe('status', () => {
  it('maps the three DLE EOT answers to the platform vocabulary', () => {
    assert.equal(interpretStatus({ printer: 0x12, cause: 0x12, paper: 0x12 }), STATUS.ONLINE);
    assert.equal(interpretStatus({ printer: 0x1a, cause: 0x12, paper: 0x12 }), STATUS.OFFLINE, 'offline bit alone');
    assert.equal(interpretStatus({ printer: 0x1a, cause: 0x16, paper: 0x12 }), STATUS.COVER_OPEN, 'cover bit');
    assert.equal(interpretStatus({ printer: 0x1a, cause: 0x12, paper: 0x72 }), STATUS.OUT_OF_PAPER, 'paper end bits');
    assert.equal(interpretStatus({ printer: 0x1a, cause: 0x52, paper: 0x12 }), STATUS.ERROR, 'error bit');
  });

  it('recognises a status question and nothing else', () => {
    assert.equal(isStatusQuery(Buffer.from([0x10, 0x04, 0x01])), true);
    assert.equal(isStatusQuery(Buffer.from([0x10, 0x04, 0x01, 0x10, 0x04, 0x02, 0x10, 0x04, 0x04])), true);
    assert.equal(isStatusQuery(Buffer.from([0x1b, 0x40, 0x00])), false);
    assert.equal(isStatusQuery(Buffer.from([0x10, 0x04, 0x03])), false);
    assert.equal(isStatusQuery(Buffer.alloc(0)), false);
  });

  it('asks a printer that answers, and calls one that is silent UNKNOWN and one that is gone OFFLINE', async () => {
    const fake = await startFakePrinter({ port: 0, host: '127.0.0.1', log: SILENT, show: false });
    const silent = createTcpServer((socket) => socket.on('data', () => {}));
    await new Promise((resolve) => silent.listen(0, '127.0.0.1', resolve));
    const timeouts = { connectTimeoutMs: 500, statusTimeoutMs: 250 };
    try {
      assert.equal(await probePrinter({ kind: 'tcp', host: '127.0.0.1', port: fake.port }, timeouts), STATUS.ONLINE);
      assert.equal(await probePrinter({ kind: 'tcp', host: '127.0.0.1', port: silent.address().port }, timeouts), STATUS.UNKNOWN);
      const gone = silent.address().port;
      await new Promise((resolve) => silent.close(resolve));
      assert.equal(await probePrinter({ kind: 'tcp', host: '127.0.0.1', port: gone }, timeouts), STATUS.OFFLINE);
      assert.equal(await probePrinter({ kind: 'file', path: '/dev/null' }, timeouts), STATUS.UNKNOWN, 'a device file cannot be asked');
    } finally {
      await fake.close();
    }
  });
});

describe('ESC/POS read-back', () => {
  it('decodes the framing the platform emits and draws the dots', () => {
    const decoded = decodeEscPos(page());
    assert.equal(decoded.widthDots, 24);
    assert.equal(decoded.heightDots, 8);
    assert.equal(decoded.cut, true);
    const art = asciiPage(decoded);
    assert.match(art, /#|\+|\./);
    assert.equal(art.split('\n').length, 2, 'eight rows are two lines of four');
  });

  it('refuses a command it does not know rather than drawing something else', () => {
    assert.throws(() => decodeEscPos(Buffer.from([0x1b, 0x2a, 0x00])), /Unknown ESC\/POS command/);
  });
});

describe('configuration', () => {
  it('needs an address and refuses an alias or a kind it cannot honour', () => {
    assert.throws(() => checkConfig({ printers: {} }), UsageError);
    assert.throws(() => checkConfig({ apiBase: 'https://api.example', printers: { 'bad alias!': { kind: 'tcp', host: 'x' } } }), /alias/);
    assert.throws(() => checkConfig({ apiBase: 'https://api.example', printers: { a: { kind: 'tcp' } } }), /host/);
    assert.throws(() => checkConfig({ apiBase: 'https://api.example', printers: { a: { kind: 'serial' } } }), /kind/);
    const ok = checkConfig({ apiBase: 'https://api.example/', printers: { counter: { kind: 'tcp', host: '192.168.1.50' } } });
    assert.equal(ok.apiBase, 'https://api.example');
    assert.equal(ok.waitSeconds, 20, 'the long poll defaults to the most the platform allows');
  });
});

describe('the loop against a fake platform and a fake printer', () => {
  let platform;
  let printer;
  let dir;

  beforeEach(async () => {
    platform = await fakePlatform();
    printer = await startFakePrinter({ port: 0, host: '127.0.0.1', log: SILENT, show: false });
    dir = mkdtempSync(join(tmpdir(), 'print-agent-'));
  });

  afterEach(async () => {
    await platform.close();
    await printer.close();
  });

  const counterAt = () => ({ counter: { kind: 'tcp', host: '127.0.0.1', port: printer.port } });

  it('claims, fetches the bytes under the lease, writes them to the printer and reports PRINTED', async () => {
    platform.jobs.push(claimable());
    const config = configFor(platform, counterAt(), dir);

    const handled = await run(config, { once: true, log: SILENT });

    assert.equal(handled, 1);
    assert.equal(printer.received.length, 1, 'one page reached the printer');
    assert.equal(printer.received[0].widthDots, 24);
    assert.equal(printer.received[0].cut, true);

    const claim = platform.of('/claims')[0];
    assert.deepEqual(claim.json.printers, [{ key: 'counter', status: 'ONLINE' }]);
    assert.equal(claim.json.agentBuild, AGENT_BUILD);
    assert.ok(claim.headers['idempotency-key'], 'a claim carries an Idempotency-Key');
    assert.match(claim.headers.authorization, /^Bearer token-/);

    const artifact = platform.of('/artifact')[0];
    assert.equal(artifact.query, `?lease=${LEASE}`, 'the bytes are asked for under the job lease');

    const result = platform.of('/results')[0];
    assert.deepEqual(result.json, { leaseToken: LEASE, outcome: 'PRINTED' });
    assert.ok(result.headers['idempotency-key'], 'a result carries an Idempotency-Key');
    assert.ok(platform.of('/devices/me').length === 1, 'the branch is learned, not typed');
  });

  it('with nothing to do it claims once and goes away', async () => {
    const handled = await run(configFor(platform, counterAt(), dir), { once: true, log: SILENT });
    assert.equal(handled, 0);
    assert.equal(platform.of('/results').length, 0);
    assert.equal(printer.received.length, 0);
  });

  it('a printer that cannot be reached is reported OFFLINE in the claim and the job FAILED with PRINTER_OFFLINE', async () => {
    const dead = await startFakePrinter({ port: 0, host: '127.0.0.1', log: SILENT, show: false });
    const port = dead.port;
    await dead.close();
    platform.jobs.push(claimable());
    const config = configFor(platform, { counter: { kind: 'tcp', host: '127.0.0.1', port } }, dir);

    await run(config, { once: true, log: SILENT });

    assert.deepEqual(platform.of('/claims')[0].json.printers, [{ key: 'counter', status: 'OFFLINE' }]);
    assert.deepEqual(platform.of('/results')[0].json, { leaseToken: LEASE, outcome: 'FAILED', failureCode: FAILURE.PRINTER_OFFLINE });
    assert.equal(printer.received.length, 0);
  });

  it('a job for an alias this machine does not have is FAILED, never guessed at', async () => {
    platform.jobs.push(claimable({ printerKey: 'kitchen-9' }));
    await run(configFor(platform, counterAt(), dir), { once: true, log: SILENT });
    assert.equal(platform.of('/results')[0].json.outcome, 'FAILED');
    assert.equal(platform.of('/artifact').length, 0, 'the bytes were not even fetched');
    assert.equal(printer.received.length, 0);
  });

  it('a lease the platform has already taken back (409 on the result) is not an error and is not retried', async () => {
    platform.jobs.push(claimable());
    platform.reportStatus = 409;
    const handled = await run(configFor(platform, counterAt(), dir), { once: true, log: SILENT });
    assert.equal(handled, 1);
    assert.equal(platform.of('/results').length, 1, 'one attempt: the job is the platform\'s again');
  });

  it('a revoked agent is refused at its next claim and says to enrol again', async () => {
    platform.claimStatus = 403;
    await assert.rejects(run(configFor(platform, counterAt(), dir), { once: true, log: SILENT }), (error) => {
      assert.ok(error instanceof RefusedError);
      assert.match(error.message, /enrol again/);
      return true;
    });
  });

  it('an identity server that refuses the credential is the same refusal', async () => {
    platform.tokenStatus = 401;
    await assert.rejects(run(configFor(platform, counterAt(), dir), { once: true, log: SILENT }), RefusedError);
  });

  it('writes a spool command\'s stdin and reports a command that fails as TRANSPORT', async () => {
    platform.jobs.push(claimable());
    const out = join(dir, 'spooled.bin');
    const config = configFor(platform, { counter: { kind: 'command', command: ['sh', '-c', `cat > '${out}'`] } }, dir);
    await run(config, { once: true, log: SILENT });
    assert.deepEqual(readFileSync(out), platform.artifact, 'the bytes arrive on stdin unchanged');
    assert.equal(platform.of('/results')[0].json.outcome, 'PRINTED');

    platform.jobs.push(claimable());
    const failing = configFor(platform, { counter: { kind: 'command', command: ['sh', '-c', 'exit 3'] } }, dir);
    await run(failing, { once: true, log: SILENT });
    assert.deepEqual(platform.of('/results')[1].json, { leaseToken: LEASE, outcome: 'FAILED', failureCode: FAILURE.TRANSPORT });
  });

  it('logs identifiers and outcomes, never the credential, a token or a byte of paper', async () => {
    platform.jobs.push(claimable());
    const lines = [];
    await run(configFor(platform, counterAt(), dir), { once: true, log: makeLogger((line) => lines.push(line)) });
    const text = lines.join('\n');
    assert.match(text, /printed/);
    assert.doesNotMatch(text, /shh-do-not-log|token-|Bearer/);
  });
});

describe('enrolment', () => {
  it('shows the code, waits for the manager, and keeps the credential for the owner only', async () => {
    const platform = await fakePlatform();
    const dir = mkdtempSync(join(tmpdir(), 'print-agent-enrol-'));
    const config = checkConfig({ apiBase: platform.apiBase, printers: {}, credentialFile: join(dir, 'sub', 'credential.json') }, dir);
    const shown = [];
    try {
      await enrol(config, 'Front counter machine', { log: SILENT, out: (line) => shown.push(line) });

      assert.equal(platform.of('/device-enrolments')[0].json.deviceClass, 'PRINT_AGENT');
      assert.equal(platform.of('/device-enrolments')[0].json.label, 'Front counter machine');
      assert.equal(platform.enrolment.polls, 2, 'one pending answer, then the approval');
      assert.ok(shown.join('\n').includes('ABCD-1234'), 'the code the manager types');
      assert.doesNotMatch(shown.join('\n'), /shh-do-not-log/, 'the secret is never shown');
      const saved = JSON.parse(readFileSync(config.credentialFile, 'utf8'));
      assert.equal(saved.clientId, 'agent-1');
      assert.equal(statSync(config.credentialFile).mode & 0o777, 0o600, 'owner-readable only');
    } finally {
      await platform.close();
    }
  });
});
