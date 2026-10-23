// frontend 의 ESLint flat 설정이다. frontend/package.json 의 lint:eslint 스크립트가 읽고,
// lint 스크립트는 lint:oxlint 다음에 이 검사를 차례로 돌린다.
// 모든 파일을 브라우저 전역으로 검사하고, frontend/unit 의 node --test 단위 테스트에는
// Node 전역을 더한다.
import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import pluginOxlint from 'eslint-plugin-oxlint'

export default defineConfig([
  // 검사할 파일 확장자다.
  {
    name: 'app/files-to-lint',
    files: ['**/*.{vue,js,mjs,jsx}'],
  },

  // 빌드 결과와 커버리지 보고서는 검사하지 않는다.
  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**']),

  // 화면 코드는 브라우저에서 돈다. 설정 파일도 이 전역으로 검사하므로 frontend/vite.config.js 는
  // process 를 node:process 에서 직접 가져온다.
  {
    languageOptions: {
      globals: {
        ...globals.browser,
      },
    },
  },

  // frontend/unit 의 단위 테스트는 frontend/package.json 의 test:unit(node --test)이 Node 에서
  // 돌리므로 Node 전역을 더한다.
  {
    name: 'app/unit-tests',
    files: ['unit/**/*.js'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },

  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],

  // frontend/.oxlintrc.json 설정으로 oxlint 가 이미 검사하는 규칙을 ESLint 쪽에서 꺼서 같은
  // 문제를 두 번 알리지 않게 한다. 이 항목은 앞의 규칙 설정을 덮어야 하므로 맨 뒤에 둔다.
  ...pluginOxlint.buildFromOxlintConfigFile('.oxlintrc.json'),
])
