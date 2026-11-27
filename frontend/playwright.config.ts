import { defineConfig } from "@playwright/test";
import path from "node:path";
// 상대 경로 실행 파일은 Windows 셸에서 해석되지 않는 경우가 있어 절대 경로로 만든다.
// CI(Linux)는 setup-python의 python을 그대로 쓰므로 가상환경 경로는 Windows에서만 쓴다.
const python = process.platform === "win32" ? `"${path.resolve("../ai-service/.venv/Scripts/python.exe")}"` : "python";
// 백엔드는 상태 DB가 있어야 시작한다. 지정하지 않으면 전용 테스트 컨테이너(infra/state/compose.yml)를 쓴다.
const stateDb = {
  TABLEFLOW_STATE_DB_URL: process.env.TABLEFLOW_STATE_DB_URL ?? "jdbc:postgresql://127.0.0.1:15443/tableflow",
  TABLEFLOW_STATE_DB_USER: process.env.TABLEFLOW_STATE_DB_USER ?? "tableflow",
  TABLEFLOW_STATE_DB_PASSWORD: process.env.TABLEFLOW_STATE_DB_PASSWORD ?? "TableFlowTest2026",
};
export default defineConfig({
  testDir: "./tests",
  // 실제 DB·OCR을 쓰는 e2e라 메모리가 부족한 PC에서는 응답이 수십 초 멈출 수 있어 대기 시간에 여유를 둔다.
  timeout: 90000, expect: { timeout: 15000 }, fullyParallel: false, workers: 1,
  use: { baseURL: "http://localhost:3101", viewport: { width: 1440, height: 1050 }, trace: "retain-on-failure" },
  // 전체 시나리오를 연달아 실행하면 기본 요청 제한(1분 120건)을 넘으므로 테스트 서버만 한도를 올린다. 제한 동작은 백엔드 테스트가 확인한다.
  webServer: [
    ...(process.env.TABLEFLOW_TEST_LOCAL_OCR === "true" ? [{
      command: `${python} -m uvicorn app.main:app --app-dir ../ai-service --host 127.0.0.1 --port 8000`,
      url: "http://127.0.0.1:8000/health", env: { TABLEFLOW_OCR_PROVIDER: "local" },
      reuseExistingServer: !process.env.CI, timeout: 60000,
    }] : []),
    { command: "java -Xmx768m -jar ../backend/build/libs/tableflow-0.1.0.jar --server.port=18080 --tableflow.frontend-origin=http://localhost:3101 --tableflow.api.posts-per-minute=1000", url: "http://localhost:18080/api/v1/health", reuseExistingServer: !process.env.CI, timeout: 60000, env: stateDb },
    { command: "npx next start --port 3101 --hostname localhost", url: "http://localhost:3101", env: { NEXT_PUBLIC_API_URL: "http://localhost:18080/api/v1" }, reuseExistingServer: !process.env.CI, timeout: 60000 },
  ],
});
