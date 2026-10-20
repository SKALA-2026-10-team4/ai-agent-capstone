package com.capstone4.skillops.trace;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceRepository extends JpaRepository<TraceRecord, String> {
}
