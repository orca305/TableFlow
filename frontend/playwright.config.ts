import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests", timeout: 30000, fullyParallel: false, workers: 1,
  use: { baseURL: "http://localhost:3101", viewport: { width: 1440, height: 1050 }, trace: "retain-on-failure" },
  webServer: [
    ...(process.env.TABLEFLOW_TEST_LOCAL_OCR === "true" ? [{
      command: `${process.platform === "win32" ? "../ai-service/.venv/Scripts/python.exe" : "python"} -m uvicorn app.main:app --app-dir ../ai-service --host 127.0.0.1 --port 8000`,
      url: "http://127.0.0.1:8000/health", env: { TABLEFLOW_OCR_PROVIDER: "local" },
      reuseExistingServer: !process.env.CI, timeout: 60000,
    }] : []),
    { command: "java -Xmx768m -jar ../backend/build/libs/tableflow-0.1.0.jar --server.port=18080 --tableflow.frontend-origin=http://localhost:3101", url: "http://localhost:18080/api/v1/health", reuseExistingServer: !process.env.CI, timeout: 60000 },
    { command: "npx next start --port 3101 --hostname localhost", url: "http://localhost:3101", env: { NEXT_PUBLIC_API_URL: "http://localhost:18080/api/v1" }, reuseExistingServer: !process.env.CI, timeout: 60000 },
  ],
});
