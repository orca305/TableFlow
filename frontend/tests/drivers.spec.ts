import { test, expect } from "@playwright/test";

const driver = { id: "123e4567-e89b-12d3-a456-426614174000", dbms: "postgresql", version: "fixture", sha256: "a".repeat(64), bytes: 100, registeredAt: "2026-11-25T00:00:00Z" };

test("등록 드라이버를 선택하면 연결 요청에 해당 버전을 유지한다", async ({ page }) => {
  await page.route("**/api/v1/connections/drivers", route => route.fulfill({ json: { enabled: true, loadEnabled: true, savedEnabled: false, savePasswordEnabled: false, defaultChunkSize: 5000, drivers: [], installedDrivers: [driver] } }));
  await page.route("**/api/v1/connections/saved", route => route.fulfill({ json: { enabled: false, items: [] } }));
  await page.route("**/api/v1/connections/test", route => route.fulfill({ json: { product: "PostgreSQL", serverVersion: "fixture" } }));
  await page.route("**/api/v1/connections/schemas", route => route.fulfill({ json: { items: ["public"], truncated: false } }));
  await page.goto("/");
  const source = page.getByRole("region", { name: "원본 선택" });
  await source.getByLabel("원본 JDBC 드라이버", { exact: true }).selectOption(driver.id);
  await source.getByLabel("원본 데이터베이스", { exact: true }).fill("fixture");
  await source.getByLabel("원본 사용자", { exact: true }).fill("reader");
  await source.getByLabel("원본 비밀번호", { exact: true }).fill("test-only");
  const request = page.waitForRequest("**/api/v1/connections/test");
  await source.getByRole("button", { name: "연결 확인", exact: true }).click();
  expect((await request).postDataJSON().driverId).toBe(driver.id);
  await expect(source.getByRole("status", { name: "원본 연결 상태" })).toContainText("연결 성공");
});

test("드라이버 관리자 키는 요청 후 비우고 브라우저 저장소에 기록하지 않는다", async ({ page }) => {
  await page.route("**/api/v1/driver-catalog", route => route.fulfill({ json: { items: [driver] } }));
  await page.goto("/#settings");
  const region = page.getByRole("region", { name: "JDBC 드라이버 관리" });
  const key = "test-only-admin-key-with-32-characters";
  await region.getByLabel("관리자 키", { exact: true }).fill(key);
  const request = page.waitForRequest("**/api/v1/driver-catalog");
  await region.getByRole("button", { name: "목록 조회", exact: true }).click();
  expect((await request).headers()["x-tableflow-admin-key"]).toBe(key);
  await expect(region.getByLabel("관리자 키", { exact: true })).toHaveValue("");
  await expect(region.getByRole("cell", { name: "fixture", exact: true })).toBeVisible();
  expect(await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]))).not.toContain(key);
});
