package com.capstone4.skillops.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 서비스가 뜰 때 skills/ 의 SKILL.md 중 저장소에 아직 없는 skill 을 version 1 로 넣는다.
 * 이미 있는 skill 은 건드리지 않아서, 다시 떠도 버전 이력이 중복되거나 덮이지 않는다.
 */
@Component
public class SkillVersionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillVersionSeeder.class);

    private final SkillVersionStore store;
    private final Path skillsDir;

    // SkillVersionStore 와 같은 이유로 문자열로 받는다.
    public SkillVersionSeeder(SkillVersionStore store, @Value("${skillops.skills-dir}") String skillsDir) {
        this.store = store;
        this.skillsDir = Path.of(skillsDir);
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        for (Path file : skillFiles()) {
            String name = file.getParent().getFileName().toString();
            if (store.latest(name).isEmpty()) {
                store.importAs(name, 1, read(file), "seed");
                log.info("skill 적재: {} v1", name);
            }
        }
    }

    private List<Path> skillFiles() {
        if (!Files.isDirectory(skillsDir)) {
            log.warn("skills 폴더가 없어 적재를 건너뜁니다: {}", skillsDir.toAbsolutePath());
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            return dirs.filter(Files::isDirectory)
                    .map(dir -> dir.resolve("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
