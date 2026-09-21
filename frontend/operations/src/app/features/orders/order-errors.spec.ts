import { describe, expect, it } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { MessageKey } from '../../core/i18n/messages.en';
import { accessRefusal, describeApiError } from './order-errors';

/** Every caller passes `I18n.t`; this stands in for it without the DI overhead. */
const translate = (key: MessageKey, values?: Readonly<Record<string, string | number>>): string => {
  const templates: Partial<Record<MessageKey, string>> = {
    'error.unknown': 'Something went wrong. Reference {correlationId}.',
    'error.unknown.noReference': 'Something went wrong.',
    'error.detailed': '{detail} Reference {correlationId}.',
    'error.detailed.noReference': '{detail}',
    'error.RESOURCE_NOT_FOUND': 'That no longer exists.',
  };
  let template = templates[key] ?? key;
  for (const [name, value] of Object.entries(values ?? {})) {
    template = template.replaceAll(`{${name}}`, String(value));
  }
  return template;
};

/**
 * The console showed only "Something went wrong. Reference <id>" for a 400
 * INVALID_REQUEST naming an unknown provider environment (pre-production,
 * 2026-09-19 and 2026-09-21) — the server's own `detail` never reached the
 * operator. These prove the split this fix draws: a client error (400-499)
 * with no friendlier mapping shows that `detail`; a server error (500+)
 * never does, whatever `detail` it carries.
 */
describe('describeApiError', () => {
  it('renders a mapped code by its own translated sentence, ignoring any detail it carries', () => {
    const error = new ApiError(
      ApiErrorCode.RESOURCE_NOT_FOUND,
      404,
      { status: 404, detail: 'No installation abc-123' },
      'corr-1',
    );

    expect(describeApiError(error, translate)).toBe('That no longer exists.');
  });

  it('shows the server detail and the reference for an unmapped 400 (INVALID_REQUEST)', () => {
    const error = new ApiError(
      ApiErrorCode.INVALID_REQUEST,
      400,
      { status: 400, detail: 'Unknown provider environment for this category: telegram-typo' },
      'corr-2',
    );

    expect(describeApiError(error, translate)).toBe(
      'Unknown provider environment for this category: telegram-typo Reference corr-2.',
    );
  });

  it('shows just the detail, with no dangling reference, when the server sent no correlation id', () => {
    const error = new ApiError(
      ApiErrorCode.VALIDATION_FAILED,
      400,
      { status: 400, detail: 'displayName must not be blank' },
      null,
    );

    expect(describeApiError(error, translate)).toBe('displayName must not be blank');
  });

  it('shows the detail for an unmapped 409 conflict', () => {
    const error = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, detail: 'This installation is already ACTIVE' },
      'corr-3',
    );

    expect(describeApiError(error, translate)).toBe(
      'This installation is already ACTIVE Reference corr-3.',
    );
  });

  it('shows the detail for an unmapped 422 unprocessable-state response', () => {
    const error = new ApiError(
      'UNPROCESSABLE_STATE',
      422,
      { status: 422, detail: 'Collection has stopped for this shift' },
      'corr-4',
    );

    expect(describeApiError(error, translate)).toBe(
      'Collection has stopped for this shift Reference corr-4.',
    );
  });

  it('stays generic for a 500, even though the server sent a detail', () => {
    const error = new ApiError(
      ApiErrorCode.INTERNAL_ERROR,
      500,
      { status: 500, detail: 'NullPointerException at OrderService.java:412' },
      'corr-5',
    );

    expect(describeApiError(error, translate)).toBe('Something went wrong. Reference corr-5.');
  });

  it('stays generic for an unmapped 4xx with no detail at all', () => {
    const error = new ApiError(ApiErrorCode.RESOURCE_CONFLICT, 409, { status: 409 }, 'corr-6');

    expect(describeApiError(error, translate)).toBe('Something went wrong. Reference corr-6.');
  });

  it('stays generic for an unmapped 4xx whose detail is blank', () => {
    const error = new ApiError(
      ApiErrorCode.VALIDATION_FAILED,
      400,
      { status: 400, detail: '   ' },
      'corr-7',
    );

    expect(describeApiError(error, translate)).toBe('Something went wrong. Reference corr-7.');
  });
});

describe('accessRefusal', () => {
  it('reads a missing-capability refusal as denied, naming the capability', () => {
    const error = new ApiError(
      ApiErrorCode.INSUFFICIENT_CAPABILITY,
      403,
      { status: 403, requiredCapability: 'IAM_GRANT_MANAGE', requiredScope: 'TENANT' },
      'corr-1',
    );

    expect(accessRefusal(error)).toEqual({ kind: 'denied', name: 'IAM_GRANT_MANAGE' });
  });

  it('reads a missing-entitlement refusal as locked, naming the entitlement', () => {
    const error = new ApiError(
      ApiErrorCode.ENTITLEMENT_REQUIRED,
      403,
      { status: 403, entitlementKey: 'telegram.broadcasts.enabled' },
      'corr-2',
    );

    expect(accessRefusal(error)).toEqual({ kind: 'locked', name: 'telegram.broadcasts.enabled' });
  });

  it('names nothing when the server refused without naming one', () => {
    const error = new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, { status: 403 }, null);

    expect(accessRefusal(error)).toEqual({ kind: 'denied', name: null });
  });

  it('is null for a refusal that is neither of the two', () => {
    const error = new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, { status: 404 }, null);

    expect(accessRefusal(error)).toBeNull();
  });
});
