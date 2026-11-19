import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";
import path from "node:path";

test("CSV upload, mapping, validation, editing and real downloads", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "데이터 변환 워크스페이스" })).toBeVisible();
  await page.screenshot({ path: "test-results/workspace-empty.png", fullPage: true });
  await page.locator('input[type="file"]').setInputFiles(path.resolve("public/samples/employees.csv"));
  await expect(page.getByLabel("1행 사원번호", { exact: true })).toHaveValue("001");
  await page.getByRole("tab", { name: "컬럼 매핑" }).click();
  for (const [source, target, type] of [["사원번호", "EMP_NO", "string"], ["이름", "EMP_NM", "string"], ["입사일", "HIRE_DT", "date"], ["급여", "SALARY", "decimal"]]) {
    await page.getByLabel(`${source} 대상 컬럼명`, { exact: true }).fill(target);
    await page.getByLabel(`${source} 자료형`, { exact: true }).selectOption(type);
    await page.getByLabel(`${source} 필수`, { exact: true }).check();
  }
  await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
  await expect(page.getByText("모든 검증을 통과했습니다. 파일을 내보낼 수 있습니다.")).toBeVisible();
  await page.getByRole("tab", { name: "데이터 미리보기" }).click();
  await expect(page.getByLabel("1행 급여", { exact: true })).toHaveValue("3500000");
  await page.getByLabel("1행 이름", { exact: true }).fill("");
  await expect(page.getByRole("button", { name: "SQL 내보내기" })).toBeDisabled();
  await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
  await expect(page.getByText("필수값입니다.", { exact: true })).toBeVisible();
  await expect(page.getByLabel("1행 이름", { exact: true })).toHaveAttribute("aria-invalid", "true");
  await page.getByLabel("1행 이름", { exact: true }).fill("O'Brien");
  await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
  await expect(page.getByText("모든 검증을 통과했습니다. 파일을 내보낼 수 있습니다.")).toBeVisible();
  const oracleDownload = page.waitForEvent("download");
  await page.getByRole("button", { name: "SQL 내보내기" }).click();
  const oracle = await oracleDownload;
  const sql = await readFile((await oracle.path())!, "utf8");
  expect(sql).toContain("O''Brien"); expect(sql).toContain("TO_DATE('2026-01-15'"); expect(sql).toContain("3500000");
  await page.getByRole("combobox", { name: "대상 DBMS", exact: true }).selectOption("mysql");
  const mysqlDownload = page.waitForEvent("download");
  await page.getByRole("button", { name: "SQL 내보내기" }).click();
  expect(await readFile((await (await mysqlDownload).path())!, "utf8")).toContain("`TB_EMPLOYEES`");
  const excelDownload = page.waitForEvent("download");
  await page.getByRole("button", { name: "Excel 파일", exact: true }).click();
  await page.getByRole("button", { name: "Excel ↓", exact: true }).click();
  expect((await readFile((await (await excelDownload).path())!)).subarray(0, 2).toString()).toBe("PK");
  await page.screenshot({ path: "test-results/workspace-validated.png", fullPage: true });
  expect(errors).toEqual([]);
});

test("API outage shows a recoverable error", async ({ page }) => {
  await page.route("**/api/v1/files/parse", route => route.abort());
  await page.goto("/");
  await page.locator('input[type="file"]').setInputFiles(path.resolve("public/samples/employees.csv"));
  await expect(page.getByRole("alert").filter({ hasText: "API에 연결할 수 없습니다" })).toBeVisible();
  await expect(page.getByRole("button", { name: "파일 선택", exact: true })).toBeEnabled();
});

test("mobile layout fits the viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await expect(page.getByRole("button", { name: "파일 선택", exact: true })).toBeVisible();
  await page.screenshot({ path: "test-results/workspace-mobile.png", fullPage: true });
});

test("sample, duplicate policies, PostgreSQL and CSV downloads", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", {name:"중복 데이터",exact:true}).click();
  await expect(page.getByLabel("1행 이름", {exact:true})).toBeVisible();
  await page.getByRole("button", {name:"데이터 검증",exact:true}).click();
  await expect(page.getByText("경고 · 포함된 컬럼 기준으로 중복된 행입니다.")).toBeVisible();
  await page.getByRole("combobox", {name:"대상 DBMS",exact:true}).selectOption("postgresql");
  const download = page.waitForEvent("download");
  await page.getByRole("button", {name:"SQL 내보내기 ↓",exact:true}).click();
  expect(await readFile((await (await download).path())!, "utf8")).toContain('INSERT INTO "TB_DUPLICATES"');
  await page.getByRole("combobox", {name:"중복 행 처리",exact:true}).selectOption("exclude");
  await page.getByRole("button", {name:"데이터 검증",exact:true}).click();
  await expect(page.getByLabel("3행 이름", {exact:true})).toHaveCount(0);
  await page.getByRole("button", {name:"CSV 파일",exact:true}).click();
  const csv = page.waitForEvent("download");
  await page.getByRole("button", {name:"CSV 내보내기 ↓",exact:true}).click();
  expect(await readFile((await (await csv).path())!, "utf8")).toContain("EMP_NM");
});
