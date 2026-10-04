package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import vn.thanhtuanle.config.CustomAccessDeniedHandler;
import vn.thanhtuanle.config.CustomAuthenticationEntryPoint;
import vn.thanhtuanle.oj.common.security.OjJwtAuthenticationFilter;
import vn.thanhtuanle.config.SecurityConfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProblemController.class)
@Import({SecurityConfig.class, OjJwtAuthenticationFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class ProblemControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProblemService problemService;
    @MockBean
    private JwtDecoder jwtDecoder;
    @MockBean
    private UserDetailsService userDetailsService;

    @Test
    @WithMockUser(authorities = "problem:delete")
    void deleteAllowed_whenUserHasProblemDeletePermission() throws Exception {
        mockMvc.perform(delete("/api/v1/problems/some-slug"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void deleteForbidden_whenUserHasOnlyAdminRoleAuthority() throws Exception {
        mockMvc.perform(delete("/api/v1/problems/some-slug"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "problem:create")
    void deleteForbidden_whenUserHasWrongPermission() throws Exception {
        mockMvc.perform(delete("/api/v1/problems/some-slug"))
                .andExpect(status().isForbidden());
    }
}
