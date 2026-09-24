import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';

import { HorecaOSApiError } from '../../core/api/problem-details';
import { ChannelPageComponent } from './channel-page.component';
import { ChannelPage, ChannelPagesService } from '../../services/channel-pages.service';
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

class FakePages {
  page = vi.fn<() => Promise<ChannelPage>>();
}

function page(overrides: Partial<ChannelPage> = {}): ChannelPage {
  return { slug: 'about', locale: 'en', version: 1, body: 'About Tandir House.', ...overrides };
}

function setUp(slug: string | null) {
  const pages = new FakePages();
  TestBed.configureTestingModule({
    imports: [ChannelPageComponent],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: { get: (key: string) => (key === 'slug' ? slug : null) } } },
      },
      { provide: ChannelPagesService, useValue: pages },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(ChannelPageComponent);
  return { fixture, pages };
}

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('ChannelPageComponent', () => {
  it('renders the fetched page in paragraphs', async () => {
    const { fixture, pages } = setUp('about');
    pages.page.mockResolvedValue(page({ body: 'First paragraph.\n\nSecond paragraph.' }));

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(pages.page).toHaveBeenCalledWith('about');
    const paragraphs = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '.channel-page-content p',
    );
    expect(paragraphs.length).toBe(2);
    expect(paragraphs[0].textContent).toContain('First paragraph.');
    expect(paragraphs[1].textContent).toContain('Second paragraph.');
    expect(fixture.componentInstance.state()).toBe('ready');
  });

  it('never renders a paragraph as HTML -- a body containing a tag is shown as literal text', async () => {
    const { fixture, pages } = setUp('about');
    pages.page.mockResolvedValue(page({ body: '<script>alert(1)</script> is not code here.' }));

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('script')).toBeNull();
    expect(host.textContent).toContain('<script>alert(1)</script>');
  });

  it('shows not-found for an unrecognised slug, without ever calling the API', async () => {
    const { fixture, pages } = setUp('not-a-real-page');

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(pages.page).not.toHaveBeenCalled();
    expect(fixture.componentInstance.state()).toBe('not-found');
  });

  it('shows not-found, not the error state, when the channel has never published this page', async () => {
    const { fixture, pages } = setUp('contacts');
    pages.page.mockRejectedValue(
      new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'no such page' }),
    );

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('not-found');
  });

  it('shows the error state on a real failure, distinct from not-found', async () => {
    const { fixture, pages } = setUp('about');
    pages.page.mockRejectedValue(new Error('network'));

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('error');
  });
});
