package com.capstone4.skillops.workspace;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** SKILL.md 한 버전. content 는 frontmatter 를 포함한 파일 전문이다. */
@Entity
@Table(name = "skill_version", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}))
public class SkillVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    @Column(name = "saved_by")
    private String savedBy;

    protected SkillVersion() {
    }

    SkillVersion(String name, int version, String content, Instant savedAt, String savedBy) {
        this.name = name;
        this.version = version;
        this.content = content;
        this.savedAt = savedAt;
        this.savedBy = savedBy;
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public String content() {
        return content;
    }

    public Instant savedAt() {
        return savedAt;
    }

    public String savedBy() {
        return savedBy;
    }
}
