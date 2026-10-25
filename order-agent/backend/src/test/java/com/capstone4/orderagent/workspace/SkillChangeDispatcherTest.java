package com.capstone4.orderagent.workspace;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillChangeDispatcher.java 의 dispatch 와 status 를 보호한다.
 * 전달이 실패하면 thread_id 없이 last_error 만 남겨 다음 차례에 다시 보내고, 다시 보내 성공하면 service 가 돌려준 thread_id 를 기록하는지 본다.
 * 받는 쪽이 행의 본문을 거절(400, 409, 422)하면 failed_at 을 남겨 다시 고르지 않고, 토큰·주소·부하 문제(401, 403, 404, 429, 5xx)는 다시 보내는지도 본다.
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
    /** 본문 탓 거절은 상태 숫자를 밝힌 last_error 와 failed_at 을 남기고, 고르는 질의는 failed_at 이 있는 행을 빼 뒤의 행이 막히지 않는다. */
    @Test void rejectedPayloadIsMarkedFailedAndNoLongerPicked(){
        for(var status:List.of(HttpStatus.BAD_REQUEST,HttpStatus.CONFLICT,HttpStatus.UNPROCESSABLE_CONTENT)){
            UUID change=UUID.randomUUID();
            var jdbc=dispatchOnce(change,status);
            verify(jdbc).queryForList(contains("thread_id is null and failed_at is null"));
            verify(jdbc).update(contains("failed_at=current_timestamp"),contains("HTTP "+status.value()),eq(change));
        }
    }
    /** 토큰, 주소, 부하, 서버 오류는 행의 잘못이 아니라 failed_at 없이 상태 숫자만 last_error 에 남기고 다음 차례에 다시 보낸다. */
    @Test void connectionSideFailuresStayRetryable(){
        for(var status:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.TOO_MANY_REQUESTS,HttpStatus.BAD_GATEWAY)){
            UUID change=UUID.randomUUID();
            var jdbc=dispatchOnce(change,status);
            verify(jdbc).update(argThat((String sql)->sql.contains("last_error=?")&&!sql.contains("failed_at")),contains("HTTP "+status.value()),eq(change));
            verify(jdbc,never()).update(contains("failed_at"),any(),any());
        }
    }
    /** 전달 상태는 thread_id 가 있으면 ACCEPTED, 없고 failed_at 이 있으면 FAILED, 둘 다 없으면 PENDING 이고 failed_at 칸은 응답에 싣지 않는다. */
    @Test void statusReportsFailedRowsWithoutTheFailedAtColumn(){
        UUID change=UUID.randomUUID();
        var jdbc=mock(JdbcTemplate.class);
        var row=new LinkedHashMap<String,Object>();
        row.put("change_id",change);row.put("snapshot_id",UUID.randomUUID());row.put("thread_id",null);row.put("attempts",1);
        row.put("last_error","진단 요청 거절(HTTP 400): 이 저장은 다시 보내지 않습니다");row.put("failed_at",java.sql.Timestamp.from(java.time.Instant.EPOCH));
        when(jdbc.queryForList(anyString(),eq(change))).thenReturn(List.of(row));
        var dispatcher=new SkillChangeDispatcher(jdbc,RestClient.create(),true);
        var status=dispatcher.status(change);
        assertThat(status.keySet())
            .containsExactly("change_id","snapshot_id","thread_id","attempts","last_error","delivery_status");
        assertThat(status.get("delivery_status")).isEqualTo("FAILED");
        row.put("failed_at",null);
        assertThat(dispatcher.status(change).get("delivery_status")).isEqualTo("PENDING");
        row.put("thread_id",UUID.randomUUID());
        assertThat(dispatcher.status(change).get("delivery_status")).isEqualTo("ACCEPTED");
    }

    /** 대기 행 하나를 두고 service 가 status 로 한 번 답하게 한 뒤 dispatch 를 한 번 부르고 mock JdbcTemplate 을 돌려준다. */
    static JdbcTemplate dispatchOnce(UUID change,HttpStatus status){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("change_id",change,"payload","{\"change_id\":\""+change+"\"}")));
        var builder=RestClient.builder().baseUrl("http://service");
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://service/internal/skill-changes")).andRespond(withStatus(status));
        new SkillChangeDispatcher(jdbc,builder.build(),true).dispatch();
        server.verify();
        return jdbc;
    }
}
