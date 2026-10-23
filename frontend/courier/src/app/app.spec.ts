import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';
import { AppUpdates } from './core/app-updates';
import { AuthService } from './core/auth/auth.service';
import { I18n } from './core/i18n/i18n';
import { OffersStore } from './core/offers/offers.store';
import { DutyStore, type DutyIndicator } from './core/telemetry/duty.store';
import { WorkSession } from './core/work/work-session';

describe('App shell', () => {
  const signedIn = signal(false);
  const ready = signal(false);
  const apply = vi.fn();
  const indicator = signal<DutyIndicator>('off');
  const waiting = signal(0);
  const begin = vi.fn();
  const end = vi.fn();

  async function render() {
    signedIn.set(false);
    ready.set(false);
    apply.mockReset();
    begin.mockReset();
    end.mockReset();
    indicator.set('off');
    waiting.set(0);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { signedIn } },
        { provide: AppUpdates, useValue: { ready, apply } },
        { provide: WorkSession, useValue: { begin, end } },
        { provide: DutyStore, useValue: { indicator } },
        { provide: OffersStore, useValue: { count: waiting } },
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
    expect(tabs).toEqual(['Today', 'Offers', 'Deliveries', 'Shift', 'Profile']);
    expect(root(fixture).querySelector('nav.tabs')?.getAttribute('aria-label')).toBe(
      'Main navigation',
    );
  });

  it('shows how many offers are waiting on the Offers tab, to a screen reader as words', async () => {
    const fixture = await render();
    signedIn.set(true);
    fixture.detectChanges();
    expect(root(fixture).querySelector('nav.tabs .count')).toBeNull();

    waiting.set(2);
    fixture.detectChanges();

    const count = root(fixture).querySelector('nav.tabs a[href="/offers"] .count')!;
    expect(count.textContent).toContain('2');
    expect(root(fixture).querySelector('nav.tabs a[href="/offers"]')?.textContent).toContain(
      '2 waiting',
    );
  });

  it('starts the courier’s working state when they sign in, and stops it when they leave', async () => {
    const fixture = await render();
    expect(begin).not.toHaveBeenCalled();

    signedIn.set(true);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(begin).toHaveBeenCalledOnce();

    signedIn.set(false);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(end).toHaveBeenCalledOnce();
  });

  it('shows the on-duty indicator on every screen while the courier is on duty, and not otherwise', async () => {
    const fixture = await render();
    expect(root(fixture).querySelector('app-duty-indicator a')).toBeNull();

    indicator.set('tracking');
    fixture.detectChanges();

    expect(root(fixture).querySelector('app-duty-indicator a')?.textContent).toContain(
      'On duty · sharing location',
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
