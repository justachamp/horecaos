import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { TermsOfConditionsComponent } from './terms-of-conditions.component';
import { TermsService, TermsDocument } from '../../services/terms.service';
import { TranslateService } from '../../services/translate.service';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, unknown>): string {
    return params ? `${key}:${JSON.stringify(params)}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

class FakeTermsService {
  current = vi.fn<() => Promise<TermsDocument>>();
  accept = vi.fn<() => Promise<string>>();
}

function setUp(historyState: unknown) {
  window.history.replaceState(historyState, '');

  const terms = new FakeTermsService();
  TestBed.configureTestingModule({
    imports: [TermsOfConditionsComponent],
    providers: [
      provideRouter([]),
      { provide: TermsService, useValue: terms },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const router = TestBed.inject(Router);
  const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(TermsOfConditionsComponent);
  return { fixture, terms, navigateSpy };
}

function document(overrides: Partial<TermsDocument> = {}): TermsDocument {
  return { locale: 'en', isPlatformDefault: false, version: 1, body: 'Some terms text.', ...overrides };
}

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('TermsOfConditionsComponent', () => {
  it('renders the fetched document', async () => {
    const { fixture, terms } = setUp(null);
    terms.current.mockResolvedValue(document({ body: 'Our own words, not a legacy brand.' }));

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Our own words, not a legacy brand.');
  });

  it('shows a notice when serving the platform default', async () => {
    const { fixture, terms } = setUp(null);
    terms.current.mockResolvedValue(document({ isPlatformDefault: true }));

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.componentInstance.isPlatformDefault()).toBe(true);
  });

  it('shows the error state when the read fails', async () => {
    const { fixture, terms } = setUp(null);
    terms.current.mockRejectedValue(new Error('network'));

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('error');
  });

  it('is read-only, with no accept action, when reached without mustAccept state', async () => {
    const { fixture, terms } = setUp(null);
    terms.current.mockResolvedValue(document());

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.componentInstance.mustAccept).toBe(false);
    expect((fixture.nativeElement as HTMLElement).querySelector('button[type="button"].w-full')).toBeNull();
  });

  it('accepting navigates to returnTo, recording acceptance first', async () => {
    const { fixture, terms, navigateSpy } = setUp({ mustAccept: true, returnTo: '/locations' });
    terms.current.mockResolvedValue(document());
    terms.accept.mockResolvedValue('v1:en');

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.componentInstance.mustAccept).toBe(true);

    await fixture.componentInstance.agree();

    expect(terms.accept).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/locations']);
  });

  it('defaults to /locations when no returnTo was carried', async () => {
    const { fixture, terms, navigateSpy } = setUp({ mustAccept: true });
    terms.current.mockResolvedValue(document());
    terms.accept.mockResolvedValue('default-v1:en');

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    await fixture.componentInstance.agree();

    expect(navigateSpy).toHaveBeenCalledWith(['/locations']);
  });
});
