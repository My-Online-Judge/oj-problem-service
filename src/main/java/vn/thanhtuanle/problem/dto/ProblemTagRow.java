package vn.thanhtuanle.problem.dto;

import java.util.UUID;

/** Native-query row projection: one (problem id, tag) pair from t_problem_tags. */
public interface ProblemTagRow {
    UUID getProblemId();

    String getTag();
}
