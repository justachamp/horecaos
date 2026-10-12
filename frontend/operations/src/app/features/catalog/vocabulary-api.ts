import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import { LabelsByLocale } from '../../core/i18n/locale-labels';

/**
 * ADR 0174's two reference vocabularies: tenant-wide registries of tags (badges and storefront filter options)
 * and ingredients (composition labels), and the ordered set of each a product carries.
 *
 * Control-plane paths, like every catalog authoring endpoint the console calls (see `catalog-paths.ts`).
 * The registry is tenant-scoped (no brand); a product's set is brand-scoped through the product.
 */
export type VocabularyKind = 'TAG' | 'INGREDIENT';

/** The closed pictogram list a tag may carry (ADR 0174, code-owned: no uploaded image). */
export const TAG_ICONS = ['FLAME', 'LEAF', 'STAR', 'HEART', 'FISH', 'NEW'] as const;
export type TagIcon = (typeof TAG_ICONS)[number];

/** Mirrors `VocabularyResponses.EntryResponse`. `iconKey` and the two switches are a tag's; an ingredient reads null/false/false. */
export interface VocabularyEntry {
  readonly id: string;
  readonly code: string;
  readonly iconKey: string | null;
  readonly showAsBadge: boolean;
  readonly showAsFilter: boolean;
  readonly sortOrder: number;
  /** `ACTIVE` or `ARCHIVED`. */
  readonly status: string;
  readonly version: number;
  readonly names: LabelsByLocale;
}

export interface NewVocabularyEntry {
  readonly code: string;
  readonly iconKey?: string | null;
  readonly showAsBadge?: boolean;
  readonly showAsFilter?: boolean;
  readonly sortOrder?: number;
  readonly names: LabelsByLocale;
}

/** An edit names only what it changes: a locale not named keeps its wording, an unnamed switch keeps its value. */
export interface VocabularyEdit {
  readonly iconKey?: string | null;
  readonly showAsBadge?: boolean;
  readonly showAsFilter?: boolean;
  readonly sortOrder?: number;
  readonly names?: LabelsByLocale;
}

const CONTROL_PLANE = '/api/v1/control-plane';

function segment(kind: VocabularyKind): string {
  return kind === 'TAG' ? 'tags' : 'ingredients';
}

@Injectable({ providedIn: 'root' })
export class VocabularyApi {
  private readonly api = inject(ApiClient);

  async list(tenantId: string, kind: VocabularyKind): Promise<readonly VocabularyEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly VocabularyEntry[]>(this.registry(tenantId, kind)),
    );
    return result.value ?? [];
  }

  /** Refused 409 (`VOCABULARY_CODE_TAKEN`) when the tenant already has the code, 422 for the other findings. */
  create(
    tenantId: string,
    kind: VocabularyKind,
    body: NewVocabularyEntry,
  ): Promise<VocabularyEntry> {
    return firstValueFrom(
      this.api.post<NewVocabularyEntry, VocabularyEntry>(
        this.registry(tenantId, kind),
        command(body),
      ),
    );
  }

  /** `If-Match` carries the version the caller read. The code never changes. */
  update(
    tenantId: string,
    kind: VocabularyKind,
    entry: VocabularyEntry,
    body: VocabularyEdit,
  ): Promise<VocabularyEntry> {
    return firstValueFrom(
      this.api.put<VocabularyEdit, VocabularyEntry>(
        `${this.registry(tenantId, kind)}/${encodeURIComponent(entry.id)}`,
        command(body),
        { expectedVersion: entry.version },
      ),
    );
  }

  /** Archived, never deleted: the entry keeps resolving on the products that carry it. */
  archive(
    tenantId: string,
    kind: VocabularyKind,
    entry: VocabularyEntry,
  ): Promise<VocabularyEntry> {
    return firstValueFrom(
      this.api.post<Record<string, never>, VocabularyEntry>(
        `${this.registry(tenantId, kind)}/${encodeURIComponent(entry.id)}/archive`,
        command({}),
        { expectedVersion: entry.version },
      ),
    );
  }

  /** A product's ordered set, including an archived entry it still carries. */
  async forProduct(
    scope: BrandScope,
    kind: VocabularyKind,
    productId: string,
  ): Promise<readonly VocabularyEntry[]> {
    const result = await firstValueFrom(
      this.api.get<{ items: readonly VocabularyEntry[] }>(this.productSet(scope, kind, productId)),
    );
    return result.value?.items ?? [];
  }

  /** Replaces the product's set with these entries, in this order. */
  async setForProduct(
    scope: BrandScope,
    kind: VocabularyKind,
    productId: string,
    ids: readonly string[],
  ): Promise<readonly VocabularyEntry[]> {
    const result = await firstValueFrom(
      this.api.put<{ ids: readonly string[] }, { items: readonly VocabularyEntry[] }>(
        this.productSet(scope, kind, productId),
        command({ ids }),
      ),
    );
    return result.items;
  }

  private registry(tenantId: string, kind: VocabularyKind): string {
    return `${CONTROL_PLANE}/tenants/${encodeURIComponent(tenantId)}/${segment(kind)}`;
  }

  private productSet(scope: BrandScope, kind: VocabularyKind, productId: string): string {
    return (
      `${CONTROL_PLANE}/tenants/${encodeURIComponent(scope.tenantId)}/brands/` +
      `${encodeURIComponent(scope.brandId)}/products/${encodeURIComponent(productId)}/${segment(kind)}`
    );
  }
}
