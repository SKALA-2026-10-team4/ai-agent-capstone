package com.capstone4.orderagent.trace;

import java.util.*;
import com.capstone4.orderagent.harness.AgentException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/trace/RunId.java 의 of 와 parse 를 보호한다. run_id 별칭의 인코딩과 해석이
 * 한 규칙으로 왕복하는지, 다른 모양을 EXECUTION_NOT_FOUND 로 거절하는지 확인한다. trace 응답의 run_id 와 GET /api/traces/{runId} 가 이 규칙을 쓴다.
 */
class RunIdTest {
    final UUID id=UUID.fromString("0a1b2c3d-4e5f-6789-abcd-ef0123456789");

    /** 실행 id 는 'r_' 뒤에 하이픈을 뺀 소문자 16진수로 바뀌고 다시 같은 id 로 돌아온다. */
    @Test void encodesAndParsesBack(){
        assertThat(RunId.of(id)).isEqualTo("r_0a1b2c3d4e5f6789abcdef0123456789");
        assertThat(RunId.parse(RunId.of(id))).isEqualTo(id);
        assertThat(RunId.parse("r_0A1B2C3D4E5F6789ABCDEF0123456789")).isEqualTo(id);
    }
    /** 접두어, 길이, 16진수 규칙이 다르면 EXECUTION_NOT_FOUND 다. */
    @Test void rejectsOtherShapes(){
        for(String bad:List.of("0a1b2c3d4e5f6789abcdef0123456789","R_0a1b2c3d4e5f6789abcdef0123456789","r_0a1b2c3d4e5f6789abcdef012345678",
                "r_0a1b2c3d4e5f6789abcdef01234567890","r_0a1b2c3d4e5f6789abcdef012345678g","r_"+id))
            assertThatThrownBy(()->RunId.parse(bad)).as(bad).isInstanceOf(AgentException.class).hasMessage("EXECUTION_NOT_FOUND");
    }
}
