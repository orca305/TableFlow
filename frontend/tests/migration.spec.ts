import {test, expect} from "@playwright/test";
import {execSync} from "node:child_process";

// 실제 Oracle/PostgreSQL 테스트 컨테이너가 필요합니다(infra/jdbc/compose.yml, --profile oracle).
// 백엔드를 TABLEFLOW_JDBC_ENABLED=true TABLEFLOW_JDBC_LOAD_ENABLED=true 로 실행하고
// TABLEFLOW_TEST_JDBC=true 를 설정한 경우에만 실행합니다. 운영/개인 DB에는 사용하지 마세요.
test.skip(process.env.TABLEFLOW_TEST_JDBC !== "true", "실제 DB 컨테이너 필요: TABLEFLOW_TEST_JDBC=true");

const oracle = (sql: string) => execSync("docker exec -i tableflow-jdbc-tests-oracle-1 sqlplus -s tableflow/TableFlowTest2026@//localhost/FREEPDB1", {input: sql + "\nexit\n"}).toString();
const pg = (sql: string) => execSync("docker exec -i tableflow-jdbc-tests-postgresql-1 psql -U tableflow -d tableflow -t -A", {input: sql}).toString();

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

test("real browser: Oracle → PostgreSQL migration with auto/manual mapping, dry-run, consent and replay-safe execute", async ({page}) => {
  await page.goto("/");
  await page.getByRole("button", {name: "DB → DB 이전", exact: true}).click();
  const panel = page.getByLabel("DB → DB 이전", {exact: true});
  const fill = async (side: string, port: string, db: string) => {
    await panel.getByLabel(`${side} 포트`, {exact: true}).fill(port);
    await panel.getByLabel(`${side} 데이터베이스명`, {exact: true}).fill(db);
    await panel.getByLabel(`${side} 사용자`, {exact: true}).fill("tableflow");
    await panel.getByLabel(`${side} 비밀번호`, {exact: true}).fill("TableFlowTest2026");
    await panel.getByRole("group", {name: `${side} DB`}).getByRole("checkbox").uncheck(); // 로컬 컨테이너는 TLS 없이
  };
  await fill("원본", "11521", "FREEPDB1");
  await panel.getByLabel("대상 DBMS", {exact: true}).selectOption("postgresql");
  await fill("대상", "15432", "tableflow");
  await panel.getByRole("button", {name: "원본 연결·스키마 조회"}).click();
  await panel.getByLabel("원본 스키마", {exact: true}).selectOption("TABLEFLOW");
  await panel.getByLabel("원본 테이블", {exact: true}).selectOption("TF_UI_SRC");
  await panel.getByRole("button", {name: "대상 연결·스키마 조회"}).click();
  await panel.getByLabel("대상 스키마", {exact: true}).selectOption("public");
  await panel.getByLabel("대상 테이블", {exact: true}).selectOption("tf_ui_dst");
  // 자동 추천: 이름이 비슷한 컬럼만 채워지고, 모호한 컬럼은 비워 둔다.
  await expect(panel.getByLabel("EMP_NO 대상 컬럼", {exact: true})).toHaveValue("empno");
  await expect(panel.getByLabel("HIRE_DATE 대상 컬럼", {exact: true})).toHaveValue("hiredate");
  await expect(panel.getByLabel("EMP_NM 대상 컬럼", {exact: true})).toHaveValue("");
  await expect(panel.getByLabel("SALARY 대상 컬럼", {exact: true})).toHaveValue("");
  await panel.getByLabel("EMP_NM 대상 컬럼", {exact: true}).selectOption("emp_name");
  await panel.getByLabel("SALARY 대상 컬럼", {exact: true}).selectOption("pay");
  await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
  await expect(panel.getByText(/원본 3행 중 3행을 이전합니다/)).toBeVisible();
  expect(pg("SELECT count(*) FROM tf_ui_dst;").trim()).toBe("0"); // Dry-run은 쓰지 않는다.
  const run = panel.getByRole("button", {name: "선택한 대상에 이전 실행"});
  await expect(run).toBeDisabled(); // 동의 전에는 실행 불가
  await panel.getByRole("checkbox", {name: /실제 DB INSERT에 동의/}).check();
  await run.click();
  await expect(panel.getByText("이전 완료: 3행", {exact: true})).toBeVisible();
  expect(pg("SELECT count(*) FROM tf_ui_dst;").trim()).toBe("3");
  expect(pg("SELECT emp_name||'|'||pay||'|'||hiredate FROM tf_ui_dst WHERE empno='001';").trim()).toBe("Kim O'Brien|3500000.50|2026-01-15");
  await expect(run).toBeDisabled(); // 같은 화면에서 이중 실행 방지

  // 작업 이력: 성공 기록이 화면에 나타난다.
  const history = panel.getByLabel("작업 이력", {exact: true});
  await expect(history.getByRole("row", {name: /DB → DB 이전.*TF_UI_SRC.*tf_ui_dst.*3.*성공/}).first()).toBeVisible(); // 이력은 실행 간에 유지된다
  // 실제 PK 충돌(같은 데이터 재이전, 새 요청 ID)은 거부되고 실패 이력으로 남는다. 대상은 그대로 3행.
  const conn = (db: string, port: number, dbms: string) => ({dbms, host: "localhost", port, database: db, username: "tableflow", password: "TableFlowTest2026", tls: false});
  const body = {source: conn("FREEPDB1", 11521, "oracle"), sourceSchema: "TABLEFLOW", sourceTable: "TF_UI_SRC", target: conn("tableflow", 15432, "postgresql"),
    targetSchema: "public", targetTable: "tf_ui_dst", limit: 1000, requestId: "11111111-2222-3333-4444-555555555555", confirmed: true,
    mappings: [["EMP_NO","empno"],["EMP_NM","emp_name"],["HIRE_DATE","hiredate"],["SALARY","pay"]].map(([source, target]) => ({source, target}))};
  const rejected = await page.request.post("http://localhost:18080/api/v1/connections/migrate", {data: body});
  expect(rejected.status()).toBe(422);
  expect(pg("SELECT count(*) FROM tf_ui_dst;").trim()).toBe("3");
  const raw = await (await page.request.get("http://localhost:18080/api/v1/connections/history?limit=10")).text();
  const items = JSON.parse(raw).items;
  expect(items[0]).toMatchObject({kind: "migrate", status: "failed", errorCode: "LOAD_REJECTED", rows: 0});
  expect(items[1]).toMatchObject({kind: "migrate", status: "success", rows: 3});
  expect(raw).not.toContain("TableFlowTest2026"); expect(raw).not.toContain("localhost"); expect(raw).not.toContain("Kim O'Brien");
  await history.getByRole("button", {name: "새로고침"}).click();
  await expect(history.getByRole("row", {name: /LOAD_REJECTED/}).first()).toBeVisible();
});

test("real browser: create missing PostgreSQL table + receipts from Oracle definition (DDL preview, consent, no change before execute)", async ({page}) => {
  pg(`DROP TABLE IF EXISTS tf_ui_new; DROP TABLE IF EXISTS tableflow_load_receipts;`);
  try {
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
    await panel.getByLabel("원본 테이블", {exact: true}).selectOption("TF_UI_SRC");
    await panel.getByRole("button", {name: "대상 연결·스키마 조회"}).click();
    await panel.getByLabel("대상 스키마", {exact: true}).selectOption("public");
    await panel.getByRole("checkbox", {name: /새 테이블 만들기/}).check();
    await panel.getByLabel("대상 새 테이블 이름", {exact: true}).fill("tf_ui_new");
    // 대상 컬럼 이름 기본값: PostgreSQL 관례(소문자), 직접 수정 가능
    await expect(panel.getByLabel("EMP_NO 대상 컬럼", {exact: true})).toHaveValue("emp_no");
    await panel.getByLabel("SALARY 대상 컬럼", {exact: true}).fill("pay");
    await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
    await expect(panel.getByText(/표시된 CREATE TABLE 실행과 DB INSERT에 동의합니다/)).toBeVisible();
    const ddl = await panel.locator("pre.ddl-preview").allTextContents();
    expect(ddl).toHaveLength(2);
    expect(ddl[0]).toContain('CREATE TABLE "public"."tf_ui_new"'); expect(ddl[0]).toContain('"pay" NUMERIC(20,2) NOT NULL'); expect(ddl[0]).toContain('PRIMARY KEY ("emp_no")');
    expect(ddl[1]).toContain("tableflow_load_receipts");
    expect(pg("SELECT count(*) FROM pg_tables WHERE tablename IN ('tf_ui_new','tableflow_load_receipts');").trim()).toBe("0"); // Dry-run은 만들지 않는다.
    const run = panel.getByRole("button", {name: "선택한 대상에 이전 실행"});
    await expect(run).toBeDisabled();
    await panel.getByRole("checkbox", {name: /DB INSERT에 동의/}).check();
    await run.click();
    await expect(panel.getByText("이전 완료: 3행", {exact: true})).toBeVisible();
    expect(pg("SELECT count(*) FROM tf_ui_new;").trim()).toBe("3");
    expect(pg("SELECT string_agg(column_name||':'||data_type, ',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name='tf_ui_new';").trim())
      .toBe("emp_no:character varying,emp_nm:character varying,hire_date:timestamp without time zone,pay:numeric");
    expect(pg("SELECT pay||'|'||hire_date FROM tf_ui_new WHERE emp_no='001';").trim()).toBe("3500000.50|2026-01-15 00:00:00");
    expect(pg("SELECT count(*) FROM pg_constraint WHERE conrelid='tf_ui_new'::regclass AND contype='p';").trim()).toBe("1");
  } finally {
    try { pg(`DROP TABLE IF EXISTS tf_ui_new;`); } catch {}
  }
});

async function openMigration(page: import("@playwright/test").Page, srcTable: string) {
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
const receipts = `CREATE TABLE IF NOT EXISTS tableflow_load_receipts (request_id varchar(36) PRIMARY KEY, payload_hash varchar(64) NOT NULL, table_name varchar(128) NOT NULL, inserted_rows integer NOT NULL);`;

test("real browser: 12,500-row Oracle → PostgreSQL chunked migration with progress (background job, 3 chunks)", async ({page}) => {
  oracle(`BEGIN EXECUTE IMMEDIATE 'DROP TABLE TF_UI_BIG'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
CREATE TABLE TF_UI_BIG (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(30), AMT NUMBER(12,2));
INSERT INTO TF_UI_BIG SELECT LEVEL, 'n'||LEVEL, LEVEL+0.25 FROM DUAL CONNECT BY LEVEL <= 12500;
COMMIT;`);
  pg(`DROP TABLE IF EXISTS tf_ui_big; ${receipts}
CREATE TABLE tf_ui_big (id integer PRIMARY KEY, name varchar(30), amt numeric(12,2));`);
  try {
    const panel = await openMigration(page, "TF_UI_BIG");
    await panel.getByLabel("대상 테이블", {exact: true}).selectOption("tf_ui_big");
    await expect(panel.getByLabel("ID 대상 컬럼", {exact: true})).toHaveValue("id");
    // 한도 10,000 이하에서는 일부만 이전하지 않고 거부한다.
    await panel.getByLabel("최대 이전 행 수", {exact: true}).fill("5000");
    await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
    await expect(page.getByRole("alert").filter({hasText: /일부만 이전하는 것을 막았습니다/})).toBeVisible();
    // 한도를 올리면 대용량(청크) 점검: 전체 행 수·청크 수, 표본만 대조한다는 경고
    await panel.getByLabel("최대 이전 행 수", {exact: true}).fill("20000");
    await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
    await expect(panel.getByText(/원본 12[,.]?500행 중 12[,.]?500행을 이전합니다/)).toBeVisible();
    await expect(panel.getByText(/대용량: 3개 청크/)).toBeVisible();
    await expect(panel.getByText(/처음 200행만 대상 스키마와 대조/)).toBeVisible();
    expect(pg("SELECT count(*) FROM tf_ui_big;").trim()).toBe("0");
    await panel.getByRole("checkbox", {name: /DB INSERT에 동의/}).check();
    await panel.getByRole("button", {name: "선택한 대상에 이전 실행"}).click();
    await expect(panel.getByLabel("이전 진행률", {exact: true})).toBeVisible();
    await expect(panel.getByText("이전 완료: 12,500행", {exact: true})).toBeVisible({timeout: 60_000});
    await expect(panel.getByLabel("이전 진행률", {exact: true}).getByText(/완료 · 12[,.]?500 \/ 12[,.]?500행 · 청크 3 \/ 3/)).toBeVisible();
    expect(pg("SELECT count(*)||'|'||count(DISTINCT id)||'|'||sum(id)||'|'||max(amt) FROM tf_ui_big;").trim()).toBe("12500|12500|78131250|12500.25");
    await expect(panel.getByLabel("작업 이력", {exact: true}).getByRole("row", {name: /TF_UI_BIG.*tf_ui_big.*12[,.]?500.*성공/}).first()).toBeVisible();
    await expect(panel.getByRole("button", {name: "선택한 대상에 이전 실행"})).toBeDisabled();
  } finally {
    try { oracle(`DROP TABLE TF_UI_BIG;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_ui_big;`); } catch {}
  }
});

test("real browser: preserve Oracle keys into PostgreSQL identity column and resync the sequence", async ({page}) => {
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
    const panel = await openMigration(page, "TF_UI_KEY");
    await panel.getByLabel("대상 테이블", {exact: true}).selectOption("tf_ui_key");
    // 옵션 없이는 자동 증가 컬럼에 원본 값을 넣을 수 없다 → 옵션 안내와 함께 거부
    await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
    await expect(page.getByRole("alert").filter({hasText: /원본 키 값 보존/})).toBeVisible();
    await panel.getByRole("checkbox", {name: "자동 증가 컬럼에 원본 키 값 보존"}).check();
    await panel.getByRole("button", {name: "점검 (Dry-run)"}).click();
    await expect(panel.getByText(/자동 증가 컬럼 id에 원본 값을 그대로 넣고/)).toBeVisible();
    await panel.getByRole("checkbox", {name: /DB INSERT에 동의/}).check();
    await panel.getByRole("button", {name: "선택한 대상에 이전 실행"}).click();
    await expect(panel.getByText("이전 완료: 3행", {exact: true})).toBeVisible();
    await expect(panel.getByText(/시퀀스 재동기화: id → 다음 값 31/)).toBeVisible();
    expect(pg("SELECT sum(id) FROM tf_ui_key;").trim()).toBe("60"); // 원본 키 값 그대로
    pg("INSERT INTO tf_ui_key (name) VALUES ('new');");
    expect(pg("SELECT id FROM tf_ui_key WHERE name='new';").trim()).toBe("31"); // 다음 번호는 최댓값 다음
  } finally {
    try { oracle(`DROP TABLE TF_UI_KEY;`); } catch {}
    try { pg(`DROP TABLE IF EXISTS tf_ui_key;`); } catch {}
  }
});
