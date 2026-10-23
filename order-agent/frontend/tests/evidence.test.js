// order-agent/frontend/src/composables/useExecutionEvidence.js 의 조회 순서, 늦은 응답 무시, 최근 실행 자동 선택,
// 후보 고정, dispose 와, order-agent/frontend/src/utils/evidenceFormat.js 의 이벤트 라벨과 문구 helper 를 보호한다.
// 문구 helper 의 기대값은 이 문구들이 템플릿 보간에 있을 때 만들던 문자열과 같아서, 화면 문구가 바뀌면 이 테스트가 먼저 깨진다.
// 가짜 증거 API 를 넣어 node --test 에서 서버 없이 돈다.
import test from "node:test";
import assert from "node:assert/strict";
import { useExecutionEvidence } from "../src/composables/useExecutionEvidence.js";
import {
  EVENT_KIND_TEXT,
  costText,
  eventClock,
  eventKindLabel,
  executionOptionText,
  isErrorEvent,
  modelText,
  prettyJson,
  selectionText,
} from "../src/utils/evidenceFormat.js";
import { timestamp } from "../src/utils/format.js";

/** 테스트가 resolve 와 reject 시점을 직접 정하는 promise 를 만든다. 응답 순서를 뒤바꿀 때 쓴다. */
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};
/** 대기 중인 promise 후속 작업이 한 바퀴 돌도록 기다린다. */
const flush = () => new Promise((resolve) => setImmediate(resolve));
/** snapshot_id 가 snap-{id} 인 가짜 trace 를 만든다. */
const traceOf = (id, extra = {}) => ({
  execution_id: id,
  snapshot_id: `snap-${id}`,
  events: [],
  ...extra,
});
/** 가짜 snapshot manifest 를 만든다. paths 를 주지 않으면 파일 두 개를 담는다. */
const bundleOf = (id, paths = ["skills/a/SKILL.md", "skills/b.md"]) => ({
  snapshot_id: id,
  files: paths.map((path) => ({ path, size: 1 })),
});
/**
 * 호출 기록을 남기는 가짜 증거 API 를 만든다. 기록에는 함수 이름과 마지막 인자(options)를 뺀 인자만 남긴다.
 * overrides 로 테스트마다 필요한 함수만 바꾼다.
 */
const fakeApi = (overrides = {}) => {
  const calls = [];
  const record =
    (name, impl) =>
    (...args) => {
      calls.push([name, ...args.slice(0, -1)]);
      return impl(...args);
    };
  const api = {
    listExecutions: record("listExecutions", async () => ({ items: [] })),
    getTrace: record("getTrace", async (id) => traceOf(id)),
    getSnapshot: record("getSnapshot", async (id) => bundleOf(id)),
    getSnapshotFile: record("getSnapshotFile", async (id, path) => ({
      hash: "h",
      encoding: "utf-8",
      content: `${id}:${path}`,
    })),
    publishSnapshot: record("publishSnapshot", async (fixture) => ({
      snapshot_id: `pub-${fixture}`,
      metadata: { fixture },
    })),
  };
  for (const [name, impl] of Object.entries(overrides))
    api[name] = record(name, impl);
  return { api, calls };
};

test("inspect reads trace, then its snapshot, then the first file", async () => {
  const { api, calls } = fakeApi();
  const state = useExecutionEvidence(api);
  await state.inspect("e1");
  assert.deepEqual(calls, [
    ["getTrace", "e1"],
    ["getSnapshot", "snap-e1"],
    ["getSnapshotFile", "snap-e1", "skills/a/SKILL.md"],
  ]);
  assert.equal(state.trace.value.execution_id, "e1");
  assert.equal(state.snapshot.value.snapshot_id, "snap-e1");
  assert.equal(state.path.value, "skills/a/SKILL.md");
  assert.equal(state.file.value.content, "snap-e1:skills/a/SKILL.md");
  assert.equal(state.loading.value, false);
  assert.equal(state.error.value, "");
  await state.inspect("");
  assert.equal(calls.length, 3);
  state.dispose();
});

test("inspect passes the abort signal and skips snapshot reads without a snapshot id", async () => {
  const seen = [];
  const { api, calls } = fakeApi({
    getTrace: async (id, options) => {
      seen.push(options);
      return traceOf(id, { snapshot_id: null });
    },
  });
  const state = useExecutionEvidence(api);
  await state.inspect("e1");
  assert.deepEqual(calls, [["getTrace", "e1"]]);
  assert.deepEqual(Object.keys(seen[0]), ["signal"]);
  assert.ok(seen[0].signal instanceof AbortSignal);
  assert.equal(state.snapshot.value, null);
  assert.equal(state.file.value, null);
  assert.equal(state.path.value, "");
  state.dispose();
});

test("inspect clears the previous snapshot and ignores a slower earlier trace", async () => {
  const traces = { e1: deferred(), e2: deferred() };
  const { api } = fakeApi({ getTrace: (id) => traces[id].promise });
  const state = useExecutionEvidence(api);
  const first = state.inspect("e1");
  const second = state.inspect("e2");
  assert.equal(state.loading.value, true);
  traces.e2.resolve(traceOf("e2"));
  await second;
  traces.e1.resolve(traceOf("e1"));
  await first;
  assert.equal(state.trace.value.execution_id, "e2");
  assert.equal(state.snapshot.value.snapshot_id, "snap-e2");
  assert.equal(state.loading.value, false);
  state.dispose();
});

test("a failed trace read shows its message and keeps the earlier trace", async () => {
  let fail = false;
  const { api } = fakeApi({
    getTrace: async (id) => {
      if (fail) throw new Error("trace offline");
      return traceOf(id);
    },
  });
  const state = useExecutionEvidence(api);
  await state.inspect("e1");
  fail = true;
  await state.inspect("e2");
  assert.equal(state.error.value, "trace offline");
  assert.equal(state.loading.value, false);
  assert.equal(state.trace.value.execution_id, "e1");
  assert.equal(state.snapshot.value, null);
  state.dispose();
});

test("refresh lists executions and auto-selects the newest only when nothing is shown", async () => {
  const items = [{ execution_id: "new" }, { execution_id: "old" }];
  const { api, calls } = fakeApi({ listExecutions: async () => ({ items }) });
  const state = useExecutionEvidence(api);
  await state.refresh();
  assert.deepEqual(state.recent.value, items);
  assert.equal(state.trace.value.execution_id, "new");
  await state.refresh();
  assert.equal(calls.filter(([name]) => name === "getTrace").length, 1);
  state.dispose();

  const followed = fakeApi({ listExecutions: async () => ({ items }) });
  const withOrder = useExecutionEvidence(followed.api, {
    executionId: () => "from-order",
  });
  await withOrder.refresh();
  assert.equal(withOrder.trace.value, null);
  assert.deepEqual(
    followed.calls.map(([name]) => name),
    ["listExecutions"],
  );
  withOrder.dispose();

  const failing = useExecutionEvidence(
    fakeApi({
      listExecutions: async () => {
        throw new Error("list offline");
      },
    }).api,
  );
  await failing.refresh();
  assert.equal(failing.error.value, "list offline");
  failing.dispose();
});

test("readFile ignores a slower earlier file response", async () => {
  const files = {};
  const { api } = fakeApi({
    getSnapshotFile: (id, path) => {
      files[path] = deferred();
      return files[path].promise;
    },
  });
  const state = useExecutionEvidence(api);
  const inspecting = state.inspect("e1");
  await flush();
  files["skills/a/SKILL.md"].resolve({ content: "a" });
  await inspecting;
  state.path.value = "skills/b.md";
  const slow = state.readFile();
  state.path.value = "skills/a/SKILL.md";
  const fast = state.readFile();
  files["skills/a/SKILL.md"].resolve({ content: "a2" });
  await fast;
  files["skills/b.md"].resolve({ content: "b" });
  await slow;
  assert.equal(state.file.value.content, "a2");
  state.dispose();
});

test("publish fixes a fixture once at a time and reports the new snapshot id", async () => {
  const published = [];
  let isLocked = false;
  const gate = deferred();
  const { api, calls } = fakeApi({
    publishSnapshot: async (fixture) => {
      await gate.promise;
      return { snapshot_id: `pub-${fixture}`, metadata: { fixture } };
    },
  });
  const state = useExecutionEvidence(api, {
    locked: () => isLocked,
    onPublished: (id) => published.push([id, state.publishing.value]),
  });
  state.error.value = "old";
  const pending = state.publish("baseline");
  assert.equal(state.publishing.value, true);
  assert.equal(state.error.value, "");
  await state.publish("repaired");
  gate.resolve();
  await pending;
  assert.deepEqual(published, [["pub-baseline", true]]);
  assert.equal(state.published.value.snapshot_id, "pub-baseline");
  assert.equal(state.publishing.value, false);
  isLocked = true;
  await state.publish("repaired");
  assert.equal(calls.filter(([name]) => name === "publishSnapshot").length, 1);
  state.dispose();

  const failing = useExecutionEvidence(
    fakeApi({
      publishSnapshot: async () => {
        throw new Error("publish failed");
      },
    }).api,
  );
  await failing.publish("baseline");
  assert.equal(failing.error.value, "publish failed");
  assert.equal(failing.publishing.value, false);
  assert.equal(failing.published.value, null);
  failing.dispose();
});

test("dispose ignores late responses and aborts the shared signal", async () => {
  const trace = deferred();
  let signal;
  const { api } = fakeApi({
    getTrace: (id, options) => {
      signal = options.signal;
      return trace.promise;
    },
  });
  const state = useExecutionEvidence(api);
  const pending = state.inspect("e1");
  state.dispose();
  assert.equal(signal.aborted, true);
  trace.resolve(traceOf("e1"));
  await pending;
  assert.equal(state.trace.value, null);
  assert.equal(state.loading.value, true);
});

test("event labels, error marks, clock and JSON follow the trace panel rules", () => {
  assert.equal(Object.keys(EVENT_KIND_TEXT).length, 21);
  assert.equal(eventKindLabel("SKILL_LOADED"), "실제 파일 로드");
  assert.equal(eventKindLabel("TOOL_REQUEST"), "도구 요청");
  assert.equal(eventKindLabel("SKILL_STARTED"), "SKILL_STARTED");
  assert.equal(isErrorEvent("TOOL_ERROR"), true);
  assert.equal(isErrorEvent("SKILL_LOAD_REJECTED"), true);
  assert.equal(isErrorEvent("SKILL_LOADED"), false);
  assert.equal(eventClock("2026-10-21T08:51:28.123456Z"), "08:51:28.123");
  assert.equal(eventClock("2026-10-21T08:51:28.1Z"), "08:51:28.1Z");
  assert.equal(prettyJson({ a: [1] }), '{\n  "a": [\n    1\n  ]\n}');
  assert.equal(prettyJson(undefined), undefined);
});

test("execution, selection, model and cost texts match the template interpolation", () => {
  assert.equal(
    executionOptionText({
      started_at: "2026-10-21T08:51:28Z",
      final_status: "FAILED",
      completion: "COMPLETED",
      execution_id: "3b23f085-c0f8",
    }),
    `${timestamp("2026-10-21T08:51:28Z")} · FAILED · 3b23f085`,
  );
  assert.equal(
    executionOptionText({
      started_at: null,
      final_status: null,
      completion: null,
      execution_id: "abc",
    }),
    "— ·  · abc",
  );
  assert.equal(
    executionOptionText({
      started_at: null,
      final_status: "",
      completion: "RUNNING",
      execution_id: "abcdefghij",
    }),
    "— · RUNNING · abcdefgh",
  );
  assert.equal(
    selectionText({ raw_selection: "order-flow", resolved_skill_id: "order-flow" }),
    "order-flow → order-flow",
  );
  assert.equal(
    selectionText({ raw_selection: null, resolved_skill_id: undefined }),
    "응답 없음 → 해석 없음",
  );
  assert.equal(selectionText({ raw_selection: "", resolved_skill_id: 0 }), " → 0");
  assert.equal(modelText({ model_mode: "fixed", model: "m" }), "고정 응답 · 실제 모델 호출 없음");
  assert.equal(modelText({ model_mode: "live", model: "m" }), "m");
  assert.equal(
    costText({ api_calls: 2, input_tokens: 10, output_tokens: 20, elapsed_ms: 30 }),
    "API 2회 · 입력 10 / 출력 20 토큰 · 30ms",
  );
  assert.equal(
    costText({ api_calls: null, input_tokens: 0, output_tokens: null, elapsed_ms: null }),
    "API 회 · 입력 0 / 출력 미관측 토큰 · 진행 중ms",
  );
});
