import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { Observable, firstValueFrom, isObservable, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { RETURN_TO_KEY, authGuard } from './auth.guard';

/**
 * What can and cannot be proven here.
 *
 * These tests prove the guard's decision and its side effects. They do not prove
 * the PKCE handshake, because that needs a running Keycloak realm and this
 * repository has none — see the note at the top of `auth.providers.ts` for the
 * five things somebody with a realm must check by hand.
 */
describe('authGuard', () => {
  let authorize: ReturnType<typeof vi.fn>;

  function configure(isAuthenticated: boolean): void {
    authorize = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: OidcSecurityService,
          useValue: {
            isAuthenticated$: of({ isAuthenticated, allConfigsAuthenticated: [] }),
            authorize,
          },
        },
      ],
    });
  }

  beforeEach(() => {
    sessionStorage.clear();
  });

  it('admits a signed-in operator without starting a redirect', async () => {
    configure(true);
    await expect(run('/orders/018f')).resolves.toBe(true);
    expect(authorize).not.toHaveBeenCalled();
  });

  it('starts the login redirect when there is no session', async () => {
    configure(false);
    await expect(run('/orders/018f')).resolves.toBe(false);
    expect(authorize).toHaveBeenCalledOnce();
  });

  it('remembers the deep link so a shared order link survives a login', () => {
    // The reason this matters: a supervisor sends a colleague a link to one late
    // order. Without this the colleague lands on Today and has to find it again,
    // which is the moment the link stopped being useful.
    configure(false);
    void run('/orders/018f-late-one');
    expect(sessionStorage.getItem(RETURN_TO_KEY)).toBe('/orders/018f-late-one');
  });

  it('does not return a UrlTree, which would race the document redirect', async () => {
    configure(false);
    const decision = await run('/orders');
    expect(typeof decision).toBe('boolean');
  });
});

function run(url: string): Promise<unknown> {
  const result = TestBed.runInInjectionContext(() =>
    authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
  );
  return isObservable(result)
    ? firstValueFrom(result as Observable<unknown>)
    : Promise.resolve(result);
}
