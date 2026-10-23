package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.util.Map;

import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.junit.jupiter.api.Test;

/**
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceSkill.java 가 workspaces.skills 칸
 * ({@code @JdbcTypeCode(SqlTypes.JSON)})을 읽고 쓰는 JSON 변환기로 제대로 오가는지 보호한다. revision 칸이 생기기 전에 저장한
 * 행이 revision 0 으로 읽히는지, 새로 쓴 revision 이 다시 읽히는지 본다.
 * Hibernate 7.4 는 클래스 경로에 Jackson 2(com.fasterxml.jackson.databind, openai-java 가 끌고 온다)가 있으면
 * JacksonJsonFormatMapper(기본 설정의 ObjectMapper)를 쓰고, 없으면 Jackson 3 용 Jackson3JsonFormatMapper(Jackson 2 기본값으로
 * 만든 JsonMapper)를 쓴다. Spring Boot 는 따로 지정하지 않는다. 어느 쪽이든 옛 행이 읽혀야 해서 둘 다 확인한다.
 */
class WorkspaceSkillJsonTest {

    /** revision 칸이 생기기 전에 저장된 행의 skills 칸 글이다. */
    static final String LEGACY_JSON = "{\"inventory-check\":{\"baseVersion\":1,\"fullText\":\"x\"}}";

    /** Hibernate 가 칸을 읽을 때와 같은 타입을 쓰려고 WorkspaceRecord.skills 필드의 제네릭 타입을 그대로 꺼내 둔다. */
    private static final Type SKILLS_TYPE = skillsColumnType();

    private final JacksonJsonFormatMapper jackson2 = new JacksonJsonFormatMapper();
    private final Jackson3JsonFormatMapper jackson3 = new Jackson3JsonFormatMapper();

    @Test
    void legacyRowWithoutRevisionLoadsAsRevisionZeroWithJackson2() {
        Map<String, WorkspaceSkill> skills = jackson2.fromString(LEGACY_JSON, SKILLS_TYPE);

        assertThat(skills).containsOnlyKeys("inventory-check");
        assertThat(skills.get("inventory-check")).isEqualTo(new WorkspaceSkill(1, "x", 0));
    }

    @Test
    void legacyRowWithoutRevisionLoadsAsRevisionZeroWithJackson3() {
        Map<String, WorkspaceSkill> skills = jackson3.fromString(LEGACY_JSON, SKILLS_TYPE);

        assertThat(skills.get("inventory-check")).isEqualTo(new WorkspaceSkill(1, "x", 0));
    }

    @Test
    void revisionSurvivesWriteAndRead() {
        Map<String, WorkspaceSkill> skills = Map.of("payment", new WorkspaceSkill(1, "text", 2));

        String json = jackson2.toString(skills, SKILLS_TYPE);

        assertThat(json).contains("\"baseVersion\":1").contains("\"fullText\":\"text\"").contains("\"revision\":2");
        assertThat(jackson2.<Map<String, WorkspaceSkill>>fromString(json, SKILLS_TYPE)).isEqualTo(skills);
        assertThat(jackson3.<Map<String, WorkspaceSkill>>fromString(json, SKILLS_TYPE)).isEqualTo(skills);
    }

    @Test
    void twoArgumentConstructorStartsAtRevisionZero() {
        assertThat(new WorkspaceSkill(3, "x").revision()).isZero();
    }

    /** WorkspaceRecord 의 skills 필드 타입을 리플렉션으로 꺼낸다. */
    private static Type skillsColumnType() {
        try {
            return WorkspaceRecord.class.getDeclaredField("skills").getGenericType();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
