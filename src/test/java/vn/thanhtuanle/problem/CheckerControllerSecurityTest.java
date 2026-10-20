package vn.thanhtuanle.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.thanhtuanle.config.SecurityConfig;
import vn.thanhtuanle.oj.common.security.OjJwtAuthenticationFilter;
import vn.thanhtuanle.oj.common.web.security.OjAccessDeniedHandler;
import vn.thanhtuanle.oj.common.web.security.OjAuthenticationEntryPoint;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET, PUT and DELETE {@code /problems/{slug}/checker} all need {@code problem:update}. */
@WebMvcTest(controllers = CheckerController.class)
@Import({SecurityConfig.class, OjJwtAuthenticationFilter.class,
        OjAuthenticationEntryPoint.class, OjAccessDeniedHandler.class})
@ActiveProfiles("test")
class CheckerControllerSecurityTest {

    private static final String URL = "/api/v1/problems/two-sum/checker";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckerService checkers;
    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private UserDetailsService userDetailsService;

    private static List<MockHttpServletRequestBuilder> everyMethod() {
        return List.of(
                get(URL),
                multipart(HttpMethod.PUT, URL).file(new MockMultipartFile("file", "spj.cpp", "text/plain",
                        "int main() { return 0; }\n".getBytes())),
                delete(URL));
    }

    @Test
    void anonymousIs401() throws Exception {
        for (MockHttpServletRequestBuilder request : everyMethod()) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(checkers);
    }

    @Test
    @WithMockUser(authorities = {"problem:create", "problem:read"})
    void withoutProblemUpdateIs403() throws Exception {
        for (MockHttpServletRequestBuilder request : everyMethod()) {
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(checkers);
    }

    @Test
    @WithMockUser(authorities = "problem:update")
    void withProblemUpdateIsAllowed() throws Exception {
        for (MockHttpServletRequestBuilder request : everyMethod()) {
            mockMvc.perform(request).andExpect(status().isOk());
        }
    }
}
