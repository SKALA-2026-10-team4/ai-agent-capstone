<script setup>
// 주문 Agent 의 실행 증거(trace, snapshot)를 보여주는 패널이다. 최근 실행 선택, trace 이벤트 목록, snapshot 파일 원본,
// 시연용 Skill 후보 고정 버튼을 담는다.
// 조회와 늦은 응답 처리는 order-agent/frontend/src/composables/useExecutionEvidence.js 가 하고, 표시 문구는
// order-agent/frontend/src/utils/evidenceFormat.js 가 만든다. 이 컴포넌트는 props, watch, lifecycle 을 composable 에 연결하고
// 템플릿에서 문구 함수를 부른다.
// order-agent/frontend/src/App.vue 가 executionId(지금 보이는 주문의 execution_id), locked(주문 입력 잠금 여부),
// selectedSnapshotId(다음 주문에 쓸 snapshot_id)를 넘긴다. 후보를 고정하면 snapshot 이벤트로 새 snapshot_id 를 올리고,
// App.vue 가 그 이벤트를 order-agent/frontend/src/composables/useOrder.js 의 selectSnapshot 에 연결해 다음 주문 입력에 넣는다.
// order-agent/frontend/tests/e2e/evidence.spec.js 가 이 패널의 버튼 문구, 클래스, 기본 탭에 기대므로
// 화면을 고칠 때 그 spec 을 함께 돌린다.
import { ref, watch, onMounted, onUnmounted } from "vue";
import { useExecutionEvidence } from "../composables/useExecutionEvidence.js";
import {
  costText,
  eventClock,
  eventKindLabel,
  executionOptionText,
  isErrorEvent,
  modelText,
  prettyJson,
  selectionText,
} from "../utils/evidenceFormat.js";
const props = defineProps({
  executionId: String,
  locked: Boolean,
  selectedSnapshotId: String,
});
// snapshot 이벤트는 새로 고정한 후보의 snapshot_id 를 싣는다.
const emit = defineEmits(["snapshot"]);
// 고정 버튼으로 보일 시연 fixture 목록이다. fixture 이름은
// order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의 DEMO_FIXTURES 와 같아야 한다.
// 시연 후보를 더하려면 서버의 fixture 와 이 목록을 함께 고친다.
// order-agent/frontend/tests/e2e/evidence.spec.js 는 label 문구로 버튼을 찾는다.
const SNAPSHOT_FIXTURES = [
  { fixture: "baseline", label: "준비된 정상본 고정" },
  { fixture: "broken-contract", label: "준비된 결함본 고정" },
  { fixture: "repaired", label: "준비된 수정본 고정" },
];
// 처음에는 trace 탭을 보여준다. snapshot 탭에는 식별자 목록(.evidence-identifiers)이 두 개라서, 기본 탭을 바꾸면
// 하나만 있다고 가정하는 order-agent/frontend/tests/e2e/evidence.spec.js 의 strict locator 가 실패한다.
const tab = ref("trace");
// api 는 기본값(order-agent/frontend/src/api/evidence.js)을 쓰고, props 는 getter 로 넘겨 호출 시점의 최신 값을 읽게 한다.
// 템플릿이 ref 로 쓰도록 최상위 변수로 꺼낸다. path 는 파일 select 의 v-model 이 ref 에 직접 쓰므로 반드시 최상위 변수여야 한다.
const {
  recent,
  trace,
  snapshot,
  file,
  path,
  error,
  loading,
  publishing,
  published,
  inspect,
  refresh,
  readFile,
  publish,
  dispose,
} = useExecutionEvidence(undefined, {
  executionId: () => props.executionId,
  locked: () => props.locked,
  onPublished: (snapshotId) => emit("snapshot", snapshotId),
});
// 주문 응답으로 executionId 가 생기거나 바뀌면 그 실행의 trace 를 먼저 열고, 새 실행이 최근 실행 select 에도 보이도록
// 목록을 다시 읽는다. executionId 가 있으므로 이때 refresh 는 가장 최근 실행을 자동으로 열지 않는다.
watch(
  () => props.executionId,
  async (id) => {
    if (id) {
      await inspect(id);
      await refresh();
    }
  },
  { immediate: true },
);
// 화면이 붙으면 최근 실행 목록을 읽는다. 아직 주문이 없으면 refresh 가 가장 최근 실행을 자동으로 연다.
onMounted(refresh);
// 화면이 사라지면 남은 조회를 취소하고 늦은 응답을 버린다.
onUnmounted(dispose);
</script>
<template>
  <section class="panel evidence-panel" aria-labelledby="evidence-title">
    <div class="section-heading">
      <div>
        <span class="eyebrow">03 / EXECUTION EVIDENCE</span>
        <h2 id="evidence-title">Trace · Snapshot</h2>
      </div>
      <button class="button ghost" type="button" @click="refresh">실행 목록 새로고침</button>
    </div>
    <p class="muted evidence-intro">Trace는 실제 실행 기록, snapshot은 그 실행에 사용한 Skill 파일 원본입니다. 이전 실행도 조회할 수 있어요.</p>
    <div class="evidence-toolbar">
      <label for="execution-select">최근 실행<select
          id="execution-select"
          :value="trace?.execution_id || ''"
          @change="inspect($event.target.value)"
        >
          <option value="" disabled>실행을 선택하세요</option>
          <option
            v-for="item in recent"
            :key="item.execution_id"
            :value="item.execution_id"
          >{{ executionOptionText(item) }}</option>
        </select></label>
      <div class="button-row" aria-label="실행 증거 보기">
        <button
          class="button"
          :class="tab === 'trace' ? 'primary' : 'ghost'"
          type="button"
          :aria-pressed="tab === 'trace'"
          @click="tab = 'trace'"
        >Trace 보기</button>
        <button
          class="button"
          :class="tab === 'snapshot' ? 'primary' : 'ghost'"
          type="button"
          :aria-pressed="tab === 'snapshot'"
          @click="tab = 'snapshot'"
        >Snapshot 보기</button>
      </div>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="loading" class="notice" role="status">실행 기록을 읽고 있습니다.</p>
    <template v-if="trace">
      <dl class="evidence-identifiers">
        <div>
          <dt>실행 ID</dt>
          <dd>{{ trace.execution_id }}</dd>
        </div>
        <div>
          <dt>원래 선택 → 해석</dt>
          <dd>{{ selectionText(trace) }}</dd>
        </div>
        <div>
          <dt>실행 모드</dt>
          <dd>
            {{ modelText(trace) }} · {{ trace.completion }}
            <span v-if="trace.error_category">· {{ trace.error_category }}</span>
          </dd>
        </div>
        <div>
          <dt>관측 비용</dt>
          <dd>{{ costText(trace) }}</dd>
        </div>
      </dl>
      <div
        v-if="tab === 'trace'"
        class="trace-events"
        aria-label="실행 trace 이벤트"
      >
        <details
          v-for="event in trace.events"
          :key="event.sequence"
          :class="{ 'event-error': isErrorEvent(event.kind) }"
        >
          <summary>
            <span class="event-number">{{ event.sequence }}</span>{{ eventKindLabel(event.kind) }}
            <strong v-if="event.skill_id">{{ event.skill_id }}</strong><small>{{ eventClock(event.at) }} UTC</small>
          </summary>
          <pre>{{ prettyJson(event.data) }}</pre>
        </details>
      </div>
      <div v-else-if="snapshot" class="snapshot-view">
        <dl class="evidence-identifiers">
          <div>
            <dt>Snapshot ID</dt>
            <dd>{{ snapshot.snapshot_id }}</dd>
          </div>
          <div>
            <dt>전체 SHA-256</dt>
            <dd>{{ snapshot.hash }}</dd>
          </div>
          <div>
            <dt>후보 출처</dt>
            <dd>{{ prettyJson(snapshot.metadata) }}</dd>
          </div>
        </dl>
        <label for="snapshot-file">고정된 파일 · 관련 자원 포함<select
            id="snapshot-file"
            v-model="path"
            @change="readFile"
          >
            <option
              v-for="item in snapshot.files"
              :key="item.path"
              :value="item.path"
            >{{ item.path }} · {{ item.size }} bytes</option>
          </select></label>
        <template v-if="file">
          <p class="file-hash mono">SHA-256 {{ file.hash }} · {{ file.encoding }}</p>
          <pre class="snapshot-content">{{ file.content }}</pre>
        </template>
      </div>
      <p v-else class="notice">파일 고정 전에 실행이 실패해 snapshot이 없습니다. Trace의 오류를 확인해주세요.</p>
    </template>
    <p v-else-if="!loading" class="notice">아직 Agent 실행 기록이 없습니다. 새 주문을 실행하면 여기에 표시됩니다. 기존 MVP 주문에는 소급해 기록을 만들지 않습니다.</p>
    <details class="skill-demo">
      <summary>개발·시연: 미리 준비한 Skill 후보</summary>
      <p>아래는 미리 준비한 파일 세트입니다. SkillOps가 생성하거나 자동 수정한 후보가 아닙니다. 주문 입력을 채우고 후보를 고정한 다음 실행하세요. 원본과 이전 snapshot은 유지됩니다.</p>
      <div class="button-row">
        <button
          v-for="item in SNAPSHOT_FIXTURES"
          :key="item.fixture"
          class="button ghost"
          :disabled="locked || publishing"
          @click="publish(item.fixture)"
        >{{ item.label }}</button>
      </div>
      <p class="mono">다음 주문 snapshot: {{ selectedSnapshotId || "실행 시 현재 게시된 Skill 세트 고정" }}</p>
      <p
        v-if="published && selectedSnapshotId === published.snapshot_id"
        class="notice"
      >{{ published.metadata.fixture }} 후보를 선택했습니다. 위 주문 실행 버튼으로 실행해주세요.</p>
      <small>결함 후보는 inventory.reserved 선언을 제거해 계약 검사에서 실패합니다. 고정 모드는 자연어 본문 해석의 유효성을 보증하지 않습니다.</small>
    </details>
  </section>
</template>
