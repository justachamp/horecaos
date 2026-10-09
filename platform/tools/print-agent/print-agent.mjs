#!/usr/bin/env node
// HorecaOS reference print agent (ADR 0154, Decision 9).
//
// A byte pipe. It runs on a small always-on machine inside a branch, dials OUT to the platform over HTTPS,
// asks for work, writes the ESC/POS bytes the platform hands it to the printer next to it, and says what
// happened. It holds no template, no language and no business rule, it renders nothing, and the platform
// never sends it an address to dial: which printers it may reach, by what alias, lives in ITS OWN config.
//
//   node print-agent.mjs enrol  --config agent.json [--label "Front counter machine"]
//   node print-agent.mjs run    --config agent.json [--once]
//   node print-agent.mjs status --config agent.json
//   node print-agent.mjs fake-printer [--port 9100] [--out dir]
//
// Zero dependencies; Node 20 or newer (global fetch, node:test). The runbook that goes with it is
// platform/docs/runbooks/print-agent-and-printers.md, and the wire contract is its section 6.
//
// Exit codes: 0 done, 1 failed, 2 bad usage or config, 3 the platform refused this agent's credential
// (it was revoked, or deleted: enrol again, do not retry).

import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer, createConnection } from 'node:net';
import { homedir, hostname } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

export const AGENT_VERSION = '1.0.0';
export const AGENT_BUILD = `print-agent/${AGENT_VERSION}`;

/** The statuses the platform knows (PrinterStatus). ONLINE and UNKNOWN are claimable; the rest are not. */
export const STATUS = Object.freeze({
  ONLINE: 'ONLINE',
  OFFLINE: 'OFFLINE',
  OUT_OF_PAPER: 'OUT_OF_PAPER',
  COVER_OPEN: 'COVER_OPEN',
  ERROR: 'ERROR',
  UNKNOWN: 'UNKNOWN',
});

/** The reasons an attempt can fail, as the platform's FailureCode names them. */
export const FAILURE = Object.freeze({
  PRINTER_OFFLINE: 'PRINTER_OFFLINE',
  OUT_OF_PAPER: 'OUT_OF_PAPER',
  COVER_OPEN: 'COVER_OPEN',
  TRANSPORT: 'TRANSPORT',
});

export class RefusedError extends Error {
  constructor(message) {
    super(message);
    this.name = 'RefusedError';
  }
}

export class HttpError extends Error {
  constructor(status, body, what) {
    super(`${what} answered HTTP ${status}`);
    this.name = 'HttpError';
    this.status = status;
    this.body = body;
  }
}

// ---------------------------------------------------------------------------------------------------------
// Logging: one line per event, identifiers and outcomes only. A document, a note or a name never appears.

export function makeLogger(write = (line) => process.stderr.write(`${line}\n`)) {
  const emit = (level, message, fields) => {
    const extra = fields
      ? ' ' +
        Object.entries(fields)
          .map(([key, value]) => `${key}=${value}`)
          .join(' ')
      : '';
    write(`${new Date().toISOString()} ${level} ${message}${extra}`);
  };
  return {
    info: (message, fields) => emit('INFO', message, fields),
    warn: (message, fields) => emit('WARN', message, fields),
    error: (message, fields) => emit('ERROR', message, fields),
  };
}

// ---------------------------------------------------------------------------------------------------------
// Configuration

/** Reads and checks the agent's own config. The printers here are the ONLY addresses this process dials. */
export function loadConfig(path) {
  let raw;
  try {
    raw = JSON.parse(readFileSync(path, 'utf8'));
  } catch (error) {
    throw new UsageError(`Cannot read the config file ${path}: ${error.message}`);
  }
  return checkConfig(raw, dirname(resolve(path)));
}

export function checkConfig(raw, baseDir = process.cwd()) {
  const apiBase = String(raw.apiBase ?? process.env.HORECAOS_API_BASE ?? '').replace(/\/+$/, '');
  if (!/^https?:\/\/[^/\s]+/.test(apiBase)) {
    throw new UsageError('"apiBase" must be the platform address, for example https://api.horecaos.uz');
  }
  const printers = raw.printers ?? {};
  for (const [alias, printer] of Object.entries(printers)) {
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(alias)) {
      throw new UsageError(`Printer alias "${alias}" must be 1 to 64 letters, digits, dot, dash or underscore`);
    }
    if (printer.kind === 'tcp') {
      if (!printer.host) throw new UsageError(`Printer "${alias}": a tcp printer needs "host"`);
    } else if (printer.kind === 'file') {
      if (!printer.path) throw new UsageError(`Printer "${alias}": a file printer needs "path"`);
    } else if (printer.kind === 'command') {
      if (!Array.isArray(printer.command) || printer.command.length === 0) {
        throw new UsageError(`Printer "${alias}": a command printer needs "command" as a list`);
      }
    } else {
      throw new UsageError(`Printer "${alias}": "kind" is tcp, file or command`);
    }
  }
  const credentialFile = resolve(
    baseDir,
    String(raw.credentialFile ?? join(homedir(), '.horecaos-print-agent', 'credential.json')),
  );
  return {
    apiBase,
    printers,
    credentialFile,
    // Set by hand only to pin the branch; normally learned from GET /api/v1/devices/me after enrolment.
    scope: raw.scope ?? null,
    waitSeconds: clamp(raw.waitSeconds ?? 20, 0, 20),
    maxJobs: clamp(raw.maxJobs ?? 1, 1, 10),
    idleSeconds: clamp(raw.idleSeconds ?? 5, 1, 60),
    statusTimeoutMs: clamp(raw.statusTimeoutMs ?? 1500, 100, 10000),
    connectTimeoutMs: clamp(raw.connectTimeoutMs ?? 5000, 500, 30000),
    writeTimeoutMs: clamp(raw.writeTimeoutMs ?? 20000, 1000, 60000),
  };
}

export class UsageError extends Error {
  constructor(message) {
    super(message);
    this.name = 'UsageError';
  }
}

function clamp(value, low, high) {
  const number = Number(value);
  if (!Number.isFinite(number)) return low;
  return Math.min(high, Math.max(low, Math.trunc(number)));
}

export function readCredential(file) {
  if (!existsSync(file)) return null;
  const credential = JSON.parse(readFileSync(file, 'utf8'));
  if (!credential.tokenEndpoint || !credential.clientId || !credential.clientSecret) return null;
  return credential;
}

/** The credential is a secret (ADR 0028): written once, owner-readable only, never logged. */
export function writeCredential(file, credential) {
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  writeFileSync(file, JSON.stringify(credential), { mode: 0o600 });
  chmodSync(file, 0o600);
}

// ---------------------------------------------------------------------------------------------------------
// The platform: enrolment, token, and the three agent calls

/** Everything the agent says to the platform goes through here. `fetchImpl` is replaceable for a test. */
export class Platform {
  constructor(config, log, fetchImpl = fetch) {
    this.config = config;
    this.log = log;
    this.fetch = fetchImpl;
    this.credential = null;
    this.token = null;
    this.scope = config.scope;
  }

  // Enrolment (ADR 0079): unauthenticated by design, like the kitchen tablet's.
  async beginEnrolment(label) {
    const response = await this.fetch(`${this.config.apiBase}/api/v1/control-plane/device-enrolments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceClass: 'PRINT_AGENT', label }),
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) throw new HttpError(response.status, await safeText(response), 'Beginning enrolment');
    return response.json();
  }

  async pollEnrolment(deviceCode) {
    const response = await this.fetch(
      `${this.config.apiBase}/api/v1/control-plane/device-enrolments/${encodeURIComponent(deviceCode)}/poll`,
      { method: 'POST', signal: AbortSignal.timeout(15000) },
    );
    if (!response.ok) throw new HttpError(response.status, await safeText(response), 'Polling enrolment');
    return response.json();
  }

  /** A live access token, minted against the identity server directly (client credentials). */
  async accessToken(force = false) {
    if (!this.credential) {
      this.credential = readCredential(this.config.credentialFile);
      if (!this.credential) {
        throw new UsageError(`This machine is not enrolled: no credential in ${this.config.credentialFile}. Run "enrol".`);
      }
    }
    if (!force && this.token && this.token.expiresAt - Date.now() > 30000) return this.token.value;
    const response = await this.fetch(this.credential.tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'client_credentials',
        client_id: this.credential.clientId,
        client_secret: this.credential.clientSecret,
      }),
      signal: AbortSignal.timeout(15000),
    });
    if (response.status === 400 || response.status === 401 || response.status === 403) {
      throw new RefusedError(
        `The identity server refused this agent's credential (HTTP ${response.status}). It was revoked or removed: enrol again.`,
      );
    }
    if (!response.ok) throw new HttpError(response.status, await safeText(response), 'Requesting a token');
    const body = await response.json();
    this.token = { value: body.access_token, expiresAt: Date.now() + Number(body.expires_in ?? 60) * 1000 };
    return this.token.value;
  }

  /** An authenticated call. One 401 is a token that aged out between the check and the call: renew once. */
  async call(method, path, { body, headers = {}, timeoutMs = 30000, binary = false } = {}) {
    for (let attempt = 0; attempt < 2; attempt++) {
      const token = await this.accessToken(attempt > 0);
      const response = await this.fetch(`${this.config.apiBase}${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          Accept: binary ? 'application/octet-stream' : 'application/json',
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...headers,
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (response.status === 401 && attempt === 0) continue;
      if (response.status === 401 || response.status === 403) {
        throw new RefusedError(
          `The platform refused this agent (HTTP ${response.status}) for ${method} ${path.replace(/[0-9a-f-]{36}/g, ':id')}. ` +
            'It was revoked, or is not enrolled at this branch: enrol again.',
        );
      }
      if (!response.ok) throw new HttpError(response.status, await safeText(response), `${method} ${path}`);
      return binary ? Buffer.from(await response.arrayBuffer()) : response.status === 204 ? null : response.json();
    }
    throw new RefusedError('The platform did not accept a fresh token');
  }

  /** Learns the branch this agent was approved at (the device self-read). A pinned scope in the config wins. */
  async resolveScope() {
    if (this.scope) return this.scope;
    const me = await this.call('GET', '/api/v1/devices/me');
    this.scope = { tenantId: me.tenantId, brandId: me.brandId, locationId: me.locationId };
    this.log.info('enrolled at a branch', { location: me.locationId });
    return this.scope;
  }

  base(scope) {
    return (
      `/api/v1/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}` +
      `/locations/${encodeURIComponent(scope.locationId)}/print`
    );
  }

  /** claim: says what each printer last reported and how long to wait for work. The reply holds no paper. */
  claim(scope, printers, maxJobs, waitSeconds) {
    return this.call('POST', `${this.base(scope)}/claims`, {
      body: { printers, maxJobs, waitSeconds, agentBuild: AGENT_BUILD },
      headers: { 'Idempotency-Key': randomUUID() },
      timeoutMs: (waitSeconds + 20) * 1000,
    });
  }

  /** The ESC/POS bytes, readable only with the job's live lease token. */
  artifact(scope, jobId, lease) {
    return this.call('GET', `${this.base(scope)}/jobs/${encodeURIComponent(jobId)}/artifact?lease=${encodeURIComponent(lease)}`, {
      binary: true,
    });
  }

  /** report: PRINTED ends the job; FAILED with a failureCode returns it to the queue after a backoff. */
  report(scope, jobId, lease, outcome, failureCode, idempotencyKey) {
    return this.call('POST', `${this.base(scope)}/jobs/${encodeURIComponent(jobId)}/results`, {
      body: { leaseToken: lease, outcome, ...(failureCode ? { failureCode } : {}) },
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }
}

async function safeText(response) {
  try {
    return (await response.text()).slice(0, 400);
  } catch {
    return '';
  }
}

// ---------------------------------------------------------------------------------------------------------
// Printers: status by DLE EOT, delivery by raw TCP, a device file or a spool command

const GRACE_AFTER_FLUSH_MS = 1500;
const DLE_EOT_PRINTER = Buffer.from([0x10, 0x04, 0x01]);
const DLE_EOT_OFFLINE_CAUSE = Buffer.from([0x10, 0x04, 0x02]);
const DLE_EOT_PAPER = Buffer.from([0x10, 0x04, 0x04]);

/**
 * Maps the three DLE EOT answers of an ESC/POS printer to the platform's vocabulary.
 * n=1 bit 3: offline. n=2 bit 2: cover open, bit 6: error. n=4 bits 5-6: paper end.
 */
export function interpretStatus({ printer, cause, paper }) {
  const offline = (printer & 0x08) !== 0;
  const coverOpen = cause !== undefined && (cause & 0x04) !== 0;
  const errored = cause !== undefined && (cause & 0x40) !== 0;
  const paperEnd = paper !== undefined && (paper & 0x60) !== 0;
  if (coverOpen) return STATUS.COVER_OPEN;
  if (paperEnd) return STATUS.OUT_OF_PAPER;
  if (errored) return STATUS.ERROR;
  if (offline) return STATUS.OFFLINE;
  return STATUS.ONLINE;
}

/** Opens a raw TCP connection, asks the three status questions, and closes. */
export function probeTcp({ host, port = 9100 }, { connectTimeoutMs, statusTimeoutMs }) {
  return new Promise((resolvePromise) => {
    const answers = [];
    let settled = false;
    let connectTimer;
    let statusTimer;
    const socket = createConnection({ host, port });
    const done = (status) => {
      if (settled) return;
      settled = true;
      clearTimeout(connectTimer);
      clearTimeout(statusTimer);
      socket.destroy();
      resolvePromise(status);
    };
    connectTimer = setTimeout(() => done(STATUS.OFFLINE), connectTimeoutMs);
    socket.on('error', () => done(STATUS.OFFLINE));
    socket.on('connect', () => {
      clearTimeout(connectTimer);
      // A printer that answers nothing within the window is connected but cannot say: UNKNOWN is claimable.
      statusTimer = setTimeout(() => done(STATUS.UNKNOWN), statusTimeoutMs);
      socket.write(Buffer.concat([DLE_EOT_PRINTER, DLE_EOT_OFFLINE_CAUSE, DLE_EOT_PAPER]));
    });
    socket.on('data', (chunk) => {
      for (const byte of chunk) answers.push(byte);
      if (answers.length >= 3) {
        done(interpretStatus({ printer: answers[0], cause: answers[1], paper: answers[2] }));
      }
    });
  });
}

/** What the agent says about one configured printer. Only a raw TCP printer can be asked. */
export async function probePrinter(printer, timeouts) {
  if (printer.kind === 'tcp') return probeTcp({ host: printer.host, port: printer.port ?? 9100 }, timeouts);
  return STATUS.UNKNOWN;
}

/**
 * Writes the bytes to the printer. Resolves on success; rejects with `.failureCode` set to the platform's
 * FailureCode for what went wrong: PRINTER_OFFLINE when the printer cannot be reached at all, TRANSPORT when
 * the write itself failed after the connection was made.
 */
export function deliver(printer, bytes, timeouts) {
  if (printer.kind === 'tcp') return deliverTcp(printer, bytes, timeouts);
  if (printer.kind === 'file') return deliverFile(printer, bytes);
  return deliverCommand(printer, bytes, timeouts);
}

function failure(failureCode, message) {
  const error = new Error(message);
  error.failureCode = failureCode;
  return error;
}

function deliverTcp({ host, port = 9100 }, bytes, { connectTimeoutMs, writeTimeoutMs }) {
  return new Promise((resolvePromise, reject) => {
    let connected = false;
    let settled = false;
    let connectTimer;
    let writeTimer;
    let graceTimer;
    const socket = createConnection({ host, port });
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(connectTimer);
      clearTimeout(writeTimer);
      clearTimeout(graceTimer);
      socket.destroy();
      if (error) reject(error);
      else resolvePromise();
    };
    connectTimer = setTimeout(
      () => finish(failure(FAILURE.PRINTER_OFFLINE, 'The printer did not answer a connection')),
      connectTimeoutMs,
    );
    socket.on('error', (error) =>
      finish(
        connected
          ? failure(FAILURE.TRANSPORT, `Writing to the printer failed: ${error.code ?? error.message}`)
          : failure(FAILURE.PRINTER_OFFLINE, `Cannot reach the printer: ${error.code ?? error.message}`),
      ),
    );
    socket.on('connect', () => {
      connected = true;
      clearTimeout(connectTimer);
      writeTimer = setTimeout(
        () => finish(failure(FAILURE.TRANSPORT, 'The printer stopped taking data')),
        writeTimeoutMs,
      );
      socket.end(bytes);
    });
    // Every byte is with the operating system once 'finish' fires. Most printers close their side when they
    // have read it all; some never do, so a short grace period ends the wait rather than failing a page that
    // is already printing (which the platform would then print a second time).
    socket.on('finish', () => {
      graceTimer = setTimeout(() => finish(), GRACE_AFTER_FLUSH_MS);
    });
    socket.on('close', () => finish(connected ? undefined : failure(FAILURE.PRINTER_OFFLINE, 'Connection closed')));
  });
}

async function deliverFile({ path }, bytes) {
  try {
    writeFileSync(path, bytes, { flag: 'a' });
  } catch (error) {
    throw failure(
      error.code === 'ENOENT' || error.code === 'ENXIO' ? FAILURE.PRINTER_OFFLINE : FAILURE.TRANSPORT,
      `Writing ${path} failed: ${error.code ?? error.message}`,
    );
  }
}

function deliverCommand({ command }, bytes, { writeTimeoutMs }) {
  return new Promise((resolvePromise, reject) => {
    const [program, ...args] = command;
    const child = spawn(program, args, { stdio: ['pipe', 'ignore', 'pipe'] });
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(failure(FAILURE.TRANSPORT, `${program} did not finish in time`));
    }, writeTimeoutMs);
    child.stderr.on('data', (chunk) => {
      stderr = (stderr + chunk).slice(-200);
    });
    child.on('error', (error) => {
      clearTimeout(timer);
      reject(failure(FAILURE.TRANSPORT, `Could not start ${program}: ${error.code ?? error.message}`));
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) resolvePromise();
      else reject(failure(FAILURE.TRANSPORT, `${program} exited ${code}: ${stderr.trim()}`));
    });
    child.stdin.on('error', () => {});
    child.stdin.end(bytes);
  });
}

// ---------------------------------------------------------------------------------------------------------
// The loop

/** Probes every configured printer at once. */
export async function probeAll(config) {
  const entries = await Promise.all(
    Object.entries(config.printers).map(async ([key, printer]) => [key, await probePrinter(printer, config)]),
  );
  return Object.fromEntries(entries);
}

/**
 * Handles one claimed job: fetch the bytes under the lease, check the printer can take them, write them, and
 * report. A 409 (the lease lapsed or is not ours) means the platform has already given the job back: say so
 * and move on, the next claim will not repeat it to us unless it is ours again.
 */
export async function handleJob({ platform, scope, config, log }, job) {
  const printer = config.printers[job.printerKey];
  const lease = job.leaseToken;
  const fields = { job: job.jobId, printer: job.printerKey, type: job.documentType };
  const reportKey = randomUUID();
  const report = async (outcome, failureCode) => {
    for (let attempt = 1; ; attempt++) {
      try {
        await platform.report(scope, job.jobId, lease, outcome, failureCode, reportKey);
        return;
      } catch (error) {
        if (error instanceof HttpError && error.status === 409) {
          log.warn('the lease is no longer ours; the platform has the job back', fields);
          return;
        }
        if (error instanceof RefusedError || attempt >= 4) throw error;
        await sleep(500 * attempt);
      }
    }
  };

  if (!printer) {
    log.warn('a job for an alias this machine does not have', fields);
    return report('FAILED', FAILURE.PRINTER_OFFLINE);
  }
  let bytes;
  try {
    bytes = await platform.artifact(scope, job.jobId, lease);
  } catch (error) {
    if (error instanceof HttpError && (error.status === 409 || error.status === 422)) {
      log.warn('the platform would not hand over the bytes', { ...fields, status: error.status });
      return; // 409: not ours any more. 422: it cannot be drawn and the job is already dead.
    }
    throw error;
  }
  const before = await probePrinter(printer, config);
  const blocked = { OUT_OF_PAPER: FAILURE.OUT_OF_PAPER, COVER_OPEN: FAILURE.COVER_OPEN, OFFLINE: FAILURE.PRINTER_OFFLINE, ERROR: FAILURE.TRANSPORT }[before];
  if (blocked) {
    log.warn('the printer is not ready', { ...fields, status: before });
    return report('FAILED', blocked);
  }
  try {
    await deliver(printer, bytes, config);
  } catch (error) {
    log.warn('writing to the printer failed', { ...fields, code: error.failureCode ?? 'TRANSPORT' });
    return report('FAILED', error.failureCode ?? FAILURE.TRANSPORT);
  }
  log.info('printed', { ...fields, bytes: bytes.length });
  return report('PRINTED');
}

/** One pass of the loop: report statuses, claim, handle. Returns how many jobs were handled. */
export async function runOnce(context, waitSeconds) {
  const { platform, scope, config } = context;
  const statuses = await probeAll(config);
  const printers = Object.entries(statuses).map(([key, status]) => ({ key, status }));
  const reply = await platform.claim(scope, printers, config.maxJobs, waitSeconds);
  const jobs = reply.jobs ?? [];
  for (const job of jobs) await handleJob(context, job);
  return jobs.length;
}

export async function run(config, { once = false, signal, log = makeLogger(), fetchImpl = fetch } = {}) {
  if (Object.keys(config.printers).length === 0) {
    throw new UsageError('No printers are configured: add at least one under "printers" in the config');
  }
  const platform = new Platform(config, log, fetchImpl);
  const scope = await platform.resolveScope();
  const context = { platform, scope, config, log };
  log.info('agent started', { build: AGENT_BUILD, printers: Object.keys(config.printers).join(',') });
  let failures = 0;
  while (!signal?.aborted) {
    try {
      const startedAt = Date.now();
      const handled = await runOnce(context, once ? 0 : config.waitSeconds);
      failures = 0;
      if (once) return handled;
      // The platform holds a claim open while it has nothing; a reply that came straight back with nothing
      // must not become a busy loop.
      if (handled === 0 && Date.now() - startedAt < 1000) {
        await sleep(config.idleSeconds * 1000, undefined, { signal }).catch(() => {});
      }
    } catch (error) {
      if (error instanceof RefusedError || error instanceof UsageError) throw error;
      failures++;
      const delay = Math.min(30000, 1000 * 2 ** Math.min(failures, 5)) + Math.floor(Math.random() * 500);
      log.warn('the platform could not be reached; will try again', {
        error: error.name === 'TimeoutError' ? 'timeout' : (error.cause?.code ?? error.message),
        inMs: delay,
      });
      if (once) throw error;
      await sleep(delay, undefined, { signal }).catch(() => {});
    }
  }
  log.info('agent stopped');
  return 0;
}

export async function enrol(config, label, { log = makeLogger(), fetchImpl = fetch, out = (line) => console.log(line) } = {}) {
  const platform = new Platform(config, log, fetchImpl);
  const begun = await platform.beginEnrolment(label);
  out('');
  out(`  Approve this machine in the console (Settings > Printing and receipts > Agents), code:  ${begun.userCode}`);
  out(`  The code is valid until ${begun.expiresAt}.`);
  out('');
  const pace = Math.max(1, Number(begun.pollIntervalSeconds ?? 3)) * 1000;
  for (;;) {
    await sleep(pace);
    const polled = await platform.pollEnrolment(begun.deviceCode);
    if (polled.status === 'APPROVED' && polled.credential) {
      writeCredential(config.credentialFile, polled.credential);
      out(`Approved. The credential is saved in ${config.credentialFile} (owner-readable only).`);
      return polled;
    }
    if (polled.status === 'DENIED' || polled.status === 'EXPIRED') {
      throw new RefusedError(`The enrolment was ${polled.status.toLowerCase()}. Run "enrol" again for a new code.`);
    }
  }
}

// ---------------------------------------------------------------------------------------------------------
// A fake printer: what `make up` starts, and what a support engineer points the agent at to see a page.
// It answers DLE EOT with "online, no cover, paper present" and draws the raster bands it is sent as text.

/** Reads back the ESC/POS the platform emits: ESC @, GS v 0 bands, ESC d and GS V. Anything else is refused. */
export function decodeEscPos(bytes) {
  const rows = [];
  let widthBytes = 0;
  let cut = false;
  let index = 0;
  while (index < bytes.length) {
    const first = bytes[index];
    const second = bytes[index + 1];
    if (first === 0x10 && second === 0x04) {
      index += 3; // a status question, which the fake printer has already answered
    } else if (first === 0x1b && second === 0x40) {
      index += 2;
    } else if (first === 0x1b && second === 0x64) {
      index += 3;
    } else if (first === 0x1d && second === 0x56) {
      cut = true;
      index += bytes[index + 2] === 66 ? 4 : 3;
    } else if (first === 0x1d && second === 0x76 && bytes[index + 2] === 0x30) {
      const rowBytes = bytes[index + 4] | (bytes[index + 5] << 8);
      const height = bytes[index + 6] | (bytes[index + 7] << 8);
      if (widthBytes && widthBytes !== rowBytes) throw new Error('The bands of one page have one width');
      widthBytes = rowBytes;
      index += 8;
      for (let row = 0; row < height; row++) {
        rows.push(bytes.subarray(index, index + rowBytes));
        index += rowBytes;
      }
    } else {
      throw new Error(`Unknown ESC/POS command at byte ${index}: 0x${first.toString(16)}`);
    }
  }
  return { widthDots: widthBytes * 8, heightDots: rows.length, rows, cut };
}

/** A page as text, one character per two dot columns and four dot rows, for a terminal. */
export function asciiPage({ widthDots, rows }) {
  const dot = (row, x) => (rows[row][x >> 3] >> (7 - (x & 7))) & 1;
  const lines = [];
  for (let top = 0; top < rows.length; top += 4) {
    let line = '';
    for (let x = 0; x < widthDots; x += 2) {
      let set = 0;
      for (let dy = 0; dy < 4 && top + dy < rows.length; dy++) {
        set += dot(top + dy, x) + (x + 1 < widthDots ? dot(top + dy, x + 1) : 0);
      }
      line += set >= 4 ? '#' : set >= 2 ? '+' : set >= 1 ? '.' : ' ';
    }
    lines.push(line.replace(/\s+$/, ''));
  }
  return lines.join('\n');
}

/** A portable bitmap (P4), which any image viewer opens. */
export function toPbm({ widthDots, heightDots, rows }) {
  return Buffer.concat([Buffer.from(`P4\n${widthDots} ${heightDots}\n`), ...rows]);
}

/** Whether a chunk is only DLE EOT status questions (10 04 n, repeated). */
export function isStatusQuery(chunk) {
  if (chunk.length === 0 || chunk.length % 3 !== 0) return false;
  for (let i = 0; i < chunk.length; i += 3) {
    if (chunk[i] !== 0x10 || chunk[i + 1] !== 0x04 || ![1, 2, 4].includes(chunk[i + 2])) return false;
  }
  return true;
}

export function startFakePrinter({ port = 9100, host = '0.0.0.0', out = null, log = makeLogger(), show = true } = {}) {
  let pages = 0;
  const received = [];
  const server = createServer((socket) => {
    const chunks = [];
    let idle;
    const flush = () => {
      if (chunks.length === 0) return;
      const bytes = Buffer.concat(chunks.splice(0));
      try {
        const page = decodeEscPos(bytes);
        pages++;
        received.push(page);
        log.info('page received', { page: pages, widthDots: page.widthDots, heightDots: page.heightDots, cut: page.cut });
        if (show) console.log(asciiPage(page));
        if (out) {
          mkdirSync(out, { recursive: true });
          writeFileSync(join(out, `page-${String(pages).padStart(4, '0')}.pbm`), toPbm(page));
        }
      } catch (error) {
        log.error('bytes the fake printer cannot read', { error: error.message });
      }
    };
    socket.on('data', (chunk) => {
      if (isStatusQuery(chunk)) {
        // Online, cover closed and no error, paper adequate: 0x12 is the idle answer to n = 1, 2 and 4.
        socket.write(Buffer.alloc(chunk.length / 3, 0x12));
        return;
      }
      chunks.push(chunk);
      clearTimeout(idle);
      idle = setTimeout(flush, 200);
    });
    socket.on('end', () => {
      clearTimeout(idle);
      flush();
    });
    socket.on('error', () => {});
  });
  return new Promise((resolvePromise) => {
    server.listen(port, host, () => {
      log.info('fake printer listening', { port: server.address().port });
      resolvePromise({ server, port: server.address().port, received, close: () => new Promise((done) => server.close(done)) });
    });
  });
}

// ---------------------------------------------------------------------------------------------------------
// Command line

function parseArgs(argv) {
  const [command, ...rest] = argv;
  const options = {};
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i];
    if (!arg.startsWith('--')) throw new UsageError(`Unexpected argument "${arg}"`);
    const key = arg.slice(2);
    if (['once'].includes(key)) options[key] = true;
    else options[key] = rest[++i];
  }
  return { command, options };
}

const USAGE = `Usage:
  print-agent.mjs enrol  --config agent.json [--label "Front counter machine"]
  print-agent.mjs run    --config agent.json [--once]
  print-agent.mjs status --config agent.json
  print-agent.mjs fake-printer [--port 9100] [--out dir]`;

export async function main(argv) {
  const { command, options } = parseArgs(argv);
  const log = makeLogger();
  if (command === 'fake-printer') {
    await startFakePrinter({ port: Number(options.port ?? 9100), out: options.out ?? null, log });
    return new Promise(() => {});
  }
  if (!['enrol', 'run', 'status'].includes(command)) throw new UsageError(USAGE);
  if (!options.config) throw new UsageError(`--config is required\n${USAGE}`);
  const config = loadConfig(options.config);
  if (command === 'enrol') {
    await enrol(config, options.label ?? `${hostname()} print agent`, { log });
    return 0;
  }
  if (command === 'status') {
    const statuses = await probeAll(config);
    for (const [key, status] of Object.entries(statuses)) console.log(`${key}\t${status}`);
    return 0;
  }
  const controller = new AbortController();
  // The first signal finishes the job in hand and stops; the second stops now.
  for (const name of ['SIGINT', 'SIGTERM']) {
    process.on(name, () => {
      if (controller.signal.aborted) process.exit(130);
      controller.abort();
    });
  }
  await run(config, { once: Boolean(options.once), signal: controller.signal, log });
  return 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code ?? 0),
    (error) => {
      if (error instanceof UsageError) {
        console.error(error.message);
        process.exit(2);
      }
      if (error instanceof RefusedError) {
        console.error(error.message);
        process.exit(3);
      }
      console.error(`print-agent failed: ${error.message}`);
      process.exit(1);
    },
  );
}
