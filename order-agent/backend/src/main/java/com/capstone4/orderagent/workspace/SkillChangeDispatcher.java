package com.capstone4.orderagent.workspace;

import java.time.Duration;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Skill 수정 저장 이벤트를 service 로 보내는 outbox 전달기다. order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의
 * saveEdits 가 snapshot 저장과 같은 트랜잭션에 skill_change_events 행을 남기면, dispatch 가 주기적으로 그 행을
 * service 의 POST /internal/skill-changes 로 보낸다. 저장과 전달을 나눠 두어 service 나 진단 Agent 가 꺼져 있어도
 * 수정 저장은 성공하고, 전달은 성공할 때까지 다음 차례에 다시 시도한다. 다만 받는 쪽이 그 행의 본문을 거절하면(REJECTED_STATUSES)
 * failed_at 을 남기고 더는 보내지 않는다.
 * 받는 쪽은 service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 의 changed 다. X-Service-Token 을 확인하고
 * 이벤트를 진단 Agent 의 POST /diagnose 로 넘긴 뒤 thread_id 가 든 응답을 돌려주며, 같은 change_id 와 같은 본문을 다시 보내도 진단은 하나만 생긴다.
 * 설정은 skillops.service-url(SKILLOPS_SERVICE_URL), skillops.service-token(SKILLOPS_SERVICE_TOKEN), order-agent.skill-change-dispatch-enabled
 * (기본 true, 테스트 설정 order-agent/backend/src/test/resources/application.properties 는 false), order-agent.skill-change-dispatch-delay-ms(기본 3000)다.
 * 연결 2초와 읽기 10초 타임아웃, 한 번에 한 행씩 보내는 크기는 코드에 고정돼 있다.
 * 전달 상태는 status 로 읽으며 order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의
 * changeDelivery(GET /api/skill-changes/{id})가 부른다.
 */
@Service
public class SkillChangeDispatcher {
    /**
     * 받는 쪽이 이 행의 본문 탓으로 거절했다고 보는 HTTP 상태다. 400(저장 이벤트 모양), 409(같은 change_id 에 다른 본문), 413, 415, 422(읽을 수 없는 본문)는
     * 다시 보내도 같은 답이므로 failed_at 을 남기고 다시 고르지 않는다. 그래야 그 행이 뒤에 저장된 행의 전달을 막지 않는다.
     * 401, 403, 404, 408, 429 같은 나머지 4xx 와 5xx, 연결 실패는 토큰, 주소, 부하처럼 행이 아니라 연결 쪽 문제라 고치면 모든 행이 다시 전달돼야 하므로
     * 지금처럼 다음 차례에 다시 보낸다. 지금 service 중계(DiagnosisController.upstream)는 Agent 의 409, 422 를 502 로 바꾸므로 실제로 여기에 드는 것은
     * 중계 자신의 400 이다. 읽을 수 없는 의도 조건은 SnapshotService 가 저장 때 막는다.
     */
    private static final Set<Integer> REJECTED_STATUSES=Set.of(400,409,413,415,422);
    private final JdbcTemplate jdbc;
    private final RestClient http;
    private final boolean enabled;
    /**
     * Spring 이 쓰는 생성자다. service 주소와 서비스 토큰으로 RestClient 를 만들고 모든 요청에 X-Service-Token 헤더를 붙인다.
     * HTTP/1.1 을 고정하고 연결 2초, 읽기 10초 타임아웃을 건다. service 쪽에 토큰이 설정돼 있는데 이 값이 다르면(비어 있어도) 401 로 거절돼
     * 전달이 계속 실패로 남는다.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SkillChangeDispatcher(JdbcTemplate jdbc,
            @Value("${skillops.service-url:http://localhost:8080}") String serviceUrl,
            @Value("${skillops.service-token:}") String token,
            @Value("${order-agent.skill-change-dispatch-enabled:true}") boolean enabled){
        this.jdbc=jdbc;this.enabled=enabled;
        var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http=RestClient.builder().baseUrl(serviceUrl).requestFactory(factory)
            .defaultHeader("X-Service-Token",token).build();
    }
    /** 테스트용 생성자다. SkillChangeDispatcherTest 가 mock JdbcTemplate 과 MockRestServiceServer 에 묶은 RestClient 를 넣는다. */
    SkillChangeDispatcher(JdbcTemplate jdbc,RestClient http,boolean enabled){this.jdbc=jdbc;this.http=http;this.enabled=enabled;}
    /**
     * 아직 진단 thread 를 받지 못했고(thread_id 가 빈) 거절되지도 않은(failed_at 이 빈) 이벤트 가운데 가장 오래된 하나를 잠가 service 로 보낸다.
     * for update skip locked 라 겹친 실행이나 다른 인스턴스가 같은 행을 함께 보내지 않는다. 응답의 thread_id 를 받으면 그 값을 남기고 시도 횟수를 올리며
     * last_error 를 지운다. 받는 쪽이 REJECTED_STATUSES 로 답하면 시도 횟수를 올리고 상태 숫자를 밝힌 last_error 와 failed_at 을 남겨 다시 고르지 않는다.
     * 그 밖의 실패는 시도 횟수를 올리고 예외 클래스 이름(HTTP 응답이면 상태 숫자도)을 last_error 에 남긴 뒤 다음 차례에 다시 보낸다.
     * 다시 보내는 행도 다음 차례에 가장 먼저 고르므로, 그런 실패가 이어지는 동안에는 그 뒤에 저장된 행도 전달되지 않는다.
     * 거절된 행을 다시 보내려면 DB 에서 그 행의 failed_at 을 비운다.
     * HTTP 호출이 DB 트랜잭션 안에서 일어나므로 응답을 기다리는 동안(최대 읽기 10초) 그 행의 잠금이 유지된다.
     * Spring 스케줄러가 앞 실행이 끝난 뒤 order-agent.skill-change-dispatch-delay-ms 만큼 쉬고 부르며, 기능이 꺼져 있으면 아무것도 하지 않는다.
     */
    @Scheduled(fixedDelayString="${order-agent.skill-change-dispatch-delay-ms:3000}")
    @Transactional
    public void dispatch(){
        if(!enabled)return;
        var rows=jdbc.queryForList("select change_id,payload from skill_change_events where thread_id is null and failed_at is null order by created_at limit 1 for update skip locked");
        for(var row:rows){
            try{
                String raw=http.post().uri("/internal/skill-changes").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(row.get("payload")).retrieve().body(String.class);
                var result=Json.object(raw);
                UUID thread=UUID.fromString((String)result.get("thread_id"));
                jdbc.update("update skill_change_events set thread_id=?,attempts=attempts+1,last_error=null where change_id=?",thread,row.get("change_id"));
            }catch(RestClientResponseException e){
                int status=e.getStatusCode().value();
                if(REJECTED_STATUSES.contains(status))
                    jdbc.update("update skill_change_events set attempts=attempts+1,last_error=?,failed_at=current_timestamp where change_id=?",
                        "진단 요청 거절(HTTP "+status+"): 이 저장은 다시 보내지 않습니다",row.get("change_id"));
                else retryLater(row.get("change_id"),e.getClass().getSimpleName()+" (HTTP "+status+")");
            }catch(RuntimeException e){
                retryLater(row.get("change_id"),e.getClass().getSimpleName());
            }
        }
    }
    /** 시도 횟수를 올리고 실패 이유를 last_error 에 남긴다. failed_at 을 두지 않으므로 다음 차례에 다시 보낸다. */
    private void retryLater(Object changeId,String reason){
        jdbc.update("update skill_change_events set attempts=attempts+1,last_error=? where change_id=?","진단 요청 전달 실패: "+reason,changeId);
    }
    /**
     * 수정 저장 이벤트 하나의 전달 상태를 읽는다. 응답 키는 change_id, snapshot_id, thread_id, attempts, last_error, delivery_status 이고,
     * delivery_status 는 thread_id 가 있으면 ACCEPTED, 없고 failed_at 이 있으면(받는 쪽이 거절해 다시 보내지 않음) FAILED, 둘 다 없으면 PENDING 이다.
     * failed_at 자체는 응답에 싣지 않는다. 없는 변경이면 SKILL_CHANGE_NOT_FOUND(404)다.
     * EvidenceController.changeDelivery 가 부르고, frontend/src/views/DiagnosisView.vue 는 thread_id 가 생기면 그 진단 화면으로 옮기고
     * last_error 가 있으면 자동 재전송 안내와 함께 보여 준다.
     */
    public Map<String,Object> status(UUID id){
        var rows=jdbc.queryForList("select change_id,snapshot_id,thread_id,attempts,last_error,failed_at from skill_change_events where change_id=?",id);
        if(rows.isEmpty())throw new AgentException("SKILL_CHANGE_NOT_FOUND");
        var result=new LinkedHashMap<String,Object>(rows.getFirst());
        Object failed=result.remove("failed_at");
        result.put("delivery_status",result.get("thread_id")!=null?"ACCEPTED":failed!=null?"FAILED":"PENDING");
        return result;
    }
}
