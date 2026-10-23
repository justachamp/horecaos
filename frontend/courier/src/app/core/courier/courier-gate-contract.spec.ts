import { TestBed } from '@angular/core/testing';

import courierBaseline from '../../../../../../platform/api/openapi/v1/horecaos-api.courier.json';
import { TEST_CONFIG, TENANT_ID } from '../../../testing/fixtures';
import { delivery } from '../../../testing/work-fixtures';
import { ApiClient } from '../api/api-client';
import { ApiError } from '../api/problem-details';
import { APP_CONFIG } from '../config/app-config';
import { classifyStepFailure, describeSuccess } from '../deliveries/step-outcome';
import { CourierApi } from './courier-api';
import type { BranchRef } from './courier-shift';

/**
 * The courier client against the platform's pickup and handover gate (ADR 0042, ADR 0178), the seam the
 * batch-21 integration closed between the delivery-maps wave, which built the server side, and the courier
 * wave, which built this client against the contract the two had agreed in words.
 *
 * Nothing here retypes the server. The paths, the body's shape and the answer's `checks` field names are read
 * from `platform/api/openapi/v1/horecaos-api.courier.json`, which `OpenApiContractTests` holds to the running
 * controllers, so a rename on either side fails a build instead of a courier's phone. The wire shape of the two
 * outcomes (`409 GATE_FAILED` whose detail is the failed kinds, `200` with `checks`) is pinned on the server by
 * `CourierDeliveryEndpointTests.aHardLocationRefusesWithGateFailed` and `theSoftDefault...`; the fixtures below
 * are written the way those tests read the response.
 */

interface BaselineOperation {
  readonly operationId: string;
  readonly requestBody: {
    readonly content: Record<string, { readonly schema: { readonly $ref: string } }>;
  };
  readonly responses: Record<string, unknown>;
}

interface BaselineSchema {
  readonly properties?: Record<
    string,
    { readonly $ref?: string; readonly items?: { $ref: string } }
  >;
}

const BASELINE = courierBaseline as unknown as {
  readonly paths: Record<string, Record<string, BaselineOperation>>;
  readonly components: { readonly schemas: Record<string, BaselineSchema> };
};

const WHERE: BranchRef = {
  brandId: '018fe100-4000-7000-8000-0000000000b1',
  locationId: '018fe100-4000-7000-8000-0000000000c1',
};
const POSITION = { latitude: 41.31, longitude: 69.24, accuracyMeters: 12 };

function schemaOf(reference: string): BaselineSchema {
  return BASELINE.components.schemas[reference.replace('#/components/schemas/', '')];
}

/** The baseline path a client path names: `/api/v1` in front, and its ids as the baseline's `{parameter}`s. */
function baselinePathOf(clientPath: string): string {
  return `/api/v1${clientPath}`
    .replace(TENANT_ID, '{tenantId}')
    .replace(WHERE.brandId, '{brandId}')
    .replace(WHERE.locationId, '{locationId}')
    .replace('/ship-1/', '/{shipmentId}/');
}

describe('the pickup and handover gate: client against the platform contract', () => {
  let post: ReturnType<typeof vi.fn>;
  let api: CourierApi;

  beforeEach(() => {
    post = vi.fn().mockResolvedValue(null);
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: ApiClient, useValue: { get: vi.fn(), post } },
      ],
    });
    api = TestBed.inject(CourierApi);
  });

  const held = delivery({ shipmentId: 'ship-1', version: 4 });

  for (const [step, operationId, call] of [
    ['pickup', 'courierPickUpDelivery', 'pickUp'],
    ['handover', 'courierHandOverDelivery', 'handOver'],
  ] as const) {
    describe(`the ${step}`, () => {
      it('posts to the path the platform publishes, with the body shape it declares', async () => {
        await api[call](WHERE, held, POSITION, 'k1');

        const [path, init] = post.mock.calls[0] as [string, { body: Record<string, unknown> }];
        const operation = BASELINE.paths[baselinePathOf(path)]?.['post'];

        expect(operation, `${path} is not a published POST`).toBeDefined();
        expect(operation.operationId).toBe(operationId);
        const request = schemaOf(operation.requestBody.content['application/json'].schema.$ref);
        expect(Object.keys(init.body)).toEqual(['position']);
        expect(Object.keys(request.properties ?? {})).toContain('position');
        const position = schemaOf(request.properties?.['position'].$ref ?? '');
        expect(
          Object.keys(position.properties ?? {}).sort(),
          'the position the phone sends carries exactly the fields the platform reads',
        ).toEqual(Object.keys(init.body['position'] as object).sort());
      });

      it('sends an empty body, not a missing one, when the phone has no position', async () => {
        await api[call](WHERE, held, null, 'k1');

        const [, init] = post.mock.calls[0] as [string, { body: Record<string, unknown> }];
        expect(init.body).toEqual({});
      });
    });
  }

  it('reads the checks the platform answers with by the field names it declares', () => {
    const delivered = schemaOf('#/components/schemas/CourierDeliveryResponse');
    const check = schemaOf(delivered.properties?.['checks'].items?.$ref ?? '');

    expect(Object.keys(check.properties ?? {}).sort()).toEqual(['kind', 'outcome']);

    // A SOFT location: the step happened and the answer says what could not be confirmed.
    const soft = {
      ...delivery({ status: 'PICKED_UP' }),
      checks: [{ kind: 'GEO', outcome: 'FAILED' }],
    };
    const outcome = describeSuccess(soft);
    expect(outcome.kind).toBe('done');
    expect(outcome.unconfirmed).toEqual(['GEO']);
    expect(outcome.checks).toEqual([{ kind: 'GEO', outcome: 'FAILED' }]);
  });

  it('branches a HARD refusal on 409 GATE_FAILED and the kinds the platform lists in the detail', () => {
    // As the platform writes it (ApiException GATE_FAILED): the detail is the failed kinds joined by ", " and
    // nothing else, because this client names them to the courier from it.
    const refusal = new ApiError({ status: 409, code: 'GATE_FAILED', detail: 'GEO, PAYMENT' });

    expect(classifyStepFailure(refusal)).toEqual({
      kind: 'gate-failed',
      failed: ['GEO', 'PAYMENT'],
    });
  });
});
