import { test, expect } from "@playwright/test";
import path from "node:path";
import { readFile } from "node:fs/promises";

test("text PDF pages are selectable and preserve edits", async ({ page }) => {
  await page.goto("/");
  await page.locator('input[type="file"]').setInputFiles(path.resolve("public/samples/table-text.pdf"));
  await expect(page.getByText("PDF 텍스트", { exact: true })).toBeVisible();
  await expect(page.getByLabel("1행 Name", { exact: true })).toHaveValue("Alice");
  await page.getByLabel("1행 Name", { exact: true }).fill("Edited Alice");
  await page.getByRole("combobox", { name: "추출된 표", exact: true }).selectOption("1");
  await expect(page.getByLabel("1행 Name", { exact: true })).toHaveValue("Bob");
  await page.getByRole("combobox", { name: "추출된 표", exact: true }).selectOption("0");
  await expect(page.getByLabel("1행 Name", { exact: true })).toHaveValue("Edited Alice");
});

for (const [filename, column, expected] of [["table-en.png", "Name", "Alice"], ["table-en.jpg", "Name", "Alice"], ["table-ko.png", "이름", "김하늘"], ["table-scan.pdf", "Name", "Alice"]]) {
  test(`real local OCR review and export: ${filename}`, async ({ page }) => {
    test.setTimeout(60000);
    test.skip(process.env.TABLEFLOW_TEST_LOCAL_OCR !== "true", "Requires real local OCR service");
    await page.goto("/");
    await page.locator('input[type="file"]').setInputFiles(path.resolve(`public/samples/${filename}`));
    await expect(page.getByText("로컬 OCR", { exact: true })).toBeVisible({ timeout: 50000 });
    await expect(page.getByLabel(`1행 ${column}`, { exact: true })).toHaveValue(expected);
    await expect(page.getByText(/신뢰도 \d+%/).first()).toBeVisible();
    await page.getByRole("button", { name: "데이터 검증", exact: true }).click();
    await expect(page.getByText("모든 검증을 통과했습니다. 파일을 내보낼 수 있습니다.")).toBeVisible();
    await expect(page.getByRole("button", { name: "SQL 내보내기" })).toBeDisabled();
    await page.getByRole("checkbox", { name: "검증된 OCR 결과를 원본과 대조하여 검토했습니다" }).check();
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "SQL 내보내기" }).click();
    const sql = await readFile((await (await download).path())!, "utf8");
    expect(sql).toContain(expected); expect(sql).toContain("15000");
    if (filename === "table-ko.png") {
      const excel = page.waitForEvent("download");
      await page.getByRole("button", { name: "Excel 파일", exact: true }).click();
  await page.getByRole("button", { name: "Excel ↓", exact: true }).click();
      expect((await readFile((await (await excel).path())!)).subarray(0, 2).toString()).toBe("PK");
      await page.screenshot({ path: "test-results/ocr-reviewed.png", fullPage: true });
    }
  });
}

test("OCR unavailability leaves the existing UI recoverable", async ({ page }) => {
  await page.route("**/api/v1/files/parse", route => route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ code: "OCR_UNAVAILABLE", message: "OCR 서비스에 연결할 수 없습니다." }) }));
  await page.goto("/");
  await page.locator('input[type="file"]').setInputFiles(path.resolve("public/samples/table-en.png"));
  await expect(page.getByRole("alert").filter({ hasText: "OCR 서비스에 연결할 수 없습니다" })).toBeVisible();
  await expect(page.getByRole("button", { name: "파일 선택", exact: true })).toBeEnabled();
});
