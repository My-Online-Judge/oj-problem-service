package vn.thanhtuanle.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "t_problems")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class Problem extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String subject;

    private int timeLimit; // in milliseconds

    private Long memoryLimit; // in megabytes

    private int hardnessLevel; // e.g., 1 (easy), 2 (medium), 3 (hard)

    private String problemSlug; // e.g., a-plus-b, thap-ha-noi

    private String inputDescription;

    private String outputDescription;

    private String sampleInput;

    private String sampleOutput;

    private String hint;

    private int status;

    /** The highest test-case number ever used; a new case is numbered above it, so a deleted number is never reused. */
    @Column(name = "test_case_high_water", nullable = false)
    private int testCaseHighWater;

    /** Judged by a C++ checker (special judge), stored at {@code <slug>/spj.cpp}, instead of by comparing outputs. */
    @Column(nullable = false)
    private boolean spj;

    /** The sha256 hex of the checker's source; null without a checker. */
    @Column(length = 64)
    private String spjVersion;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "t_problem_tags", joinColumns = @JoinColumn(name = "problem_id"))
    @Column(name = "tag")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @OneToMany(mappedBy = "problem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TestCase> testCases = new ArrayList<>();
}
