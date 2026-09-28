import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';

import { DineInService } from '../../../services/dine-in.service';
import { TranslateService } from '../../../services/translate.service';
import { DineInScanComponent } from './dine-in-scan.component';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
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
          snapshot: { paramMap: { get: (key: string) => (key === 'tableToken' ? tableToken : null) } },
        },
      },
      { provide: DineInService, useValue: dineIn },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(DineInScanComponent);
  const router = TestBed.inject(Router);
  return { fixture, dineIn, router };
}

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('DineInScanComponent', () => {
  it('exchanges the printed token and replaces the URL with the token-free table screen', async () => {
    const { fixture, dineIn, router } = setUp('printed-table-token');
    dineIn.exchange.mockResolvedValue({ mode: 'ORDER_AND_PAY' });
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.detectChanges();
    await flush();

    expect(dineIn.exchange).toHaveBeenCalledWith('printed-table-token');
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
  });

  it('fails immediately with no table token in the route at all', async () => {
    const { fixture, dineIn } = setUp(null);

    fixture.detectChanges();
    await flush();

    expect(dineIn.exchange).not.toHaveBeenCalled();
    expect(fixture.componentInstance.failed()).toBe(true);
  });
});
