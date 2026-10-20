package vn.thanhtuanle.common.exception;

import lombok.Getter;
import vn.thanhtuanle.oj.common.web.error.ErrorCodeSpec;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode implements ErrorCodeSpec {
    ACCESS_DENIED("Access denied", HttpStatus.FORBIDDEN),
    CONTEST_STARTED("This contest has started: its problems and start time are locked and it cannot be deleted", HttpStatus.CONFLICT),
    /** Without problem:update: a contest holding INACTIVE problems decides when they go public, so it is not moved. */
    CONTEST_HOLDS_INACTIVE_PROBLEMS("This contest holds problems that are not public yet: only a problem editor can publish it or move its start earlier", HttpStatus.FORBIDDEN),
    UNCATEGORIZED_EXCEPTION("Uncategorized error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus statusCode;

    ErrorCode(String message, HttpStatus statusCode) {
        this.message = message;
        this.statusCode = statusCode;
    }
}
