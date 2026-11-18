import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests", timeout: 30000, fullyParallel: false, workers: 1,
  use: { baseURL: "http://localhost:3100", viewport: { width: 1440, height: 1050 }, trace: "retain-on-failure" },
  webServer: [
    ...(process.env.TABLEFLOW_TEST_LOCAL_OCR === "true" ? [{
      command: `${process.platform === "win32" ? "../ai-service/.venv/Scripts/python.exe" : "python"} -m uvicorn app.main:app --app-dir ../ai-service --host 127.0.0.1 --port 8000`,
      url: "http://127.0.0.1:8000/health", env: { TABLEFLOW_OCR_PROVIDER: "local" },
      reuseExistingServer: !process.env.CI, timeout: 60000,
    }] : []),
    { command: "java -Xmx768m -jar ../backend/build/libs/tableflow-0.1.0.jar", url: "http://localhost:8080/api/v1/health", reuseExistingServer: !process.env.CI, timeout: 60000 },
    { command: "npm run dev -- --hostname localhost", url: "http://localhost:3100", reuseExistingServer: !process.env.CI, timeout: 60000 },
  ],
});
