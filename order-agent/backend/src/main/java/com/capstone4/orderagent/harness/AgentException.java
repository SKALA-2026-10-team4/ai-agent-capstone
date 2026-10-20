package com.capstone4.orderagent.harness;

import java.util.Map;

public class AgentException extends RuntimeException {
    private final String category;
    private final Map<String,Object> details;
    public AgentException(String category) { this(category, Map.of()); }
    public AgentException(String category, Map<String,Object> details) {
        super(category); this.category=category; this.details=Map.copyOf(details);
    }
    public String category() { return category; }
    public Map<String,Object> details() { return details; }
}
