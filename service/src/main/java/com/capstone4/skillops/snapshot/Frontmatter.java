package com.capstone4.skillops.snapshot;

import java.util.Map;

import com.capstone4.skillops.skill.SkillFrontmatter;

/**
 * C-1 diff와 graph가 공통 SkillFrontmatter 파서를 사용하는 어댑터다.
 * 기존 구분선·빈 맵 반환·YAML 예외·hunk 위치 규칙을 보존한다.
 * YAML 이 깨지면 YAMLException 을 그대로 던지고, 호출처가 그 예외를 parse_error, frontmatter_parse_error 칸이나 INVALID_FLOW
 * 오류로 바꾼다.
 * service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java 가 frontmatter 키 변화와 덩어리 위치(part)를 계산할 때,
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 가 steps 와 단계 선언을 읽을 때 부른다.
 * C-2 SkillHeader도 같은 YAML 해석 기능을 사용하되 필드 타입과 입력 오류를 별도로 검사한다.
 */
final class Frontmatter {

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private Frontmatter() {
    }

    /**
     * text 의 frontmatter 를 읽어 키를 문자열로 바꾼 맵으로 돌려준다. 키 순서는 파일에 적힌 순서다.
     * text 가 null 이거나 frontmatter 가 없거나 YAML 이 맵이 아니면 빈 맵을 돌려준다. YAML 이 깨졌거나 중복 키, 컬렉션 alias,
     * 안전하지 않은 태그가 있으면 YAMLException 을 던진다.
     */
    static Map<String, Object> parse(String text) {
        return SkillFrontmatter.parse(text);
    }

    /**
     * frontmatter 를 닫는 '---' 줄의 번호(1부터)를 돌려준다. 첫 줄이 '---' 가 아니거나 닫는 줄이 없으면 0 이다.
     * YAML 내용은 보지 않고 둘째 줄부터 닫는 줄을 찾으므로 '---' 두 줄만 있는 빈 블록에도 2 를 돌려준다.
     * SkillDiffer 가 덩어리의 part 를 frontmatter 와 body 로 가르는 기준으로 쓴다.
     */
    static int closingLine(String text) {
        return SkillFrontmatter.closingLine(text);
    }
}
