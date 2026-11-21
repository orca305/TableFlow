import { test, expect } from "@playwright/test";
import { MYSQL, connect, mysql } from "./helpers";

// 파일 → 실제 MySQL 적재(전체 성공 / 제약 위반 시 전체 취소). 일회용 테스트 컨테이너(infra/jdbc/compose.yml)와
// TABLEFLOW_TEST_JDBC=true, 백엔드 TABLEFLOW_JDBC_ENABLED/LOAD_ENABLED=true 가 필요합니다. 운영·개인 DB에는 사용하지 마세요.
test.skip(process.env.TABLEFLOW_TEST_JDBC !== "true", "실제 DB 컨테이너 필요: TABLEFLOW_TEST_JDBC=true");

test.beforeAll(() => mysql(`DROP TABLE IF EXISTS TB_EMPLOYEE; DROP TABLE IF EXISTS tableflow_load_receipts;
CREATE TABLE TB_EMPLOYEE (EMP_NO VARCHAR(40) PRIMARY KEY, EMP_NM VARCHAR(100) NOT NULL, HIRE_DT DATE NOT NULL, SALARY DECIMAL(12,2) NOT NULL CHECK (SALARY >= 0)) ENGINE=InnoDB;
CREATE TABLE tableflow_load_receipts (request_id VARCHAR(36) PRIMARY KEY, payload_hash VARCHAR(64) NOT NULL, table_name VARCHAR(128) NOT NULL, inserted_rows INT NOT NULL) ENGINE=InnoDB;`));
test.afterAll(() => { try { mysql(`DROP TABLE IF EXISTS TB_EMPLOYEE; DROP TABLE IF EXISTS tableflow_load_receipts;`); } catch {} });

for (const shouldFail of [false, true]) {
  test(`browser MySQL load ${shouldFail ? "rolls back on constraint failure" : "commits and deduplicates retries"}`, async ({ page }) => {
    const id = `E2E_${crypto.randomUUID().replaceAll("-", "").slice(0, 10)}`;
    let requestId = "";
    try {
      await page.goto("/#file");
      const csv = `EMP_NO,EMP_NM,HIRE_DT,SALARY\n${id},Browser Tester,2026-11-19,15000\n` + (shouldFail ? `${id}_B,Rejected Row,2026-11-19,-1\n` : "");
      await page.locator('input[type="file"]').setInputFiles({ name: "load.csv", mimeType: "text/csv", buffer: Buffer.from(csv) });
      await expect(page.getByLabel("1행 EMP_NO", { exact: true })).toHaveValue(id);
      await page.getByRole("tab", { name: "컬럼 매핑" }).click();
      for (const name of ["EMP_NO", "EMP_NM", "HIRE_DT", "SALARY"]) await page.getByLabel(`${name} 대상 컬럼명`, { exact: true }).fill(name);
      await page.getByLabel("SALARY 자료형", { exact: true }).selectOption("decimal");
      await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
      await expect(page.getByText("모든 검증을 통과했습니다. 파일로 출력하거나 DB에 적재할 수 있습니다.")).toBeVisible();
      const load = page.getByRole("region", { name: "DB 적재" });
      await connect(load, "대상", MYSQL);
      await load.getByLabel("대상 스키마", { exact: true }).selectOption("tableflow");
      await load.getByRole("radio", { name: "기존 테이블에 적재" }).check();
      await load.getByLabel("대상 테이블", { exact: true }).selectOption("TB_EMPLOYEE");
      await load.getByRole("button", { name: "대상·스키마 점검" }).click();
      const send = load.getByRole("button", { name: "DB 적재", exact: true });
      await expect(send).toBeDisabled();
      await load.getByRole("checkbox", { name: /실제 DB에 적재하는 것에 동의/ }).check();
      const sent = page.waitForRequest("**/connections/load");
      await send.click();
      const body = (await sent).postDataJSON(); requestId = body.requestId;
      if (shouldFail) {
        await expect(page.getByRole("alert").filter({ hasText: "전체 적재를 취소했습니다" })).toBeVisible();
        expect(mysql(`SELECT COUNT(*) FROM TB_EMPLOYEE WHERE EMP_NO IN ('${id}','${id}_B')`).trim()).toBe("0");
        expect(mysql(`SELECT COUNT(*) FROM tableflow_load_receipts WHERE request_id='${requestId}'`).trim()).toBe("0");
      } else {
        await expect(page.getByText(/적재 완료: 1행/).first()).toBeVisible();
        expect(mysql(`SELECT COUNT(*) FROM TB_EMPLOYEE WHERE EMP_NO='${id}' AND SALARY=15000`).trim()).toBe("1");
        const replay = await page.request.post("http://localhost:18080/api/v1/connections/load", { data: body });
        expect(replay.status()).toBe(200); expect((await replay.json()).replayed).toBe(true);
        expect(mysql(`SELECT COUNT(*) FROM TB_EMPLOYEE WHERE EMP_NO='${id}'`).trim()).toBe("1");
        await page.screenshot({ path: "test-results/mysql-loaded.png", fullPage: true });
      }
    } finally {
      mysql(`DELETE FROM TB_EMPLOYEE WHERE EMP_NO IN ('${id}','${id}_B')`);
      if (requestId) mysql(`DELETE FROM tableflow_load_receipts WHERE request_id='${requestId}'`);
    }
  });
}

test("browser file → MySQL: 신규 테이블 생성 후 적재", async ({ page }) => {
  mysql(`DROP TABLE IF EXISTS TB_FILE_NEW`);
  try {
    await page.goto("/#file");
    await page.getByRole("button", { name: "기본 사원정보", exact: true }).click();
    await expect(page.getByLabel("1행 이름", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
    await expect(page.getByText(/모든 검증을 통과했습니다/)).toBeVisible();
    const load = page.getByRole("region", { name: "DB 적재" });
    await connect(load, "대상", MYSQL);
    await load.getByLabel("대상 스키마", { exact: true }).selectOption("tableflow");
    await load.getByLabel("신규 테이블 이름").fill("TB_FILE_NEW");
    await load.getByRole("group", { name: "기본 키 컬럼" }).getByLabel("EMP_NO", { exact: true }).check();
    await load.getByRole("button", { name: "대상·스키마 점검" }).click();
    await expect(load.getByText(/적재하면 아래 테이블이 새로 생성됩니다/)).toBeVisible();
    expect(mysql(`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='tableflow' AND table_name='TB_FILE_NEW'`).trim()).toBe("0"); // 점검은 만들지 않는다
    await load.getByRole("checkbox", { name: /실제 DB에 적재하는 것에 동의/ }).check();
    await load.getByRole("button", { name: "DB 적재", exact: true }).click();
    await expect(page.getByText(/적재 완료: 3행/).first()).toBeVisible();
    expect(mysql(`SELECT COUNT(*) FROM TB_FILE_NEW`).trim()).toBe("3");
    expect(mysql(`SELECT GROUP_CONCAT(column_name) FROM information_schema.key_column_usage WHERE table_schema='tableflow' AND table_name='TB_FILE_NEW' AND constraint_name='PRIMARY'`).trim()).toBe("EMP_NO"); // 선택한 기본 키
  } finally {
    try { mysql(`DROP TABLE IF EXISTS TB_FILE_NEW`); } catch {}
  }
});
