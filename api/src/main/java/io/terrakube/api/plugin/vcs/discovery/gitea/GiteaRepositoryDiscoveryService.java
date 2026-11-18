package io.terrakube.api.plugin.vcs.discovery.gitea;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryDiscoveryProvider;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositorySummary;
import io.terrakube.api.rs.vcs.Vcs;

import lombok.extern.slf4j.Slf4j;

// Gitea and Forgejo. A group is the numeric ID of the token's user or of one of its
// organizations, so one /repos/search call serves listing and search alike.
@Slf4j
@Service
public class GiteaRepositoryDiscoveryService implements VcsRepositoryDiscoveryProvider {

    // Gitea's default MAX_RESPONSE_ITEMS; a larger limit is cut down to it.
    private static final int PAGE_SIZE = 50;
    private static final String PATH_LOGIN = "login";
    private static final String PARAM_LIMIT = "limit";

    private final ObjectMapper objectMapper;

    public GiteaRepositoryDiscoveryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<VcsGroupSummary> listGroups(Vcs vcs) {
        List<VcsGroupSummary> groups = new ArrayList<>();

        JsonNode me = callApi(vcs.getApiUrl() + "/user", vcs.getAccessToken()).map(ResponseEntity::getBody)
                .map(this::readTree).orElse(null);
        if (me != null) {
            groups.add(VcsGroupSummary.builder()
                    .id(me.path("id").asText())
                    .name(me.path(PATH_LOGIN).asText() + " (personal)")
                    .build());
        }

        String url = UriComponentsBuilder.fromUriString(vcs.getApiUrl() + "/user/orgs")
                .queryParam(PARAM_LIMIT, PAGE_SIZE)
                .toUriString();
        JsonNode orgs = callApi(url, vcs.getAccessToken()).map(ResponseEntity::getBody).map(this::readTree)
                .orElse(null);
        if (orgs != null) {
            for (JsonNode org : orgs) {
                groups.add(VcsGroupSummary.builder()
                        .id(org.path("id").asText())
                        .name(org.path("name").asText(org.path("username").asText()))
                        .build());
            }
        }
        return groups;
    }

    @Override
    public VcsRepositoryPage listRepositories(Vcs vcs, String group, String search, int page) {
        // exclusive: only repositories the group owns, not the ones its user collaborates on.
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(vcs.getApiUrl() + "/repos/search")
                .queryParam("uid", group)
                .queryParam("exclusive", true)
                .queryParam("sort", "alpha")
                .queryParam(PARAM_LIMIT, PAGE_SIZE)
                .queryParam("page", page);
        if (search != null && !search.isBlank()) {
            builder.queryParam("q", search);
        }

        ResponseEntity<String> response = callApi(builder.toUriString(), vcs.getAccessToken()).orElse(null);
        JsonNode body = response == null ? null : readTree(response.getBody());
        if (body == null) {
            return VcsRepositoryPage.builder().items(new ArrayList<>()).page(page).hasMore(false).build();
        }

        List<VcsRepositorySummary> items = new ArrayList<>();
        for (JsonNode repo : body.path("data")) {
            items.add(toSummary(repo));
        }
        String totalCount = response.getHeaders().getFirst("X-Total-Count");
        boolean hasMore = totalCount != null
                ? parseOrZero(totalCount) > (long) page * PAGE_SIZE
                : items.size() == PAGE_SIZE;
        return VcsRepositoryPage.builder().items(items).page(page).hasMore(hasMore).build();
    }

    private VcsRepositorySummary toSummary(JsonNode repo) {
        return VcsRepositorySummary.builder()
                .name(repo.path("name").asText())
                .fullName(repo.path("full_name").asText())
                .group(repo.path("owner").path(PATH_LOGIN).asText())
                .url(repo.path("clone_url").asText(repo.path("html_url").asText() + ".git"))
                .privateRepo(repo.path("private").asBoolean())
                .defaultBranch(repo.path("default_branch").asText(null))
                .build();
    }

    private static long parseOrZero(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private JsonNode readTree(String body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.error("Error parsing Gitea API response: {}", e.getMessage());
            return null;
        }
    }

    private Optional<ResponseEntity<String>> callApi(String url, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Authorization", "Bearer " + token);
        try {
            RestTemplate restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers),
                    String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return Optional.of(response);
            }
        } catch (Exception e) {
            log.error("Error calling Gitea API {}: {}", url, e.getMessage());
        }
        return Optional.empty();
    }
}
