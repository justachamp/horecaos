import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { CatalogApi } from './catalog-api';
import { PhysicalAttributesRequest } from './physical-attributes';

const SCOPE = { tenantId: 't1', brandId: 'b1' };
const URL = `${environment.apiBaseUrl}/api/v1/control-plane/tenants/t1/brands/b1/catalog/variants/v1/physical-attributes`;

const REQUEST: PhysicalAttributesRequest = {
  netWeightGrams: 1200,
  netVolumeMillilitres: null,
  catchweight: true,
  catchweightQuantumGrams: 100,
  catchweightNominalGrams: null,
  splittable: false,
  portionSize: null,
  caloriesKcalPer100: 215.5,
  proteinGramsPer100: null,
  fatGramsPer100: null,
  carbohydratesGramsPer100: null,
};

describe('CatalogApi physical attributes (ADR 0137)', () => {
  let api: CatalogApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), CatalogApi],
    });
    api = TestBed.inject(CatalogApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('reads the set from the control-plane catalog path the controller maps', async () => {
    const read = firstValueFrom(api.physicalAttributes(SCOPE, 'v1'));
    const request = http.expectOne(URL);
    expect(request.request.method).toBe('GET');
    request.flush({ catchweight: false, splittable: false, version: 0 });

    expect(await read).toEqual({ catchweight: false, splittable: false, version: 0 });
  });

  it('writes the whole set under If-Match and an Idempotency-Key, booleans always present', async () => {
    const write = firstValueFrom(api.setPhysicalAttributes(SCOPE, 'v1', REQUEST, 3));
    const request = http.expectOne(URL);

    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"3"');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual(REQUEST);
    expect(Object.keys(request.request.body)).toEqual(
      expect.arrayContaining(['catchweight', 'splittable']),
    );
    request.flush({ ...REQUEST, version: 4 });

    expect((await write).version).toBe(4);
  });

  it('quotes version 0 for a variant that has no row yet', () => {
    void firstValueFrom(api.setPhysicalAttributes(SCOPE, 'v1', REQUEST, 0));
    const request = http.expectOne(URL);

    expect(request.request.headers.get('If-Match')).toBe('W/"0"');
    request.flush({ ...REQUEST, version: 1 });
  });
});
