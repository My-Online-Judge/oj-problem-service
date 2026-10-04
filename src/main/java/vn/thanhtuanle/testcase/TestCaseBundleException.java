package vn.thanhtuanle.testcase;

public class TestCaseBundleException extends RuntimeException {
    public TestCaseBundleException(String message) {
        super(message);
    }

    public TestCaseBundleException(String message, Throwable cause) {
        super(message, cause);
    }
}
