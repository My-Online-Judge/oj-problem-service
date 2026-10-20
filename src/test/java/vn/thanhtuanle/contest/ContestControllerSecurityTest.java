package vn.thanhtuanle.contest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.thanhtuanle.config.SecurityConfig;
import vn.thanhtuanle.contest.dto.ContestDetail;
import vn.thanhtuanle.contest.dto.ContestListResponse;
import vn.thanhtuanle.contest.dto.ContestRequest;
import vn.thanhtuanle.oj.common.security.OjJwtAuthenticationFilter;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.oj.common.web.error.ResourceAlreadyExistException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.oj.common.web.security.OjAccessDeniedHandler;
import vn.thanhtuanle.oj.common.web.security.OjAuthenticationEntryPoint;
import vn.thanhtuanle.problem.dto.ProblemResponseDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ContestController.class)
@Import({SecurityConfig.class, OjJwtAuthenticationFilter.class,
        OjAuthenticationEntryPoint.class, OjAccessDeniedHandler.class})
@ActiveProfiles("test")
class ContestControllerSecurityTest {

    private static final String CONTESTS = "/api/v1/contests";
    private static final Instant START = Instant.parse("2026-10-21T02:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContestService contests;
    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private UserDetailsService userDetailsService;

    private static String body(String slug) {
        return """
                {"slug": "%s", "title": "Spring cup", "startsAt": "2026-10-21T02:00:00Z",
                 "endsAt": "2026-10-21T05:00:00Z", "status": "PUBLISHED", "problemSlugs": ["a-plus-b"]}""".formatted(slug);
    }

    private static ContestDetail detail(String slug) {
        return new ContestDetail(slug, "Spring cup", null, START, START.plusSeconds(3 * 3600), ContestStatus.PUBLISHED,
                ContestPhase.RUNNING, List.of(), Map.of(), NOW);
    }

    @Test
    void anonymousListIs200() throws Exception {
        when(contests.list(false)).thenReturn(new ContestListResponse(List.of(), List.of(), List.of(), NOW));

        mockMvc.perform(get(CONTESTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverTime").value("2026-10-21T03:00:00Z"));
    }

    @Test
    void anonymousCreateIs401() throws Exception {
        mockMvc.perform(post(CONTESTS).contentType(MediaType.APPLICATION_JSON).content(body("spring-cup")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(contests);
    }

    @Test
    @WithMockUser(authorities = {"contest:update", "contest:delete"})
    void createWithoutContestCreateIs403() throws Exception {
        mockMvc.perform(post(CONTESTS).contentType(MediaType.APPLICATION_JSON).content(body("spring-cup")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(contests);
    }

    @Test
    @WithMockUser(authorities = "contest:create")
    void createWithContestCreateIs201() throws Exception {
        when(contests.create(any(), anyBoolean())).thenReturn(detail("spring-cup"));

        mockMvc.perform(post(CONTESTS).contentType(MediaType.APPLICATION_JSON).content(body("spring-cup")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.slug").value("spring-cup"));
    }

    @Test
    @WithMockUser(authorities = "contest:create")
    void aBadSlugIs400() throws Exception {
        mockMvc.perform(post(CONTESTS).contentType(MediaType.APPLICATION_JSON).content(body("Bad Slug")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(contests);
    }

    @Test
    @WithMockUser(authorities = "contest:create")
    void aSlugClashIs409() throws Exception {
        when(contests.create(any(), anyBoolean())).thenThrow(new ResourceAlreadyExistException("Contest slug already exists: spring-cup"));

        mockMvc.perform(post(CONTESTS).contentType(MediaType.APPLICATION_JSON).content(body("spring-cup")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Contest slug already exists: spring-cup"));
    }

    @Test
    @WithMockUser(authorities = "contest:update")
    void updateIgnoresTheSlug() throws Exception {
        when(contests.update(eq("spring-cup"), any(ContestRequest.class), anyBoolean())).thenReturn(detail("spring-cup"));

        mockMvc.perform(put(CONTESTS + "/spring-cup").contentType(MediaType.APPLICATION_JSON).content(body("Bad Slug")))
                .andExpect(status().isOk());
    }

    /** contest:* alone reads a contest's problems by the public rule (DRAFT or before the start: 404). */
    @Test
    @WithMockUser(authorities = {"contest:create", "contest:update", "contest:delete"})
    void aContestOnlyRoleReadsProblemsByThePublicRule() throws Exception {
        when(contests.problem("spring-cup", "A", false))
                .thenThrow(new ResourceNotFoundException("Contest not found: spring-cup"));
        when(contests.detail("spring-cup", true, false, Optional.empty())).thenReturn(detail("spring-cup"));
        when(contests.create(any(), eq(false))).thenReturn(detail("spring-cup"));
        when(contests.update(eq("spring-cup"), any(ContestRequest.class), eq(false))).thenReturn(detail("spring-cup"));

        mockMvc.perform(get(CONTESTS + "/spring-cup/problems/A")).andExpect(status().isNotFound());
        mockMvc.perform(get(CONTESTS + "/spring-cup")).andExpect(status().isOk());
        mockMvc.perform(post(CONTESTS).contentType(MediaType.APPLICATION_JSON).content(body("spring-cup")))
                .andExpect(status().isCreated());
        mockMvc.perform(put(CONTESTS + "/spring-cup").contentType(MediaType.APPLICATION_JSON).content(body("spring-cup")))
                .andExpect(status().isOk());

        verify(contests).problem("spring-cup", "A", false);
        verify(contests).detail("spring-cup", true, false, Optional.empty());
        verify(contests).create(any(), eq(false));
        verify(contests).update(eq("spring-cup"), any(ContestRequest.class), eq(false));
    }

    @Test
    @WithMockUser(authorities = {"contest:update", "problem:update"})
    void aContestAndProblemEditorReadsEarly() throws Exception {
        when(contests.problem("spring-cup", "A", true)).thenReturn(new ProblemResponseDto());
        when(contests.detail("spring-cup", true, true, Optional.empty())).thenReturn(detail("spring-cup"));
        when(contests.update(eq("spring-cup"), any(ContestRequest.class), eq(true))).thenReturn(detail("spring-cup"));

        mockMvc.perform(get(CONTESTS + "/spring-cup/problems/A")).andExpect(status().isOk());
        mockMvc.perform(get(CONTESTS + "/spring-cup")).andExpect(status().isOk());
        mockMvc.perform(put(CONTESTS + "/spring-cup").contentType(MediaType.APPLICATION_JSON).content(body("spring-cup")))
                .andExpect(status().isOk());

        verify(contests).problem("spring-cup", "A", true);
        verify(contests).detail("spring-cup", true, true, Optional.empty());
        verify(contests).update(eq("spring-cup"), any(ContestRequest.class), eq(true));
    }

    @Test
    @WithMockUser(authorities = "problem:update")
    void aProblemEditorWithoutContestUpdateIsNoContestEditor() throws Exception {
        when(contests.problem("spring-cup", "A", false)).thenReturn(new ProblemResponseDto());

        mockMvc.perform(get(CONTESTS + "/spring-cup/problems/A")).andExpect(status().isOk());

        verify(contests).problem("spring-cup", "A", false);
    }

    @Test
    void anonymousContestProblemReadIs401() throws Exception {
        mockMvc.perform(get(CONTESTS + "/spring-cup/problems/A"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(contests);
    }

    /** No token, or one the lenient filter could not use (expired): the anonymous view, never a 500 or a 401. */
    @Test
    void anonymousDetailHasNoProgress() throws Exception {
        when(contests.detail("spring-cup", false, false, Optional.empty())).thenReturn(detail("spring-cup"));
        when(jwtDecoder.decode("expired")).thenThrow(new BadJwtException("Jwt expired"));

        mockMvc.perform(get(CONTESTS + "/spring-cup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myProgress").isEmpty());
        mockMvc.perform(get(CONTESTS + "/spring-cup").header(HttpHeaders.AUTHORIZATION, "Bearer expired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myProgress").isEmpty());
    }

    /** Identity always sets uid; a token without it is read as anonymous, not as a 500. */
    @Test
    void aTokenWithoutUidReadsAsAnonymous() throws Exception {
        Jwt jwt = Jwt.withTokenValue("no-uid").header("alg", "RS256").subject("alice")
                .claim(OjJwtAuthenticationFilter.AUTHORITIES_CLAIM, List.of()).build();
        when(jwtDecoder.decode("no-uid")).thenReturn(jwt);
        when(contests.detail("spring-cup", false, false, Optional.empty())).thenReturn(detail("spring-cup"));

        mockMvc.perform(get(CONTESTS + "/spring-cup").header(HttpHeaders.AUTHORIZATION, "Bearer no-uid"))
                .andExpect(status().isOk());

        verify(contests).detail("spring-cup", false, false, Optional.empty());
    }

    @Test
    void aSignedInViewerIsTheTokensUidAndAnEditorWithContestUpdate() throws Exception {
        UUID uid = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("good").header("alg", "RS256").subject("alice")
                .claim(OjJwtDecoders.UID_CLAIM, uid.toString())
                .claim(OjJwtAuthenticationFilter.AUTHORITIES_CLAIM, List.of("contest:update"))
                .build();
        when(jwtDecoder.decode("good")).thenReturn(jwt);
        when(contests.detail("spring-cup", true, false, Optional.of(uid))).thenReturn(detail("spring-cup"));

        mockMvc.perform(get(CONTESTS + "/spring-cup").header(HttpHeaders.AUTHORIZATION, "Bearer good"))
                .andExpect(status().isOk());

        verify(contests).detail("spring-cup", true, false, Optional.of(uid));
    }
}
