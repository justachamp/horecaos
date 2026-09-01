import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService, AuthStatus } from '../core/auth/auth.service';
import { Capability } from '../core/auth/capability';
import { SessionContextService } from '../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../core/config/app-config';
import { I18nService } from '../core/i18n/i18n.service';
import { ConsoleShell } from './console-shell';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

class FakeAuth {
  readonly state = signal<AuthStatus>('signed-in');
  readonly status = this.state.asReadonly();
  readonly displayName = signal<string | null>('Aziza Karimova');
  readonly signOut = vi.fn();
}

class FakeSession {
  readonly held = signal<ReadonlySet<string>>(new Set());

  has(capability: Capability): boolean {
    return this.held().has(capability);
  }
}

describe('ConsoleShell', () => {
  let fixture: ComponentFixture<ConsoleShell>;
  let auth: FakeAuth;
  let session: FakeSession;

  beforeEach(async () => {
    auth = new FakeAuth();
    session = new FakeSession();

    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [ConsoleShell],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AuthService, useValue: auth },
        { provide: SessionContextService, useValue: session },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ConsoleShell);
    fixture.detectChanges();
  });

  function railLabels(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.rail-link')).map((link) =>
      (link as HTMLElement).textContent!.trim(),
    );
  }

  it('shows the sections that need no capability', () => {
    // Overview needs none, so it is there before any context has loaded.
    expect(railLabels()).toEqual(['Обзор']);
  });

  it('adds a section once its capability is held', () => {
    session.held.set(new Set<string>(['TENANT_READ']));
    fixture.detectChanges();
    expect(railLabels()).toEqual(['Обзор', 'Клиенты']);
  });

  it('never renders a section the console has no screen for', () => {
    // Onboarding is declared in SECTIONS with its capability but has no route.
    session.held.set(
      new Set<string>(['TENANT_READ', 'TENANT_ONBOARDING_MANAGE', 'PLATFORM_ADMIN']),
    );
    fixture.detectChanges();
    expect(railLabels()).not.toContain('Подключение');
  });

  it('names the operator in the rail', () => {
    expect(fixture.nativeElement.querySelector('.rail-name').textContent.trim()).toBe(
      'Aziza Karimova',
    );
  });

  it('falls back to a placeholder rather than an empty line when there is no name', () => {
    auth.displayName.set(null);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.rail-name').textContent.trim()).toBe(
      'Неизвестный пользователь',
    );
  });

  it('signs out through Keycloak, not just locally', () => {
    fixture.nativeElement.querySelector('.rail-action').click();
    expect(auth.signOut).toHaveBeenCalledOnce();
  });

  it('re-renders in the chosen language without a reload', () => {
    TestBed.inject(I18nService).use('en');
    fixture.detectChanges();
    expect(railLabels()).toEqual(['Overview']);
    expect(document.documentElement.lang).toBe('en');
  });

  it('renders the date in the console timezone, not the browser one', () => {
    // 20:00 UTC on New Year's Eve is already 01:00 on the 1st in Tashkent
    // (UTC+5). A console that printed 31.12.2026 here would put a day's
    // takings in the wrong year.
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(new Date('2026-12-31T20:00:00Z'));
      const shell = TestBed.createComponent(ConsoleShell);
      shell.detectChanges();
      expect(shell.nativeElement.querySelector('.topbar-right').textContent).toContain(
        '01.01.2027',
      );
    } finally {
      vi.useRealTimers();
    }
  });
});
