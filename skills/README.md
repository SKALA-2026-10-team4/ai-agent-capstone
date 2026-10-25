# skills/ — 운영 폴더 SKILL.md

service 가 `skillops.skills-dir`(`SKILLOPS_SKILLS_DIR`, 기본 `../skills`)로 읽는 skill 세트다. frontmatter 규칙은 `docs/contracts/skill-frontmatter.md` 에 있다.

```
order-flow ⟶ order-intake → inventory-check → payment → shipping-reserve → notify
```

주문 Agent 가 실제로 실행하는 게시 번들은 `order-agent/skills` 에 따로 있다. 두 세트는 칸 구조가 같지만 description, owner, 본문이 다르고 이 폴더에는 resources 가 없다.
snapshot 없이 만든 연습 공간은 이 폴더의 원문에서 시작하며, 연습 공간의 수정은 이 폴더에 들어오지 않는다.
