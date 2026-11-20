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

test("source DB extraction UI contract: column pick → workbench → CSV (mock DB read, real validate/export)", async ({page}) => {
  await page.route("**/connections/drivers",r=>r.fulfill({json:{enabled:true,loadEnabled:false,drivers:[{dbms:"postgresql",defaultPort:5432,available:true,version:"42.7"}]}}));
  await page.route("**/connections/test",r=>r.fulfill({json:{product:"PostgreSQL (mock)",serverVersion:"UI contract",driver:"mock",driverVersion:"0"}}));
  await page.route("**/connections/schemas",r=>r.fulfill({json:{items:["public"],truncated:false}}));
  await page.route("**/connections/tables",r=>r.fulfill({json:{items:["EMP"],truncated:false}}));
  const col=(name:string,position:number)=>({name,type:"varchar",size:20,scale:0,nullable:true,primaryKey:position===1,autoIncrement:false,position});
  await page.route("**/connections/columns",r=>r.fulfill({json:{items:[col("EMP_NO",1),col("EMP_NM",2),col("MEMO",3)],truncated:false}}));
  let payload:any=null;
  await page.route("**/connections/read",async r=>{payload=r.request().postDataJSON();await r.fulfill({json:{
    schema_version:"1.0",table_id:"t-1",name:"EMP",source:{filename:"public.EMP",type:"database",extraction_method:"jdbc"},
    columns:[{id:"col_1",name:"EMP_NO",target_name:"EMP_NO",data_type:"string",nullable:true,included:true,max_length:null,precision:null,scale:null},
             {id:"col_2",name:"EMP_NM",target_name:"EMP_NM",data_type:"string",nullable:true,included:true,max_length:null,precision:null,scale:null}],
    rows:[{id:"row_1",cells:{col_1:{raw:"001",value:"001",confidence:null,edited:false},col_2:{raw:"홍길동",value:"홍길동",confidence:null,edited:false}}},
          {id:"row_2",cells:{col_1:{raw:"002",value:"002",confidence:null,edited:false},col_2:{raw:"이도윤",value:"이도윤",confidence:null,edited:false}}}]}});});
  await page.goto("/");await page.getByRole("button",{name:"데이터베이스 연결",exact:true}).click();
  const explorer=page.getByLabel("소스 DB 메타데이터 탐색",{exact:true});
  await explorer.getByLabel("데이터베이스명",{exact:true}).fill("tableflow");await explorer.getByLabel("DB 사용자",{exact:true}).fill("tester");
  await explorer.getByRole("button",{name:"연결 테스트",exact:true}).click();await explorer.getByRole("button",{name:"스키마 조회",exact:true}).click();
  await explorer.getByLabel("스키마 / 카탈로그",{exact:true}).selectOption("public");await explorer.getByLabel("테이블 / 뷰",{exact:true}).selectOption("EMP");
  await explorer.getByLabel("MEMO 추출",{exact:true}).uncheck();await explorer.getByLabel("최대 추출 행 수",{exact:true}).fill("500");
  await explorer.getByRole("button",{name:"선택한 컬럼 행 추출",exact:true}).click();
  expect(payload.columns).toEqual(["EMP_NO","EMP_NM"]);expect(payload.limit).toBe(500);expect(payload.schema).toBe("public");expect(payload.table).toBe("EMP");
  await expect(page.getByLabel("2행 EMP_NM",{exact:true})).toHaveValue("이도윤");
  await page.getByRole("button",{name:"데이터 검증",exact:true}).click();
  await page.getByRole("button",{name:"CSV 파일",exact:true}).click();
  const [download]=await Promise.all([page.waitForEvent("download"),page.getByRole("button",{name:"CSV 내보내기 ↓"}).click()]);
  expect(download.suggestedFilename()).toBe("tableflow.csv");
});

test("target DB column mapping suggestion: alias match, explicit apply, nothing auto-confirmed (mock metadata)", async ({page}) => {
  await page.route("**/connections/drivers",r=>r.fulfill({json:{enabled:true,loadEnabled:true,drivers:[{dbms:"postgresql",defaultPort:5432,available:true,version:"42.7"}]}}));
  await page.route("**/connections/test",r=>r.fulfill({json:{product:"PostgreSQL (mock)",serverVersion:"UI contract",driver:"mock",driverVersion:"0"}}));
  await page.route("**/connections/schemas",r=>r.fulfill({json:{items:["public"],truncated:false}}));
  await page.route("**/connections/tables",r=>r.fulfill({json:{items:["EMP"],truncated:false}}));
  const col=(name:string,jdbcType:number,type:string,position:number)=>({name,type,jdbcType,size:20,scale:0,nullable:true,primaryKey:false,autoIncrement:false,position});
  await page.route("**/connections/columns",r=>r.fulfill({json:{items:[col("EMPNO",12,"varchar",1),col("EMP_NAME",12,"varchar",2),col("HIREDATE",91,"date",3),col("PAY",3,"numeric",4)],truncated:false}}));
  await page.goto("/");await page.getByRole("button",{name:"샘플 데이터로 시작",exact:true}).click();
  await expect(page.getByLabel("1행 이름",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"데이터베이스 적재",exact:true}).click();
  const explorer=page.getByLabel("대상 DB 메타데이터 탐색",{exact:true});
  await explorer.getByLabel("데이터베이스명",{exact:true}).fill("tableflow");await explorer.getByLabel("DB 사용자",{exact:true}).fill("tester");
  await explorer.getByRole("button",{name:"연결 테스트",exact:true}).click();await explorer.getByRole("button",{name:"스키마 조회",exact:true}).click();
  await explorer.getByLabel("스키마 / 카탈로그",{exact:true}).selectOption("public");await explorer.getByLabel("테이블 / 뷰",{exact:true}).selectOption("EMP");
  await explorer.getByRole("button",{name:"DB 컬럼 기준 자동 매핑 추천",exact:true}).click();
  await expect(explorer.getByRole("row",{name:/이름 EMP_NAME 사용자·업무 용어 매핑/})).toBeVisible();
  // 추천만으로는 원본 매핑이 바뀌지 않는다.
  await page.getByRole("tab",{name:/컬럼 매핑/}).click();
  await expect(page.getByLabel("사원번호 대상 컬럼명",{exact:true})).toHaveValue("EMP_NO");
  await explorer.getByRole("button",{name:"추천 4개 적용",exact:true}).click();
  await expect(page.getByLabel("사원번호 대상 컬럼명",{exact:true})).toHaveValue("EMPNO");
  await expect(page.getByLabel("이름 대상 컬럼명",{exact:true})).toHaveValue("EMP_NAME");
  await expect(page.getByLabel("입사일 대상 컬럼명",{exact:true})).toHaveValue("HIREDATE");
  await expect(page.getByLabel("급여 대상 컬럼명",{exact:true})).toHaveValue("PAY");
});
