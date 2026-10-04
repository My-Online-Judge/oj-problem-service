package vn.thanhtuanle.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import vn.thanhtuanle.common.payload.ApiResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerRetryAfterTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void rateLimitedException_carriesDynamicRetryAfter() {
        RateLimitedException ex = new RateLimitedException(ErrorCode.SUBMISSION_RATE_LIMITED, 7);

        ResponseEntity<ApiResponse<Object>> res = handler.handleRateLimitedException(ex);

        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(res.getBody().getMessage()).contains("submitting too fast");
    }
}
