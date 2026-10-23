package com.capstone4.skillparser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.error.YAMLException;

/** 공용 모듈의 정확한 크기 경계·UTF-8·프로필 계약을 고정한다. */
class SkillParserTest {

    /** 실행과 분석의 공통 상한을 ASCII와 보조 평면 Unicode에서 모두 확인한다. */
    @ParameterizedTest
    @MethodSource("characters")
    void exactCodePointLimitIsAcceptedAndNextPointIsRejected(String character) {
        String prefix = "padding: ";
        String yaml = prefix + character.repeat(SkillParser.MAX_YAML_CODE_POINTS - prefix.length());
        String text = "---\n" + yaml + "\n---\nbody\n";
        assertEquals(SkillParser.MAX_YAML_CODE_POINTS, yaml.codePointCount(0, yaml.length()));
        assertTrue(SkillParser.parseForAnalysis(text).containsKey("padding"));
        assertTrue(SkillParser.readForExecution(text).fields().containsKey("padding"));
        String oversized = text.replace("\n---\nbody", character + "\n---\nbody");
        assertThrows(YAMLException.class, () -> SkillParser.parseForAnalysis(oversized));
        assertThrows(YAMLException.class, () -> SkillParser.readForExecution(oversized));
    }

    /** 코드 포인트와 UTF-16 길이·UTF-8 바이트 수가 다른 값을 제공한다. */
    static Stream<String> characters() {
        return Stream.of("x", "가", "😀");
    }

    /** 줄바꿈 정규화가 한도 근처에서 실행과 분석의 수락 여부를 바꾸지 않는다. */
    @Test
    void crlfUsesTheSameYamlBudget() {
        String prefix = "name: payment\npadding: ";
        String yaml = prefix + "x".repeat(SkillParser.MAX_YAML_CODE_POINTS - prefix.length());
        String text = ("---\n" + yaml + "\n---\nbody\n").replace("\n", "\r\n");
        assertEquals(SkillParser.parseForAnalysis(text), SkillParser.readForExecution(text).fields());
    }

    /** 본문을 YAML 예산에 포함하거나 원문 전체를 100,000자로 잘라 읽지 않는다. */
    @Test
    void bodyIsNotPartOfYamlLimit() {
        String body = "x".repeat(100_001) + "\n";
        String text = "---\nname: payment\n---\n" + body;
        assertEquals(body, SkillParser.readForExecution(text).body());
        assertEquals("payment", SkillParser.parseForAnalysis(text).get("name"));
    }

    /** EOF 경계는 분석에서만 허용하고 실제 실행의 기존 줄바꿈 요구는 유지한다. */
    @Test
    void purposeSpecificBoundaryRulesArePreserved() {
        String text = "---\nname: payment\n---";
        assertEquals("payment", SkillParser.parseForAnalysis(text).get("name"));
        assertThrows(IllegalArgumentException.class, () -> SkillParser.readForExecution(text));
        assertEquals(3, SkillParser.closingLine(text));
    }

    /** UTF-8 오류를 대체 문자로 정규화하지 않는다. */
    @Test
    void invalidUtf8IsRejected() {
        assertThrows(CharacterCodingException.class,
                () -> SkillParser.readForExecution(new byte[] {(byte) 0xc3, 0x28}));
    }

    /** 안전 옵션은 실제 파서에서 중첩 한도와 임의 객체 생성을 제한한다. */
    @Test
    void rejectsDeepNestingAndUnsafeTags() {
        assertThrows(YAMLException.class, () -> SkillParser.parseForAnalysis(
                "---\nx: " + "[".repeat(60) + "1" + "]".repeat(60) + "\n---\n"));
        assertThrows(YAMLException.class, () -> SkillParser.parseForAnalysis(
                "---\nx: !!java.io.File [/tmp]\n---\n"));
    }

    /** 바이트 입력을 LF로 해석해도 원본 bytes는 그대로 남긴다. */
    @Test
    void byteInputIsNotMutated() throws Exception {
        byte[] bytes = "---\r\nname: payment\r\n---\r\nbody\r\n".getBytes(StandardCharsets.UTF_8);
        String original = new String(bytes, StandardCharsets.UTF_8);
        assertEquals("body\n", SkillParser.readForExecution(bytes).body());
        assertEquals(original, new String(bytes, StandardCharsets.UTF_8));
    }
}
