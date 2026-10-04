package vn.thanhtuanle.common.exception;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.thanhtuanle.oj.common.web.error.OjExceptionHandler;

/** problem-service answers errors with the handlers every OJ service shares; it adds none of its own. */
@RestControllerAdvice
public class GlobalExceptionHandler extends OjExceptionHandler {
}
