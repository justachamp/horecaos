import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import {
  InvitationLocale,
  OnboardingRunView,
  OnboardingTemplateSuggestion,
  OnboardingTemplateView,
  OwnerInvitationView,
  TenantsApi,
  ValidationOutcome,
} from './tenants-api';

/** Why an invitation is waiting or stopped, in the operator's words (ADR 0097). */
const INVITATION_HINTS: ReadonlySet<string> = new Set([
  'MAIL_NOT_CONFIGURED',
  'SMTP_UNAVAILABLE',
  'SMTP_AUTHENTICATION',
  'SMTP_SECRET_MISSING',
  'ADDRESS_REJECTED',
  'ADDRESS_INVALID',
  'OWNER_ACCOUNT_MISSING',
  'IDENTITY_UNAVAILABLE',
  'EXPIRED',
]);

/** Where each failure is fixed, when it is fixed in this console. */
type HintLink = 'brands' | 'legal-entities' | 'identity' | 'installations';

/**
 * What an operator does about each failure code a step can report, in their
 * language. Codes the platform adds later fall back to the server's own
 * detail, which is still shown underneath every hint because it names the
 * specifics -- which location, which payment method.
 */
const HINTS: Readonly<Record<string, { readonly key: MessageKey; readonly link?: HintLink }>> = {
  AWAITING_ORGANIZATION: { key: 'onboarding.hint.AWAITING_ORGANIZATION' },
  OWNER_EMAIL_UNREADABLE: { key: 'onboarding.hint.OWNER_EMAIL_UNREADABLE' },
  IDENTITY_DRIFT: { key: 'onboarding.hint.IDENTITY_DRIFT', link: 'identity' },
  ITEM_NOT_AVAILABLE_TO_SELL: { key: 'onboarding.hint.ITEM_NOT_AVAILABLE_TO_SELL' },
  MEDIA_NOT_AVAILABLE: { key: 'onboarding.hint.MEDIA_NOT_AVAILABLE' },
  NO_AVAILABLE_ITEM: { key: 'onboarding.hint.NO_AVAILABLE_ITEM' },
  NO_BRAND: { key: 'onboarding.hint.NO_BRAND', link: 'brands' },
  NO_CHANNEL: { key: 'onboarding.hint.NO_CHANNEL' },
  NO_DELIVERY_TARIFF: { key: 'onboarding.hint.NO_DELIVERY_TARIFF' },
  NO_DELIVERY_ZONE: { key: 'onboarding.hint.NO_DELIVERY_ZONE' },
  NO_FULFILLMENT_MODE: { key: 'onboarding.hint.NO_FULFILLMENT_MODE' },
  NO_LEGAL_ENTITY: { key: 'onboarding.hint.NO_LEGAL_ENTITY', link: 'legal-entities' },
  NO_LOCATION: { key: 'onboarding.hint.NO_LOCATION', link: 'brands' },
  NO_MERCHANT_BINDING: { key: 'onboarding.hint.NO_MERCHANT_BINDING' },
  NO_PUBLISHED_MENU: { key: 'onboarding.hint.NO_PUBLISHED_MENU' },
  NOT_REQUESTED: { key: 'onboarding.hint.NOT_REQUESTED' },
  OWNER_NOT_SUPPLIED: { key: 'onboarding.hint.OWNER_NOT_SUPPLIED' },
  POS_BINDING_UNHEALTHY: { key: 'onboarding.hint.POS_BINDING_UNHEALTHY', link: 'installations' },
  QUOTE_REFUSED: { key: 'onboarding.hint.QUOTE_REFUSED' },
  SAMPLE_MENU_REJECTED: { key: 'onboarding.hint.SAMPLE_MENU_REJECTED' },
  SAMPLE_MENU_UNSUPPORTED_CURRENCY: { key: 'onboarding.hint.SAMPLE_MENU_UNSUPPORTED_CURRENCY' },
  SAMPLE_PRICING_REFUSED: { key: 'onboarding.hint.SAMPLE_PRICING_REFUSED' },
  SERVICEABILITY_UNAVAILABLE: { key: 'onboarding.hint.SERVICEABILITY_UNAVAILABLE' },
  TENANT_MISSING: { key: 'onboarding.hint.TENANT_MISSING' },
  TRANSIENT_INFRASTRUCTURE: { key: 'onboarding.hint.TRANSIENT_INFRASTRUCTURE' },
};

const STEP_NAMES: ReadonlySet<string> = new Set([
  'KEYCLOAK_ORGANIZATION_RECONCILE',
  'TENANT_OWNER_LINK_OR_INVITE',
  'DEFAULT_CONFIGURATION_APPLY',
  'SAMPLE_MENU_PUBLISH',
  'BRANDS_AND_LOCATIONS_VALIDATE',
  'PAYMENT_CONFIGURATION_VALIDATE',
  'DELIVERY_CONFIGURATION_VALIDATE',
  'POS_BINDINGS_VALIDATE',
  'CATALOG_READINESS_VALIDATE',
  'MEDIA_READINESS_VALIDATE',
  'FRONTEND_DOMAIN_VALIDATE',
  'ACTIVATION_SMOKE_TEST',
  'TENANT_ACTIVATE',
]);

/** A run in these states has ended; a new one may be started over it. */
const ENDED = new Set(['CANCELLED', 'FAILED']);

/**
 * IA 2.5 Onboarding -- the resumable run: steps, blockers, what to do about
 * each, a dry-run check, resume, cancel, and activate.
 *
 * The real step catalogue (every `OnboardingStep`, from
 * `KEYCLOAK_ORGANIZATION_RECONCILE` through `TENANT_ACTIVATE`, of which
 * `SAMPLE_MENU_PUBLISH` is the only optional one -- a run that declined it
 * carries that step `SKIPPED` rather than omitting it, which is normal and not
 * a failure) is shown as
 * the server names it, not the fiscal-code-backfill/SMS-sender-alias guess
 * this row's own IA prose makes -- `FRONTEND_DOMAIN_VALIDATE` is the closest
 * thing to domain verification, `POS_BINDINGS_VALIDATE` to a provider
 * install checklist, `CATALOG_READINESS_VALIDATE` to the first catalog
 * import.
 *
 * `activate` is this wave's maker-checker exit criterion's last step: its
 * response names `AWAITING_APPROVAL` with a pending request id when a
 * platform-scope policy governs `tenant.activate` (ADR 0027/0050) -- this
 * screen shows that state plainly rather than treating it as a failure, and
 * a second admin decides it from IA 7.1's approvals panel.
 */
@Component({
  selector: 'app-tenant-onboarding',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './tenant-onboarding.html',
  styleUrl: './tenant-onboarding.css',
})
export class TenantOnboarding {
  protected readonly i18n = inject(I18nService);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly tenantId = this.route.snapshot.paramMap.get('tenantId')!;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly run = signal<OnboardingRunView | null>(null);
  protected readonly runId = signal<string | null>(null);

  protected readonly starting = signal(false);
  protected readonly ownerEmail = signal('');
  /** The owner's invitation language, starting from the operator's own. */
  protected readonly ownerLocale = signal<InvitationLocale>('ru');
  protected readonly invitation = signal<OwnerInvitationView | null>(null);
  protected readonly resendReason = signal('');
  protected readonly resendLocale = signal<InvitationLocale | ''>('');
  protected readonly resending = signal(false);
  protected readonly resent = signal(false);
  protected readonly invitationLocales: readonly InvitationLocale[] = ['uz', 'ru', 'en'];

  /**
   * Whether to plant a sample menu (ADR 0099). On by default for a new tenant:
   * the platform wants to see its own storefront answer before the tenant is
   * ready to author anything, and the tenant replaces it later. The server
   * treats an absent field as a no, so the default lives here rather than there
   * -- a caller that predates the field must not start planting sample catalogs.
   *
   * Off by default for a restart, set in `load()` from whether a run already
   * exists. This panel is not only the first-run panel: `canStartNew` also shows
   * it over a `CANCELLED` or `FAILED` run, and a tenant on its second run has
   * usually spent the time between the two authoring something. The server's own
   * decline only sees a *published* menu, so a draft the owner is mid-way
   * through is invisible to it -- on by default there would publish a sample
   * over a tenant that is nearly ready, silently. The operator can still tick
   * the box; what changes is that it is now a choice rather than a default.
   */
  protected readonly sampleMenu = signal(true);
  protected readonly actionError = signal<string | null>(null);

  protected readonly resumeReason = signal('');
  protected readonly resuming = signal(false);
  protected readonly resumeMessage = signal<string | null>(null);

  protected readonly activateReason = signal('');
  protected readonly activating = signal(false);
  protected readonly activationOutcomeKey = signal<string | null>(null);
  protected readonly pendingApprovalId = signal<string | null>(null);
  protected readonly validation = signal<ValidationOutcome | null>(null);
  protected readonly validating = signal(false);
  protected readonly cancelReason = signal('');
  protected readonly cancelling = signal(false);
  protected readonly suggestion = signal<OnboardingTemplateSuggestion | null>(null);
  protected readonly templates = signal<readonly OnboardingTemplateView[]>([]);
  protected readonly selectedTemplateId = signal<string | null>(null);

  /** Active versions to choose from; the list needs platform scope, so it may be empty. */
  protected readonly activeTemplates = computed(() =>
    this.templates().filter((template) => template.status === 'ACTIVE'),
  );

  /** The template a new run will start under: the operator's choice, else the suggestion. */
  protected readonly selectedTemplate = computed(
    () =>
      this.activeTemplates().find((template) => template.id === this.selectedTemplateId()) ??
      this.suggestion()?.template ??
      null,
  );

  constructor() {
    const locale = this.i18n.locale();
    this.ownerLocale.set(locale === 'uz-Latn' ? 'uz' : locale === 'en' ? 'en' : 'ru');
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const run = await this.tenantsApi.currentOnboardingRun(this.tenantId);
      this.run.set(run);
      this.runId.set(run?.run.id ?? null);
      // A restart is not a first run: see `sampleMenu`. Keyed on "has this
      // tenant a run at all" rather than on the previous run's
      // SAMPLE_MENU_PUBLISH status, because the harmful case is the one where
      // that step was SKIPPED (the owner has been authoring instead), not the
      // one where it completed.
      this.sampleMenu.set(run === null);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
    await this.loadInvitation();
    try {
      const suggestion = await this.tenantsApi.suggestedOnboardingTemplate(this.tenantId);
      this.suggestion.set(suggestion);
      this.selectedTemplateId.set(suggestion.template.id);
    } catch {
      // Without the suggestion the server still picks one when the run starts;
      // the panel just cannot name it in advance.
      this.suggestion.set(null);
    }
    try {
      this.templates.set(await this.tenantsApi.onboardingTemplates());
    } catch {
      // Listing every template needs platform scope; without it there is
      // nothing to choose between, and the suggestion stands.
      this.templates.set([]);
    }
  }

  private async loadInvitation(): Promise<void> {
    try {
      this.invitation.set(await this.tenantsApi.ownerInvitation(this.tenantId));
    } catch {
      // The run itself still renders; only the invitation panel is missing.
      this.invitation.set(null);
    }
  }

  /** The owner step linked an owner; with no invitation on record, one can still be sent. */
  protected ownerLinked(view: OnboardingRunView | null): boolean {
    return (
      view?.steps.some(
        (step) => step.stepKey === 'TENANT_OWNER_LINK_OR_INVITE' && step.status === 'COMPLETED',
      ) ?? false
    );
  }

  protected invitationHint(view: OwnerInvitationView): MessageKey | null {
    const code = view.state === 'EXPIRED' ? 'EXPIRED' : view.lastErrorCode;
    return code !== null && INVITATION_HINTS.has(code)
      ? (`onboarding.invitation.hint.${code}` as MessageKey)
      : null;
  }

  protected when(instant: string | null): string {
    return instant === null ? '' : this.i18n.dateTime(new Date(instant));
  }

  /**
   * The address whole when the server sent it -- which it does only for
   * `TENANT_ONBOARDING_MANAGE`, recording the reveal (ADR 0100) -- and ADR
   * 0097's mask otherwise. Never both: a mask shown beside the real address
   * would be noise, and the real address is the one being checked against
   * what was typed at onboarding.
   */
  protected recipientOf(view: OwnerInvitationView): string | null {
    return view.recipient ?? view.emailMasked;
  }

  protected eventKey(type: string): MessageKey {
    return `onboarding.invitation.event.${type}` as MessageKey;
  }

  protected actorKey(actorType: string): MessageKey {
    return `onboarding.invitation.actor.${actorType}` as MessageKey;
  }

  protected async resendInvitation(event: Event): Promise<void> {
    event.preventDefault();
    const reason = this.resendReason().trim();
    if (reason.length === 0 || this.resending()) {
      return;
    }
    this.resending.set(true);
    this.resent.set(false);
    this.actionError.set(null);
    try {
      await this.tenantsApi.resendOwnerInvitation(
        this.tenantId,
        reason,
        this.resendLocale() || (this.invitation() === null ? this.ownerLocale() : undefined),
      );
      this.resendReason.set('');
      this.resent.set(true);
      await this.loadInvitation();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.resending.set(false);
    }
  }

  protected businessTypeLabel(code: string | null): string {
    return code === null ? '' : this.i18n.t(`businessTypes.type.${code}` as MessageKey);
  }

  protected stepName(stepKey: string): string {
    return STEP_NAMES.has(stepKey)
      ? this.i18n.t(`onboarding.step.${stepKey}` as MessageKey)
      : stepKey;
  }

  protected hint(
    errorCode: string | null,
  ): { readonly key: MessageKey; readonly link?: HintLink } | null {
    return errorCode === null ? null : (HINTS[errorCode] ?? null);
  }

  protected hintRoute(link: HintLink): readonly string[] {
    return link === 'installations'
      ? ['/providers', 'installations']
      : ['/tenants', this.tenantId, link];
  }

  /** A run that has ended can be replaced by a fresh one; one in flight cannot. */
  protected canStartNew(view: OnboardingRunView | null): boolean {
    return view === null || ENDED.has(view.run.status);
  }

  protected canCancel(view: OnboardingRunView): boolean {
    return !ENDED.has(view.run.status) && view.run.status !== 'ACTIVE';
  }

  protected async validate(): Promise<void> {
    const runId = this.runId();
    if (runId === null || this.validating()) {
      return;
    }
    this.validating.set(true);
    this.actionError.set(null);
    try {
      this.validation.set(await this.tenantsApi.validateOnboarding(this.tenantId, runId));
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.validating.set(false);
    }
  }

  protected async cancel(event: Event): Promise<void> {
    event.preventDefault();
    const runId = this.runId();
    if (runId === null || this.cancelReason().trim().length === 0 || this.cancelling()) {
      return;
    }
    this.cancelling.set(true);
    this.actionError.set(null);
    try {
      await this.tenantsApi.cancelOnboarding(this.tenantId, runId, this.cancelReason().trim());
      this.cancelReason.set('');
      this.validation.set(null);
      await this.reloadRun();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.cancelling.set(false);
    }
  }

  protected async startRun(event: Event): Promise<void> {
    event.preventDefault();
    this.starting.set(true);
    this.actionError.set(null);
    try {
      const { runId } = await this.tenantsApi.startOnboarding(
        this.tenantId,
        this.ownerEmail().trim() || undefined,
        undefined,
        this.selectedTemplate()?.id,
        this.ownerLocale(),
        this.sampleMenu(),
      );
      this.runId.set(runId);
      this.validation.set(null);
      await this.reloadRun();
      await this.loadInvitation();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.starting.set(false);
    }
  }

  private async reloadRun(): Promise<void> {
    const run = await this.tenantsApi.currentOnboardingRun(this.tenantId);
    this.run.set(run);
  }

  protected async resume(event: Event): Promise<void> {
    event.preventDefault();
    const runId = this.runId();
    if (runId === null || this.resumeReason().trim().length === 0) {
      return;
    }
    this.resuming.set(true);
    this.actionError.set(null);
    this.resumeMessage.set(null);
    try {
      const { reopenedSteps } = await this.tenantsApi.resumeOnboarding(
        this.tenantId,
        runId,
        this.resumeReason().trim(),
      );
      this.resumeMessage.set(this.i18n.t('onboarding.resume.result', { count: reopenedSteps }));
      this.resumeReason.set('');
      await this.reloadRun();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.resuming.set(false);
    }
  }

  protected async activate(event: Event): Promise<void> {
    event.preventDefault();
    const runId = this.runId();
    if (runId === null || this.activateReason().trim().length === 0) {
      return;
    }
    this.activating.set(true);
    this.actionError.set(null);
    this.activationOutcomeKey.set(null);
    this.pendingApprovalId.set(null);
    try {
      const outcome = await this.tenantsApi.activateOnboarding(
        this.tenantId,
        runId,
        this.activateReason().trim(),
      );
      this.activationOutcomeKey.set(outcome.outcome);
      this.pendingApprovalId.set(outcome.approvalRequestId);
      this.activateReason.set('');
      await this.reloadRun();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.activating.set(false);
    }
  }
}
