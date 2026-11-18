package io.terrakube.api;

import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.plugin.vcs.discovery.gitea.GiteaRepositoryDiscoveryService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class VcsRepositoryDiscoveryGiteaTests extends ServerApplicationTests {

    @Autowired
    GiteaRepositoryDiscoveryService giteaRepositoryDiscoveryService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    private Vcs giteaVcs() {
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITEA);
        vcs.setAccessToken("gitea-token-abc");
        vcs.setApiUrl("http://localhost:" + wireMockServer.port() + "/api/v1");
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        return vcs;
    }

    @Test
    void listGroupsIncludesTheUserAndItsOrganizations() {
        stubFor(get(urlPathEqualTo("/api/v1/user"))
                .withHeader("Authorization", equalTo("Bearer gitea-token-abc"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"login\":\"alice\"}")));
        stubFor(get(urlPathEqualTo("/api/v1/user/orgs"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":42,\"name\":\"infra\",\"username\":\"infra\"}]")));

        List<VcsGroupSummary> groups = giteaRepositoryDiscoveryService.listGroups(giteaVcs());

        assertThat(groups).extracting(VcsGroupSummary::getId).containsExactly("7", "42");
        assertThat(groups).extracting(VcsGroupSummary::getName).containsExactly("alice (personal)", "infra");
    }

    @Test
    void listRepositoriesSearchesTheGroupsOwnRepositoriesAndMapsFields() {
        String body = "{\"ok\":true,\"data\":[{\"name\":\"network\",\"full_name\":\"infra/network\","
                + "\"owner\":{\"login\":\"infra\"},\"clone_url\":\"https://git.example.com/infra/network.git\","
                + "\"private\":true,\"default_branch\":\"main\"}]}";
        stubFor(get(urlPathEqualTo("/api/v1/repos/search"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("X-Total-Count", "1").withBody(body)));

        VcsRepositoryPage page = giteaRepositoryDiscoveryService.listRepositories(giteaVcs(), "42", "", 1);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getFullName()).isEqualTo("infra/network");
        assertThat(page.getItems().get(0).getGroup()).isEqualTo("infra");
        assertThat(page.getItems().get(0).getUrl()).isEqualTo("https://git.example.com/infra/network.git");
        assertThat(page.getItems().get(0).isPrivateRepo()).isTrue();
        assertThat(page.getItems().get(0).getDefaultBranch()).isEqualTo("main");
        assertThat(page.isHasMore()).isFalse();
        verify(getRequestedFor(urlPathEqualTo("/api/v1/repos/search"))
                .withQueryParam("uid", equalTo("42"))
                .withQueryParam("exclusive", equalTo("true"))
                .withQueryParam("page", equalTo("1")));
    }

    @Test
    void listRepositoriesHasMoreWhenTheTotalCountIsAboveThisPage() {
        stubFor(get(urlPathEqualTo("/api/v1/repos/search"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("X-Total-Count", "51").withBody("{\"ok\":true,\"data\":[]}")));

        VcsRepositoryPage page = giteaRepositoryDiscoveryService.listRepositories(giteaVcs(), "42", "", 1);

        assertThat(page.isHasMore()).isTrue();
    }

    @Test
    void listRepositoriesPassesTheSearchAsQ() {
        stubFor(get(urlPathEqualTo("/api/v1/repos/search"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"data\":[]}")));

        giteaRepositoryDiscoveryService.listRepositories(giteaVcs(), "7", "terraform", 1);

        verify(getRequestedFor(urlPathEqualTo("/api/v1/repos/search")).withQueryParam("q", equalTo("terraform")));
    }

    @Test
    void listRepositoriesReturnsAnEmptyPageWhenTheApiFails() {
        stubFor(get(urlPathEqualTo("/api/v1/repos/search")).willReturn(aResponse().withStatus(500)));

        VcsRepositoryPage page = giteaRepositoryDiscoveryService.listRepositories(giteaVcs(), "42", "", 1);

        assertThat(page.getItems()).isEmpty();
        assertThat(page.isHasMore()).isFalse();
    }
}
