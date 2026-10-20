package vn.thanhtuanle.common.exception;

import lombok.Getter;
import vn.thanhtuanle.oj.common.web.error.ErrorCodeSpec;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode implements ErrorCodeSpec {
    ACCESS_DENIED("Access denied", HttpStatus.FORBIDDEN),
    CONTEST_STARTED("This contest has started: its problems and start time are locked and it cannot be deleted", HttpStatus.CONFLICT),
    UNCATEGORIZED_EXCEPTION("Uncategorized error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus statusCode;

    ErrorCode(String message, HttpStatus statusCode) {
        this.message = message;
        this.statusCode = statusCode;
    }
}
