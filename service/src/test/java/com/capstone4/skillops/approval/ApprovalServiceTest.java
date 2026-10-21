package com.capstone4.skillops.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.capstone4.skillops.auth.AuthUser;

class ApprovalServiceTest {

    private final ApprovalRepository repository = mock(ApprovalRepository.class);
    private final AgentClient agent = mock(AgentClient.class);
    private final ApprovalService service = new ApprovalService(repository, agent,
            Clock.fixed(Instant.parse("2026-10-21T01:00:00Z"), ZoneOffset.UTC));
    private final AuthUser operator = new AuthUser("operator", "운영자", List.of("OPERATOR"));

    {
        given(repository.save(any(Approval.class))).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void records_approver_and_sends_code_to_agent() {
        given(agent.approve(eq("p_stub_001"), eq("approved"), anyString()))
                .willReturn(new AgentClient.Reply(true, "approved"));

        Approval approval = service.approve("p_stub_001", 1, true, operator);

        assertThat(approval.getApprovedBy()).isEqualTo("operator");
        assertThat(approval.getCandidateNo()).isEqualTo(1);
        assertThat(approval.isIntentConfirmed()).isTrue();
        assertThat(approval.getApprovalToken()).matches("a_[A-Z2-9]{10}");
        assertThat(approval.getUsedAt()).isNull();
        assertThat(approval.getAgentResult()).isEqualTo("ACCEPTED");
        assertThat(approval.getDecidedAt()).isEqualTo(Instant.parse("2026-10-21T01:00:00Z"));
    }

    @Test
    void reject_has_no_token() {
        given(agent.approve(eq("p_stub_001"), eq("rejected"), org.mockito.ArgumentMatchers.isNull()))
                .willReturn(new AgentClient.Reply(true, "rejected"));

        Approval approval = service.reject("p_stub_001", operator);

        assertThat(approval.getApprovalToken()).isNull();
        assertThat(approval.getCandidateNo()).isNull();
    }

    @Test
    void keeps_the_record_when_agent_is_unreachable() {
        given(agent.approve(any(), any(), any()))
                .willThrow(new AgentException("AGENT_UNAVAILABLE", "SkillOps Agent 에 연결하지 못했습니다.", 502));

        assertThatThrownBy(() -> service.reject("p_stub_001", operator))
                .isInstanceOf(AgentException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
                .save(org.mockito.ArgumentMatchers.argThat(a -> "AGENT_UNAVAILABLE".equals(a.getAgentResult())
                        || "PENDING".equals(a.getAgentResult())));
    }
}
