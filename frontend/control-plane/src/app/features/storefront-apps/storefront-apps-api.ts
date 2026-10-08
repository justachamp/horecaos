import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

export type ClientType = 'PUBLIC' | 'CONFIDENTIAL';
export type AppStatus = 'ACTIVE' | 'SUSPENDED' | 'RETIRED';
export type ConformanceState = 'NOT_RUN' | 'PASSED' | 'FAILED' | 'EXPIRED';

/** Mirrors StorefrontAppViews.StorefrontAppConformance. EXPIRED is derived server-side, never stored. */
export interface Conformance {
  readonly status: ConformanceState;
  readonly contractVersion: string | null;
  readonly recordedAt: string | null;
  readonly note: string | null;
}

/**
 * Mirrors StorefrontAppViews.StorefrontAppView (ADR 0070). There is no secret and no
 * reference to one in it, by design: only whether a confidential client has one and when
 * it last changed.
 */
export interface StorefrontApp {
  readonly id: string;
  readonly name: string;
  readonly vendor: string;
  readonly clientType: ClientType;
  readonly firstParty: boolean;
  readonly originAllowlist: readonly string[];
  readonly secretConfigured: boolean;
  readonly secretRotatedAt: string | null;
  readonly status: AppStatus;
  readonly conformance: Conformance;
  readonly version: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface StorefrontAppSummary {
  readonly app: StorefrontApp;
  readonly activeAuthorisations: number;
  readonly activeTenants: number;
}

export interface AppAuthorisation {
  readonly id: string;
  readonly tenantId: string;
  readonly tenantName: string;
  readonly brandId: string;
  readonly brandName: string;
  readonly status: 'ACTIVE' | 'REVOKED';
  readonly grantedBy: string;
  readonly grantedAt: string;
  readonly revokedBy: string | null;
  readonly revokedAt: string | null;
  readonly version: number;
}

export interface StorefrontAppDetail {
  readonly app: StorefrontApp;
  readonly authorisations: readonly AppAuthorisation[];
}

/** The app, and — for a confidential client, on registration and rotation only — its secret, once. */
export interface RegisteredApp {
  readonly app: StorefrontApp;
  readonly secretValue: string | null;
}

export interface RegisterRequest {
  readonly name: string;
  readonly vendor: string;
  readonly clientType: ClientType;
  readonly firstParty: boolean;
  readonly originAllowlist: readonly string[];
  readonly reason: string;
}

export interface UpdateRequest {
  readonly name: string;
  readonly vendor: string;
  readonly originAllowlist: readonly string[];
  readonly reason: string;
}

/**
 * IA 3.6 Storefront apps (ADR 0070, `StorefrontAppRegistryController`) — the platform's
 * registry of who may build a storefront against the published contract.
 *
 * Every change names the version it read: `If-Match` on the wire, from `app.version`.
 */
@Injectable({ providedIn: 'root' })
export class StorefrontAppsApi {
  private readonly api = inject(ApiClient);
  private static readonly PATH = '/api/v1/control-plane/storefront-apps';

  async list(): Promise<readonly StorefrontAppSummary[]> {
    return firstValueFrom(this.api.get<StorefrontAppSummary[]>(StorefrontAppsApi.PATH));
  }

  async detail(appId: string): Promise<StorefrontAppDetail> {
    return firstValueFrom(this.api.get<StorefrontAppDetail>(`${StorefrontAppsApi.PATH}/${appId}`));
  }

  async register(request: RegisterRequest): Promise<RegisteredApp> {
    return firstValueFrom(this.api.post<RegisteredApp>(StorefrontAppsApi.PATH, request));
  }

  async update(app: StorefrontApp, request: UpdateRequest): Promise<StorefrontApp> {
    return firstValueFrom(
      this.api.put<StorefrontApp>(`${StorefrontAppsApi.PATH}/${app.id}`, request, {
        expectedVersion: app.version,
      }),
    );
  }

  async changeStatus(
    app: StorefrontApp,
    status: AppStatus,
    reason: string,
  ): Promise<StorefrontApp> {
    return firstValueFrom(
      this.api.put<StorefrontApp>(
        `${StorefrontAppsApi.PATH}/${app.id}/status`,
        { status, reason },
        { expectedVersion: app.version },
      ),
    );
  }

  async rotateSecret(app: StorefrontApp, reason: string): Promise<RegisteredApp> {
    return firstValueFrom(
      this.api.post<RegisteredApp>(
        `${StorefrontAppsApi.PATH}/${app.id}/secret-rotations`,
        { reason },
        { expectedVersion: app.version },
      ),
    );
  }

  async recordConformance(
    app: StorefrontApp,
    request: {
      result: 'PASSED' | 'FAILED';
      contractVersion: string;
      note?: string;
      reason: string;
    },
  ): Promise<StorefrontApp> {
    return firstValueFrom(
      this.api.post<StorefrontApp>(
        `${StorefrontAppsApi.PATH}/${app.id}/conformance-results`,
        request,
        {
          expectedVersion: app.version,
        },
      ),
    );
  }
}
