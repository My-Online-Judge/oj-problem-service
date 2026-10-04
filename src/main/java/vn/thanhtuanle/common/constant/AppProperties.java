package vn.thanhtuanle.common.constant;

import java.util.List;

public class AppProperties {
    public static final String X_JUDGE_SERVER_TOKEN = "X-Judge-Server-Token";
    public static final String REQUEST_PART_FILE = "file";
    public static final String REQUEST_PART_DATA = "data";
    public static final String DEFAULT_SORT_BY = "created_at";
    public static final String DEFAULT_SORT_DIRECTION = "DESC";
    public static final List<String> JUDGE_ENV = List.of("LANG=en_US.UTF-8", "LANGUAGE=en_US:en",
            "LC_ALL=en_US.UTF-8");

    public static final String INPUT_FILE_EXTENSION = ".in";
    public static final String OUTPUT_FILE_EXTENSION = ".out";
}
