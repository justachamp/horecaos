import { Injectable, Signal, computed, signal } from '@angular/core';

import { LocationScope } from '../core/api/operations-paths';
import { environment } from '../../environments/environment';
import { DeviceClass, DeviceProfile } from './device-profile';

/**
 * ADR 0079's own credential, held by an enrolled device: what `DeviceSession`
 * mints access tokens against once enrolment is done. Never the staff bearer
 * — see this module's own `README`-shaped doc below and ADR 0119.
 */
export interface DeviceCredential {
  readonly tokenEndpoint: string;
  readonly clientId: string;
  readonly clientSecret: string;
}

const SETUP_KEY = 'horecaos.kds.setup';
const CREDENTIAL_KEY = 'horecaos.kds.credential';
/** The last record the server gave this device, so a flaky read at boot is not a dead end (ADR 0151). */
const PROFILE_KEY = 'horecaos.kds.profile';

/** Refresh this far before the access token actually expires. */
const REFRESH_MARGIN_MS = 30_000;

/**
 * The device's credential is refused: Keycloak (or the platform) said no to this client, and asking
 * again will not change the answer. Revoked, most likely (ADR 0079). The shell treats it as the end of
 * the session and goes back to pairing.
 */
export class DeviceAuthError extends Error {}

/**
 * Keycloak could not answer right now: a 5xx, a throttle, a timeout, a dropped connection or a reply
 * that is not a token. **Nothing is wrong with the credential**, so this is deliberately not a
 * {@link DeviceAuthError}: the session is kept, the wall keeps its last rows, and the next poll asks
 * again. Treating an identity-provider outage as a revoked device would log every wall in the building
 * out at once, during exactly the minutes nobody can re-pair them.
 */
export class DeviceTokenUnavailableError extends Error {
  constructor(
    /** The HTTP status Keycloak answered, or null when it could not be reached at all. */
    readonly status: number | null,
    message: string,
  ) {
    super(message);
  }
}

/**
 * How long a token request waits before trying again after a transient failure, and so how many
 * times it tries: one more per entry. Bounded on purpose: a wall polls every ten seconds anyway, so
 * the retry only has to ride out a blip, not an outage.
 */
export const DEVICE_TOKEN_RETRY_DELAYS_MS: readonly number[] = [500, 1500];

/** A status that says "not now", as opposed to "no": the server failed, was busy or timed out. */
function isTransientTokenStatus(status: number): boolean {
  return status >= 500 || status === 408 || status === 425 || status === 429;
}

/**
 * ADR 0079's device principal, held by the frontend (ADR 0119).
 *
 * **This class, and everything under `device/`, never imports `core/auth`.**
 * A kitchen KDS is not the signed-in staff member whose session
 * `Auth`/`StaffTokenStore`/`bearerTokenInterceptor` exist for — see this
 * repo's `docs/adr/not-started/0119-*.md` for why that boundary is a
 * deliberate architectural decision, not an oversight. Every network call
 * this class makes is a plain `fetch`, never Angular's `HttpClient`, so no
 * interceptor written for a *different* principal can ever attach to,
 * inspect, or override a device request — including
 * {@link tokenEndpoint}'s own call, which is deliberately cross-origin,
 * straight to Keycloak (ADR 0079 step 4), the one narrow exception to ADR
 * 0062's "the browser never talks to Keycloak."
 *
 * **Two local, human steps enrol a device**, not one: {@link saveSetup}
 * (typed once, by whoever installs the tablet — its tenant/brand/location,
 * since nothing on an ADR 0079 device token names them; see ADR 0119's own
 * Open inputs for why) and then the pairing handshake itself
 * ({@link beginEnrolment}/{@link pollOnce}). Credential storage is
 * `localStorage`, not `sessionStorage` — the opposite of `StaffTokenStore`'s
 * choice, and for the opposite reason: a wall-mounted KDS must resume after
 * a reboot with nobody re-typing anything, and its grant is revocable
 * server-side in one row the moment it needs to stop working, so surviving a
 * restart costs nothing `StaffTokenStore`'s own reasoning was protecting
 * against.
 */
@Injectable({ providedIn: 'root' })
export class DeviceSession {
  private readonly setupState = signal<LocationScope | null>(readJson(SETUP_KEY));
  private readonly credentialState = signal<DeviceCredential | null>(readJson(CREDENTIAL_KEY));
  private readonly profileState = signal<DeviceProfile | null>(readJson(PROFILE_KEY));

  readonly setup: Signal<LocationScope | null> = this.setupState.asReadonly();
  readonly credential: Signal<DeviceCredential | null> = this.credentialState.asReadonly();
  /** What the server last said this device is (ADR 0151); null until the first successful read. */
  readonly profile: Signal<DeviceProfile | null> = this.profileState.asReadonly();
  readonly isSetUp: Signal<boolean> = computed(() => this.setupState() !== null);
  readonly isEnrolled: Signal<boolean> = computed(() => this.credentialState() !== null);

  private cachedToken: { readonly value: string; readonly expiresAt: number } | null = null;
  private mintInFlight: Promise<string> | null = null;

  saveSetup(scope: LocationScope): void {
    this.setupState.set(scope);
    writeJson(SETUP_KEY, scope);
  }

  /**
   * Keeps what the server said this device is, and makes its branch the branch every later read uses:
   * the server's word replaces anything typed by hand, which is the point of asking it (ADR 0151).
   */
  saveProfile(profile: DeviceProfile): void {
    this.profileState.set(profile);
    writeJson(PROFILE_KEY, profile);
    this.saveSetup({
      tenantId: profile.tenantId,
      brandId: profile.brandId,
      locationId: profile.locationId,
    });
  }

  /** An installer's escape hatch — re-run setup on a device that was configured for the wrong branch. */
  clearSetup(): void {
    this.setupState.set(null);
    removeJson(SETUP_KEY);
  }

  /**
   * Begins a pairing-code enrolment (ADR 0079 step 1). Called by the device
   * itself, before it holds any credential — an unauthenticated `fetch`, the
   * same trust boundary `DeviceEnrolmentController` documents.
   */
  async beginEnrolment(
    label: string | null,
    deviceClass: DeviceClass = 'KITCHEN_KDS',
  ): Promise<{
    readonly deviceCode: string;
    readonly userCode: string;
    readonly expiresAt: string;
    readonly pollIntervalSeconds: number;
  }> {
    const response = await fetch(
      `${environment.apiBaseUrl}/api/v1/control-plane/device-enrolments`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // A request for a class is a claim the approver sees on Kitchen → Devices; they may approve
        // it as less (a tablet as a wall) and never as more (ADR 0151).
        body: JSON.stringify({ deviceClass, label }),
      },
    );
    if (!response.ok) {
      throw new DeviceAuthError(`Could not begin enrolment (HTTP ${response.status})`);
    }
    return response.json();
  }

  /**
   * One poll (ADR 0079 step 3). The caller ({@link device-shell.ts}) owns the
   * interval and the `pollIntervalSeconds` pacing — this method makes exactly
   * one request. On the poll that observes `APPROVED` with a credential, the
   * credential is stored immediately, before returning, so a component
   * unmounted mid-callback never loses a claimed-exactly-once secret.
   */
  async pollOnce(deviceCode: string): Promise<{
    readonly status: 'PENDING' | 'APPROVED' | 'DENIED' | 'EXPIRED';
    readonly credential: DeviceCredential | null;
  }> {
    const response = await fetch(
      `${environment.apiBaseUrl}/api/v1/control-plane/device-enrolments/${encodeURIComponent(deviceCode)}/poll`,
      { method: 'POST' },
    );
    if (!response.ok) {
      throw new DeviceAuthError(`Could not poll enrolment (HTTP ${response.status})`);
    }
    const body = await response.json();
    if (body.credential) {
      this.storeCredential(body.credential);
    }
    return body;
  }

  private storeCredential(credential: DeviceCredential): void {
    this.credentialState.set(credential);
    writeJson(CREDENTIAL_KEY, credential);
    this.cachedToken = null;
  }

  /** Forgets this device's credential locally — the console-side revoke (ADR 0079) already did the half that matters; this clears the half stuck on the tablet. */
  forgetCredential(): void {
    this.credentialState.set(null);
    this.profileState.set(null);
    this.cachedToken = null;
    removeJson(CREDENTIAL_KEY);
    removeJson(PROFILE_KEY);
  }

  /**
   * A live access token, minted directly against Keycloak's own
   * `tokenEndpoint` (ADR 0079 step 4) — never through this platform's
   * backend, never through `core/auth`. Cached in memory only; a page reload
   * simply mints again, which costs one request and no state.
   */
  async accessToken(): Promise<string> {
    const now = Date.now();
    if (this.cachedToken && this.cachedToken.expiresAt - REFRESH_MARGIN_MS > now) {
      return this.cachedToken.value;
    }
    if (this.mintInFlight) {
      return this.mintInFlight;
    }
    this.mintInFlight = this.mint().finally(() => {
      this.mintInFlight = null;
    });
    return this.mintInFlight;
  }

  private async mint(): Promise<string> {
    for (let attempt = 0; ; attempt++) {
      try {
        return await this.mintOnce();
      } catch (error) {
        const delay = DEVICE_TOKEN_RETRY_DELAYS_MS[attempt];
        if (!(error instanceof DeviceTokenUnavailableError) || delay === undefined) {
          throw error;
        }
        await new Promise<void>((resolve) => setTimeout(resolve, delay));
      }
    }
  }

  private async mintOnce(): Promise<string> {
    const credential = this.credentialState();
    if (!credential) {
      throw new DeviceAuthError('Device is not enrolled');
    }
    let response: Response;
    try {
      response = await fetch(credential.tokenEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'client_credentials',
          client_id: credential.clientId,
          client_secret: credential.clientSecret,
        }).toString(),
      });
    } catch {
      // The request never got an answer (offline, DNS, a reset): the credential has not been judged.
      throw new DeviceTokenUnavailableError(
        null,
        'Keycloak could not be reached for a device token',
      );
    }
    if (!response.ok) {
      if (isTransientTokenStatus(response.status)) {
        throw new DeviceTokenUnavailableError(
          response.status,
          `Keycloak could not answer the device's token request (HTTP ${response.status})`,
        );
      }
      // A 401/403 here means Keycloak refused this client — most likely
      // revoked (ADR 0079's disable-the-client half of revocation). Forget
      // the credential locally so the shell falls back to its enrolment
      // screen instead of retrying a client that will never work again.
      if (response.status === 401 || response.status === 403) {
        this.forgetCredential();
      }
      throw new DeviceAuthError(
        `Keycloak refused the device's token request (HTTP ${response.status})`,
      );
    }
    let body: { access_token: string; expires_in: number };
    try {
      body = await response.json();
    } catch {
      // A 200 that is not a token (a proxy's error page, a truncated body) is a failed answer, not a refusal.
      throw new DeviceTokenUnavailableError(
        response.status,
        'Keycloak answered with something that is not a token',
      );
    }
    this.cachedToken = { value: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
    return body.access_token;
  }
}

function readJson<T>(key: string): T | null {
  try {
    const raw = globalThis.localStorage?.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function writeJson(key: string, value: unknown): void {
  try {
    globalThis.localStorage?.setItem(key, JSON.stringify(value));
  } catch {
    // A device that cannot persist its own setup/credential re-does the step
    // on every reload rather than throwing — the same tolerant stance
    // `current-location.ts`'s own storage calls take.
  }
}

function removeJson(key: string): void {
  try {
    globalThis.localStorage?.removeItem(key);
  } catch {
    // Nothing more to do.
  }
}
