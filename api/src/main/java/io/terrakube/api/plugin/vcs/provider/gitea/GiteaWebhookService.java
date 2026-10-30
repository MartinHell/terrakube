package io.terrakube.api.plugin.vcs.provider.gitea;

import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.rs.job.JobVia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Gitea (and Forgejo) speak GitHub's REST API under /api/v1 and send GitHub's
 * webhook headers (X-GitHub-Event, X-Hub-Signature-256), so this reuses the
 * GitHub provider and only fixes what differs:
 * <ul>
 * <li>creating a repository hook needs {@code "type":"gitea"};</li>
 * <li>a push to an open PR is action {@code synchronized}, not {@code synchronize};</li>
 * <li>a new release is action {@code published}, not {@code created};</li>
 * <li>{@code pull_request.url} is the PR's web page, and a PR comment's
 * {@code issue.pull_request} has no URL at all, while the GitHub code fetches the
 * PR and its files from the API URL. Both are rebuilt from {@code repository.url},
 * which is the repository's API URL.</li>
 * <li>a plain issue carries {@code "pull_request": null}, which the GitHub code
 * would read as a PR.</li>
 * </ul>
 * Connection: an access token of a Gitea user, stored as the VCS access token
 * (the GitHub OAuth and App flows don't apply).
 */
@Service
@Slf4j
public class GiteaWebhookService extends GitHubWebhookService {

    private final ObjectMapper objectMapper;

    public GiteaWebhookService(ObjectMapper objectMapper, TokenService tokenService) {
        super(objectMapper, tokenService);
        this.objectMapper = objectMapper;
    }

    @Override
    protected String via() {
        return JobVia.GITEA.getValue();
    }

    // Fork PRs run code from outside the repository; Gitea gives us no
    // reliable way to tell whether its author may write here (the API's
    // collaborator permission leaves out team access), so they never trigger.
    @Override
    protected boolean acceptsForkPrs() {
        return false;
    }

    // "terrakube apply" from a comment: anyone who can comment could apply,
    // and for the same reason we can't check that they may write. Applies
    // happen on merge instead.
    @Override
    protected String parseTerrakubeCommand(String commentBody) {
        String command = super.parseTerrakubeCommand(commentBody);
        if ("apply".equals(command)) {
            log.warn("Ignoring 'terrakube apply' comment: not supported for Gitea, apply by merging");
            return null;
        }
        return command;
    }

    // Gitea ignores insecure_ssl and name; its own "type" is required. On Gitea
    // "pull_request" also covers comments on PRs (pull_request_comment), so the
    // GitHub event list works unchanged.
    @Override
    protected String newHookBody(String webhookUrl, String secret, String events) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "gitea");
        body.put("active", true);
        try {
            body.set("events", objectMapper.readTree("[" + events + "]"));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid webhook event list: " + events, e);
        }
        ObjectNode config = body.putObject("config");
        config.put("url", webhookUrl);
        config.put("secret", secret);
        config.put("content_type", "json");
        return body.toString();
    }

    @Override
    protected String normalizePayload(String jsonPayload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonPayload);
        } catch (Exception e) {
            // Let the GitHub parser report it.
            return jsonPayload;
        }
        if (!(root instanceof ObjectNode rootNode)) {
            return jsonPayload;
        }

        String repoApiUrl = rootNode.path("repository").path("url").asText("");
        String action = rootNode.path("action").asText("");
        JsonNode pullRequest = rootNode.path("pull_request");
        JsonNode issue = rootNode.path("issue");

        if (rootNode.has("release")) {
            if ("published".equals(action)) {
                rootNode.put("action", "created");
            }
        } else if (issue.isObject()) {
            if (issue.path("pull_request").isObject()) {
                // issue_comment on a PR
                ((ObjectNode) issue.path("pull_request")).put("url",
                        repoApiUrl + "/pulls/" + issue.path("number").asText());
            } else {
                // Gitea sends "pull_request": null on plain issues; GitHub omits the
                // key, and the GitHub parser treats its presence as "this is a PR".
                ((ObjectNode) issue).remove("pull_request");
            }
        } else if (pullRequest.isObject()) {
            if ("synchronized".equals(action)) {
                rootNode.put("action", "synchronize");
            }
            ((ObjectNode) pullRequest).put("url",
                    repoApiUrl + "/pulls/" + rootNode.path("number").asText());
        }

        return rootNode.toString();
    }
}
