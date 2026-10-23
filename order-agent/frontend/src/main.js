// order-agent 주문 데모 화면의 진입점이다.
// order-agent/frontend/src/App.vue 로 Vue 앱을 만들고, 전역 스타일 order-agent/frontend/src/styles/main.css 를 불러온 뒤
// order-agent/frontend/index.html 의 #app 에 붙인다. index.html 이 이 파일을 module script 로 싣는다.
// main.css 는 상태값을 소문자로 바꾼 클래스(.pill.hold, .skipped .step-dot 같은)로 상태별 색을 정한다.
// e2e 도 .pill.shipped 처럼 같은 규칙으로 만든 클래스로 화면을 찾는다.
import { createApp } from "vue";
import App from "./App.vue";
import "./styles/main.css";

createApp(App).mount("#app");
