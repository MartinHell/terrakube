package io.terrakube.api.plugin.vcs.provider.gitea;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.RepoWebhook;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

// Payloads follow Gitea's own structs (modules/structs, services/convert):
// pull_request.url is the PR's web page, issue.pull_request has only html_url,
// repository.url is the repository's API URL.
class GiteaWebhookServiceTest {

    private static final String WORKSPACE_ID = "8f0c2b1e-0000-4000-8000-000000000001";
    private static final String SECRET = Base64.getEncoder()
            .encodeToString(WORKSPACE_ID.getBytes(StandardCharsets.UTF_8));

    WireMockServer gitea;
    GiteaWebhookService subject;
    Vcs vcs;
    String apiUrl;
    String repoApiUrl;

    @BeforeEach
    void setUp() throws Exception {
        gitea = new WireMockServer(options().dynamicPort());
        gitea.start();
        apiUrl = "http://localhost:" + gitea.port() + "/api/v1";
        repoApiUrl = apiUrl + "/repos/infra/network";

        TokenService tokenService = mock(TokenService.class);
        when(tokenService.getAccessToken(any(String[].class), any())).thenReturn("gitea-token");
        subject = new GiteaWebhookService(new ObjectMapper(), tokenService);
        subject.setWebhookRestTemplate(new RestTemplate(new HttpComponentsClientHttpRequestFactory()));
        ReflectionTestUtils.setField(subject, "apiBaseUrl", "https://terrakube-api.example.com");

        vcs = new Vcs();
        vcs.setVcsType(VcsType.GITEA);
        vcs.setApiUrl(apiUrl);

        gitea.stubFor(get(urlPathEqualTo("/api/v1/repos/infra/network/pulls/7/files"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"filename\":\"stacks/pilot/main.tf\",\"status\":\"modified\"},"
                                + "{\"filename\":\"stacks/new/main.tf\",\"previous_filename\":\"stacks/old/main.tf\","
                                + "\"status\":\"renamed\"}]")));
        gitea.stubFor(get(urlPathEqualTo("/api/v1/repos/infra/network/pulls/7"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(prApiBody(7, 3))));
        // PR 8 comes from a fork (head repository 9).
        gitea.stubFor(get(urlPathEqualTo("/api/v1/repos/infra/network/pulls/8"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(prApiBody(8, 9))));
        // PR 9 is an AGit PR (git push HEAD:refs/for/main): Gitea's convert.ToAPIPullRequest
        // gives it the base repository as head, an empty label and ref refs/pull/9/head.
        gitea.stubFor(get(urlPathEqualTo("/api/v1/repos/infra/network/pulls/9"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(prApiBody(9, 3).replace("\"label\":\"feat/x\",\"ref\":\"feat/x\"",
                                "\"label\":\"\",\"ref\":\"refs/pull/9/head\""))));
    }

    // GET /repos/{owner}/{repo}/pulls/{n}: head and base carry their repository.
    private static String prApiBody(int number, int headRepoId) {
        return "{\"number\":" + number + ",\"head\":{\"label\":\"feat/x\",\"ref\":\"feat/x\",\"sha\":\"abc123\",\"repo_id\":" + headRepoId
                + ",\"repo\":{\"id\":" + headRepoId + "}},\"base\":{\"label\":\"main\",\"ref\":\"main\",\"sha\":\"def456\",\"repo_id\":3,"
                + "\"repo\":{\"id\":3}}}";
    }

    @AfterEach
    void tearDown() {
        gitea.stop();
    }

    private String repository() {
        return "{\"id\":3,\"name\":\"network\",\"full_name\":\"infra/network\",\"owner\":{\"login\":\"infra\"},"
                + "\"html_url\":\"https://git.example.com/infra/network\",\"url\":\"" + repoApiUrl + "\"}";
    }

    private static Map<String, String> headers(String event, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        // What Gitea's deliver.go sends (lowercased, as WebhookHeaders.caseInsensitive leaves them).
        return Map.of("x-github-event", event, "x-gitea-event", event,
                "x-hub-signature-256", "sha256=" + sig, "x-gitea-signature", sig);
    }

    private String pullRequestPayload(String action) {
        return pullRequestPayload(action, 3);
    }

    private String pullRequestPayload(String action, int headRepoId) {
        return "{\"action\":\"" + action + "\",\"number\":7,"
                + "\"pull_request\":{\"id\":70,\"number\":7,\"url\":\"https://git.example.com/infra/network/pulls/7\","
                + "\"html_url\":\"https://git.example.com/infra/network/pulls/7\",\"user\":{\"login\":\"alice\"},"
                + "\"head\":{\"label\":\"feat/x\",\"ref\":\"feat/x\",\"sha\":\"abc123\",\"repo\":{\"id\":" + headRepoId + "}},"
                + "\"base\":{\"ref\":\"main\",\"sha\":\"def456\",\"repo\":{\"id\":3}}},"
                + "\"repository\":" + repository() + ",\"sender\":{\"login\":\"alice\"}}";
    }

    @Test
    void pushToAnOpenPrIsASynchronizeWithTheApiFileList() throws Exception {
        String payload = pullRequestPayload("synchronized");

        WebhookResult result = subject.processWebhook(payload, headers("pull_request", payload), SECRET, vcs);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getVia()).isEqualTo("Gitea");
        assertThat(result.getPrNumber()).isEqualTo(7);
        assertThat(result.getCommit()).isEqualTo("abc123");
        assertThat(result.getBranch()).isEqualTo("feat/x");
        assertThat(result.getCreatedBy()).isEqualTo("alice");
        assertThat(result.getPrFilesUrl()).isEqualTo(repoApiUrl + "/pulls/7/files");
        assertThat(result.getFileChanges())
                .containsExactly("stacks/pilot/main.tf", "stacks/new/main.tf", "stacks/old/main.tf");
    }

    @Test
    void openedPrIsAccepted() throws Exception {
        String payload = pullRequestPayload("opened");

        WebhookResult result = subject.processWebhook(payload, headers("pull_request", payload), SECRET, vcs);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getPrNumber()).isEqualTo(7);
    }

    @Test
    void otherPrActionsAreIgnored() throws Exception {
        String payload = pullRequestPayload("label_updated");

        WebhookResult result = subject.processWebhook(payload, headers("pull_request", payload), SECRET, vcs);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void wrongSignatureIsRejected() throws Exception {
        String payload = pullRequestPayload("opened");
        Map<String, String> headers = headers("pull_request", payload.replace("alice", "mallory"));

        WebhookResult result = subject.processWebhook(payload, headers, SECRET, vcs);

        assertThat(result.isValid()).isFalse();
    }

    private String prCommentPayload(String body) {
        return prCommentPayload(body, 7);
    }

    private String prCommentPayload(String body, int number) {
        return "{\"action\":\"created\","
                + "\"issue\":{\"id\":71,\"number\":" + number + ",\"title\":\"x\",\"user\":{\"login\":\"alice\"},"
                + "\"pull_request\":{\"merged\":false,\"merged_at\":null,\"draft\":false,"
                + "\"html_url\":\"https://git.example.com/infra/network/pulls/7\"}},"
                + "\"pull_request\":{\"id\":70,\"number\":7,\"url\":\"https://git.example.com/infra/network/pulls/7\"},"
                + "\"comment\":{\"id\":901,\"body\":\"" + body + "\",\"user\":{\"login\":\"bob\"}},"
                + "\"repository\":" + repository() + ",\"sender\":{\"login\":\"bob\"},\"is_pull\":true}";
    }

    @Test
    void prCommentCommandResolvesThePrFromTheApi() throws Exception {
        String payload = prCommentPayload("terrakube plan");

        WebhookResult result = subject.processWebhook(payload, headers("issue_comment", payload), SECRET, vcs);

        assertThat(result.isValid()).isTrue();
        assertThat(result.isPrComment()).isTrue();
        assertThat(result.getCommentCommand()).isEqualTo("plan");
        assertThat(result.getCommentId()).isEqualTo("901");
        assertThat(result.getPrNumber()).isEqualTo(7);
        assertThat(result.getCreatedBy()).isEqualTo("bob");
        assertThat(result.getCommit()).isEqualTo("abc123");
        assertThat(result.getBranch()).isEqualTo("feat/x");
        assertThat(result.getFileChanges()).contains("stacks/pilot/main.tf");
    }

    @Test
    void sharedWebhookPrCommentDefersToTheApiUrl() {
        String payload = prCommentPayload("terrakube plan");

        WebhookResult result = subject.parseGitHubPayload(payload, Map.of("x-github-event", "issue_comment"));

        assertThat(result.isPrComment()).isTrue();
        assertThat(result.getVia()).isEqualTo("Gitea");
        assertThat(result.getPrDetailsUrl()).isEqualTo(repoApiUrl + "/pulls/7");
        assertThat(result.getPrFilesUrl()).isEqualTo(repoApiUrl + "/pulls/7/files");
    }

    @Test
    void commentOnAPlainIssueIsIgnored() {
        String payload = "{\"action\":\"created\",\"issue\":{\"number\":3,\"pull_request\":null},"
                + "\"comment\":{\"id\":5,\"body\":\"terrakube plan\",\"user\":{\"login\":\"bob\"}},"
                + "\"repository\":" + repository() + ",\"is_pull\":false}";

        WebhookResult result = subject.parseGitHubPayload(payload, Map.of("x-github-event", "issue_comment"));

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void pushIsParsedUnchanged() {
        String payload = "{\"ref\":\"refs/heads/main\",\"before\":\"0\",\"after\":\"abc123\","
                + "\"commits\":[{\"id\":\"abc123\",\"added\":[\"a.tf\"],\"removed\":[],\"modified\":[\"b.tf\"]}],"
                + "\"head_commit\":{\"id\":\"abc123\",\"added\":[\"a.tf\"],\"removed\":[],\"modified\":[\"b.tf\"]},"
                + "\"repository\":" + repository() + ",\"pusher\":{\"login\":\"alice\",\"email\":\"alice@example.com\"},"
                + "\"sender\":{\"login\":\"alice\"}}";

        WebhookResult result = subject.parseGitHubPayload(payload, Map.of("x-github-event", "push"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getBranch()).isEqualTo("main");
        assertThat(result.getCommit()).isEqualTo("abc123");
        assertThat(result.getCreatedBy()).isEqualTo("alice");
        assertThat(result.getFileChanges()).containsExactly("a.tf", "b.tf");
    }

    @Test
    void publishedReleaseIsARelease() {
        String payload = "{\"action\":\"published\",\"release\":{\"id\":1,\"tag_name\":\"v1.2.0\"},"
                + "\"repository\":" + repository() + "}";

        WebhookResult result = subject.parseGitHubPayload(payload, Map.of("x-github-event", "release"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.isRelease()).isTrue();
        assertThat(result.getBranch()).isEqualTo("v1.2.0");
    }

    @Test
    void createsTheRepositoryHookWithGiteasType() throws Exception {
        gitea.stubFor(post(urlPathEqualTo("/api/v1/repos/infra/network/hooks"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42,\"type\":\"gitea\"}")));
        RepoWebhook repoWebhook = new RepoWebhook();
        repoWebhook.setId(UUID.fromString("00000000-0000-4000-8000-0000000000aa"));
        repoWebhook.setRepositoryUrl("https://git.example.com/infra/network");
        repoWebhook.setWebhookSecret("s3cret");
        repoWebhook.setVcs(vcs);

        String id = subject.createOrUpdateRepoWebhook(repoWebhook,
                Set.of(WebhookEventType.PUSH, WebhookEventType.PULL_REQUEST), true, false);

        assertThat(id).isEqualTo("42");
        String sent = gitea.findAll(postRequestedFor(urlPathEqualTo("/api/v1/repos/infra/network/hooks")))
                .get(0).getBodyAsString();
        JsonNode body = new ObjectMapper().readTree(sent);
        assertThat(body.path("type").asText()).isEqualTo("gitea");
        assertThat(body.path("active").asBoolean()).isTrue();
        assertThat(body.has("name")).isFalse();
        assertThat(body.path("config").path("url").asText())
                .isEqualTo("https://terrakube-api.example.com/webhook/v2/00000000-0000-4000-8000-0000000000aa");
        assertThat(body.path("config").path("secret").asText()).isEqualTo("s3cret");
        assertThat(body.path("config").path("content_type").asText()).isEqualTo("json");
        assertThat(body.path("events")).extracting(JsonNode::asText)
                .contains("push", "pull_request", "issue_comment");
    }
    @Test
    void forkPrIsIgnoredAndItsFilesAreNotFetched() throws Exception {
        String payload = pullRequestPayload("opened", 9);

        WebhookResult result = subject.processWebhook(payload, headers("pull_request", payload), SECRET, vcs);

        assertThat(result.isValid()).isFalse();
        assertThat(gitea.findAll(getRequestedFor(urlPathEqualTo("/api/v1/repos/infra/network/pulls/7/files"))))
                .isEmpty();
    }

    @Test
    void prWhoseHeadRepositoryIsGoneIsIgnored() throws Exception {
        String payload = pullRequestPayload("opened").replace("\"repo\":{\"id\":3}},\"base\"", "\"repo\":null},\"base\"");

        WebhookResult result = subject.processWebhook(payload, headers("pull_request", payload), SECRET, vcs);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void commentCommandOnAForkPrIsIgnored() throws Exception {
        String payload = prCommentPayload("terrakube plan", 8);

        WebhookResult result = subject.processWebhook(payload, headers("issue_comment", payload), SECRET, vcs);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void sharedWebhookDoesNotResolveAForkPr() {
        WebhookResult result = new WebhookResult();

        boolean resolved = subject.resolvePrDetails(vcs, "https://git.example.com/infra/network",
                repoApiUrl + "/pulls/8", result);

        assertThat(resolved).isFalse();
        assertThat(result.getCommit()).isNull();
    }

    @Test
    void applyCommentIsIgnored() throws Exception {
        String payload = prCommentPayload("terrakube apply");

        WebhookResult result = subject.processWebhook(payload, headers("issue_comment", payload), SECRET, vcs);

        assertThat(result.isValid()).isFalse();
        assertThat(result.isPrComment()).isFalse();
    }

    @Test
    void agitPrIsIgnoredOnEveryPath() throws Exception {
        String payload = pullRequestPayload("opened")
                .replace("\"label\":\"feat/x\",\"ref\":\"feat/x\"", "\"label\":\"\",\"ref\":\"refs/pull/7/head\"");
        assertThat(subject.processWebhook(payload, headers("pull_request", payload), SECRET, vcs).isValid()).isFalse();

        String comment = prCommentPayload("terrakube plan", 9);
        assertThat(subject.processWebhook(comment, headers("issue_comment", comment), SECRET, vcs).isValid())
                .isFalse();

        assertThat(subject.resolvePrDetails(vcs, "https://git.example.com/infra/network",
                repoApiUrl + "/pulls/9", new WebhookResult())).isFalse();
    }
    // Gitea's hook IDs are small numbers ("34"); the legacy check for a stored
    // full URL took substring(0, 4) and threw on anything shorter, which failed
    // every migration of a Gitea workspace to the shared webhook.
    @Test
    void deletesAHookWithAShortId() {
        gitea.stubFor(delete(urlPathEqualTo("/api/v1/repos/infra/network/hooks/34"))
                .willReturn(aResponse().withStatus(204)));
        Workspace workspace = new Workspace();
        workspace.setSource("https://git.example.com/infra/network");
        workspace.setVcs(vcs);

        subject.deleteWebhook(workspace, "34");

        assertThat(gitea.findAll(deleteRequestedFor(urlPathEqualTo("/api/v1/repos/infra/network/hooks/34")))).hasSize(1);
    }
}
