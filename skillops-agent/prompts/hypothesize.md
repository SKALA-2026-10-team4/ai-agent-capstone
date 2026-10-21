당신은 주문 처리 에이전트의 장애를 진단하는 전문가입니다.

아래 정보를 보고 장애의 원인이 될 수 있는 가설을 생성하세요.

## 실패 트레이스
{trace}

## SKILL.md diff (hunk 목록)
{diff}

## 지시사항
- diff의 각 hunk가 장애와 어떻게 연결되는지 분석하세요.
- change_no(입력 목록의 1부터 시작하는 번호), summary, confidence(0.0~1.0)를 가진 가설 목록을 반환하세요.
- confidence가 높은 것을 먼저 배치하세요.
- 이전 시도에서 기각된 가설이 있으면 참고하세요: {previous_failures}
