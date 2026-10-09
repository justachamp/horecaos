import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { TPipe } from '../../../core/i18n/t.pipe';
import { PromptPolicyPanel } from './prompt-policy-panel';
import { RecoveryPolicyPanel } from './recovery-policy-panel';
import { TagLibraryPanel } from './tag-library-panel';
import { VisibilityPanel } from './visibility-panel';
import { MessageKey } from '../../../core/i18n/messages.en';

export type FeedbackTab = 'tags' | 'prompt' | 'recovery' | 'visibility';
export type FeedbackTarget = 'TENANT' | 'BRAND';

const TABS: readonly FeedbackTab[] = ['tags', 'prompt', 'recovery', 'visibility'];

const TAB_KEYS: Readonly<Record<FeedbackTab, MessageKey>> = {
  tags: 'customers.feedback.tab.tags',
  prompt: 'customers.feedback.tab.prompt',
  recovery: 'customers.feedback.tab.recovery',
  visibility: 'customers.feedback.tab.visibility',
};

/**
 * 5.5, Feedback settings (ADR 0159, Decisions 3, 4, 5 and 8): the tag library, when a customer is
 * asked to rate an order, which reviews open a recovery case, and what the public may see.
 *
 * <p>It is a settings screen, not a work queue, so it follows the settings screens' rule: reading is
 * `tenant.configuration.read`, changing is `tenant.configuration.write`, and a reader who cannot
 * write sees the values with the fields disabled. The server decides either way (ADR 0025).
 *
 * <p>Each policy is edited at the whole company or at the current brand; a brand that has saved
 * nothing of its own follows the company, and the panel says so. The tag library is the tenant's one
 * list and has no brand level.
 */
@Component({
  selector: 'q-feedback-settings-page',
  imports: [TPipe, TagLibraryPanel, PromptPolicyPanel, RecoveryPolicyPanel, VisibilityPanel],
  templateUrl: './feedback-settings-page.html',
  styleUrl: './feedback-settings-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FeedbackSettingsPage {
  private readonly brand = inject(CurrentBrand);
  private readonly capabilities = inject(SessionCapabilities);

  protected readonly state = signal<'loading' | 'ready' | 'denied'>('loading');
  protected readonly tab = signal<FeedbackTab>('tags');
  protected readonly target = signal<FeedbackTarget>('TENANT');

  protected readonly tabs = TABS;
  protected readonly tabKey = (tab: FeedbackTab): MessageKey => TAB_KEYS[tab];

  protected readonly scope = computed(() => this.brand.scope());
  protected readonly canWrite = computed(() => this.capabilities.has('TENANT_CONFIGURATION_WRITE'));
  protected readonly showsTarget = computed(() => this.tab() !== 'tags');

  constructor() {
    void this.load();
  }

  protected select(tab: FeedbackTab): void {
    this.tab.set(tab);
  }

  protected setTarget(value: string): void {
    this.target.set(value === 'BRAND' ? 'BRAND' : 'TENANT');
  }

  private async load(): Promise<void> {
    await this.brand.ensureLoaded();
    this.state.set(this.brand.scope() ? 'ready' : 'denied');
  }
}
