package com.capstone4.skillops.proposal;

import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Agent 가 저장한 수정안(proposals 테이블). id 는 p_ + UUID(32자리 16진수).
 * ApprovalController 가 /api/proposals/{id}/approve·reject 에서 이 id 를 받는다.
 */
@Entity
@Table(name = "proposals")
public class Proposal {

    @Id
    @Column(name = "proposal_id")
    private String proposalId;

    /** proposed | no_valid_fix */
    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(nullable = false)
    private Instant createdAt;

    protected Proposal() {
    }

    public static Proposal create(String proposalId, String status, Map<String, Object> payload, Instant createdAt) {
        Proposal p = new Proposal();
        p.proposalId = proposalId;
        p.status = status;
        p.payload = payload;
        p.createdAt = createdAt;
        return p;
    }

    public String getProposalId() {
        return proposalId;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
