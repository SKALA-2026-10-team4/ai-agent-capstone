package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.capstone4.skillops.workspace.WorkspaceService;

/** skillops.flow-runner 값마다 실행기가 꼭 하나만 뜬다. 둘이 뜨면 ScenarioRunner 가 만들어지지 않는다. */
class FlowRunnerSelectionTest {

    ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(WorkspaceService.class, () -> mock(WorkspaceService.class))
            .withBean(OrderAgentRunClient.class, () -> mock(OrderAgentRunClient.class))
            .withUserConfiguration(ContractOnlyFlowRunner.class, HarnessFlowRunner.class);

    @Test
    void contractOnlyIsTheDefault() {
        context.run(ctx -> assertThat(ctx).getBean(FlowRunner.class).isInstanceOf(ContractOnlyFlowRunner.class));
        context.withPropertyValues("skillops.flow-runner=contract-only")
                .run(ctx -> assertThat(ctx).getBean(FlowRunner.class).isInstanceOf(ContractOnlyFlowRunner.class));
    }

    @Test
    void harnessSelectsTheOrderAgentRunner() {
        context.withPropertyValues("skillops.flow-runner=harness").run(ctx -> {
            assertThat(ctx).hasSingleBean(FlowRunner.class);
            assertThat(ctx).getBean(FlowRunner.class).isInstanceOf(HarnessFlowRunner.class);
            assertThat(ctx.getBean(FlowRunner.class).supportsReplay()).isTrue();
        });
    }
}
