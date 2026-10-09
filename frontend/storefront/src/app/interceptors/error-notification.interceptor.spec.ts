import { TestBed } from '@angular/core/testing';
import { HttpContext, HttpRequest, HttpResponse } from '@angular/common/http';
import type { HttpHandlerFn } from '@angular/common/http';
import { firstValueFrom, of, throwError } from 'rxjs';

import { errorNotificationInterceptor } from './error-notification.interceptor';
import { NotificationService } from '../services/notification.service';
import { TranslateService } from '../services/translate.service';
import { PLATFORM_API_REQUEST, QUIET_FAILURE } from '../core/api/api-client';
import { HorecaOSApiError } from '../core/api/problem-details';

class FakeNotificationService {
  show = vi.fn();
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
}

function failure(): HorecaOSApiError {
  return new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'boom' });
}

function request(quiet: boolean): HttpRequest<unknown> {
  return new HttpRequest('GET', '/api/v1/storefront/content/home', {
    context: new HttpContext().set(PLATFORM_API_REQUEST, true).set(QUIET_FAILURE, quiet),
  });
}

function setUp() {
  const notification = new FakeNotificationService();
  TestBed.configureTestingModule({
    providers: [
      { provide: NotificationService, useValue: notification },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  return { notification };
}

const fails: HttpHandlerFn = () => throwError(() => failure());

describe('errorNotificationInterceptor', () => {
  it('shows a toast for a platform failure the customer asked for', async () => {
    const { notification } = setUp();

    await expect(
      TestBed.runInInjectionContext(() =>
        firstValueFrom(errorNotificationInterceptor(request(false), fails)),
      ),
    ).rejects.toBeInstanceOf(HorecaOSApiError);

    expect(notification.show).toHaveBeenCalledTimes(1);
  });

  it('shows no toast for a QUIET_FAILURE request, and still hands the failure on', async () => {
    const { notification } = setUp();

    await expect(
      TestBed.runInInjectionContext(() =>
        firstValueFrom(errorNotificationInterceptor(request(true), fails)),
      ),
    ).rejects.toBeInstanceOf(HorecaOSApiError);

    expect(notification.show).not.toHaveBeenCalled();
  });

  it('lets a success through untouched', async () => {
    setUp();
    const ok: HttpHandlerFn = () => of(new HttpResponse({ status: 200, body: { a: 1 } }));

    const event = await TestBed.runInInjectionContext(() =>
      firstValueFrom(errorNotificationInterceptor(request(true), ok)),
    );

    expect((event as HttpResponse<unknown>).body).toEqual({ a: 1 });
  });
});
