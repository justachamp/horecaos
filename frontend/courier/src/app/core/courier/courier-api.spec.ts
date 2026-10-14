import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG, TENANT_ID, courierProfile } from '../../../testing/fixtures';
import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from './courier-api';

describe('CourierApi', () => {
  it("reads the courier's own record from the tenant's courier surface, naming no courier id", async () => {
    const get = vi.fn().mockResolvedValue(courierProfile());
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: ApiClient, useValue: { get } },
      ],
    });

    await TestBed.inject(CourierApi).me();

    expect(get).toHaveBeenCalledWith(`/courier/tenants/${TENANT_ID}/me`);
  });
});
