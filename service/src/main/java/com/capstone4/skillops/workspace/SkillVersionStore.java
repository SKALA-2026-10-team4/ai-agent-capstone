package com.capstone4.skillops.workspace;

import java.util.Map;

/**
 * skill 버전 보관함에서 원문을 읽는 쪽 접점. C-1 의 버전 저장소(skill_versions)가 구현한다.
 * C-1 구현은 {@code @Primary} 를 붙여 {@link LiveFolderSkillVersionStore} 를 대신한다.
 */
public interface SkillVersionStore {

    /** 운영 중인 skill 전부와 각각의 운영 버전. */
    Map<String, Integer> liveVersions();

    /** 해당 버전의 SKILL.md 원문 전체. 없으면 IllegalArgumentException. */
    String fullText(String skill, int version);
}
