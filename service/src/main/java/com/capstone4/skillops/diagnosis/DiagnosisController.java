package com.capstone4.skillops.diagnosis;

import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

/** Server-side token boundary: the browser never receives the B service token. */
@RestController
public class DiagnosisController {
    private final RestClient agent;
    private final String serviceToken;
    @org.springframework.beans.factory.annotation.Autowired
    public DiagnosisController(@Value("${skillops.agent.base-url}") String url,
            @Value("${skillops.agent.service-token:}") String token){
        serviceToken=token;
        var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        agent=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Service-Token",token).build();
    }
    DiagnosisController(RestClient agent,String token){this.agent=agent;this.serviceToken=token;}
    @PostMapping("/internal/skill-changes")
    public Map<?,?> changed(@RequestHeader(value="X-Service-Token",defaultValue="") String token,@RequestBody Map<String,Object> event){
        if(!serviceToken.isBlank()&&!serviceToken.equals(token))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(!"skill_saved".equals(event.get("type"))||event.get("change_id")==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A saved change is required");
        return agent.post().uri("/diagnose").contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(Map.class);
    }
    @GetMapping("/api/diagnoses")
    public Map<?,?> list(){return agent.get().uri("/runs").retrieve().body(Map.class);}
    @GetMapping("/api/diagnoses/{threadId}")
    public Map<?,?> status(@PathVariable UUID threadId){
        return agent.get().uri("/runs/{id}/events",threadId).retrieve().body(Map.class);
    }
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String,String>> upstream(RestClientResponseException e){
        int status=e.getStatusCode().value()==404?404:502;
        return ResponseEntity.status(status).body(Map.of("code","DIAGNOSIS_UPSTREAM_ERROR","message","진단 Agent가 요청을 처리하지 못했습니다 ("+e.getStatusCode().value()+")."));
    }
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String,String>> unavailable(ResourceAccessException e){
        return ResponseEntity.status(502).body(Map.of("code","DIAGNOSIS_UNAVAILABLE","message","진단 Agent에 연결하지 못했습니다. 다시 조회해 주세요."));
    }
}
