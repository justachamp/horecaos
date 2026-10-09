import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { AllowedHost, ContentApi } from './content-api';
import { ContentHostsPanel, normalizeHost } from './content-hosts-panel';

const HOSTS: AllowedHost[] = [
  { host: 'shop.example.uz', source: 'ADDED', addedBy: 'anna', addedAt: '2026-10-01T07:00:00Z' },
  { host: 'www.brand.uz', source: 'CHANNEL', addedBy: '', addedAt: null },
];

class FakeApi {
  hosts: AllowedHost[] = [...HOSTS];
  readonly added: { host: string; reason: string | undefined }[] = [];
  readonly removed: string[] = [];
  addFails: (() => Promise<unknown>) | null = null;
  listFails = false;
  allowedHosts(): Promise<readonly AllowedHost[]> {
    return this.listFails
      ? Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null))
      : Promise.resolve(this.hosts);
  }
  addAllowedHost(_s: unknown, host: string, reason?: string): Promise<{ host: string }> {
    this.added.push({ host, reason });
    if (this.addFails) {
      return this.addFails() as Promise<{ host: string }>;
    }
    this.hosts = [...this.hosts, { host, source: 'ADDED', addedBy: 'me', addedAt: null }];
    return Promise.resolve({ host });
  }
  removeAllowedHost(_s: unknown, host: string): Promise<void> {
    this.removed.push(host);
    this.hosts = this.hosts.filter((h) => h.host !== host);
    return Promise.resolve();
  }
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

async function setup(api = new FakeApi()): Promise<{
  fixture: ReturnType<typeof TestBed.createComponent<ContentHostsPanel>>;
  api: FakeApi;
  root: HTMLElement;
  changes: number[];
}> {
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentApi, useValue: api },
      { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentHostsPanel);
  const changes: number[] = [];
  fixture.componentInstance.changed.subscribe(() => changes.push(1));
  fixture.detectChanges();
  await settle();
  fixture.detectChanges();
  return { fixture, api, root: fixture.nativeElement as HTMLElement, changes };
}

function type(root: HTMLElement, id: string, value: string): void {
  const input = root.querySelector<HTMLInputElement>(`[data-testid="${id}"]`)!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

describe('normalizeHost (ADR 0157: a bare lower-case DNS name)', () => {
  it.each([
    ['shop.example.uz', 'shop.example.uz'],
    ['  Shop.Example.UZ  ', 'shop.example.uz'],
    ['a-b.c1.example.co.uk', 'a-b.c1.example.co.uk'],
  ])('takes %s', (typed, host) => {
    expect(normalizeHost(typed)).toBe(host);
  });

  it.each([
    'https://shop.example.uz',
    'shop.example.uz:8443',
    '*.example.uz',
    'example',
    'shop.example.uz/path',
    '-bad.example.uz',
    'bad_host.example.uz',
    'user@example.uz',
    '',
    'a..b.uz',
    '192.168.0.1',
  ])('refuses %s', (typed) => {
    expect(normalizeHost(typed)).toBeNull();
  });
});

describe('ContentHostsPanel (ADR 0157: the open-redirect control)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists what authors added with a way to remove it, and the channel addresses as read-only', async () => {
    const { root } = await setup();
    const rows = Array.from(root.querySelectorAll('[data-testid="content-host-row"]'));

    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('[data-testid="content-host-remove"]')).toBeTruthy();
    expect(rows[0].textContent).toContain('added by anna');
    expect(rows[1].querySelector('[data-testid="content-host-remove"]')).toBeNull();
    expect(rows[1].querySelector('[data-testid="content-host-channel"]')).toBeTruthy();
  });

  it('allows a site once it is a bare host name, lower-cased, with the reason if given', async () => {
    const { fixture, api, root, changes } = await setup();
    type(root, 'content-host-input', 'New.Example.uz');
    type(root, 'content-host-reason', 'campaign page');
    fixture.detectChanges();

    (root.querySelector('[data-testid="content-host-add"]') as HTMLButtonElement).click();
    await settle();
    await settle();
    fixture.detectChanges();

    expect(api.added).toEqual([{ host: 'new.example.uz', reason: 'campaign page' }]);
    expect(root.querySelectorAll('[data-testid="content-host-row"]')).toHaveLength(3);
    expect(
      (root.querySelector('[data-testid="content-host-input"]') as HTMLInputElement).value,
    ).toBe('');
    expect(changes).toHaveLength(1);
  });

  it('will not add anything that is not a bare host name, and says why', async () => {
    const { fixture, api, root } = await setup();

    type(root, 'content-host-input', 'https://shop.example.uz');
    fixture.detectChanges();

    expect(
      (root.querySelector('[data-testid="content-host-add"]') as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(root.querySelector('[data-testid="content-host-invalid"]')).toBeTruthy();
    expect(api.added).toEqual([]);
  });

  it('shows the platform’s refusal of an add', async () => {
    const api = new FakeApi();
    api.addFails = () => Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null));
    const { fixture, root } = await setup(api);
    type(root, 'content-host-input', 'new.example.uz');
    fixture.detectChanges();

    (root.querySelector('[data-testid="content-host-add"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="content-hosts-error"]')?.textContent?.trim()).not.toBe(
      '',
    );
  });

  it('asks before taking a site off the list, then removes it and says the list changed', async () => {
    const { fixture, api, root, changes } = await setup();

    (root.querySelector('[data-testid="content-host-remove"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.textContent).toContain('Stop allowing shop.example.uz?');
    expect(api.removed).toEqual([]);

    (
      fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
    ).confirm.emit();
    await settle();
    await settle();
    fixture.detectChanges();

    expect(api.removed).toEqual(['shop.example.uz']);
    expect(root.querySelectorAll('[data-testid="content-host-row"]')).toHaveLength(1);
    expect(changes).toHaveLength(1);
  });

  it('keeps the site when the author backs out', async () => {
    const { fixture, api, root } = await setup();

    (root.querySelector('[data-testid="content-host-remove"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (
      fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
    ).cancel.emit();
    fixture.detectChanges();

    expect(api.removed).toEqual([]);
    expect(fixture.debugElement.query(By.directive(ConfirmDialog))).toBeNull();
  });

  it('says what is missing, not an empty list, when the principal cannot read', async () => {
    const api = new FakeApi();
    api.listFails = true;
    const { root } = await setup(api);

    expect(root.querySelector('[data-testid="q-denied-state"]')).toBeTruthy();
  });

  it('says there are no sites rather than drawing an empty list', async () => {
    const api = new FakeApi();
    api.hosts = [];
    const { root } = await setup(api);

    expect(root.querySelector('[data-testid="content-hosts-empty"]')).toBeTruthy();
  });
});
