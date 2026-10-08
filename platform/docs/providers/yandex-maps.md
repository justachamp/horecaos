# Yandex Maps: Geocoder and Suggest (transcribed for ADR 0145)

The first adapter ADR 0145 builds (`YandexGeocoderAdapter`). This file records what the
adapter assumes about the provider and **what it could not verify**, because no key exists yet
and no live call has been made. Where the provider's own current documentation disagrees with
this file, the provider wins and this file is wrong.

## How the fixtures were made

`src/test/resources/geo/yandex/*.json` are **transcribed from the documented response shape,
not captured from the live service.** No Yandex account, key or licence exists (ADR 0145's open
inputs: the licence terms and how a foreign licence is paid from Uzbekistan). Their addresses
are invented; their structure is what the adapter parses. The first thing to do once a key
exists is to capture one real answer per operation and compare it to these files field by field;
the adapter reads defensively, so a difference is an unreadable answer (`Unavailable`), never a
wrong result, but it would also mean the screens say "unavailable" for a working provider.

## Endpoints

| Operation | Method and URL | Approved environment (V0490) |
|---|---|---|
| geocode, reverse geocode | `GET https://geocode-maps.yandex.ru/1.x/` | `yandex_geocoder_production` |
| suggest | `GET https://suggest-maps.yandex.ru/v1/suggest` | `yandex_suggest_production` |

Both take the key as the query parameter `apikey`. **There is no header form, so the key and
the address are both in the URL.** `ProviderHttpClient` keeps the query out of every log line
and every outcome.

## Request parameters the adapter sends

Geocode: `apikey`, `geocode` (the address text), `format=json`, `lang` (`ru_RU` or `en_US`),
`results=5`, `bbox=lon,lat~lon,lat` (the region's box, south-west first) and `rspn=0` (bias,
not restriction).

Reverse: `apikey`, `geocode=lon,lat` (**longitude first**), `sco=longlat`, `kind=house`,
`format=json`, `lang`, `results=1`.

Suggest: `apikey`, `text`, `lang` (`ru` or `en`), `results=7`, `print_address=1`, `types=geo`,
`attrs=uri`, `ll=lon,lat` (the person's map centre, else the region's centre), `bbox` as above
and `strict_bounds=0`.

## What the adapter reads

Geocode and reverse: `response.GeoObjectCollection.featureMember[].GeoObject`, using
`Point.pos` (**"lon lat"**), `metaDataProperty.GeocoderMetaData.precision`, `.kind`, `.text`,
`.Address.formatted`, `.Address.country_code` and `.Address.Components[{kind, name}]`. A candidate
without a parseable point is dropped, never placed at zero.

Suggest: `results[]` with `title.text`, `subtitle.text`, `address.formatted_address` and `uri`.
The provider's suggest answer **carries no coordinates**, so a suggestion has no point; choosing a
line submits its `fullText` to geocode.

## What this platform derives, because the provider does not say

- **Confidence.** Yandex publishes no confidence. The adapter derives one from `precision`:
  `exact` (a building) is `HIGH`; `number`, `near` and `range` (a neighbouring or interpolated
  number) are `MEDIUM`; `street`, `other` and anything unrecognised are `LOW_CONFIDENCE`, because
  a point that names no door is a delivery to the wrong place. This is a decision of ours; it is
  the thing the bake-off's "within 100 m" threshold tests.
- **Precision.** `exact` -> `HOUSE`; `number`/`near`/`range` -> `NEAR_HOUSE`; `street` -> `STREET`;
  otherwise by `kind` (`locality`, `district`, `province`, `area`, `country`, `metro`, `airport`
  -> `LOCALITY`).
- **The region box on the way back** is applied by `GeoGateway` for every adapter, not here.

## What the documentation does not say, or this file could not confirm

- **No Uzbek locale is documented for the Geocoder.** Its documented languages are Russian,
  Ukrainian, Belarusian, English and Turkish, so `uz-Latn` asks for `ru_RU`. Whether the Suggest
  service accepts an Uzbek `lang` was not confirmed, so it is not sent.
- **Whether `attrs=uri` and `print_address=1` are honoured exactly as sent** was not confirmed
  against a live key. Both are read defensively: a missing `uri` falls back to a synthetic
  reference, and a missing `formatted_address` falls back to `title, subtitle`.
- **A sandbox host.** None is documented, which is why V0490 has no non-production row.
- **What the licence allows to be stored, shown and resold**, and how it is paid for from
  Uzbekistan: ADR 0145's open inputs, unanswered. The response cache is held at the narrowest
  limit read on 2026-10-01 (thirty days) and nothing else is stored.
- **Attribution text.** The adapter defaults to `© Яндекс` and
  `horecaos.geo.yandex.attribution` overrides it; the licence's own wording binds.
- **Rate limits and daily quota** are not published in a form the adapter can read; a `429`
  is `RATE_LIMITED`, and a `403` for an exhausted quota is indistinguishable from a bad key and
  is reported as a refusal.

## Configuration

| Property | Meaning |
|---|---|
| `horecaos.geo.provider` | `yandex` for this adapter; `fake` under the `local` profile; unset is `NONE` |
| `horecaos.geo.yandex.secret-reference` | The ADR 0028 reference of the **server** key, in the platform-owned `PROVIDER_GEOCODING` category: `horecaos:production:provider_geocoding:platform:<id>`, written by the owner with `bao kv put horecaos/production/provider_geocoding/platform/<id> value=...`. Unset means `NOT_CONFIGURED` |
| `horecaos.geo.yandex.browser-key` | The public, referrer-restricted key for the vendor's map script, delivered by `GET .../map-config`. Not a secret; unset means no `TILES` feature |
| `horecaos.geo.yandex.attribution` | The attribution text, default `© Яндекс` |
