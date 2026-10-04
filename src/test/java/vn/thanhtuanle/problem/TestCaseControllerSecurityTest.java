package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import vn.thanhtuanle.oj.common.web.security.OjAccessDeniedHandler;
import vn.thanhtuanle.oj.common.web.security.OjAuthenticationEntryPoint;
import vn.thanhtuanle.oj.common.security.OjJwtAuthenticationFilter;
import vn.thanhtuanle.config.SecurityConfig;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TestCaseController.class)
@Import({SecurityConfig.class, OjJwtAuthenticationFilter.class,
        OjAuthenticationEntryPoint.class, OjAccessDeniedHandler.class})
@ActiveProfiles("test")
class TestCaseControllerSecurityTest {

    private static final String BASE = "/api/v1/problems/some-slug/test-cases";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TestCaseService testCaseService;
    @MockBean
    private JwtDecoder jwtDecoder;
    @MockBean
    private UserDetailsService userDetailsService;

    @Test
    @WithMockUser(authorities = "problem:update")
    void listAllowed_whenUserHasProblemUpdatePermission() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void listForbidden_whenUserHasOnlyAdminRoleAuthority() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "problem:create")
    void listForbidden_whenUserHasWrongPermission() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "problem:update")
    void deleteAllowed_whenUserHasProblemUpdatePermission() throws Exception {
        mockMvc.perform(delete(BASE + "/" + UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "role:read")
    void deleteForbidden_whenUserHasWrongPermission() throws Exception {
        mockMvc.perform(delete(BASE + "/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "problem:update")
    void patchAllowed_whenUserHasProblemUpdatePermission() throws Exception {
        mockMvc.perform(patch(BASE + "/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "role:read")
    void patchForbidden_whenUserHasWrongPermission() throws Exception {
        mockMvc.perform(patch(BASE + "/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sample\":true}"))
                .andExpect(status().isForbidden());
    }
}
