import { test, expect } from "@playwright/test";
import { confirmExistingTarget, confirmSource, connectTarget, oracle, openHistory, pg, receipts, runPanel } from "./helpers";

// README 시연 스크린샷 생성용. 기본 실행에서는 건너뜁니다.
// SCREENSHOTS=true (DB 이관 화면은 TABLEFLOW_TEST_JDBC=true + 실제 컨테이너 + JDBC 플래그 필요)
test.skip(process.env.SCREENSHOTS !== "true", "SCREENSHOTS=true 일 때만 실행");
const out = (name: string) => `../docs/screenshots/${name}.png`;

test("screenshot: sample CSV → validated workspace (라이트/다크)", async ({ page }) => {
  await page.goto("/#file");
  await page.getByRole("button", { name: "샘플 데이터로 시작", exact: true }).click();
  await expect(page.getByLabel("1행 이름", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
  await expect(page.getByText(/모든 검증을 통과했습니다/)).toBeVisible();
  await page.screenshot({ path: out("01-workspace-validated"), fullPage: true });
  await page.getByRole("button", { name: "다크 모드" }).click();
  await page.screenshot({ path: out("01-workspace-validated-dark"), fullPage: true });
});

test("screenshots: DB 이관 사전 점검, 신규 테이블 DDL 미리보기, 분할 이관 진행 현황", async ({ page }) => {
  test.skip(process.env.TABLEFLOW_TEST_JDBC !== "true", "실제 DB 필요");
  test.setTimeout(120_000);
  oracle(`BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UI_SRC'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UI_BIG'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE TF_UI_SRC (EMP_NO VARCHAR2(20) PRIMARY KEY, EMP_NM VARCHAR2(100) NOT NULL, HIRE_DATE DATE NOT NULL, SALARY NUMBER(20,2) NOT NULL);
INSERT INTO TF_UI_SRC VALUES ('001','Kim',DATE '2026-01-15',3500000.50);
INSERT INTO TF_UI_SRC VALUES ('002','Lee',DATE '2026-03-02',4200000);
CREATE TABLE TF_UI_BIG (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(30), AMT NUMBER(12,2));
INSERT INTO TF_UI_BIG SELECT LEVEL, 'n'||LEVEL, LEVEL+0.25 FROM DUAL CONNECT BY LEVEL <= 12500;
COMMIT;`);
  pg(`DROP TABLE IF EXISTS tf_ui_dst; DROP TABLE IF EXISTS tf_ui_new; DROP TABLE IF EXISTS tf_ui_big; DROP TABLE IF EXISTS tableflow_load_receipts;
${receipts}
CREATE TABLE tf_ui_dst (empno varchar(20) PRIMARY KEY, emp_name varchar(100) NOT NULL, hiredate date NOT NULL, pay numeric(20,2) NOT NULL);
CREATE TABLE tf_ui_big (id integer PRIMARY KEY, name varchar(30), amt numeric(12,2));`);
  try {
    // 1) 기존 테이블로 사전 점검
    await confirmSource(page, "TF_UI_SRC");
    let review = await confirmExistingTarget(page, "tf_ui_dst");
    await review.getByLabel("EMP_NM 대상 컬럼", { exact: true }).selectOption("emp_name");
    await review.getByLabel("SALARY 대상 컬럼", { exact: true }).selectOption("pay");
    await runPanel(page).getByRole("button", { name: "사전 점검" }).click();
    await expect(runPanel(page).getByText(/원본 2행 중 2행을 이관합니다/)).toBeVisible();
    await page.screenshot({ path: out("02-db-to-db-dry-run"), fullPage: true });

    // 2) 신규 테이블 생성: CREATE TABLE 미리보기(아직 아무것도 만들지 않음)
    await confirmSource(page, "TF_UI_SRC");
    const dst = await connectTarget(page);
    await dst.getByLabel("신규 테이블 이름").fill("tf_ui_new");
    await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
    await expect(page.getByRole("region", { name: "검토" }).locator("pre.ddl-preview").first()).toBeVisible();
    await runPanel(page).getByRole("button", { name: "사전 점검" }).click();
    await expect(runPanel(page).getByText(/표시된 CREATE TABLE 실행과 DB INSERT에 동의합니다/)).toBeVisible();
    await page.screenshot({ path: out("03-db-to-db-create-table-preview"), fullPage: true });

    // 3) 대용량(12,500행) 분할 이관: 진행 현황 + 작업 이력
    await confirmSource(page, "TF_UI_BIG");
    review = await confirmExistingTarget(page, "tf_ui_big");
    await runPanel(page).getByRole("button", { name: "사전 점검" }).click();
    await expect(runPanel(page).getByText(/대용량: 3회 분할/)).toBeVisible();
    await runPanel(page).getByRole("checkbox", { name: /DB INSERT에 동의/ }).check();
    await runPanel(page).getByRole("button", { name: "이관 시작" }).click();
    await expect(page.getByText("이관 완료: 12,500행", { exact: true })).toBeVisible({ timeout: 60_000 });
    await page.screenshot({ path: out("04-db-to-db-chunked-done"), fullPage: true });
    const history = await openHistory(page);
    await expect(history.getByRole("row", { name: /TF_UI_BIG/ }).first()).toBeVisible();
    await page.screenshot({ path: out("05-history"), fullPage: true });
  } finally {
    try { oracle(`DROP TABLE TF_UI_SRC;\nDROP TABLE TF_UI_BIG;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_ui_dst; DROP TABLE IF EXISTS tf_ui_new; DROP TABLE IF EXISTS tf_ui_big; DROP TABLE IF EXISTS tableflow_load_receipts;`); } catch {}
  }
});
