import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { APP_CONFIG } from '../core/config/app-config';

/** One place the customer can pick, from a search. */
export interface GeocodeSuggestion {
  readonly address: string;
  readonly lat: number;
  readonly lng: number;
}

/**
 * Turning a point into an address and back, against Yandex directly.
 *
 * The legacy backend proxied this at `/customers/addresses/action/geocode` and
 * `/action/reverse-geocode`. **The platform has no equivalent and is not going
 * to grow one by accident**: `geocode` appears in the codebase only as
 * `CoordinateSource.GEOCODER`, an enum value recording who produced a point, and
 * there is no endpoint behind it. So this calls the geocoder the application
 * already ships a key for.
 *
 * Two things follow from calling a third party from the browser, and both are
 * already handled rather than assumed.
 *
 * **No platform token goes to Yandex.** `bearerInterceptor` attaches one only to
 * requests marked `PLATFORM_API_REQUEST`, which `ApiClient` alone sets. That
 * gate is the reason this file can exist without leaking a credential to
 * somebody else's host — a URL-prefix check on the API base would be one
 * misconfiguration away from doing exactly that, silently.
 *
 * **The customer's coordinate goes to Yandex, because that is the question.**
 * It is not stored here, not logged, and not put anywhere but the query of this
 * one call. What comes back is text the customer then chooses to save.
 *
 * The key is a browser key and is public by construction — it travels in every
 * request the map component already makes. It is not an ADR 0028 secret and must
 * not be treated as one; restricting it is a referrer setting in the Yandex
 * console, not something this code can enforce.
 */
@Injectable({ providedIn: 'root' })
export class GeocodingService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG);

  /** The address at a point, or an empty string when the geocoder has none. */
  async describe(lat: number, lng: number): Promise<string> {
    // Yandex takes longitude first. Reversing these silently returns an address
    // in the wrong hemisphere rather than an error, which is the kind of bug
    // that reaches a courier.
    const found = await this.query(`${lng},${lat}`, 1);
    return found[0]?.address ?? '';
  }

  /** Places matching what the customer typed, best first. */
  async search(text: string): Promise<GeocodeSuggestion[]> {
    const trimmed = text.trim();
    return trimmed ? this.query(trimmed, 10) : [];
  }

  private async query(geocode: string, results: number): Promise<GeocodeSuggestion[]> {
    const response = await firstValueFrom(
      this.http.get<YandexGeocodeResponse>('https://geocode-maps.yandex.ru/1.x/', {
        params: {
          apikey: this.config.yandexMapsApiKey,
          geocode,
          format: 'json',
          results,
          lang: 'ru_RU',
        },
        // A bare context, so nothing marks this as a platform request and no
        // bearer is attached. Stated rather than left to the default, because
        // the default is what a later edit would quietly change.
        context: new HttpContext(),
      }),
    );

    const members = response?.response?.GeoObjectCollection?.featureMember ?? [];
    return members
      .map((member) => {
        const object = member.GeoObject;
        // "longitude latitude", space separated, in that order.
        const [lng, lat] = (object?.Point?.pos ?? '').split(' ').map(Number);
        const address =
          object?.metaDataProperty?.GeocoderMetaData?.text ?? object?.name ?? '';
        return { address, lat, lng };
      })
      .filter(
        (suggestion) =>
          suggestion.address !== '' &&
          Number.isFinite(suggestion.lat) &&
          Number.isFinite(suggestion.lng),
      );
  }
}

/** Only the parts of Yandex's response this reads. */
interface YandexGeocodeResponse {
  response?: {
    GeoObjectCollection?: {
      featureMember?: {
        GeoObject?: {
          name?: string;
          Point?: { pos?: string };
          metaDataProperty?: { GeocoderMetaData?: { text?: string } };
        };
      }[];
    };
  };
}
