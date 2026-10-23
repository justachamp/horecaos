import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { MediaApi } from '../../catalog/media-api';
import { AddVersionRequest, NotificationsApi, WordingResponse } from './notifications-api';
import { TemplateEditor } from './template-editor';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

describe('TemplateEditor for a push wording (ADR 0182, D14)', () => {
  let fixture: ComponentFixture<TemplateEditor>;
  let api: { addVersion: ReturnType<typeof vi.fn>; variableCatalogue: ReturnType<typeof vi.fn> };
  let media: { upload: ReturnType<typeof vi.fn> };

  async function open(prefill?: readonly WordingResponse[]): Promise<void> {
    api = {
      addVersion: vi
        .fn()
        .mockResolvedValue({ templateId: 't1', versionNumber: 1, awaitsProviderReview: false }),
      variableCatalogue: vi.fn().mockResolvedValue([]),
    };
    media = { upload: vi.fn().mockReturnValue(of({ assetId: 'asset-9' })) };
    await TestBed.configureTestingModule({
      imports: [TemplateEditor],
      providers: [
        { provide: NotificationsApi, useValue: api },
        { provide: MediaApi, useValue: media },
        { provide: LocaleSet, useValue: { locales: signal(['ru']), ensureLoaded: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TemplateEditor);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('templateId', 't1');
    fixture.componentRef.setInput('templateKey', 'PROMO_PUSH');
    fixture.componentRef.setInput('notificationClass', 'MARKETING');
    fixture.componentRef.setInput('channel', 'PUSH');
    if (prefill) {
      fixture.componentRef.setInput('prefill', prefill);
    }
    fixture.detectChanges();
    await vi.waitFor(() => expect(api.variableCatalogue).toHaveBeenCalled());
    fixture.detectChanges();
  }

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const field = <T extends HTMLElement>(testId: string): T =>
    host().querySelector(`[data-testid="${testId}"]`) as T;

  function type(testId: string, text: string): void {
    const el = field<HTMLInputElement>(testId);
    el.value = text;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  const save = (): HTMLButtonElement => field<HTMLButtonElement>('editor-save');

  it('asks for a title as well as the text, and cannot be saved without one', async () => {
    await open();

    expect(host().textContent).toContain('Title');
    expect(host().textContent).not.toContain('Subject');
    type('editor-body', 'Chegirma 20%');
    expect(save().disabled).toBe(true);

    type('editor-subject', 'Aksiya');
    expect(save().disabled).toBe(false);
  });

  it('shows the title above the text in the phone preview', async () => {
    await open();
    type('editor-subject', 'Aksiya');
    type('editor-body', 'Chegirma 20%');

    expect(field('preview-title').textContent).toContain('Aksiya');
    expect(field('preview-body').textContent).toBe('Chegirma 20%');
  });

  it('sends the title, the cover and the target with every language', async () => {
    await open();
    type('editor-subject', 'Aksiya');
    type('editor-body', 'Chegirma 20%');
    type('push-target', '/category/pizza?promo=PIZZA20');
    // the uploader is a canvas cropper jsdom cannot drive: its output is what the fields component reacts to
    const fields = fixture.debugElement.query((el) => el.name === 'q-push-wording-fields');
    (fields.componentInstance as { upload(file: File): Promise<void> }).upload(
      new File(['x'], 'cover.jpg', { type: 'image/jpeg' }),
    );
    await vi.waitFor(() => expect(media.upload).toHaveBeenCalled());
    fixture.detectChanges();
    await vi.waitFor(() => expect(field('push-cover-set')).not.toBeNull());

    save().click();
    await vi.waitFor(() => expect(api.addVersion).toHaveBeenCalledTimes(1));

    const [, , request] = api.addVersion.mock.calls[0] as [
      LocationScope,
      string,
      AddVersionRequest,
    ];
    expect(request.wordings['ru']).toEqual({
      subject: 'Aksiya',
      body: 'Chegirma 20%',
      coverAssetId: 'asset-9',
      targetPath: '/category/pizza?promo=PIZZA20',
    });
    expect(media.upload).toHaveBeenCalledWith(
      'tenant-1',
      'TENANT',
      'tenant-1',
      'PUBLIC',
      expect.any(File),
    );
  });

  it('sends neither a cover nor a target when the author set none', async () => {
    await open();
    type('editor-subject', 'Aksiya');
    type('editor-body', 'Chegirma');

    save().click();
    await vi.waitFor(() => expect(api.addVersion).toHaveBeenCalledTimes(1));

    const [, , request] = api.addVersion.mock.calls[0] as [
      LocationScope,
      string,
      AddVersionRequest,
    ];
    expect(request.wordings['ru']).toEqual({ subject: 'Aksiya', body: 'Chegirma' });
  });

  it('holds back a target that is not a path on the storefront, and says so', async () => {
    await open();
    type('editor-subject', 'Aksiya');
    type('editor-body', 'Chegirma');

    for (const bad of ['menu', '//evil.example', 'https://evil.example', '/a b']) {
      type('push-target', bad);
      expect(save().disabled, bad).toBe(true);
      expect(field('push-target-invalid'), bad).not.toBeNull();
    }
    type('push-target', '/menu');
    expect(save().disabled).toBe(false);
    expect(field('push-target-invalid')).toBeNull();
    type('push-target', '');
    expect(save().disabled).toBe(false);
  });

  it('starts from the cover and the target of the version it is given', async () => {
    const row: WordingResponse = {
      versionNumber: 3,
      locale: 'ru',
      subject: 'Aksiya',
      body: 'Chegirma',
      contentHash: 'h',
      status: 'ACTIVE',
      approvedBy: 'ops',
      variablesSchema: {},
      providerReview: 'NOT_REQUIRED',
      providerReviewReference: null,
      providerReviewNote: null,
      providerReviewUpdatedAt: null,
      showItemPrices: false,
      coverAssetId: 'asset-3',
      targetPath: '/menu',
    };
    await open([row]);

    expect(field<HTMLInputElement>('push-target').value).toBe('/menu');
    expect(field('push-cover-set')).not.toBeNull();
    save().click();
    await vi.waitFor(() => expect(api.addVersion).toHaveBeenCalledTimes(1));
    const [, , request] = api.addVersion.mock.calls[0] as [
      LocationScope,
      string,
      AddVersionRequest,
    ];
    expect(request.wordings['ru']).toMatchObject({ coverAssetId: 'asset-3', targetPath: '/menu' });
  });

  it('lets the author take the cover away', async () => {
    await open();
    type('editor-subject', 'Aksiya');
    type('editor-body', 'Chegirma');
    const fields = fixture.debugElement.query((el) => el.name === 'q-push-wording-fields');
    (fields.componentInstance as { upload(file: File): Promise<void> }).upload(
      new File(['x'], 'cover.jpg', { type: 'image/jpeg' }),
    );
    await vi.waitFor(() => expect(media.upload).toHaveBeenCalled());
    fixture.detectChanges();
    await vi.waitFor(() => expect(field('push-cover-set')).not.toBeNull());

    field<HTMLButtonElement>('push-cover-remove').click();
    fixture.detectChanges();
    expect(field('push-cover-set')).toBeNull();

    save().click();
    await vi.waitFor(() => expect(api.addVersion).toHaveBeenCalledTimes(1));
    const [, , request] = api.addVersion.mock.calls[0] as [
      LocationScope,
      string,
      AddVersionRequest,
    ];
    expect(request.wordings['ru']).not.toHaveProperty('coverAssetId');
  });
});
