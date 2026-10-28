package vn.thanhtuanle.problem;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.problem.dto.CreateProblemDto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DTO constraints (the slug's pattern becomes a MinIO key prefix; the limits reach the judge spec) only run when a
 * Bean Validation provider is on the classpath. It used to arrive only through springdoc, which happens to depend on
 * it: the starter is declared in this pom, so dropping springdoc cannot silently switch every constraint off.
 */
class ValidationProviderTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static CreateProblemDto.CreateProblemDtoBuilder valid() {
        return CreateProblemDto.builder().title("A + B").subject("s").timeLimit(1000).memoryLimit(256)
                .hardnessLevel(1).problemSlug("a-plus-b").inputDescription("i").outputDescription("o")
                .sampleInput("1 2").sampleOutput("3");
    }

    private Set<String> violatedFields(CreateProblemDto dto) {
        return validator.validate(dto).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void aValidProblemHasNoViolation() {
        assertThat(violatedFields(valid().build())).isEmpty();
    }

    @Test
    void aSlugWithASlashOrASpaceIsAViolation() {
        assertThat(violatedFields(valid().problemSlug("a/b").build())).containsExactly("problemSlug");
        assertThat(violatedFields(valid().problemSlug("a b").build())).containsExactly("problemSlug");
    }

    @Test
    void aBlankSlugIsAViolation() {
        assertThat(violatedFields(valid().problemSlug("").build())).contains("problemSlug");
    }

    @Test
    void aNonPositiveLimitIsAViolation() {
        assertThat(violatedFields(valid().timeLimit(0).memoryLimit(-1).build()))
                .containsExactlyInAnyOrder("timeLimit", "memoryLimit");
    }

    @Test
    void thePomDeclaresTheValidationStarterItself() throws Exception {
        assertThat(Files.readString(Path.of("pom.xml")))
                .containsPattern("(?s)<dependency>\\s*<groupId>org\\.springframework\\.boot</groupId>\\s*"
                        + "<artifactId>spring-boot-starter-validation</artifactId>");
    }
}
