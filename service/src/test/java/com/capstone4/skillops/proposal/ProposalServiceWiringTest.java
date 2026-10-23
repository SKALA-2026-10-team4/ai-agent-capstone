package com.capstone4.skillops.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.capstone4.skillops.scenario.RunLedger;
import com.capstone4.skillops.scenario.TestFixtures.InMemoryRunLedger;

/**
 * Spring 이 service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 빈을 실제로 만들 수 있는지 보호하는 테스트다.
 * ProposalService 는 생성자가 둘이라서(테스트용 생성자는 Clock 을 더 받는다) 공개 생성자에 @Autowired 가 없으면 Spring 이 어느 쪽을
 * 쓸지 몰라 빈을 만들지 못한다. 커밋 943b585 에서 고친 이 기동 실패가 다시 생기지 않게 지킨다.
 */
class ProposalServiceWiringTest {

    /** 저장소 mock 과 메모리 장부만 등록한 작은 ApplicationContext 에 ProposalService 를 등록하고, 빈이 만들어지는지 본다. */
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
