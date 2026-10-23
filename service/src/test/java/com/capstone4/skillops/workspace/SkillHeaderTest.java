package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java 의 parse 를 저장소의 실제 SKILL.md
 * (skills/payment, skills/order-flow)와 YAML 표현·타입 회귀 사례로 보호한다.
 * 테스트는 service 폴더에서 돌기 때문에 ../skills 를 읽는다.
 */
class SkillHeaderTest {

    @Test
    void readsRealPaymentSkill() throws Exception {
        SkillHeader h = SkillHeader.parse(Files.readString(Path.of("../skills/payment/SKILL.md")));
        assertThat(h.name()).isEqualTo("payment");
        assertThat(h.version()).isEqualTo(1);
        assertThat(h.inputs()).contains("inventory.reserved");
        assertThat(h.outputs()).containsExactly("payment.id", "payment.status");
    }

    @Test
    void readsOrderFlowSteps() throws Exception {
        SkillHeader h = SkillHeader.parse(Files.readString(Path.of("../skills/order-flow/SKILL.md")));
        assertThat(h.steps()).containsExactly("order-intake", "inventory-check", "payment", "shipping-reserve", "notify");
        assertThat(h.inputs()).isEmpty();
    }

    @Test
    void rejectsTextWithoutHeader() {
        assertThatThrownBy(() -> SkillHeader.parse("# 제목만 있음"))
                .hasMessage("SKILL.md 맨 위 칸(--- 사이)을 찾을 수 없습니다");
    }

    /** 여러 줄과 따옴표를 사용한 목록도 원래 키와 순서를 유지한다. */
    @Test
    void readsBlockAndQuotedLists() {
        SkillHeader header = SkillHeader.parse("""
                ---
                name: "payment"
                version: 1
                inputs:
                  - 'inventory.reserved'
                outputs: ["payment.id", 'payment.status'] # 결제 결과
                steps:
                  - inventory-check
                  - payment
                ---
                body
                """);

        assertThat(header.name()).isEqualTo("payment");
        assertThat(header.inputs()).containsExactly("inventory.reserved");
        assertThat(header.outputs()).containsExactly("payment.id", "payment.status");
        assertThat(header.steps()).containsExactly("inventory-check", "payment");
    }

    /** 누락한 목록과 명시적인 빈 목록은 선택 필드의 빈 값으로 읽는다. */
    @Test
    void omittedAndEmptyListsAreSupported() {
        SkillHeader header = SkillHeader.parse(document("outputs: []\n"));

        assertThat(header.inputs()).isEmpty();
        assertThat(header.outputs()).isEmpty();
        assertThat(header.steps()).isEmpty();
    }

    /** scalar, null, 숫자 항목과 빈 문자열을 정상 빈 목록으로 취급하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"inventory.reserved", "null", "[1]", "['']", "['   ']"})
    void rejectsInvalidListValues(String value) {
        for (String field : List.of("inputs", "outputs", "steps")) {
            assertThatThrownBy(() -> SkillHeader.parse(document(field + ": " + value + "\n")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(field);
        }
    }

    /** 버전은 문자열이나 소수가 아닌 양의 Integer여야 한다. */
    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "0", "-1", "1.5", "null", "true", "2147483648"})
    void rejectsInvalidVersions(String value) {
        assertThatThrownBy(() -> SkillHeader.parse("---\nname: payment\nversion: " + value + "\n---\nbody\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    /** 버전이 없으면 기존 저장소의 필수 버전 검사가 처리할 수 있게 null을 유지한다. */
    @Test
    void missingVersionRemainsAbsent() {
        assertThat(SkillHeader.parse("---\nname: payment\n---\nbody\n").version()).isNull();
    }

    /** YAML 파싱 실패도 MCP가 INVALID_INPUT으로 분류할 수 있는 입력 예외로 바꾼다. */
    @ParameterizedTest
    @ValueSource(strings = {"outputs: [broken\n", "version: 1\nversion: 2\n"})
    void yamlFailuresAreInputErrors(String fields) {
        assertThatThrownBy(() -> SkillHeader.parse("---\nname: payment\n" + fields + "---\nbody\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YAML");
    }

    /** 목록 회귀 사례에 필요한 정상 이름과 버전을 가진 원문을 만든다. */
    private static String document(String fields) {
        return "---\nname: payment\nversion: 1\n" + fields + "---\nbody\n";
    }
}
