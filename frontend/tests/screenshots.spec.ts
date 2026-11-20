import {test, expect, type Page} from "@playwright/test";
import {execSync} from "node:child_process";

// README 시연 스크린샷 생성용. 기본 실행에서는 건너뜁니다.
// SCREENSHOTS=true (DB→DB 화면은 TABLEFLOW_TEST_JDBC=true + 실제 컨테이너 + JDBC 플래그 필요)
test.skip(process.env.SCREENSHOTS !== "true", "SCREENSHOTS=true 일 때만 실행");
const out = (name: string) => `../docs/screenshots/${name}.png`;
const oracle = (sql: string) => execSync("docker exec -i tableflow-jdbc-tests-oracle-1 sqlplus -s tableflow/TableFlowTest2026@//localhost/FREEPDB1", {input: sql + "\nexit\n"}).toString();
const pg = (sql: string) => execSync("docker exec -i tableflow-jdbc-tests-postgresql-1 psql -U tableflow -d tableflow -t -A", {input: sql}).toString();
const receipts = `CREATE TABLE IF NOT EXISTS tableflow_load_receipts (request_id varchar(36) PRIMARY KEY, payload_hash varchar(64) NOT NULL, table_name varchar(128) NOT NULL, inserted_rows integer NOT NULL);`;

async function openMigration(page: Page, srcTable: string) {
  await page.goto("/");
  await page.getByRole("button", {name: "DB → DB 이전", exact: true}).click();
  const panel = page.getByLabel("DB → DB 이전", {exact: true});
  const fill = async (side: string, port: string, db: string) => {
    await panel.getByLabel(`${side} 포트`, {exact: true}).fill(port);
    await panel.getByLabel(`${side} 데이터베이스명`, {exact: true}).fill(db);
    await panel.getByLabel(`${side} 사용자`, {exact: true}).fill("tableflow");
    await panel.getByLabel(`${side} 비밀번호`, {exact: true}).fill("TableFlowTest2026");
    await panel.getByRole("group", {name: `${side} DB`}).getByRole("checkbox").uncheck();
  };
  await fill("원본", "11521", "FREEPDB1");
  await panel.getByLabel("대상 DBMS", {exact: true}).selectOption("postgresql");
  await fill("대상", "15432", "tableflow");
  await panel.getByRole("button", {name: "원본 연결·스키마 조회"}).click();
  await panel.getByLabel("원본 스키마", {exact: true}).selectOption("TABLEFLOW");
  await panel.getByLabel("원본 테이블", {exact: true}).selectOption(srcTable);
  await panel.getByRole("button", {name: "대상 연결·스키마 조회"}).click();
  await panel.getByLabel("대상 스키마", {exact: true}).selectOption("public");
  return panel;
}

test("screenshot: sample CSV → validated workspace → SQL export", async ({page}) => {
  await page.goto("/");
  await page.getByRole("button", {name: "샘플 데이터로 시작", exact: true}).click();
  await expect(page.getByLabel("1행 이름", {exact: true})).toBeVisible();
  await page.getByRole("button", {name: "데이터 검증", exact: true}).click();
  await expect(page.getByText(/모든 검증을 통과했습니다/)).toBeVisible();
  await page.screenshot({path: out("01-workspace-validated"), fullPage: true});
});

test("screenshots: DB → DB dry-run, create-table DDL preview, chunked migration progress", async ({page}) => {
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
    // 1) 기존 테이블로 Dry-run
    let panel = await openMigration(page, "TF_UI_SRC");
    await panel.getByLabel("대상 테이블", {exact: true}).selectOption("tf_ui_dst");
    await panel.getByLabel("EMP_NM 대상 컬럼", {exact: true}).selectOption("emp_name");
    await panel.getByLabel("SALARY 대상 컬럼", {exact: true}).selectOption("pay");
    await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
    await expect(panel.getByText(/원본 2행 중 2행을 이전합니다/)).toBeVisible();
    await panel.screenshot({path: out("02-db-to-db-dry-run")});

    // 2) 새 테이블 만들기: CREATE TABLE 미리보기(아직 아무것도 만들지 않음)
    panel = await openMigration(page, "TF_UI_SRC");
    await panel.getByRole("checkbox", {name: /새 테이블 만들기/}).check();
    await panel.getByLabel("대상 새 테이블 이름", {exact: true}).fill("tf_ui_new");
    await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
    await expect(panel.getByText(/표시된 CREATE TABLE 실행과 DB INSERT에 동의합니다/)).toBeVisible();
    await panel.screenshot({path: out("03-db-to-db-create-table-preview")});

    // 3) 대용량(12,500행) 청크 이전: 진행률 + 작업 이력
    panel = await openMigration(page, "TF_UI_BIG");
    await panel.getByLabel("대상 테이블", {exact: true}).selectOption("tf_ui_big");
    await panel.getByLabel("최대 이전 행 수", {exact: true}).fill("20000");
    await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
    await expect(panel.getByText(/대용량: 3개 청크/)).toBeVisible();
    await panel.getByRole("checkbox", {name: /DB INSERT에 동의/}).check();
    await panel.getByRole("button", {name: "선택한 대상에 이전 실행"}).click();
    await expect(panel.getByText("이전 완료: 12,500행", {exact: true})).toBeVisible({timeout: 60_000});
    await expect(panel.getByLabel("작업 이력", {exact: true}).getByRole("row", {name: /TF_UI_BIG/}).first()).toBeVisible();
    await panel.screenshot({path: out("04-db-to-db-chunked-done-history")});
  } finally {
    try { oracle(`DROP TABLE TF_UI_SRC;\nDROP TABLE TF_UI_BIG;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_ui_dst; DROP TABLE IF EXISTS tf_ui_new; DROP TABLE IF EXISTS tf_ui_big; DROP TABLE IF EXISTS tableflow_load_receipts;`); } catch {}
  }
});
