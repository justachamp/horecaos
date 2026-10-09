import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { BrandScope } from '../../../core/api/catalog-paths';
import { ContentApi, ContentDraftBody } from './content-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const ROOT = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/content`;

const BODY: ContentDraftBody = {
  document: {
    schemaVersion: 1,
    texts: { ru: { title: 'Скидка' } },
    media: {},
    link: { type: 'NONE' },
  },
  channelCodes: [],
  startsAt: null,
  endsAt: null,
  promotionId: null,
  locationId: null,
};

describe('ContentApi (ADR 0157, Staff API: ETag in If-Match, Idempotency-Key on a POST)', () => {
  let api: ContentApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ContentApi],
    });
    api = TestBed.inject(ContentApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const expectOne = (
    method: string,
    path: string,
  ): ReturnType<HttpTestingController['expectOne']> =>
    http.expectOne((r: HttpRequest<unknown>) => r.method === method && r.url === `${ROOT}${path}`);

  it('lists with only the filters that were given, never the string "undefined"', async () => {
    const promise = api.list(SCOPE, { placement: 'HOME_CAROUSEL', limit: 200 });
    const request = http.expectOne((r) => r.url === `${ROOT}/items`);

    expect(request.request.params.get('placement')).toBe('HOME_CAROUSEL');
    expect(request.request.params.get('limit')).toBe('200');
    expect(request.request.params.keys().sort()).toEqual(['limit', 'placement']);
    request.flush({ items: [], nextCursor: null, orderToken: 't' });
    expect((await promise).orderToken).toBe('t');
  });

  it('creates an item with an Idempotency-Key and every optional field present as null, not omitted', async () => {
    const promise = api.create(SCOPE, {
      kind: 'BANNER',
      placement: 'HOME_CAROUSEL',
      internalName: 'Spring',
      slug: null,
      ...BODY,
    });
    const request = expectOne('POST', '/items');

    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.headers.has('If-Match')).toBe(false);
    expect(request.request.body).toEqual({
      kind: 'BANNER',
      placement: 'HOME_CAROUSEL',
      internalName: 'Spring',
      slug: null,
      document: BODY.document,
      channelCodes: [],
      startsAt: null,
      endsAt: null,
      promotionId: null,
      locationId: null,
    });
    request.flush({ id: 'i1', version: 1 });
    expect((await promise).id).toBe('i1');
  });

  it('replaces the draft with the item’s version in If-Match, as W/"n"', async () => {
    const promise = api.replaceDraft(SCOPE, 'i1', 7, 'Spring', BODY);
    const request = expectOne('PUT', '/items/i1/draft');

    expect(request.request.headers.get('If-Match')).toBe('W/"7"');
    expect(request.request.body).toMatchObject({ internalName: 'Spring', document: BODY.document });
    request.flush({ id: 'i1', version: 8 });
    await promise;
  });

  it('discards a draft with DELETE and If-Match', async () => {
    const promise = api.discardDraft(SCOPE, 'i1', 3);
    const request = expectOne('DELETE', '/items/i1/draft');

    expect(request.request.headers.get('If-Match')).toBe('W/"3"');
    request.flush({ id: 'i1', version: 4 });
    expect((await promise).version).toBe(4);
  });

  it.each([
    ['publish', '/items/i1/publications'],
    ['withdraw', '/items/i1/withdrawals'],
    ['archive', '/items/i1/archives'],
  ] as const)(
    '%s is a POST with an Idempotency-Key, If-Match and a body that is an object',
    async (act, path) => {
      const promise = api[act](SCOPE, 'i1', 5);
      const request = expectOne('POST', path);

      expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
      expect(request.request.headers.get('If-Match')).toBe('W/"5"');
      expect(request.request.body).toEqual({});
      request.flush({ id: 'i1', version: 6 });
      await promise;
    },
  );

  it('carries the reason when one is given', async () => {
    const promise = api.publish(SCOPE, 'i1', 5, 'new campaign');
    const request = expectOne('POST', '/items/i1/publications');

    expect(request.request.body).toEqual({ reason: 'new campaign' });
    request.flush({ id: 'i1', version: 6 });
    await promise;
  });

  it('restores a revision into the draft with If-Match', async () => {
    const promise = api.republish(SCOPE, 'i1', 2, 9);
    const request = expectOne('POST', '/items/i1/revisions/2/republications');

    expect(request.request.headers.get('If-Match')).toBe('W/"9"');
    request.flush({ id: 'i1', version: 10 });
    await promise;
  });

  it('orders a placement with the whole id list and the token, and no If-Match', async () => {
    const promise = api.order(SCOPE, 'HOME_CAROUSEL', ['a', 'b'], 'tok');
    const request = expectOne('PUT', '/placements/HOME_CAROUSEL/order');

    expect(request.request.body).toEqual({ ids: ['a', 'b'], orderToken: 'tok' });
    expect(request.request.headers.has('If-Match')).toBe(false);
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush({ ids: ['a', 'b'], orderToken: 'tok2' });
    expect((await promise).orderToken).toBe('tok2');
  });

  it('previews for a channel with the optional language and day left out when absent', async () => {
    const promise = api.preview(SCOPE, 'web', null, null);
    const request = http.expectOne((r) => r.url === `${ROOT}/preview`);

    expect(request.request.params.keys()).toEqual(['channel']);
    expect(request.request.params.get('channel')).toBe('web');
    request.flush({ locale: 'ru', carousel: [], groups: [] });
    await promise;
  });

  it('reads revisions, one revision, health and stats from their paths', async () => {
    const reads = [
      api.revisions(SCOPE, 'i1'),
      api.revision(SCOPE, 'i1', 3),
      api.health(SCOPE),
      api.stats(SCOPE, 'i1'),
    ];
    expectOne('GET', '/items/i1/revisions').flush([]);
    expectOne('GET', '/items/i1/revisions/3').flush({ revisionNo: 3 });
    expectOne('GET', '/health').flush([]);
    expectOne('GET', '/items/i1/stats').flush({ rows: [] });
    await Promise.all(reads);
  });

  it('adds an allowed host with an Idempotency-Key and removes one with DELETE', async () => {
    const add = api.addAllowedHost(SCOPE, 'shop.example.uz', 'campaign');
    const added = expectOne('POST', '/allowed-hosts');
    expect(added.request.body).toEqual({ host: 'shop.example.uz', reason: 'campaign' });
    expect(added.request.headers.get('Idempotency-Key')).toBeTruthy();
    added.flush({ host: 'shop.example.uz' });
    await add;

    const remove = api.removeAllowedHost(SCOPE, 'shop.example.uz');
    expectOne('DELETE', '/allowed-hosts/shop.example.uz').flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    await remove;
  });

  it('sends the policies with If-Match, every flag a boolean, and the reason only when given', async () => {
    const publishing = api.setPublishingPolicy(SCOPE, 2, true);
    const first = expectOne('PUT', '/policies/publishing');
    expect(first.request.headers.get('If-Match')).toBe('W/"2"');
    expect(first.request.body).toEqual({ requireAllBrandLocales: true });
    first.flush({});
    await publishing;

    const editorial = api.setEditorialPolicy(
      SCOPE,
      5,
      { news: true, gallery: false, recipes: false, vacancies: true },
      'launch',
    );
    const second = expectOne('PUT', '/policies/editorial');
    expect(second.request.headers.get('If-Match')).toBe('W/"5"');
    expect(second.request.body).toEqual({
      news: true,
      gallery: false,
      recipes: false,
      vacancies: true,
      reason: 'launch',
    });
    second.flush({});
    await editorial;
  });
});
