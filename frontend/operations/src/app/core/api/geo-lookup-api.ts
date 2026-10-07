import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from './api-client';
import { BrandScope } from './catalog-paths';
import { GeoLookupKind, geoPaths } from './geo-paths';
import { command } from './idempotency';
import { ApiError, ApiErrorCode } from './problem-details';

/**
 * The address lookups the platform answers through its map provider (ADR 0145 decision 2: "the
 * platform calls the geocoder; the browser draws the map"). Mirrors
 * `OperationsGeocodeController`.
 *
 * **"Unavailable" is an answer here, as it is on the wire.** The server answers `200` with
 * `status: UNAVAILABLE` and a bounded reason for a provider that is not set up, refusing or
 * down; this client adds the two failures that are the caller's own (a rate limit and a request
 * that did not get through) to the same union, so a component has one thing to render and never a
 * `try`/`catch` around a type-ahead field. ADR 0145 decision 6 fixes the order in which a screen
 * degrades, and a component can only follow it if an outage is not an exception.
 *
 * The one failure that is **not** folded in is a refusal the person must act on elsewhere: a
 * missing capability (`INSUFFICIENT_CAPABILITY`) throws, because "you may not do this" must not be
 * shown as "try again later".
 */

export type GeoConfidence = 'HIGH' | 'MEDIUM' | 'LOW_CONFIDENCE';
export type GeoPrecision = 'HOUSE' | 'NEAR_HOUSE' | 'STREET' | 'LOCALITY' | 'UNKNOWN';

/** Why there is no answer. The first three are the server's; the last three are this client's own. */
export type GeoUnavailableReason =
  | 'NOT_CONFIGURED'
  | 'PROVIDER_REFUSED'
  | 'PROVIDER_UNAVAILABLE'
  | 'RATE_LIMITED'
  | 'NO_REGION'
  | 'REQUEST_FAILED';

export type GeoAnswer<T> =
  | { readonly status: 'ANSWERED'; readonly value: T }
  | { readonly status: 'UNAVAILABLE'; readonly reason: GeoUnavailableReason };

export interface GeoSuggestion {
  readonly title: string;
  readonly subtitle: string | null;
  /** What to submit to {@link GeoLookupApi.resolve} when this line is chosen. */
  readonly fullText: string;
  readonly providerReference: string;
}

export interface GeoComponents {
  readonly country: string | null;
  readonly locality: string | null;
  readonly district: string | null;
  readonly street: string | null;
  readonly house: string | null;
  readonly formatted: string;
}

export interface GeoResult {
  readonly latitude: number;
  readonly longitude: number;
  readonly components: GeoComponents;
  readonly providerReference: string;
  readonly confidence: GeoConfidence;
  readonly precision: GeoPrecision;
  readonly resolvedAt: string;
  readonly provider: string;
}

export interface GeoLookupContext {
  readonly scope: BrandScope;
  /** The branch the person is working at, when there is one: it selects the path a branch grant covers. */
  readonly locationId?: string | null;
  /** Optional; the server uses the tenant's only active region when it is absent. */
  readonly regionId?: string | null;
  /** `ru`, `uz-Latn` or `en`. */
  readonly locale?: string;
}

interface Envelope<T> {
  readonly status: 'ANSWERED' | 'UNAVAILABLE';
  readonly reason: GeoUnavailableReason | null;
}

interface SuggestionsBody extends Envelope<GeoSuggestion> {
  readonly suggestions: readonly GeoSuggestion[];
}
interface ResolutionsBody extends Envelope<GeoResult> {
  readonly results: readonly GeoResult[];
}
interface ReverseBody extends Envelope<GeoResult> {
  readonly result: GeoResult | null;
}

@Injectable({ providedIn: 'root' })
export class GeoLookupApi {
  private readonly api = inject(ApiClient);

  async suggest(
    context: GeoLookupContext,
    text: string,
    near?: { readonly latitude: number; readonly longitude: number } | null,
  ): Promise<GeoAnswer<readonly GeoSuggestion[]>> {
    return this.call<SuggestionsBody, readonly GeoSuggestion[]>(
      context,
      'suggestions',
      {
        text,
        ...(near ? { near: { latitude: near.latitude, longitude: near.longitude } } : {}),
        ...this.common(context),
      },
      (body) => body.suggestions,
    );
  }

  async resolve(context: GeoLookupContext, text: string): Promise<GeoAnswer<readonly GeoResult[]>> {
    return this.call<ResolutionsBody, readonly GeoResult[]>(
      context,
      'resolutions',
      { text, ...this.common(context) },
      (body) => body.results,
    );
  }

  async reverse(
    context: GeoLookupContext,
    point: { readonly latitude: number; readonly longitude: number },
  ): Promise<GeoAnswer<GeoResult | null>> {
    return this.call<ReverseBody, GeoResult | null>(
      context,
      'reverse-resolutions',
      {
        point: { latitude: point.latitude, longitude: point.longitude },
        ...this.common(context),
      },
      (body) => body.result,
    );
  }

  private common(context: GeoLookupContext): Record<string, string> {
    return {
      ...(context.regionId ? { regionId: context.regionId } : {}),
      ...(context.locale ? { locale: context.locale } : {}),
    };
  }

  private async call<TBody extends Envelope<unknown>, TValue>(
    context: GeoLookupContext,
    kind: GeoLookupKind,
    body: object,
    read: (response: TBody) => TValue,
  ): Promise<GeoAnswer<TValue>> {
    try {
      const response = await firstValueFrom(
        this.api.post<object, TBody>(
          geoPaths.lookup(context.scope, kind, context.locationId),
          command(body),
        ),
      );
      if (response.status === 'UNAVAILABLE') {
        return { status: 'UNAVAILABLE', reason: response.reason ?? 'PROVIDER_UNAVAILABLE' };
      }
      return { status: 'ANSWERED', value: read(response) };
    } catch (failure) {
      return this.unavailable(failure);
    }
  }

  private unavailable<T>(failure: unknown): GeoAnswer<T> {
    if (failure instanceof ApiError) {
      if (failure.code === ApiErrorCode.INSUFFICIENT_CAPABILITY) {
        throw failure;
      }
      if (failure.code === ApiErrorCode.RATE_LIMIT_EXCEEDED) {
        return { status: 'UNAVAILABLE', reason: 'RATE_LIMITED' };
      }
      if (
        failure.code === ApiErrorCode.UNPROCESSABLE_STATE &&
        failure.problem?.['reason'] === 'REGION_REQUIRED'
      ) {
        return { status: 'UNAVAILABLE', reason: 'NO_REGION' };
      }
    }
    return { status: 'UNAVAILABLE', reason: 'REQUEST_FAILED' };
  }
}
