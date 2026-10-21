package com.capstone4.skillops.proposal;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProposalRepository extends JpaRepository<Proposal, String> {

    Optional<Proposal> findByThreadId(String threadId);

    List<Proposal> findTop100ByOrderByCreatedAtDesc();
}
