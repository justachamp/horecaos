import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { MediaApi } from './media-api';

const BASE = `${environment.apiBaseUrl}/api/v1/tenants/t1/media/assets/a1`;

describe('MediaApi.downloadUrl', () => {
  let api: MediaApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MediaApi],
    });
    api = TestBed.inject(MediaApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('asks for the original with no variant param', () => {
    let value: string | undefined;
    api.downloadUrl('t1', 'a1').subscribe((v) => (value = v));

    const request = http.expectOne((r) => r.url === `${BASE}/download-url`);
    expect(request.request.params.has('variant')).toBe(false);
    request.flush({ url: 'https://cdn.example/original.jpg' });

    expect(value).toBe('https://cdn.example/original.jpg');
  });

  it('the trap this closes: a variant is actually requested, not silently dropped to the original', () => {
    let value: string | undefined;
    api.downloadUrl('t1', 'a1', 'THUMBNAIL').subscribe((v) => (value = v));

    const request = http.expectOne((r) => r.url === `${BASE}/download-url`);
    expect(request.request.params.get('variant')).toBe('THUMBNAIL');
    request.flush({ url: 'https://cdn.example/original-thumb.jpg' });

    expect(value).toBe('https://cdn.example/original-thumb.jpg');
  });
});

describe('MediaApi.requestUpload (ADR 0157: a content creative asks for the HERO rendition)', () => {
  let api: MediaApi;
  let http: HttpTestingController;
  const file = new File([new Uint8Array(8)], 'banner.jpg', { type: 'image/jpeg' });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MediaApi],
    });
    api = TestBed.inject(MediaApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('sends no renditionProfile for a product photo, so it is rendered the three standard sizes', () => {
    api.requestUpload('t1', 'BRAND', 'b1', 'PUBLIC', file).subscribe();

    const request = http.expectOne((r) => r.url.endsWith('/media/assets/upload-requests'));
    expect(request.request.body).not.toHaveProperty('renditionProfile');
    request.flush({});
  });

  it('sends CREATIVE when the content editor asks for it', () => {
    api.requestUpload('t1', 'BRAND', 'b1', 'PUBLIC', file, 'CREATIVE').subscribe();

    const request = http.expectOne((r) => r.url.endsWith('/media/assets/upload-requests'));
    expect(request.request.body).toMatchObject({
      ownerScope: 'BRAND',
      ownerId: 'b1',
      visibility: 'PUBLIC',
      renditionProfile: 'CREATIVE',
      contentType: 'image/jpeg',
    });
    request.flush({});
  });
});

describe('MediaApi under a brand (ADR 0157: a brand manager holds media.upload at her brand only)', () => {
  let api: MediaApi;
  let http: HttpTestingController;
  const brand = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/media/assets`;
  const file = new File([new Uint8Array(8)], 'banner.jpg', { type: 'image/jpeg' });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MediaApi],
    });
    api = TestBed.inject(MediaApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('requests the upload under the brand and names no owner: the brand in the path is the owner', () => {
    api.requestBrandUpload('t1', 'b1', 'PUBLIC', file, 'CREATIVE').subscribe();

    const request = http.expectOne(`${brand}/upload-requests`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      visibility: 'PUBLIC',
      contentType: 'image/jpeg',
      sizeBytes: 8,
      filename: 'banner.jpg',
      renditionProfile: 'CREATIVE',
    });
    request.flush({});
  });

  it('finalizes, reads the status and asks for a rendition under the brand, never the tenant routes', () => {
    api.finalizeBrand('t1', 'b1', 'a1').subscribe();
    http.expectOne(`${brand}/a1/finalize`).flush({ assetId: 'a1', status: 'UPLOADED' });

    let status: string | undefined;
    api.brandAsset('t1', 'b1', 'a1').subscribe((view) => (status = view.status));
    http.expectOne(`${brand}/a1`).flush({ assetId: 'a1', status: 'AVAILABLE' });
    expect(status).toBe('AVAILABLE');

    let url: string | undefined;
    api.brandDownloadUrl('t1', 'b1', 'a1', 'THUMBNAIL').subscribe((value) => (url = value));
    const download = http.expectOne((r) => r.url === `${brand}/a1/download-url`);
    expect(download.request.params.get('variant')).toBe('THUMBNAIL');
    download.flush({ url: 'https://cdn.example/thumb.jpg' });
    expect(url).toBe('https://cdn.example/thumb.jpg');
  });
});
