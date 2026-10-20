package vn.thanhtuanle.problem.packaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.thanhtuanle.config.SecurityConfig;
import vn.thanhtuanle.oj.common.security.OjJwtAuthenticationFilter;
import vn.thanhtuanle.oj.common.web.security.OjAccessDeniedHandler;
import vn.thanhtuanle.oj.common.web.security.OjAuthenticationEntryPoint;
import vn.thanhtuanle.problem.dto.CreateProblemDto;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProblemPackageController.class)
@Import({SecurityConfig.class, OjJwtAuthenticationFilter.class,
        OjAuthenticationEntryPoint.class, OjAccessDeniedHandler.class})
@ActiveProfiles("test")
class ProblemPackageControllerSecurityTest {

    private static final String IMPORT = "/api/v1/problems/import";
    private static final String EXPORT = "/api/v1/problems/two-sum/export";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProblemPackageService packages;
    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private UserDetailsService userDetailsService;

    private static MockMultipartFile packageFile() {
        return new MockMultipartFile("file", "two-sum.zip", "application/zip", new byte[] {1});
    }

    @Test
    void anonymousImportGets401() throws Exception {
        mockMvc.perform(multipart(IMPORT).file(packageFile())).andExpect(status().isUnauthorized());
        verifyNoInteractions(packages);
    }

    @Test
    @WithMockUser(authorities = "problem:update")
    void importWithoutProblemCreateGets403() throws Exception {
        mockMvc.perform(multipart(IMPORT).file(packageFile())).andExpect(status().isForbidden());
        verifyNoInteractions(packages);
    }

    @Test
    @WithMockUser(authorities = "problem:create")
    void exportWithoutProblemUpdateGets403() throws Exception {
        mockMvc.perform(get(EXPORT)).andExpect(status().isForbidden());
        verifyNoInteractions(packages);
    }

    /**
     * The stream waits until the request thread has left the filter chain: both commit Spring Security's headers onto
     * the one mock response, and its header map is not thread-safe.
     */
    @Test
    @WithMockUser(authorities = "problem:update")
    void exportSetsTheDownloadHeaders() throws Exception {
        CreateProblemDto problem = CreateProblemDto.builder().problemSlug("two-sum").build();
        when(packages.plan("two-sum")).thenReturn(new ProblemPackageService.PackagePlan(problem, List.of()));
        CountDownLatch requestThreadDone = new CountDownLatch(1);
        doAnswer(write -> requestThreadDone.await(10, TimeUnit.SECONDS)).when(packages).write(any(), any());

        MvcResult started = mockMvc.perform(get(EXPORT))
                .andExpect(request().asyncStarted())
                .andReturn();
        requestThreadDone.countDown();
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"two-sum.zip\""))
                .andExpect(content().contentType("application/zip"));
    }
}
