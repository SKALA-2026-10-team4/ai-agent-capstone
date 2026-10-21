package com.capstone4.skillops.approval;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    List<Approval> findTop50ByOrderByDecidedAtDesc();
}
