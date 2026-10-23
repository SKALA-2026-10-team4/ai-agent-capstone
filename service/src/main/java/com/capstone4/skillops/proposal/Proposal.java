package com.capstone4.skillops.proposal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * proposals 표의 한 줄, 곧 저장된 수정 제안서 하나를 나타내는 엔티티다.
 * SkillOps Agent 가 save_proposal 로 보낸 제안서 원본(payload)과, 저장할 때
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 가 만든 본문(document)을 함께 둔다.
 * 문장 틀을 나중에 고쳐도 운영자가 이미 본 본문은 바뀌지 않도록 본문을 저장할 때 한 번 만들어 보관한다.
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 가 create 로 만들어 저장하고 decide 로 결정을 표시한다.
 * service/src/main/java/com/capstone4/skillops/api/ProposalController.java 가 getter 로 목록과 상세 응답을 만든다.
 * getter 는 칸 값을 그대로 돌려주되, DB 에서 비어 있을 수 있는 document 와 validCandidateNos 는 null 대신 빈 값을 돌려준다.
 * 이미 있는 표에 칸을 더할 때 ddl-auto: update 가 실패하지 않도록, 처음부터 있던 칸이 아니면 DB 에서 비워 둘 수 있게 했다.
 */
@Entity
@Table(name = "proposals")
public class Proposal {

    /**
     * 제안서 번호를 경로 변수로 받는 API 가 @Pattern 으로 검사하는 모양이다. p_ 뒤에 1자부터 62자까지 붙으므로 proposal_id 칸 길이
     * 64 안에 들어간다.
     * service/src/main/java/com/capstone4/skillops/api/ApprovalController.java 와 ProposalController 가 쓰며, newId 가 만드는 번호도 이 모양에 맞는다.
     */
    public static final String ID_PATTERN = "p_[A-Za-z0-9_]{1,62}";

    @Id
    @Column(name = "proposal_id", length = 64)
    private String proposalId;

    /**
     * 이 제안서를 만든 진단의 thread_id 다. skillops-agent/skillops_agent/nodes.py 의 proposal_payload 가 pipeline_thread_id 를 넣어 보낸다.
     * 같은 진단으로 다시 저장하면 ProposalService 의 save 가 이 칸으로 처음 제안서를 찾아 돌려주고, unique 제약이 진단 하나에 제안서 하나를 지킨다.
     */
    @Column(name = "thread_id", unique = true, length = 64)
    private String threadId;

    /**
     * 제안서 상태다. ProposalStatus 의 네 값(proposed, no_valid_fix, approved, rejected) 가운데 하나다.
     * 저장할 때는 앞의 둘 가운데 하나이고, 운영자가 결정하면 뒤의 둘 가운데 하나로 바뀐다.
     */
    @Column(nullable = false, length = 16)
    private String status;

    /** Agent 가 보낸 제안서 원본 JSON 이다. 승인할 때 intent.confirmed 를 더한 사본으로 바꾸는 것 말고는 고치지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    /** ProposalDocument 를 snake_case 키의 Map 으로 바꿔 저장한 본문이다. 화면은 이 모양을 그대로 그린다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, Object> document;

    /** service 가 시험 결과로 다시 계산한 합격 후보 번호 목록이다. 승인할 수 있는 후보는 이 목록에 있는 번호뿐이다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "valid_candidate_nos")
    private List<Integer> validCandidateNos;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /**
     * JPA 낙관적 잠금 버전이다. 두 운영자가 같은 제안서를 동시에 결정하지 못하게 막는다.
     * 늦게 커밋한 쪽은 ObjectOptimisticLockingFailureException 으로 실패하고,
     * service/src/main/java/com/capstone4/skillops/api/ProposalApiExceptionHandler.java 가 이를 409 응답으로 바꾼다.
     */
    @Version
    private Long version;

    /** JPA 가 표에서 읽은 줄로 객체를 만들 때 쓰는 생성자다. */
    protected Proposal() {
    }

    /** 새 제안서 한 줄을 만든다. 결정 시각과 버전은 비워 둔다. ProposalService 의 store 가 부르고, 테스트도 저장된 제안서를 흉내 낼 때 부른다. */
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

    /** 새 제안서 번호를 만든다. p_ 뒤에 대시를 뺀 UUID 32자를 붙인다. ProposalService 의 store 가 부른다. */
    static String newId() {
        return "p_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 운영자의 결정을 이 제안서에 표시한다. 상태를 결정 값으로 바꾸고 결정 시각을 남긴다.
     * 승인이면 담당자가 의도 해석을 확인한 것이므로 payload 를 intent.confirmed 가 true 인 사본으로 바꾸고(계약 5 §10), 거절이면 payload 를 그대로 둔다.
     * 받을 수 있는 결정인지는 ProposalService 의 markDecided 가 먼저 확인하므로 여기서는 다시 보지 않는다.
     */
    void decide(String decision, Instant at) {
        this.status = decision;
        this.decidedAt = at;
        if (ProposalStatus.APPROVED.equals(decision)) {
            this.payload = withIntentConfirmed(payload);
        }
    }

    /**
     * intent.confirmed 를 true 로 둔 payload 사본을 만든다. 원본 payload 와 원본 intent Map 은 고치지 않고 키 순서도 지킨다.
     * intent 가 객체가 아니면 intent 를 그대로 둔 사본을 돌려준다.
     */
    private static Map<String, Object> withIntentConfirmed(Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        Map<String, Object> intent = ProposalRules.map(copy.get("intent"));
        if (intent != null) {
            Map<String, Object> confirmed = new LinkedHashMap<>(intent);
            confirmed.put("confirmed", true);
            copy.put("intent", confirmed);
        }
        return copy;
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
