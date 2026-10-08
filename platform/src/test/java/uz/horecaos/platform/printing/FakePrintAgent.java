package uz.horecaos.platform.printing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.support.printing.FakePrinter;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * An agent, in process, speaking the protocol (ADR 0154, "Job lifecycle and the agent contract"): the whole of
 * what the reference agent does and nothing it does not. It claims jobs for the printers it can reach, fetches the
 * bytes of each, writes them to the printer's socket, and reports what happened. It holds no template, no language
 * and no business rule; the only thing it knows about a printer is its alias, its state and its port.
 *
 * <p>Every call carries the device's own token and a fresh {@code Idempotency-Key}, as a real agent's would.
 */
final class FakePrintAgent {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What one pass over the queue did, job by job. */
    record Handled(UUID jobId, String printerKey, String documentType, String reported, int httpStatus) {}

    private final MockMvc mvc;
    private final RequestPostProcessor token;
    private final String base;
    private final Map<String, FakePrinter> printers = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private String build = "fake-agent 1.0";

    FakePrintAgent(MockMvc mvc, RequestPostProcessor token, String base) {
        this.mvc = mvc;
        this.token = token;
        this.base = base;
    }

    FakePrintAgent reaches(String alias, FakePrinter printer) {
        printers.put(alias, printer);
        statuses.put(alias, "ONLINE");
        return this;
    }

    FakePrintAgent reportsStatus(String alias, String status) {
        statuses.put(alias, status);
        return this;
    }

    FakePrintAgent build(String value) {
        this.build = value;
        return this;
    }

    /** The claim body: every printer it can reach, and what each last said. */
    private String claimBody(int maxJobs) {
        List<String> entries = new ArrayList<>();
        statuses.forEach((alias, status) -> entries.add("{\"key\":\"" + alias + "\",\"status\":\"" + status + "\"}"));
        return "{\"printers\":[" + String.join(",", entries) + "],\"maxJobs\":" + maxJobs
                + ",\"waitSeconds\":0,\"agentBuild\":\"" + build + "\"}";
    }

    MvcResult claim(int maxJobs) throws Exception {
        return mvc.perform(post(base + "/claims")
                        .with(token)
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "claim-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(maxJobs)))
                .andReturn();
    }

    List<JsonNode> claimed(int maxJobs) throws Exception {
        MvcResult result = claim(maxJobs);
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError("claim answered " + result.getResponse().getStatus() + ": "
                    + result.getResponse().getContentAsString());
        }
        List<JsonNode> jobs = new ArrayList<>();
        JSON.readTree(result.getResponse().getContentAsString()).get("jobs").forEach(jobs::add);
        return jobs;
    }

    MvcResult artifact(UUID jobId, UUID lease) throws Exception {
        return mvc.perform(
                        get(base + "/jobs/" + jobId + "/artifact").with(token).param("lease", lease.toString()))
                .andReturn();
    }

    MvcResult report(UUID jobId, UUID lease, String outcome, @Nullable String failureCode) throws Exception {
        String body = "{\"leaseToken\":\"" + lease + "\",\"outcome\":\"" + outcome + "\""
                + (failureCode == null ? "" : ",\"failureCode\":\"" + failureCode + "\"") + "}";
        return mvc.perform(post(base + "/jobs/" + jobId + "/results")
                        .with(token)
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "result-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /**
     * One pass: claim, and for each job fetch the bytes, write them to the printer and report PRINTED -- or
     * report FAILED when the printer it would write to is not one it can reach.
     */
    List<Handled> handleOnce() throws Exception {
        List<Handled> handled = new ArrayList<>();
        for (JsonNode job : claimed(10)) {
            UUID jobId = UUID.fromString(job.get("jobId").asString());
            UUID lease = UUID.fromString(job.get("leaseToken").asString());
            String key = job.get("printerKey").asString();
            String type = job.get("documentType").asString();
            FakePrinter printer = printers.get(key);
            MvcResult artifact = artifact(jobId, lease);
            if (artifact.getResponse().getStatus() != 200) {
                handled.add(new Handled(
                        jobId, key, type, "NONE", artifact.getResponse().getStatus()));
                continue;
            }
            if (printer == null) {
                MvcResult failed = report(jobId, lease, "FAILED", "PRINTER_OFFLINE");
                handled.add(new Handled(
                        jobId, key, type, "FAILED", failed.getResponse().getStatus()));
                continue;
            }
            FakePrinter.send(printer.port(), artifact.getResponse().getContentAsByteArray());
            MvcResult printed = report(jobId, lease, "PRINTED", null);
            handled.add(new Handled(
                    jobId, key, type, "PRINTED", printed.getResponse().getStatus()));
        }
        return handled;
    }
}
