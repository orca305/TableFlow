import { defineConfig } from "@playwright/test";
import path from "node:path";
// 상대 경로 실행 파일은 Windows 셸에서 해석되지 않는 경우가 있어 절대 경로로 만든다.
// CI(Linux)는 setup-python의 python을 그대로 쓰므로 가상환경 경로는 Windows에서만 쓴다.
const python = process.platform === "win32" ? `"${path.resolve("../ai-service/.venv/Scripts/python.exe")}"` : "python";
export default defineConfig({
  testDir: "./tests",
  // 실제 DB·OCR을 쓰는 e2e라 메모리가 부족한 PC에서는 응답이 수십 초 멈출 수 있어 대기 시간에 여유를 둔다.
  timeout: 90000, expect: { timeout: 15000 }, fullyParallel: false, workers: 1,
  use: { baseURL: "http://localhost:3101", viewport: { width: 1440, height: 1050 }, trace: "retain-on-failure" },
  webServer: [
    ...(process.env.TABLEFLOW_TEST_LOCAL_OCR === "true" ? [{
      command: `${python} -m uvicorn app.main:app --app-dir ../ai-service --host 127.0.0.1 --port 8000`,
      url: "http://127.0.0.1:8000/health", env: { TABLEFLOW_OCR_PROVIDER: "local" },
      reuseExistingServer: !process.env.CI, timeout: 60000,
    }] : []),
    { command: "java -Xmx768m -jar ../backend/build/libs/tableflow-0.1.0.jar --server.port=18080 --tableflow.frontend-origin=http://localhost:3101", url: "http://localhost:18080/api/v1/health", reuseExistingServer: !process.env.CI, timeout: 60000 },
    { command: "npx next start --port 3101 --hostname localhost", url: "http://localhost:3101", env: { NEXT_PUBLIC_API_URL: "http://localhost:18080/api/v1" }, reuseExistingServer: !process.env.CI, timeout: 60000 },
  ],
});
