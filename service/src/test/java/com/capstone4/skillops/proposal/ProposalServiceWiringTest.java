package com.capstone4.skillops.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.capstone4.skillops.scenario.RunLedger;
import com.capstone4.skillops.scenario.ScenarioCatalog;
import com.capstone4.skillops.scenario.TestFixtures.InMemoryRunLedger;
import com.capstone4.skillops.snapshot.OrderAgentClient;
import com.capstone4.skillops.workspace.WorkspaceService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spring 이 service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 빈을 실제로 만들 수 있는지 보호하는 테스트다.
 * ProposalService 는 생성자가 둘이라서(테스트용 생성자는 Clock 을 더 받는다) 공개 생성자에 @Autowired 가 없으면 Spring 이 어느 쪽을
 * 쓸지 몰라 빈을 만들지 못한다. 커밋 943b585 에서 고친 이 기동 실패가 다시 생기지 않게 지킨다.
 * 재판정기(ProposalJudge)는 연습 공간과 주문 Agent 를 쓰므로 테스트 환경(ProposalTestBench)으로 만든 빈을 등록한다.
 */
class ProposalServiceWiringTest {

    /** 저장소 mock, 메모리 장부, 재판정기만 등록한 작은 ApplicationContext 에 ProposalService 를 등록하고, 빈이 만들어지는지 본다. */
    @Test
    void springCreatesTheServiceWithItsPublicConstructor() {
        ProposalTestBench bench = new ProposalTestBench();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProposalRepository.class, () -> mock(ProposalRepository.class));
            context.registerBean(RunLedger.class, InMemoryRunLedger::new);
            context.registerBean(ProposalJudge.class, bench::judge);
            context.register(ProposalService.class);
            context.refresh();

            assertThat(context.getBean(ProposalService.class)).isNotNull();
        }
    }

    /**
     * 재판정기도 공개 생성자로 만들어지는지 본다. 설정이 없으면 min-repeat 는 2, golden-ids 는 기준 주문 폴더 전부다.
     * 설정한 golden-ids 가 폴더에 없으면 빈을 만들지 못해 서비스가 켜지지 않는다.
     */
    @Test
    void springCreatesTheJudgeFromSettings() {
        try (AnnotationConfigApplicationContext context = judgeContext(null)) {
            assertThat(context.getBean(ProposalJudge.class)).isNotNull();
        }
        try (AnnotationConfigApplicationContext context = judgeContext("g-01")) {
            assertThat(context.getBean(ProposalJudge.class)).isNotNull();
        }
        assertThatThrownBy(() -> judgeContext("g-99").close()).hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("g-99");
    }

    /** 재판정기가 받는 빈을 등록하고 skillops.golden-ids 를 goldenIds 로 둔 ApplicationContext 를 만든다. */
    static AnnotationConfigApplicationContext judgeContext(String goldenIds) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (goldenIds != null) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("skillops.golden-ids", goldenIds)));
        }
        context.registerBean(RunLedger.class, InMemoryRunLedger::new);
        context.registerBean(WorkspaceService.class, () -> mock(WorkspaceService.class));
        context.registerBean(OrderAgentClient.class, () -> mock(OrderAgentClient.class));
        context.registerBean(ScenarioCatalog.class, () -> new ScenarioCatalog("../scenarios", JsonMapper.builder().build()));
        context.register(ProposalJudge.class);
        context.refresh();
        return context;
    }
}
