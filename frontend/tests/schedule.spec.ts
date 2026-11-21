import { test, expect, type Locator } from "@playwright/test";
import { oracle, ORACLE, PG, pg, type Conn } from "./helpers";

// 실제 Oracle/PostgreSQL 테스트 컨테이너가 필요합니다(TABLEFLOW_TEST_JDBC=true). 비밀번호를 저장한 연결로 스케줄을 등록·실행합니다.
test.skip(process.env.TABLEFLOW_TEST_JDBC !== "true", "실제 DB 컨테이너 필요: TABLEFLOW_TEST_JDBC=true");

const API = "http://localhost:18080/api/v1";
const dropOracle = `BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_US_C'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_US_P'; EXCEPTION WHEN OTHERS THEN NULL; END;
/`;
const dropPg = `DROP TABLE IF EXISTS tf_us_c; DROP TABLE IF EXISTS tf_us_p; DROP TABLE IF EXISTS tableflow_load_receipts;`;

async function cleanup(request: import("@playwright/test").APIRequestContext) {
  for (const s of (await (await request.get(`${API}/schedules`)).json()).items) if (s.name.startsWith("UI 반복")) await request.delete(`${API}/schedules/${s.id}`);
  for (const c of (await (await request.get(`${API}/connections/saved`)).json()).items) if (c.name.startsWith("UI 스케줄")) await request.delete(`${API}/connections/saved/${c.id}`);
}

test.beforeAll(async ({ request }) => {
  await cleanup(request);
  oracle(`${dropOracle}
CREATE TABLE TF_US_P (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(40));
CREATE TABLE TF_US_C (ID NUMBER(10) PRIMARY KEY, PID NUMBER(10), CONSTRAINT FK_TF_US_C FOREIGN KEY (PID) REFERENCES TF_US_P(ID));
INSERT INTO TF_US_P VALUES (1,'부모1');
INSERT INTO TF_US_P VALUES (2,'부모2');
INSERT INTO TF_US_C VALUES (1,1);
INSERT INTO TF_US_C VALUES (2,2);
INSERT INTO TF_US_C VALUES (3,2);
COMMIT;`);
  pg(dropPg);
});
test.afterAll(async ({ request }) => {
  try { await cleanup(request); } catch {}
  try { oracle(dropOracle); } catch {}
  try { pg(dropPg); } catch {}
});

async function connectSaved(region: Locator, role: "원본" | "대상", c: Conn, name: string) {
  await region.getByLabel(`${role} 연결`, { exact: true }).selectOption("new");
  await region.getByLabel(`${role} 종류`, { exact: true }).selectOption(c.dbms);
  await region.getByLabel(`${role} 포트`, { exact: true }).fill(c.port);
  await region.getByLabel(`${role} 데이터베이스`, { exact: true }).fill(c.db);
  await region.getByLabel(`${role} 사용자`, { exact: true }).fill("tableflow");
  await region.getByLabel(`${role} 비밀번호`, { exact: true }).fill("TableFlowTest2026");
  await region.getByLabel(`${role} 연결 이름`).fill(name);
  await region.getByRole("checkbox", { name: "비밀번호 저장" }).check();
  await region.getByRole("button", { name: "연결 확인", exact: true }).click();
  await expect(region.getByRole("status", { name: `${role} 연결 상태` })).toContainText("연결 성공");
}

test("real browser: 스케줄 — 저장된 연결로 일괄 이관을 등록하고, 지금 실행해도 중복 없이 다시 적재된다", async ({ page }) => {
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "메뉴" });
  await nav.getByRole("button", { name: "일괄 이관", exact: true }).click();
  const src = page.getByRole("region", { name: "일괄 이관 원본" });
  await connectSaved(src, "원본", ORACLE, "UI 스케줄 원본");
  await src.getByLabel("일괄 원본 스키마", { exact: true }).selectOption("TABLEFLOW");
  await src.getByLabel("테이블 검색", { exact: true }).fill("TF_US_");
  await src.getByRole("checkbox", { name: "전체 선택" }).check();
  const dst = page.getByRole("region", { name: "일괄 이관 대상" });
  await connectSaved(dst, "대상", PG, "UI 스케줄 대상");
  await dst.getByLabel("일괄 대상 스키마", { exact: true }).selectOption("public");
  const run = page.getByRole("region", { name: "일괄 이관 실행" });
  await run.getByRole("button", { name: "사전 점검" }).click();
  await expect(run.getByText("이관할 5행")).toBeVisible();

  // 등록: 비우고 다시 적재는 동의가 있어야 한다
  await run.getByText("스케줄로 등록 (주기적으로 반복 실행)").click();
  const form = run.getByRole("group", { name: "스케줄 등록" });
  await form.getByLabel("sb 스케줄 이름").fill("UI 반복 이관");
  await form.getByLabel("sb 반복 방식").selectOption("interval");
  await form.getByLabel("sb 간격(분)").fill("60");
  const register = form.getByRole("button", { name: "스케줄 등록" });
  await expect(register).toBeDisabled();
  await form.getByRole("checkbox", { name: /기존 행을 모두 삭제/ }).check();
  await register.click();
  await expect(form.getByText(/스케줄을 등록했습니다/)).toBeVisible();

  // 스케줄 메뉴에서 지금 실행 → 성공 → 대상에 적재
  await nav.getByRole("button", { name: "스케줄", exact: true }).click();
  const table = page.getByRole("table", { name: "스케줄 표" });
  const row = table.locator("tbody tr").filter({ hasText: "UI 반복 이관" });
  await expect(row).toContainText("60분마다");
  await expect(row).toContainText("비우고 다시 적재");
  await page.getByRole("button", { name: "UI 반복 이관 지금 실행" }).click();
  await expect(row).toContainText("성공", { timeout: 60_000 });
  expect(pg("SELECT count(*) FROM tf_us_p;").trim()).toBe("2");
  expect(pg("SELECT count(*) FROM tf_us_c;").trim()).toBe("3");

  // 한 번 더 실행해도 지우고 다시 넣으므로 중복되지 않는다
  pg("INSERT INTO tf_us_p VALUES (99,'임시');"); // 사람이 대상에 넣은 행은 교체 때 사라진다
  await page.getByRole("button", { name: "UI 반복 이관 지금 실행" }).click();
  await expect.poll(() => pg("SELECT count(*) FROM tf_us_p;").trim(), { timeout: 60_000 }).toBe("2");
  await expect(row).toContainText("성공");
  expect(pg("SELECT count(*) FROM tf_us_c;").trim()).toBe("3");

  // 중지/사용, 삭제
  await page.getByRole("button", { name: "UI 반복 이관 중지" }).click();
  await expect(row).toContainText("중지됨");
  page.once("dialog", d => void d.accept());
  await page.getByRole("button", { name: "UI 반복 이관 삭제" }).click();
  await expect(row).toHaveCount(0);
});
