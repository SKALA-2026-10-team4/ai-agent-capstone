package com.capstone4.skillops.proposal;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProposalService {

    private final ProposalRepository repository;
    private final Clock clock;

    @Autowired
    public ProposalService(ProposalRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ProposalService(ProposalRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record SavedProposal(String id, String status) {
    }

    public SavedProposal save(Map<String, Object> payload) {
        String id = "p_" + UUID.randomUUID().toString().replace("-", "");
        String status = String.valueOf(payload.getOrDefault("status", "proposed"));
        repository.save(Proposal.create(id, status, payload, clock.instant()));
        return new SavedProposal(id, status);
    }
}
