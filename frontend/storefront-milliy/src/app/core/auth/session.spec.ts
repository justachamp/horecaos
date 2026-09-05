import { TestBed } from '@angular/core/testing';

import { Session } from './session';

const TOKEN_KEY = 'horecaos_session_token';
const EXPIRES_AT_KEY = 'horecaos_session_expires_at';

function freshSession(): Session {
  TestBed.configureTestingModule({});
  return TestBed.inject(Session);
}

describe('Session', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe('adopt', () => {
    it('installs the token and reports authenticated', () => {
      const session = freshSession();

      session.adopt({
        accessToken: 'tok-1',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      });

      expect(session.accessToken()).toBe('tok-1');
      expect(session.status()).toBe('AUTHENTICATED');
      expect(session.isAuthenticated()).toBe(true);
    });

    it('persists the token to localStorage', () => {
      const session = freshSession();
      const expiresAt = new Date(Date.now() + 60_000).toISOString();

      session.adopt({ accessToken: 'tok-2', expiresAt });

      expect(localStorage.getItem(TOKEN_KEY)).toBe('tok-2');
      expect(Number(localStorage.getItem(EXPIRES_AT_KEY))).toBe(Date.parse(expiresAt));
    });

    it('clears an in-flight beginSignIn state', () => {
      const session = freshSession();
      session.beginSignIn();
      expect(session.status()).toBe('AUTHENTICATING');

      session.adopt({ accessToken: 'tok-3', expiresAt: new Date(Date.now() + 60_000).toISOString() });

      expect(session.status()).toBe('AUTHENTICATED');
    });

    it('treats a missing expiresAt as already expired', () => {
      const session = freshSession();

      session.adopt({ accessToken: 'tok-4' });

      expect(session.accessToken()).toBeNull();
      expect(session.status()).toBe('ANONYMOUS');
    });

    it('treats an unparseable expiresAt as already expired', () => {
      const session = freshSession();

      session.adopt({ accessToken: 'tok-5', expiresAt: 'not-a-date' });

      expect(session.accessToken()).toBeNull();
    });
  });

  describe('accessToken (self-expiring)', () => {
    it('returns the token before its deadline', () => {
      const session = freshSession();
      session.adopt({ accessToken: 'tok-6', expiresAt: new Date(Date.now() + 10_000).toISOString() });

      expect(session.accessToken()).toBe('tok-6');
    });

    it('reads null on first evaluation when the stored deadline has already passed', () => {
      const session = freshSession();
      const realNow = Date.now;
      try {
        Date.now = () => 1_000_000;
        session.adopt({ accessToken: 'tok-7', expiresAt: new Date(999_000).toISOString() });

        expect(session.accessToken()).toBeNull();
        expect(session.status()).toBe('ANONYMOUS');
        expect(session.isAuthenticated()).toBe(false);
      } finally {
        Date.now = realNow;
      }
    });

    /**
     * `accessToken()` is a plain method, not an Angular `computed()`: it
     * checks the clock on every call rather than memoizing on the `token`/
     * `expiresAtMillis` signals, so a deadline that passes between two reads
     * is caught on the second one even though nothing else touched the
     * session in between. This is the caching artifact the class used to
     * have (and `Session.accessToken`'s own doc comment still promises does
     * not happen) -- this test now asserts the promise holds instead of
     * documenting the gap.
     */
    it('a token already read as live expires at the next read once its deadline has passed, with no adopt/expire/signOut in between', () => {
      const session = freshSession();
      const realNow = Date.now;
      try {
        Date.now = () => 1_000_000;
        session.adopt({ accessToken: 'tok-7b', expiresAt: new Date(1_010_000).toISOString() });
        expect(session.accessToken()).toBe('tok-7b');

        Date.now = () => 1_010_001;
        expect(session.accessToken()).toBeNull();
      } finally {
        Date.now = realNow;
      }
    });

    /**
     * The write side of the self-expiry: a stale read through `accessToken()`
     * also clears the underlying signals, so `status`/`isAuthenticated` --
     * which stay `computed()` and do not themselves poll the clock -- agree
     * on the very next read rather than continuing to report a session that
     * `accessToken()` has already stopped honouring.
     */
    it('a read that catches the deadline also clears status/isAuthenticated for the next read', () => {
      const session = freshSession();
      const realNow = Date.now;
      try {
        Date.now = () => 1_000_000;
        session.adopt({ accessToken: 'tok-7c', expiresAt: new Date(1_010_000).toISOString() });
        expect(session.status()).toBe('AUTHENTICATED');

        Date.now = () => 1_010_001;
        expect(session.accessToken()).toBeNull();
        expect(session.status()).toBe('ANONYMOUS');
        expect(session.isAuthenticated()).toBe(false);
        expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
      } finally {
        Date.now = realNow;
      }
    });

    it('reads null with no token adopted at all', () => {
      const session = freshSession();
      expect(session.accessToken()).toBeNull();
    });
  });

  describe('expire', () => {
    it('drops the bearer and clears storage', () => {
      const session = freshSession();
      session.adopt({ accessToken: 'tok-8', expiresAt: new Date(Date.now() + 60_000).toISOString() });

      session.expire();

      expect(session.accessToken()).toBeNull();
      expect(session.status()).toBe('ANONYMOUS');
      expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
      expect(localStorage.getItem(EXPIRES_AT_KEY)).toBeNull();
    });
  });

  describe('signOut', () => {
    it('drops the bearer and clears storage, same as expire', () => {
      const session = freshSession();
      session.adopt({ accessToken: 'tok-9', expiresAt: new Date(Date.now() + 60_000).toISOString() });

      session.signOut();

      expect(session.accessToken()).toBeNull();
      expect(session.status()).toBe('ANONYMOUS');
      expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    });

    it('also clears an in-flight authenticating state', () => {
      const session = freshSession();
      session.beginSignIn();

      session.signOut();

      expect(session.status()).toBe('ANONYMOUS');
    });
  });

  describe('restore (constructor)', () => {
    it('picks up a valid token stored by a previous run', () => {
      const expiresAt = Date.now() + 60_000;
      localStorage.setItem(TOKEN_KEY, 'stored-tok');
      localStorage.setItem(EXPIRES_AT_KEY, String(expiresAt));

      const session = freshSession();

      expect(session.accessToken()).toBe('stored-tok');
      expect(session.status()).toBe('AUTHENTICATED');
    });

    it('drops a stored token whose deadline has already passed', () => {
      localStorage.setItem(TOKEN_KEY, 'stale-tok');
      localStorage.setItem(EXPIRES_AT_KEY, String(Date.now() - 1_000));

      const session = freshSession();

      expect(session.accessToken()).toBeNull();
      expect(session.status()).toBe('ANONYMOUS');
      // Dropped from storage too, not just from the in-memory signal.
      expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    });

    it('starts anonymous when nothing was stored', () => {
      const session = freshSession();
      expect(session.status()).toBe('ANONYMOUS');
    });

    it('starts anonymous when the stored deadline is not a finite number', () => {
      localStorage.setItem(TOKEN_KEY, 'garbage-deadline-tok');
      localStorage.setItem(EXPIRES_AT_KEY, 'not-a-number');

      const session = freshSession();

      expect(session.accessToken()).toBeNull();
    });
  });

  describe('localStorage throwing (private window / disabled site data)', () => {
    it('adopt still updates in-memory state when localStorage.setItem throws', () => {
      const session = freshSession();
      const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('SecurityError: storage disabled');
      });
      try {
        session.adopt({
          accessToken: 'tok-no-storage',
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
        });
        expect(session.accessToken()).toBe('tok-no-storage');
      } finally {
        spy.mockRestore();
      }
    });

    it('clear (via signOut) still updates in-memory state when localStorage.removeItem throws', () => {
      const session = freshSession();
      session.adopt({
        accessToken: 'tok-x',
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      });
      const spy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
        throw new Error('SecurityError: storage disabled');
      });
      try {
        session.signOut();
        expect(session.accessToken()).toBeNull();
        expect(session.status()).toBe('ANONYMOUS');
      } finally {
        spy.mockRestore();
      }
    });

    it('restore falls back to anonymous when localStorage.getItem throws', () => {
      const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('SecurityError: storage disabled');
      });
      try {
        const session = freshSession();
        expect(session.status()).toBe('ANONYMOUS');
        expect(session.accessToken()).toBeNull();
      } finally {
        spy.mockRestore();
      }
    });
  });
});
