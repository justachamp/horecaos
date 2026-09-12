import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';

/** Mirrors `StorefrontAnalyticsConfigController.AnalyticsConfigResponse`. Every field is nullable. */
interface AnalyticsConfigResponse {
  readonly gtmContainerId: string | null;
  readonly ga4MeasurementId: string | null;
  readonly searchConsoleVerificationToken: string | null;
}

/**
 * Loads this deployment's own brand's analytics configuration and injects
 * whichever scripts the tenant has actually configured (ADR 0106, gap-map
 * row `10.8e`).
 *
 * Replaces `index.html`'s old hard-coded, switched-off, wrong-tenant Yandex
 * Metrika counter: analytics injection is now a runtime decision, read once
 * per brand from `GET /storefront/tenants/{tenantId}/brands/{brandId}/analytics`
 * (unauthenticated, cacheable — the same posture the published menu takes),
 * never a build-time constant. A brand with no `ANALYTICS` binding gets every
 * field null and this injects nothing, which is every brand today.
 *
 * **Search Console is not injected as a script.** Its verification token is a
 * meta tag or DNS record the tenant sets outside this application entirely
 * (Google's own verification flow); this class does not act on it. It exists
 * in the response for a future settings surface to read back, not for this
 * injector to consume.
 *
 * Idempotent: a second call while the first is still loading, or after it has
 * finished, returns the same promise or resolves immediately rather than
 * injecting the same script twice.
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsInjector {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private loadPromise: Promise<void> | null = null;

  /** Fetches this brand's analytics config and injects its scripts, once. */
  ensureLoaded(): Promise<void> {
    this.loadPromise ??= this.load();
    return this.loadPromise;
  }

  private async load(): Promise<void> {
    let response: AnalyticsConfigResponse;
    try {
      response = await this.api.get<AnalyticsConfigResponse>(
        `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/analytics`,
        { anonymous: true },
      );
    } catch {
      // No installation, a transient network failure, or a brand this
      // endpoint has never heard of — every one of these means "inject
      // nothing", the same posture the endpoint itself takes for a brand
      // with no ANALYTICS binding. Never blocks the storefront from loading.
      return;
    }

    if (response.gtmContainerId) {
      injectGtm(response.gtmContainerId);
    }
    if (response.ga4MeasurementId) {
      injectGtag(response.ga4MeasurementId);
    }
  }
}

function injectGtm(containerId: string): void {
  if (document.getElementById(scriptId('gtm', containerId))) {
    return;
  }
  window.dataLayer = window.dataLayer ?? [];
  const script = document.createElement('script');
  script.id = scriptId('gtm', containerId);
  script.async = true;
  script.src = `https://www.googletagmanager.com/gtm.js?id=${encodeURIComponent(containerId)}`;
  document.head.appendChild(script);
}

function injectGtag(measurementId: string): void {
  if (document.getElementById(scriptId('gtag', measurementId))) {
    return;
  }
  window.dataLayer = window.dataLayer ?? [];
  // The standard gtag.js bootstrap: define `gtag` as a function that queues
  // into the same `dataLayer` `pushEcommerceEvent` writes to, so an event
  // pushed before or after this script tag runs is handled identically.
  const inline = document.createElement('script');
  inline.id = scriptId('gtag-inline', measurementId);
  inline.textContent =
    'window.dataLayer = window.dataLayer || [];' +
    'function gtag(){window.dataLayer.push(arguments);}' +
    "gtag('js', new Date());" +
    `gtag('config', ${JSON.stringify(measurementId)});`;
  document.head.appendChild(inline);

  const script = document.createElement('script');
  script.id = scriptId('gtag', measurementId);
  script.async = true;
  script.src = `https://www.googletagmanager.com/gtag/js?id=${encodeURIComponent(measurementId)}`;
  document.head.appendChild(script);
}

function scriptId(kind: string, id: string): string {
  return `horecaos-analytics-${kind}-${id}`;
}
