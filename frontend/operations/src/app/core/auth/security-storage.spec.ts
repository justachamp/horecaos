import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { SESSION_STORAGE, SplitSecurityStorage } from './security-storage';

/**
 * The split is the whole point, and both halves of it can fail silently.
 *
 * Put tokens in sessionStorage and nobody notices until a security review. Put
 * the PKCE verifier in memory and every login fails with `invalid_grant`, which
 * reads as a Keycloak misconfiguration and costs a day. These tests pin both.
 */
describe('SplitSecurityStorage', () => {
  const CONFIG_ID = 'qoida-operations';
  let session: Storage;
  let storage: SplitSecurityStorage;

  beforeEach(() => {
    session = new MemoryStorage();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: SESSION_STORAGE, useValue: session }, SplitSecurityStorage],
    });
    storage = TestBed.inject(SplitSecurityStorage);
  });

  it('never writes a token into browser storage', () => {
    storage.write(
      CONFIG_ID,
      JSON.stringify({
        authzData: 'the-access-token',
        authnResult: { access_token: 'the-access-token', refresh_token: 'the-refresh-token' },
        userData: { sub: 'abc', preferred_username: 'dilnoza' },
        codeVerifier: 'the-verifier',
      }),
    );

    const persisted = JSON.stringify(session);
    expect(persisted).not.toContain('the-access-token');
    expect(persisted).not.toContain('the-refresh-token');
    expect(persisted).not.toContain('dilnoza');
  });

  it('keeps the redirect state so the code exchange can complete', () => {
    // Simulates the only thing that matters: the document navigates to Keycloak
    // and comes back, so every in-memory object is gone but sessionStorage is not.
    storage.write(
      CONFIG_ID,
      JSON.stringify({
        codeVerifier: 'the-verifier',
        authStateControl: 'the-state',
        authNonce: 'the-nonce',
        authzData: 'the-access-token',
      }),
    );

    // A fresh instance over the same sessionStorage is what a full-page redirect
    // leaves behind: the closure is gone, the browser store is not.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: SESSION_STORAGE, useValue: session }, SplitSecurityStorage],
    });
    const afterRedirect = TestBed.inject(SplitSecurityStorage);
    const recovered = JSON.parse(afterRedirect.read(CONFIG_ID) ?? '{}') as Record<string, unknown>;

    expect(recovered['codeVerifier']).toBe('the-verifier');
    expect(recovered['authStateControl']).toBe('the-state');
    expect(recovered['authNonce']).toBe('the-nonce');
    // The token did not survive, which is the trade being made.
    expect(recovered['authzData']).toBeUndefined();
  });

  it('merges both halves on read', () => {
    storage.write(CONFIG_ID, JSON.stringify({ codeVerifier: 'v', authzData: 't' }));
    const merged = JSON.parse(storage.read(CONFIG_ID) ?? '{}') as Record<string, unknown>;
    expect(merged).toEqual({ codeVerifier: 'v', authzData: 't' });
  });

  it('returns null when nothing has been written', () => {
    expect(storage.read(CONFIG_ID)).toBeNull();
  });

  it('treats the library’s "null" write as a removal', () => {
    storage.write(CONFIG_ID, JSON.stringify({ codeVerifier: 'v' }));
    storage.write(CONFIG_ID, 'null');
    expect(storage.read(CONFIG_ID)).toBeNull();
    expect(session.length).toBe(0);
  });

  it('clears both halves on logout, leaving no session behind', () => {
    storage.write(CONFIG_ID, JSON.stringify({ codeVerifier: 'v', authzData: 't' }));
    storage.clear();
    expect(storage.read(CONFIG_ID)).toBeNull();
    expect(session.length).toBe(0);
  });

  it('recovers from a corrupt persisted blob instead of wedging every login', () => {
    session.setItem(`qoida.operations.oidc.${CONFIG_ID}`, '{not json');
    expect(storage.read(CONFIG_ID)).toBeNull();
    expect(session.length).toBe(0);
  });
});

/** A Storage implementation, because jsdom's sessionStorage is shared per file. */
class MemoryStorage implements Storage {
  private readonly map = new Map<string, string>();

  get length(): number {
    return this.map.size;
  }

  clear(): void {
    this.map.clear();
  }

  getItem(key: string): string | null {
    return this.map.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.map.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.map.delete(key);
  }

  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }

  /** `JSON.stringify(storage)` must see the values, for the leak assertion above. */
  toJSON(): Record<string, string> {
    return Object.fromEntries(this.map);
  }
}
