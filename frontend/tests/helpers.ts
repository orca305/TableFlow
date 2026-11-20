import { expect, type Locator, type Page } from "@playwright/test";
import { execSync } from "node:child_process";

// 일회용 테스트 컨테이너(infra/jdbc/compose.yml)용 실행기. 운영·개인 DB에는 절대 사용하지 마세요.
export const oracle = (sql: string) => execSync("docker exec -i tableflow-jdbc-tests-oracle-1 sqlplus -s tableflow/TableFlowTest2026@//localhost/FREEPDB1", { input: sql + "\nexit\n" }).toString();
export const pg = (sql: string) => execSync("docker exec -i tableflow-jdbc-tests-postgresql-1 psql -U tableflow -d tableflow -t -A", { input: sql }).toString();
export const mysql = (sql: string) => execSync("docker exec -i tableflow-jdbc-tests-mysql-1 mysql -utableflow -pTableFlowTest2026 tableflow -N", { input: sql }).toString();

export type Conn = { dbms: "oracle" | "postgresql" | "mysql" | "mariadb"; port: string; db: string };
export const ORACLE: Conn = { dbms: "oracle", port: "11521", db: "FREEPDB1" };
export const PG: Conn = { dbms: "postgresql", port: "15432", db: "tableflow" };
export const MYSQL: Conn = { dbms: "mysql", port: "13316", db: "tableflow" };
export const receipts = `CREATE TABLE IF NOT EXISTS tableflow_load_receipts (request_id varchar(36) PRIMARY KEY, payload_hash varchar(64) NOT NULL, table_name varchar(128) NOT NULL, inserted_rows integer NOT NULL);`;

/** 신규 연결을 입력하고 연결 확인까지 마친다(테스트용 고정 계정, TLS 없음). */
export async function connect(region: Locator, role: "원본" | "대상", c: Conn, password = "TableFlowTest2026") {
  await region.getByLabel(`${role} 연결`, { exact: true }).selectOption("new");
  await region.getByLabel(`${role} 종류`, { exact: true }).selectOption(c.dbms);
  await region.getByLabel(`${role} 포트`, { exact: true }).fill(c.port);
  await region.getByLabel(`${role} 데이터베이스`, { exact: true }).fill(c.db);
  await region.getByLabel(`${role} 사용자`, { exact: true }).fill("tableflow");
  await region.getByLabel(`${role} 비밀번호`, { exact: true }).fill(password);
  await region.getByRole("button", { name: "연결 확인", exact: true }).click();
  await expect(region.getByRole("status", { name: `${role} 연결 상태` })).toContainText("연결 성공");
}

/** DB 이관: Oracle 원본 테이블을 고르고 '원본 확정'까지 진행한다. */
export async function confirmSource(page: Page, table: string, schema = "TABLEFLOW") {
  await page.goto("/");
  const src = page.getByRole("region", { name: "원본 선택" });
  await connect(src, "원본", ORACLE);
  await src.getByLabel("원본 스키마", { exact: true }).selectOption(schema);
  await src.getByLabel("원본 테이블", { exact: true }).selectOption(table);
  await src.getByRole("button", { name: "원본 확정", exact: true }).click();
  return page.getByRole("region", { name: "대상 선택" });
}
/** DB 이관: PostgreSQL 대상 연결 + 스키마까지 선택한다(테이블 모드는 호출자가 고른다). */
export async function connectTarget(page: Page) {
  const dst = page.getByRole("region", { name: "대상 선택" });
  await connect(dst, "대상", PG);
  await dst.getByLabel("대상 스키마", { exact: true }).selectOption("public");
  return dst;
}
/** DB 이관: 기존 PostgreSQL 테이블을 대상으로 확정하고 검토 패널을 돌려준다. */
export async function confirmExistingTarget(page: Page, table: string) {
  const dst = await connectTarget(page);
  await dst.getByRole("radio", { name: "기존 테이블에 적재" }).check();
  await dst.getByLabel("대상 테이블", { exact: true }).selectOption(table);
  await dst.getByRole("button", { name: "대상 확정", exact: true }).click();
  return page.getByRole("region", { name: "검토" });
}
export const runPanel = (page: Page) => page.getByRole("region", { name: "이관 실행" });
export const historyRows = (page: Page) => page.getByRole("region", { name: "작업 이력" });
export async function openHistory(page: Page) {
  await page.getByRole("navigation", { name: "메뉴" }).getByRole("button", { name: "작업 이력", exact: true }).click();
  const region = historyRows(page);
  await region.getByRole("button", { name: "새로고침" }).click();
  return region;
}
