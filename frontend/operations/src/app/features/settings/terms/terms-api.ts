import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors uz.horecaos.platform.legal.web.OperationsTermsController.TermsVersionSummaryView. */
export interface TermsVersionSummaryView {
  readonly id: string;
  readonly version: number;
  readonly locales: readonly string[];
  readonly publishedBy: string;
  readonly publishedAt: string;
}

/**
 * Mirrors OperationsTermsController.TermsVersionView. `published: false` with
 * every other field null/empty is the brand's honest "never published"
 * state, not an error — see `TermsApi.current`'s own doc.
 */
export interface TermsVersionView {
  readonly published: boolean;
  readonly id: string | null;
  readonly version: number | null;
  readonly contentsByLocale: Readonly<Record<string, string>>;
  readonly publishedBy: string | null;
  readonly publishedAt: string | null;
}

export interface PublishTermsRequest {
  readonly contentsByLocale: Readonly<Record<string, string>>;
  readonly note?: string;
}

/**
 * 10.12 Terms of service (ADR 0067, `OperationsTermsController`) — the
 * tenant-owner's own console for authoring the storefront's terms-of-service
 * text, replacing the legacy hardcoded copy. `TERMS_READ`/`TERMS_MANAGE` are
 * held only by `tenant-owner`, a `TENANT`-scoped bundle, which is why every
 * call here takes a bare `tenantId`/`brandId` pair rather than the
 * `LocationScope` most of `settings-paths.ts` uses — see `terms-page.ts`'s
 * own doc for the full story.
 */
@Injectable({ providedIn: 'root' })
export class TermsApi {
  private readonly api = inject(ApiClient);

  /** Every published version, newest first. */
  async list(tenantId: string, brandId: string): Promise<readonly TermsVersionSummaryView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TermsVersionSummaryView[]>(
        settingsPaths.termsDocuments(tenantId, brandId),
      ),
    );
    return result.value ?? [];
  }

  /**
   * The version in force right now. `published: false` means the brand has
   * never published — the storefront is serving the platform's own neutral
   * default text, not an error state.
   */
  async current(tenantId: string, brandId: string): Promise<TermsVersionView> {
    const result = await firstValueFrom(
      this.api.get<TermsVersionView>(settingsPaths.termsDocumentCurrent(tenantId, brandId)),
    );
    return result.value;
  }

  /** One historical version, for the publish history's read-only preview. */
  async version(tenantId: string, brandId: string, version: number): Promise<TermsVersionView> {
    const result = await firstValueFrom(
      this.api.get<TermsVersionView>(
        settingsPaths.termsDocumentVersion(tenantId, brandId, version),
      ),
    );
    return result.value;
  }

  /**
   * Publish a new version. Never edits a previous one — the server always
   * creates the next version number, carrying forward whichever locale the
   * caller omitted from `request.contentsByLocale`.
   */
  async publish(
    tenantId: string,
    brandId: string,
    request: PublishTermsRequest,
  ): Promise<TermsVersionView> {
    return firstValueFrom(
      this.api.post<PublishTermsRequest, TermsVersionView>(
        settingsPaths.termsDocuments(tenantId, brandId),
        command(request),
      ),
    );
  }
}
