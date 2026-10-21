package com.capstone4.skillops.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.capstone4.skillops.scenario.RunLedger;
import com.capstone4.skillops.scenario.TestFixtures.InMemoryRunLedger;

/** 생성자가 둘이라 Spring 이 어느 쪽을 쓸지 알아야 한다(테스트용 생성자는 Clock 을 더 받는다). */
class ProposalServiceWiringTest {

    @Test
    void springCreatesTheServiceWithItsPublicConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProposalRepository.class, () -> mock(ProposalRepository.class));
            context.registerBean(RunLedger.class, InMemoryRunLedger::new);
            context.register(ProposalService.class);
            context.refresh();

            assertThat(context.getBean(ProposalService.class)).isNotNull();
        }
    }
}
