import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../core/api/operations-paths';
import {
  DeviceTerminalApi,
  OpenedSession,
  RosterMember,
  TerminalError,
  TerminalRoster,
} from './terminal-api';
import { TerminalSession } from './terminal-session';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const AZIZA: RosterMember = { memberId: 'm1', label: 'Aziza K.', pinState: 'ACTIVE' };

function roster(overrides: Partial<TerminalRoster> = {}): TerminalRoster {
  return {
    attributionMode: 'PERSON_REQUIRED',
    idleSeconds: 120,
    sessionMaxHours: 12,
    pinLength: 6,
    members: [AZIZA],
    ...overrides,
  };
}

function opened(overrides: Partial<OpenedSession> = {}): OpenedSession {
  return {
    sessionToken: 'token-1',
    member: AZIZA,
    idleSeconds: 120,
    hardExpiresAt: new Date(Date.now() + 12 * 3_600_000).toISOString(),
    ...overrides,
  };
}

describe('TerminalSession (ADR 0155)', () => {
  let api: {
    roster: ReturnType<typeof vi.fn>;
    unlock: ReturnType<typeof vi.fn>;
    activate: ReturnType<typeof vi.fn>;
    changePin: ReturnType<typeof vi.fn>;
    touch: ReturnType<typeof vi.fn>;
    lock: ReturnType<typeof vi.fn>;
  };
  let terminal: TerminalSession;

  beforeEach(() => {
    api = {
      roster: vi.fn().mockResolvedValue(roster()),
      unlock: vi.fn().mockResolvedValue(opened()),
      activate: vi.fn().mockResolvedValue(opened()),
      changePin: vi.fn().mockResolvedValue(undefined),
      touch: vi.fn().mockResolvedValue(undefined),
      lock: vi.fn().mockResolvedValue(undefined),
    };
    TestBed.configureTestingModule({
      providers: [{ provide: DeviceTerminalApi, useValue: api }],
    });
    terminal = TestBed.inject(TerminalSession);
  });

  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('requires a lock screen exactly when the mode says a person must be unlocked and nobody is', async () => {
    expect(terminal.lockRequired()).toBe(false);

    await terminal.refresh(SCOPE);
    expect(terminal.lockRequired()).toBe(true);
    expect(terminal.canUnlock()).toBe(true);

    await terminal.unlock('m1', '482915');
    expect(terminal.lockRequired()).toBe(false);
    expect(terminal.person()?.label).toBe('Aziza K.');
    expect(terminal.sessionToken()).toBe('token-1');

    api.roster.mockResolvedValue(roster({ attributionMode: 'PERSON_OPTIONAL' }));
    await terminal.lock();
    await terminal.refresh(SCOPE);
    expect(terminal.lockRequired()).toBe(false);
    expect(terminal.canUnlock()).toBe(true);
  });

  it('holds the session token in memory only: not in local or session storage', async () => {
    await terminal.refresh(SCOPE);
    await terminal.unlock('m1', '482915');

    const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage });
    expect(stored).not.toContain('token-1');
    expect(terminal.sessionToken()).toBe('token-1');
  });

  it('keeps what it knew when the roster cannot be read, so a blip does not unlock a screen', async () => {
    await terminal.refresh(SCOPE);
    api.roster.mockRejectedValue(new TypeError('offline'));

    await terminal.refresh(SCOPE);

    expect(terminal.lockRequired()).toBe(true);
    expect(terminal.members()).toEqual([AZIZA]);
  });

  it('drops a session when a manager rolls the device back to DEVICE_ONLY', async () => {
    await terminal.refresh(SCOPE);
    await terminal.unlock('m1', '482915');

    api.roster.mockResolvedValue(roster({ attributionMode: 'DEVICE_ONLY', members: [] }));
    await terminal.refresh(SCOPE);

    expect(terminal.unlocked()).toBe(false);
    expect(terminal.canUnlock()).toBe(false);
    expect(terminal.sessionToken()).toBeNull();
  });

  it('answers every refused unlock alike, a lock with the time it lifts, and anything else as an error', async () => {
    await terminal.refresh(SCOPE);

    api.unlock.mockRejectedValue(new TerminalError(403, 'TERMINAL_UNLOCK_REFUSED', null, null));
    expect(await terminal.unlock('m1', '000000')).toEqual({ kind: 'REFUSED' });

    api.unlock.mockRejectedValue(new TerminalError(423, 'TERMINAL_PIN_LOCKED', 840, null));
    expect(await terminal.unlock('m1', '000000')).toEqual({
      kind: 'LOCKED',
      retryAfterSeconds: 840,
    });

    api.unlock.mockRejectedValue(new TerminalError(423, 'TERMINAL_PIN_LOCKED', null, null));
    expect(await terminal.unlock('m1', '000000')).toEqual({
      kind: 'LOCKED',
      retryAfterSeconds: null,
    });

    api.unlock.mockRejectedValue(new TypeError('offline'));
    expect(await terminal.unlock('m1', '000000')).toEqual({ kind: 'ERROR' });
    expect(terminal.unlocked()).toBe(false);
  });

  it('tells a PIN rule from a refused code on activation', async () => {
    await terminal.refresh(SCOPE);

    api.activate.mockRejectedValue(
      new TerminalError(422, 'TERMINAL_ACTIVATION_REFUSED', null, 'SEQUENCE'),
    );
    expect(await terminal.activate('m1', '12345678', '123456')).toEqual({
      kind: 'PIN_RULE',
      reason: 'SEQUENCE',
    });

    api.activate.mockRejectedValue(
      new TerminalError(422, 'TERMINAL_ACTIVATION_REFUSED', null, null),
    );
    expect(await terminal.activate('m1', '12345678', '482915')).toEqual({ kind: 'CODE_REFUSED' });
  });

  it('opens a session when a PIN is set with a code, as an unlock does', async () => {
    await terminal.refresh(SCOPE);

    expect(await terminal.activate('m1', '12345678', '482915')).toEqual({ kind: 'OK' });
    expect(terminal.unlocked()).toBe(true);
  });

  it('changes a PIN only with the live session, sending it as the session and nowhere else', async () => {
    await terminal.refresh(SCOPE);
    expect(await terminal.changePin('482915', '735914')).toEqual({ kind: 'ERROR' });
    expect(api.changePin).not.toHaveBeenCalled();

    await terminal.unlock('m1', '482915');
    expect(await terminal.changePin('482915', '735914')).toEqual({ kind: 'OK' });
    expect(api.changePin).toHaveBeenCalledWith(SCOPE, 'token-1', '482915', '735914');
  });

  it('locks locally first, so a failing call cannot leave the terminal open', async () => {
    await terminal.refresh(SCOPE);
    await terminal.unlock('m1', '482915');
    api.lock.mockRejectedValue(new TypeError('offline'));

    await terminal.lock();

    expect(terminal.unlocked()).toBe(false);
    expect(api.lock).toHaveBeenCalledWith(SCOPE, 'token-1');
  });

  it('locks itself after the idle length, and a tap restarts the clock', async () => {
    vi.useFakeTimers();
    await terminal.refresh(SCOPE);
    await terminal.unlock('m1', '482915');

    await vi.advanceTimersByTimeAsync(100_000);
    terminal.noteActivity();
    await vi.advanceTimersByTimeAsync(100_000);
    expect(terminal.unlocked()).toBe(true);

    await vi.advanceTimersByTimeAsync(21_000);
    expect(terminal.unlocked()).toBe(false);
  });

  it('never outlasts the longest session the server gave', async () => {
    vi.useFakeTimers();
    await terminal.refresh(SCOPE);
    api.unlock.mockResolvedValue(
      opened({ idleSeconds: 3_600, hardExpiresAt: new Date(Date.now() + 30_000).toISOString() }),
    );
    await terminal.unlock('m1', '482915');

    await vi.advanceTimersByTimeAsync(31_000);

    expect(terminal.unlocked()).toBe(false);
  });

  it('tells the server about a tap at most every twenty seconds', async () => {
    vi.useFakeTimers();
    await terminal.refresh(SCOPE);
    await terminal.unlock('m1', '482915');

    terminal.noteActivity();
    terminal.noteActivity();
    expect(api.touch).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(21_000);
    terminal.noteActivity();
    terminal.noteActivity();
    expect(api.touch).toHaveBeenCalledTimes(1);
    expect(api.touch).toHaveBeenCalledWith(SCOPE, 'token-1');
  });

  it('follows the server when it says the session is over', async () => {
    await terminal.refresh(SCOPE);
    await terminal.unlock('m1', '482915');

    terminal.endedByServer();

    expect(terminal.unlocked()).toBe(false);
    expect(terminal.lockRequired()).toBe(true);
  });
});
