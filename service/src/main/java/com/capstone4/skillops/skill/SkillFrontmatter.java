package com.capstone4.skillops.skill;

import java.util.Map;

import com.capstone4.skillparser.SkillParser;

/** service의 기존 호출 계약을 보존하며 서버 공용 skill-parser에 위임하는 어댑터다. */
public final class SkillFrontmatter {

    /** 기존 필수 정보 검증기에 전달할 필드와 본문이다. */
    public record ExecutionDocument(Map<String, Object> fields, String body) {
    }

    /** 정적 어댑터만 제공한다. */
    private SkillFrontmatter() {
    }

    /** 진단·diff의 경계와 빈 맵 정책을 사용해 공용 YAML 파서를 호출한다. */
    public static Map<String, Object> parse(String text) {
        return SkillParser.parseForAnalysis(text);
    }

    /** workspace의 필수 객체·입력 오류 정책을 보존한다. */
    public static Map<String, Object> requireFields(String text) {
        return SkillParser.requireFields(text);
    }

    /** 실제 주문 로더와 같은 실행용 파싱 결과를 기존 service 타입으로 감싼다. */
    public static ExecutionDocument readForExecution(String text) {
        var document = SkillParser.readForExecution(text);
        return new ExecutionDocument(document.fields(), document.body());
    }

    /** 원문 hunk 위치를 공용 경계 해석으로 계산한다. */
    public static int closingLine(String text) {
        return SkillParser.closingLine(text);
    }
}
