import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { DateRangePicker } from '../../../shared/ui/date-range-picker';
import { LocalizedFieldGroup } from '../../../shared/ui/localized-field-group';
import {
  ContentApi,
  ContentCreateRequest,
  ContentDraftBody,
  ContentItemView,
  ContentKind,
  ContentPlacement,
} from './content-api';
import { ContentEditor } from './content-editor';
import { ContentHistory } from './content-history';
import { emptyDocument, withText } from './content-document';
import { ContentMedia } from './content-media';
import { ContentReferences } from './content-references';
import { addDays, endInstant, startInstant, today } from './content-schedule';

const SCOPE = { tenantId: 't', brandId: 'b' };

function view(overrides: Partial<ContentItemView> = {}): ContentItemView {
  const kind = overrides.kind ?? 'BANNER';
  return {
    id: 'item-1',
    kind,
    placement: 'HOME_CAROUSEL',
    internalName: 'Spring sale',
    displayTitle: 'Весенняя скидка',
    slug: null,
    state: 'DRAFT',
    visibility: null,
    sortKey: 1,
    version: 3,
    createdBy: 'anna',
    createdAt: '2026-10-01T07:00:00Z',
    updatedAt: '2026-10-01T07:00:00Z',
    liveRevision: null,
    draft: {
      document: withText(emptyDocument(kind), 'ru', 'title', 'Весенняя скидка'),
      channelCodes: [],
      startsAt: null,
      endsAt: null,
      promotionId: null,
      locationId: null,
      basedOnRevisionNo: null,
      updatedBy: 'anna',
      updatedAt: '2026-10-01T07:00:00Z',
    },
    hasUnpublishedChanges: false,
    problems: [],
    ...overrides,
  };
}

class FakeApi {
  readonly calls: { name: string; args: unknown[] }[] = [];
  next: () => Promise<ContentItemView> = () => Promise.resolve(view({ version: 4 }));

  private record(name: string, ...args: unknown[]): Promise<ContentItemView> {
    this.calls.push({ name, args });
    return this.next();
  }
  create(_s: unknown, request: ContentCreateRequest): Promise<ContentItemView> {
    return this.record('create', request);
  }
  replaceDraft(
    _s: unknown,
    id: string,
    version: number,
    name: string,
    body: ContentDraftBody,
  ): Promise<ContentItemView> {
    return this.record('replaceDraft', id, version, name, body);
  }
  publish(_s: unknown, id: string, version: number): Promise<ContentItemView> {
    return this.record('publish', id, version);
  }
  withdraw(_s: unknown, id: string, version: number): Promise<ContentItemView> {
    return this.record('withdraw', id, version);
  }
  archive(_s: unknown, id: string, version: number): Promise<ContentItemView> {
    return this.record('archive', id, version);
  }
  discardDraft(_s: unknown, id: string, version: number): Promise<ContentItemView> {
    return this.record('discardDraft', id, version);
  }
  revisions(): Promise<[]> {
    return Promise.resolve([]);
  }
  stats(): Promise<{ clientReported: boolean; from: string; to: string; rows: [] }> {
    return Promise.resolve({
      clientReported: true,
      from: '2026-09-10',
      to: '2026-10-09',
      rows: [],
    });
  }
  preview(): Promise<{ locale: string; carousel: []; groups: [] }> {
    return Promise.resolve({ locale: 'ru', carousel: [], groups: [] });
  }
}

interface Options {
  item?: ContentItemView | null;
  newKind?: ContentKind | null;
  newPlacement?: ContentPlacement | null;
  channels?: { code: string; displayName: string }[];
}

async function setup(options: Options = {}): Promise<{
  fixture: ReturnType<typeof TestBed.createComponent<ContentEditor>>;
  api: FakeApi;
  root: HTMLElement;
  saved: ContentItemView[];
  dirty: boolean[];
  reloads: number[];
}> {
  const api = new FakeApi();
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentApi, useValue: api },
      { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
      {
        provide: LocaleSet,
        useValue: {
          locales: signal(['ru', 'uz-Latn', 'en']),
          defaultLocale: signal('ru'),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      {
        provide: ContentReferences,
        useValue: {
          contentChannels: () =>
            options.channels ?? [
              { code: 'web', displayName: 'Web' },
              { code: 'tg', displayName: 'Telegram' },
            ],
          locations: () => [{ id: 'loc-1', label: 'Chilonzor' }],
          products: () => [],
          categories: () => [],
          promotions: () => [{ id: 'promo-1', label: 'Lunch deal' }],
          productLabel: (id: string) => id,
          categoryLabel: (id: string) => id,
          promotionLabel: (id: string) => (id === 'promo-1' ? 'Lunch deal' : id),
          hostAllowed: () => true,
        },
      },
      {
        provide: ContentMedia,
        useValue: { thumbnails: signal({}), ensureThumbnail: () => undefined },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentEditor);
  const saved: ContentItemView[] = [];
  const dirty: boolean[] = [];
  const reloads: number[] = [];
  fixture.componentInstance.saved.subscribe((i) => saved.push(i));
  fixture.componentInstance.dirtyChange.subscribe((d) => dirty.push(d));
  fixture.componentInstance.reloadRequested.subscribe(() => reloads.push(1));
  if (options.item !== undefined) {
    fixture.componentRef.setInput('item', options.item);
  }
  if (options.newKind !== undefined) {
    fixture.componentRef.setInput('newKind', options.newKind);
  }
  if (options.newPlacement !== undefined) {
    fixture.componentRef.setInput('newPlacement', options.newPlacement);
  }
  fixture.detectChanges();
  await settle();
  fixture.detectChanges();
  return { fixture, api, root: fixture.nativeElement as HTMLElement, saved, dirty, reloads };
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));
const byId = <T extends HTMLElement>(root: HTMLElement, id: string): T =>
  root.querySelector<T>(`[data-testid="${id}"]`)!;
function type(root: HTMLElement, id: string, value: string): void {
  const field = byId<HTMLInputElement>(root, id);
  field.value = value;
  field.dispatchEvent(new Event('input'));
}
async function click(
  fixture: { detectChanges(): void },
  root: HTMLElement,
  id: string,
): Promise<void> {
  byId<HTMLButtonElement>(root, id).click();
  await settle();
  fixture.detectChanges();
}

describe('ContentEditor (ADR 0157, Front ends)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  describe('a new item', () => {
    it('starts empty, in the placement of its kind, with nothing to save until it has a name', async () => {
      const { fixture, root } = await setup({ newKind: 'BANNER' });

      expect(byId<HTMLInputElement>(root, 'content-internal-name').value).toBe('');
      expect(byId<HTMLButtonElement>(root, 'content-save').disabled).toBe(true);
      expect(root.querySelector('[data-testid="content-publish"]')).toBeNull();
      expect(root.querySelector('[data-testid="content-tab-preview"]')).toBeNull();

      type(root, 'content-internal-name', 'Spring sale');
      fixture.detectChanges();
      expect(byId<HTMLButtonElement>(root, 'content-save').disabled).toBe(false);
    });

    it('creates the item with its kind, placement, channels, schedule and a document with blank texts left out', async () => {
      const { fixture, api, root, saved } = await setup({
        newKind: 'PROMO_CARD',
        newPlacement: 'PROMO_GRID',
      });

      type(root, 'content-internal-name', '  Lunch card  ');
      type(root, 'content-title', 'Обед');
      fixture.detectChanges();
      type(root, 'content-subtitle', '   ');
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      expect(api.calls).toHaveLength(1);
      expect(api.calls[0].name).toBe('create');
      expect(api.calls[0].args[0]).toMatchObject({
        kind: 'PROMO_CARD',
        placement: 'PROMO_GRID',
        internalName: 'Lunch card',
        slug: null,
        channelCodes: [],
        startsAt: null,
        endsAt: null,
        promotionId: null,
        document: { schemaVersion: 1, texts: { ru: { title: 'Обед' } } },
      });
      expect(saved).toHaveLength(1);
    });

    it('lets a promo card choose between the two places it may sit in, and a banner only one', async () => {
      const card = await setup({ newKind: 'PROMO_CARD' });
      const options = Array.from(
        card.root.querySelectorAll<HTMLOptionElement>('[data-testid="content-placement"] option'),
      ).map((o) => o.value);
      expect(options).toEqual(['HOME_CAROUSEL', 'PROMO_GRID']);

      TestBed.resetTestingModule();
      const banner = await setup({ newKind: 'BANNER' });
      expect(banner.root.querySelector('[data-testid="content-placement"]')).toBeNull();
    });

    it('asks an editorial item for its address, which stays fixed after it is saved', async () => {
      const { fixture, root, api } = await setup({ newKind: 'NEWS' });
      expect(byId<HTMLInputElement>(root, 'content-slug').disabled).toBe(false);
      expect(byId<HTMLButtonElement>(root, 'content-save').disabled).toBe(true);

      type(root, 'content-internal-name', 'Opening');
      fixture.detectChanges();
      expect(byId<HTMLButtonElement>(root, 'content-save').disabled).toBe(true);
      type(root, 'content-slug', 'grand-opening');
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      expect(api.calls[0].args[0]).toMatchObject({
        kind: 'NEWS',
        slug: 'grand-opening',
        placement: null,
      });
      TestBed.resetTestingModule();
      const saved = await setup({
        item: view({ kind: 'NEWS', placement: null, slug: 'grand-opening' }),
      });
      expect(byId<HTMLInputElement>(saved.root, 'content-slug').disabled).toBe(true);
    });

    it('sends no slug for a kind that has none', async () => {
      const { fixture, api, root } = await setup({ newKind: 'BANNER' });
      type(root, 'content-internal-name', 'x');
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      expect((api.calls[0].args[0] as ContentCreateRequest).slug).toBeNull();
    });
  });

  describe('saving', () => {
    it('holds a language that has text but no title back from saving, and names it', async () => {
      const { fixture, root } = await setup({ newKind: 'BANNER' });
      type(root, 'content-internal-name', 'x');
      type(root, 'content-subtitle', 'only a subtitle');
      fixture.detectChanges();

      expect(byId<HTMLButtonElement>(root, 'content-save').disabled).toBe(true);
      expect(byId(root, 'content-missing-title').textContent).toContain('ru');
    });

    it('replaces the draft of an existing item at its version, and shows the new state as clean', async () => {
      const { fixture, api, root, dirty } = await setup({ item: view() });
      expect(byId<HTMLButtonElement>(root, 'content-save').disabled).toBe(true);

      type(root, 'content-title', 'Новая скидка');
      fixture.detectChanges();
      expect(byId(root, 'content-dirty')).toBeTruthy();
      expect(dirty.at(-1)).toBe(true);
      await click(fixture, root, 'content-save');

      expect(api.calls[0].name).toBe('replaceDraft');
      expect(api.calls[0].args.slice(0, 3)).toEqual(['item-1', 3, 'Spring sale']);
      expect((api.calls[0].args[3] as ContentDraftBody).document['texts']).toEqual({
        ru: { title: 'Новая скидка' },
      });
      expect(root.querySelector('[data-testid="content-dirty"]')).toBeNull();
      expect(byId(root, 'content-notice').textContent).toContain('Draft saved');
      expect(dirty.at(-1)).toBe(false);
    });

    it('starts from the live version when the item has no draft', async () => {
      const live = view({
        state: 'PUBLISHED',
        visibility: 'LIVE',
        draft: null,
        liveRevision: {
          revisionNo: 2,
          contentHash: 'h',
          document: withText(emptyDocument('BANNER'), 'ru', 'title', 'Живая версия'),
          channelCodes: ['web'],
          startsAt: null,
          endsAt: null,
          promotionId: null,
          locationId: null,
          publishedBy: 'anna',
          publishedAt: '2026-10-01T07:00:00Z',
        },
      });
      const { root } = await setup({ item: live });

      expect(byId<HTMLInputElement>(root, 'content-title').value).toBe('Живая версия');
    });
  });

  describe('channels and schedule', () => {
    it('shows no channel chosen, which means every channel that can show content', async () => {
      const { root } = await setup({ newKind: 'BANNER' });

      expect(
        Array.from(root.querySelectorAll('[data-testid="content-channel"]')).map((b) =>
          b.getAttribute('aria-pressed'),
        ),
      ).toEqual(['false', 'false']);
    });

    it('sends the channels the author chose', async () => {
      const { fixture, api, root } = await setup({ newKind: 'BANNER' });
      type(root, 'content-internal-name', 'x');
      root.querySelectorAll<HTMLButtonElement>('[data-testid="content-channel"]')[1].click();
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      expect((api.calls[0].args[0] as ContentCreateRequest).channelCodes).toEqual(['tg']);
    });

    it('says there is no channel to choose when none can show content', async () => {
      const { root } = await setup({ newKind: 'BANNER', channels: [] });

      expect(root.querySelector('[data-testid="content-no-channels"]')).toBeTruthy();
    });

    it('schedules whole days: between two dates starts at the first midnight and ends at the one after the last day', async () => {
      const { fixture, api, root } = await setup({ newKind: 'BANNER' });
      type(root, 'content-internal-name', 'x');
      byId<HTMLInputElement>(root, 'content-schedule-between').click();
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      const request = api.calls[0].args[0] as ContentCreateRequest;
      expect(request.startsAt).toBe(startInstant(today()));
      expect(request.endsAt).toBe(endInstant(addDays(today(), 6)));
    });

    it('keeps the whole last day: a window to the 7th ends at the midnight after it, and the picker has no reporting presets', async () => {
      const { fixture, api, root } = await setup({ newKind: 'BANNER' });
      type(root, 'content-internal-name', 'x');
      byId<HTMLInputElement>(root, 'content-schedule-between').click();
      fixture.detectChanges();
      const picker = fixture.debugElement.query(By.directive(DateRangePicker))
        .componentInstance as DateRangePicker;
      expect(picker.showPresets()).toBe(false);

      picker.rangeChange.emit({ start: '2026-11-01', end: '2026-11-07' });
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      const request = api.calls[0].args[0] as ContentCreateRequest;
      expect(request.startsAt).toBe(startInstant('2026-11-01'));
      expect(request.endsAt).toBe(endInstant('2026-11-07'));
      expect(request.endsAt).not.toBe(startInstant('2026-11-07'));
    });

    it('can run from a date onwards, and clears the window again with “always”', async () => {
      const { fixture, api, root } = await setup({ newKind: 'BANNER' });
      type(root, 'content-internal-name', 'x');
      // The platform answers a save with the window it was sent.
      api.next = () =>
        Promise.resolve(
          view({ version: 4, draft: { ...view().draft!, startsAt: startInstant(today()) } }),
        );
      byId<HTMLInputElement>(root, 'content-schedule-from').click();
      fixture.detectChanges();
      await click(fixture, root, 'content-save');
      const first = api.calls[0].args[0] as ContentCreateRequest;
      expect(first.startsAt).toBe(startInstant(today()));
      expect(first.endsAt).toBeNull();

      byId<HTMLInputElement>(root, 'content-schedule-always').click();
      fixture.detectChanges();
      await click(fixture, root, 'content-save');
      const second = api.calls[1].args;
      expect((second[3] as ContentDraftBody).startsAt).toBeNull();
      expect((second[3] as ContentDraftBody).endsAt).toBeNull();
    });

    it('refuses to save a window that ends before it starts', async () => {
      const backwards = view({
        draft: {
          ...view().draft!,
          startsAt: startInstant('2026-11-10'),
          endsAt: startInstant('2026-11-05'),
        },
      });
      const { root } = await setup({ item: backwards });

      expect(byId(root, 'content-backwards')).toBeTruthy();
      expect(byId<HTMLButtonElement>(root, 'content-save').disabled).toBe(true);
    });
  });

  describe('promotion and branch', () => {
    it('ties a promo card to a live promotion, and only a promo card', async () => {
      const card = await setup({ item: view({ kind: 'PROMO_CARD' }) });
      expect(card.root.querySelector('[data-testid="content-promotion"]')).toBeTruthy();

      TestBed.resetTestingModule();
      const banner = await setup({ item: view({ kind: 'BANNER' }) });
      expect(banner.root.querySelector('[data-testid="content-promotion"]')).toBeNull();
    });

    it('names the chosen promotion and lets the author untie it', async () => {
      const { fixture, root } = await setup({
        item: view({ kind: 'PROMO_CARD', draft: { ...view().draft!, promotionId: 'promo-1' } }),
      });

      expect(byId(root, 'content-promotion').textContent).toContain('Lunch deal');
      await click(fixture, root, 'content-promotion-clear');
      expect(root.querySelector('[data-testid="content-promotion"] q-combobox')).toBeTruthy();
    });

    it('offers a branch on a vacancy only', async () => {
      const vacancy = await setup({ newKind: 'VACANCY' });
      expect(vacancy.root.querySelector('[data-testid="content-branch"]')).toBeTruthy();
      expect(vacancy.root.querySelector('[data-testid="content-channel"]')).toBeNull();
    });
  });

  describe('languages', () => {
    it('shows a tab per language of the brand, default first, with a dot for the ones that have a title', async () => {
      const { fixture } = await setup({ item: view() });
      const group = fixture.debugElement.query(By.directive(LocalizedFieldGroup))
        .componentInstance as LocalizedFieldGroup;

      expect(group.locales()).toEqual(['ru', 'uz-Latn', 'en']);
      expect(group.defaultLocale()).toBe('ru');
      expect(group.activeLocale()).toBe('ru');
      expect(group.completeness()).toEqual({ ru: true, 'uz-Latn': false, en: false });
    });

    it('edits another language when its tab is chosen, without touching the first', async () => {
      const { fixture, root, api } = await setup({ item: view() });
      const group = fixture.debugElement.query(By.directive(LocalizedFieldGroup))
        .componentInstance as LocalizedFieldGroup;

      group.activeLocaleChange.emit('uz-Latn');
      fixture.detectChanges();
      expect(byId<HTMLInputElement>(root, 'content-title').value).toBe('');
      type(root, 'content-title', 'Bahorgi chegirma');
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      expect((api.calls[0].args[3] as ContentDraftBody).document['texts'] as object).toEqual({
        ru: { title: 'Весенняя скидка' },
        'uz-Latn': { title: 'Bahorgi chegirma' },
      });
    });
  });

  describe('publishing', () => {
    it('asks first, and says what happens', async () => {
      const { fixture, root, api } = await setup({ item: view() });

      byId<HTMLButtonElement>(root, 'content-publish').click();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.directive(ConfirmDialog))).toBeTruthy();
      expect(root.textContent).toContain('goes live');
      expect(api.calls).toEqual([]);
    });

    it('saves what is on screen first, then publishes the version that save made', async () => {
      const { fixture, root, api, saved } = await setup({ item: view() });
      type(root, 'content-title', 'Правка');
      fixture.detectChanges();

      byId<HTMLButtonElement>(root, 'content-publish').click();
      fixture.detectChanges();
      api.next = () => Promise.resolve(view({ version: 4 }));
      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).confirm.emit();
      await settle();
      await settle();
      fixture.detectChanges();

      expect(api.calls.map((c) => c.name)).toEqual(['replaceDraft', 'publish']);
      expect(api.calls[1].args).toEqual(['item-1', 4]);
      expect(saved).toHaveLength(2);
      expect(fixture.debugElement.query(By.directive(ConfirmDialog))).toBeNull();
      expect(byId(root, 'content-notice').textContent).toContain('Published');
    });

    it('publishes straight away when nothing has changed', async () => {
      const { fixture, root, api } = await setup({ item: view() });

      byId<HTMLButtonElement>(root, 'content-publish').click();
      fixture.detectChanges();
      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).confirm.emit();
      await settle();

      expect(api.calls.map((c) => c.name)).toEqual(['publish']);
    });

    it('shows each reason the platform refused with as its own sentence, beside the fields', async () => {
      const { fixture, root, api } = await setup({ item: view() });
      api.next = () =>
        Promise.reject(
          new ApiError(
            'UNPROCESSABLE_STATE',
            422,
            {
              status: 422,
              errors: [
                { field: 'media.primaryAssetId', code: 'CONTENT_MEDIA_NOT_DISPLAYABLE' },
                { field: 'link.targetId', code: 'CONTENT_TARGET_NOT_FOUND' },
              ],
            },
            null,
          ),
        );

      byId<HTMLButtonElement>(root, 'content-publish').click();
      fixture.detectChanges();
      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).confirm.emit();
      await settle();
      fixture.detectChanges();

      const list = Array.from(root.querySelectorAll('[data-testid="content-problem"]')).map(
        (li) => li.textContent,
      );
      expect(list[0]).toContain('An image is missing');
      expect(list[1]).toContain('not on the brand’s published menu');
      expect(byId(root, 'content-error').textContent).toContain('did not accept');
    });

    it('shows the warnings a save returns before anything is published', async () => {
      const { fixture, root, api } = await setup({ item: view() });
      api.next = () =>
        Promise.resolve(
          view({
            version: 4,
            problems: [{ field: 'texts', code: 'CONTENT_DEFAULT_LOCALE_MISSING', message: 'x' }],
          }),
        );
      type(root, 'content-title', 'Правка');
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      expect(byId(root, 'content-problems').textContent).toContain('default language');
    });
  });

  describe('withdraw, archive and discard', () => {
    const live = (): ContentItemView =>
      view({ state: 'PUBLISHED', visibility: 'LIVE', hasUnpublishedChanges: true });

    it('withdraws a published item after asking, at its version', async () => {
      const { fixture, root, api } = await setup({ item: live() });

      await click(fixture, root, 'content-withdraw');
      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).confirm.emit();
      await settle();

      expect(api.calls).toEqual([{ name: 'withdraw', args: ['item-1', 3] }]);
    });

    it('offers withdraw only while the item is published', async () => {
      const { root } = await setup({ item: view() });

      expect(root.querySelector('[data-testid="content-withdraw"]')).toBeNull();
    });

    it('archives after asking', async () => {
      const { fixture, root, api } = await setup({ item: live() });

      await click(fixture, root, 'content-archive');
      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).confirm.emit();
      await settle();

      expect(api.calls).toEqual([{ name: 'archive', args: ['item-1', 3] }]);
    });

    it('discards a draft after asking, and only when there is one', async () => {
      const { fixture, root, api } = await setup({ item: live() });
      await click(fixture, root, 'content-discard');
      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).confirm.emit();
      await settle();
      expect(api.calls).toEqual([{ name: 'discardDraft', args: ['item-1', 3] }]);

      TestBed.resetTestingModule();
      const noDraft = await setup({ item: view({ state: 'PUBLISHED', draft: null }) });
      expect(noDraft.root.querySelector('[data-testid="content-discard"]')).toBeNull();
    });

    it('does nothing when the author backs out of the question', async () => {
      const { fixture, root, api } = await setup({ item: live() });

      await click(fixture, root, 'content-archive');
      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).cancel.emit();
      fixture.detectChanges();

      expect(api.calls).toEqual([]);
      expect(fixture.debugElement.query(By.directive(ConfirmDialog))).toBeNull();
    });

    it('is read-only once archived, with no way to act', async () => {
      const { root } = await setup({ item: view({ state: 'ARCHIVED' }) });

      expect(byId<HTMLInputElement>(root, 'content-internal-name').disabled).toBe(true);
      expect(byId<HTMLInputElement>(root, 'content-title').disabled).toBe(true);
      for (const id of [
        'content-publish',
        'content-withdraw',
        'content-archive',
        'content-discard',
      ]) {
        expect(root.querySelector(`[data-testid="${id}"]`)).toBeNull();
      }
      expect(byId<HTMLButtonElement>(root, 'content-save').disabled).toBe(true);
    });
  });

  describe('a colleague’s change', () => {
    it('says so, and asks the host to read the item again', async () => {
      const { fixture, root, api, reloads } = await setup({ item: view() });
      api.next = () =>
        Promise.reject(
          new ApiError('STALE_VERSION', 412, { status: 412, expected: 3, actual: 5 }, null),
        );
      type(root, 'content-title', 'Правка');
      fixture.detectChanges();
      await click(fixture, root, 'content-save');

      expect(byId(root, 'content-stale')).toBeTruthy();
      await click(fixture, root, 'content-reload');
      expect(reloads).toHaveLength(1);
    });
  });

  describe('tabs of an existing item', () => {
    it('offers preview, history and counts, and none of them to a new item', async () => {
      const { root } = await setup({ item: view() });

      for (const tab of ['edit', 'preview', 'history', 'stats']) {
        expect(root.querySelector(`[data-testid="content-tab-${tab}"]`)).toBeTruthy();
      }
    });

    it('brings a restored version back into the editor as the draft', async () => {
      const { fixture, root, saved } = await setup({ item: view() });
      await click(fixture, root, 'content-tab-history');
      const history = fixture.debugElement.query(By.directive(ContentHistory))
        .componentInstance as ContentHistory;

      history.restored.emit(
        view({
          version: 9,
          draft: {
            ...view().draft!,
            document: withText(emptyDocument('BANNER'), 'ru', 'title', 'Старая версия'),
          },
        }),
      );
      fixture.detectChanges();

      expect(byId(root, 'content-form')).toBeTruthy();
      expect(byId<HTMLInputElement>(root, 'content-title').value).toBe('Старая версия');
      expect(byId(root, 'content-notice').textContent).toContain('Nothing reaches customers');
      expect(saved).toHaveLength(1);
    });
  });
});
