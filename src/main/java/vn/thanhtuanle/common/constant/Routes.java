package vn.thanhtuanle.common.constant;

public class Routes {

    private Routes() {
    }

    public static final String BASE_API = "/api/v1";

    public static final String PROBLEMS = BASE_API + "/problems";
    public static final String PROBLEM_TEST_CASES = PROBLEMS + "/{slug}/test-cases";
}
