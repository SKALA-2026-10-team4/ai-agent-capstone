<script setup>
// 개발 요청서 카드다. 담당자 수정이 어떤 업무 도구도 만들지 않는 값을 요구해 SKILL.md 수정만으로는 고칠 수
// 없을 때, SkillOps 가 제안서에 담은 development_request 를 보여 준다. 모양은
// docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절과 docs/contracts/proposal.schema.json 이다.
// 필요한 값(gaps), 제안 방법(options), 바꿀 곳(change_points), 임시 대안(interim), 근거 실행을 차례로 그리고,
// 증명·근거 실행은 번호 대신 frontend/src/components/RunRecordList.vue 의 실행 기록 타임라인으로 보여 준다.
// '마크다운으로 복사' 버튼은 개발팀에 그대로 보낼 한국어 마크다운을 클립보드에 넣는다. 이름표와 마크다운은
// frontend/src/utils/developmentRequest.js 가 만든다. 글은 모두 텍스트 보간으로만 넣는다.
// frontend/src/views/ProposalView.vue 와 frontend/src/views/DiagnosisView.vue 가 쓴다.
import { computed } from 'vue'
import { ClipboardCopy, Wrench } from 'lucide-vue-next'
import { useToastStore } from '@/stores/toast'
import { copyText } from '@/utils/clipboard'
import {
  developmentRequestMarkdown,
  gapFieldLabel,
  gapKindLabel,
  optionProduces,
  optionText,
  optionTypeLabel,
} from '@/utils/developmentRequest'
import RunRecordList from './RunRecordList.vue'

// request 는 development_request 칸이고, context 는 마크다운의 배경 절에 넣을 맥락
// (frontend/src/utils/developmentRequest.js 의 markdownContextOf 모양)이다.
const props = defineProps({
  request: { type: Object, required: true },
  context: { type: Object, default: () => ({}) },
})
const toast = useToastStore()

const options = computed(() => props.request.options ?? [])
const changePoints = computed(() => props.request.change_points ?? [])
const interim = computed(() => props.request.interim ?? null)
const evidence = computed(() => props.request.evidence_run_ids ?? [])

// '마크다운으로 복사' 버튼이 부른다. 클립보드는 frontend/src/utils/clipboard.js 의 copyText 가 다룬다.
// 마크다운에는 개발팀이 서버 기록을 찾을 수 있도록 원래 id 를 그대로 넣는다. 결과는 토스트로 알린다.
async function copy() {
  try {
    await copyText(developmentRequestMarkdown(props.request, props.context))
    toast.show('개발 요청서를 마크다운으로 복사했어요. 이슈나 메신저에 붙여 넣어 주세요.')
  } catch {
    toast.show('복사하지 못했어요. 브라우저의 클립보드 권한을 확인해 주세요.', 'err')
  }
}
</script>

<template>
  <section id="dev-request" class="card dev">
    <div class="head">
      <h2 class="sec"><Wrench :size="18" /> 개발 요청서</h2>
      <button type="button" class="primary sm" @click="copy"><ClipboardCopy :size="14" /> 마크다운으로 복사</button>
    </div>
    <p class="title">{{ request.title }}</p>
    <p class="small muted">SKILL.md 수정만으로는 고칠 수 없어요. 아래 내용을 개발팀에 전달해 업무 도구를 만들거나 넓혀 주세요.</p>

    <h3>필요한 값</h3>
    <table class="gaps">
      <thead>
        <tr>
          <th>값</th>
          <th>필요로 하는 Skill</th>
          <th>칸</th>
          <th>종류</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(g, i) in request.gaps" :key="i">
          <td><code>{{ g.key }}</code></td>
          <td>{{ g.needed_by }}</td>
          <td :title="g.field">{{ gapFieldLabel(g.field) }}</td>
          <td><span class="tag" :class="g.kind">{{ gapKindLabel(g.kind) }}</span></td>
        </tr>
      </tbody>
    </table>

    <template v-if="options.length">
      <h3>제안 방법 <span class="small muted">(하나를 골라 개발해 주세요)</span></h3>
      <ol class="options">
        <li v-for="(o, i) in options" :key="i">
          <span class="tag">{{ optionTypeLabel(o.type) }}</span>
          <span>{{ optionText(o) }}</span>
          <span v-if="o.type === 'new_tool' && optionProduces(o).length" class="small muted">
            내야 할 값: <code v-for="k in optionProduces(o)" :key="k">{{ k }}</code>
          </span>
        </li>
      </ol>
    </template>

    <template v-if="changePoints.length">
      <h3>바꿀 곳</h3>
      <ul class="paths">
        <li v-for="p in changePoints" :key="p"><code>{{ p }}</code></li>
      </ul>
    </template>

    <template v-if="interim">
      <h3>임시 대안</h3>
      <p v-if="interim.summary" class="para">{{ interim.summary }}</p>
      <pre v-if="interim.patch" class="patch">{{ interim.patch }}</pre>
      <template v-if="interim.proof_run_ids?.length">
        <p class="small muted runs-title">증명 실행 · 이 변경을 되돌리면 통과</p>
        <RunRecordList :refs="interim.proof_run_ids" />
      </template>
    </template>

    <template v-if="evidence.length">
      <h3>근거 실행</h3>
      <RunRecordList :refs="evidence" />
    </template>
  </section>
</template>

<style scoped>
.dev {
  border-color: rgb(246 207 122 / 28%);
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.sec {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.title {
  margin: 14px 0 4px;
  font-family: var(--display);
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

h3 {
  margin: 22px 0 10px;
  color: var(--text-2);
}

.para {
  margin: 0 0 8px;
  line-height: 1.6;
}

.gaps {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

th,
td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  overflow-wrap: anywhere;
}

th {
  color: var(--muted);
  font-weight: 600;
}

.tag {
  display: inline-block;
  padding: 1px 9px;
  border-radius: var(--radius-pill);
  background: rgb(255 255 255 / 6%);
  color: var(--text-2);
  font-size: var(--fs-xs);
  font-weight: 600;
  white-space: nowrap;
}

.tag.missing_value,
.tag.tool_output_missing {
  background: var(--warn-soft);
  color: var(--warn);
}

.options {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.options li > span {
  margin-right: 6px;
}

.options li > .small {
  display: block;
}

.paths {
  margin: 0;
  padding-left: 20px;
  font-size: var(--fs-sm);
}

.patch {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-deep);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.runs-title {
  margin: 14px 0 4px;
}

code {
  overflow-wrap: anywhere;
}
</style>
