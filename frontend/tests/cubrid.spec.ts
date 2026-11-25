import { test, expect, type Locator } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { pg } from "./helpers";

test.skip(process.env.TABLEFLOW_TEST_CUBRID !== "true", "전용 CUBRID·PostgreSQL 테스트 컨테이너와 JDBC 활성화 필요");
const api = "http://localhost:18080/api/v1";
const tag = `TF_CU_E2E_${randomUUID()}`;
const cubrid = (sql: string) => execFileSync("docker", ["exec", "-i", "-u", "cubrid", "tableflow-jdbc-tests-cubrid-cubrid-1", "csql", "-u", "tableflow", "-p", "TableFlowTest2026", "tableflow"], { input: sql, encoding: "utf8" });

for (const reverse of [false, true]) {
test(`실제 브라우저: ${reverse ? "PostgreSQL → CUBRID" : "CUBRID → PostgreSQL"} 이관과 사후 검증`, async ({ page, request }) => {
  const jar = process.env.TABLEFLOW_TEST_CUBRID_DRIVER;
  const key = process.env.TABLEFLOW_DRIVERS_ADMIN_KEY;
  expect(jar, "정식 CUBRID JAR 경로가 필요합니다").toBeTruthy();
  expect(key, "테스트 전용 관리자 키가 필요합니다").toBeTruthy();
  const registration = await request.post(`${api}/driver-catalog`, {
    headers: { "X-TableFlow-Admin-Key": key! },
    multipart: { dbms: "cubrid", version: "e2e-fixture", file: { name: "cubrid-jdbc.jar", mimeType: "application/java-archive", buffer: await readFile(jar!) } },
  });
  expect(registration.ok()).toBeTruthy();
  const driver = await registration.json();

  try {
    cubrid("DROP TABLE IF EXISTS tableflow.tf_cu_ui_dst; CREATE TABLE tableflow.tf_cu_ui_dst(id BIGINT PRIMARY KEY,name VARCHAR(80),amount DECIMAL(12,2)); DROP TABLE IF EXISTS tableflow.tf_cu_ui_src; CREATE TABLE tableflow.tf_cu_ui_src(id BIGINT PRIMARY KEY,name VARCHAR(80),amount DECIMAL(12,2)); INSERT INTO tableflow.tf_cu_ui_src VALUES(1,'한글 이름',12.34),(2,'후행 공백 ',56.78);");
    pg("DROP TABLE IF EXISTS tf_cu_ui_dst; CREATE TABLE tf_cu_ui_dst(id BIGINT PRIMARY KEY,name VARCHAR(80),amount DECIMAL(12,2)); DROP TABLE IF EXISTS tf_cu_ui_src; CREATE TABLE tf_cu_ui_src(id BIGINT PRIMARY KEY,name VARCHAR(80),amount DECIMAL(12,2)); INSERT INTO tf_cu_ui_src VALUES(1,'한글 이름',12.34),(2,'후행 공백 ',56.78);");

    async function connect(region: Locator, role: "원본" | "대상", dbms: string, port: string, driverId = "") {
      await region.getByLabel(`${role} 종류`, { exact: true }).selectOption(dbms);
      await region.getByLabel(`${role} JDBC 드라이버`, { exact: true }).selectOption(driverId);
      await region.getByLabel(`${role} 포트`, { exact: true }).fill(port);
      await region.getByLabel(`${role} 데이터베이스`, { exact: true }).fill("tableflow");
      await region.getByLabel(`${role} 사용자`, { exact: true }).fill("tableflow");
      await region.getByLabel(`${role} 비밀번호`, { exact: true }).fill("TableFlowTest2026");
      await region.getByLabel(`${role} 연결 이름`, { exact: true }).fill(`${tag}_${role}`);
      await region.getByRole("button", { name: "연결 확인", exact: true }).click();
      await expect(region.getByRole("status", { name: `${role} 연결 상태` })).toContainText("연결 성공");
    }

    await page.goto("/");
    const source = page.getByRole("region", { name: "원본 선택" });
    await connect(source, "원본", reverse ? "postgresql" : "cubrid", reverse ? "15432" : "13300", reverse ? "" : driver.id);
    await source.getByLabel("원본 스키마", { exact: true }).selectOption(reverse ? "public" : "TABLEFLOW");
    await source.getByLabel("원본 테이블", { exact: true }).selectOption("tf_cu_ui_src");
    await source.getByRole("button", { name: "원본 확정", exact: true }).click();
    const target = page.getByRole("region", { name: "대상 선택" });
    await connect(target, "대상", reverse ? "cubrid" : "postgresql", reverse ? "13300" : "15432", reverse ? driver.id : "");
    if (reverse) await expect(target.getByRole("checkbox", { name: "원본 기본 키(PK) 유지" })).toBeDisabled();
    await target.getByLabel("대상 스키마", { exact: true }).selectOption(reverse ? "TABLEFLOW" : "public");
    await target.getByRole("radio", { name: "기존 테이블에 적재" }).check();
    await target.getByLabel("대상 테이블", { exact: true }).selectOption("tf_cu_ui_dst");
    await target.getByRole("button", { name: "대상 확정", exact: true }).click();
    const run = page.getByRole("region", { name: "이관 실행" });
    await run.getByRole("button", { name: "사전 점검", exact: true }).click();
    await expect(run.getByText(/원본 2행 중 2행을 이관합니다/)).toBeVisible();
    await run.getByRole("checkbox", { name: /실제 DB INSERT에 동의/ }).check();
    await run.getByRole("button", { name: "이관 시작", exact: true }).click();
    await expect(page.getByText("이관 완료: 2행", { exact: true })).toBeVisible();
    await expect(run.getByRole("region", { name: "이관 결과 검증" })).toContainText("원본과 대상이 일치합니다");
    if (reverse) expect(cubrid("SELECT name FROM tableflow.tf_cu_ui_dst WHERE id=1;")).toContain("한글 이름");
    else expect(pg("SELECT name||'|'||amount FROM tf_cu_ui_dst WHERE id=1;").trim()).toBe("한글 이름|12.34");
  } finally {
    cubrid("DROP TABLE IF EXISTS tableflow.tf_cu_ui_src; DROP TABLE IF EXISTS tableflow.tf_cu_ui_dst;");
    pg("DROP TABLE IF EXISTS tf_cu_ui_src; DROP TABLE IF EXISTS tf_cu_ui_dst;");
    const saved = await (await request.get(`${api}/connections/saved`)).json();
    for (const item of saved.items ?? []) if (item.name.startsWith(tag)) await request.delete(`${api}/connections/saved/${item.id}`);
  }
});

}
