import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { LocationScope } from '../../../core/api/operations-paths';
import { IdleUploadRejected, IdleUploadTimedOut, KioskApi } from './kiosk-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const IDLE = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/kiosk/channels/c1/idle-media`;

function pause(): Promise<void> {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('KioskApi: idle media (ADR 0187)', () => {
  let api: KioskApi;
  let http: HttpTestingController;
  const file = new File([new Uint8Array(32)], 'lobby.jpg', { type: 'image/jpeg' });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), KioskApi],
    });
    api = TestBed.inject(KioskApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('attaches against the version it read, with an idempotency key', () => {
    api.attachIdle(SCOPE, 'c1', 'a1', 3).subscribe();

    const request = http.expectOne(IDLE);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toBe('W/"3"');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual({ assetId: 'a1' });
    request.flush({});
  });

  it('sends the whole list in its new order to the order route', () => {
    api.reorderIdle(SCOPE, 'c1', ['i2', 'i1'], 5).subscribe();

    const request = http.expectOne(`${IDLE}/order`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"5"');
    expect(request.request.body).toEqual({ itemIds: ['i2', 'i1'] });
    request.flush({});
  });

  it('removes with DELETE against the version it read and answers the list', () => {
    let body: unknown;
    api.removeIdle(SCOPE, 'c1', 'i1', 2).subscribe((list) => (body = list));

    const request = http.expectOne(`${IDLE}/i1`);
    expect(request.request.method).toBe('DELETE');
    expect(request.request.headers.get('If-Match')).toBe('W/"2"');
    request.flush({ channelId: 'c1', version: 3, items: [] });
    expect(body).toMatchObject({ version: 3 });
  });

  async function walkToTheStore(): Promise<void> {
    await pause();
    const requested = http.expectOne(`${IDLE}/upload-requests`);
    expect(requested.request.body).toEqual({
      contentType: 'image/jpeg',
      sizeBytes: 32,
      filename: 'lobby.jpg',
    });
    requested.flush({
      assetId: 'a1',
      uploadUrl: 'https://store.example/put/a1',
      requiredHeaders: { 'Content-Type': 'image/jpeg' },
      expiresAt: '2026-10-14T10:00:00Z',
    });
    await pause();
    const put = http.expectOne('https://store.example/put/a1');
    expect(put.request.method).toBe('PUT');
    expect(put.request.headers.get('Content-Type')).toBe('image/jpeg');
    put.flush(null);
    await pause();
    http.expectOne(`${IDLE}/uploads/a1/finalize`).flush({ assetId: 'a1', status: 'UPLOADED' });
  }

  it('uploads, claims it complete and waits for the platform to call the picture available', async () => {
    const done = api.uploadIdlePicture(SCOPE, 'c1', file, { intervalMs: 0, attempts: 5 });

    await walkToTheStore();
    await pause();
    http
      .expectOne(`${IDLE}/uploads/a1`)
      .flush({ assetId: 'a1', status: 'UPLOADED', rejectionCode: null });
    await pause();
    await pause();
    http
      .expectOne(`${IDLE}/uploads/a1`)
      .flush({ assetId: 'a1', status: 'AVAILABLE', rejectionCode: null });

    await expect(done).resolves.toBe('a1');
  });

  it('rejects with the platform’s code when the pipeline refused the picture', async () => {
    const done = api.uploadIdlePicture(SCOPE, 'c1', file, { intervalMs: 0, attempts: 5 });
    const outcome = done.then(
      () => null,
      (error: unknown) => error,
    );

    await walkToTheStore();
    await pause();
    http
      .expectOne(`${IDLE}/uploads/a1`)
      .flush({ assetId: 'a1', status: 'REJECTED', rejectionCode: 'PURPOSE_ASPECT_RATIO' });

    const error = await outcome;
    expect(error).toBeInstanceOf(IdleUploadRejected);
    expect((error as IdleUploadRejected).rejectionCode).toBe('PURPOSE_ASPECT_RATIO');
  });

  it('gives up with a timeout when the platform never decides', async () => {
    const done = api.uploadIdlePicture(SCOPE, 'c1', file, { intervalMs: 0, attempts: 2 });
    const outcome = done.then(
      () => null,
      (error: unknown) => error,
    );

    await walkToTheStore();
    for (let attempt = 0; attempt < 2; attempt++) {
      await pause();
      await pause();
      http
        .expectOne(`${IDLE}/uploads/a1`)
        .flush({ assetId: 'a1', status: 'UPLOADED', rejectionCode: null });
    }

    expect(await outcome).toBeInstanceOf(IdleUploadTimedOut);
  });
});
