import { test, expect, type Page } from "@playwright/test";

// DB 없이 화면 계약만 검증하는 테스트(API는 모의 응답). 실제 DB 경로는 migration/db-to-file/database 스펙이 검증한다.
const drivers = (over: Record<string, unknown> = {}) => ({ enabled: true, loadEnabled: true, savedEnabled: false, savePasswordEnabled: false, defaultChunkSize: 5000,
  drivers: [{ dbms: "postgresql", defaultPort: 5432, available: true, version: "42.7" }], ...over });
async function mockDb(page: Page, opts: { over?: Record<string, unknown>; tables?: string[]; columns?: unknown[] } = {}) {
  await page.route("**/connections/drivers", r => r.fulfill({ json: drivers(opts.over) }));
  await page.route("**/connections/saved", r => r.fulfill({ json: { enabled: false, savePasswordEnabled: false, items: [] } }));
  await page.route("**/connections/test", r => r.fulfill({ json: { product: "PostgreSQL (mock)", serverVersion: "UI contract", driver: "mock", driverVersion: "0" } }));
  await page.route("**/connections/schemas", r => r.fulfill({ json: { items: ["public"], truncated: false } }));
  await page.route("**/connections/tables", r => r.fulfill({ json: { items: opts.tables ?? ["TB_EMPLOYEE"], truncated: false } }));
  await page.route("**/connections/columns", r => r.fulfill({ json: { items: opts.columns ?? [], truncated: false } }));
}
const col = (name: string, position: number, over: Record<string, unknown> = {}) => ({ name, type: "varchar", jdbcType: 12, size: 20, scale: 0, nullable: true, primaryKey: position === 1, autoIncrement: false, position, ...over });
async function fillConnection(region: ReturnType<Page["getByRole"]>, role: "원본" | "대상") {
  await region.getByLabel(`${role} 데이터베이스`, { exact: true }).fill("tableflow");
  await region.getByLabel(`${role} 사용자`, { exact: true }).fill("tester");
  await region.getByLabel(`${role} 비밀번호`, { exact: true }).fill("temporary-local-value");
  await region.getByRole("button", { name: "연결 확인", exact: true }).click();
  await expect(region.getByRole("status", { name: `${role} 연결 상태` })).toContainText("연결 성공");
}

test("DB connection is disabled without pretending to connect, and passwords never reach browser storage", async ({ page }) => {
  await mockDb(page, { over: { enabled: false, loadEnabled: false } });
  await page.goto("/");
  await expect(page.getByText(/서버에서 DB 조회가 비활성화/)).toBeVisible();
  const src = page.getByRole("region", { name: "원본 선택" });
  await expect(src.getByRole("button", { name: "연결 확인", exact: true })).toBeDisabled();
  await src.getByLabel("원본 비밀번호", { exact: true }).fill("temporary-local-value");
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain("temporary-local-value");
  // 비밀번호 저장은 서버가 허용하지 않으면 선택할 수 없다.
  await expect(src.getByRole("checkbox", { name: "비밀번호 저장" })).toBeDisabled();
});

test("saved connection: password is never shown, saved password connects without typing, delete needs confirmation", async ({ page }) => {
  const saved = { id: "123e4567-e89b-12d3-a456-426614174000", name: "테스트 PG", dbms: "postgresql", host: "localhost", port: 5432, database: "tableflow", username: "tester", tls: false, passwordSaved: true, savedAt: "2026-11-20T00:00:00Z" };
  const items = [saved]; let tested: any = null; let deleted = false;
  await page.route("**/connections/drivers", r => r.fulfill({ json: drivers({ savedEnabled: true, savePasswordEnabled: true }) }));
  await page.route("**/connections/saved", r => r.fulfill({ json: { enabled: true, savePasswordEnabled: true, items: deleted ? [] : items } }));
  await page.route("**/connections/saved/*", r => { deleted = true; return r.fulfill({ json: { deleted: true } }); });
  await page.route("**/connections/test", r => { tested = r.request().postDataJSON(); return r.fulfill({ json: { product: "PostgreSQL (mock)", serverVersion: "1", driver: "mock", driverVersion: "0" } }); });
  await page.route("**/connections/schemas", r => r.fulfill({ json: { items: ["public"], truncated: false } }));
  await page.goto("/");
  const src = page.getByRole("region", { name: "원본 선택" });
  await src.getByLabel("원본 연결", { exact: true }).selectOption(saved.id);
  // 저장된 비밀번호로 자동 연결: 요청에는 비밀번호 대신 저장된 연결 ID만 실린다.
  await expect(src.getByRole("status", { name: "원본 연결 상태" })).toContainText("연결 성공");
  expect(tested.savedId).toBe(saved.id); expect(tested.password).toBe("");
  await expect(src.getByLabel("원본 비밀번호", { exact: true })).toHaveValue("");
  await expect(src.getByLabel("원본 비밀번호", { exact: true })).toHaveAttribute("placeholder", "저장된 비밀번호 사용");
  await expect(src.getByRole("checkbox", { name: "비밀번호 저장" })).toBeChecked();
  // 삭제는 한 번 더 확인한다.
  await src.getByRole("button", { name: "연결 삭제" }).click();
  expect(deleted).toBe(false);
  await src.getByRole("button", { name: "삭제 확인" }).click();
  await expect.poll(() => deleted).toBe(true);
});

test("target load UI contract: new-table plan, consent, retry with the same request id (mock DB)", async ({ page }) => {
  await mockDb(page, { columns: [col("EMP_NO", 1)] });
  await page.route("**/connections/load/check", r => { const b = r.request().postDataJSON(); return r.fulfill({ json: b.createTable
    ? { dbms: "postgresql", schema: "public", tableName: b.tableName, rows: 3, columns: ["EMP_NO"], createsTable: true, ddl: [`CREATE TABLE "public"."${b.tableName}" (...)`], warnings: ["기본 키 없이 생성됩니다. 필요하면 적재 후 DB에서 직접 추가하세요."] }
    : { dbms: "postgresql", schema: "public", tableName: b.tableName, rows: 3, columns: ["EMP_NO"] } }); });
  const attempts: any[] = [];
  await page.route("**/connections/load", async r => {
    const body = r.request().postDataJSON(); attempts.push(body);
    expect(body.confirmed).toBe(true); expect(body.connection.dbms).toBe("postgresql"); expect(body.table.rows).toHaveLength(3);
    if (attempts.length === 1) { await r.fulfill({ status: 503, json: { code: "LOAD_OUTCOME_UNKNOWN", message: "커밋 결과 미확인: 같은 요청으로 재시도해주세요." } }); return; }
    await r.fulfill({ json: { insertedRows: 3, replayed: true, requestId: body.requestId } });
  });
  await page.goto("/#file");
  await page.getByRole("button", { name: "샘플 데이터로 시작", exact: true }).click();
  await expect(page.getByLabel("1행 이름", { exact: true })).toBeVisible();
  const load = page.getByRole("region", { name: "DB 적재" });
  await expect(load.getByRole("button", { name: "대상·스키마 점검" })).toBeDisabled(); // 검증 전에는 점검할 수 없다
  await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
  await fillConnection(load, "대상");
  await load.getByLabel("대상 스키마", { exact: true }).selectOption("public");
  await load.getByLabel("신규 테이블 이름").fill("TB_NEW");
  await load.getByRole("button", { name: "대상·스키마 점검" }).click();
  await expect(load.getByText(/적재하면 아래 테이블이 새로 생성됩니다/)).toBeVisible();
  await expect(load.getByText(/기본 키 없이 생성됩니다/)).toBeVisible();
  const send = load.getByRole("button", { name: "DB 적재", exact: true });
  await expect(send).toBeDisabled(); // 동의 전에는 실행할 수 없다
  await load.getByRole("checkbox", { name: /실제 DB에 적재하는 것에 동의/ }).check();
  await send.click();
  await expect(page.getByRole("alert").filter({ hasText: "커밋 결과 미확인" })).toBeVisible();
  await send.click(); // 같은 요청 ID로 재시도
  await expect(page.getByText(/적재 완료: 3행/).first()).toBeVisible();
  expect(attempts).toHaveLength(2);
  expect(attempts[0].requestId).toMatch(/^[a-f0-9-]{36}$/); expect(attempts[1].requestId).toBe(attempts[0].requestId);
  expect(attempts[0].createTable).toBe(true); expect(attempts[0].tableName).toBe("TB_NEW");
});

test("source DB extraction UI contract: column pick → workbench → CSV (mock DB read, real validate/export)", async ({ page }) => {
  await mockDb(page, { over: { loadEnabled: false }, tables: ["EMP"], columns: [col("EMP_NO", 1), col("EMP_NM", 2), col("MEMO", 3)] });
  let payload: any = null;
  await page.route("**/connections/read", async r => { payload = r.request().postDataJSON(); await r.fulfill({ json: {
    schema_version: "1.0", table_id: "t-1", name: "EMP", source: { filename: "public.EMP", type: "database", extraction_method: "jdbc" },
    columns: [{ id: "col_1", name: "EMP_NO", target_name: "EMP_NO", data_type: "string", nullable: true, included: true, max_length: null, precision: null, scale: null },
              { id: "col_2", name: "EMP_NM", target_name: "EMP_NM", data_type: "string", nullable: true, included: true, max_length: null, precision: null, scale: null }],
    rows: [{ id: "row_1", cells: { col_1: { raw: "001", value: "001", confidence: null, edited: false }, col_2: { raw: "홍길동", value: "홍길동", confidence: null, edited: false } } },
           { id: "row_2", cells: { col_1: { raw: "002", value: "002", confidence: null, edited: false }, col_2: { raw: "이도윤", value: "이도윤", confidence: null, edited: false } } }] } }); });
  await page.goto("/#file");
  await page.getByRole("tab", { name: "DB 테이블에서 추출" }).click();
  const source = page.getByRole("region", { name: "데이터 소스" });
  await fillConnection(source, "원본");
  await source.getByLabel("원본 스키마", { exact: true }).selectOption("public");
  await source.getByLabel("원본 테이블", { exact: true }).selectOption("EMP");
  await source.getByLabel("MEMO 추출", { exact: true }).uncheck();
  await source.getByLabel("최대 추출 행 수", { exact: true }).fill("500");
  await source.getByRole("button", { name: "선택한 컬럼 행 추출", exact: true }).click();
  expect(payload.columns).toEqual(["EMP_NO", "EMP_NM"]); expect(payload.limit).toBe(500); expect(payload.schema).toBe("public"); expect(payload.table).toBe("EMP");
  await expect(page.getByLabel("2행 EMP_NM", { exact: true })).toHaveValue("이도윤");
  await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
  await page.getByRole("button", { name: "CSV 파일", exact: true }).click();
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "CSV 내보내기 ↓" }).click()]);
  expect(download.suggestedFilename()).toBe("tableflow.csv");
});

test("target DB column mapping suggestion: alias match, explicit apply, nothing auto-confirmed (mock metadata)", async ({ page }) => {
  await mockDb(page, { tables: ["EMP"], columns: [col("EMPNO", 1), col("EMP_NAME", 2), col("HIREDATE", 3, { type: "date", jdbcType: 91 }), col("PAY", 4, { type: "numeric", jdbcType: 3 })] });
  await page.goto("/#file");
  await page.getByRole("button", { name: "샘플 데이터로 시작", exact: true }).click();
  await expect(page.getByLabel("1행 이름", { exact: true })).toBeVisible();
  const load = page.getByRole("region", { name: "DB 적재" });
  await fillConnection(load, "대상");
  await load.getByLabel("대상 스키마", { exact: true }).selectOption("public");
  await load.getByRole("radio", { name: "기존 테이블에 적재" }).check();
  await load.getByLabel("대상 테이블", { exact: true }).selectOption("EMP");
  await load.getByRole("button", { name: "DB 컬럼 기준 자동 매핑 추천", exact: true }).click();
  await expect(load.getByRole("row", { name: /이름 EMP_NAME 사용자·업무 용어 매핑/ })).toBeVisible();
  // 추천만으로는 원본 매핑이 바뀌지 않는다.
  await page.getByRole("tab", { name: /컬럼 매핑/ }).click();
  await expect(page.getByLabel("사원번호 대상 컬럼명", { exact: true })).toHaveValue("EMP_NO");
  await load.getByRole("button", { name: "추천 4개 적용", exact: true }).click();
  await expect(page.getByLabel("사원번호 대상 컬럼명", { exact: true })).toHaveValue("EMPNO");
  await expect(page.getByLabel("이름 대상 컬럼명", { exact: true })).toHaveValue("EMP_NAME");
  await expect(page.getByLabel("입사일 대상 컬럼명", { exact: true })).toHaveValue("HIREDATE");
  await expect(page.getByLabel("급여 대상 컬럼명", { exact: true })).toHaveValue("PAY");
});

test("DB 이관 UI contract: mapping status, split settings and gating (mock DB)", async ({ page }) => {
  await mockDb(page, { tables: ["EMP"], columns: [col("EMP_NO", 1), col("EMP_NM", 2, { size: 50 }), col("SALARY", 3, { type: "numeric", jdbcType: 3, size: 12, scale: 2 })] });
  await page.route("**/connections/count", r => r.fulfill({ json: { rows: 12500 } }));
  await page.route("**/connections/read", r => r.fulfill({ json: { schema_version: "1.0", table_id: "t", name: "EMP", source: { filename: "public.EMP", type: "database", extraction_method: "jdbc" },
    columns: [{ id: "c1", name: "EMP_NO", target_name: "EMP_NO", data_type: "string", nullable: true, included: true, max_length: null, precision: null, scale: null }],
    rows: Array.from({ length: 25 }, (_, i) => ({ id: `r${i}`, cells: { c1: { raw: String(i + 1), value: String(i + 1), confidence: null, edited: false } } })) } }));
  let analysisBody: any = null; let level = "error";
  await page.route("**/connections/migrate/analyze", r => { analysisBody = r.request().postDataJSON(); return r.fulfill({ json: { sourceRows: 12500, maxRows: 1000000, notes: [], warnings: [], createsTable: false, ddl: [],
    columns: [{ source: "EMP_NO", target: "EMP_NO", sourceType: "varchar", targetType: "varchar", conversion: "", level: "ok", detail: "" },
              { source: "EMP_NM", target: "EMP_NM", sourceType: "varchar", targetType: "varchar", conversion: "", level, detail: level === "error" ? "원본 값 중 최대 30자로, 대상 한도(20자)를 넘는 값이 1건 있습니다." : "" },
              { source: "SALARY", target: "SALARY", sourceType: "numeric", targetType: "numeric", conversion: "", level: "warn", detail: "소수점 이하 값은 반올림될 수 있습니다." }] } }); });
  await page.route("**/connections/migrate/check", r => r.fulfill({ json: { sourceDbms: "postgresql", targetDbms: "postgresql", source: "public.EMP", target: "public.EMP2", sourceRows: 12500, rows: 12500,
    columns: [], notes: [], createsTable: false, ddl: [], warnings: [], bulk: true, chunks: 3, chunkSize: 5000 } }));
  await page.goto("/");
  const src = page.getByRole("region", { name: "원본 선택" });
  await fillConnection(src, "원본");
  await src.getByLabel("원본 스키마", { exact: true }).selectOption("public");
  await src.getByLabel("원본 테이블", { exact: true }).selectOption("EMP");
  await src.getByRole("button", { name: "원본 확정", exact: true }).click();
  const dst = page.getByRole("region", { name: "대상 선택" });
  await fillConnection(dst, "대상");
  await dst.getByLabel("대상 스키마", { exact: true }).selectOption("public");
  await dst.getByLabel("신규 테이블 이름").fill("EMP2");
  // 분할 단위: 직접 입력하거나 빠른 선택, 범위를 벗어나면 막는다.
  await expect(dst.getByRole("status").filter({ hasText: "총 12,500행 ÷ 5,000행 = 3회 분할" })).toBeVisible();
  await dst.getByRole("button", { name: "2,500" }).click();
  await expect(dst.getByRole("status").filter({ hasText: "총 12,500행 ÷ 2,500행 = 5회 분할" })).toBeVisible();
  await dst.getByLabel("분할 단위(행)", { exact: true }).fill("50");
  await expect(dst.getByText("분할 단위는 100~10,000행 사이로 입력하세요")).toBeVisible();
  await expect(dst.getByRole("button", { name: "대상 확정", exact: true })).toBeDisabled();
  await dst.getByLabel("분할 단위(행)", { exact: true }).fill("2500");
  await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
  // 검토: 컬럼별 상태. 오류가 있으면 이관 시작을 누를 수 없다.
  const review = page.getByRole("region", { name: "검토" });
  await expect(review.getByText("대상 한도(20자)를 넘는 값이 1건")).toBeVisible();
  expect(analysisBody.chunkSize).toBe(2500); expect(analysisBody.createTable).toBe(true); expect(analysisBody.targetTable).toBe("EMP2");
  await expect(review.getByText("매핑 3건 · 경고 1건 · 오류 1건 — 오류를 해결해야 이관할 수 있습니다")).toBeVisible();
  await expect(page.getByRole("button", { name: "이관 시작" })).toBeDisabled();
  await expect(page.getByText("검증 오류 1건을 해결해야 이관할 수 있습니다")).toBeVisible();
  // 미리보기도 페이지 단위로 나눠 보여준다(샘플 25행 → 10행씩).
  await expect(review.getByText("1–10 / 25행")).toBeVisible();
  await review.getByRole("button", { name: "다음" }).click();
  await expect(review.getByText("11–20 / 25행")).toBeVisible();
  // 오류를 고치면(대상에서 제외) 점검과 동의를 거쳐 이관할 수 있다.
  level = "ok";
  await review.getByLabel("EMP_NM 대상 컬럼", { exact: true }).fill("EMP_NAME");
  await expect(review.getByText("매핑 3건 · 경고 1건 · 오류 0건")).toBeVisible();
  await page.getByRole("button", { name: "사전 점검" }).click();
  await expect(page.getByText(/대용량: 3회 분할 × 최대 5,000행/)).toBeVisible();
  await expect(page.getByRole("button", { name: "이관 시작" })).toBeDisabled(); // 동의 전
  await page.getByRole("checkbox", { name: /실제 DB INSERT에 동의/ }).check();
  await expect(page.getByRole("button", { name: "이관 시작" })).toBeEnabled();
});
