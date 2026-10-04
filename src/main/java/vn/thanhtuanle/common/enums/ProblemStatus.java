package vn.thanhtuanle.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

@Getter
public enum ProblemStatus {
    ACTIVE(1),
    INACTIVE(0),
    /** Set only by {@code DELETE /problems/{slug}}: the problem is kept, hidden, and closed to new submissions. */
    DELETED(2);

    @JsonValue
    private final int value;

    ProblemStatus(int value) {
        this.value = value;
    }

    public static ProblemStatus fromValue(int value) {
        for (ProblemStatus status : ProblemStatus.values()) {
            if (status.getValue() == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid ProblemStatus value: " + value);
    }

    /**
     * JSON carries the stored value ({@code 1}, {@code "1"}) or the constant's name ({@code "ACTIVE"}).
     * Without this creator Jackson maps a JSON number to the constant at that <em>ordinal</em>, so the
     * portal's {@code status: 1} was stored as INACTIVE.
     */
    @JsonCreator
    public static ProblemStatus fromJson(JsonNode node) {
        if (node.isIntegralNumber()) {
            return fromValue(node.intValue());
        }
        if (node.isTextual()) {
            String text = node.textValue().trim();
            return text.matches("-?\\d+") ? fromValue(Integer.parseInt(text)) : ProblemStatus.valueOf(text);
        }
        throw new IllegalArgumentException("Invalid ProblemStatus: " + node);
    }
}
