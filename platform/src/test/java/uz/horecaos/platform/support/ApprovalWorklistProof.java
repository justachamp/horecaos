package uz.horecaos.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The three HTTP steps every "this producer reaches the worklist and can be decided" proof shares (operations row
 * 9.4, ADR 0181): the tenant owner publishes a policy through the operations twin, a second person reads the
 * worklist the console reads, and decides a request over the route the console calls.
 *
 * <p>What is proved is the loop and not the producer's own arithmetic: a request raised by the producer's real
 * call, under a policy the tenant authored through the surface the console now has, shows up on the operations
 * worklist under the producer's action code and approver capability, and the second person's decision settles it.
 * Each producer's own test then shows its effect landing only after that decision.
 */
public final class ApprovalWorklistProof {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ApprovalWorklistProof() {}

    /** Publishes a tenant-wide policy over the operations twin, as the owner does from Settings, Approvals. */
    public static void publishPolicy(
            MockMvc mvc,
            UUID tenantId,
            RequestPostProcessor owner,
            String actionCode,
            String approverCapability,
            String idempotencyKey)
            throws Exception {
        MvcResult published = mvc.perform(post("/api/v1/operations/tenants/" + tenantId + "/approval-policies")
                        .with(owner)
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionCode":"%s","scopeType":"TENANT",
                                 "thresholdDescription":"Published from the operations console by the proof",
                                 "requiredApproverCapability":"%s",
                                 "reason":"A second signature is wanted on this action"}
                                """.formatted(actionCode, approverCapability)))
                .andReturn();
        assertThat(published.getResponse().getStatus())
                .as(
                        "the owner publishes a policy for %s: %s",
                        actionCode, published.getResponse().getContentAsString())
                .isEqualTo(201);
    }

    /**
     * The worklist entry the console would show for an action, read as the approver: its id, the approver
     * capability the policy named, and whether this reader may decide it.
     */
    public static JsonNode listed(MockMvc mvc, UUID tenantId, RequestPostProcessor approver, String actionCode)
            throws Exception {
        MvcResult worklist = mvc.perform(get("/api/v1/operations/tenants/" + tenantId + "/approval-requests")
                        .with(approver))
                .andReturn();
        assertThat(worklist.getResponse().getStatus()).isEqualTo(200);
        JsonNode items =
                MAPPER.readTree(worklist.getResponse().getContentAsString()).get("items");
        for (JsonNode item : items) {
            if (actionCode.equals(item.get("actionCode").asString())) {
                return item;
            }
        }
        throw new AssertionError("%s is not on the tenant's worklist: %s"
                .formatted(actionCode, worklist.getResponse().getContentAsString()));
    }

    /** Approves over the operations decision route and answers the settled status. */
    public static String approve(
            MockMvc mvc, UUID tenantId, RequestPostProcessor approver, UUID requestId, String idempotencyKey)
            throws Exception {
        MvcResult decided = mvc.perform(
                        post("/api/v1/operations/tenants/" + tenantId + "/approval-requests/" + requestId + "/decision")
                                .with(approver)
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {"decision":"APPROVE","reason":"Checked, and the second signature is given"}"""))
                .andReturn();
        assertThat(decided.getResponse().getStatus())
                .as(decided.getResponse().getContentAsString())
                .isEqualTo(200);
        return MAPPER.readTree(decided.getResponse().getContentAsString())
                .get("status")
                .asString();
    }
}
