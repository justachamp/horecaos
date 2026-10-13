import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';

import { DineInService } from '../../../services/dine-in.service';
import { TranslateService } from '../../../services/translate.service';
import { DineInScanComponent } from './dine-in-scan.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
  current = (): Record<string, unknown> => ({});
}

class FakeDineInService {
  exchange = vi.fn();
}

function setUp(tableToken: string | null) {
  const dineIn = new FakeDineInService();
  TestBed.configureTestingModule({
    imports: [DineInScanComponent],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            paramMap: { get: (key: string) => (key === 'tableToken' ? tableToken : null) },
          },
        },
      },
      { provide: DineInService, useValue: dineIn },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(DineInScanComponent);
  return { fixture, dineIn, router: TestBed.inject(Router) };
}

/**
 * Gives a path that must *not* do anything asynchronous a couple of turns in which to
 * betray itself. It is only for absence checks: waiting for an effect to land is
 * `vi.waitFor`, never a count of turns.
 */
async function letPendingWorkRun(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('DineInScanComponent', () => {
  // The scan remembers the table as a return destination (ReturnDestination, sessionStorage)
  // for a sign-in that this spec never performs; clear it so no later spec in the same
  // worker inherits it (the storefront runner shares one document across spec files).
  afterEach(() => sessionStorage.clear());

  it('exchanges the printed token and replaces the URL with the token-free table screen', async () => {
    const { fixture, dineIn, router } = setUp('printed-table-token');
    dineIn.exchange.mockResolvedValue({ mode: 'VIEW_ONLY' });
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.detectChanges();
    // The navigation follows the exchange's promise by a runtime-defined number of
    // microtask turns; wait for the call rather than counting turns.
    // replaceUrl: the one-time token must not stay in history or a bookmark.
    await vi.waitFor(() =>
      expect(navigate).toHaveBeenCalledWith(['/dine-in', 'table'], { replaceUrl: true }),
    );

    expect(dineIn.exchange).toHaveBeenCalledWith('printed-table-token');
    expect(fixture.componentInstance.failed()).toBe(false);
  });

  it('shows the one honest failure message when the platform refuses the code', async () => {
    const { fixture, dineIn } = setUp('a-guessed-or-rotated-token');
    dineIn.exchange.mockRejectedValue(new Error('refused'));

    fixture.detectChanges();
    // `failed` is set in a catch handler chained after the exchange's rejection, a
    // runtime-defined number of microtask turns later; wait for it.
    await vi.waitFor(() => expect(fixture.componentInstance.failed()).toBe(true));
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="dine-in-scan-failed"]')).not.toBeNull();
    expect(host.textContent).toContain('dineIn.scanFailedTitle');
    // A way out that is not a dead end.
    expect(host.querySelector('[data-testid="dine-in-scan-home-link"]')).not.toBeNull();
  });

  it('shows a loading message while the exchange is in flight', () => {
    const { fixture, dineIn } = setUp('printed-table-token');
    dineIn.exchange.mockReturnValue(new Promise(() => {}));

    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="dine-in-scan-loading"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="dine-in-scan-failed"]')).toBeNull();
  });

  it('fails immediately with no table token in the route at all', async () => {
    const { fixture, dineIn } = setUp(null);

    fixture.detectChanges();
    await letPendingWorkRun();

    expect(dineIn.exchange).not.toHaveBeenCalled();
    expect(fixture.componentInstance.failed()).toBe(true);
  });
});
