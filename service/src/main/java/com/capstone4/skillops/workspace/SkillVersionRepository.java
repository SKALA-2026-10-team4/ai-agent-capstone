package com.capstone4.skillops.workspace;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SkillVersionRepository extends JpaRepository<SkillVersion, Long> {

    Optional<SkillVersion> findByNameAndVersion(String name, int version);

    Optional<SkillVersion> findTopByNameOrderByVersionDesc(String name);

    List<SkillVersion> findByNameOrderByVersionDesc(String name);

    @Query("""
            select v from SkillVersion v
            where v.version = (select max(v2.version) from SkillVersion v2 where v2.name = v.name)
            order by v.name""")
    List<SkillVersion> findLatestOfEachSkill();
}
