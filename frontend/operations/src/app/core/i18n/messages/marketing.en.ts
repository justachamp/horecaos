/**
 * English messages of the `marketing` area (namespaces `marketing`).
 *
 * This file defines the key set of its area: `marketingRu` and `marketingUzLatn`
 * are typed against it, so a key missing from either is a compile error. Which area a key belongs to is
 * decided by its prefix, in `../message-areas.ts`; `../messages.en.ts` puts the areas back together.
 */
export const marketingEn = {
  'marketing.nav.label': 'Marketing sections',
  'marketing.nav.promotions': 'Promotions',
  'marketing.nav.promoCodes': 'Promo codes',
  'marketing.nav.loyalty': 'Loyalty',
  'marketing.nav.referrals': 'Referrals',
  'marketing.nav.campaigns': 'Campaigns',
  'marketing.nav.automations': 'Automations',
  'marketing.nav.content': 'Content',
  'marketing.nav.storefront': 'Storefront',

  'marketing.dialog.cancel': 'Cancel',

  'marketing.campaigns.title': 'Campaigns',
  'marketing.campaigns.tab.campaigns': 'Campaigns',
  'marketing.campaigns.tab.audiences': 'Audiences',
  'marketing.campaigns.tab.suppressions': 'Suppressions',
  'marketing.campaigns.loading': 'Loading',
  'marketing.campaigns.denied': 'No access to this brand’s campaigns',
  'marketing.campaigns.empty': 'No campaigns drafted yet',
  'marketing.campaigns.list.awaitingSignature': 'Awaiting a second signature',
  'marketing.campaigns.list.haltedScheduledSend':
    'Scheduled send did not go out — needs re-scheduling',
  'marketing.campaigns.column.name': 'Name',
  'marketing.campaigns.column.channel': 'Channel',
  'marketing.campaigns.column.status': 'Status',
  'marketing.campaigns.column.recipients': 'Recipients (est.)',
  'marketing.campaigns.column.cost': 'Cost (est.)',
  'marketing.campaigns.create.action': 'New campaign',
  'marketing.campaigns.create.title': 'Draft a campaign',
  'marketing.campaigns.create.name': 'Name',
  'marketing.campaigns.create.audience': 'Audience',
  'marketing.campaigns.create.audience.empty':
    'No audiences yet — define one on the Audiences tab first',
  'marketing.campaigns.create.channel': 'Channel',
  'marketing.campaigns.create.template': 'Template',
  'marketing.campaigns.create.template.manualHint':
    'No active marketing template on this channel was found for this brand. Type its key exactly as registered.',
  'marketing.campaigns.create.consentPurpose': 'Consent purpose (from the template): {purpose}',
  'marketing.campaigns.create.recipientCap': 'Recipient cap',
  'marketing.campaigns.create.costCeiling': 'Cost ceiling (minor units)',
  'marketing.campaigns.create.currency': 'Currency',
  'marketing.campaigns.create.scheduledAt': 'Scheduled send (optional)',
  'marketing.campaigns.create.scheduledAt.hint':
    'Leave blank to launch immediately, on an operator’s word.',
  'marketing.campaigns.create.submit': 'Save draft',

  'marketing.channel.SMS': 'SMS',
  'marketing.channel.EMAIL': 'Email',
  'marketing.channel.PUSH': 'Push',
  'marketing.channel.MESSAGING_APP': 'Telegram',

  'marketing.campaign.status.DRAFT': 'Draft',
  'marketing.campaign.status.IN_REVIEW': 'In review',
  'marketing.campaign.status.APPROVED': 'Approved',
  'marketing.campaign.status.SCHEDULED': 'Scheduled',
  'marketing.campaign.status.SENDING': 'Sending',
  'marketing.campaign.status.PAUSED': 'Paused',
  'marketing.campaign.status.SENT': 'Sent',
  'marketing.campaign.status.PARTIALLY_SENT': 'Partially sent',
  'marketing.campaign.status.HALTED_BUDGET': 'Halted (budget)',
  'marketing.campaign.status.HALTED_OPERATOR': 'Halted (operator)',
  'marketing.campaign.status.CANCELLED': 'Cancelled',

  'marketing.campaign.close': 'Close',
  'marketing.campaign.field.channel': 'Channel',
  'marketing.campaign.field.recipientCap': 'Recipient cap',
  'marketing.campaign.field.estimatedRecipients': 'Estimated recipients',
  'marketing.campaign.field.upperBoundHint':
    'an upper bound, not a promise — the same checks run again at send',
  'marketing.campaign.field.estimatedCost': 'Estimated cost',
  'marketing.campaign.field.rangeHint':
    'a range, not a promise — a personalised name changes the length',
  'marketing.campaign.field.costUnknown':
    'Not knowable yet — no active template, or no configured price',
  'marketing.campaign.field.estimatedDelivery': 'Estimated delivery window',
  'marketing.campaign.field.seconds': 'seconds',
  'marketing.campaign.field.deliveryHint': 'a planning number, not a promise',
  'marketing.campaign.field.deliveryUnknown':
    'Not knowable yet — this channel has no configured pacing ceiling',
  'marketing.campaign.field.costCeiling': 'Cost ceiling',
  'marketing.campaign.field.spent': 'Spent',
  'marketing.campaign.field.reserved': 'Reserved (cost · recipients)',
  'marketing.campaign.field.scheduledAt': 'Scheduled for',
  'marketing.campaign.unwired.warning':
    'This campaign cannot be launched on {channel} for this brand yet.',
  'marketing.campaign.paused.blockedCount':
    'Paused by the block-rate guard: {count} recipient(s) have blocked this send so far.',
  'marketing.campaign.resume.suppressedCost':
    'The pause cost {count} message(s): already queued while paused, and not retried.',
  'marketing.campaign.action.estimate': 'Estimate',
  'marketing.campaign.action.reestimate': 'Re-estimate',
  'marketing.campaign.action.submit': 'Submit for review',
  'marketing.campaign.action.approve': 'Approve',
  'marketing.campaign.action.launch': 'Launch',
  'marketing.campaign.action.halt': 'Halt',
  'marketing.campaign.action.resume': 'Resume',
  'marketing.campaign.action.reschedule': 'Re-schedule',
  'marketing.campaign.fourEyes.awaitingSignature':
    'Submitted for review. This campaign needs a second signature from somebody other than you.',
  'marketing.campaign.entitlement.telegramBroadcasts':
    'This plan does not include Telegram broadcast messaging (telegram.broadcasts.enabled). Contact HorecaOS to enable it.',
  'marketing.campaign.recipients.title': 'Recipients',
  'marketing.campaign.recipients.empty': 'No recipients yet',
  'marketing.campaign.recipients.column.status': 'Outcome',
  'marketing.campaign.recipients.column.reason': 'Reason',
  'marketing.campaign.reasonPrompt.title.approve': 'Approve this campaign',
  'marketing.campaign.reasonPrompt.title.halt': 'Halt this campaign',
  'marketing.campaign.reasonPrompt.title.resume': 'Resume this campaign',
  'marketing.campaign.reasonPrompt.reason': 'Reason',
  'marketing.campaign.reasonPrompt.confirm': 'Confirm',

  'marketing.campaign.halted.banner':
    'This scheduled send did not go out: {reason}. Re-schedule it for a new moment, or launch it now.',
  'marketing.campaign.reschedulePrompt.title': 'Re-schedule this send',
  'marketing.campaign.reschedulePrompt.label': 'New moment',
  'marketing.campaign.reschedulePrompt.confirm': 'Re-schedule',

  'marketing.campaign.stats.title': 'History & statistics',
  'marketing.campaign.stats.queued': 'Sent (queued for delivery)',
  'marketing.campaign.stats.pending': 'Pending',
  'marketing.campaign.stats.deferred': 'Deferred',
  'marketing.campaign.stats.refused': 'Refused',
  'marketing.campaign.stats.total': 'Total attempted',
  'marketing.campaign.stats.refusedByReason.title': 'Refused, by reason',
  'marketing.campaign.stats.deliveryUnavailableHint':
    'Delivered vs. failed is not tracked yet — no read receipt reaches this campaign.',
  'marketing.campaign.stats.export': 'Export recipients as CSV',
  'marketing.campaign.stats.exportedCount': 'Exported {count} account id(s)',

  'marketing.refusal.ACCOUNT_NOT_ACTIVE': 'Account not active, merged, or anonymised',
  'marketing.refusal.CONSENT_WITHHELD': 'No marketing consent on file',
  'marketing.refusal.SUPPRESSED': 'Suppressed',
  'marketing.refusal.FREQUENCY_CAP_REACHED': 'Already at the frequency cap',
  'marketing.refusal.NO_VERIFIED_ENDPOINT': 'No verified endpoint for this channel',
  'marketing.refusal.CAMPAIGN_HALTED': 'The campaign stopped before reaching this recipient',

  'marketing.audiences.empty': 'No audiences defined yet',
  'marketing.audiences.column.name': 'Name',
  'marketing.audiences.column.description': 'Description',
  'marketing.audiences.column.status': 'Status',
  'marketing.audiences.create.action': 'New audience',
  'marketing.audiences.create.title': 'Define an audience',
  'marketing.audiences.create.name': 'Name',
  'marketing.audiences.create.description': 'Description (optional)',
  'marketing.audiences.create.predicates': 'Predicates',
  'marketing.audiences.create.addPredicate': 'Add predicate',
  'marketing.audiences.create.removePredicate': 'Remove',
  'marketing.audiences.create.submit': 'Define audience',

  'marketing.audiences.detail.view': 'View',
  'marketing.audiences.detail.title': 'Audience predicates',
  'marketing.audiences.detail.version': 'Definition version {version}',
  'marketing.audiences.detail.noPredicates': 'No predicates',
  'marketing.audiences.detail.edit': 'Edit predicates',
  'marketing.audiences.detail.save': 'Save predicates',

  'marketing.predicate.type.RECENCY_DAYS': 'Days since last order',
  'marketing.predicate.type.ORDER_COUNT': 'Order count',
  'marketing.predicate.type.COMPLETED_ORDER_COUNT': 'Completed order count',
  'marketing.predicate.type.NET_SPEND_MINOR': 'Net spend',
  'marketing.predicate.type.AVERAGE_CHECK_MINOR': 'Average check',
  'marketing.predicate.type.ACQUISITION_CHANNEL': 'Acquisition channel',
  'marketing.predicate.type.REGISTERED_BETWEEN': 'Registered between',
  'marketing.predicate.type.BIRTHDAY_WITHIN_DAYS': 'Birthday within (days)',
  'marketing.predicate.type.PREFERRED_LOCALE': 'Preferred locale',
  'marketing.predicate.type.AUDIENCE_MEMBERSHIP': 'Member of audience',

  'marketing.predicate.operator.AT_LEAST': 'At least',
  'marketing.predicate.operator.AT_MOST': 'At most',
  'marketing.predicate.operator.BETWEEN': 'Between',
  'marketing.predicate.operator.IN': 'In',
  'marketing.predicate.operator.NOT_IN': 'Not in',

  'marketing.predicate.field.numericLow': 'Value',
  'marketing.predicate.field.numericHigh': 'To',
  'marketing.predicate.field.textValues': 'Values, comma-separated',
  'marketing.predicate.field.fixedValuesHint': 'one of: {values}',

  'marketing.suppressions.empty': 'No suppressions recorded',
  'marketing.suppressions.activeOnlyToggle': 'Active only',
  'marketing.suppressions.column.channel': 'Channel',
  'marketing.suppressions.column.reason': 'Reason',
  'marketing.suppressions.column.appliedBy': 'Recorded by',
  'marketing.suppressions.column.statedReason': 'Stated reason',
  'marketing.suppressions.lift.action': 'Lift',
  'marketing.suppressions.lift.title': 'Lift this suppression',
  'marketing.suppressions.lift.reason': 'Reason',
  'marketing.suppressions.lift.submit': 'Lift',
  'marketing.suppressions.record.action': 'Record a suppression',
  'marketing.suppressions.record.title': 'Record a suppression',
  'marketing.suppressions.record.customerAccountId': 'Customer account ID',
  'marketing.suppressions.record.channel': 'Channel',
  'marketing.suppressions.record.channel.everyChannel': 'Every channel',
  'marketing.suppressions.record.reason': 'Reason',
  'marketing.suppressions.record.statedReason': 'Stated reason (optional)',
  'marketing.suppressions.record.submit': 'Record',
  'marketing.suppressions.reason.UNSUBSCRIBE': 'Unsubscribed',
  'marketing.suppressions.reason.HARD_BOUNCE': 'Hard bounce',
  'marketing.suppressions.reason.INVALID_NUMBER': 'Invalid number',
  'marketing.suppressions.reason.COMPLAINT': 'Complaint',
  'marketing.suppressions.reason.OPERATOR_BLOCK': 'Blocked by an operator',

  // ------------------------------------------------------------- courier broadcasts 6.4b (T18)
  'marketing.courierBroadcasts.title': 'Courier broadcasts',
  'marketing.courierBroadcasts.intro':
    'A dispatcher’s own operational SMS blast to couriers — a shift change, a weather closure, a route closure. Never a customer campaign.',
  'marketing.courierBroadcasts.loading': 'Loading',
  'marketing.courierBroadcasts.empty': 'No broadcast sent yet',
  'marketing.courierBroadcasts.create.action': 'New broadcast',
  'marketing.courierBroadcasts.create.title': 'Draft a courier broadcast',
  'marketing.courierBroadcasts.create.targetKind': 'Target',
  'marketing.courierBroadcasts.create.targetKind.ALL_ACTIVE': 'Every active courier',
  'marketing.courierBroadcasts.create.targetKind.GROUP': 'One courier group',
  'marketing.courierBroadcasts.create.targetGroupId': 'Group ID',
  'marketing.courierBroadcasts.create.message': 'Message',
  'marketing.courierBroadcasts.create.submit': 'Save draft',
  'marketing.courierBroadcasts.column.target': 'Target',
  'marketing.courierBroadcasts.column.message': 'Message',
  'marketing.courierBroadcasts.column.status': 'Status',
  'marketing.courierBroadcasts.column.recipients': 'Recipients',
  'marketing.courierBroadcasts.status.DRAFT': 'Draft',
  'marketing.courierBroadcasts.status.SENT': 'Sent',
  'marketing.courierBroadcasts.status.FAILED': 'Failed',
  'marketing.courierBroadcasts.action.send': 'Send',
  'marketing.courierBroadcasts.refusalReason': 'Refused: {reason}',

  // --------------------------------------------------------- attribution links 6.6a (ADR 0044, T18)
  'marketing.attributionLinks.title': 'Acquisition links',
  'marketing.attributionLinks.intro':
    'A trackable website ?ref= link or Telegram deep link, for a campaign or an influencer.',
  'marketing.attributionLinks.empty': 'No link minted yet',
  'marketing.attributionLinks.create.action': 'Mint a link',
  'marketing.attributionLinks.create.title': 'Mint an acquisition link',
  'marketing.attributionLinks.create.label': 'Label',
  'marketing.attributionLinks.create.ownerNote': 'Note (optional)',
  'marketing.attributionLinks.create.channel': 'Channel',
  'marketing.attributionLinks.create.destinationType': 'Destination',
  'marketing.attributionLinks.create.destinationId': 'Campaign ID',
  'marketing.attributionLinks.create.submit': 'Mint link',
  'marketing.attributionLinks.column.label': 'Label',
  'marketing.attributionLinks.column.link': 'Link',
  'marketing.attributionLinks.column.clicks': 'Clicks',
  'marketing.attributionLinks.column.status': 'Status',
  'marketing.attributionLinks.action.archive': 'Archive',
  'marketing.attributionLinks.channel.WEB': 'Website',
  'marketing.attributionLinks.channel.TELEGRAM_BOT': 'Telegram bot',
  'marketing.attributionLinks.channel.TELEGRAM_MINI_APP': 'Telegram mini app',
  'marketing.attributionLinks.channel.MOBILE_APP': 'Mobile app',
  'marketing.attributionLinks.destinationType.CAMPAIGN': 'A campaign',
  'marketing.attributionLinks.destinationType.STOREFRONT_HOME': 'The storefront home',
  'marketing.attributionLinks.destinationType.INFLUENCER': 'An influencer',

  // ------------------------------------------------------- promo codes 6.2 (ADR 0072, wave 60)
  'marketing.promoCodes.title': 'Promo codes',
  'marketing.promoCodes.intro':
    'A code a customer types into their cart. A closed set of three discount shapes — percentage off the order, a fixed amount off the order, or free delivery — never an item-level or time-windowed rule (ADR 0072).',
  'marketing.promoCodes.loading': 'Loading',
  'marketing.promoCodes.denied': 'No access to this brand’s promo codes',
  'marketing.promoCodes.create.action': 'New promo code',
  'marketing.promoCodes.create.title': 'Draft a promo code',
  'marketing.promoCodes.create.submit': 'Save draft',
  'marketing.promoCodes.dialog.cancel': 'Cancel',
  'marketing.promoCodes.empty': 'No promo code authored yet.',
  'marketing.promoCodes.column.code': 'Code',
  'marketing.promoCodes.column.name': 'Name',
  'marketing.promoCodes.column.shape': 'Shape',
  'marketing.promoCodes.column.value': 'Value',
  'marketing.promoCodes.column.minBasket': 'Minimum basket',
  'marketing.promoCodes.column.redeemed': 'Redeemed',
  'marketing.promoCodes.column.status': 'Status',
  'marketing.promoCodes.action.activate': 'Activate',
  'marketing.promoCodes.action.retire': 'Retire',
  'marketing.promoCodes.shape.PERCENTAGE_OFF_ORDER': 'Percentage off the order',
  'marketing.promoCodes.shape.FIXED_AMOUNT_OFF_ORDER': 'Fixed amount off the order',
  'marketing.promoCodes.shape.FREE_DELIVERY': 'Free delivery',
  'marketing.promoCodes.status.DRAFT': 'Draft',
  'marketing.promoCodes.status.SUSPENDED': 'Not yet active',
  'marketing.promoCodes.status.ACTIVE': 'Active',
  'marketing.promoCodes.status.EXHAUSTED': 'Exhausted',
  'marketing.promoCodes.status.ARCHIVED': 'Retired',
  'marketing.promoCodes.reveal.label': 'This code is',
  'marketing.promoCodes.reveal.hint':
    'Shown once, now — the code is stored only as a hash, so this is the only time it can be read back. Write it down before dismissing this.',
  'marketing.promoCodes.reveal.dismiss': 'Got it',

  // Promotion condition names, shown by q-condition-builder (ADR 0140, row 6.1). The rest of
  // the Promotions screen's text is a lazy table: features/marketing/promotions/promotion-texts.ts.
  'marketing.promotions.condition.SUBTOTAL_AT_LEAST': 'Basket subtotal',
  'marketing.promotions.condition.QUANTITY_AT_LEAST': 'Number of matching items',
  'marketing.promotions.condition.PRODUCT': 'Product',
  'marketing.promotions.condition.CATEGORY': 'Category (with its sub-categories)',
  'marketing.promotions.condition.VARIANT': 'Variant (size, portion)',
  'marketing.promotions.condition.CHANNEL': 'Sales channel',
  'marketing.promotions.condition.CHANNEL_TYPE': 'Kind of channel',
  'marketing.promotions.condition.LOCATION': 'Branch',
  'marketing.promotions.condition.FULFILLMENT_MODE': 'Order type',
  'marketing.promotions.condition.PAYMENT_METHOD': 'Payment method',
  'marketing.promotions.condition.DELIVERY_ZONE': 'Delivery zone',
  'marketing.promotions.condition.CUSTOMER_SEGMENT': 'Customer segment',
  'marketing.promotions.condition.DAY_OF_WEEK': 'Weekday',
  'marketing.promotions.condition.TIME_OF_DAY': 'Time of day (branch time)',
  'marketing.promotions.condition.FIRST_ORDER': 'The customer’s first order',
  'marketing.promotions.condition.ORDER_FIRST_CHANNEL':
    'The customer’s first order through this channel',
  'marketing.promotions.condition.ORDER_NTH': 'The customer’s order number',
  'marketing.promotions.condition.ORDER_EVERY_NTH': 'Every n-th order of the customer',
  'marketing.promoCodes.form.name': 'Name (internal, for this list)',
  'marketing.promoCodes.form.code': 'Code a customer types',
  'marketing.promoCodes.form.code.hint': '4-32 letters or digits. Shown once after saving.',
  'marketing.promoCodes.form.shape': 'Discount shape',
  'marketing.promoCodes.form.percent': 'Percentage off the order, %',
  'marketing.promoCodes.form.amount': 'Amount off the order',
  'marketing.promoCodes.form.hasCap': 'Cap the maximum discount',
  'marketing.promoCodes.form.cap': 'Maximum discount',
  'marketing.promoCodes.form.minBasket': 'Minimum basket to qualify',
  'marketing.promoCodes.form.hasTotalLimit': 'Limit total redemptions',
  'marketing.promoCodes.form.totalLimit': 'Total redemptions allowed',
  'marketing.promoCodes.form.perCustomerLimit': 'Redemptions per customer',
  'marketing.promoCodes.form.perCustomerLimit.hint':
    'Not enforced for a guest checkout, which has no account to count against.',
  'marketing.promoCodes.form.validFrom': 'Starts',
  'marketing.promoCodes.form.validFrom.hint':
    'Left blank, the code starts working the moment it is activated.',
  'marketing.promoCodes.form.hasValidUntil': 'Give this code an expiry date',
  'marketing.promoCodes.form.validUntil': 'Expires at the end of',
  'marketing.promoCodes.form.channels': 'Restrict to channels',
  'marketing.promoCodes.form.channels.hint': 'Leave every box unchecked to allow every channel.',
  'marketing.promoCodes.form.locations': 'Restrict to branches',
  'marketing.promoCodes.form.locations.hint': 'Leave every box unchecked to allow every branch.',
  'marketing.promoCodes.column.expiry': 'Expiry',
  'marketing.promoCodes.redemptions.title': 'Redemptions of ···{hint}',
  'marketing.promoCodes.redemptions.loading': 'Loading redemptions…',
  'marketing.promoCodes.redemptions.empty': 'Nobody has redeemed this code yet.',
  'marketing.promoCodes.redemptions.close': 'Close',
  'marketing.promoCodes.redemptions.whoNote':
    'This list does not name the customer. To see who redeemed a code, open Marketing reports › Promotions › Redemption log and choose “Show customer”: that needs the customer permission and is recorded in the audit log.',
  'marketing.promoCodes.redemptions.column.order': 'Order',
  'marketing.promoCodes.redemptions.column.amount': 'Discount',
  'marketing.promoCodes.redemptions.column.status': 'Status',
  'marketing.promoCodes.redemptions.column.when': 'When',
  'marketing.promoCodes.redemptions.status.RESERVED': 'Reserved',
  'marketing.promoCodes.redemptions.status.REDEEMED': 'Redeemed',
  'marketing.promoCodes.redemptions.status.RELEASED': 'Released',

  // ---------------------------------------------------------------- loyalty 6.3 (ADR 0046, wave 44)
  'marketing.loyalty.title': 'Loyalty',
  'marketing.loyalty.intro':
    'Cashback points, not stored value. A customer earns and spends points against the numbers below — there is no prepaid balance, because HorecaOS holds no customer funds (ADR 0046).',
  'marketing.loyalty.loading': 'Loading',
  'marketing.loyalty.denied': 'No access to this brand’s loyalty policy',
  'marketing.loyalty.liability.label': 'Outstanding points owed by this brand',
  'marketing.loyalty.liability.held': '{amount} held by an unfinished checkout',
  'marketing.loyalty.column.scope': 'Applies to',
  'marketing.loyalty.column.status': 'Status',
  'marketing.loyalty.uncapped': 'Uncapped',
  'marketing.loyalty.hours': '{count} h',
  'marketing.loyalty.days': '{count} d',
  'marketing.loyalty.yes': 'Yes',
  'marketing.loyalty.no': 'No',
  'marketing.loyalty.status.DRAFT': 'Draft',
  'marketing.loyalty.status.ACTIVE': 'Active',
  'marketing.loyalty.status.RETIRED': 'Retired',
  'marketing.loyalty.action.activate': 'Activate',
  'marketing.loyalty.action.retire': 'Retire',
  'marketing.loyalty.dialog.cancel': 'Cancel',
  'marketing.loyalty.scope.brand': 'Whole brand',
  'marketing.loyalty.scope.location': 'One branch',
  'marketing.loyalty.scope.channel': 'One channel',

  'marketing.loyalty.accrual.title': 'Accrual',
  'marketing.loyalty.accrual.hint':
    'What a customer earns on a paid order. A brand with no active rule accrues nothing — there is no built-in default rate.',
  'marketing.loyalty.accrual.create.action': 'New accrual rule',
  'marketing.loyalty.accrual.create.title': 'Draft an accrual rule',
  'marketing.loyalty.accrual.create.submit': 'Save draft',
  'marketing.loyalty.accrual.column.rate': 'Rate',
  'marketing.loyalty.accrual.column.cap': 'Cap per order',
  'marketing.loyalty.accrual.column.earnDelay': 'Earn delay',
  'marketing.loyalty.accrual.column.lotLifetime': 'Expires after',
  'marketing.loyalty.accrual.empty': 'No accrual rule authored yet.',
  'marketing.loyalty.accrual.form.rate': 'Rate, % of the money-settled order value',
  'marketing.loyalty.accrual.form.hasCap': 'Cap the points one order can earn',
  'marketing.loyalty.accrual.form.cap': 'Maximum points per order',
  'marketing.loyalty.accrual.form.earnDelay': 'Earn delay, hours after the order completes',
  'marketing.loyalty.accrual.form.lotLifetime': 'Points expire after, days',
  'marketing.loyalty.accrual.form.expiryWarning': 'Warn before expiry, days',
  'marketing.loyalty.accrual.form.expiryWarning.hint':
    'How many days ahead a lot counts as "about to expire" — the message that would use this is not built yet (ADR 0046).',

  'marketing.loyalty.redemption.title': 'Redemption',
  'marketing.loyalty.redemption.hint':
    'How much of an order points may cover. A brand with no active policy accepts no points as payment.',
  'marketing.loyalty.redemption.create.action': 'New redemption policy',
  'marketing.loyalty.redemption.create.title': 'Draft a redemption policy',
  'marketing.loyalty.redemption.create.submit': 'Save draft',
  'marketing.loyalty.redemption.column.share': 'Share of order value',
  'marketing.loyalty.redemption.column.minOrder': 'Minimum order',
  'marketing.loyalty.redemption.column.excludesFee': 'Excludes delivery fee',
  'marketing.loyalty.redemption.empty': 'No redemption policy authored yet.',
  'marketing.loyalty.redemption.form.share': 'Maximum share of the order, %',
  'marketing.loyalty.redemption.form.share.hint':
    'Capped at 90% — points may never cover a whole order, so a fiscal receipt and a courier’s cash always have something to point at.',
  'marketing.loyalty.redemption.form.minOrder': 'Minimum order to redeem against',
  'marketing.loyalty.redemption.form.excludesFee':
    'Exclude the delivery fee from the eligible value',

  'marketing.loyalty.deposit.title': 'Deposit accounts',
  'marketing.loyalty.deposit.body':
    'Not built, and not a gap: ADR 0046 withdrew customer-funded stored value from scope outright. HorecaOS holds no customer funds, so a prepaid deposit account raises the same question as an unlicensed payment service, and the decision is to offer points only.',
  'marketing.loyalty.posSync.title': 'POS balance sync',
  'marketing.loyalty.posSync.body':
    'Not built. No ADR owns a point-of-sale terminal reading or writing a loyalty balance, and no provider capability declares it — see frontend-information-architecture.md §6.3.',

  // -------------------------------------------------------------- referrals 6.6 (a new ADR, wave 47)
  'marketing.referrals.title': 'Referrals',
  'marketing.referrals.intro':
    'A brand-configured referral reward, paid through the loyalty ledger. Choose whether both the referrer and the new customer are rewarded, or the referrer only — a brand with no active program runs none.',
  'marketing.referrals.loading': 'Loading',
  'marketing.referrals.denied': 'No access to this brand’s referral program',
  'marketing.referrals.column.status': 'Status',
  'marketing.referrals.uncapped': 'Uncapped',
  'marketing.referrals.notApplicable': 'N/A',
  'marketing.referrals.skipped': 'Skipped',
  'marketing.referrals.days': '{count} d',
  'marketing.referrals.action.activate': 'Activate',
  'marketing.referrals.action.retire': 'Retire',
  'marketing.referrals.dialog.cancel': 'Cancel',
  'marketing.referrals.shape.BOTH_SIDES': 'Both sides rewarded',
  'marketing.referrals.shape.REFERRER_ONLY': 'Referrer only',
  'marketing.referrals.status.DRAFT': 'Draft',
  'marketing.referrals.status.ACTIVE': 'Active',
  'marketing.referrals.status.RETIRED': 'Retired',
  'marketing.referrals.status.PENDING': 'Pending',
  'marketing.referrals.status.REWARDED': 'Rewarded',
  'marketing.referrals.status.EXPIRED': 'Expired',
  'marketing.referrals.status.VOIDED': 'Voided',
  'marketing.referrals.skipReason.REFERRER_CAP_REACHED':
    'The referrer’s own reward cap was already reached',

  'marketing.referrals.summary.codesIssued': 'Codes issued',
  'marketing.referrals.summary.pending': 'Awaiting a first order',
  'marketing.referrals.summary.rewarded': 'Rewarded',
  'marketing.referrals.summary.paidOut': 'Points paid out',

  'marketing.referrals.program.title': 'Reward program',
  'marketing.referrals.program.hint':
    'The shape, the amounts, the per-referrer cap, and how long a redeemed code stays open before it lapses unqualified. A brand with no active program rewards nothing.',
  'marketing.referrals.program.create.action': 'New program',
  'marketing.referrals.program.create.title': 'Draft a referral program',
  'marketing.referrals.program.create.submit': 'Save draft',
  'marketing.referrals.program.column.shape': 'Shape',
  'marketing.referrals.program.column.referrerReward': 'Referrer reward',
  'marketing.referrals.program.column.refereeReward': 'New-customer reward',
  'marketing.referrals.program.column.cap': 'Cap per referrer',
  'marketing.referrals.program.column.window': 'Redemption window',
  'marketing.referrals.program.empty': 'No referral program authored yet.',
  'marketing.referrals.program.form.shape': 'Who is rewarded',
  'marketing.referrals.program.form.shape.hint':
    'Both sides: the referrer and the new customer are each credited on the new customer’s first completed order. Referrer only: the new customer receives nothing extra.',
  'marketing.referrals.program.form.referrerReward': 'Referrer reward, points',
  'marketing.referrals.program.form.refereeReward': 'New-customer reward, points',
  'marketing.referrals.program.form.hasCap': 'Cap how many referrals one referrer can be paid for',
  'marketing.referrals.program.form.cap': 'Maximum rewarded referrals per referrer',
  'marketing.referrals.program.form.redemptionWindow':
    'Redemption window, days to place a first completed order',
  'marketing.referrals.program.form.redemptionWindow.hint':
    'A code redeemed but not followed by a completed order within this many days lapses unqualified.',
  'marketing.referrals.program.form.lotLifetime': 'Reward points expire after, days',

  'marketing.referrals.redemptions.title': 'Referrals actually happening',
  'marketing.referrals.redemptions.hint':
    'Every code redemption this brand’s customers have made, whether it already paid out, and why a referrer’s own reward was skipped when their cap was already reached.',
  'marketing.referrals.redemptions.column.referrer': 'Referrer',
  'marketing.referrals.redemptions.column.referee': 'New customer',
  'marketing.referrals.redemptions.column.referrerReward': 'Referrer paid',
  'marketing.referrals.redemptions.column.refereeReward': 'New customer paid',
  'marketing.referrals.redemptions.empty': 'No referral has been redeemed yet.',

  // -------------------------------------------------------- automations (row 6.5, ADR 0044)
  'marketing.automations.loading': 'Loading automations…',
  'marketing.automations.denied': 'You do not have access to this brand’s automations.',
  'marketing.automations.intro':
    'Unattended triggers. A rule is authored inert and only fires once an operator arms it — nothing sends without a human.',
  'marketing.automations.create': 'New automation',
  'marketing.automations.empty': 'No automation rule authored yet.',
  'marketing.automations.viewRuns': 'Recent firings — {name}',
  'marketing.automations.rule.description':
    '{trigger} · {channel} · {configValue} · cooldown {cooldownDays}d',
  'marketing.automations.trigger.BIRTHDAY': 'Birthday',
  'marketing.automations.trigger.INACTIVITY': 'Inactivity',
  'marketing.automations.trigger.CART_ABANDONMENT': 'Cart abandonment',
  'marketing.automations.trigger.CASHBACK_CHANGE': 'Cashback change',
  'marketing.automations.configLabel.BIRTHDAY': 'Window, days either side of the birthday',
  'marketing.automations.configLabel.INACTIVITY': 'Days since last order',
  'marketing.automations.configLabel.CART_ABANDONMENT': 'Abandonment delay, hours',
  'marketing.automations.configLabel.CASHBACK_CHANGE': 'Minimum balance change, minor units',
  'marketing.automations.form.title': 'Author an automation rule',
  'marketing.automations.form.name': 'Name',
  'marketing.automations.form.trigger': 'Trigger',
  'marketing.automations.form.channel': 'Channel',
  'marketing.automations.form.cooldownDays': 'Cooldown, days',
  'marketing.automations.form.consentPurpose': 'Consent purpose',
  'marketing.automations.form.templateKey': 'Template key',
  'marketing.automations.form.submit': 'Save (inactive until armed)',
  'marketing.automations.dialog.cancel': 'Cancel',
  'marketing.automations.dialog.close': 'Close',
  'marketing.automations.runs.title': 'Recent firings — {name}',
  'marketing.automations.runs.loading': 'Loading…',
  'marketing.automations.runs.empty': 'No firing recorded yet.',
  'marketing.automations.runs.column.status': 'Status',
  'marketing.automations.runs.column.reason': 'Reason',
  'marketing.automations.runs.column.firedAt': 'When',
  'marketing.automations.runStatus.FIRED': 'Fired',
  'marketing.automations.runStatus.REFUSED': 'Refused',
  'marketing.automations.runStatus.CANCELLED': 'Cancelled',
  'marketing.automations.preview.open': 'Preview matches — {name}',
  'marketing.automations.preview.title': 'Who this rule would match today — {name}',
  'marketing.automations.preview.intro':
    'A bounded sample of today’s real customers, name masked. Nothing here sends a message or claims a cooldown.',
  'marketing.automations.preview.loading': 'Loading matches…',
  'marketing.automations.preview.empty': 'No customer matches this rule today.',
  'marketing.automations.preview.unnamed': 'Unnamed customer',
  'marketing.automations.preview.simulateTitle': 'Try a customer',
  'marketing.automations.preview.simulateIntro':
    'Type the figures of a customer to see whether this rule would fire once armed. Nothing is read from a real customer and nothing is sent.',
  'marketing.automations.preview.sampleTitle': 'Customers who match today',
  'marketing.automations.preview.outcome':
    'Sends “{template}” by {channel}, at most once every {cooldownDays} days',
  'marketing.automations.condition.BIRTHDAY': 'Days from the birthday, before or after',
  'marketing.automations.condition.INACTIVITY': 'Days since the last order',
  'marketing.automations.condition.CART_ABANDONMENT': 'Hours since the cart was left',
  'marketing.automations.condition.CASHBACK_CHANGE': 'Size of the cashback change, minor units',

  // channel wiring, refusal explanations and the fifth automation trigger (ADR 0112, ADR 0146)
  'marketing.wiring.notConnectedSuffix': ' (not connected)',
  'marketing.wiring.SMS_PURPOSE_NOT_PERMITTED':
    'This brand’s SMS account is not cleared to carry marketing messages. Sign-in codes and order messages are unaffected. Until the platform owner confirms in writing which account may carry marketing and it is named on the connection, a marketing SMS cannot be launched.',
  'marketing.wiring.NO_PROVIDER_BINDING':
    'No provider is connected to this channel for this brand. Connect one in the brand’s integrations settings.',
  'marketing.wiring.INSTALLATION_INACTIVE':
    'The provider connection for this channel is switched off.',
  'marketing.wiring.INSTALLATION_MISSING':
    'The provider connection for this channel no longer exists.',
  'marketing.wiring.SMS_ACCOUNT_MISCONFIGURED':
    'The SMS account for this brand is missing part of its configuration.',
  'marketing.wiring.PROVIDER_ADAPTER_MISMATCH':
    'The connected provider has no adapter for this channel in this release.',
  'marketing.wiring.NO_ADAPTER': 'This release has no adapter for this channel.',
  'marketing.wiring.NO_DELIVERY_ADAPTER': 'This channel has no delivery path in this release.',
  'marketing.wiring.NO_DELIVERY_ADAPTER.EMAIL':
    'Email to guests is not connected. The platform’s mail service sends staff invitations and password resets only; sending email to a tenant’s own guests is a separate decision that has not been made.',
  'marketing.wiring.NO_DELIVERY_ADAPTER.PUSH':
    'Push is not connected: no push provider exists yet, so nothing can send a push notification to a guest.',
  'marketing.wiring.UNKNOWN': 'This channel cannot deliver for this brand right now ({reason}).',
  'marketing.refusal.SCENARIO_CONFLICT':
    'Another live scenario gave this guest an offer a moment ago',
  'marketing.refusal.SCENARIO_PRIORITY_LOST': 'A broadcast outranks this step for the same guest',
  'marketing.refusal.SCENARIO_STOPPED': 'The scenario no longer applies to this guest',
  'marketing.refusal.CHANNEL_NOT_WIRED': 'The channel had no delivery path when the rule fired',
  'marketing.refusal.effect.ENDS': 'Ends this guest’s run',
  'marketing.refusal.effect.HOLDS': 'Holds the step and asks again later; never dropped',
  'marketing.refusal.effect.VARIES':
    'Usually ends the run; the recorded sentence says when it asks again instead',
  'marketing.refusal.effect.BROADCAST': 'Only a one-off broadcast meets this',
  'marketing.refusal.meaning.CONSENT_WITHHELD':
    'The guest has no positive consent for this kind of message on this channel. Absence of an answer is not consent.',
  'marketing.refusal.remedy.CONSENT_WITHHELD':
    'Nothing to do here: consent is read from the guest’s own choices and never re-decided by marketing.',
  'marketing.refusal.meaning.SUPPRESSED':
    'An active suppression covers this guest on this channel: an unsubscribe, a bounce, a complaint or an operator block. It outranks consent.',
  'marketing.refusal.remedy.SUPPRESSED':
    'If it was a mistake, lift it on the Suppressions tab, with a reason.',
  'marketing.refusal.meaning.ACCOUNT_NOT_ACTIVE':
    'The guest’s account is no longer active: closed, merged into another, or anonymised.',
  'marketing.refusal.remedy.ACCOUNT_NOT_ACTIVE': 'Nothing to do: there is no one left to message.',
  'marketing.refusal.meaning.FREQUENCY_CAP_REACHED':
    'The guest has already had as many messages as the rules allow in the window. Either the platform’s cap across every channel, or this brand’s own contact policy for this channel and purpose, stopped it; the recorded sentence says which, and the numbers.',
  'marketing.refusal.remedy.FREQUENCY_CAP_REACHED':
    'Wait: the step is asked again at the next slot. The platform’s cap cannot be raised; a brand rule can be removed on the Contact policy tab.',
  'marketing.refusal.meaning.NO_VERIFIED_ENDPOINT':
    'The guest has no verified contact of the kind this channel needs: a confirmed phone for SMS, a linked chat for Telegram.',
  'marketing.refusal.remedy.NO_VERIFIED_ENDPOINT':
    'Nothing to do here; the step is asked again tomorrow in case the guest has verified a contact since.',
  'marketing.refusal.meaning.SCENARIO_CONFLICT':
    'Another live scenario handed this guest an offer within the last day, and a second offer on top would contradict it.',
  'marketing.refusal.remedy.SCENARIO_CONFLICT':
    'Nothing to do: the step is asked again in six hours.',
  'marketing.refusal.meaning.SCENARIO_PRIORITY_LOST':
    'A broadcast is also due for this guest on the same channel, and the tenant’s channel priority order ranks its purpose at or above this scenario’s.',
  'marketing.refusal.remedy.SCENARIO_PRIORITY_LOST':
    'Nothing to do: the step is asked again in fifteen minutes, once the broadcast has gone.',
  'marketing.refusal.meaning.SCENARIO_STOPPED':
    'The scenario no longer applies to this guest or can no longer go on: its offer expired or was retired, the channel can no longer deliver, the guest met a stop or continuation condition, or the cost ceiling would have been passed.',
  'marketing.refusal.remedy.SCENARIO_STOPPED':
    'Read the recorded sentence: it names the exact cause. If it is the offer, publish a new version and revise the scenario.',
  'marketing.refusal.meaning.CAMPAIGN_HALTED':
    'The campaign stopped before it reached this recipient.',
  'marketing.refusal.remedy.CAMPAIGN_HALTED':
    'Resume the campaign if it was paused; a halted one is not restarted.',
  'marketing.automations.trigger.LATE_ORDER_APOLOGY': 'Late-order apology',
  'marketing.automations.configLabel.LATE_ORDER_APOLOGY':
    'Minutes late: an order that closed at least this long after its promised time',
  'marketing.automations.condition.LATE_ORDER_APOLOGY':
    'Minutes the order closed after it was promised',
  'marketing.automations.form.apologyNote':
    'An apology is words, never a benefit: a rule names a template and nothing else, so it cannot compensate. Support gets first refusal: an order counts only half an hour after it closed, and one with a recorded remedy (a refund or credit) is cancelled instead of being apologised to again. Once per order, under the same consent, caps and quiet hours as every other trigger.',
  'marketing.automations.rule.description.LATE_ORDER_APOLOGY':
    '{trigger} · {channel} · {configValue} min late or more · once per order',
  'marketing.automations.preview.outcome.LATE_ORDER_APOLOGY':
    'Sends “{template}” by {channel}, once per order, and not when a remedy is already recorded',

  // offers, offer picker, contact policy (ADR 0112)
  'marketing.channel.IN_APP': 'In-app banner',
  'marketing.channel.CALL_CENTRE': 'Call centre',
  'marketing.offerPicker.label': 'Offer',
  'marketing.offerPicker.none': 'No offer',
  'marketing.offerPicker.choose': 'Choose an offer',
  'marketing.offerPicker.notInForceSuffix': ' — no longer in force',
  'marketing.offerPicker.empty':
    'No published offer is allowed in this channel. Offers are written and published on the Offers tab.',
  'marketing.offerPicker.stale':
    'The offer this step names is no longer in force. Choose another, or the scenario cannot be saved.',
  'marketing.offerPicker.fact.reference': 'Points at',
  'marketing.offerPicker.fact.window': 'Valid',
  'marketing.offerPicker.fact.template': 'Template',
  'marketing.offerPicker.window.open': 'from {from}',
  'marketing.offerPicker.window.closed': '{from} to {until}',
  'marketing.offer.reference.promotion': 'A pricing promotion',
  'marketing.offer.reference.accrualRule': 'A loyalty accrual rule',
  'marketing.offer.status.DRAFT': 'Draft',
  'marketing.offer.status.PUBLISHED': 'Published',
  'marketing.offer.status.SUPERSEDED': 'Superseded',
  'marketing.offer.status.RETIRED': 'Retired',
  'marketing.campaigns.tab.offers': 'Offers',
  'marketing.campaigns.tab.contactPolicy': 'Contact policy',
  'marketing.offers.intro':
    'Versioned references to a promotion or a loyalty accrual rule that already exists. Scenarios choose from these. An offer never states what it is worth: pricing and loyalty decide that.',
  'marketing.offers.create': 'New offer',
  'marketing.offers.denied': 'No access to this brand’s offers',
  'marketing.offers.empty':
    'No offer drafted yet. An offer points at a promotion or an accrual rule that already exists, so write those first.',
  'marketing.offers.column.version': 'Version',
  'marketing.offers.column.status': 'Status',
  'marketing.offers.column.reference': 'Points at',
  'marketing.offers.column.window': 'Valid',
  'marketing.offers.column.channels': 'Channels',
  'marketing.offers.action.edit': 'Edit draft',
  'marketing.offers.action.publish': 'Publish',
  'marketing.offers.action.retire': 'Retire',
  'marketing.offers.action.newVersion': 'New version',
  'marketing.offers.accrualRuleLabel': 'Earn {rate}% back ({status})',
  'marketing.offers.form.title.create': 'New offer',
  'marketing.offers.form.title.edit': 'Edit draft offer',
  'marketing.offers.form.title.version': 'New version of this offer',
  'marketing.offers.form.noBenefit':
    'An offer names what it points at, when it applies, where it may be shown and with which words. It has no field for a discount or a number of points: what a benefit is worth is decided by pricing and loyalty.',
  'marketing.offers.form.name': 'Name a guest can be shown',
  'marketing.offers.form.reference': 'Points at',
  'marketing.offers.form.noPromotions':
    'This brand has no promotion to point at. Write one on the Promotions tab first.',
  'marketing.offers.form.noAccrualRules':
    'This brand has no accrual rule to point at. Write one on the Loyalty tab first.',
  'marketing.offers.form.referenceManual':
    'The promotions and accrual rules could not be listed for you. Enter the id of the one this offer points at.',
  'marketing.offers.form.validFrom': 'Valid from ({zone} time)',
  'marketing.offers.form.validUntil': 'Valid until ({zone} time, optional)',
  'marketing.offers.form.channels': 'May be shown in',
  'marketing.offers.form.template': 'Template (the wording)',
  'marketing.offers.form.audience': 'Audience',
  'marketing.offers.form.audience.any': 'Everyone a scenario or campaign reaches',
  'marketing.offers.form.banner': 'Banner image reference (optional)',
  'marketing.offers.form.submit': 'Save draft',
  'marketing.offers.problem.name': 'Give the offer a name a guest can be shown.',
  'marketing.offers.problem.reference':
    'Choose the promotion or accrual rule this offer points at.',
  'marketing.offers.problem.validFrom': 'Say when the offer starts.',
  'marketing.offers.problem.window': 'The offer’s window must end after it starts.',
  'marketing.offers.problem.channels': 'Allow at least one channel.',
  'marketing.offers.problem.template': 'Choose the template that carries the wording.',
  'marketing.offers.publish.title': 'Publish “{name}”?',
  'marketing.offers.publish.body':
    'It goes into force and supersedes the version now in force. Scenarios that name the older version keep naming it.',
  'marketing.offers.retire.title': 'Retire “{name}”',
  'marketing.offers.retire.body':
    'It stops being selectable, and every scenario that names it will stop offering it at the guest’s next step, with the reason recorded.',
  'marketing.offers.retire.reason': 'Why is it being retired?',
  'marketing.contactPolicy.intro':
    'The platform sets how often and when a guest may be contacted. A brand may be stricter, never looser. When the policy stops a message, the decision log says which rule and why.',
  'marketing.contactPolicy.create': 'New rule',
  'marketing.contactPolicy.denied': 'No access to this brand’s contact policy',
  'marketing.contactPolicy.bounds.title': 'The platform’s bounds',
  'marketing.contactPolicy.bounds.hint':
    'What a brand’s rule is measured against. A cap above its ceiling, or quiet hours that start later or end earlier, would loosen the platform’s and is refused.',
  'marketing.contactPolicy.bounds.quiet': 'Quiet hours',
  'marketing.contactPolicy.bounds.quietValue':
    'no messages from {start} until {end} at the latest; a brand may start earlier and end later',
  'marketing.contactPolicy.period.DAILY': 'Messages per day, at most',
  'marketing.contactPolicy.period.WEEKLY': 'Messages per calendar week, at most',
  'marketing.contactPolicy.period.ROLLING_7D': 'Messages in any 7 days, at most',
  'marketing.contactPolicy.period.ROLLING_30D': 'Messages in any 30 days, at most',
  'marketing.contactPolicy.overrides.title': 'This brand’s own rules',
  'marketing.contactPolicy.overrides.empty':
    'This brand has set nothing tighter than the platform’s bounds.',
  'marketing.contactPolicy.column.channel': 'Channel',
  'marketing.contactPolicy.column.purpose': 'Campaign purpose',
  'marketing.contactPolicy.column.period': 'Period',
  'marketing.contactPolicy.column.cap': 'Cap',
  'marketing.contactPolicy.column.quiet': 'Quiet hours',
  'marketing.contactPolicy.column.reason': 'Why',
  'marketing.contactPolicy.action.replace': 'Change',
  'marketing.contactPolicy.action.remove': 'Remove',
  'marketing.contactPolicy.defaults.title': 'Settings scenarios read',
  'marketing.contactPolicy.defaults.hint':
    'Set through the configuration API, not here. They decide ties and defaults.',
  'marketing.contactPolicy.defaults.priority':
    'Which purpose goes first when a step and a broadcast are due together',
  'marketing.contactPolicy.defaults.priorityNone': 'Not set: the step waits for the broadcast',
  'marketing.contactPolicy.defaults.inAppCap':
    'Times one in-app banner is shown to a guest per day',
  'marketing.contactPolicy.defaults.controlGroup': 'Control group offered by default',
  'marketing.contactPolicy.explainer.title': 'Why a guest is blocked',
  'marketing.contactPolicy.explainer.hint':
    'Every choice a scenario makes is written down, and every block carries one of these reasons. Which of them ends the guest’s run and which only holds the step is stated for each.',
  'marketing.contactPolicy.explainer.governed': 'the brand’s contact policy can be behind this',
  'marketing.contactPolicy.explainer.quiet':
    'Quiet hours never refuse a message: one that falls due inside the closed window is held to the next open moment and sent then.',
  'marketing.contactPolicy.form.title.create': 'New contact rule',
  'marketing.contactPolicy.form.title.replace': 'Change this rule',
  'marketing.contactPolicy.form.tightenOnly':
    'A rule may make this brand quieter and never louder. The platform’s number is beside each field.',
  'marketing.contactPolicy.form.cap': 'Cap for this period (platform ceiling {ceiling})',
  'marketing.contactPolicy.form.quietStart': 'Quiet hours start (no later than the platform’s)',
  'marketing.contactPolicy.form.quietEnd': 'Quiet hours end (no earlier than the platform’s)',
  'marketing.contactPolicy.form.reason': 'Why this rule exists',
  'marketing.contactPolicy.form.submit': 'Save rule',
  'marketing.contactPolicy.problem.purpose': 'Name the campaign purpose the rule is for.',
  'marketing.contactPolicy.problem.empty': 'A rule says something: a cap, a quiet window, or both.',
  'marketing.contactPolicy.problem.quietPair': 'A quiet window has both a start and an end.',
  'marketing.contactPolicy.problem.capNegative': 'A cap is a whole number, zero or more.',
  'marketing.contactPolicy.problem.capLoosened':
    'A cap may be tightened and never loosened: {cap} exceeds the platform’s {ceiling}.',
  'marketing.contactPolicy.problem.quietStartLoosened':
    'Quiet hours may be tightened and never loosened: a start of {start} is later than the platform’s {bound}.',
  'marketing.contactPolicy.problem.quietEndLoosened':
    'Quiet hours may be tightened and never loosened: an end of {end} is earlier than the platform’s {bound}.',
  'marketing.contactPolicy.problem.reason':
    'Say why the rule exists: somebody should be able to attribute it.',
  'marketing.contactPolicy.remove.title': 'Remove this rule',
  'marketing.contactPolicy.remove.body':
    'The brand returns to the platform’s bound for {channel}, {period}.',
  'marketing.contactPolicy.remove.reason': 'Why is it being removed?',

  // scenario campaigns (ADR 0112) and delivery evidence (ADR 0146)
  'marketing.campaigns.create.scenario': 'New scenario',
  'marketing.campaigns.kind.BROADCAST': 'Broadcast',
  'marketing.campaigns.kind.SCENARIO': 'Scenario',
  'marketing.wiring.notConnected': 'Not connected:',
  'marketing.scenario.editor.title.create': 'New scenario',
  'marketing.scenario.editor.title.edit': 'Edit the steps of “{name}”',
  'marketing.scenario.editor.intro':
    'A scenario is a plan for each guest: steps, each with a wait before it, a channel, an offer and a template. Saving writes a draft and sends nothing. It is estimated, submitted and approved by somebody who is not its author, like any campaign, and only launching starts it. Once it leaves draft its steps are fixed: a change is a new version that needs its own approval.',
  'marketing.scenario.editor.notDraft':
    'This scenario is past draft, so its steps are fixed. A change is a new version that needs its own approval: open the scenario and draft one.',
  'marketing.scenario.editor.name': 'Name',
  'marketing.scenario.editor.steps': 'Steps',
  'marketing.scenario.editor.steps.hint':
    'Up to {max} steps. A wait is at most {days} days, counted from the guest entering (first step) or from the step before it being sent.',
  'marketing.scenario.editor.step': 'Step {number}',
  'marketing.scenario.editor.addStep': 'Add a step',
  'marketing.scenario.editor.moveUp': 'Move this step up',
  'marketing.scenario.editor.moveDown': 'Move this step down',
  'marketing.scenario.editor.remove': 'Remove this step',
  'marketing.scenario.editor.callCentreSuffix':
    ' (needs the call-centre queue, which does not exist yet)',
  'marketing.scenario.editor.wait': 'Wait before this step',
  'marketing.scenario.editor.waitUnit': 'Unit',
  'marketing.scenario.editor.wait.hintFirst':
    'Counted from the moment the guest enters the scenario.',
  'marketing.scenario.editor.wait.hint': 'Counted from the moment the step before is sent.',
  'marketing.scenario.editor.continuation': 'Go on to this step',
  'marketing.scenario.editor.stop': 'End the scenario for the guest',
  'marketing.scenario.editor.offerTemplate': 'The offer’s template ({template})',
  'marketing.scenario.editor.noTemplate': 'Choose a template',
  'marketing.scenario.editor.inAppTemplate':
    'An in-app banner shows its offer and uses the offer’s template.',
  'marketing.scenario.editor.controlGroup':
    'Withhold a control group, so the results can state a lift',
  'marketing.scenario.editor.controlGroup.percent': 'Share of the audience withheld, %',
  'marketing.scenario.editor.controlGroup.hint':
    'Up to {count} of the {cap} guests would be withheld from every step, decided once at the start and never resampled. Withholding guests costs reach: on a small audience it may leave too few to measure anything.',
  'marketing.scenario.editor.controlGroup.off':
    'No control group: the scenario runs against its whole audience, and its results cannot state a lift because there is no baseline.',
  'marketing.scenario.editor.unwired':
    'This scenario cannot be launched until these are fixed. You can still save it as a draft.',
  'marketing.scenario.editor.unwired.step': 'Step {number},',
  'marketing.scenario.editor.save': 'Save draft',
  'marketing.scenario.editor.saveSteps': 'Save steps',
  'marketing.scenario.editor.problem.name': 'Give the scenario a name.',
  'marketing.scenario.editor.problem.audience': 'Choose an audience.',
  'marketing.scenario.editor.problem.cap': 'The recipient cap is a whole number, 1 or more.',
  'marketing.scenario.editor.problem.ceiling':
    'A scenario that sends on a channel billed per message needs a cost ceiling.',
  'marketing.scenario.editor.problem.currency': 'The currency is a three-letter code.',
  'marketing.scenario.editor.problem.controlGroup':
    'The control group is a whole percentage from 0 to 100.',
  'marketing.scenario.editor.problem.scheduledAt': 'The start time must be in the future.',
  'marketing.scenario.problem.NO_STEPS': 'A scenario has at least one step.',
  'marketing.scenario.problem.TOO_MANY_STEPS': 'A scenario has at most {max} steps.',
  'marketing.scenario.problem.NO_MESSAGING_STEP':
    'A scenario needs at least one step that sends a message: its cost ceiling, consent and estimate are those of a messaging channel, and an in-app banner alone has none.',
  'marketing.scenario.problem.CALL_CENTRE_NOT_WIRED':
    'Step {step} hands off to the call centre, whose lead queue does not exist yet.',
  'marketing.scenario.problem.IN_APP_NEEDS_OFFER':
    'Step {step} shows an in-app banner and names no offer to show.',
  'marketing.scenario.problem.NEEDS_TEMPLATE':
    'Step {step} needs a template, or an offer that names one.',
  'marketing.scenario.problem.WAIT_INVALID': 'Step {step} needs a wait of zero or more.',
  'marketing.scenario.problem.WAIT_TOO_LONG': 'Step {step} waits longer than {days} days.',
  'marketing.scenario.problem.OFFER_NOT_IN_FORCE':
    'Step {step} names an offer that is not in force: it is not published, or it is over, retired or replaced.',
  'marketing.scenario.problem.OFFER_CHANNEL':
    'Step {step} sends on a channel the offer is not allowed in.',
  'marketing.scenario.condition.ALWAYS': 'Always',
  'marketing.scenario.condition.NO_ORDER_SINCE_ENTRY':
    'Only if the guest has not ordered since entering',
  'marketing.scenario.condition.NONE': 'Never early',
  'marketing.scenario.condition.ORDER_PLACED_SINCE_ENTRY': 'As soon as the guest orders',
  'marketing.scenario.unit.MINUTES': 'minutes',
  'marketing.scenario.unit.HOURS': 'hours',
  'marketing.scenario.unit.DAYS': 'days',
  'marketing.scenario.steps.title': 'Steps',
  'marketing.scenario.steps.wait': 'Wait before it',
  'marketing.scenario.wait.none': 'Straight away',
  'marketing.scenario.wait.days': '{count} day(s)',
  'marketing.scenario.wait.hours': '{count} hour(s)',
  'marketing.scenario.wait.minutes': '{count} minute(s)',
  'marketing.scenario.supersedes': 'Replaces the version {id}; launching this one halts that one.',
  'marketing.scenario.guests.title': 'Where the guests are',
  'marketing.scenario.guests.none':
    'No guest has entered yet: guests are enrolled when the scenario starts.',
  'marketing.scenario.control.some':
    '{percent}% of the audience is withheld as a control group: fixed at the start, never resampled.',
  'marketing.scenario.control.none':
    'No control group: this scenario runs against its whole audience, so its results can state no lift.',
  'marketing.scenario.participant.IN_PROGRESS': 'In progress',
  'marketing.scenario.participant.CONTROL': 'In the control group',
  'marketing.scenario.participant.COMPLETED': 'Completed',
  'marketing.scenario.participant.STOPPED_BY_CONDITION': 'Stopped by a condition',
  'marketing.scenario.participant.STOPPED_BY_CONSENT_WITHDRAWN': 'Stopped: consent withdrawn',
  'marketing.scenario.participant.STOPPED_BY_SUPPRESSION': 'Stopped: suppressed',
  'marketing.scenario.decisions.title': 'What it decided, and why',
  'marketing.scenario.decision.SENT': 'Sent',
  'marketing.scenario.decision.BLOCKED': 'Blocked',
  'marketing.scenario.decisions.empty': 'Nothing decided yet.',
  'marketing.scenario.decisions.column.when': 'When',
  'marketing.scenario.decisions.column.step': 'Step',
  'marketing.scenario.decisions.column.outcome': 'Outcome',
  'marketing.scenario.decisions.column.guest': 'Guest',
  'marketing.scenario.decisions.guest.label': 'Why did this guest not get a step? Guest account id',
  'marketing.scenario.decisions.guest.lookup': 'Show this guest’s decisions',
  'marketing.scenario.decisions.guest.clear': 'Show everyone',
  'marketing.scenario.decisions.guest.hint':
    'An account id is on the customer card. Nothing here shows a name, a phone number or an email.',
  'marketing.scenario.decisions.guest.invalid':
    'That is not an account id: it is 36 characters, letters and digits in groups of 8-4-4-4-12.',
  'marketing.scenario.decisions.guest.empty':
    'This scenario has decided nothing for that guest yet.',
  'marketing.scenario.decisions.recorded': 'Recorded:',
  'marketing.scenario.results.title': 'Did it work?',
  'marketing.scenario.results.hint':
    'The goal is the guest’s next order within the window. A contacted guest’s order counts for this scenario only if the attribution model credits it; control guests are counted as they are, because they were never contacted.',
  'marketing.scenario.results.model': 'Attribution',
  'marketing.scenario.results.model.FIRST_TOUCH':
    'First touch: the first campaign to contact the guest',
  'marketing.scenario.results.model.LAST_TOUCH':
    'Last touch: the most recent campaign before the order',
  'marketing.scenario.results.window': 'Window, days',
  'marketing.scenario.results.window.invalid':
    'The window is a whole number of days, from 1 to 90.',
  'marketing.scenario.results.none': 'No guest has entered yet, so there is nothing to measure.',
  'marketing.scenario.results.column.guests': 'Guests',
  'marketing.scenario.results.column.ordered': 'Ordered',
  'marketing.scenario.results.column.rate': 'Rate',
  'marketing.scenario.results.treated': 'Contacted',
  'marketing.scenario.results.control': 'Control group',
  'marketing.scenario.results.lift': 'Lift: {points} percentage points over the control group.',
  'marketing.scenario.results.noLift.noControl':
    'No lift can be stated: this scenario ran without a control group, so there is no baseline to compare against.',
  'marketing.scenario.results.noLift.empty':
    'No lift can be stated yet: one of the two groups has no guests.',
  'marketing.scenario.results.open':
    '{count} guest(s) are still inside their window, so these figures will move.',
  'marketing.scenario.action.edit': 'Edit steps',
  'marketing.scenario.action.revise': 'Draft a new version',
  'marketing.scenario.action.revise.hint':
    'Steps are fixed once a scenario leaves draft. A new version is a draft with the same steps; it needs its own approval, and launching it halts this one.',
  'marketing.campaign.recipients.column.delivery': 'Delivery',
  'marketing.delivery.DELIVERED': 'Delivered',
  'marketing.delivery.FAILED': 'Failed',
  'marketing.delivery.REJECTED': 'Rejected by the gateway',
  'marketing.delivery.NO_RECEIPT': 'No receipt came back',
  'marketing.delivery.HANDED_TO_OPERATOR': 'Handed to the operator',
  'marketing.delivery.PENDING': 'Not sent yet',
  'marketing.campaign.recipients.deliveryHint':
    'Delivery is evidence from the gateway, not a promise: “handed to the operator” means it was accepted and nothing more has been reported.',
  'marketing.campaign.recipients.segments': '{count} segment(s) billed',
} as const;
