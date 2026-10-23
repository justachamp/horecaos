import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LabelsByLocale, LocaleSetView } from '../../../core/i18n/locale-labels';

/**
 * `CommentPresetController` — control-plane, not the operations prefix, the
 * same pre-existing mismatch `catalog-paths.ts` documents at length for
 * every other catalog authoring endpoint this console calls. Kept as its
 * own small constant here rather than reusing `catalogPaths` because these
 * endpoints are `TENANT`-scoped (no `brandId`), a different shape from
 * `catalogPaths`' own `BrandScope`.
 */
const CONTROL_PLANE = '/api/v1/control-plane';

/** Mirrors `CommentPresetController.PresetResponse` (row 2.1b). */
export interface PresetResponse {
  readonly presetId: string;
  readonly code: string;
  /**
   * Every language the preset has wording in (row 10.12): the platform triple
   * first, then any other by code, each once. The only shape of the wording
   * since V0678; the `labelRu`/`labelUz`/`labelEn` fields it replaced are gone.
   */
  readonly labels: LabelsByLocale;
  readonly posModifierCode: string | null;
  readonly sortOrder: number;
  /** `ACTIVE` or `ARCHIVED`. */
  readonly status: string;
  readonly version: number;
}

/**
 * `labels` is the wording by locale, the only shape a preset's wording has: the
 * tenant's default language must be in it, every other language is optional,
 * and a language the tenant does not serve is simply left out.
 */
export interface NewPreset {
  readonly code: string;
  readonly labels: LabelsByLocale;
  readonly posModifierCode?: string | null;
  readonly sortOrder?: number;
}

/**
 * Whole-record edit: POS mapping, sort order and status together, with an
 * expected version. `labels` names only the offered languages the operator
 * filled in, so a language that is not offered keeps its wording.
 */
export interface PresetEdit {
  readonly labels: LabelsByLocale;
  readonly posModifierCode?: string | null;
  readonly sortOrder: number;
  readonly status: string;
  readonly expectedVersion: number;
}

/** The tenant-wide coded kitchen-instruction vocabulary (row 2.1b) — the settings screen's own API. */
@Injectable({ providedIn: 'root' })
export class CommentPresetsApi {
  private readonly api = inject(ApiClient);

  async list(tenantId: string): Promise<readonly PresetResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PresetResponse[]>(this.path(tenantId)),
    );
    return result.value ?? [];
  }

  /** The languages this editor offers — the union of the tenant's brands' (row 10.12). */
  async localeSet(tenantId: string): Promise<LocaleSetView> {
    const result = await firstValueFrom(
      this.api.get<LocaleSetView>(`${this.path(tenantId)}/locale-set`),
    );
    return result.value;
  }

  /** Refused (409) when the code is already registered for this tenant. */
  create(tenantId: string, body: NewPreset): Observable<PresetResponse> {
    return this.api.post<NewPreset, PresetResponse>(this.path(tenantId), command(body));
  }

  /** Archiving is `status: 'ARCHIVED'` here, not a delete — a preset already on a product must stay resolvable. */
  update(tenantId: string, presetId: string, body: PresetEdit): Observable<PresetResponse> {
    return this.api.put<PresetEdit, PresetResponse>(
      `${this.path(tenantId)}/${encodeURIComponent(presetId)}`,
      command(body),
    );
  }

  private path(tenantId: string): string {
    return `${CONTROL_PLANE}/tenants/${encodeURIComponent(tenantId)}/comment-presets`;
  }
}
