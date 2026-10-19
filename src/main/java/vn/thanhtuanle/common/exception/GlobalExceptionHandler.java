package vn.thanhtuanle.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import vn.thanhtuanle.oj.common.web.error.OjExceptionHandler;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;

/** problem-service answers errors with the handlers every OJ service shares, plus the upload size limit. */
@RestControllerAdvice
public class GlobalExceptionHandler extends OjExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error(413, "Upload exceeds 50 MB"));
    }
}
