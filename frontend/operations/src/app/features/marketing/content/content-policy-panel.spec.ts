import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ContentApi, ContentPolicies } from './content-api';
import { ContentPolicyPanel } from './content-policy-panel';

const POLICIES: ContentPolicies = {
  publishing: { requireAllBrandLocales: false },
  publishingVersion: 2,
  editorial: { news: false, gallery: false, recipes: false, vacancies: false },
  editorialVersion: 5,
  layout: 'STANDARD',
  layoutVersion: 0,
  editorialEntitled: true,
  videoEntitled: false,
};

class FakeApi {
  current: ContentPolicies = POLICIES;
  readonly publishing: { version: number; required: boolean }[] = [];
  readonly editorial: { version: number; kinds: ContentPolicies['editorial'] }[] = [];
  readFails = false;
  writeFails: (() => Promise<ContentPolicies>) | null = null;

  policies(): Promise<ContentPolicies> {
    return this.readFails
      ? Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null))
      : Promise.resolve(this.current);
  }
  setPublishingPolicy(_s: unknown, version: number, required: boolean): Promise<ContentPolicies> {
    this.publishing.push({ version, required });
    if (this.writeFails) {
      return this.writeFails();
    }
    this.current = {
      ...this.current,
      publishing: { requireAllBrandLocales: required },
      publishingVersion: version + 1,
    };
    return Promise.resolve(this.current);
  }
  setEditorialPolicy(
    _s: unknown,
    version: number,
    kinds: ContentPolicies['editorial'],
  ): Promise<ContentPolicies> {
    this.editorial.push({ version, kinds });
    if (this.writeFails) {
      return this.writeFails();
    }
    this.current = { ...this.current, editorial: kinds, editorialVersion: version + 1 };
    return Promise.resolve(this.current);
  }
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

async function setup(api = new FakeApi()): Promise<{
  fixture: ReturnType<typeof TestBed.createComponent<ContentPolicyPanel>>;
  api: FakeApi;
  root: HTMLElement;
  changes: ContentPolicies[];
}> {
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentApi, useValue: api },
      { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentPolicyPanel);
  const changes: ContentPolicies[] = [];
  fixture.componentInstance.policiesChanged.subscribe((p) => changes.push(p));
  fixture.detectChanges();
  await settle();
  fixture.detectChanges();
  return { fixture, api, root: fixture.nativeElement as HTMLElement, changes };
}

const box = (root: HTMLElement, id: string): HTMLInputElement =>
  root.querySelector<HTMLInputElement>(`[data-testid="${id}"]`)!;

function toggle(root: HTMLElement, id: string, on: boolean): void {
  const input = box(root, id);
  input.checked = on;
  input.dispatchEvent(new Event('change'));
}

describe('ContentPolicyPanel (ADR 0157: content.publishing and content.editorial)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('tells the page what the plan allows as soon as it has read it', async () => {
    const { changes } = await setup();

    expect(changes).toEqual([POLICIES]);
  });

  it('saves “every language is required” at the policy version it read', async () => {
    const { fixture, api, root, changes } = await setup();

    toggle(root, 'content-policy-require-all', true);
    await settle();
    fixture.detectChanges();

    expect(api.publishing).toEqual([{ version: 2, required: true }]);
    expect(root.querySelector('[data-testid="content-policies-saved"]')).toBeTruthy();
    expect(box(root, 'content-policy-require-all').checked).toBe(true);
    expect(changes.at(-1)?.publishing.requireAllBrandLocales).toBe(true);
  });

  it('switches one editorial kind on without disturbing the others', async () => {
    const api = new FakeApi();
    api.current = {
      ...POLICIES,
      editorial: { news: true, gallery: false, recipes: false, vacancies: false },
    };
    const { root } = await setup(api);

    toggle(root, 'content-policy-recipes', true);
    await settle();

    expect(api.editorial).toEqual([
      { version: 5, kinds: { news: true, gallery: false, recipes: true, vacancies: false } },
    ]);
  });

  it('shows the editorial kinds as switched off, with the reason, when the plan does not include them', async () => {
    const api = new FakeApi();
    api.current = { ...POLICIES, editorialEntitled: false };
    const { root } = await setup(api);

    expect(root.querySelector('[data-testid="content-policy-not-entitled"]')).toBeTruthy();
    for (const kind of ['news', 'gallery', 'recipes', 'vacancies']) {
      expect(box(root, `content-policy-${kind}`).closest('fieldset')?.disabled).toBe(true);
    }
  });

  it('states whether video is part of the plan, as a fact and not a control', async () => {
    const off = await setup();
    expect(off.root.querySelector('[data-testid="content-policy-video"]')?.textContent).toContain(
      'not part of your plan',
    );

    TestBed.resetTestingModule();
    const api = new FakeApi();
    api.current = { ...POLICIES, videoEntitled: true };
    const on = await setup(api);
    expect(on.root.querySelector('[data-testid="content-policy-video"]')?.textContent).toContain(
      'allows MP4 video',
    );
    expect(on.root.querySelector('[data-testid="content-policy-video"] input')).toBeNull();
  });

  it('puts the switch back and says why when a save is refused', async () => {
    const api = new FakeApi();
    api.writeFails = () =>
      Promise.reject(
        new ApiError('STALE_VERSION', 412, { status: 412, expected: 2, actual: 3 }, null),
      );
    const { fixture, root } = await setup(api);

    toggle(root, 'content-policy-require-all', true);
    await settle();
    await settle();
    fixture.detectChanges();

    expect(box(root, 'content-policy-require-all').checked).toBe(false);
    expect(
      root.querySelector('[data-testid="content-policies-error"]')?.textContent?.trim(),
    ).not.toBe('');
    expect(root.querySelector('[data-testid="content-policies-saved"]')).toBeNull();
  });

  it('says what is missing when the principal cannot read the policies', async () => {
    const api = new FakeApi();
    api.readFails = true;
    const { root } = await setup(api);

    expect(root.querySelector('[data-testid="q-denied-state"]')).toBeTruthy();
  });
});
