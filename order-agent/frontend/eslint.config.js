// order-agent 주문 데모 화면(독립 Vue 앱)의 ESLint flat 설정이다.
// @eslint/js 의 recommended 규칙과 eslint-plugin-vue 의 flat/essential 규칙을 적용한다.
// flat/essential 은 컴포넌트 이름을 여러 단어로 짓고 v-for 에 key 를 달라고 요구한다.
// order-agent/frontend/package.json 의 lint 스크립트(eslint .)가 이 설정을 읽는다.
import js from "@eslint/js";
import vue from "eslint-plugin-vue";
import globals from "globals";

export default [
  // 빌드 결과, 의존성, Playwright 결과물 폴더는 검사하지 않는다.
  {
    ignores: [
      "dist/**",
      "node_modules/**",
      "test-results/**",
      "playwright-report/**",
    ],
  },
  js.configs.recommended,
  ...vue.configs["flat/essential"],
  // src 는 브라우저에서 돌고 설정 파일과 tests 는 Node 에서 돌므로 두 환경의 전역 변수를 모두 허용한다.
  { languageOptions: { globals: { ...globals.browser, ...globals.node } } },
];
