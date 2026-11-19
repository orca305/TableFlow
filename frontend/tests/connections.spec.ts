import {test, expect} from "@playwright/test";

test("DB explorer exposes disabled state without pretending to connect", async ({page}) => {
  await page.goto("/");
  await page.getByRole("button",{name:"데이터베이스 연결",exact:true}).click();
  await expect(page.getByText(/서버에서 DB 탐색이 비활성화/)).toBeVisible();
  await expect(page.getByRole("button",{name:"연결 테스트",exact:true})).toBeDisabled();
  await page.getByLabel("DB 비밀번호",{exact:true}).fill("temporary-local-value");
  await page.getByRole("button",{name:"연결 정보 지우기",exact:true}).click();
  await expect(page.getByLabel("DB 비밀번호",{exact:true})).toHaveValue("");
  await page.getByLabel("DB 비밀번호",{exact:true}).fill("temporary-local-value");
  await page.getByRole("button",{name:"파일 업로드",exact:true}).click();
  await page.getByRole("button",{name:"데이터베이스 연결",exact:true}).click();
  await expect(page.getByLabel("DB 비밀번호",{exact:true})).toHaveValue("");
  expect(await page.evaluate(() => JSON.stringify({...localStorage,...sessionStorage}))).not.toContain("temporary-local-value");
});

test("target load UI contract: confirmation, payload and schema invalidation (mock DB)", async ({page}) => {
  await page.route("**/connections/drivers",r=>r.fulfill({json:{enabled:true,loadEnabled:true,drivers:[{dbms:"postgresql",defaultPort:5432,available:true,version:"42.7"}]}}));
  await page.route("**/connections/test",r=>r.fulfill({json:{product:"PostgreSQL (mock)",serverVersion:"UI contract",driver:"mock",driverVersion:"0"}}));
  await page.route("**/connections/schemas",r=>r.fulfill({json:{items:["public"],truncated:false}}));
  await page.route("**/connections/tables",r=>r.fulfill({json:{items:["TB_EMPLOYEE"],truncated:false}}));
  await page.route("**/connections/columns",r=>r.fulfill({json:{items:[{name:"EMP_NO",type:"varchar",size:20,scale:0,nullable:false,primaryKey:true,autoIncrement:false,position:1}],truncated:false}}));
  await page.route("**/connections/load/check",r=>r.fulfill({json:{dbms:"postgresql",schema:"public",tableName:"TB_EMPLOYEE",rows:3,columns:["EMP_NO","EMP_NM","HIRE_DT","SALARY"]}}));
  let requestId="";
  await page.route("**/connections/load",async r=>{const body=r.request().postDataJSON();requestId=body.requestId;expect(body.confirmed).toBe(true);expect(body.connection.dbms).toBe("postgresql");expect(body.table.rows).toHaveLength(3);await r.fulfill({json:{insertedRows:3,replayed:false,requestId}});});
  await page.goto("/");await page.getByRole("button",{name:"샘플 데이터로 시작",exact:true}).click();
  await expect(page.getByLabel("1행 이름",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"데이터 검증",exact:true}).click();
  await page.getByRole("button",{name:"데이터베이스 적재",exact:true}).click();
  const explorer=page.getByLabel("대상 DB 메타데이터 탐색",{exact:true});
  await explorer.getByLabel("데이터베이스명",{exact:true}).fill("tableflow");await explorer.getByLabel("DB 사용자",{exact:true}).fill("tester");
  await explorer.getByRole("button",{name:"연결 테스트",exact:true}).click();await explorer.getByRole("button",{name:"스키마 조회",exact:true}).click();
  await explorer.getByLabel("스키마 / 카탈로그",{exact:true}).selectOption("public");await explorer.getByLabel("테이블 / 뷰",{exact:true}).selectOption("TB_EMPLOYEE");
  await explorer.getByRole("button",{name:"대상·스키마 검사",exact:true}).click();
  await expect(explorer.getByRole("button",{name:"선택한 DB에 적재",exact:true})).toBeDisabled();
  await explorer.getByRole("checkbox",{name:"이 대상과 행 수를 확인했으며 실제 DB INSERT에 동의합니다"}).check();await explorer.getByRole("button",{name:"선택한 DB에 적재",exact:true}).click();
  await expect(explorer.getByText("적재 완료: 3행",{exact:true})).toBeVisible();expect(requestId).toMatch(/^[a-f0-9-]{36}$/);
  await explorer.getByLabel("DB 호스트",{exact:true}).fill("127.0.0.1");await expect(explorer.getByRole("button",{name:"선택한 DB에 적재",exact:true})).toHaveCount(0);
});
