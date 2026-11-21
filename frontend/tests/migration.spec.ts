import { test, expect } from "@playwright/test";
import { confirmExistingTarget, confirmSource, connectTarget, historyRows, oracle, openHistory, pg, receipts, runPanel } from "./helpers";

// 실제 Oracle/PostgreSQL 테스트 컨테이너가 필요합니다(infra/jdbc/compose.yml, --profile oracle).
// 백엔드를 TABLEFLOW_JDBC_ENABLED=true TABLEFLOW_JDBC_LOAD_ENABLED=true 로 실행하고
// TABLEFLOW_TEST_JDBC=true 를 설정한 경우에만 실행합니다. 운영/개인 DB에는 사용하지 마세요.
test.skip(process.env.TABLEFLOW_TEST_JDBC !== "true", "실제 DB 컨테이너 필요: TABLEFLOW_TEST_JDBC=true");

test.beforeAll(() => {
  oracle(`BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UI_SRC'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE TF_UI_SRC (EMP_NO VARCHAR2(20) PRIMARY KEY, EMP_NM VARCHAR2(100) NOT NULL, HIRE_DATE DATE NOT NULL, SALARY NUMBER(20,2) NOT NULL);
INSERT INTO TF_UI_SRC VALUES ('001','Kim O''Brien',DATE '2026-01-15',3500000.50);
INSERT INTO TF_UI_SRC VALUES ('002','Lee',DATE '2026-03-02',4200000);
INSERT INTO TF_UI_SRC VALUES ('003','Park',DATE '2026-06-18',3800000.25);
COMMIT;`);
  pg(`DROP TABLE IF EXISTS tf_ui_dst; DROP TABLE IF EXISTS tableflow_load_receipts;
CREATE TABLE tf_ui_dst (empno varchar(20) PRIMARY KEY, emp_name varchar(100) NOT NULL, hiredate date NOT NULL, pay numeric(20,2) NOT NULL);
CREATE TABLE tableflow_load_receipts (request_id varchar(36) PRIMARY KEY, payload_hash varchar(64) NOT NULL, table_name varchar(128) NOT NULL, inserted_rows integer NOT NULL);`);
});
test.afterAll(() => {
  try { oracle(`DROP TABLE TF_UI_SRC;`); } catch {}
  try { pg(`DROP TABLE IF EXISTS tf_ui_dst; DROP TABLE IF EXISTS tableflow_load_receipts;`); } catch {}
});

test("real browser: Oracle → PostgreSQL 이관 with auto/manual mapping, pre-check, consent and replay-safe execute", async ({ page }) => {
  await confirmSource(page, "TF_UI_SRC");
  const review = await confirmExistingTarget(page, "tf_ui_dst");
  // 자동 추천: 이름이 비슷한 컬럼만 채워지고, 모호한 컬럼은 비워 둔다.
  await expect(review.getByLabel("EMP_NO 대상 컬럼", { exact: true })).toHaveValue("empno");
  await expect(review.getByLabel("HIRE_DATE 대상 컬럼", { exact: true })).toHaveValue("hiredate");
  await expect(review.getByLabel("EMP_NM 대상 컬럼", { exact: true })).toHaveValue("");
  await expect(review.getByLabel("SALARY 대상 컬럼", { exact: true })).toHaveValue("");
  await review.getByLabel("EMP_NM 대상 컬럼", { exact: true }).selectOption("emp_name");
  await review.getByLabel("SALARY 대상 컬럼", { exact: true }).selectOption("pay");
  // 컬럼별 호환성: 오류는 없고, 일시를 날짜 컬럼에 넣는 1건만 주의로 표시된다.
  await expect(review.getByText("매핑 4건 · 경고 1건 · 오류 0건")).toBeVisible(); // 경고 1건: Oracle DATE(일시) → 날짜
  await expect(review.getByText("원본의 샘플 200행")).toBeVisible();
  const run = runPanel(page);
  await run.getByRole("button", { name: "사전 점검" }).click();
  await expect(run.getByText(/원본 3행 중 3행을 이관합니다/)).toBeVisible();
  expect(pg("SELECT count(*) FROM tf_ui_dst;").trim()).toBe("0"); // 사전 점검은 쓰지 않는다.
  const start = run.getByRole("button", { name: "이관 시작" });
  await expect(start).toBeDisabled(); // 동의 전에는 실행 불가
  await run.getByRole("checkbox", { name: /실제 DB INSERT에 동의/ }).check();
  await start.click();
  await expect(page.getByText("이관 완료: 3행", { exact: true })).toBeVisible();
  expect(pg("SELECT count(*) FROM tf_ui_dst;").trim()).toBe("3");
  expect(pg("SELECT emp_name||'|'||pay||'|'||hiredate FROM tf_ui_dst WHERE empno='001';").trim()).toBe("Kim O'Brien|3500000.50|2026-01-15");
  await expect(start).toBeDisabled(); // 같은 화면에서 이중 실행 방지

  // 작업 이력: 성공 기록이 화면에 나타난다(이력은 실행 간에 유지된다).
  let history = await openHistory(page);
  await expect(history.getByRole("row", { name: /DB 이관.*TF_UI_SRC.*tf_ui_dst.*3.*성공/ }).first()).toBeVisible();
  // 실제 PK 충돌(같은 데이터 재이관, 새 요청 ID)은 거부되고 실패 이력으로 남는다. 대상은 그대로 3행.
  const conn = (db: string, port: number, dbms: string) => ({ dbms, host: "localhost", port, database: db, username: "tableflow", password: "TableFlowTest2026", tls: false });
  const body = { source: conn("FREEPDB1", 11521, "oracle"), sourceSchema: "TABLEFLOW", sourceTable: "TF_UI_SRC", target: conn("tableflow", 15432, "postgresql"),
    targetSchema: "public", targetTable: "tf_ui_dst", limit: 1000, requestId: "11111111-2222-3333-4444-555555555555", confirmed: true,
    mappings: [["EMP_NO", "empno"], ["EMP_NM", "emp_name"], ["HIRE_DATE", "hiredate"], ["SALARY", "pay"]].map(([source, target]) => ({ source, target })) };
  const rejected = await page.request.post("http://localhost:18080/api/v1/connections/migrate", { data: body });
  expect(rejected.status()).toBe(422);
  expect(pg("SELECT count(*) FROM tf_ui_dst;").trim()).toBe("3");
  const raw = await (await page.request.get("http://localhost:18080/api/v1/connections/history?limit=10")).text();
  const items = JSON.parse(raw).items;
  expect(items[0]).toMatchObject({ kind: "migrate", status: "failed", errorCode: "LOAD_REJECTED", rows: 0 });
  expect(items[1]).toMatchObject({ kind: "migrate", status: "success", rows: 3 });
  expect(raw).not.toContain("TableFlowTest2026"); expect(raw).not.toContain("localhost"); expect(raw).not.toContain("Kim O'Brien");
  history = await openHistory(page);
  await expect(history.getByRole("row", { name: /LOAD_REJECTED/ }).first()).toBeVisible();
});

test("real browser: 대상에 없는 PostgreSQL 테이블과 영수증을 Oracle 정의로 생성 (DDL 미리보기, 동의, 실행 전 변경 없음)", async ({ page }) => {
  pg(`DROP TABLE IF EXISTS tf_ui_new; DROP TABLE IF EXISTS tableflow_load_receipts;`);
  try {
    await confirmSource(page, "TF_UI_SRC");
    const dst = await connectTarget(page);
    await dst.getByLabel("신규 테이블 이름").fill("tf_ui_new");
    await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
    const review = page.getByRole("region", { name: "검토" });
    // 대상 컬럼 이름 기본값: PostgreSQL 관례(소문자), 직접 수정 가능
    await expect(review.getByLabel("EMP_NO 대상 컬럼", { exact: true })).toHaveValue("emp_no");
    await review.getByLabel("SALARY 대상 컬럼", { exact: true }).fill("pay");
    await expect.poll(async () => (await review.locator("pre.ddl-preview").allTextContents()).join("\n")).toContain('"pay" NUMERIC(20,2) NOT NULL');
    const ddl = await review.locator("pre.ddl-preview").allTextContents();
    expect(ddl).toHaveLength(2);
    expect(ddl[0]).toContain('CREATE TABLE "public"."tf_ui_new"'); expect(ddl[0]).toContain('PRIMARY KEY ("emp_no")');
    expect(ddl[1]).toContain("tableflow_load_receipts");
    const run = runPanel(page);
    await run.getByRole("button", { name: "사전 점검" }).click();
    await expect(run.getByText(/표시된 CREATE TABLE 실행과 DB INSERT에 동의합니다/)).toBeVisible();
    expect(pg("SELECT count(*) FROM pg_tables WHERE tablename IN ('tf_ui_new','tableflow_load_receipts');").trim()).toBe("0"); // 사전 점검은 만들지 않는다.
    const start = run.getByRole("button", { name: "이관 시작" });
    await expect(start).toBeDisabled();
    await run.getByRole("checkbox", { name: /DB INSERT에 동의/ }).check();
    await start.click();
    await expect(page.getByText("이관 완료: 3행", { exact: true })).toBeVisible();
    expect(pg("SELECT count(*) FROM tf_ui_new;").trim()).toBe("3");
    expect(pg("SELECT string_agg(column_name||':'||data_type, ',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='tf_ui_new';").trim())
      .toBe("emp_no:character varying,emp_nm:character varying,hire_date:timestamp without time zone,pay:numeric");
    expect(pg("SELECT pay||'|'||hire_date FROM tf_ui_new WHERE emp_no='001';").trim()).toBe("3500000.50|2026-01-15 00:00:00");
    expect(pg("SELECT count(*) FROM pg_constraint WHERE conrelid='tf_ui_new'::regclass AND contype='p';").trim()).toBe("1");
  } finally {
    try { pg(`DROP TABLE IF EXISTS tf_ui_new;`); } catch {}
  }
});

test("real browser: 타입·길이가 맞지 않는 컬럼은 이관 전에 오류로 표시되고 이관을 막는다", async ({ page }) => {
  oracle(`BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UI_LEN'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE TF_UI_LEN (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(100), CODE VARCHAR2(100), NOTE VARCHAR2(50));
INSERT INTO TF_UI_LEN VALUES (1,'짧은 이름','A1','메모');
INSERT INTO TF_UI_LEN VALUES (2,'${"가".repeat(30)}','B2','메모');
COMMIT;`);
  pg(`DROP TABLE IF EXISTS tf_ui_len; ${receipts}
CREATE TABLE tf_ui_len (id integer PRIMARY KEY, name varchar(20), code varchar(20), note integer);`);
  try {
    await confirmSource(page, "TF_UI_LEN");
    const review = await confirmExistingTarget(page, "tf_ui_len");
    await expect(review.getByText(/원본 값 중 최대 30자로, 대상 한도\(20자\)를 넘는 값이 1건/).first()).toBeVisible(); // 실제 데이터의 최대 길이까지 확인
    await expect(review.getByText(/실제 값은 모두 대상 한도\(20자\) 이하/).first()).toBeVisible();       // 길이는 크지만 값은 모두 한도 이하
    await review.getByLabel("NOTE 대상 컬럼", { exact: true }).selectOption("note");
    await expect(review.getByText(/문자열 값을 int4 컬럼에 넣을 수 없습니다/).first()).toBeVisible();
    await expect(review.getByText(/오류 2건 — 오류를 해결해야 이관할 수 있습니다/)).toBeVisible();
    await expect(runPanel(page).getByRole("button", { name: "이관 시작" })).toBeDisabled();
    await review.getByLabel("NOTE 대상 컬럼", { exact: true }).selectOption("");
    await review.getByLabel("NAME 대상 컬럼", { exact: true }).selectOption("");
    await expect(review.getByText(/오류 0건/)).toBeVisible();
    expect(pg("SELECT count(*) FROM tf_ui_len;").trim()).toBe("0"); // 분석은 아무것도 쓰지 않는다
  } finally {
    try { oracle(`DROP TABLE TF_UI_LEN;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_ui_len;`); } catch {}
  }
});

test("real browser: 12,500-row Oracle → PostgreSQL 분할 이관과 진행 현황 (백그라운드 작업, 분할 단위 5,000행 × 3회)", async ({ page }) => {
  oracle(`BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UI_BIG'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE TF_UI_BIG (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(30), AMT NUMBER(12,2));
INSERT INTO TF_UI_BIG SELECT LEVEL, 'n'||LEVEL, LEVEL+0.25 FROM DUAL CONNECT BY LEVEL <= 12500;
COMMIT;`);
  pg(`DROP TABLE IF EXISTS tf_ui_big; ${receipts}
CREATE TABLE tf_ui_big (id integer PRIMARY KEY, name varchar(30), amt numeric(12,2));`);
  try {
    await confirmSource(page, "TF_UI_BIG");
    const dst = await connectTarget(page);
    await dst.getByRole("radio", { name: "기존 테이블에 적재" }).check();
    await dst.getByLabel("대상 테이블", { exact: true }).selectOption("tf_ui_big");
    // 분할 단위 설정: 범위를 벗어나면 막고, 5,000행이면 3회로 나눈다.
    await dst.getByLabel("분할 단위(행)", { exact: true }).fill("50");
    await expect(dst.getByText("분할 단위는 100~10,000행 사이로 입력하세요")).toBeVisible();
    await expect(dst.getByRole("button", { name: "대상 확정", exact: true })).toBeDisabled();
    await dst.getByRole("button", { name: "5,000" }).click();
    await expect(dst.getByText("총 12,500행 ÷ 5,000행 = 3회 분할 (마지막 회 2,500행)")).toBeVisible();
    await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
    const review = page.getByRole("region", { name: "검토" });
    await expect(review.getByLabel("ID 대상 컬럼", { exact: true })).toHaveValue("id");
    const run = runPanel(page);
    await run.getByRole("button", { name: "사전 점검" }).click();
    await expect(run.getByText(/원본 12[,.]?500행 중 12[,.]?500행을 이관합니다/)).toBeVisible();
    await expect(run.getByText(/대용량: 3회 분할 × 최대 5,000행/)).toBeVisible();
    await expect(run.getByText(/처음 200행만 대상 스키마와 대조/)).toBeVisible();
    expect(pg("SELECT count(*) FROM tf_ui_big;").trim()).toBe("0");
    await run.getByRole("checkbox", { name: /DB INSERT에 동의/ }).check();
    await run.getByRole("button", { name: "이관 시작" }).click();
    await expect(run.getByRole("status", { name: "이관 진행 현황" })).toBeVisible();
    await expect(page.getByText("이관 완료: 12,500행", { exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(run.getByRole("status", { name: "이관 진행 현황" }).getByText(/완료 · 12[,.]?500 \/ 12[,.]?500행 · 3 \/ 3회 완료/)).toBeVisible();
    expect(pg("SELECT count(*)||'|'||count(DISTINCT id)||'|'||sum(id)||'|'||max(amt) FROM tf_ui_big;").trim()).toBe("12500|12500|78131250|12500.25");
    const history = await openHistory(page);
    await expect(history.getByRole("row", { name: /TF_UI_BIG.*tf_ui_big.*12[,.]?500.*성공/ }).first()).toBeVisible();
  } finally {
    try { oracle(`DROP TABLE TF_UI_BIG;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_ui_big;`); } catch {}
  }
});

test("real browser: Oracle 기본 키를 PostgreSQL 자동 증가 컬럼에 유지하고 시퀀스를 다시 맞춘다", async ({ page }) => {
  oracle(`BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UI_KEY'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE TF_UI_KEY (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(30));
INSERT INTO TF_UI_KEY VALUES (10,'a');
INSERT INTO TF_UI_KEY VALUES (20,'b');
INSERT INTO TF_UI_KEY VALUES (30,'c');
COMMIT;`);
  pg(`DROP TABLE IF EXISTS tf_ui_key; ${receipts}
CREATE TABLE tf_ui_key (id integer GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name varchar(30));`);
  try {
    await confirmSource(page, "TF_UI_KEY");
    const review = await confirmExistingTarget(page, "tf_ui_key");
    // 옵션 없이는 자동 증가 컬럼에 원본 값을 넣을 수 없다 → 옵션 안내와 함께 거부
    await expect(review.getByRole("alert").filter({ hasText: /원본 키 값 보존/ })).toBeVisible();
    await page.getByRole("button", { name: /^2 · 대상/ }).click();
    await page.getByRole("checkbox", { name: "원본 기본 키(PK) 유지" }).check();
    await page.getByRole("region", { name: "대상 선택" }).getByRole("button", { name: "대상 확정", exact: true }).click();
    const run = runPanel(page);
    await run.getByRole("button", { name: "사전 점검" }).click();
    await expect(run.getByText(/자동 증가 컬럼 id에 원본 값을 그대로 넣고/)).toBeVisible();
    await run.getByRole("checkbox", { name: /DB INSERT에 동의/ }).check();
    await run.getByRole("button", { name: "이관 시작" }).click();
    await expect(page.getByText("이관 완료: 3행", { exact: true })).toBeVisible();
    await expect(page.getByText(/시퀀스 재동기화: id → 다음 값 31/)).toBeVisible();
    expect(pg("SELECT sum(id) FROM tf_ui_key;").trim()).toBe("60"); // 원본 키 값 그대로
    pg("INSERT INTO tf_ui_key (name) VALUES ('new');");
    expect(pg("SELECT id FROM tf_ui_key WHERE name='new';").trim()).toBe("31"); // 다음 번호는 최댓값 다음
  } finally {
    try { oracle(`DROP TABLE TF_UI_KEY;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_ui_key;`); } catch {}
  }
});

test("real browser: 비밀번호 저장 — 저장하면 입력 없이 연결되고, 저장 파일에 평문이 없으며, 삭제하면 사라진다", async ({ page }) => {
  await page.goto("/");
  const src = page.getByRole("region", { name: "원본 선택" });
  await src.getByLabel("원본 연결", { exact: true }).selectOption("new");
  await src.getByLabel("원본 종류", { exact: true }).selectOption("oracle");
  await src.getByLabel("원본 포트", { exact: true }).fill("11521");
  await src.getByLabel("원본 데이터베이스", { exact: true }).fill("FREEPDB1");
  await src.getByLabel("원본 사용자", { exact: true }).fill("tableflow");
  await src.getByLabel("원본 비밀번호", { exact: true }).fill("TableFlowTest2026");
  await src.getByLabel("원본 연결 이름").fill("UI 테스트 Oracle");
  await src.getByRole("checkbox", { name: "비밀번호 저장" }).check();
  await src.getByRole("button", { name: "연결 확인", exact: true }).click();
  await expect(src.getByRole("status", { name: "원본 연결 상태" })).toContainText("연결 성공");
  // 서버가 돌려주는 목록에는 비밀번호가 없고 저장 여부만 있다.
  const list = await (await page.request.get("http://localhost:18080/api/v1/connections/saved")).text();
  expect(list).not.toContain("TableFlowTest2026"); expect(list).toContain('"passwordSaved":true');
  // 새로 열어도 입력 없이 저장된 비밀번호로 연결된다.
  await page.reload();
  const again = page.getByRole("region", { name: "원본 선택" });
  await again.getByLabel("원본 연결", { exact: true }).selectOption({ label: "UI 테스트 Oracle" });
  await expect(again.getByRole("status", { name: "원본 연결 상태" })).toContainText("연결 성공");
  await expect(again.getByLabel("원본 비밀번호", { exact: true })).toHaveValue("");
  // 브라우저 저장소에도 비밀번호가 없다.
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain("TableFlowTest2026");
  // 삭제
  await again.getByRole("button", { name: "연결 삭제" }).click();
  await again.getByRole("button", { name: "삭제 확인" }).click();
  await expect.poll(async () => (await (await page.request.get("http://localhost:18080/api/v1/connections/saved")).text())).not.toContain("UI 테스트 Oracle");
});

test("real browser: 신규 테이블의 대상 길이를 직접 지정하면 그 길이로 만들어진다", async ({ page }) => {
  pg(`DROP TABLE IF EXISTS tf_ui_typ; DROP TABLE IF EXISTS tableflow_load_receipts;`);
  try {
    await confirmSource(page, "TF_UI_SRC");
    const dst = await connectTarget(page);
    await dst.getByLabel("신규 테이블 이름").fill("tf_ui_typ");
    await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
    const review = page.getByRole("region", { name: "검토" });
    await review.getByLabel("EMP_NM 길이 또는 정밀도", { exact: true }).fill("60");
    await expect(review.getByText(/매핑 4건/)).toBeVisible();
    await expect(review.getByText("PK", { exact: true }).first()).toBeVisible(); // EMP_NO는 원본의 기본 키
    const run = runPanel(page);
    await run.getByRole("button", { name: "사전 점검" }).click();
    await run.getByRole("checkbox", { name: /DB INSERT에 동의/ }).check();
    await run.getByRole("button", { name: "이관 시작" }).click();
    await expect(page.getByText("이관 완료: 3행", { exact: true })).toBeVisible();
    expect(pg("SELECT character_maximum_length FROM information_schema.columns WHERE table_name='tf_ui_typ' AND column_name='emp_nm';").trim()).toBe("60");
    expect(pg("SELECT character_maximum_length FROM information_schema.columns WHERE table_name='tf_ui_typ' AND column_name='emp_no';").trim()).toBe("20"); // 지정하지 않은 컬럼은 원본 정의
  } finally { try { pg(`DROP TABLE IF EXISTS tf_ui_typ; DROP TABLE IF EXISTS tableflow_load_receipts;`); } catch {} }
});

test("real browser: 중간에 멈춘 대용량 이관을 작업 이력에서 재개 — 완료된 분량은 건너뛰고 이어서 완료", async ({ page }) => {
  test.setTimeout(120_000);
  oracle(`BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UI_RES'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE TF_UI_RES (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(40));
INSERT INTO TF_UI_RES SELECT LEVEL, 'n'||LEVEL FROM DUAL CONNECT BY LEVEL <= 12500;
UPDATE TF_UI_RES SET NAME='${"x".repeat(30)}' WHERE ID=7000;
COMMIT;`);
  pg(`DROP TABLE IF EXISTS tf_ui_res; ${receipts}
CREATE TABLE tf_ui_res (id integer PRIMARY KEY, name varchar(20));`);
  try {
    // 화면의 분석은 길이 초과(30자)를 미리 오류로 막으므로, 중간에 실패하는 상황은 API로 직접 이관을 시작해 만든다.
    const conn = (db: string, port: number, dbms: string) => ({ dbms, host: "localhost", port, database: db, username: "tableflow", password: "TableFlowTest2026", tls: false });
    const requestId = "99999999-8888-7777-6666-555555555555";
    const body = { source: conn("FREEPDB1", 11521, "oracle"), sourceSchema: "TABLEFLOW", sourceTable: "TF_UI_RES", target: conn("tableflow", 15432, "postgresql"),
      targetSchema: "public", targetTable: "tf_ui_res", limit: 12500, requestId, confirmed: true, chunkSize: 5000, mappings: [{ source: "ID", target: "id" }, { source: "NAME", target: "name" }] };
    const started = await page.request.post("http://localhost:18080/api/v1/connections/migrate/jobs", { data: body });
    expect(started.ok()).toBe(true);
    await expect.poll(async () => (await (await page.request.get(`http://localhost:18080/api/v1/connections/migrate/jobs/${requestId}`)).json()).state, { timeout: 60_000 }).toBe("failed");
    expect(pg("SELECT count(*) FROM tf_ui_res;").trim()).toBe("5000"); // 첫 분할만 저장됨
    // 작업 이력에 중단된 작업이 나타나고 재개할 수 있다(비밀번호가 저장되지 않은 연결이라 입력이 필요).
    await page.goto("/"); await page.reload();
    await page.getByRole("navigation", { name: "메뉴" }).getByRole("button", { name: "작업 이력", exact: true }).click();
    const jobs = page.getByRole("region", { name: "이관 작업" });
    await expect(jobs.getByRole("row", { name: /TF_UI_RES.*tf_ui_res.*중단·실패/ }).first()).toBeVisible();
    // 원인(원본의 긴 값)을 고친 뒤 재개한다.
    oracle(`UPDATE TF_UI_RES SET NAME='ok' WHERE ID=7000;\nCOMMIT;`);
    await jobs.getByRole("button", { name: "재개", exact: true }).first().click();
    await jobs.getByLabel("원본 DB 비밀번호").fill("TableFlowTest2026");
    await jobs.getByLabel("대상 DB 비밀번호").fill("TableFlowTest2026");
    await jobs.getByRole("button", { name: "재개 시작" }).click();
    await expect.poll(async () => (await (await page.request.get(`http://localhost:18080/api/v1/connections/migrate/jobs/${requestId}`)).json()).state, { timeout: 60_000 }).toBe("succeeded");
    await jobs.getByRole("button", { name: "새로고침" }).count(); // 목록은 진행 중이면 자동 갱신된다
    await expect(jobs.getByRole("row", { name: /TF_UI_RES.*12,500 \/ 12,500행.*3 \/ 3회/ }).first()).toBeVisible({ timeout: 15_000 });
    expect(pg("SELECT count(*)||'|'||count(DISTINCT id)||'|'||max(length(name)) FROM tf_ui_res;").trim()).toBe("12500|12500|6"); // 중복 없이 전체(가장 긴 이름 n12500)
    const status = await (await page.request.get(`http://localhost:18080/api/v1/connections/migrate/jobs/${requestId}`)).json();
    expect(status.state).toBe("succeeded"); expect(status.replayedChunks).toBe(1); // 첫 분할은 영수증으로 건너뜀
    const saved = await (await page.request.get("http://localhost:18080/api/v1/connections/migrate/jobs")).text();
    expect(saved).not.toContain("TableFlowTest2026"); // 목록·정의에 비밀번호가 없다
  } finally {
    try { oracle(`DROP TABLE TF_UI_RES;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_ui_res;`); } catch {}
  }
});

test("real browser: 이관 순서 계산(FK 기준)과 부모 테이블 바로 이관 후 FK 생성", async ({ page }) => {
  test.setTimeout(120_000);
  oracle(`BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_O_C'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_O_P'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE TF_O_P (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(20));
CREATE TABLE TF_O_C (ID NUMBER(10) PRIMARY KEY, P_ID NUMBER(10) NOT NULL REFERENCES TF_O_P (ID), QTY NUMBER(5));
INSERT INTO TF_O_P VALUES (1,'a');
INSERT INTO TF_O_P VALUES (2,'b');
INSERT INTO TF_O_C VALUES (10,1,5);
INSERT INTO TF_O_C VALUES (11,2,6);
COMMIT;`);
  pg(`DROP TABLE IF EXISTS tf_o_c; DROP TABLE IF EXISTS tf_o_p; DROP TABLE IF EXISTS tableflow_load_receipts;`);
  try {
    await confirmSource(page, "TF_O_C");
    // 순서 계산: 부모(TF_O_P)가 1단계, 자식(TF_O_C)이 2단계
    await page.getByRole("button", { name: /^1 · 원본/ }).click();
    const src = page.getByRole("region", { name: "원본 선택" });
    await src.getByText("이관 순서 계산 (외래 키 기준)").click();
    await src.getByRole("button", { name: "순서 계산", exact: true }).click();
    const order = src.getByRole("status", { name: "이관 순서" });
    await expect(order.locator(".row", { hasText: "1단계" }).getByRole("button", { name: "TF_O_P", exact: true })).toBeVisible();
    await expect(order.locator(".row", { hasText: "2단계" }).getByRole("button", { name: "TF_O_C", exact: true })).toBeVisible();
    // 자식을 원본으로 선택해 신규 테이블 + FK 복제로 분석하면 '부모 먼저 이관' 안내가 나온다.
    await order.getByRole("button", { name: "TF_O_C", exact: true }).click();
    await expect(src.getByLabel("원본 테이블", { exact: true })).toHaveValue("TF_O_C");
    await src.getByRole("button", { name: "원본 확정", exact: true }).click();
    const dst = await connectTarget(page);
    await dst.getByLabel("신규 테이블 이름").fill("tf_o_c");
    await dst.getByRole("checkbox", { name: "외래 키(FK) 복제" }).check();
    await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
    const review = page.getByRole("region", { name: "검토" });
    const guide = review.getByRole("status", { name: "이관 순서 안내" });
    await expect(guide).toContainText("부모 먼저 이관");
    // 버튼 한 번으로 부모 테이블 이관으로 이동 → 대상 이름이 부모 이름으로 채워진다.
    await guide.getByRole("button", { name: "부모 테이블 이관" }).click();
    await expect(src.getByLabel("원본 테이블", { exact: true })).toHaveValue("TF_O_P");
    await src.getByRole("button", { name: "원본 확정", exact: true }).click();
    await expect(dst.getByLabel("신규 테이블 이름")).toHaveValue("TF_O_P");
    await dst.getByLabel("신규 테이블 이름").fill("tf_o_p");
    await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
    const run = runPanel(page);
    await run.getByRole("button", { name: "사전 점검" }).click();
    await run.getByRole("checkbox", { name: /DB INSERT에 동의/ }).check();
    await run.getByRole("button", { name: "이관 시작" }).click();
    await expect(page.getByText("이관 완료: 2행", { exact: true })).toBeVisible();
    // 부모가 대상에 생겼으므로 자식은 FK를 만들 수 있다.
    await page.getByRole("button", { name: /^1 · 원본/ }).click();
    await src.getByLabel("원본 테이블", { exact: true }).selectOption("TF_O_C");
    await src.getByRole("button", { name: "원본 확정", exact: true }).click();
    await dst.getByLabel("신규 테이블 이름").fill("tf_o_c");
    await dst.getByRole("checkbox", { name: "외래 키(FK) 복제" }).check();
    await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
    await expect(review.getByRole("status", { name: "이관 순서 안내" })).toContainText("FK 생성");
    await run.getByRole("button", { name: "사전 점검" }).click();
    await run.getByRole("checkbox", { name: /DB INSERT에 동의/ }).check();
    await run.getByRole("button", { name: "이관 시작" }).click();
    await expect(page.getByText("이관 완료: 2행", { exact: true })).toBeVisible();
    expect(pg("SELECT count(*) FROM pg_constraint WHERE conrelid='tf_o_c'::regclass AND contype='f';").trim()).toBe("1");
  } finally {
    try { oracle(`DROP TABLE TF_O_C;\nDROP TABLE TF_O_P;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_o_c; DROP TABLE IF EXISTS tf_o_p; DROP TABLE IF EXISTS tableflow_load_receipts;`); } catch {}
  }
});
