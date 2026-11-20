import {test, expect} from "@playwright/test";
import {execSync} from "node:child_process";
import {readFileSync} from "node:fs";

// 실제 PostgreSQL 테스트 컨테이너에서 행을 추출해 CSV/XLSX로 내려받는 실제 브라우저 E2E.
// TABLEFLOW_TEST_JDBC=true TABLEFLOW_JDBC_ENABLED=true 와 컨테이너(infra/jdbc/compose.yml)가 필요합니다.
test.skip(process.env.TABLEFLOW_TEST_JDBC !== "true", "실제 DB 컨테이너 필요: TABLEFLOW_TEST_JDBC=true");
const pg = (sql: string) => execSync("docker exec -i tableflow-jdbc-tests-postgresql-1 psql -U tableflow -d tableflow -t -A", {input: sql}).toString();

test.beforeAll(() => pg(`DROP TABLE IF EXISTS tf_ui_file;
CREATE TABLE tf_ui_file (emp_no varchar(20) PRIMARY KEY, emp_nm varchar(100), hire_dt date, salary numeric(20,2), memo text);
INSERT INTO tf_ui_file VALUES ('001','김하늘 O''Brien','2026-01-15',3500000.50,'제외할 메모');
INSERT INTO tf_ui_file VALUES ('002','이도윤',NULL,12345678901234567.12,'x');
INSERT INTO tf_ui_file VALUES ('003','=SUM(A1)','2026-06-18',0,'y');`));
test.afterAll(() => { try { pg("DROP TABLE IF EXISTS tf_ui_file;"); } catch {} });

test("real browser: PostgreSQL rows → workbench → CSV and XLSX (Korean, NULL, date, precision, formula guard, column exclusion)", async ({page}) => {
  await page.goto("/");
  await page.getByRole("button", {name: "데이터베이스 연결", exact: true}).click();
  const explorer = page.getByLabel("소스 DB 메타데이터 탐색", {exact: true});
  await explorer.getByLabel("DB 포트", {exact: true}).fill("15432");
  await explorer.getByLabel("데이터베이스명", {exact: true}).fill("tableflow");
  await explorer.getByLabel("DB 사용자", {exact: true}).fill("tableflow");
  await explorer.getByLabel("DB 비밀번호", {exact: true}).fill("TableFlowTest2026");
  await explorer.getByRole("checkbox", {name: /TLS 사용/}).uncheck();
  await explorer.getByRole("button", {name: "연결 테스트", exact: true}).click();
  await expect(explorer.getByText(/PostgreSQL 17/)).toBeVisible();
  await explorer.getByRole("button", {name: "스키마 조회", exact: true}).click();
  await explorer.getByLabel("스키마 / 카탈로그", {exact: true}).selectOption("public");
  await explorer.getByLabel("테이블 / 뷰", {exact: true}).selectOption("tf_ui_file");
  await explorer.getByLabel("memo 추출", {exact: true}).uncheck();
  await explorer.getByRole("button", {name: "선택한 컬럼 행 추출", exact: true}).click();
  await expect(page.getByText("3행을 추출했습니다", {exact: false})).toBeVisible();
  await expect(page.getByLabel("1행 emp_nm", {exact: true})).toHaveValue("김하늘 O'Brien");
  await page.getByRole("button", {name: "데이터 검증", exact: true}).click();
  await expect(page.getByText(/모든 검증을 통과했습니다/)).toBeVisible();

  await page.getByRole("button", {name: "CSV 파일", exact: true}).click();
  const [csv] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", {name: "CSV 내보내기 ↓"}).click()]);
  const text = readFileSync(await csv.path(), "utf-8");
  expect(text).toContain("김하늘 O'Brien");
  expect(text).toContain("2026-01-15");
  expect(text).toContain("3500000.50");
  expect(text).toContain("12345678901234567.12");   // 고정밀 숫자가 반올림되지 않는다
  expect(text).toContain("'=SUM(A1)");                // 수식 실행 방지
  expect(text).not.toContain("제외할 메모");          // 선택하지 않은 컬럼은 나오지 않는다
  const row2 = text.split(/\r?\n/).find(line => line.startsWith("002"))!;
  expect(row2).toMatch(/^002,이도윤,,/);               // NULL 날짜는 빈 칸

  await page.getByRole("button", {name: "Excel 파일", exact: true}).click();
  const [xlsx] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", {name: "Excel ↓"}).click()]);
  const bytes = readFileSync(await xlsx.path());
  expect(bytes.subarray(0, 2).toString()).toBe("PK");  // 유효한 XLSX(zip) 시그니처
  expect(bytes.length).toBeGreaterThan(3000);
  expect(pg("SELECT count(*) FROM tf_ui_file;").trim()).toBe("3"); // 읽기 전용: 원본 불변
});
