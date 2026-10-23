package com.capstone4.skillops.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * service/src/main/java/com/capstone4/skillops/snapshot/Frontmatter.java 의 parse 가 SKILL.md 맨 위 '---' 블록을 읽는 규칙을
 * 보호한다. 구분 줄의 정확한 모양, CRLF 줄바꿈, 첫 닫는 줄에서 끝나는 블록, 빈 블록과 맵이 아닌 YAML 을 빈 맵으로 돌려주는
 * 규칙, 그리고 주문 Agent(order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java)와 맞춘 안전
 * 옵션(중복 키, 컬렉션 alias, 안전하지 않은 태그 거절)을 고정한다.
 */
class FrontmatterTest {

    @Test
    void readsKeysInFileOrderAsStrings() {
        Map<String, Object> fm = Frontmatter.parse("---\nname: x\nb: 1\na: [p, q]\n---\nbody\n");

        assertThat(fm.keySet()).containsExactly("name", "b", "a");
        assertThat(fm).containsEntry("name", "x").containsEntry("b", 1).containsEntry("a", List.of("p", "q"));
    }

    @Test
    void nonStringKeysBecomeStrings() {
        assertThat(Frontmatter.parse("---\n1: one\ntrue: yes\n---\n").keySet()).containsExactly("1", "true");
    }

    @Test
    void missingOrNonMapBlockIsEmpty() {
        assertThat(Frontmatter.parse(null)).isEmpty();
        assertThat(Frontmatter.parse("")).isEmpty();
        assertThat(Frontmatter.parse("# 제목\nname: x\n")).isEmpty();
        assertThat(Frontmatter.parse("---\njust text\n---\n")).isEmpty();
        assertThat(Frontmatter.parse("---\n- a\n- b\n---\n")).isEmpty();
        assertThat(Frontmatter.parse("---\nname: x\n")).isEmpty();
    }

    @Test
    void emptyBlockIsEmpty() {
        assertThat(Frontmatter.parse("---\n---\n")).isEmpty();
        assertThat(Frontmatter.parse("---\n\n---\nbody")).isEmpty();
    }

    @Test
    void blockMayEndAtEndOfText() {
        assertThat(Frontmatter.parse("---\nname: x\n---")).containsEntry("name", "x");
    }

    @Test
    void openingDelimiterMustBeExact() {
        assertThat(Frontmatter.parse("--- \nname: x\n---\n")).isEmpty();
        assertThat(Frontmatter.parse("\n---\nname: x\n---\n")).isEmpty();
    }

    @Test
    void closingDelimiterMustEndTheLine() {
        assertThat(Frontmatter.parse("---\nname: x\n--- \nbody\n")).isEmpty();
        assertThat(Frontmatter.parse("---\nname: x\n----\nbody\n")).isEmpty();
    }

    @Test
    void crlfLinesAreAccepted() {
        assertThat(Frontmatter.parse("---\r\nname: x\r\n---\r\nbody")).containsEntry("name", "x");
    }

    @Test
    void firstClosingDelimiterEndsTheBlock() {
        assertThat(Frontmatter.parse("---\nname: x\n---\nother: y\n---\n")).containsOnlyKeys("name");
    }

    @Test
    void duplicateKeyIsRejected() {
        assertThatThrownBy(() -> Frontmatter.parse("---\nname: x\nname: y\n---\n")).isInstanceOf(YAMLException.class);
    }

    @Test
    void collectionAliasIsRejected() {
        assertThatThrownBy(() -> Frontmatter.parse("---\na: &x [1]\nb: *x\n---\n")).isInstanceOf(YAMLException.class);
    }

    /** 컬렉션이 아닌 값의 alias 는 컬렉션 alias 한도(0)에 걸리지 않으므로 그대로 읽힌다. */
    @Test
    void scalarAliasIsAllowed() {
        assertThat(Frontmatter.parse("---\na: &x 1\nb: *x\n---\n")).containsEntry("a", 1).containsEntry("b", 1);
    }

    @Test
    void brokenYamlIsThrown() {
        assertThatThrownBy(() -> Frontmatter.parse("---\nname: x\ninputs: [a\n---\n")).isInstanceOf(YAMLException.class);
    }

    @Test
    void unsafeTagIsRejected() {
        assertThatThrownBy(() -> Frontmatter.parse("---\nx: !!java.io.File [\"/tmp\"]\n---\n"))
                .isInstanceOf(YAMLException.class);
    }
}
