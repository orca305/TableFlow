import { test, expect } from "@playwright/test";
import { execFileSync } from "node:child_process";

function sql(query: string) {
  return execFileSync("docker", ["compose", "exec", "-T", "mysql", "sh", "-c", 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -e "$1"', "mysql", query], { cwd: "..", encoding: "utf8" }).trim();
}
for (const shouldFail of [false, true]) {
  test(`browser MySQL load ${shouldFail ? "rolls back on constraint failure" : "commits and deduplicates retries"}`, async ({ page }) => {
    test.skip(process.env.TABLEFLOW_TEST_MYSQL !== "true", "Requires isolated compose MySQL");
    const id = `E2E_${crypto.randomUUID().replaceAll("-", "").slice(0, 10)}`;
    let requestId = "";
    try {
      await page.goto("/");
      const csv = `EMP_NO,EMP_NM,HIRE_DT,SALARY\n${id},Browser Tester,2026-11-19,15000\n` + (shouldFail ? `${id}_B,Rejected Row,2026-11-19,-1\n` : "");
      await page.locator('input[type="file"]').setInputFiles({name:"load.csv", mimeType:"text/csv", buffer:Buffer.from(csv)});
      await expect(page.getByLabel("1행 EMP_NO", { exact:true })).toHaveValue(id);
      await page.getByRole("tab", {name:"컬럼 매핑"}).click();
      for (const name of ["EMP_NO", "EMP_NM", "HIRE_DT", "SALARY"]) await page.getByLabel(`${name} 대상 컬럼명`, {exact:true}).fill(name);
      await page.getByLabel("SALARY 자료형", {exact:true}).selectOption("decimal");
      await page.getByRole("button", {name:"데이터 검증",exact:true}).click();
      await expect(page.getByText("모든 검증을 통과했습니다. 파일을 내보낼 수 있습니다.")).toBeVisible();
      await page.getByRole("button", {name:"적재 대상 확인",exact:true}).click();
      await page.getByRole("combobox", {name:"DB 대상 테이블",exact:true}).selectOption("TB_EMPLOYEE");
      await page.getByRole("button", {name:"연결·스키마 검사",exact:true}).click();
      await expect(page.getByRole("button",{name:"MySQL에 적재",exact:true})).toBeDisabled();
      await page.getByRole("checkbox", {name:"대상과 행 수를 확인했으며 실제 DB 적재에 동의합니다"}).check();
      const sent = page.waitForRequest("**/loads/database");
      await page.getByRole("button",{name:"MySQL에 적재",exact:true}).click();
      const body = (await sent).postDataJSON(); requestId = body.requestId;
      if (shouldFail) {
        await expect(page.getByRole("alert").filter({hasText:"전체 적재를 취소했습니다"})).toBeVisible();
        expect(sql(`SELECT COUNT(*) FROM TB_EMPLOYEE WHERE EMP_NO IN ('${id}','${id}_B')`)).toBe("0");
        expect(sql(`SELECT COUNT(*) FROM tableflow_load_receipts WHERE request_id='${requestId}'`)).toBe("0");
      } else {
        await expect(page.getByText(/적재 완료: 1행/)).toBeVisible();
        expect(sql(`SELECT COUNT(*) FROM TB_EMPLOYEE WHERE EMP_NO='${id}' AND SALARY=15000`)).toBe("1");
        const replay = await page.request.post("http://localhost:8080/api/v1/loads/database", {data:body});
        expect(replay.status()).toBe(200); expect((await replay.json()).replayed).toBe(true);
        expect(sql(`SELECT COUNT(*) FROM TB_EMPLOYEE WHERE EMP_NO='${id}'`)).toBe("1");
        await page.screenshot({path:"test-results/mysql-loaded.png",fullPage:true});
      }
    } finally {
      sql(`DELETE FROM TB_EMPLOYEE WHERE EMP_NO IN ('${id}','${id}_B')`);
      if (requestId) sql(`DELETE FROM tableflow_load_receipts WHERE request_id='${requestId}'`);
    }
  });
}
