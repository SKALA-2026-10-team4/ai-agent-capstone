package com.capstone4.skillops.proposal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 저장된 수정 제안서 한 줄. B 가 save_proposal 로 보낸 제안서(payload)와, 저장할 때 만든 본문(document)을 함께 둔다.
 * 운영자가 본 본문이 나중에 바뀌지 않도록 본문도 그대로 보관한다.
 * 칸을 더할 때 이미 있는 표에 ddl-auto: update 가 실패하지 않도록, 처음부터 있던 칸 말고는 DB 에서 비워 둘 수 있게 했다.
 */
@Entity
@Table(name = "proposals")
public class Proposal {

    @Id
    @Column(name = "proposal_id", length = 64)
    private String proposalId;

    /** 이 제안서를 만든 진단(B 의 thread_id). 같은 진단으로 다시 저장하면 이 제안서를 돌려준다 */
    @Column(name = "thread_id", unique = true, length = 64)
    private String threadId;

    /** proposed | no_valid_fix | approved | rejected */
    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    /** {@link ProposalDocument} 를 snake_case 묶음으로 바꾼 것 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> document;

    /** service 가 다시 계산한 합격 후보 번호 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "valid_candidate_nos")
    private List<Integer> validCandidateNos;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** 두 운영자가 같은 제안서를 동시에 결정하지 못하게 막는다 */
    @Version
    private Long version;

    protected Proposal() {
    }

    public static Proposal create(String proposalId, String threadId, String status, Map<String, Object> payload,
            Map<String, Object> document, List<Integer> validCandidateNos, Instant createdAt) {
        Proposal p = new Proposal();
        p.proposalId = proposalId;
        p.threadId = threadId;
        p.status = status;
        p.payload = payload;
        p.document = document;
        p.validCandidateNos = validCandidateNos;
        p.createdAt = createdAt;
        return p;
    }

    /**
     * 승인·거절을 표시한다. 승인이면 담당자가 의도 해석을 확인한 것이므로 payload 의 intent.confirmed 를 true 로 둔다(계약 5 §10).
     * 받을 수 있는 결정인지는 {@link ProposalService#markDecided} 가 먼저 확인한다.
     */
    void decide(String decision, Instant at) {
        this.status = decision;
        this.decidedAt = at;
        if (ProposalService.APPROVED.equals(decision)) {
            Map<String, Object> copy = new LinkedHashMap<>(payload);
            Map<String, Object> intent = ProposalRules.map(copy.get("intent"));
            if (intent != null) {
                Map<String, Object> confirmed = new LinkedHashMap<>(intent);
                confirmed.put("confirmed", true);
                copy.put("intent", confirmed);
            }
            this.payload = copy;
        }
    }

    public String getProposalId() {
        return proposalId;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Map<String, Object> getDocument() {
        return document == null ? Map.of() : document;
    }

    public List<Integer> getValidCandidateNos() {
        return validCandidateNos == null ? List.of() : validCandidateNos;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
