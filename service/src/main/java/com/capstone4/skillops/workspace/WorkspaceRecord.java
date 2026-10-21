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
 * 연습 공간 한 줄. skill 원문은 skill 이름 → {@link WorkspaceSkill} 묶음으로 JSON 칸 하나에 둔다
 * (C-1 의 trace_run 이 단계 기록을 묶어 두는 방식과 같다).
 * 주문 Agent snapshot 에서 시작했다면 SKILL.md 가 아닌 파일(skills/&lt;이름&gt;/resources/… 등)은 경로 → 글로 resources 에 둔다.
 */
@Entity
@Table(name = "workspaces")
public class WorkspaceRecord {

    /** OPEN 사용 중 · PROMOTED 운영에 반영됨 · DISCARDED 버림 */
    public enum Status { OPEN, PROMOTED, DISCARDED }

    @Id
    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, WorkspaceSkill> skills;

    /**
     * SKILL.md 가 아닌 skills/ 아래 파일. 파일 경로 → 글. 주문 Agent snapshot 에서 시작했을 때만 채워진다.
     * 이 칸이 생기기 전에 만든 행은 null 이다(ddl-auto: update 가 비어 있는 칸으로 더한다).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, String> resources;

    /** 시작한 주문 Agent snapshot 번호. 운영 폴더에서 시작했거나 이 칸이 생기기 전 행이면 null */
    @Column(name = "source_snapshot_id")
    private String sourceSnapshotId;

    protected WorkspaceRecord() {
    }

    public static WorkspaceRecord open(String workspaceId, Instant createdAt, Map<String, WorkspaceSkill> skills) {
        return open(workspaceId, createdAt, skills, Map.of(), null);
    }

    /**
     * @param resources        SKILL.md 가 아닌 파일의 경로 → 글. null 이면 없는 것으로 본다
     * @param sourceSnapshotId 시작한 주문 Agent snapshot 번호. 운영 폴더에서 시작했으면 null
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

    /** SKILL.md 가 아닌 파일의 경로 → 글. 값이 없던 행도 빈 맵으로 돌려준다. */
    public Map<String, String> getResources() {
        return resources == null ? Map.of() : resources;
    }

    /** 수정을 넣은 뒤의 skill 원문 묶음으로 바꾼다. 저장은 부르는 쪽이 repository.save 로 한다. */
    public void replaceSkills(Map<String, WorkspaceSkill> skills) {
        this.skills = skills;
    }

    public String getSourceSnapshotId() {
        return sourceSnapshotId;
    }
}
