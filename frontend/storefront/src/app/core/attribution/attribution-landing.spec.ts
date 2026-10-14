import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';
import { AttributionLanding, refToken, startAppToken } from './attribution-landing';
import { Session } from '../auth/session';
import {
  bearerInterceptor,
  conventionsInterceptor,
  expiredSessionInterceptor,
  problemDetailsInterceptor,
} from '../api/api.interceptors';
import { errorNotificationInterceptor } from '../../interceptors/error-notification.interceptor';
import { NotificationService } from '../../services/notification.service';
import { TranslateService } from '../../services/translate.service';

class FakeApiClient {
  report = vi.fn().mockResolvedValue(undefined);
}

afterEach(() => sessionStorage.clear());

function setUp(api = new FakeApiClient()): { landing: AttributionLanding; api: FakeApiClient } {
  sessionStorage.clear();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      {
        provide: APP_CONFIG,
        useValue: { tenantId: 'tenant-1', brandId: 'brand-1', apiBaseUrl: '/api/v1' },
      },
    ],
  });
  return { landing: TestBed.inject(AttributionLanding), api };
}

describe('refToken', () => {
  it('reads a ref that has the shape of a token', () => {
    expect(refToken('?ref=abcd1234')).toBe('abcd1234');
    expect(refToken('?utm=x&ref=Ab_-9zZ')).toBe('Ab_-9zZ');
  });

  it('ignores a missing, short, long or oddly-shaped ref', () => {
    expect(refToken('')).toBeNull();
    expect(refToken('?ref=')).toBeNull();
    expect(refToken('?ref=abc')).toBeNull();
    expect(refToken('?ref=' + 'x'.repeat(65))).toBeNull();
    expect(refToken('?ref=has space')).toBeNull();
    expect(refToken('?ref=semi%3Bcolon')).toBeNull();
  });
});

describe('startAppToken', () => {
  it('reads a Mini App start parameter that has the shape of a token', () => {
    expect(startAppToken('abcd1234')).toBe('abcd1234');
    expect(startAppToken('Ab_-9zZ')).toBe('Ab_-9zZ');
  });

  it('ignores no parameter, and a parameter that is a layout or anything else', () => {
    expect(startAppToken(null)).toBeNull();
    expect(startAppToken('')).toBeNull();
    expect(startAppToken('mode=compact')).toBeNull();
    expect(startAppToken('abc')).toBeNull();
    expect(startAppToken('x'.repeat(65))).toBeNull();
    expect(startAppToken('a b c d')).toBeNull();
  });
});

describe('AttributionLanding from the Telegram Mini App (ADR 0183)', () => {
  it('counts one click when the Mini App is launched from a startapp link, at the same endpoint', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('', 'liveToken01');

    expect(api.report).toHaveBeenCalledTimes(1);
    expect(api.report).toHaveBeenCalledWith(
      '/storefront/tenants/tenant-1/brands/brand-1/attribution-links/liveToken01/clicks',
    );
  });

  it('sends nothing for a Mini App opened from the menu button, or launched with a layout parameter', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('', null);
    await landing.recordLanding('', 'mode=compact');
    await landing.recordLanding('?mode=compact', 'mode=fullscreen');

    expect(api.report).not.toHaveBeenCalled();
  });

  it('counts a relaunch of the same link once per tab session, and a different link separately', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('', 'liveToken01');
    await landing.recordLanding('', 'liveToken01');
    await landing.recordLanding('', 'otherToken2');

    expect(api.report).toHaveBeenCalledTimes(2);
  });

  it('prefers the ?ref= of a web landing, and never counts both on one landing', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=webToken01', 'miniToken01');

    expect(api.report).toHaveBeenCalledTimes(1);
    expect(api.report).toHaveBeenCalledWith(
      expect.stringContaining('/attribution-links/webToken01/clicks'),
    );
  });
});

describe('AttributionLanding', () => {
  it('counts one click on a ?ref= landing, anonymously, at the brand and token in the path', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=liveToken01');

    expect(api.report).toHaveBeenCalledTimes(1);
    expect(api.report).toHaveBeenCalledWith(
      '/storefront/tenants/tenant-1/brands/brand-1/attribution-links/liveToken01/clicks',
    );
  });

  it('sends nothing at all when there is no ref, so an ordinary visit is measured by nothing', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('');
    await landing.recordLanding('?other=value');

    expect(api.report).not.toHaveBeenCalled();
  });

  it('sends nothing for a ref that is not shaped like a token, so a crafted URL cannot make a request', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=../../admin');
    await landing.recordLanding('?ref=ab');

    expect(api.report).not.toHaveBeenCalled();
  });

  it('counts a reload of the same landing once, per tab session', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=liveToken01');
    await landing.recordLanding('?ref=liveToken01');

    expect(api.report).toHaveBeenCalledTimes(1);
  });

  it('counts a different token separately', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=liveToken01');
    await landing.recordLanding('?ref=otherToken2');

    expect(api.report).toHaveBeenCalledTimes(2);
  });

  it('swallows every failure: a refused or rate-limited counter never reaches the customer', async () => {
    const api = new FakeApiClient();
    api.report.mockRejectedValue(new Error('429'));
    const { landing } = setUp(api);

    await expect(landing.recordLanding('?ref=liveToken01')).resolves.toBeUndefined();
  });
});

describe('AttributionLanding through the real interceptor chain', () => {
  const COUNT_URL =
    '/api/v1/storefront/tenants/tenant-1/brands/brand-1/attribution-links/liveToken01/clicks';

  function setUpTheRealStack(): {
    landing: AttributionLanding;
    http: HttpTestingController;
    toasts: ReturnType<typeof vi.fn>;
  } {
    sessionStorage.clear();
    TestBed.resetTestingModule();
    const toasts = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([
            errorNotificationInterceptor,
            expiredSessionInterceptor,
            conventionsInterceptor,
            bearerInterceptor,
            problemDetailsInterceptor,
          ]),
        ),
        provideHttpClientTesting(),
        {
          provide: APP_CONFIG,
          useValue: { tenantId: 'tenant-1', brandId: 'brand-1', apiBaseUrl: '/api/v1' },
        },
        { provide: NotificationService, useValue: { show: toasts } },
        { provide: TranslateService, useValue: { get: (key: string) => key } },
        {
          provide: Session,
          useValue: { accessToken: () => 'a-signed-in-token', expire: vi.fn() },
        },
      ],
    });
    return {
      landing: TestBed.inject(AttributionLanding),
      http: TestBed.inject(HttpTestingController),
      toasts,
    };
  }

  it('sends no Idempotency-Key and no bearer: nothing about the visitor travels with the count', async () => {
    const { landing, http } = setUpTheRealStack();

    const counted = landing.recordLanding('?ref=liveToken01');
    const request = http.expectOne(COUNT_URL);
    request.flush(null, { status: 202, statusText: 'Accepted' });
    await counted;

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);
    expect(request.request.headers.has('Authorization')).toBe(false);
    http.verify();
  });

  it.each([429, 500])(
    'shows the customer nothing when the count fails with %i, and the landing completes',
    async (status) => {
      const { landing, http, toasts } = setUpTheRealStack();

      const counted = landing.recordLanding('?ref=liveToken01');
      http
        .expectOne(COUNT_URL)
        .flush({ code: 'RATE_LIMITED', detail: 'slow down' }, { status, statusText: 'Failed' });

      await expect(counted).resolves.toBeUndefined();
      expect(toasts).not.toHaveBeenCalled();
      http.verify();
    },
  );

  it('shows the customer nothing when the network fails, and the landing completes', async () => {
    const { landing, http, toasts } = setUpTheRealStack();

    const counted = landing.recordLanding('?ref=liveToken01');
    http.expectOne(COUNT_URL).error(new ProgressEvent('error'));

    await expect(counted).resolves.toBeUndefined();
    expect(toasts).not.toHaveBeenCalled();
    http.verify();
  });
});
