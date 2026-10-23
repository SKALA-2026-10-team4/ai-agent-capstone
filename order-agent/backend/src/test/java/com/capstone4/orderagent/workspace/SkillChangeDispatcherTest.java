package com.capstone4.orderagent.workspace;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java 의 dispatch 를 보호한다.
 * 전달이 실패하면 thread_id 없이 last_error 만 남겨 다음 차례에 다시 보내고, 다시 보내 성공하면 service 가 돌려준 thread_id 를 기록하는지 본다.
 * DB 는 mock JdbcTemplate 이고 service 의 POST /internal/skill-changes 는 MockRestServiceServer 로 흉내 내므로 DB 와 서버 없이 돈다.
 */
class SkillChangeDispatcherTest {
    @Test void failedDeliveryRemainsPendingAndSuccessfulRetryRecordsThread(){
        UUID change=UUID.randomUUID(),thread=UUID.randomUUID();
        var jdbc=mock(JdbcTemplate.class);
        Map<String,Object> row=Map.of("change_id",change,"payload","{\"change_id\":\""+change+"\"}");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row));
        var builder=RestClient.builder().baseUrl("http://service");
        var server=MockRestServiceServer.bindTo(builder).build();
        var dispatcher=new SkillChangeDispatcher(jdbc,builder.build(),true);
        server.expect(requestTo("http://service/internal/skill-changes")).andRespond(withServerError());
        server.expect(requestTo("http://service/internal/skill-changes"))
            .andRespond(withSuccess("{\"thread_id\":\""+thread+"\"}",MediaType.APPLICATION_JSON));
        dispatcher.dispatch();
        verify(jdbc).update(contains("last_error=?"),anyString(),eq(change));
        dispatcher.dispatch();
        verify(jdbc).update(contains("thread_id=?"),eq(thread),eq(change));
        server.verify();
    }
}
