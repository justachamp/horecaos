import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { PrintingApi, printRefusal } from './printing-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1`;

/**
 * The page specs mock `PrintingApi`, so nothing else builds the real request. What matters here is what the
 * platform will actually receive: the verb, the `Idempotency-Key` every effect carries, the `If-Match` every
 * aggregate write carries, and that the preview, which is a read, carries neither.
 */
describe('PrintingApi', () => {
  let http: HttpTestingController;
  let api: PrintingApi;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(PrintingApi);
  });

  afterEach(() => http.verify());

  it('registers a browser printer with both agent fields null and a key on the request', async () => {
    const pending = api.registerPrinter(SCOPE, {
      displayName: 'Counter',
      tapeWidthMm: 80,
      agentDeviceId: null,
      agentPrinterKey: null,
    });
    const request = http.expectOne(`${BASE}/print/printers`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual({
      displayName: 'Counter',
      tapeWidthMm: 80,
      agentDeviceId: null,
      agentPrinterKey: null,
    });
    request.flush({ id: 'p1' });
    expect((await pending).id).toBe('p1');
  });

  it('updates a printer under If-Match with the status in the body', async () => {
    const pending = api.updatePrinter(
      SCOPE,
      'p1',
      { displayName: 'Grill', tapeWidthMm: 58, agentDeviceId: 'a1', agentPrinterKey: 'grill' },
      'RETIRED',
      4,
    );
    const request = http.expectOne(`${BASE}/print/printers/p1`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    expect(request.request.body).toMatchObject({ status: 'RETIRED', agentPrinterKey: 'grill' });
    request.flush({ id: 'p1' });
    await pending;
  });

  it('deletes a route under If-Match, with a key', async () => {
    const pending = api.deleteRoute(SCOPE, 'r1', 2);
    const request = http.expectOne(`${BASE}/print/routes/r1`);
    expect(request.request.method).toBe('DELETE');
    expect(request.request.headers.get('If-Match')).toBe('W/"2"');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush(null, { status: 204, statusText: 'No Content' });
    await pending;
  });

  it('reads the queue with the state filter and no cursor on the first page', async () => {
    const pending = api.jobs(SCOPE, 'DEAD', null);
    const request = http.expectOne(
      (candidate) => candidate.url === `${BASE}/print/jobs` && candidate.method === 'GET',
    );
    expect(request.request.params.get('status')).toBe('DEAD');
    expect(request.request.params.has('cursor')).toBe(false);
    expect(request.request.params.get('limit')).toBe('25');
    request.flush({ items: [], nextCursor: null });
    expect((await pending).nextCursor).toBeNull();
  });

  it('asks for the whole queue with no state parameter when no filter is chosen', async () => {
    const pending = api.jobs(SCOPE, null, 'c1');
    const request = http.expectOne((candidate) => candidate.url === `${BASE}/print/jobs`);
    expect(request.request.params.has('status')).toBe(false);
    expect(request.request.params.get('cursor')).toBe('c1');
    request.flush({ items: [], nextCursor: null });
    await pending;
  });

  it('prints an order document with the reason and the chosen printer, or none', async () => {
    const pending = api.printOrderDocument(SCOPE, 'o1', 'ORDER_SLIP', 'CUSTOMER_REQUEST', null);
    const request = http.expectOne(`${BASE}/orders/o1/print-jobs`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual({
      documentType: 'ORDER_SLIP',
      reasonCode: 'CUSTOMER_REQUEST',
      printerId: null,
    });
    request.flush([{ id: 'j1', status: 'QUEUED' }]);
    expect(await pending).toHaveLength(1);
  });

  it('reprints a finished job with a reason', async () => {
    const pending = api.reprintJob(SCOPE, 'j1', 'PAPER_JAM', 'p2');
    const request = http.expectOne(`${BASE}/print/jobs/j1/reprint`);
    expect(request.request.body).toEqual({ reasonCode: 'PAPER_JAM', printerId: 'p2' });
    request.flush({ id: 'j2' });
    await pending;
  });

  it('reads a preview as a picture, with no key and no version, because it changes nothing', async () => {
    const pending = api.preview(SCOPE, { jobId: 'j1' });
    const request = http.expectOne(`${BASE}/print/documents/preview`);
    expect(request.request.method).toBe('POST');
    expect(request.request.responseType).toBe('blob');
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);
    expect(request.request.body).toEqual({ jobId: 'j1' });
    request.flush(new Blob(['png'], { type: 'image/png' }));
    expect((await pending).size).toBe(3);
  });

  it('reads the picture of a job handed to the browser with a GET, no key and no version', async () => {
    const pending = api.handedOffPicture(SCOPE, 'j1');
    const request = http.expectOne(`${BASE}/print/jobs/j1/picture`);
    expect(request.request.method).toBe('GET');
    expect(request.request.responseType).toBe('blob');
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);
    request.flush(new Blob(['png'], { type: 'image/png' }));
    expect((await pending).size).toBe(3);
  });

  it('saves the receipt profile on the brand under If-Match', async () => {
    const pending = api.saveReceiptProfile(
      { tenantId: 't1', brandId: 'b1' },
      { printLogo: true, footerTexts: { ru: ['Спасибо'] } },
      0,
    );
    const request = http.expectOne(
      `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/print/receipt-profile`,
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"0"');
    request.flush({
      printLogo: true,
      footerTexts: {},
      version: 1,
      updatedBy: null,
      updatedAt: null,
    });
    expect((await pending).version).toBe(1);
  });

  it('approves an agent by the typed code, encoded', async () => {
    const pending = api.approveAgent(SCOPE, 'AB-12', 'Pass');
    const request = http.expectOne(`${BASE}/print/agents/enrolments/AB-12/approve`);
    expect(request.request.body).toEqual({ displayName: 'Pass' });
    request.flush({ deviceId: 'd1' });
    await pending;
  });
});

describe('printRefusal', () => {
  function refusal(reason: unknown, code: string = ApiErrorCode.UNPROCESSABLE_STATE): ApiError {
    return new ApiError(code, 422, { status: 422, code, reason }, null);
  }

  it('names the printing module’s own refusals by their reason', () => {
    expect(printRefusal(refusal('PRINTING_OFF'))).toBe('PRINTING_OFF');
    expect(printRefusal(refusal('NO_ROUTE'))).toBe('NO_ROUTE');
  });

  it('leaves every other failure to the ordinary path', () => {
    expect(printRefusal(refusal('SOMETHING_ELSE'))).toBeNull();
    expect(printRefusal(refusal('NO_ROUTE', ApiErrorCode.VALIDATION_FAILED))).toBeNull();
    expect(printRefusal(new Error('boom'))).toBeNull();
  });
});
