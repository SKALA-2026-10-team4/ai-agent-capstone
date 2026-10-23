package com.capstone4.skillops.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * workspaces 표를 읽고 쓰는 Spring Data JPA 저장소다. 기본 키는 workspace_id 글자다.
 * Spring Data 가 구현을 만들어 service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 에 넣고,
 * WorkspaceService 는 findById 와 save 만 쓴다.
 * 따로 정의한 질의 메서드는 없다.
 */
public interface WorkspaceRepository extends JpaRepository<WorkspaceRecord, String> {
}
