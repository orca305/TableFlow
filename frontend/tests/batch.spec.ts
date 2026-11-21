import { test, expect } from "@playwright/test";
import { connect, oracle, ORACLE, PG, pg } from "./helpers";

// 실제 Oracle/PostgreSQL 테스트 컨테이너가 필요합니다(TABLEFLOW_TEST_JDBC=true, infra/jdbc/compose.yml --profile oracle).
test.skip(process.env.TABLEFLOW_TEST_JDBC !== "true", "실제 DB 컨테이너 필요: TABLEFLOW_TEST_JDBC=true");

const dropOracle = `BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UB_C'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UB_E'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UB_P'; EXCEPTION WHEN OTHERS THEN NULL; END;
/`;
const dropPg = `DROP TABLE IF EXISTS tf_ub_c; DROP TABLE IF EXISTS tf_ub_e; DROP TABLE IF EXISTS tf_ub_p; DROP TABLE IF EXISTS tableflow_load_receipts;`;

test.beforeAll(() => {
  oracle(`${dropOracle}
CREATE TABLE TF_UB_P (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(40));
CREATE TABLE TF_UB_C (ID NUMBER(10) PRIMARY KEY, PID NUMBER(10), NOTE VARCHAR2(40), CONSTRAINT FK_TF_UB_C FOREIGN KEY (PID) REFERENCES TF_UB_P(ID));
CREATE TABLE TF_UB_E (ID NUMBER(10) PRIMARY KEY);
INSERT INTO TF_UB_P VALUES (1,'부모1'); 
INSERT INTO TF_UB_P VALUES (2,'부모2'); 
INSERT INTO TF_UB_P VALUES (3,'부모3');
INSERT INTO TF_UB_C VALUES (1,1,'자식1'); 
INSERT INTO TF_UB_C VALUES (2,2,'자식2'); 
INSERT INTO TF_UB_C VALUES (3,3,'자식3'); 
INSERT INTO TF_UB_C VALUES (4,1,'자식4');
COMMIT;`);
  pg(dropPg);
});
test.afterAll(() => {
  try { oracle(dropOracle); } catch {}
  try { pg(dropPg); } catch {}
});

test("real browser: 일괄 이관 — 부모 테이블 먼저 순서대로 생성·적재하고 FK를 복제한다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("navigation", { name: "메뉴" }).getByRole("button", { name: "일괄 이관", exact: true }).click();
  const src = page.getByRole("region", { name: "일괄 이관 원본" });
  await connect(src, "원본", ORACLE);
  await src.getByLabel("일괄 원본 스키마", { exact: true }).selectOption("TABLEFLOW");
  await src.getByLabel("테이블 검색", { exact: true }).fill("TF_UB_");
  await src.getByRole("checkbox", { name: "전체 선택" }).check(); // 검색 결과 3개만 선택된다
  await expect(src.getByRole("status").filter({ hasText: "3개 선택" })).toBeVisible();
  const dst = page.getByRole("region", { name: "일괄 이관 대상" });
  await connect(dst, "대상", PG);
  await dst.getByLabel("일괄 대상 스키마", { exact: true }).selectOption("public");

  const run = page.getByRole("region", { name: "일괄 이관 실행" });
  await run.getByRole("button", { name: "사전 점검" }).click();
  const rows = run.getByRole("table", { name: "일괄 이관 점검 결과" }).locator("tbody tr");
  await expect(rows).toHaveCount(3);
  await expect(rows.last()).toContainText("TF_UB_C"); // 자식은 마지막
  await expect(rows.last()).toContainText("tf_ub_c"); // 대상 이름은 PostgreSQL 관례로 변환
  await expect(run.getByText("이관할 7행")).toBeVisible();
  const start = run.getByRole("button", { name: "이관 시작" });
  await expect(start).toBeDisabled();
  await run.getByRole("checkbox", { name: /CREATE TABLE.*동의/ }).check();
  await start.click();
  const progress = run.getByRole("status", { name: "일괄 이관 진행 현황" });
  await expect(progress).toContainText("완료 · 테이블 3 / 3개", { timeout: 60_000 });
  await expect(progress.getByRole("table", { name: "테이블별 진행 현황" }).locator("tbody tr")).toHaveCount(3);

  expect(pg("SELECT count(*) FROM tf_ub_p;").trim()).toBe("3");
  expect(pg("SELECT count(*) FROM tf_ub_c;").trim()).toBe("4");
  expect(pg("SELECT count(*) FROM tf_ub_e;").trim()).toBe("0"); // 빈 테이블도 생성된다
  expect(pg("SELECT count(*) FROM information_schema.table_constraints WHERE constraint_type='FOREIGN KEY' AND table_name='tf_ub_c';").trim()).toBe("1");

  // 다시 점검하면 이미 있는 테이블은 건너뛰는 것으로 표시된다
  await run.getByRole("button", { name: "사전 점검" }).click();
  await expect(rows.first()).toContainText("건너뜀");
});
