import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { FeedbackSettingsApi, PolicyTarget } from './feedback-settings-api';

const TENANT: PolicyTarget = { kind: 'TENANT', tenantId: 't1' };
const BRAND: PolicyTarget = { kind: 'BRAND', scope: { tenantId: 't1', brandId: 'b1' } };

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

describe('FeedbackSettingsApi', () => {
  let api: FeedbackSettingsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(FeedbackSettingsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the tag library from the tenant route', async () => {
    const promise = api.library('t1');

    http.expectOne(url('/api/v1/operations/tenants/t1/feedback/tags')).flush({
      tags: [],
      locales: ['ru'],
      defaultLocale: 'ru',
      maximumActiveTags: 40,
      iconAspectRatio: '3:2',
      iconMaximumBytes: 1048576,
    });

    expect((await promise).maximumActiveTags).toBe(40);
  });

  it('creates a tag with its wording and an Idempotency-Key', async () => {
    const promise = api.createTag('t1', {
      code: 'food_greasy',
      category: 'FOOD',
      sentiment: 'NEGATIVE',
      iconAssetId: null,
      sortOrder: null,
      labels: { ru: 'Слишком жирно' },
    });

    const request = http.expectOne(url('/api/v1/operations/tenants/t1/feedback/tags'));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body.labels).toEqual({ ru: 'Слишком жирно' });
    request.flush({ id: 'g1', code: 'food_greasy' });

    expect((await promise).id).toBe('g1');
  });

  it('imports the starter pack with no body', async () => {
    const promise = api.importStarterPack('t1');

    const request = http.expectOne(url('/api/v1/operations/tenants/t1/feedback/tags/starter-pack'));
    expect(request.request.method).toBe('POST');
    request.flush([]);

    expect(await promise).toEqual([]);
  });

  it('edits a tag with the version the list returned in If-Match, and names only the locales it shows', async () => {
    const promise = api.updateTag(
      't1',
      'g1',
      {
        category: 'FOOD',
        sentiment: 'NEGATIVE',
        iconAssetId: null,
        sortOrder: 20,
        status: 'RETIRED',
        labels: { en: 'Served cold' },
      },
      7,
    );

    const request = http.expectOne(url('/api/v1/operations/tenants/t1/feedback/tags/g1'));
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"7"');
    expect(request.request.body.labels).toEqual({ en: 'Served cold' });
    expect(request.request.body.code).toBeUndefined();
    request.flush({ id: 'g1', version: 8 });

    await promise;
  });

  it('reads and saves the prompt policy at the tenant and at a brand, each against the version at its own level', async () => {
    const read = api.promptPolicy(TENANT);
    http
      .expectOne(url('/api/v1/operations/tenants/t1/feedback/prompt-policy'))
      .flush({ isDefault: true, rows: [], versionAtScope: 0 });
    expect((await read).isDefault).toBe(true);

    const save = api.savePromptPolicy(
      BRAND,
      { sendWindow: null, inlineWindowDays: 3, rows: [], reason: 'ask within three days' },
      2,
    );
    const request = http.expectOne(
      url('/api/v1/operations/tenants/t1/brands/b1/feedback/prompt-policy'),
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"2"');
    expect(request.request.body).toEqual({
      sendWindow: null,
      inlineWindowDays: 3,
      rows: [],
      reason: 'ask within three days',
    });
    request.flush({ isDefault: false, rows: [], versionAtScope: 3 });
    expect((await save).versionAtScope).toBe(3);
  });

  it('saves the recovery policy against the version at the level it is written', async () => {
    const promise = api.saveRecoveryPolicy(
      TENANT,
      {
        autoOpenMaxRating: 3,
        firstResponseMinutes: 60,
        resolutionMinutes: 1440,
        alertOnOpen: true,
        alertOnBreach: false,
        reason: 'tighten for the pilot',
      },
      0,
    );

    const request = http.expectOne(url('/api/v1/operations/tenants/t1/feedback/recovery-policy'));
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"0"');
    expect(request.request.body.autoOpenMaxRating).toBe(3);
    request.flush({ isDefault: false, versionAtScope: 1 });

    expect((await promise).versionAtScope).toBe(1);
  });

  it('reads the recovery policy of a brand from the brand route', async () => {
    const promise = api.recoveryPolicy(BRAND);

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/feedback/recovery-policy'))
      .flush({ isDefault: true, versionAtScope: 0 });

    expect((await promise).isDefault).toBe(true);
  });
});
