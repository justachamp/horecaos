import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { BrandScope } from '../../core/api/catalog-paths';
import { ChannelPreviewApi } from './channel-preview-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const BASE = `${environment.apiBaseUrl}/api/v1/control-plane/tenants/t1/brands/b1/catalog`;

describe('ChannelPreviewApi', () => {
  let api: ChannelPreviewApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ChannelPreviewApi],
    });
    api = TestBed.inject(ChannelPreviewApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('reads the preview of one channel at one branch, naming the branch and the locale', () => {
    api.previewPage(SCOPE, 'cat1', 'ch1', { locationId: 'l1' }, 'uz', null, 100).subscribe();

    const request = http.expectOne((r) => r.url === `${BASE}/catalogs/cat1/channels/ch1/preview`);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('locationId')).toBe('l1');
    expect(request.request.params.get('locale')).toBe('uz');
    expect(request.request.params.get('limit')).toBe('100');
    // No cursor on the first page, and no binding unless one was chosen.
    expect(request.request.params.has('cursor')).toBe(false);
    expect(request.request.params.has('bindingId')).toBe(false);
    request.flush({ items: [], nextCursor: null });
  });

  it('continues from the cursor it was given', () => {
    api.previewPage(SCOPE, 'cat1', 'ch1', { bindingId: 'bind1' }, 'ru', 'next-1').subscribe();

    const request = http.expectOne((r) => r.url === `${BASE}/catalogs/cat1/channels/ch1/preview`);
    expect(request.request.params.get('cursor')).toBe('next-1');
    expect(request.request.params.get('bindingId')).toBe('bind1');
    request.flush({ items: [], nextCursor: null });
  });

  it('replaces a product’s channel photos as the whole set, under the version it read, with an idempotency key', () => {
    let result: unknown;
    api
      .replaceMediaOverride(
        SCOPE,
        'ch1',
        'PRODUCT',
        'p1',
        [{ mediaAssetId: 'a1', role: 'PRIMARY', sortOrder: 0 }],
        3,
      )
      .subscribe((value) => (result = value));

    const request = http.expectOne(`${BASE}/channels/ch1/media-overrides/PRODUCT/p1`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    // ADR 0031: a save of a versioned set names the version it was shown, or the server refuses it.
    expect(request.request.headers.get('If-Match')).toBe('W/"3"');
    expect(request.request.body).toEqual({
      images: [{ mediaAssetId: 'a1', role: 'PRIMARY', sortOrder: 0 }],
    });
    request.flush({
      images: [
        {
          entityType: 'PRODUCT',
          entityId: 'p1',
          mediaAssetId: 'a1',
          role: 'PRIMARY',
          sortOrder: 0,
          version: 1,
        },
      ],
    });
    expect(result).toHaveLength(1);
  });

  it('reads one item’s channel photos with the version a save has to quote', () => {
    let set: unknown;
    api.mediaOverrideSet(SCOPE, 'ch1', 'PRODUCT', 'p1').subscribe((value) => (set = value));

    const request = http.expectOne((r) => r.url === `${BASE}/channels/ch1/media-overrides`);
    expect(request.request.params.get('entityType')).toBe('PRODUCT');
    expect(request.request.params.get('entityId')).toBe('p1');
    request.flush({ images: [], version: 0 }, { headers: { ETag: 'W/"0"' } });

    expect(set).toEqual({ images: [], version: 0 });
  });

  it('takes the version from the ETag, which is where the server states it', () => {
    let set: unknown;
    api.mediaOverrideSet(SCOPE, 'ch1', 'PRODUCT', 'p1').subscribe((value) => (set = value));

    http
      .expectOne((r) => r.url === `${BASE}/channels/ch1/media-overrides`)
      .flush({ images: [] }, { headers: { ETag: 'W/"7"' } });

    expect(set).toEqual({ images: [], version: 7 });
  });

  it('lists the branches a channel sells at', () => {
    let targets: unknown;
    api.targets(SCOPE, 'ch1').subscribe((value) => (targets = value));

    http
      .expectOne(`${BASE}/channels/ch1/preview-targets`)
      .flush([{ locationId: 'l1', binding: null }]);

    expect(targets).toEqual([{ locationId: 'l1', binding: null }]);
  });
});
