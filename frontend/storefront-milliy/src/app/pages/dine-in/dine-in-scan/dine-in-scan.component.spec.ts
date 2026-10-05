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

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('DineInScanComponent', () => {
  it('exchanges the printed token and replaces the URL with the token-free table screen', async () => {
    const { fixture, dineIn, router } = setUp('printed-table-token');
    dineIn.exchange.mockResolvedValue({ mode: 'VIEW_ONLY' });
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.detectChanges();
    await flush();

    expect(dineIn.exchange).toHaveBeenCalledWith('printed-table-token');
    // replaceUrl: the one-time token must not stay in history or a bookmark.
    expect(navigate).toHaveBeenCalledWith(['/dine-in', 'table'], { replaceUrl: true });
    expect(fixture.componentInstance.failed()).toBe(false);
  });

  it('shows the one honest failure message when the platform refuses the code', async () => {
    const { fixture, dineIn } = setUp('a-guessed-or-rotated-token');
    dineIn.exchange.mockRejectedValue(new Error('refused'));

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.componentInstance.failed()).toBe(true);
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
    await flush();

    expect(dineIn.exchange).not.toHaveBeenCalled();
    expect(fixture.componentInstance.failed()).toBe(true);
  });
});
