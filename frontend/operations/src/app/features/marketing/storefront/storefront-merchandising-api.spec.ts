import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { BrandScope } from '../../../core/api/catalog-paths';
import { StorefrontMerchandisingApi, SwitcherEntry } from './storefront-merchandising-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const SWITCHER = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/content/brand-switcher`;
const BRANCHES = `${environment.apiBaseUrl}/api/v1/operations/tenants/t1/brands/b1/storefront-branch-order`;

const ENTRY: SwitcherEntry = {
  id: 'e1',
  targetBrandId: 'b2',
  targetBrandName: 'Pizza',
  channelId: 'c2',
  channelCode: 'PIZZA_WEB',
  hostname: 'pizza.example.uz',
  sortKey: 10,
  state: 'ACTIVE',
  shown: true,
  version: 6,
};

describe('StorefrontMerchandisingApi (row 6.8: If-Match on a change, Idempotency-Key on every write)', () => {
  let api: StorefrontMerchandisingApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), StorefrontMerchandisingApi],
    });
    api = TestBed.inject(StorefrontMerchandisingApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const expectOne = (method: string, url: string): ReturnType<HttpTestingController['expectOne']> =>
    http.expectOne((r: HttpRequest<unknown>) => r.method === method && r.url === url);

  it('reads the entries and the candidates from the brand-switcher paths', async () => {
    const entries = api.switcherEntries(SCOPE);
    expectOne('GET', `${SWITCHER}/entries`).flush([ENTRY]);
    expect(await entries).toEqual([ENTRY]);

    const candidates = api.switcherCandidates(SCOPE);
    expectOne('GET', `${SWITCHER}/candidates`).flush([]);
    expect(await candidates).toEqual([]);
  });

  it('adds an entry naming the brand and the website channel, with an Idempotency-Key and no If-Match', async () => {
    const promise = api.addSwitcherEntry(SCOPE, 'b2', 'c2');
    const request = expectOne('POST', `${SWITCHER}/entries`);

    expect(request.request.body).toEqual({ targetBrandId: 'b2', channelId: 'c2' });
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.headers.has('If-Match')).toBe(false);
    request.flush(ENTRY);
    await promise;
  });

  it('shows or hides an entry with the version it was read at', async () => {
    const promise = api.setSwitcherEntryState(SCOPE, ENTRY, 'HIDDEN');
    const request = expectOne('PUT', `${SWITCHER}/entries/e1`);

    expect(request.request.headers.get('If-Match')).toBe('W/"6"');
    expect(request.request.body).toEqual({ state: 'HIDDEN' });
    request.flush(ENTRY);
    await promise;
  });

  it('removes an entry, and orders the entries by naming all of them', async () => {
    const removal = api.removeSwitcherEntry(SCOPE, 'e1');
    const deleted = expectOne('DELETE', `${SWITCHER}/entries/e1`);
    expect(deleted.request.headers.get('Idempotency-Key')).toBeTruthy();
    deleted.flush(null);
    await removal;

    const order = api.reorderSwitcher(SCOPE, ['e2', 'e1']);
    const put = expectOne('PUT', `${SWITCHER}/order`);
    expect(put.request.body).toEqual({ ids: ['e2', 'e1'] });
    expect(put.request.headers.has('If-Match')).toBe(false);
    put.flush([]);
    await order;
  });

  it('reads the branches and saves the whole order as one list of ids', async () => {
    const read = api.branches(SCOPE);
    expectOne('GET', BRANCHES).flush([]);
    expect(await read).toEqual([]);

    const save = api.setBranchOrder(SCOPE, ['l2', 'l1']);
    const request = expectOne('PUT', BRANCHES);
    expect(request.request.body).toEqual({ locationIds: ['l2', 'l1'] });
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush([]);
    await save;
  });
});
