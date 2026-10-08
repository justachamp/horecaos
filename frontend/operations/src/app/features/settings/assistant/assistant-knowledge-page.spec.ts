import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { SavedTarget } from '../settings-saved';
import { SettingsScope } from '../settings-scope';
import { AssistantKnowledgeApi, KnowledgeEntry, KnowledgeVersion } from './assistant-knowledge-api';
import { AssistantKnowledgePage } from './assistant-knowledge-page';

const TENANT_ID = 'tenant-1';
const BRAND_ID = 'brand-1';
const CHILANZAR = 'location-1';

function entry(overrides: Partial<KnowledgeEntry> = {}): KnowledgeEntry {
  return {
    id: 'entry-1',
    scope: 'BRAND',
    brandId: BRAND_ID,
    locationId: null,
    locale: 'en',
    version: 2,
    status: 'PUBLISHED',
    questionForm: 'Is there parking at your restaurant?',
    answerBody: 'Yes, free parking behind the building.',
    authoredBy: 'owner-1',
    publishedAt: '2026-10-05T09:00:00Z',
    ...overrides,
  };
}

const HISTORY: readonly KnowledgeVersion[] = [
  {
    version: 2,
    status: 'PUBLISHED',
    questionForm: 'Is there parking at your restaurant?',
    answerBody: 'Yes, free parking behind the building.',
    authoredBy: 'owner-1',
    reason: 'Counted the spaces',
    publishedAt: '2026-10-05T09:00:00Z',
  },
  {
    version: 1,
    status: 'PUBLISHED',
    questionForm: 'Is there parking at your restaurant?',
    answerBody: 'Parking is on the street.',
    authoredBy: 'owner-1',
    reason: 'First draft',
    publishedAt: '2026-10-01T09:00:00Z',
  },
];

class FakeSettingsScope {
  readonly brandId = signal<string | null>(BRAND_ID);
  readonly locationId = signal<string | null>(null);
  readonly level = signal<'TENANT' | 'BRAND' | 'LOCATION'>('BRAND');
  readonly denied = signal(false);
  readonly locations = signal([{ id: CHILANZAR, displayName: 'Chilanzar' }]);
  readonly targetFor = vi.fn((level: 'TENANT' | 'BRAND' | 'LOCATION'): SavedTarget =>
    level === 'TENANT' ? { level, name: null } : { level, name: 'Rayhon' },
  );
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

interface Harness {
  readonly fixture: ComponentFixture<AssistantKnowledgePage>;
  readonly api: Record<
    'list' | 'versions' | 'create' | 'publish' | 'retire',
    ReturnType<typeof vi.fn>
  >;
  readonly scope: FakeSettingsScope;
  readonly host: HTMLElement;
}

async function render(
  options: {
    entries?: readonly KnowledgeEntry[];
    level?: 'TENANT' | 'BRAND' | 'LOCATION';
    locationId?: string | null;
    listError?: Error;
    writeError?: Error;
  } = {},
): Promise<Harness> {
  const scope = new FakeSettingsScope();
  scope.level.set(options.level ?? 'BRAND');
  scope.locationId.set(options.locationId ?? null);
  const written = vi
    .fn()
    .mockImplementation(() =>
      options.writeError ? Promise.reject(options.writeError) : Promise.resolve(entry()),
    );
  const api = {
    list: vi
      .fn()
      .mockImplementation(() =>
        options.listError
          ? Promise.reject(options.listError)
          : Promise.resolve(options.entries ?? [entry()]),
      ),
    versions: vi.fn().mockResolvedValue(HISTORY),
    create: written,
    publish: vi.fn().mockImplementation(written),
    retire: vi.fn().mockImplementation(written),
  };
  await TestBed.configureTestingModule({
    imports: [AssistantKnowledgePage],
    providers: [
      provideRouter([]),
      { provide: AssistantKnowledgeApi, useValue: api },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      { provide: SettingsScope, useValue: scope },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(AssistantKnowledgePage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api, scope, host: fixture.nativeElement as HTMLElement };
}

function byId(host: HTMLElement, testId: string): HTMLElement {
  return host.querySelector(`[data-testid="${testId}"]`) as HTMLElement;
}

function type(host: HTMLElement, testId: string, value: string): void {
  const element = byId(host, testId) as HTMLInputElement | HTMLTextAreaElement;
  element.value = value;
  element.dispatchEvent(new Event('input'));
}

function saveButton(host: HTMLElement): HTMLButtonElement {
  return byId(host, 'note-save') as HTMLButtonElement;
}

describe('AssistantKnowledgePage: reading', () => {
  it('lists a brand’s notes through the brand route', async () => {
    const { api, host } = await render();

    expect(api.list).toHaveBeenCalledWith(TENANT_ID, BRAND_ID);
    expect(byId(host, 'note-entry-question').textContent).toContain('Is there parking');
    expect(byId(host, 'note-entry-answer').textContent).toContain('free parking');
    expect(host.textContent).toContain('version 2');
    expect(byId(host, 'assistant-notes-level-hint').textContent).toContain('this brand’s notes');
  });

  it('lists the company-wide notes through the company route when the bar is at company level', async () => {
    const { api, host } = await render({ level: 'TENANT' });

    expect(api.list).toHaveBeenCalledWith(TENANT_ID, null);
    expect(byId(host, 'assistant-notes-level-hint').textContent).toContain('whole company');
  });

  it('names where a note applies: the company, the brand, or a branch by its name', async () => {
    const { host } = await render({
      entries: [
        entry({ id: 'a', scope: 'TENANT', brandId: null }),
        entry({ id: 'b', scope: 'BRAND' }),
        entry({ id: 'c', scope: 'LOCATION', locationId: CHILANZAR }),
        entry({ id: 'd', scope: 'LOCATION', locationId: 'gone' }),
      ],
    });

    const text = host.textContent ?? '';
    expect(text).toContain('Whole company');
    expect(text).toContain('Whole brand');
    expect(text).toContain('Branch “Chilanzar”');
    expect(text).toContain('One branch');
  });

  it('marks a retired note as retired and offers only its history', async () => {
    const { host } = await render({ entries: [entry({ status: 'RETIRED' })] });

    expect(host.textContent).toContain('Retired');
    expect(host.querySelector('[data-testid="note-revise"]')).toBeNull();
    expect(host.querySelector('[data-testid="note-retire"]')).toBeNull();
    expect(host.querySelector('[data-testid="note-history"]')).not.toBeNull();
  });

  it('says there are none yet, and what the assistant can do without them', async () => {
    const { host } = await render({ entries: [] });

    expect(byId(host, 'notes-empty').textContent).toContain('menu, branches and hours');
  });

  it('says so when the notes cannot be read', async () => {
    const { host } = await render({
      listError: new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    });

    expect(host.querySelector('[data-testid="notes-denied"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="note-new"]')).toBeNull();
  });
});

describe('AssistantKnowledgePage: a new note', () => {
  async function fill(harness: Harness, question: string, answer: string, reason: string) {
    byId(harness.host, 'note-new').click();
    harness.fixture.detectChanges();
    type(harness.host, 'note-question', question);
    type(harness.host, 'note-answer', answer);
    type(harness.host, 'note-reason', reason);
    harness.fixture.detectChanges();
  }

  it('creates a brand note in the chosen language, with a reason, for the whole brand by default', async () => {
    const harness = await render();
    await fill(harness, '  Is there a kids menu?  ', ' Yes, ask for it. ', 'Asked every week');
    const locale = byId(harness.host, 'note-locale') as HTMLSelectElement;
    locale.value = 'uz';
    locale.dispatchEvent(new Event('change'));
    harness.fixture.detectChanges();

    saveButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.api.create).toHaveBeenCalledWith(TENANT_ID, BRAND_ID, {
      locationId: null,
      locale: 'uz',
      questionForm: 'Is there a kids menu?',
      answerBody: 'Yes, ask for it.',
      reason: 'Asked every week',
    });
  });

  it('defaults the branch to the one the bar is on, and can name another', async () => {
    const harness = await render({ locationId: CHILANZAR, level: 'LOCATION' });
    byId(harness.host, 'note-new').click();
    harness.fixture.detectChanges();

    expect((byId(harness.host, 'note-location') as HTMLSelectElement).value).toBe(CHILANZAR);
  });

  it('writes a company-wide note with no branch field at all when the bar is at company level', async () => {
    const harness = await render({ level: 'TENANT' });
    await fill(harness, 'Do you do catering?', 'Yes, call us.', 'New service');

    expect(harness.host.querySelector('[data-testid="note-location"]')).toBeNull();
    saveButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.api.create).toHaveBeenCalledWith(TENANT_ID, null, {
      locale: 'ru',
      questionForm: 'Do you do catering?',
      answerBody: 'Yes, call us.',
      reason: 'New service',
    });
  });

  it('refuses to save an incomplete or over-long note before asking the server', async () => {
    const harness = await render();
    await fill(harness, 'ab', 'Yes.', 'Because');
    expect(saveButton(harness.host).disabled).toBe(true);

    type(harness.host, 'note-question', 'Is there parking?');
    type(harness.host, 'note-answer', 'x'.repeat(2_001));
    harness.fixture.detectChanges();
    expect(saveButton(harness.host).disabled).toBe(true);

    type(harness.host, 'note-answer', 'x'.repeat(2_000));
    type(harness.host, 'note-reason', '   ');
    harness.fixture.detectChanges();
    expect(saveButton(harness.host).disabled).toBe(true);

    type(harness.host, 'note-reason', 'Because');
    harness.fixture.detectChanges();
    expect(saveButton(harness.host).disabled).toBe(false);
  });

  it('keeps the form and says why when the server refuses', async () => {
    const harness = await render({
      writeError: new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    });
    await fill(harness, 'Is there parking?', 'Yes.', 'Because');

    saveButton(harness.host).click();
    await flushMicrotasks();
    harness.fixture.detectChanges();

    expect(harness.host.querySelector('[data-testid="note-save-error"]')).not.toBeNull();
    expect(harness.host.querySelector('[data-testid="note-create-form"]')).not.toBeNull();
  });

  it('closes the form and re-reads the list after a note is published', async () => {
    const harness = await render();
    await fill(harness, 'Is there parking?', 'Yes.', 'Because');

    saveButton(harness.host).click();
    await flushMicrotasks();
    harness.fixture.detectChanges();

    expect(harness.api.list).toHaveBeenCalledTimes(2);
    expect(harness.host.querySelector('[data-testid="note-create-form"]')).toBeNull();
  });
});

describe('AssistantKnowledgePage: a note’s later life', () => {
  it('publishes a new version with the version it read as If-Match, the words pre-filled', async () => {
    const harness = await render({ entries: [entry({ version: 5 })] });

    byId(harness.host, 'note-revise').click();
    harness.fixture.detectChanges();
    expect((byId(harness.host, 'note-question') as HTMLInputElement).value).toBe(
      'Is there parking at your restaurant?',
    );
    type(harness.host, 'note-answer', 'Yes, 30 spaces.');
    type(harness.host, 'note-reason', 'Counted them');
    harness.fixture.detectChanges();
    saveButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.api.publish).toHaveBeenCalledWith(TENANT_ID, BRAND_ID, 'entry-1', 5, {
      questionForm: 'Is there parking at your restaurant?',
      answerBody: 'Yes, 30 spaces.',
      reason: 'Counted them',
    });
  });

  it('retires a note with a reason and the version it read, and says what retiring does', async () => {
    const harness = await render({ entries: [entry({ version: 3 })] });

    byId(harness.host, 'note-retire').click();
    harness.fixture.detectChanges();
    expect(byId(harness.host, 'note-retire-form').textContent).toContain(
      'stops using this note at once',
    );
    expect(harness.host.querySelector('[data-testid="note-question"]')).toBeNull();
    type(harness.host, 'note-reason', 'Closed the lot');
    harness.fixture.detectChanges();
    saveButton(harness.host).click();
    await flushMicrotasks();

    expect(harness.api.retire).toHaveBeenCalledWith(
      TENANT_ID,
      BRAND_ID,
      'entry-1',
      3,
      'Closed the lot',
    );
  });

  it('shows every version with who, when and why, the earlier words untouched', async () => {
    const harness = await render();

    byId(harness.host, 'note-history').click();
    await flushMicrotasks();
    harness.fixture.detectChanges();

    expect(harness.api.versions).toHaveBeenCalledWith(TENANT_ID, BRAND_ID, 'entry-1');
    const versions = harness.host.querySelectorAll('[data-testid="note-version"]');
    expect(versions.length).toBe(2);
    expect(versions[1].textContent).toContain('Parking is on the street.');
    expect(versions[1].textContent).toContain('First draft');
    expect(versions[0].textContent).toContain('Version 2');
  });

  it('closes the history on a second click', async () => {
    const harness = await render();
    byId(harness.host, 'note-history').click();
    await flushMicrotasks();
    harness.fixture.detectChanges();

    byId(harness.host, 'note-history').click();
    harness.fixture.detectChanges();

    expect(harness.host.querySelector('[data-testid="note-history-list"]')).toBeNull();
    expect(harness.api.versions).toHaveBeenCalledTimes(1);
  });

  it('never has two forms open at once', async () => {
    const harness = await render();

    byId(harness.host, 'note-revise').click();
    harness.fixture.detectChanges();
    byId(harness.host, 'note-new').click();
    harness.fixture.detectChanges();

    expect(harness.host.querySelector('[data-testid="note-revise-form"]')).toBeNull();
    expect(harness.host.querySelector('[data-testid="note-create-form"]')).not.toBeNull();
  });
});
