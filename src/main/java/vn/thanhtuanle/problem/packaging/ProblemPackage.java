package vn.thanhtuanle.problem.packaging;

import vn.thanhtuanle.problem.dto.CreateProblemDto;

import java.util.List;

/**
 * {@code problem.json}, the first entry of a problem package; the test cases follow as {@code testcases/<name>.in} and
 * {@code testcases/<name>.out}. {@code samples} names the test cases shown to users.
 */
public record ProblemPackage(int schemaVersion, CreateProblemDto problem, List<String> samples) {

    public static final int SCHEMA_VERSION = 1;
}
