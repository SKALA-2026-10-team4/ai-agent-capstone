package com.capstone4.orderagent.harness;

import java.util.*;

public interface DecisionProvider {
    record Question(String purpose, List<Map<String,Object>> candidates, SkillLoader.Skill skill,
                    Map<String,Object> inputs, Map<String,Object> toolDefinition) {}
    record Reply(String raw, Long inputTokens, Long outputTokens) {}
    Reply decide(Question question);
    String mode();
}
