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
  'marketing.campaign.stats.delivery.title': 'What became of the messages',
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
    'A dispatcher’s own operational SMS to couriers — a shift change, a weather closure, a route closure. Sent from a wording the SMS gateway has approved, to each courier’s own login number, which the platform reads for one message and keeps nowhere. Never a customer campaign.',
  'marketing.courierBroadcasts.loading': 'Loading',
  'marketing.courierBroadcasts.empty': 'No broadcast sent yet',
  'marketing.courierBroadcasts.create.action': 'New broadcast',
  'marketing.courierBroadcasts.create.title': 'Draft a courier broadcast',
  'marketing.courierBroadcasts.create.targetKind': 'Target',
  'marketing.courierBroadcasts.create.targetKind.ALL_ACTIVE': 'Every active courier',
  'marketing.courierBroadcasts.create.targetKind.GROUP': 'One courier group',
  'marketing.courierBroadcasts.create.targetGroupId': 'Group ID',
  'marketing.courierBroadcasts.create.submit': 'Save draft',
  'marketing.courierBroadcasts.column.target': 'Target',
  'marketing.courierBroadcasts.column.message': 'Wording',
  'marketing.courierBroadcasts.column.status': 'Status',
  'marketing.courierBroadcasts.column.recipients': 'Recipients',
  'marketing.courierBroadcasts.status.DRAFT': 'Draft',
  'marketing.courierBroadcasts.status.SENT': 'Sent',
  'marketing.courierBroadcasts.status.FAILED': 'Failed',
  'marketing.courierBroadcasts.action.send': 'Send',
  'marketing.courierBroadcasts.refusalReason': 'Refused: {reason}',
  'marketing.courierBroadcasts.create.template': 'Wording',
  'marketing.courierBroadcasts.create.template.placeholder': 'Choose an approved wording',
  'marketing.courierBroadcasts.create.template.awaiting':
    '{key} — waiting for the SMS gateway’s approval',
  'marketing.courierBroadcasts.create.templates.none':
    'No courier wording is ready. Author an SMS template of the class COURIER_DISPATCH under Settings → Notifications and have the gateway approve it.',
  'marketing.courierBroadcasts.create.variablesHint':
    'Short values, one line each, 80 characters at most. Operational text only: no names or phone numbers.',
  'marketing.courierBroadcasts.create.preview': 'The message will read',
  'marketing.courierBroadcasts.variable.place': 'Place',
  'marketing.courierBroadcasts.variable.time': 'Time',
  'marketing.courierBroadcasts.variable.note': 'Note',
  'marketing.courierBroadcasts.action.results': 'Results',
  'marketing.courierBroadcasts.action.hideResults': 'Hide results',
  'marketing.courierBroadcasts.results.title': 'What became of each courier’s message',
  'marketing.courierBroadcasts.results.loading': 'Loading',
  'marketing.courierBroadcasts.results.column.courier': 'Courier',
  'marketing.courierBroadcasts.results.column.state': 'State',
  'marketing.courierBroadcasts.results.column.reason': 'Why',
  'marketing.courierBroadcasts.results.empty': 'No courier was addressed.',
  'marketing.courierBroadcasts.results.state.NO_CONTACT': 'No number',
  'marketing.delivery.reason.COURIER_HAS_NO_NUMBER':
    'The identity provider holds no number the platform sends to for this courier. Fix their profile and draft again.',
  'marketing.delivery.reason.RECIPIENT_UNRESOLVABLE':
    'The courier’s number was gone when the message was sent.',
  'marketing.delivery.reason.SMS_PURPOSE_NOT_PERMITTED':
    'The SMS account is not cleared to carry courier messages.',
  'marketing.delivery.reason.RECEIVER_UNREACHABLE':
    'The gateway says the number cannot be reached.',
  'marketing.delivery.reason.PROVIDER_REPORTED_FAILURE':
    'The gateway reported that the message did not arrive.',
  'marketing.delivery.reason.IDENTITY_PROVIDER_UNAVAILABLE':
    'The identity provider could not be asked for the courier’s number; the platform will try again.',
  'marketing.delivery.reason.other': 'Reason code: {code}',
  'marketing.delivery.reason.ADDRESS_REJECTED': 'The mail relay refused this address for good',
  'marketing.delivery.reason.ADDRESS_INVALID': 'This is not a valid email address',
  'marketing.delivery.reason.MESSAGE_INVALID': 'The mail relay refused the message itself',
  'marketing.delivery.reason.SMTP_UNAVAILABLE': 'The mail relay could not be reached',
  'marketing.delivery.reason.EMAIL_NOT_CONFIGURED':
    'No mail server is configured on this deployment',
  'marketing.courierBroadcasts.readiness.heading': 'Courier SMS cannot be sent for this brand yet',
  'marketing.courierBroadcasts.wiring.SMS_PURPOSE_NOT_PERMITTED':
    'This brand’s SMS account is not cleared to carry courier messages. Sign-in codes and order messages are unaffected. Until the platform owner confirms in writing that the account may carry courier traffic and it is named on the connection, a courier SMS cannot be sent.',
  'marketing.courierBroadcasts.wiring.COURIER_CONTACTS_NOT_WIRED':
    'The platform may not yet use a courier’s own number to message them. The number is the one they sign in with and the platform keeps no copy; until the platform owner records that it may be used for a dispatcher’s message, a courier broadcast cannot be sent.',
  'marketing.courierBroadcasts.wiring.COURIER_CONTACTS_UNAVAILABLE':
    'The identity provider could not be asked for the couriers’ numbers. Nothing was sent and the broadcast is still a draft; try again in a moment.',
  'marketing.courierBroadcasts.wiring.NO_PROVIDER_BINDING':
    'No SMS provider is connected for this brand. Connect one in the brand’s integrations settings.',
  'marketing.courierBroadcasts.wiring.INSTALLATION_INACTIVE':
    'The SMS provider connection is switched off.',
  'marketing.courierBroadcasts.wiring.INSTALLATION_MISSING':
    'The SMS provider connection no longer exists.',
  'marketing.courierBroadcasts.wiring.SMS_ACCOUNT_MISCONFIGURED':
    'The SMS account for this brand is missing part of its configuration.',
  'marketing.courierBroadcasts.wiring.PROVIDER_ADAPTER_MISMATCH':
    'The connected SMS provider has no adapter in this release.',
  'marketing.courierBroadcasts.wiring.NO_ADAPTER': 'This release has no SMS adapter.',
  'marketing.courierBroadcasts.wiring.NO_DELIVERY_ADAPTER':
    'This release has no SMS delivery path.',
  'marketing.courierBroadcasts.wiring.COURIER_TEMPLATE_NOT_AVAILABLE':
    'The wording this broadcast names is no longer an active courier wording for this brand.',
  'marketing.courierBroadcasts.wiring.TEMPLATE_AWAITING_PROVIDER':
    'The wording is still waiting for the SMS gateway’s approval, so nothing can be sent with it yet.',
  'marketing.courierBroadcasts.wiring.NO_ACTIVE_COURIER': 'No active courier is in this target.',
  'marketing.courierBroadcasts.wiring.NO_COURIER_HAS_A_NUMBER':
    'None of the targeted couriers has a number the platform sends to; nothing was sent.',
  'marketing.courierBroadcasts.wiring.UNKNOWN':
    'A courier SMS cannot leave for this brand right now ({reason}).',

  // --------------------------------------------------------- attribution links 6.6a (ADR 0044, T18)
  'marketing.attributionLinks.title': 'Acquisition links',
  'marketing.attributionLinks.intro':
    'A link for a campaign or an influencer: your website, your Telegram bot or your Telegram Mini App. It counts how many times it is opened, never who opened it.',
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
  'marketing.attributionLinks.copy': 'Copy',
  'marketing.attributionLinks.copied': 'Copied',
  'marketing.attributionLinks.unavailable.WEB_HOSTNAME_NOT_CLAIMED':
    'No website address yet: this brand’s branches sell on no website channel with an address.',
  'marketing.attributionLinks.unavailable.WEB_HOSTNAME_NOT_VERIFIED':
    'The website address is not verified yet.',
  'marketing.attributionLinks.unavailable.WEB_HOSTNAME_AMBIGUOUS':
    'Two verified website addresses sell here, and the platform will not guess which one to link.',
  'marketing.attributionLinks.unavailable.TELEGRAM_BOT_NOT_CONNECTED':
    'No Telegram bot is connected for this brand.',
  'marketing.attributionLinks.unavailable.TELEGRAM_BOT_UNREACHABLE':
    'Telegram would not tell us the bot’s name just now. Try again shortly.',
  'marketing.attributionLinks.unavailable.TELEGRAM_MINI_APP_NOT_SET_UP':
    'The Mini App is not set up yet: finish the Telegram Mini App setup below.',
  'marketing.attributionLinks.unavailable.MOBILE_APP_ON_HOLD':
    'The mobile app is on hold, so this link goes nowhere.',
  // ------------------------------------------- the guided Mini App setup (ADR 0183, row 6.6a)
  'marketing.miniApp.title': 'Telegram Mini App setup',
  'marketing.miniApp.lead':
    'Your storefront inside your Telegram bot. Two steps are only yours to do in Telegram, and the exact words to send are written out; the rest is read from the facts, so you can leave and come back.',
  'marketing.miniApp.loading': 'Reading where the setup stands…',
  'marketing.miniApp.denied':
    'Setting up the Mini App needs the permission to manage integrations.',
  'marketing.miniApp.error': 'Where the setup stands could not be read.',
  'marketing.miniApp.open': 'Open',
  'marketing.miniApp.copy': 'Copy',
  'marketing.miniApp.state.DONE': 'Done',
  'marketing.miniApp.state.RECORDED': 'Recorded',
  'marketing.miniApp.state.TODO': 'To do',
  'marketing.miniApp.state.UNKNOWN': 'Could not check',
  'marketing.miniApp.step.CONNECT_BOT.title': 'Connect your bot',
  'marketing.miniApp.step.CONNECT_BOT.hint':
    'Create the bot with @BotFather (/newbot), then paste its token under Settings → Integrations. The token is never shown again.',
  'marketing.miniApp.step.REGISTER_WEBHOOK.title': 'Register the bot’s webhook',
  'marketing.miniApp.step.REGISTER_WEBHOOK.hint':
    'So the bot hears your customers: press Register webhook on the bot’s card in Integrations.',
  'marketing.miniApp.step.TELEGRAM_CHANNEL.title': 'Add a Telegram sales channel',
  'marketing.miniApp.step.TELEGRAM_CHANNEL.hint':
    'Under Settings → Sales channels, add a channel of type Telegram backed by this bot. Its menu button, texts and Mini App switch are set there.',
  'marketing.miniApp.step.STOREFRONT_ADDRESS.title': 'Your storefront has a verified address',
  'marketing.miniApp.step.STOREFRONT_ADDRESS.hint':
    'The Mini App opens your storefront, so it needs the verified address of your website channel. You never type an address here.',
  'marketing.miniApp.step.BOTFATHER_APP.title': 'Create the Mini App in BotFather',
  'marketing.miniApp.step.BOTFATHER_APP.hint':
    'Only the holder of the bot can do this, and Telegram gives us no way to check it, so this step reads “Recorded” once you tell us the short name.',
  'marketing.miniApp.step.MENU_BUTTON.title': 'Put the Mini App on the bot’s menu button',
  'marketing.miniApp.step.MENU_BUTTON.hint':
    'We set the button and then ask Telegram what it shows. The step is done only when Telegram shows what we set.',
  'marketing.miniApp.step.TEST_LINK.title': 'Open it on your phone',
  'marketing.miniApp.step.TEST_LINK.hint':
    'Open this link in Telegram: your storefront should open inside the bot. Then mint a “Telegram mini app” link above and open that to see a click counted.',
  'marketing.miniApp.detail.STOREFRONT_ADDRESS.NOT_CLAIMED':
    'No website channel selling at this brand’s branches has claimed an address yet.',
  'marketing.miniApp.detail.STOREFRONT_ADDRESS.NOT_VERIFIED':
    'The website channel’s address is claimed but not verified. Publish its DNS record and verify it.',
  'marketing.miniApp.detail.STOREFRONT_ADDRESS.AMBIGUOUS':
    'Two verified website addresses sell at this brand’s branches, and the platform will not guess which one is the storefront.',
  'marketing.miniApp.detail.MENU_BUTTON.NOT_READY': 'Finish the steps above first.',
  'marketing.miniApp.detail.MENU_BUTTON.MINI_APP_OFF':
    'The Mini App is switched off on the Telegram channel. Recording the short name switches it on.',
  'marketing.miniApp.detail.MENU_BUTTON.NOT_APPLIED':
    'Telegram shows a different button from the one we mean to set.',
  'marketing.miniApp.detail.MENU_BUTTON.OTHER_BRAND':
    'The Telegram channel’s Mini App opens another brand’s storefront. Recording the short name here moves it to this brand.',
  'marketing.miniApp.detail.MENU_BUTTON.STALE_BUTTON':
    'Telegram still shows a button that opens the storefront, but the Mini App is switched off or the address is no longer verified. Take it off so customers are not sent there.',
  'marketing.miniApp.detail.TEST_LINK.NOT_READY':
    'The link appears once the bot and the short name are known.',
  'marketing.miniApp.botFather.step1': 'Open @BotFather in Telegram and send /newapp.',
  'marketing.miniApp.botFather.step2': 'Choose your bot: @{bot}.',
  'marketing.miniApp.botFather.step3': 'Send a title and a short description. Customers see both.',
  'marketing.miniApp.botFather.step4': 'Send a 640×360 picture, then a GIF or /empty to skip it.',
  'marketing.miniApp.botFather.step5':
    'When BotFather asks for the Web App URL, send exactly the address below.',
  'marketing.miniApp.botFather.step6':
    'When BotFather asks for a short name, send one of your choice: letters, digits and underscores, for example order. BotFather replies with a link ending in it. Enter the short name here.',
  'marketing.miniApp.shortName': 'Short name BotFather accepted',
  'marketing.miniApp.record': 'Record short name',
  'marketing.miniApp.needsChannel':
    'Add the Telegram sales channel first: the short name is recorded on it.',
  'marketing.miniApp.needsAddress':
    'BotFather needs your storefront address first. Finish the step above.',
  'marketing.miniApp.menu.wanted': 'We will set the label “{label}”, opening {url}.',
  'marketing.miniApp.menu.held': 'Telegram currently shows a button that opens {url}.',
  'marketing.miniApp.menu.apply': 'Set the menu button',
  'marketing.miniApp.menu.takeOff': 'Take the menu button off',
  'marketing.miniApp.menu.notShown':
    'Telegram accepted the call but still shows a different button. Try again, or check the bot in BotFather.',

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
    'How many days ahead a lot counts as "about to expire". The customer is told once a day, in one message that covers every lot inside the window, using your LOYALTY_POINTS_EXPIRING wording. Until that wording is written and activated in Notifications, nothing is sent (ADR 0046).',

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

  'marketing.loyalty.deposit.title': 'Deposit accounts — not offered',
  'marketing.loyalty.deposit.body':
    'HorecaOS does not hold customer money, so there is no prepaid deposit. Customers earn and spend loyalty points, which are not money; a venue that runs its own licensed prepaid programme can connect its provider as a payment method.',
  'marketing.loyalty.deposit.revisit':
    'A decision, not a gap (ADR 0165). It is reopened only if HorecaOS, or a company its owner controls, obtains a Central Bank authorisation that covers customer prepayments, or counsel confirms in writing that a venue’s own authorisation obliges HorecaOS to operate that venue’s ledger. A venue asking for the feature does not reopen it. This narrows ADR 0046’s “a licence exists” condition: a venue’s licence alone brings a payment-provider method, never a balance held here.',

  // ---------------------------------------------------------------- loyalty 6.3b POS balance sync (ADR 0171)
  'marketing.loyalty.tab.programme': 'Programme',
  'marketing.loyalty.tab.posSync': 'POS balance sync',
  'marketing.loyalty.posSync.title': 'POS balance sync',
  'marketing.loyalty.posSync.body':
    'The points ledger is the single record of what a customer holds. A point-of-sale terminal that keeps a bonus balance of its own is compared with it and kept equal to it; its number is never copied over the ledger, and a difference is a row for a person to settle, not something corrected silently.',
  'marketing.loyalty.posSync.lag':
    'A balance here and at the till may differ for up to five minutes.',
  'marketing.loyalty.posSync.notice':
    'This brand’s till keeps its own bonus balance and HorecaOS cannot see or change it. A customer’s balance here and at the till can differ.',
  'marketing.loyalty.posSync.error': 'The POS sync could not be loaded.',
  'marketing.loyalty.posSync.bindings.title': 'Connected terminals',
  'marketing.loyalty.posSync.bindings.empty':
    'No point-of-sale terminal is connected to this brand.',
  'marketing.loyalty.posSync.binding.mode': 'Mode',
  'marketing.loyalty.posSync.binding.matched': 'Matched customers',
  'marketing.loyalty.posSync.binding.open': 'Open differences',
  'marketing.loyalty.posSync.binding.lastPass': 'Last pass',
  'marketing.loyalty.posSync.binding.never': 'Never',
  'marketing.loyalty.posSync.capability.SUPPORTED': 'Can sync',
  'marketing.loyalty.posSync.capability.PARTIAL': 'Partly can sync',
  'marketing.loyalty.posSync.capability.UNSUPPORTED': 'Cannot sync',
  'marketing.loyalty.posSync.capability.UNASSESSED': 'Not assessed',
  'marketing.loyalty.posSync.capability.PARTIAL.note':
    'This vendor can do part of the sync; the missing half is named in its assessment.',
  'marketing.loyalty.posSync.capability.UNSUPPORTED.note':
    'This vendor’s system has no customer bonus balance to read or write, so its balances are not mirrored here.',
  'marketing.loyalty.posSync.capability.UNASSESSED.note':
    'Nobody has assessed what this vendor exposes for a customer wallet, so it cannot be given this capability and its balances are not mirrored here.',
  'marketing.loyalty.posSync.mode.REPORT_ONLY': 'Report only: compares, applies and pushes nothing',
  'marketing.loyalty.posSync.mode.PULL': 'Imports what happens at the till',
  'marketing.loyalty.posSync.mode.PULL_AND_PUSH': 'Imports and sends the ledger’s balance',
  // ------------------------------------------------------------------- staging the rollout (ADR 0171, row 6.3b)
  'marketing.loyalty.posSync.stage.label': 'Stage of the rollout',
  'marketing.loyalty.posSync.stage.hint':
    'Report only first, then import, then send. Each step up is one stage; a step down is immediate.',
  'marketing.loyalty.posSync.stage.reason': 'Why (kept in the activity log)',
  'marketing.loyalty.posSync.stage.apply': 'Apply the stage',
  'marketing.loyalty.posSync.stage.saved': 'The binding now runs: {mode}.',
  'marketing.loyalty.posSync.stage.stale':
    'Someone else changed this first. The page now shows what is true; make your change again.',
  'marketing.loyalty.posSync.keeps.title': 'Does your till keep its own bonus balance?',
  'marketing.loyalty.posSync.keeps.body':
    'If it does and nothing here mirrors it, staff are told that a customer’s balance here and at the till can differ.',
  'marketing.loyalty.posSync.keeps.label': 'The till keeps a bonus balance of its own',
  'marketing.loyalty.posSync.keeps.save': 'Save',
  'marketing.loyalty.posSync.keeps.saved': 'Saved.',
  'marketing.loyalty.posSync.keeps.yes': 'Yes: the till keeps a bonus balance of its own.',
  'marketing.loyalty.posSync.keeps.no': 'No: the till keeps none, or has not been asked.',
  'marketing.loyalty.posSync.reconcile.action': 'Reconcile now',
  'marketing.loyalty.posSync.reconcile.running': 'Reconciling…',
  'marketing.loyalty.posSync.runs.title': 'Reconciliation reports',
  'marketing.loyalty.posSync.runs.empty': 'No pass has run yet.',
  'marketing.loyalty.posSync.runs.close': 'Close',
  'marketing.loyalty.posSync.runs.noDifferences': 'This pass found no differences.',
  'marketing.loyalty.posSync.runs.column.pass': 'Pass',
  'marketing.loyalty.posSync.runs.column.compared': 'Compared',
  'marketing.loyalty.posSync.runs.column.matched': 'Equal',
  'marketing.loyalty.posSync.runs.column.tillHigher': 'Till higher',
  'marketing.loyalty.posSync.runs.column.tillLower': 'Till lower',
  'marketing.loyalty.posSync.runs.column.tillOnly': 'Till only',
  'marketing.loyalty.posSync.runs.column.platformOnly': 'Ledger only',
  'marketing.loyalty.posSync.runs.column.ambiguous': 'Ambiguous',
  'marketing.loyalty.posSync.runs.column.imported': 'Imported',
  'marketing.loyalty.posSync.runs.column.held': 'Held',
  'marketing.loyalty.posSync.runs.column.unverifiable': 'Unverifiable',
  'marketing.loyalty.posSync.runs.column.pushed': 'Sent',
  'marketing.loyalty.posSync.run.kind.PULL': 'Import',
  'marketing.loyalty.posSync.run.kind.PUSH': 'Send',
  'marketing.loyalty.posSync.run.kind.RECONCILE': 'Reconciliation',
  'marketing.loyalty.posSync.run.status.RUNNING': 'Running',
  'marketing.loyalty.posSync.run.status.SUCCEEDED': 'Done',
  'marketing.loyalty.posSync.run.status.FAILED': 'Failed',
  'marketing.loyalty.posSync.discrepancies.title': 'Differences to settle',
  'marketing.loyalty.posSync.discrepancies.empty': 'No open differences.',
  'marketing.loyalty.posSync.discrepancies.platform': 'Ledger',
  'marketing.loyalty.posSync.discrepancies.till': 'Till',
  'marketing.loyalty.posSync.discrepancies.delta': 'Difference',
  'marketing.loyalty.posSync.discrepancies.reason': 'Reason for the decision',
  'marketing.loyalty.posSync.discrepancies.pending':
    'Waiting for a second person’s signature. Nothing has been credited yet.',
  'marketing.loyalty.posSync.kind.TILL_HIGHER': 'Till higher than the ledger',
  'marketing.loyalty.posSync.kind.TILL_HIGHER.note':
    'The till shows more points than the ledger holds. Nothing has been changed on either side.',
  'marketing.loyalty.posSync.kind.TILL_LOWER': 'Till lower than the ledger',
  'marketing.loyalty.posSync.kind.TILL_LOWER.note':
    'The till shows fewer points than the ledger holds. Nothing has been changed on either side.',
  'marketing.loyalty.posSync.kind.TILL_ONLY': 'Till customer with no points account',
  'marketing.loyalty.posSync.kind.TILL_ONLY.note':
    'A customer at the till matches no points account of this brand. No account is created from a till record.',
  'marketing.loyalty.posSync.kind.PLATFORM_ONLY': 'Ledger customer the till no longer knows',
  'marketing.loyalty.posSync.kind.PLATFORM_ONLY.note':
    'A matched customer is no longer found at the till.',
  'marketing.loyalty.posSync.kind.AMBIGUOUS_MATCH': 'More than one possible match',
  'marketing.loyalty.posSync.kind.AMBIGUOUS_MATCH.note':
    'The till’s phone fits more than one account. It is reported and never guessed.',
  'marketing.loyalty.posSync.kind.SPEND_EXCEEDS_BALANCE': 'Spent at the till beyond the balance',
  'marketing.loyalty.posSync.kind.SPEND_EXCEEDS_BALANCE.note':
    'The customer spent more at the till than the ledger held. The balance covered part of it; the brand carries the rest, shown on the liability report.',
  'marketing.loyalty.posSync.kind.CREDIT_HELD': 'Credit from the till held for review',
  'marketing.loyalty.posSync.kind.CREDIT_HELD.note':
    'A credit from the till is above the daily cap. Nothing is credited until someone decides.',
  'marketing.loyalty.posSync.kind.RATIO_MISMATCH': 'Programme is not one point to one som',
  'marketing.loyalty.posSync.kind.RATIO_MISMATCH.note':
    'Only a one-to-one programme can be synced, so this terminal is not bound.',
  'marketing.loyalty.posSync.kind.NOT_CONSENTED': 'Customer has not agreed to the sync',
  'marketing.loyalty.posSync.kind.NOT_CONSENTED.note':
    'The customer is left out of the sync until they agree.',
  'marketing.loyalty.posSync.kind.UNVERIFIABLE': 'Could not be checked',
  'marketing.loyalty.posSync.kind.UNVERIFIABLE.note':
    'The till did not answer, or answered in another currency. Not the same as a difference.',
  'marketing.loyalty.posSync.resolution.ACCEPT_TILL': 'Accept the till’s number',
  'marketing.loyalty.posSync.resolution.ACCEPT_TILL.hint':
    'Apply the difference to the ledger as an adjustment. Above the manual-adjustment threshold a second person must sign.',
  'marketing.loyalty.posSync.resolution.ACCEPT_PLATFORM': 'Keep the ledger’s number',
  'marketing.loyalty.posSync.resolution.ACCEPT_PLATFORM.hint':
    'Send the ledger’s balance to the till again.',
  'marketing.loyalty.posSync.resolution.ABSORB': 'Brand carries it',
  'marketing.loyalty.posSync.resolution.ABSORB.hint':
    'Record that this is seen and the brand accepts it. Nothing moves.',
  'marketing.loyalty.posSync.resolution.SUSPEND': 'Stop syncing this customer',
  'marketing.loyalty.posSync.resolution.SUSPEND.hint':
    'Leave this customer out of the sync from now on.',
  'marketing.loyalty.liability.tillAbsorbed': 'Absorbed from till spends: {amount}',

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
  'marketing.wiring.EMAIL_NOT_CONFIGURED':
    'Email is not set up on this deployment: no mail server is configured, so nothing can be sent. Until the platform owner connects a sending provider, an email campaign cannot be launched.',
  'marketing.wiring.EMAIL_PURPOSE_NOT_PERMITTED':
    'The platform’s mail service carries campaign email and nothing else.',
  'marketing.wiring.PUSH_NOT_CONFIGURED':
    'Push is not set up on this deployment: the platform’s push identity (a key pair and a contact) is not configured, so nothing can be sent. Until the platform owner connects it, a push campaign cannot be launched.',
  'marketing.wiring.PUSH_PURPOSE_NOT_PERMITTED':
    'The platform’s push service carries campaign push and nothing else.',
  'marketing.wiring.ACCOUNT_MISCONFIGURED':
    'The account for this channel is missing part of its configuration.',
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
  'marketing.delivery.READ': 'Read',
  'marketing.delivery.WITHHELD': 'Withheld',
  'marketing.delivery.HANDED_TO_OPERATOR.MESSAGING_APP': 'Sent',
  'marketing.delivery.HANDED_TO_OPERATOR.PUSH': 'Accepted by the push service',
  'marketing.delivery.READ.PUSH': 'Tapped',
  'marketing.delivery.reason.PUSH_SUBSCRIPTION_GONE':
    'The browser’s push service says this subscription is over, so the customer is no longer reachable here until they turn push on again',
  'marketing.delivery.reason.PUSH_VAPID_REJECTED':
    'The push service did not accept the platform’s sending identity for this subscription (the key was changed after the customer subscribed)',
  'marketing.delivery.reason.PUSH_NOT_CONFIGURED': 'Push is not set up on this deployment',
  'marketing.delivery.reason.PUSH_PAYLOAD_TOO_LARGE':
    'The push service refused the size of the message',
  'marketing.delivery.reason.PUSH_REQUEST_REFUSED': 'The push service refused the message',
  'marketing.delivery.reason.PUSH_PAYLOAD_UNUSABLE':
    'The message could not be encrypted to this subscription',
  'marketing.delivery.reason.PUSH_REDIRECT_REFUSED':
    'The push service redirected the request, which is never followed',
  'marketing.delivery.reason.PUSH_SERVICE_ERROR': 'The push service failed repeatedly',
  'marketing.delivery.reason.PUSH_RATE_LIMITED':
    'The push service kept asking the platform to slow down',
  'marketing.delivery.reason.PUSH_SERVICE_UNREACHABLE': 'The push service could not be reached',
  'marketing.delivery.reason.PUSH_SECRET_UNAVAILABLE': 'The platform’s push key is not available',
  'marketing.delivery.counts.notReported': 'Not reported by this channel',
  'marketing.delivery.counts.segments': 'Segments billed',
  'marketing.delivery.counts.withheldTitle': 'Withheld because',
  'marketing.delivery.withheld.TEMPLATE_AWAITING_PROVIDER':
    'Wording awaiting the gateway’s approval',
  'marketing.delivery.withheld.TEMPLATE_REFUSED_BY_PROVIDER': 'Wording refused by the gateway',
  'marketing.delivery.withheld.CONSENT_WITHHELD': 'Consent withdrawn since the audience was built',
  'marketing.delivery.withheld.PREFERENCE_DISABLED': 'The customer switched this off',
  'marketing.delivery.withheld.NO_RECIPIENT_ENDPOINT': 'No usable address or number',
  'marketing.delivery.withheld.NO_RECIPIENT_ACCOUNT': 'No customer account',
  'marketing.delivery.withheld.NO_ACTIVE_TEMPLATE': 'No active wording',
  'marketing.delivery.withheld.NO_TEMPLATE_FOR_LOCALE': 'No wording in the customer’s language',
  'marketing.delivery.withheld.CHANNEL_NOT_AVAILABLE': 'Channel not available',
  'marketing.delivery.withheld.CAMPAIGN_NOT_SENDING': 'The campaign was paused or stopped',
  'marketing.delivery.withheld.EXPIRED': 'Expired before it was sent',
  'marketing.campaign.recipients.deliveryHint':
    'Delivery is evidence from the gateway, not a promise: “handed to the operator” means it was accepted and nothing more has been reported.',
  'marketing.campaign.recipients.segments': '{count} segment(s) billed',

  'marketing.content.kind.BANNER': 'Banner',
  'marketing.content.kind.PROMO_CARD': 'Promo card',
  'marketing.content.kind.PRODUCT_GROUP': 'Product group',
  'marketing.content.kind.STORY_GROUP': 'Story',
  'marketing.content.kind.POPUP': 'Pop-up',
  'marketing.content.kind.NEWS': 'News',
  'marketing.content.kind.GALLERY_ALBUM': 'Photo album',
  'marketing.content.kind.RECIPE': 'Recipe',
  'marketing.content.kind.VACANCY': 'Vacancy',
  'marketing.content.placement.HOME_CAROUSEL': 'Home carousel',
  'marketing.content.placement.HOME_GROUPS': 'Home groups',
  'marketing.content.placement.STORY_RAIL': 'Stories',
  'marketing.content.placement.PROMO_GRID': 'Promo grid',
  'marketing.content.placement.ENTRY_MODAL': 'Entry pop-up',
  'marketing.content.state.DRAFT': 'Draft',
  'marketing.content.state.PUBLISHED': 'Published',
  'marketing.content.state.WITHDRAWN': 'Withdrawn',
  'marketing.content.state.ARCHIVED': 'Archived',
  'marketing.content.visibility.LIVE': 'Live',
  'marketing.content.visibility.SCHEDULED': 'Scheduled',
  'marketing.content.visibility.EXPIRED': 'Ended',
  'marketing.content.unpublishedChanges': 'Unpublished changes',
  'marketing.content.problemsCount': '{count} to fix',
  'marketing.content.problem.CONTENT_DEFAULT_LOCALE_MISSING':
    'Write a title in the brand’s default language before publishing',
  'marketing.content.problem.CONTENT_LOCALE_INCOMPLETE':
    'This brand requires every language to be filled in',
  'marketing.content.problem.CONTENT_MEDIA_NOT_DISPLAYABLE':
    'An image is missing, still being checked, or not public',
  'marketing.content.problem.CONTENT_MEDIA_ASPECT_MISMATCH':
    'The image is the wrong shape or too small for this placement',
  'marketing.content.problem.CONTENT_TARGET_NOT_FOUND':
    'The linked dish or category is not on the brand’s published menu',
  'marketing.content.problem.CONTENT_PROMOTION_NOT_FOUND': 'The linked promotion does not exist',
  'marketing.content.problem.CONTENT_LINK_HOST_NOT_ALLOWED':
    'The link points at a site that is not on the list of allowed links',
  'marketing.content.problem.CONTENT_CHANNEL_NOT_A_CONTENT_SURFACE':
    'A chosen channel cannot show content',
  'marketing.content.problem.CONTENT_PLACEMENT_FULL':
    'This place is full — take something down first',
  'marketing.content.problem.CONTENT_NOTHING_TO_PUBLISH':
    'Nothing has changed since the live version',
  'marketing.content.problem.CONTENT_VIDEO_LIMIT':
    'The brand already has the most videos it may show at once',
  'marketing.content.problem.CONTENT_POSTER_REQUIRED': 'A video needs a poster image',
  'marketing.content.problem.CONTENT_REVISION_MEDIA_RELEASED':
    'That version’s images were released and cannot be restored',
  'marketing.content.problem.CONTENT_KIND_NOT_ENABLED':
    'This kind of content is not switched on for the brand',
  'marketing.content.problem.CONTENT_PROMOTION_NOT_LIVE':
    'The promotion this card is bound to is not live, so the card is hidden',
  'marketing.content.problem.CONTENT_MEDIA_NOT_RENDERED':
    'The image has not finished rendering, so the item is not shown yet',
  'marketing.content.problem.CONTENT_VIDEO_NOT_ENTITLED':
    'The plan no longer includes video, so this item is hidden and cannot be published again',
  'marketing.content.problem.other': 'Needs attention: {detail}',

  'marketing.content.media.none': 'No image yet.',
  'marketing.content.media.shapeHint': 'Shape {ratio}, at least {width} px wide.',
  'marketing.content.media.noPreview': 'Preview not ready',
  'marketing.content.media.video': 'Video',
  'marketing.content.media.remove': 'Remove',
  'marketing.content.media.processing': 'Uploading and checking the file…',
  'marketing.content.media.imageRefused':
    'The platform refused that image. Use a JPEG, PNG or WebP under 10 MB.',
  'marketing.content.media.notReady':
    'The file was uploaded but has not been verified yet. Try again in a minute.',
  'marketing.content.media.failed': 'The upload failed. Try again.',

  'marketing.content.link.label': 'When tapped, go to',
  'marketing.content.link.type.NONE': 'Nowhere',
  'marketing.content.link.type.PRODUCT': 'A dish',
  'marketing.content.link.type.CATEGORY': 'A category',
  'marketing.content.link.type.CART': 'The cart',
  'marketing.content.link.type.PROMOTION': 'A promotion',
  'marketing.content.link.type.EXTERNAL': 'A web address',
  'marketing.content.link.searchProduct': 'Search dishes',
  'marketing.content.link.searchCategory': 'Search categories',
  'marketing.content.link.searchPromotion': 'Search live promotions',
  'marketing.content.link.chosen': 'Chosen: {label}',
  'marketing.content.link.clear': 'Change',
  'marketing.content.link.url': 'Address (https only)',
  'marketing.content.link.notHttps': 'Only https addresses are allowed.',
  'marketing.content.link.hostNotAllowed':
    'This site is not on the list of allowed links yet. Add it under Links before publishing.',

  'marketing.content.card.title': 'Title',
  'marketing.content.card.subtitle': 'Subtitle (optional)',
  'marketing.content.card.body': 'Text (optional)',
  'marketing.content.card.cta': 'Button text (optional)',
  'marketing.content.card.alt': 'Image description for screen readers (optional)',
  'marketing.content.card.image': 'Image or video',
  'marketing.content.card.localeImage':
    'A different image in this language (when the picture carries its own words)',
  'marketing.content.popup.settings': 'When it appears',
  'marketing.content.popup.delay': 'Show after (seconds)',
  'marketing.content.popup.dailyWindow': 'Only during a window each day',
  'marketing.content.popup.from': 'From',
  'marketing.content.popup.until': 'Until',
  'marketing.content.popup.dailyHint':
    'Judged by the visitor’s own clock. A window that ends before it starts runs past midnight.',
  'marketing.content.popup.cap': 'How often one device sees it',
  'marketing.content.popup.cap.ONCE_EVER': 'Once, ever',
  'marketing.content.popup.cap.ONCE_PER_DAY': 'Once a day',
  'marketing.content.popup.cap.EVERY_N_DAYS': 'Every few days',
  'marketing.content.popup.capDays': 'Days between showings',
  'marketing.content.popup.capHint':
    'Each device keeps count itself — the platform does not identify visitors. Editing and publishing again starts the count over.',

  'marketing.content.group.title': 'Group title',
  'marketing.content.group.mode': 'What the group shows',
  'marketing.content.group.manual': 'Dishes I pick',
  'marketing.content.group.byCategory': 'A category',
  'marketing.content.group.members': 'Dishes: {count} of {max}',
  'marketing.content.group.searchDish': 'Search dishes to add',
  'marketing.content.group.order': 'Order on the home screen',
  'marketing.content.group.skipHint':
    'A dish the menu no longer has is skipped quietly; a stopped dish stays the menu’s business.',
  'marketing.content.group.sort': 'Order of the dishes',
  'marketing.content.group.sort.MENU_ORDER': 'As in the menu',
  'marketing.content.group.sort.NEWEST': 'Newest first',
  'marketing.content.group.limit': 'Show at most',

  'marketing.content.story.title': 'Story title',
  'marketing.content.story.avatar': 'Round picture in the story strip',
  'marketing.content.story.slides': 'Slides: {count} of {max}',
  'marketing.content.story.slideLabel': 'Slide {n}',
  'marketing.content.story.addSlide': 'Add a slide',
  'marketing.content.story.removeSlide': 'Remove this slide',
  'marketing.content.story.slideMedia': 'Picture or video of this slide',
  'marketing.content.story.duration': 'Shown for (seconds)',
  'marketing.content.story.caption': 'Caption (optional)',

  'marketing.content.blocks.text': 'Text',
  'marketing.content.blocks.bold': 'Bold',
  'marketing.content.blocks.italic': 'Italic',
  'marketing.content.blocks.link': 'Link',
  'marketing.content.blocks.linkAddress': 'Link address (https, tel: or mailto:)',
  'marketing.content.blocks.linkRefused': 'Only https, tel: and mailto: links are allowed.',
  'marketing.content.blocks.addRun': 'Add text',
  'marketing.content.blocks.removeRun': 'Remove this text',
  'marketing.content.blocks.blockType': 'Block type',
  'marketing.content.blocks.type.PARAGRAPH': 'Paragraph',
  'marketing.content.blocks.type.HEADING': 'Heading',
  'marketing.content.blocks.type.QUOTE': 'Quote',
  'marketing.content.blocks.type.LIST': 'List',
  'marketing.content.blocks.type.IMAGE': 'Image',
  'marketing.content.blocks.type.DIVIDER': 'Divider',
  'marketing.content.blocks.headingLevel': 'Heading level',
  'marketing.content.blocks.level2': 'Heading',
  'marketing.content.blocks.level3': 'Subheading',
  'marketing.content.blocks.ordered': 'Numbered',
  'marketing.content.blocks.moveUp': 'Move block up',
  'marketing.content.blocks.moveDown': 'Move block down',
  'marketing.content.blocks.removeBlock': 'Remove block',
  'marketing.content.blocks.addBlock': 'Add block',
  'marketing.content.blocks.addItem': 'Add item',
  'marketing.content.blocks.removeItem': 'Remove item',
  'marketing.content.blocks.caption': 'Caption',
  'marketing.content.blocks.imageNone': 'No image yet. Choose one.',
  'marketing.content.blocks.imageUploaded': 'Image added',
  'marketing.content.blocks.imageFailed': 'The image could not be uploaded.',
  'marketing.content.blocks.divider': 'Divider',
  'marketing.content.blocks.problem.TOO_MANY_BLOCKS': 'A body has at most 200 blocks.',
  'marketing.content.blocks.problem.TOO_MANY_RUNS': 'Block {block}: at most 40 pieces of text.',
  'marketing.content.blocks.problem.TOO_MANY_ITEMS': 'Block {block}: at most 50 items.',
  'marketing.content.blocks.problem.TEXT_TOO_LONG': 'A body has at most 50,000 characters of text.',
  'marketing.content.blocks.problem.RUN_TOO_LONG':
    'Block {block}: a piece of text is over 5,000 characters.',
  'marketing.content.blocks.problem.FORBIDDEN_CHARACTER':
    'Block {block}: contains a control or text-direction character that is not allowed.',
  'marketing.content.blocks.problem.HREF_NOT_ALLOWED':
    'Block {block}: a link must be https, tel: or mailto:.',
  'marketing.content.blocks.problem.EMPTY_LIST': 'Block {block}: a list needs at least one item.',
  'marketing.content.blocks.problem.IMAGE_WITHOUT_ASSET': 'Block {block}: choose an image.',

  'marketing.content.editorial.title': 'Title',
  'marketing.content.editorial.summary': 'Short summary (optional)',
  'marketing.content.editorial.cover': 'Cover picture',
  'marketing.content.editorial.body': 'Text',
  'marketing.content.vacancy.legend': 'The job',
  'marketing.content.vacancy.employment': 'Type of work',
  'marketing.content.vacancy.employment.FULL_TIME': 'Full time',
  'marketing.content.vacancy.employment.PART_TIME': 'Part time',
  'marketing.content.vacancy.employment.SHIFT': 'Shifts',
  'marketing.content.vacancy.employment.INTERNSHIP': 'Internship',
  'marketing.content.vacancy.applyType': 'How to get in touch',
  'marketing.content.vacancy.apply.PHONE': 'Phone number',
  'marketing.content.vacancy.apply.TELEGRAM': 'Telegram address (t.me/…)',
  'marketing.content.vacancy.apply.URL': 'Web address (https)',
  'marketing.content.vacancy.apply.EMAIL': 'E-mail address',
  'marketing.content.vacancy.applyValue': 'The contact to publish',
  'marketing.content.vacancy.noApplicants':
    'This is a listing only: the platform has no application form and keeps nothing about candidates. People contact you at the address above.',
  'marketing.content.album.images': 'Photos: {count} of {max}',
  'marketing.content.album.imageLabel': 'Photo {n}',
  'marketing.content.album.add': 'Add a photo',
  'marketing.content.album.caption': 'Caption in this language (optional)',
  'marketing.content.album.remove': 'Remove this photo',

  'marketing.content.preview.channel': 'Channel',
  'marketing.content.preview.locale': 'Language',
  'marketing.content.preview.day': 'As on day (optional)',
  'marketing.content.preview.refresh': 'Refresh',
  'marketing.content.preview.noChannel':
    'No channel can show content yet, so there is nothing to preview.',
  'marketing.content.preview.failed': 'Could not build the preview.',
  'marketing.content.preview.loading': 'Building the preview…',
  'marketing.content.preview.absent':
    'This item is not in the preview for this channel, language and day — it is outside its window, on another channel, tied to a promotion that is not live, or its image is still being checked. Save the draft first if you have just changed it.',
  'marketing.content.preview.dishes': '{count} dishes',
  'marketing.content.preview.popupDelay': 'Pop-up after {seconds} s',
  'marketing.content.preview.empty': 'Nothing is on the home screen for this channel and day.',

  'marketing.content.history.loading': 'Loading the versions…',
  'marketing.content.history.empty': 'Never published, so there is no history yet.',
  'marketing.content.history.version': 'Version {n}',
  'marketing.content.history.againstPrevious': 'Compare with the version before',
  'marketing.content.history.againstLive': 'Compare with the live version',
  'marketing.content.history.publishedBy': 'Published by {who} at {when}',
  'marketing.content.history.noChange': 'Nothing differs.',
  'marketing.content.history.restore': 'Put this version into the draft',
  'marketing.content.history.restoreHint':
    'This only fills the draft. Nothing reaches customers until you publish it.',
  'marketing.content.history.failed': 'Could not read the history.',

  'marketing.content.stats.clientReported':
    'Reported by visitors’ own devices. Anyone can inflate these numbers, nothing identifies a visitor, and the reports do not use them — read them as a hint, not a measurement.',
  'marketing.content.stats.loading': 'Loading the counts…',
  'marketing.content.stats.failed': 'Could not read the counts.',
  'marketing.content.stats.window': 'Last days: {range}',
  'marketing.content.stats.empty': 'Nothing has been reported yet.',
  'marketing.content.stats.version': 'Version',
  'marketing.content.stats.impressions': 'Shown',
  'marketing.content.stats.clicks': 'Taps',
  'marketing.content.stats.dismissals': 'Dismissed',
  'marketing.content.stats.completions': 'Watched to the end',

  'marketing.content.editor.new': 'New: {kind}',
  'marketing.content.editor.unsaved': 'Not saved',
  'marketing.content.editor.close': 'Close the editor',
  'marketing.content.editor.stale':
    'Someone else changed this item while you were editing. Reload to see their version; your unsaved edits will be replaced.',
  'marketing.content.editor.reload': 'Reload',
  'marketing.content.editor.archivedNote':
    'This item is archived: it is read-only and out of the lists.',
  'marketing.content.editor.tab.edit': 'Edit',
  'marketing.content.editor.tab.preview': 'Preview',
  'marketing.content.editor.tab.history': 'History',
  'marketing.content.editor.tab.stats': 'Counts',
  'marketing.content.editor.internalName': 'Internal name (only your team sees it)',
  'marketing.content.editor.slug':
    'Address (lower-case letters, digits and hyphens; fixed once saved)',
  'marketing.content.editor.placement': 'Where it appears',
  'marketing.content.editor.channels': 'Channels',
  'marketing.content.editor.channelsHint':
    'Leave all unselected to show it on every channel that can show content.',
  'marketing.content.editor.noChannels':
    'No channel can show content yet (a web, app or Telegram channel is needed).',
  'marketing.content.editor.schedule': 'Schedule',
  'marketing.content.editor.schedule.always': 'Always',
  'marketing.content.editor.schedule.between': 'Between two dates',
  'marketing.content.editor.schedule.from': 'From a date onwards',
  'marketing.content.editor.schedule.backwards': 'The end is not after the start.',
  'marketing.content.editor.schedule.hint':
    'Whole days in your timezone. Whether it is showing is worked out each time a customer opens the app, so nothing has to run at midnight.',
  'marketing.content.editor.promotion': 'Tie to a promotion (optional)',
  'marketing.content.editor.promotionHint':
    'The card shows only while this promotion is live, and takes the promotion’s dates when it has none of its own. The platform never adds a promo code to a card; type one yourself if you want it shown.',
  'marketing.content.editor.branch': 'Branch (optional)',
  'marketing.content.editor.branchNone': 'No particular branch',
  'marketing.content.editor.language': 'Language',
  'marketing.content.editor.needsTitle':
    'Write a title in {languages}, or clear what you typed there.',
  'marketing.content.editor.problems': 'To fix before publishing',
  'marketing.content.editor.previewUnsaved':
    'You have unsaved changes. The preview shows the last saved draft.',
  'marketing.content.editor.save': 'Save draft',
  'marketing.content.editor.publish': 'Publish',
  'marketing.content.editor.withdraw': 'Withdraw',
  'marketing.content.editor.archive': 'Archive',
  'marketing.content.editor.discard': 'Discard draft',
  'marketing.content.editor.saved': 'Draft saved. Customers do not see it until it is published.',
  'marketing.content.editor.published': 'Published. Customers see it within a minute.',
  'marketing.content.editor.done': 'Done.',
  'marketing.content.editor.restored':
    'The version is in the draft. Nothing reaches customers until you publish it.',
  'marketing.content.editor.failed': 'Something went wrong. Try again.',
  'marketing.content.editor.refused': 'The platform did not accept this. See what to fix below.',
  'marketing.content.editor.confirm.publish.title': 'Publish this?',
  'marketing.content.editor.confirm.publish.body':
    'What you see now is saved and goes live. Customers see it within a minute, in the languages you filled in.',
  'marketing.content.editor.confirm.withdraw.title': 'Take it down?',
  'marketing.content.editor.confirm.withdraw.body':
    'Customers stop seeing it within a minute. Every version is kept, and you can publish it again.',
  'marketing.content.editor.confirm.archive.title': 'Archive this?',
  'marketing.content.editor.confirm.archive.body':
    'It leaves the lists, and is taken down first if it is live. Its address becomes free to use again.',
  'marketing.content.editor.confirm.discard.title': 'Discard the draft?',
  'marketing.content.editor.confirm.discard.body':
    'The live version stays as it is; the changes you have not published are lost.',

  'marketing.content.list.from': 'from {day}',
  'marketing.content.list.limit': 'Live: {live} of at most {max}',
  'marketing.content.list.showArchived': 'Show archived',
  'marketing.content.list.hideArchived': 'Back to the list',
  'marketing.content.list.new': 'New {kind}',
  'marketing.content.list.loading': 'Loading…',
  'marketing.content.list.empty': 'Nothing here yet.',
  'marketing.content.list.orderHint':
    'Drag to reorder, or use the arrows. The order takes effect at once and is not a new version.',
  'marketing.content.list.orderMoved':
    'Someone changed the order while you were dragging. The list has been read again.',
  'marketing.content.list.more': 'Show more',
  'marketing.content.list.discard.title': 'Leave without saving?',
  'marketing.content.list.discard.body': 'You have changes that are not saved. They will be lost.',
  'marketing.content.list.discard.confirm': 'Leave',
  'marketing.content.list.discard.keep': 'Keep editing',

  'marketing.content.hosts.title': 'Allowed links',
  'marketing.content.hosts.intro':
    'A banner, story or pop-up can link to a web address only if its site is on this list. Your channels’ own verified addresses are always on it; nothing else is until you add it.',
  'marketing.content.hosts.host': 'Site (for example shop.example.uz)',
  'marketing.content.hosts.reason': 'Why (optional)',
  'marketing.content.hosts.add': 'Allow this site',
  'marketing.content.hosts.invalid':
    'Type just the site’s name: lower-case letters, digits, dots and hyphens — no https://, no port, no *.',
  'marketing.content.hosts.audited':
    'Adding or removing a site is recorded in the audit history as a security fact. A link to a site that leaves the list stops working within a minute; the item itself stays live.',
  'marketing.content.hosts.empty': 'No sites yet.',
  'marketing.content.hosts.addedBy': 'added by {who}',
  'marketing.content.hosts.fromChannel': 'a verified address of one of your channels',
  'marketing.content.hosts.remove': 'Remove',
  'marketing.content.hosts.removeTitle': 'Stop allowing {host}?',
  'marketing.content.hosts.removeBody':
    'Links to this site stop working within a minute. The banners, stories and pop-ups that use them stay live, without the link.',

  'marketing.content.policy.title': 'Settings',
  'marketing.content.policy.saved': 'Saved.',
  'marketing.content.policy.publishing': 'Publishing',
  'marketing.content.policy.requireAll':
    'Require text in every language of the brand before publishing',
  'marketing.content.policy.requireAllHint':
    'By default only the brand’s default language is required; the others fall back to it. This can only make publishing stricter.',
  'marketing.content.policy.editorial': 'Editorial content',
  'marketing.content.policy.editorialNotEntitled':
    'Your plan does not include news, photo albums, recipes or vacancies. The platform owner switches this on for a tenant.',
  'marketing.content.policy.kind.news': 'News',
  'marketing.content.policy.kind.gallery': 'Photo albums',
  'marketing.content.policy.kind.recipes': 'Recipes',
  'marketing.content.policy.kind.vacancies': 'Vacancies',
  'marketing.content.policy.vacancyNote':
    'A vacancy is a listing with one contact you choose to publish. The platform has no application form and keeps nothing about candidates.',
  'marketing.content.policy.videoOn':
    'Video: your plan allows MP4 video in banners, cards, pop-ups and stories.',
  'marketing.content.policy.videoOff':
    'Video: not part of your plan. The platform owner switches it on for a tenant.',

  'marketing.content.health.title': 'Needs attention',
  'marketing.content.health.intro':
    'Things customers are being shown that are not quite right. The app has already dealt with each quietly — a broken link becomes “nowhere”, a card tied to a stopped promotion is hidden — so nobody sees an error, but nobody sees what you meant either.',
  'marketing.content.health.ok': 'Everything customers are being shown is as you made it.',
  'marketing.content.health.failed': 'Could not read the health of the content.',

  'marketing.content.page.title': 'Content',
  'marketing.content.page.intro':
    'What your brand puts in front of customers beside the menu: banners, promo cards, stories and an entry pop-up on the home screen — and, when your plan and your settings allow, news, photo albums, recipes and vacancies. Nothing reaches customers until you publish it.',
  'marketing.content.page.needsAttention': '{count} need attention',
  'marketing.content.page.views': 'Lists',
  'marketing.content.page.tab.items': 'Content',
  'marketing.content.page.tab.links': 'Links',
  'marketing.content.page.tab.settings': 'Settings',
  'marketing.content.page.tab.health': 'Health',

  'marketing.storefront.title': 'Storefront',
  'marketing.storefront.intro':
    'What the home screen of your storefronts shows, and in what order: groups of dishes, the carousel, and a preview of the whole screen. Nothing reaches customers until it is published.',
  'marketing.storefront.tab.groups': 'Home groups',
  'marketing.storefront.tab.carousel': 'Offer carousel',
  'marketing.storefront.tab.preview': 'Preview',
  'marketing.storefront.tab.switcher': 'Brand switcher and layout',
  'marketing.storefront.tab.branches': 'Branch order',
  'marketing.storefront.groups.hint':
    'Titled rails of dishes on the home screen: hand-picked, or a whole category. Their order here is their order on the screen.',
  'marketing.storefront.carousel.hint':
    'The carousel at the top of the home screen is shared by banners and promo cards. Order it here; write and publish them under Content. It is the same for every customer — a personal offer for one guest is a separate thing.',
  'marketing.storefront.carousel.link': 'Open Content',
  'marketing.storefront.layout.title': 'Category layout',
  'marketing.storefront.layout.hint':
    'Standard is the category navigation customers have today. Marketplace shows a compact strip of categories and an “all categories” overlay, for a storefront with many of them. The choice applies to this brand as soon as you make it.',
  'marketing.storefront.layout.standard': 'Standard',
  'marketing.storefront.layout.marketplace': 'Marketplace',
  'marketing.storefront.layout.saved': 'Layout saved.',
  'marketing.storefront.switcher.title': 'Other brands on this storefront',
  'marketing.storefront.switcher.hint':
    'A strip of your other brands at the top of this storefront, each leading to that brand’s own website. The address is the website’s verified address, looked up every time; nothing is typed here and nothing goes stale.',
  'marketing.storefront.switcher.empty':
    'No brand is offered here. The storefront looks as it did before.',
  'marketing.storefront.switcher.status.shown': 'Shown to customers',
  'marketing.storefront.switcher.status.hidden': 'Hidden',
  'marketing.storefront.switcher.status.noHostname':
    'Not shown: this website has no verified address yet',
  'marketing.storefront.switcher.status.inactive': 'Not shown: the brand is not active',
  'marketing.storefront.switcher.up': 'Move up',
  'marketing.storefront.switcher.down': 'Move down',
  'marketing.storefront.switcher.hide': 'Hide',
  'marketing.storefront.switcher.show': 'Show',
  'marketing.storefront.switcher.remove': 'Remove',
  'marketing.storefront.switcher.add.title': 'Offer another brand',
  'marketing.storefront.switcher.add.target': 'Brand and website',
  'marketing.storefront.switcher.add.choose': 'Choose a brand…',
  'marketing.storefront.switcher.add.noAddress': 'no verified address yet',
  'marketing.storefront.switcher.add.none':
    'There is no other brand with a website to offer. Another brand needs an active website channel that sells at one of its branches.',
  'marketing.storefront.switcher.add.submit': 'Add to the strip',
  'marketing.storefront.switcher.added': 'Brand added.',
  'marketing.storefront.switcher.updated': 'Saved.',
  'marketing.storefront.switcher.removed': 'Brand removed.',
  'marketing.storefront.switcher.reordered': 'Order saved.',
  'marketing.storefront.branches.title': 'Order of branches',
  'marketing.storefront.branches.hint':
    'A customer who shares their location sees the nearest branch first; this order only breaks a tie. A customer who shares none sees the branches in exactly this order. A branch you leave out is listed last, by name.',
  'marketing.storefront.branches.placed': 'In your order',
  'marketing.storefront.branches.nonePlaced':
    'No branch is placed. The storefront lists the branches by name.',
  'marketing.storefront.branches.unplaced': 'Not placed (listed last, by name)',
  'marketing.storefront.branches.noneUnplaced': 'Every branch is placed.',
  'marketing.storefront.branches.place': 'Place',
  'marketing.storefront.branches.unplace': 'Take out of the order',
  'marketing.storefront.branches.inactive': 'not active, so customers do not see it',
  'marketing.storefront.branches.save': 'Save order',
  'marketing.storefront.branches.reset': 'Undo changes',
  'marketing.storefront.branches.saved': 'Order saved.',
  'marketing.storefront.branches.stale':
    'Someone changed the branch order while you were editing. This is the order as it stands now; make your change again.',
} as const;
