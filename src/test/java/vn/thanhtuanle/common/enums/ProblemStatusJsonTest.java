package vn.thanhtuanle.common.enums;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.problem.dto.UpdateProblemDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The portal sends {@code status} as a number. ACTIVE is declared first (ordinal 0) but stored as 1,
 * so reading numbers by ordinal swapped ACTIVE and INACTIVE.
 */
class ProblemStatusJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ProblemStatus read(String statusJson) throws Exception {
        return mapper.readValue("{\"title\":\"t\",\"status\":" + statusJson + "}", UpdateProblemDto.class)
                .getStatus();
    }

    @Test
    void aNumberIsReadAsTheStoredValueNotTheOrdinal() throws Exception {
        assertThat(read("1")).isEqualTo(ProblemStatus.ACTIVE);
        assertThat(read("0")).isEqualTo(ProblemStatus.INACTIVE);
        assertThat(read("2")).isEqualTo(ProblemStatus.DELETED);
    }

    @Test
    void aNumericStringIsReadAsTheStoredValue() throws Exception {
        assertThat(read("\"1\"")).isEqualTo(ProblemStatus.ACTIVE);
    }

    @Test
    void theConstantNameIsStillAccepted() throws Exception {
        assertThat(read("\"ACTIVE\"")).isEqualTo(ProblemStatus.ACTIVE);
        assertThat(read("\"INACTIVE\"")).isEqualTo(ProblemStatus.INACTIVE);
    }

    @Test
    void anUnknownValueIsRejected() {
        assertThatThrownBy(() -> read("7")).isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(() -> read("\"OPEN\"")).isInstanceOf(JsonMappingException.class);
    }

    @Test
    void aStatusIsWrittenAsItsStoredValue() throws Exception {
        assertThat(mapper.writeValueAsString(ProblemStatus.ACTIVE)).isEqualTo("1");
    }
}
