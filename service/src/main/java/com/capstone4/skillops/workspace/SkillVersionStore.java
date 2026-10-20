package com.capstone4.skillops.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKILL.md 버전 저장소. 저장 = 새 버전이고, 예전 버전은 지우지 않는다.
 * A 의 저장 API(PUT /api/skills/{name})와 C-2 의 promote 가 save 를, C-2 workspace 와
 * C-1 읽기 tool 이 get·latest·history·latestAll 을 부른다.
 */
@Service
public class SkillVersionStore {

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^version:.*$");

    private final SkillVersionRepository repository;
    private final Path skillsDir;

    // 경로는 문자열로 받는다. Path 로 바로 받으면 웹 환경에서 Spring 이 "../skills" 를
    // 서블릿 리소스 경로로 해석하다 실패한다.
    public SkillVersionStore(SkillVersionRepository repository,
            @Value("${skillops.skills-dir}") String skillsDir) {
        this.repository = repository;
        this.skillsDir = Path.of(skillsDir);
    }

    /**
     * 새 버전으로 저장하고 live skills/&lt;name&gt;/SKILL.md 도 덮어쓴다.
     * frontmatter 의 version 줄은 서비스가 매긴 번호로 바꾼다(계약 1: 담당자는 건드리지 않는다).
     *
     * @param content frontmatter 를 포함한 SKILL.md 전문
     * @return 저장된 버전 (처음이면 1, 아니면 직전 + 1)
     */
    @Transactional
    public SkillVersion save(String name, String content, String savedBy) {
        int next = repository.findTopByNameOrderByVersionDesc(name).map(v -> v.version() + 1).orElse(1);
        String versioned = VERSION_LINE.matcher(content).replaceFirst("version: " + next);
        SkillVersion saved = repository.save(new SkillVersion(name, next, versioned, Instant.now(), savedBy));
        writeLive(name, versioned);
        return saved;
    }

    /** 정해진 번호 그대로 넣는다. live 파일은 이미 그 내용이라 건드리지 않는다. 첫 기동 적재용. */
    @Transactional
    SkillVersion importAs(String name, int version, String content, String savedBy) {
        String versioned = VERSION_LINE.matcher(content).replaceFirst("version: " + version);
        return repository.save(new SkillVersion(name, version, versioned, Instant.now(), savedBy));
    }

    public Optional<SkillVersion> get(String name, int version) {
        return repository.findByNameAndVersion(name, version);
    }

    public Optional<SkillVersion> latest(String name) {
        return repository.findTopByNameOrderByVersionDesc(name);
    }

    /** 최신 버전부터. */
    public List<SkillVersion> history(String name) {
        return repository.findByNameOrderByVersionDesc(name);
    }

    /** skill 마다 최신 버전 하나씩, 이름순. */
    public List<SkillVersion> latestAll() {
        return repository.findLatestOfEachSkill();
    }

    private void writeLive(String name, String content) {
        Path file = skillsDir.resolve(name).resolve("SKILL.md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("live SKILL.md 를 쓰지 못했습니다: " + file, e);
        }
    }
}
