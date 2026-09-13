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
