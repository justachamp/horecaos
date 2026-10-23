import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { PLATFORM_API_REQUEST, QUIET_FAILURE } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';

/** One half-typed line the customer can choose, from a search. It has no point: choosing it asks {@link GeocodingService.resolve}. */
export interface AddressSuggestion {
  readonly title: string;
  readonly subtitle: string | null;
  /** What to submit to {@link GeocodingService.resolve} when this line is chosen. */
  readonly text: string;
}

/** One place an address names. A suggestion for the customer to confirm on a map, never a saved point. */
export interface GeocodedPlace {
  readonly address: string;
  readonly lat: number;
  readonly lng: number;
  /** The platform flagged it (outside the region, or a street with no door): the customer must look at it. */
  readonly lowConfidence: boolean;
}

/**
 * An answer, and whether the platform could give one. `available: false` is the designed outage
 * (ADR 0145 decision 6): no provider is set up, the provider is down, or the customer was told to
 * slow down. It is not "nothing found", and the screen treats the two differently: a field with no
 * suggestions available becomes plain text, a field with none to offer says so.
 */
export interface Lookup<T> {
  readonly available: boolean;
  readonly items: readonly T[];
}

interface Components {
  readonly formatted?: string | null;
}

interface ResultView {
  readonly latitude: number;
  readonly longitude: number;
  readonly components?: Components | null;
  readonly confidence?: string | null;
}

interface Answer {
  readonly status: 'ANSWERED' | 'UNAVAILABLE';
  readonly suggestions?: readonly {
    readonly title: string;
    readonly subtitle?: string | null;
    readonly fullText: string;
  }[];
  readonly results?: readonly ResultView[];
  readonly result?: ResultView | null;
}

/**
 * Turning a typed address into places, and a pin into an address, through the platform (ADR 0145
 * decisions 2 and 4; row `X.4`).
 *
 * **The browser no longer asks a geocoder vendor anything.** This file used to call the vendor's HTTP
 * geocoder directly with a key committed to git, which sent the customer's typed address and pin to a
 * third party outside every control the platform has for personal data (ADR 0029) and could not be
 * rate limited, metered per tenant or switched to another provider without shipping a storefront. The
 * platform now makes the call: the region's box is applied and checked on the way back, the answer is
 * cached for the licence's limit, and the call is metered. What comes back is a **suggestion the
 * customer confirms on a map**: nothing here is saved, and the address book still refuses `GEOCODER`
 * from this surface.
 *
 * **Nothing here shows an error.** A lookup is a convenience on a screen that works without it, so the
 * request is marked {@link QUIET_FAILURE} and every failure is `available: false`; the platform's own
 * outage and rate-limit answers (`200 UNAVAILABLE`, `429`) read the same way. The calls are `POST`s
 * only to keep the address out of the URL (ADR 0029, ADR 0031); they write nothing and take no
 * idempotency key. They carry the customer's bearer token, because the platform authorises a lookup by
 * owning the session.
 */
@Injectable({ providedIn: 'root' })
export class GeocodingService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG);

  /** Type-ahead completions for what the customer has typed so far. */
  async suggest(text: string): Promise<Lookup<AddressSuggestion>> {
    const trimmed = text.trim();
    if (trimmed.length < 2) {
      return { available: true, items: [] };
    }
    const answer = await this.post('suggestions', { text: trimmed });
    if (answer === null) {
      return { available: false, items: [] };
    }
    return {
      available: true,
      items: (answer.suggestions ?? []).map((suggestion) => ({
        title: suggestion.title,
        subtitle: suggestion.subtitle ?? null,
        text: suggestion.fullText,
      })),
    };
  }

  /** The places an address names, best first. */
  async resolve(text: string): Promise<Lookup<GeocodedPlace>> {
    const trimmed = text.trim();
    if (trimmed.length < 2) {
      return { available: true, items: [] };
    }
    const answer = await this.post('resolutions', { text: trimmed });
    if (answer === null) {
      return { available: false, items: [] };
    }
    return { available: true, items: (answer.results ?? []).flatMap((view) => placeOf(view)) };
  }

  /** The address at a pin, or an empty string when the platform has none or cannot say. */
  async describe(lat: number, lng: number): Promise<string> {
    const answer = await this.post('reverse-resolutions', {
      point: { latitude: lat, longitude: lng },
    });
    return answer?.result ? (placeOf(answer.result)[0]?.address ?? '') : '';
  }

  /** `null` when the platform could not answer: unavailable, refused, rate limited, or unreachable. */
  private async post(operation: string, body: object): Promise<Answer | null> {
    try {
      const answer = await firstValueFrom(
        this.http.post<Answer>(
          `${this.config.apiBaseUrl}/storefront/tenants/${this.config.tenantId}` +
            `/brands/${this.config.brandId}/geocode/${operation}`,
          body,
          {
            context: new HttpContext().set(PLATFORM_API_REQUEST, true).set(QUIET_FAILURE, true),
          },
        ),
      );
      return answer.status === 'ANSWERED' ? answer : null;
    } catch {
      return null;
    }
  }
}

function placeOf(view: ResultView): GeocodedPlace[] {
  const address = view.components?.formatted ?? '';
  if (address === '' || !Number.isFinite(view.latitude) || !Number.isFinite(view.longitude)) {
    return [];
  }
  return [
    {
      address,
      lat: view.latitude,
      lng: view.longitude,
      lowConfidence: view.confidence === 'LOW_CONFIDENCE',
    },
  ];
}
