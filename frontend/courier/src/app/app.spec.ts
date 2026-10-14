import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';
import { AppUpdates } from './core/app-updates';
import { AuthService } from './core/auth/auth.service';
import { I18n } from './core/i18n/i18n';

describe('App shell', () => {
  const signedIn = signal(false);
  const ready = signal(false);
  const apply = vi.fn();

  async function render() {
    signedIn.set(false);
    ready.set(false);
    apply.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { signedIn } },
        { provide: AppUpdates, useValue: { ready, apply } },
      ],
    });
    await TestBed.inject(I18n).use('en');
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return fixture;
  }

  const root = (fixture: Awaited<ReturnType<typeof render>>) =>
    fixture.nativeElement as HTMLElement;

  it('has a skip link, a banner and a main landmark a screen reader can jump to', async () => {
    const fixture = await render();

    expect(root(fixture).querySelector('a.skip-link')?.getAttribute('href')).toBe('#main');
    expect(root(fixture).querySelector('header')).not.toBeNull();
    expect(root(fixture).querySelector('main#main')).not.toBeNull();
  });

  it('shows the bottom navigation only to a signed-in courier', async () => {
    const fixture = await render();
    expect(root(fixture).querySelector('nav.tabs')).toBeNull();

    signedIn.set(true);
    fixture.detectChanges();

    const tabs = [...root(fixture).querySelectorAll('nav.tabs a')].map((a) =>
      a.textContent!.trim(),
    );
    expect(tabs).toEqual(['Today', 'Profile']);
    expect(root(fixture).querySelector('nav.tabs')?.getAttribute('aria-label')).toBe(
      'Main navigation',
    );
  });

  it('switches language from the top bar and says so on the document', async () => {
    const fixture = await render();
    const select = root(fixture).querySelector('select')!;

    select.value = 'uz-Latn';
    select.dispatchEvent(new Event('change'));

    await vi.waitFor(() => expect(TestBed.inject(I18n).locale()).toBe('uz-Latn'));
    fixture.detectChanges();
    expect(document.documentElement.lang).toBe('uz-Latn');
    expect(root(fixture).querySelector('.skip-link')?.textContent).toContain('Asosiy qismga');
  });

  it('lists the three languages by their own names', async () => {
    const fixture = await render();

    const options = [...root(fixture).querySelectorAll('option')].map((o) => o.textContent!.trim());
    expect(options).toEqual(['Русский', 'Oʻzbekcha', 'English']);
  });

  it("offers a new build without applying it behind the courier's back", async () => {
    const fixture = await render();
    expect(root(fixture).querySelector('.update')).toBeNull();

    ready.set(true);
    fixture.detectChanges();

    expect(root(fixture).querySelector('.update[role="status"]')?.textContent).toContain(
      'new version',
    );
    expect(apply).not.toHaveBeenCalled();
    root(fixture).querySelector<HTMLButtonElement>('.update button')!.click();
    expect(apply).toHaveBeenCalledOnce();
  });
});
