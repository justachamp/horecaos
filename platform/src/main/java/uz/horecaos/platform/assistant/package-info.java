/**
 * The grounded assistant (ADR 0069): a participant in ADR 0059's conversations
 * that answers customers only from facts retrieved for that turn, never from
 * what a language model remembers.
 *
 * <p>The module owns the question classifier, the retrieval of platform facts,
 * the tenant's authored knowledge entries, the checks that a reply says nothing
 * its facts do not, the per-tenant spend ledger and ceiling, and the model
 * provider port ({@link uz.horecaos.platform.assistant.api.AssistantModelPort}).
 * It reaches every other module only through that module's {@code api} port --
 * catalog's {@code MenuSearchPort}, tenancy's {@code BranchDirectory}, ordering's
 * {@code CustomerBotOrderingPort} -- never across schemas, the discipline
 * {@code loyalty.api.ReferralGrantPort} and {@code customers.api.CustomerBlacklistPort}
 * already follow.
 *
 * <p>It depends on {@code conversations.api} to take a turn, and nothing in
 * {@code conversations} depends on it: the engine is a leaf that offers a turn to
 * whichever {@code ConversationParticipant} exists, and a build without this
 * module behaves exactly as before.
 *
 * <p><strong>It cannot complete an order.</strong> ADR 0069's default is
 * assemble-and-confirm, and stage one assembles nothing at all: this module has
 * no dependency on a cart, a checkout or a payment, and a source-scan test keeps
 * it that way. The provider adapter lives in {@code integration}, behind the
 * port, like every other provider on this platform.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Assistant")
package uz.horecaos.platform.assistant;
