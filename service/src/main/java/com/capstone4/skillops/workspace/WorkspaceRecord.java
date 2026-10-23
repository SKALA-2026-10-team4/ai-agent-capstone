package com.capstone4.skillops.workspace;

import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * workspaces 표의 한 줄, 곧 연습 공간 하나를 나타내는 JPA 엔티티다.
 * skill 원문은 skill 이름별 {@link WorkspaceSkill} 묶음으로 JSON 칸(skills) 하나에 둔다. C-1 의 trace_run 이 단계 기록을
 * 묶어 두는 방식과 같다. 주문 Agent snapshot 에서 시작했다면 SKILL.md 가 아닌 파일(skills/&lt;이름&gt;/resources/ 아래
 * 파일 등)은 경로별 글로 resources 칸에 둔다.
 *
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 가 open 으로 만들어 저장하고, apply 에서
 * replaceSkills 로 원문을 바꾼다. 표와 칸은 ddl-auto: update(service/src/main/resources/application.yml)가 이 클래스를
 * 보고 만든다. getter 는 칸 값을 그대로 돌려주고, getResources 만 null 을 빈 맵으로 바꾼다.
 */
@Entity
@Table(name = "workspaces")
public class WorkspaceRecord {

    /**
     * 연습 공간의 상태다. OPEN 은 사용 중인 상태, PROMOTED 는 운영에 반영된 상태, DISCARDED 는 버린 상태다.
     * 수정은 OPEN 일 때만 받는다(WorkspaceService.requireOpen). 지금 코드에는 OPEN 밖으로 바꾸는 곳이 없다.
     */
    public enum Status { OPEN, PROMOTED, DISCARDED }

    /** w_ 와 영숫자 8자로 된 연습 공간 번호이고 기본 키다. */
    @Id
    @Column(name = "workspace_id")
    private String workspaceId;

    /** 만든 시각이다. WorkspaceService.saveNew 가 마이크로초로 잘라 넣는다. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 상태를 이름 글자로 저장한다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** skill 이름별 원문 묶음이다. JSON 칸에 camelCase 키(baseVersion, fullText, revision)로 저장된다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, WorkspaceSkill> skills;

    /**
     * SKILL.md 가 아닌 skills/ 아래 파일의 경로별 글이다. 주문 Agent snapshot 에서 시작했을 때만 채워진다.
     * 이 칸이 생기기 전에 만든 행은 null 이다. ddl-auto: update 가 이 칸을 비어 있는 칸으로 더했기 때문이다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, String> resources;

    /** 시작한 주문 Agent snapshot 번호다. 운영 폴더에서 시작했거나 이 칸이 생기기 전에 만든 행이면 null 이다. */
    @Column(name = "source_snapshot_id")
    private String sourceSnapshotId;

    /** JPA 가 행을 읽어 객체를 만들 때 쓰는 생성자다. 코드에서는 open 으로 만든다. */
    protected WorkspaceRecord() {
    }

    /** resources 와 출처 snapshot 이 없는, 운영 폴더에서 시작한 연습 공간을 만든다. 테스트에서만 부른다. */
    public static WorkspaceRecord open(String workspaceId, Instant createdAt, Map<String, WorkspaceSkill> skills) {
        return open(workspaceId, createdAt, skills, Map.of(), null);
    }

    /**
     * 새 연습 공간을 OPEN 상태로 만든다. 저장은 부르는 쪽이 한다. WorkspaceService.saveNew 가 부른다.
     *
     * @param resources        SKILL.md 가 아닌 파일의 경로별 글이다. null 이면 없는 것으로 본다
     * @param sourceSnapshotId 시작한 주문 Agent snapshot 번호다. 운영 폴더에서 시작했으면 null 이다
     */
    public static WorkspaceRecord open(String workspaceId, Instant createdAt, Map<String, WorkspaceSkill> skills,
            Map<String, String> resources, String sourceSnapshotId) {
        WorkspaceRecord r = new WorkspaceRecord();
        r.workspaceId = workspaceId;
        r.createdAt = createdAt;
        r.status = Status.OPEN;
        r.skills = skills;
        r.resources = resources;
        r.sourceSnapshotId = sourceSnapshotId;
        return r;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Status getStatus() {
        return status;
    }

    public Map<String, WorkspaceSkill> getSkills() {
        return skills;
    }

    /**
     * SKILL.md 가 아닌 파일의 경로별 글을 돌려준다. 칸이 null 인 옛 행도 빈 맵으로 돌려주므로 부르는 쪽
     * (WorkspaceService.filesOf)은 null 을 따로 다루지 않는다.
     */
    public Map<String, String> getResources() {
        return resources == null ? Map.of() : resources;
    }

    /** 수정을 넣은 뒤의 skill 원문 묶음으로 바꾼다. 저장은 부르는 쪽(WorkspaceService.apply)이 repository.save 로 한다. */
    public void replaceSkills(Map<String, WorkspaceSkill> skills) {
        this.skills = skills;
    }

    public String getSourceSnapshotId() {
        return sourceSnapshotId;
    }
}
