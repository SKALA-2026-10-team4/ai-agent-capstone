package com.capstone4.skillops.workspace;

import java.util.Map;

/**
 * snapshot_id 없이 연습 공간을 만들 때 skill 버전 보관함에서 원문을 읽는 접점이다. start_versions 는 있어도 되고 없어도 된다.
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 createFromLive 가 liveVersions 로
 * 운영 버전을 얻고 fullText 로 원문을 읽는다.
 * snapshot_id 로 시작하는 경로는 이 접점을 쓰지 않으며, Agent 의 노드는 지금 snapshot_id 로만 연습 공간을 만든다.
 *
 * 지금 구현은 운영 폴더를 읽는 {@link LiveFolderSkillVersionStore} 하나라서 예전 버전은 읽을 수 없다.
 * C-1 의 버전 저장소(skill_versions)가 이 접점을 구현하고 {@code @Primary} 를 붙이면 Spring 이
 * LiveFolderSkillVersionStore 대신 그 구현을 WorkspaceService 에 넣는다.
 */
public interface SkillVersionStore {

    /** 운영 중인 skill 전부와 각각의 운영 버전을 skill 이름별로 돌려준다. */
    Map<String, Integer> liveVersions();

    /** 그 skill 의 해당 버전 SKILL.md 원문 전체를 돌려준다. 없는 skill 이나 버전이면 IllegalArgumentException 을 던진다. */
    String fullText(String skill, int version);
}
