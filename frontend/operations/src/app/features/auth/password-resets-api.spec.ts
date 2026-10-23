// This app's tsconfig carries no "node" types (a browser app has no business
// importing Node built-ins at runtime) - these run only under Vitest's Node
// test runner, never shipped to a browser, the same exception
// activity-log-action-codes-coverage.spec.ts already carries for the identical reason.
// @ts-expect-error node:fs is available at Vitest's Node runtime
import { readFileSync } from 'node:fs';
// @ts-expect-error node:path is available at Vitest's Node runtime
import { dirname, resolve } from 'node:path';
// @ts-expect-error node:url is available at Vitest's Node runtime
import { fileURLToPath } from 'node:url';

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { LOCALES } from '../../core/i18n/i18n';
import { PasswordResetsApi } from './password-resets-api';

const REQUEST_PATH = '/api/v1/operations/auth/password-resets';

interface Baseline {
  readonly components: {
    readonly schemas: Record<
      string,
      {
        readonly properties: Record<
          string,
          { readonly pattern?: string; readonly maxLength?: number }
        >;
      }
    >;
  };
}

/**
 * The platform's checked-in contract for this console (`make openapi-baseline`), read as the file it is.
 * The page's own spec mocks `PasswordResetsApi`, so it can say the page sends `uz-Latn` and still be green
 * while the endpoint answers 400 to that very string - which is how a request DTO that listed
 * `uz|ru|en` shipped. This reads what the endpoint will accept and holds the body this class really
 * puts on the wire against it.
 */
function passwordResetRequestSchema() {
  const baseline = JSON.parse(
    readFileSync(
      resolve(
        dirname(fileURLToPath(import.meta.url)),
        '../../../../../../platform/api/openapi/v1/horecaos-api.operations.json',
      ),
      'utf8',
    ),
  ) as Baseline;
  return baseline.components.schemas['PasswordResetRequest'];
}

describe('PasswordResetsApi against the published contract', () => {
  let api: PasswordResetsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), PasswordResetsApi],
    });
    api = TestBed.inject(PasswordResetsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it.each(LOCALES)(
    'asks for a reset in %s with a locale the endpoint accepts, as it is written',
    async (tag) => {
      const promise = api.request('aziza', tag);
      const request = http.expectOne(`${environment.apiBaseUrl}${REQUEST_PATH}`);
      request.flush(null, { status: 202, statusText: 'Accepted' });
      await promise;

      const body = request.request.body as { login: string; locale: string };
      expect(body).toEqual({ login: 'aziza', locale: tag });

      const locale = passwordResetRequestSchema().properties['locale'];
      expect(locale.pattern, 'the contract says what shape a locale takes').toBeDefined();
      // Jakarta's @Pattern matches the whole string, hence the anchors.
      expect(
        new RegExp(`^(?:${locale.pattern})$`).test(body.locale),
        `${tag} against ${locale.pattern}`,
      ).toBe(true);
      expect(body.locale.length).toBeLessThanOrEqual(locale.maxLength ?? Number.MAX_SAFE_INTEGER);
    },
  );
});
