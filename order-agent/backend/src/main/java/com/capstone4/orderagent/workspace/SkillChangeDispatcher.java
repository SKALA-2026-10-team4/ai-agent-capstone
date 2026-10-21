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
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Service
public class SkillChangeDispatcher {
    private final JdbcTemplate jdbc;
    private final RestClient http;
    private final boolean enabled;
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
    SkillChangeDispatcher(JdbcTemplate jdbc,RestClient http,boolean enabled){this.jdbc=jdbc;this.http=http;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${order-agent.skill-change-dispatch-delay-ms:3000}")
    @Transactional
    public void dispatch(){
        if(!enabled)return;
        var rows=jdbc.queryForList("select change_id,payload from skill_change_events where thread_id is null order by created_at limit 1 for update skip locked");
        for(var row:rows){
            try{
                String raw=http.post().uri("/internal/skill-changes").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(row.get("payload")).retrieve().body(String.class);
                var result=Json.object(raw);
                UUID thread=UUID.fromString((String)result.get("thread_id"));
                jdbc.update("update skill_change_events set thread_id=?,attempts=attempts+1,last_error=null where change_id=?",thread,row.get("change_id"));
            }catch(RuntimeException e){
                jdbc.update("update skill_change_events set attempts=attempts+1,last_error=? where change_id=?",
                    "진단 요청 전달 실패: "+e.getClass().getSimpleName(),row.get("change_id"));
            }
        }
    }
    public Map<String,Object> status(UUID id){
        var rows=jdbc.queryForList("select change_id,snapshot_id,thread_id,attempts,last_error from skill_change_events where change_id=?",id);
        if(rows.isEmpty())throw new AgentException("SKILL_CHANGE_NOT_FOUND");
        var result=new LinkedHashMap<String,Object>(rows.getFirst());
        result.put("delivery_status",result.get("thread_id")==null?"PENDING":"ACCEPTED");
        return result;
    }
}
