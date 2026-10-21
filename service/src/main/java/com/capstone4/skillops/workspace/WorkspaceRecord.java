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

    protected WorkspaceRecord() {
    }

    public static WorkspaceRecord open(String workspaceId, Instant createdAt, Map<String, WorkspaceSkill> skills) {
        WorkspaceRecord r = new WorkspaceRecord();
        r.workspaceId = workspaceId;
        r.createdAt = createdAt;
        r.status = Status.OPEN;
        r.skills = skills;
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
}
