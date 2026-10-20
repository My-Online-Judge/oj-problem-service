package vn.thanhtuanle.problem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.testcase.TestCaseBundlePublisher;
import vn.thanhtuanle.testcase.TestCaseBundleStore;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who sees INACTIVE problems over HTTP, on Postgres: only callers holding problem:update. */
@AutoConfigureMockMvc
class ProblemVisibilityOnPostgresTest extends PostgresTest {

    private static final String P = "vis-";
    private static final String LIST = "/api/v1/problems";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TestCaseBundlePublisher publisher;
    @MockitoBean TestCaseBundleStore bundleStore;

    @BeforeEach
    void seed() {
        insert(P + "a", 1, P + "a", "plain");
        insert(P + "b", 0, P + "b", "plain");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM t_problems WHERE problem_slug LIKE '" + P + "%'");
    }

    private void insert(String slug, int status, String title, String description) {
        jdbc.update("""
                INSERT INTO t_problems (id, hardness_level, memory_limit, status, time_limit, created_at, updated_at, title, problem_slug, description)
                VALUES (?, 1, 64, ?, 1000, now(), now(), ?, ?, ?)""", UUID.randomUUID(), status, title, slug, description);
    }

    @Test
    void userListHidesInactive() throws Exception {
        mvc.perform(get(LIST).param("search", P))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].problemSlug").value(org.hamcrest.Matchers.contains(P + "a")))
                .andExpect(jsonPath("$.pagination.totalElements").value(1));
    }

    @Test
    @WithMockUser
    void aUserWithoutTheAuthorityAlsoSeesOnlyActive() throws Exception {
        mvc.perform(get(LIST).param("search", P))
                .andExpect(jsonPath("$.data[?(@.problemSlug=='" + P + "b')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.problemSlug=='" + P + "a')]").isNotEmpty());
    }

    @Test
    @WithMockUser
    void userAskingForInactiveGetsNothing() throws Exception {
        mvc.perform(get(LIST).param("search", P).param("status", "INACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.pagination.totalElements").value(0));
    }

    @Test
    @WithMockUser(authorities = "problem:update")
    void editorListShowsInactive() throws Exception {
        mvc.perform(get(LIST).param("search", P))
                .andExpect(jsonPath("$.data[*].problemSlug").value(org.hamcrest.Matchers.containsInAnyOrder(P + "a", P + "b")))
                .andExpect(jsonPath("$.pagination.totalElements").value(2));
    }

    @Test
    @WithMockUser
    void userDetailOfInactiveIs404() throws Exception {
        mvc.perform(get(LIST + "/" + P + "b")).andExpect(status().isNotFound());
        mvc.perform(get(LIST + "/" + P + "a")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "problem:update")
    void editorDetailOfInactiveIs200() throws Exception {
        mvc.perform(get(LIST + "/" + P + "b")).andExpect(status().isOk());
    }

    @Test
    void searchPagesAndCountsAgree() throws Exception {
        for (int i = 0; i < 3; i++) {
            insert(P + "d" + i, 1, "t" + i, "only-in-description-" + P);
        }
        insert(P + "d-inactive", 0, "t", "only-in-description-" + P);
        mvc.perform(get(LIST).param("search", "only-in-description-" + P).param("size", "1"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.pagination.totalElements").value(3));
    }
}
