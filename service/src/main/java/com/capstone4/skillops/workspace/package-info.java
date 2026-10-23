/**
 * W2 레인의 연습 공간(workspace) 패키지다. 운영 skill 을 건드리지 않는 연습용 복사본을 만들고(create_workspace),
 * 그 복사본의 SKILL.md 에 unified diff 를 적용한다(apply_patch).
 *
 * 처음 계획은 SKILL.md 버전 저장소와, 버전과 패치로 격리 디렉터리를 꾸며 dry-run 하는 sandbox 였다. 지금은 복사본을
 * 디렉터리가 아니라 workspaces 표의 JSON 칸(WorkspaceRecord)에 두고, 모의 실행과 채점은 com.capstone4.skillops.scenario
 * 패키지가 이 복사본을 읽어서 한다.
 *
 * 입구는 WorkspaceService 이고 MCP 도구로는 service/src/main/java/com/capstone4/skillops/mcp/WorkspaceTools.java 가
 * 내놓는다. PatchApplier 는 diff 적용을, SkillHeader 는 SKILL.md 맨 위 칸 읽기를, SkillVersionStore 와
 * LiveFolderSkillVersionStore 는 운영 원문 읽기를 맡는다. WorkspaceRecord 와 WorkspaceSkill 은 저장하는 모양을,
 * WorkspaceRepository 는 workspaces 표 읽기와 쓰기를 맡는다.
 */
package com.capstone4.skillops.workspace;
