import { MessageKey } from '../../core/i18n/messages.en';
import {
  BlockingReason,
  ContactDirection,
  ContactOutcome,
  LeadClosedReason,
  LeadSource,
  LeadStatus,
  NextAction,
} from './leads-api';

/** The labels for ADR 0111's closed vocabularies, one typed table each so a missing code fails `tsc`. */
export const LEAD_STATUS_KEYS: Readonly<Record<LeadStatus, MessageKey>> = {
  NEW: 'customers.leads.status.NEW',
  CONTACTED: 'customers.leads.status.CONTACTED',
  CALLBACK_SCHEDULED: 'customers.leads.status.CALLBACK_SCHEDULED',
  CONVERTED: 'customers.leads.status.CONVERTED',
  DECLINED: 'customers.leads.status.DECLINED',
  LOST: 'customers.leads.status.LOST',
};

export const LEAD_SOURCE_KEYS: Readonly<Record<LeadSource, MessageKey>> = {
  STOREFRONT: 'customers.leads.source.STOREFRONT',
  TELEGRAM_BOT: 'customers.leads.source.TELEGRAM_BOT',
  SITE: 'customers.leads.source.SITE',
  RESERVATION: 'customers.leads.source.RESERVATION',
  CALLBACK_REQUEST: 'customers.leads.source.CALLBACK_REQUEST',
  AGGREGATOR_FIRST_ORDER: 'customers.leads.source.AGGREGATOR_FIRST_ORDER',
  B2B_CATERING_ENQUIRY: 'customers.leads.source.B2B_CATERING_ENQUIRY',
  CAMPAIGN_SCENARIO: 'customers.leads.source.CAMPAIGN_SCENARIO',
};

export const LEAD_REASON_KEYS: Readonly<Record<LeadClosedReason, MessageKey>> = {
  OUT_OF_CAPACITY: 'customers.leads.reason.OUT_OF_CAPACITY',
  OUT_OF_COVERAGE: 'customers.leads.reason.OUT_OF_COVERAGE',
  WRONG_CUISINE_OR_MENU: 'customers.leads.reason.WRONG_CUISINE_OR_MENU',
  NOT_REACHABLE: 'customers.leads.reason.NOT_REACHABLE',
  NOT_INTERESTED: 'customers.leads.reason.NOT_INTERESTED',
  DUPLICATE: 'customers.leads.reason.DUPLICATE',
  SPAM_OR_WRONG_NUMBER: 'customers.leads.reason.SPAM_OR_WRONG_NUMBER',
  OTHER: 'customers.leads.reason.OTHER',
};

export const CONTACT_DIRECTION_KEYS: Readonly<Record<ContactDirection, MessageKey>> = {
  INBOUND: 'customers.leads.direction.INBOUND',
  OUTBOUND: 'customers.leads.direction.OUTBOUND',
};

export const CONTACT_OUTCOME_KEYS: Readonly<Record<ContactOutcome, MessageKey>> = {
  CONNECTED: 'customers.leads.outcome.CONNECTED',
  NO_ANSWER: 'customers.leads.outcome.NO_ANSWER',
  DECLINED: 'customers.leads.outcome.DECLINED',
  VOICEMAIL: 'customers.leads.outcome.VOICEMAIL',
  BLOCKED: 'customers.leads.outcome.BLOCKED',
};

export const BLOCKING_REASON_KEYS: Readonly<Record<BlockingReason, MessageKey>> = {
  BLACKLISTED: 'customers.leads.blocking.BLACKLISTED',
  OUTSIDE_QUIET_HOURS: 'customers.leads.blocking.OUTSIDE_QUIET_HOURS',
  NO_CONSENT: 'customers.leads.blocking.NO_CONSENT',
  WRONG_NUMBER: 'customers.leads.blocking.WRONG_NUMBER',
};

export const NEXT_ACTION_KEYS: Readonly<Record<NextAction, MessageKey>> = {
  CALL_AGAIN: 'customers.leads.nextAction.CALL_AGAIN',
  AWAIT_GUEST: 'customers.leads.nextAction.AWAIT_GUEST',
  HAND_TO_BRANCH: 'customers.leads.nextAction.HAND_TO_BRANCH',
};
