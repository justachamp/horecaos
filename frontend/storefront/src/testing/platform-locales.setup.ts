import { adoptPlatformLocales } from '../app/services/platform-locales.service';
import { REGISTRY_FIXTURE } from './platform-locales.fixture';

/**
 * Global Vitest setup (wired in `angular.json`'s `test` target via `setupFiles`): every spec starts
 * with the registry already read, as the app guarantees once its initializer has run (ADR 0149), so a
 * spec that renders a price or a preset wording finds `uz-Latn` behind the catalogue id `uz` without
 * answering `GET /storefront/locales` first. A spec that tests the unread state clears it with
 * `adoptPlatformLocales([])`.
 */
adoptPlatformLocales(REGISTRY_FIXTURE);
