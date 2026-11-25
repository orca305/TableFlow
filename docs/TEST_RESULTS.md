# 이번 변경 검증 결과

2026-11-19, Windows / Java 21.0.13 / Next.js 16.4.6. 실제 실행 결과만 기록합니다.

| 검사 | 결과 |
|---|---|
| `backend: .\gradlew.bat test bootJar` | 24개 중 20 통과, 실제 MySQL 통합 4 건너뜀 |
| `frontend: npm run build` | TypeScript 포함 통과 |
| `frontend: npm run test:e2e` | 12개 중 6 통과, 실제 MySQL 2·로컬 OCR 4 건너뜀 |
| `git diff --check` | 통과 |

추가 단위 테스트: 중복 4정책/첫 행 보존/1,000개 경고 뒤 실제 오류 탐지, PostgreSQL E 문자열·불리언·일시·63자 제한·소수 초 제한, MariaDB HEX, CSV BOM·RFC4180 인용·수식 보호·음수 보존, CSV/XLSX 헤더 행·스타일 끝 행/열 제외·숫자 원문·헤더 오류 힌트. 기존 Oracle/MySQL·OCR API 검토 요구·접근 제어 회귀도 통과했습니다.

브라우저: CSV 실제 업로드→매핑→오류 편집→Oracle/MySQL SQL 및 XLSX 다운로드, 샘플→중복 경고/제외→PostgreSQL SQL/CSV 다운로드, PDF 표 선택과 편집 보존, API/OCR 장애 복구 화면, 모바일 390px 가로 넘침을 확인했습니다. 스크린샷은 `frontend/test-results/workspace-*.png`입니다. 해당 폴더는 Git 제외입니다.

실제 DB 서버가 없어 이번 MySQL 적재/롤백 재검증은 하지 않았고, Python OCR 모델도 이번 실행에서는 검증하지 않았습니다. 모의 API/단위 테스트를 실서버 검증으로 간주하지 않습니다. 원격 CI/공개 배포는 미실시입니다.

## 재현

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd C:\dev\TableFlow\backend
.\gradlew.bat test bootJar
cd ..\frontend
$env:NEXT_PUBLIC_API_URL='http://localhost:18080/api/v1'
npm run build
npx playwright install chromium
npm run test:e2e
Remove-Item Env:NEXT_PUBLIC_API_URL
```

E2E 전용 포트는 API 18080/프런트 3101입니다. 평소 개발 서버 8080/3100과 분리하며 테스트 서버는 종료 시 정리됩니다. 실제 DB/OCR 테스트는 기존 문서의 서비스·환경변수를 먼저 준비해야 합니다. 테스트 후 3100에서 production start하려면 평소 API URL(8080)로 다시 빌드하세요. Next 공개 환경변수는 빌드 시 반영됩니다.

## 주요 변경 파일

- `frontend/src/app/page.tsx`, `globals.css`: 3단계 UI, 샘플·헤더·중복 정책·오류 이동·출력 선택
- `frontend/src/lib/workspace.ts`, `api.ts`, `types.ts`: 안전한 이름 제안·오류 힌트·경고
- `frontend/public/samples/`: 중복·오류 CSV 및 다중 시트 XLSX
- `backend/.../parser/FileParser.java`, `FileParseException.java`, `DocumentParser.java`: 헤더·정밀도·구조 오류
- `backend/.../transform/TableEngine.java`, `model/Table.java`: 중복 정책·severity·solution
- `backend/.../export/TableExporter.java`, `api/TableController.java`, `ApiErrors.java`: 4 dialect·CSV·정책 전달·재검증
- Java/Playwright 테스트, `frontend/playwright.config.ts`, CI 빌드 환경 및 기존 문서

## 알려진 제약

CSV 헤더 행은 빈 줄을 건너뛴 논리 레코드 번호입니다. XLSX 헤더는 실제 행 번호입니다. 잘못된 헤더 선택의 행 너비 오류 위치는 선택한 헤더 기준입니다. 중복 제외 검증은 화면 표에서 중복 행을 제거하므로 원본을 다시 불러와야 복원됩니다. CSV 수식 보호는 원문 앞에 작은따옴표를 붙이며 출력 값을 바꿉니다. XLSX General 숫자는 파일에 저장된 값까지 보존하지만 Excel 저장 단계에서 이미 손실된 자릿수는 복원하지 못합니다. DB 소스 행 추출/DB 간 이관/작업 이력은 미구현입니다.

## JDBC 단계 추가 검증

이번 JDBC 변경 뒤 Java **31개 중 27 통과·실제 MySQL 4 건너뜀**, E2E **13개 중 7 통과·실제 MySQL/OCR 6 건너뜀**, Next.js 프로덕션 빌드 통과입니다.

추가 Java 7개: 네 실제 드라이버 로딩/URL/TLS·타임아웃 속성/record 마스킹, 비활성화·호스트 거부·원격 평문 차단, 인증·타임아웃·TLS 오류 마스킹, 메타데이터 패턴 이스케이프/읽기 전용/연결·ResultSet 종료, MySQL catalog/PK/컬럼 조회, API 기본 비활성화, 원격·교차 Origin·URL 주입 거부. JDBC 연결/메타데이터 성공 경로는 Mockito 계약 테스트이며 DB 서버에 접속한 결과가 아닙니다.

새 브라우저 테스트는 실제 API의 비활성화 표시, 연결 버튼 차단, 비밀번호 지우기/화면 전환 폐기, localStorage/sessionStorage 비저장을 확인합니다. 새 파일: `connection/JdbcExplorer.java`, `api/ConnectionController.java`, `frontend/src/components/DatabaseExplorer.tsx`, `JdbcExplorerTests.java`, `ConnectionApiTests.java`, `frontend/tests/connections.spec.ts`. 기존 설정/오류 처리/UI/문서도 갱신했습니다.

## 멀티 DB 적재 변경

공통 JDBC 적재·영수증·트랜잭션을 추가했습니다. 기본 Java 실행은 **28 통과, 실제 MySQL 기존 4 + 새 멀티 DB 통합 테스트 클래스 1 건너뜀**입니다. 새 API 테스트는 적재 opt-in/동의/OCR 검토를 확인합니다. 실제 DB 테스트는 Docker read-only/재시작 실패(디스크 부족)로 실행하지 못했습니다. [실제 검증 상태와 재현 명령](jdbc-loading.md). 신규 핵심 파일은 `load/JdbcLoader.java`, `connection/JdbcLoadIntegrationTests.java`, `infra/jdbc/*`이며 기존 JdbcExplorer/ConnectionController/DatabaseExplorer/page.tsx도 수정했습니다.

## 2026-11-20 후속 검증 (Docker 없이 진행)

**Docker Desktop 복구 실패.** 시작 시 `AppData\Local\Docker\run\sailor-ingest.sock`, 이후 `AppData\Local\docker-secrets-engine\engine.sock` 유닉스 소켓 파일이 "The file cannot be accessed by the system"(오류 1920)로 이름 변경·삭제가 안 되어 백엔드가 크래시합니다. 재부팅 후에도 동일했습니다. 이미지·볼륨은 삭제하지 않았습니다. *(이 항목은 중간 기록입니다. 이후 두 폴더를 모두 이름 변경해 복구했고, 실서버 검증 결과는 아래 "실제 서버 통합 검증" 이하를 참고하세요.)*

### 추가: DB → File 추출 (`connection/JdbcReader.java`)
- `POST /api/v1/connections/read` (스키마·테이블·컬럼 선택, `limit` 1~10,000). 기본 비활성화(`TABLEFLOW_JDBC_ENABLED`), 로컬 요청·host allowlist·TLS 규칙은 기존 JDBC 탐색과 동일.
- 임의 SQL 없음. 메타데이터로 확인된 컬럼만 DBMS별 인용해 읽기 전용 SELECT, `setMaxRows`, fetch size 500, 쿼리 타임아웃 30초. BLOB·바이너리 등은 거부.
- 결과는 기존 `Table` 구조로 반환되어 `/exports/csv`, `/exports/excel`, `/exports/sql`을 그대로 재사용.
- **프런트엔드 UI는 아직 없음.**

### 이번 실행 결과
| 항목 | 결과 |
|---|---|
| `gradlew test` | 37개 중 실패 0, 건너뜀 5(실제 MySQL 4 + 멀티 DB 통합 1) |
| `JdbcReaderTests` 4개 | 통과 — **H2 인메모리 DB 기반**(타입·NULL·한글·limit·주입 거부). 대상 4종 DBMS 실서버 검증이 아님 |
| 샘플 CSV → 4종 SQL / CSV / XLSX (실제 HTTP) | 통과 (포트 18090) |
| `/connections/read` 비활성 상태 | 503 `JDBC_DISABLED` 확인 |

### 소스 DB 추출 화면 (DB → File UI)
- `DatabaseExplorer`(소스 모드)에 컬럼 선택·최대 행 수(1~10,000)·"선택한 컬럼 행 추출" 추가, 추출 결과는 `page.tsx`의 `extracted()`로 파일과 같은 워크벤치(검토 → 검증 → CSV/Excel/SQL)에 연결.
- 검증: `tsc --noEmit` 통과, `npm run build` 통과, Playwright **15개 중 9 통과 / 6 건너뜀(실제 MySQL 2·로컬 OCR 4) / 실패 0**.
- 신규 E2E는 **`/connections/read`를 mock**한 UI 계약 테스트입니다(요청 payload, 워크벤치 표시, 실제 백엔드 검증·CSV 다운로드). 실제 DB 행 추출 검증이 아닙니다.

### DB 스키마 기반 자동 컬럼 매핑 (추천)
- `frontend/src/lib/workspace.ts`의 `suggestMapping()`: 대상 DB 컬럼 조회 후 ① 이름 일치 ② 업무 용어 별칭(사원번호→EMPNO/EMP_NO 등) ③ 유사 이름(≥0.8, 1·2위 차이 <0.1이면 모호로 보류) 순으로 추천. 확신이 없으면 "미확정"으로 남기고 자료형(JDBC 타입) 비호환은 경고. 식별자 규칙에 맞지 않는 DB 컬럼은 후보 제외.
- 대상 DB 패널의 "DB 컬럼 기준 자동 매핑 추천" → 표로 확인 → "추천 N개 적용"을 눌러야만 반영(자동 확정 없음). 적용하면 검증 상태가 초기화되어 재검증·재검사가 필요.
- 검증: `tsc` 통과, 프로덕션 빌드 통과, Playwright **16개 중 10 통과 / 6 건너뜀 / 실패 0**. 신규 E2E는 **DB 메타데이터를 mock**한 UI 계약 테스트이며 실제 DB 메타데이터 검증이 아님.
- 한계: 유사도·별칭 사전은 소규모 휴리스틱이고, 별칭 사전은 샘플 업무 용어 위주(사용자 정의 규칙 UI 없음).

## 2026-11-20 실제 DB 서버 검증 (Docker 복구 후)

**Docker 원인:** 2026-11-19 디스크 부족 사고 시점(15:44)에 깨진 유닉스 소켓 파일(`AppData\Local\Docker\run\sailor-ingest.sock`, `AppData\Local\docker-secrets-engine\engine.sock`)이 Windows에서 열기·이름 변경·삭제 모두 오류 1920으로 실패했고, Docker Desktop은 시작 때마다 이 파일을 `.stale`로 바꾸려다 크래시했습니다. 두 폴더를 삭제하지 않고 이름만 변경(`run.broken*-20261120`, `docker-secrets-engine.broken*-20261120`)한 뒤 정상 기동했습니다. 다른 프로젝트의 이미지·볼륨을 포함해 모두 보존됐습니다. 같은 증상이 재발하면 Docker 종료 → `wsl --shutdown` → 두 폴더 이름 변경 → 재시작.

### 실제 서버 통합 검증 (TableFlow 전용 일회용 컨테이너)
| DBMS | 서버 | 드라이버 | 연결·메타데이터·적재·replay·충돌·롤백 |
|---|---|---|---|
| PostgreSQL | 17.12 | 42.7.14 | 통과 |
| MySQL | 8.4.13 | Connector/J 9.7.0 | 통과 |
| MariaDB | 11.8.9 | 4.0.0 | 통과 |
| Oracle | Free 23.26.3 | ojdbc11 | 통과 |

### DB → DB 이전 (`load/MigrationService.java`, `MigrationIntegrationTests`)
실제 서버 간 8개 경로 통과: PG→MySQL, MySQL→PG, MariaDB→PG, PG→MariaDB, MySQL→MariaDB, MariaDB→MySQL, **Oracle→PostgreSQL, PostgreSQL→Oracle**.
확인 항목: Dry-run(쓰기 없음), 원본 행 수 > 한도이면 일부 이전 대신 거부, 잘못된·중복 컬럼 매핑 거부, 실제 이전, 같은 요청 ID 재시도(replay), PK 충돌 거부와 대상 불변, 시간 정보가 있는 일시를 DATE로 옮길 때 손실 방지 거부(Oracle DATE는 시간 보존이라 해당 없음), 제약 위반 시 전체 롤백, 한글·`'`·`\`·고정밀 숫자 보존.
- API: `POST /api/v1/connections/migrate/check`(Dry-run), `POST /api/v1/connections/migrate`(동의·요청 ID 필요). 기존 `JdbcLoader`의 트랜잭션·영수증·배치 INSERT·롤백을 그대로 재사용합니다.
- 한계: 1회 최대 10,000행(그 이상은 거부), 대상 테이블은 미리 존재해야 함(생성·UPDATE·DELETE 없음), FK·시퀀스 처리 없음, 대상 컬럼명은 영문/숫자/_ 규칙, **프런트엔드 화면 없음**.
- 일반 `gradlew test`: 38개 중 실패 0 / 건너뜀 6(실제 DB 필요).

### DB → DB 이전 화면 (소스 탭 "DB → DB 이전", `components/MigrationPanel.tsx`)
원본·대상 연결 → 스키마·테이블 선택 → 자동 매핑 추천(미리 채움, 직접 변경 가능) → 최대 행 수 → 점검(Dry-run) → 동의 체크 → 실행. 동의 전·실행 후에는 실행 버튼이 비활성화되고, 같은 요청 ID로 재시도하면 중복 적재하지 않습니다.
- **실제 브라우저 + 실제 서버 E2E 통과:** `frontend/tests/migration.spec.ts` — Oracle(Free 23.26.3) → PostgreSQL(17.12). 이름이 비슷한 컬럼(EMP_NO→empno, HIRE_DATE→hiredate)은 자동 추천, 모호한 컬럼(EMP_NM, SALARY)은 비워 두어 직접 선택, Dry-run 후 대상 0행 확인, 동의 전 실행 불가, 실행 후 3행·값(`Kim O'Brien|3500000.50|2026-01-15`) 확인, 테스트 테이블 정리.
- 실행 조건: 컨테이너 기동 + `TABLEFLOW_TEST_JDBC=true TABLEFLOW_JDBC_ENABLED=true TABLEFLOW_JDBC_LOAD_ENABLED=true npx playwright test tests/migration.spec.ts`. 기본 실행에서는 건너뜁니다(기본 Playwright: 17개 중 10 통과 / 7 건너뜀 / 실패 0).
- 한계: 한 번에 최대 10,000행, 대상 테이블은 미리 존재, 한글 값은 이 E2E에서 확인하지 않음(Java 통합 테스트에서는 확인).

## 2026-11-20 추가 구현·검증: DB→File 실서버, 작업 이력, 테이블 자동 생성, FK·자동 증가

### DB → File 실제 브라우저 E2E (`frontend/tests/db-to-file.spec.ts`, 실제 PostgreSQL 17.12)
소스 DB 연결 → 컬럼 선택(memo 제외) → 행 추출 → 검증 → CSV·XLSX 다운로드. 한글 값, NULL 날짜(빈 칸), 고정밀 숫자(`12345678901234567.12`) 보존, 수식 문자열(`=SUM(A1)`)은 `'` 보호, 제외한 컬럼 미출력, XLSX 시그니처(PK) 확인, 원본 테이블 불변. **통과.**

### 작업 이력 (`load/JobHistory.java`, `GET /api/v1/connections/history`, 화면 "작업 이력")
- 파일→DB 적재와 DB→DB 이전의 성공 / 재시도(replay) / 실패를 서버의 JSON Lines 파일(`data/job-history.jsonl`, `TABLEFLOW_HISTORY_FILE`로 변경, `tableflow.history.enabled=false`로 끔)에 기록. 최근 1,000건 초과 시 500건만 보관, 조회는 최신순 최대 200건.
- **비밀번호·호스트·사용자명·행 데이터는 저장하지 않으며**(이름·건수·결과 코드만), 알 수 없는 예외의 원문은 이력에 남기지 않습니다. 이력 저장 실패는 적재 결과에 영향이 없습니다.
- 단위 테스트 4개(성공·replay·실패 순서, 비밀 미저장·예외 원문 미노출, 1,001건 보관 한도·손상된 줄 건너뜀, 비활성·쓰기 불가 시 작업 지속) 통과. 실제 브라우저 E2E에서 성공 이력 표시 + 실제 PK 충돌 실패(`LOAD_REJECTED`) 기록 + 응답에 비밀번호·`localhost`·데이터 값 없음 확인.
- 한계: 기존 서버 등록 MySQL 적재(`/loads/database`)는 이력에 기록하지 않음. 단일 서버 파일이며 이력 삭제 API 없음(파일 삭제로 초기화).

### 대상 테이블·영수증 자동 생성 (`load/TableDdl.java`, DB→DB `createTable`)
- "새 테이블 만들기(없으면 생성)" 선택 시 원본 컬럼 정의(타입·길이·정밀도·NOT NULL·기본 키)로 대상 DBMS용 `CREATE TABLE`과 필요하면 `tableflow_load_receipts`를 만듭니다. **CREATE만 실행**하고 DROP/ALTER/UPDATE/DELETE 및 기존 테이블 변경은 없습니다.
- Dry-run은 DDL을 화면에 보여주고 아무것도 만들지 않습니다(실제 브라우저에서 확인). 실행은 DDL 포함 동의가 필요합니다. 이미 있는 테이블이면 만들지 않고 일반 적재를 합니다. 같은 요청 재시도는 replay.
- **원자성 한계:** PostgreSQL 외에는 DDL이 자동 커밋되므로 적재가 실패하면 빈 테이블이 남을 수 있습니다(같은 요청을 다시 실행하면 재생성 없이 적재만 수행).
- 타입 매핑 한계: 이름은 영문/숫자/_ (PostgreSQL 63자, 그 외 64자), Oracle 문자열은 4,000자 초과 거부, 일시는 소수 6자리, 부동소수는 NUMERIC(38,10) 기준, BLOB 등은 추출 단계에서 거부. 기본 키는 원본 PK가 모두 매핑될 때만 생성하며 **외래 키·인덱스·기본값·자동 증가 속성은 복제하지 않습니다.**
- 단위 테스트 4개(`TableDdlTests`: 4종 DBMS DDL, CREATE만 생성, 이름 주입·중복·길이 거부) 통과. **실서버 통합 7개 경로 통과:** PG→MySQL, MySQL→PG, MariaDB→PG, PG→MariaDB, Oracle→PG, PG→Oracle, Oracle→MySQL (Dry-run 무변경, 생성·PK·자료형·값·시간 보존, replay, 재실행 시 DDL 없음).
- 실제 브라우저 E2E: Oracle → PostgreSQL 새 테이블(`tf_ui_new`) + 영수증 테이블 생성, DDL 미리보기 2개, 실행 전 무변경, 동의 후 3행, 컬럼 타입·PK 확인.

### FK·자동 증가(시퀀스) 처리 (`MigrationService` Dry-run 경고)
- **대상 외래 키**를 Dry-run 경고로 표시(부모 행이 없으면 DB가 거부해 전체 롤백), **원본 외래 키**는 "부모 테이블을 먼저 이전" 경고. 외래 키 자체를 복제하거나 이전 순서를 자동 계산하지는 않습니다.
- **대상 자동 증가(IDENTITY/AUTO_INCREMENT) 컬럼에 원본 값을 넣는 매핑은 명확한 메시지로 거부**하고, 매핑에서 뺀 경우 DB가 새 번호를 부여한다는 경고를 표시합니다. 원본 키 값 보존과 시퀀스 재동기화(`setval` 등)는 **미구현**입니다(DDL·권한·동시성 위험으로 범위에서 제외).
- 실서버 통합 4개 경로 통과: PG→MySQL, MySQL→PG, PG→MariaDB, PG→Oracle (FK 경고, 자동 증가 매핑 거부, 제외 시 새 번호로 적재).

### 실서버 테스트가 찾아 고친 기존 버그
Oracle 대상 테이블에 IDENTITY 컬럼이 있으면 `JdbcLoader`의 스키마 검사가 `ORA-17027`(스트림이 이미 닫힘)로 실패했습니다. `getColumns`의 `COLUMN_DEF`(LONG)를 다른 컬럼보다 늦게 읽던 순서 문제로, ResultSet 컬럼 순서대로 읽도록 고쳤습니다. 적재 실패 로그에 SQLState·벤더 코드만 남기도록 추가했습니다(오류 메시지는 데이터 값이 섞일 수 있어 제외).

### 이번 전체 회귀
- 기본 `gradlew test`: 48개 중 실패 0 / 건너뜀 8(실제 DB 필요).
- 실서버 4종 포함(`TABLEFLOW_TEST_JDBC=true`, 컨테이너 기동): **67개 중 실패 0 / 건너뜀 4**(로컬 MySQL 전용).
- Playwright 기본: 10 통과 / 11 건너뜀(실제 DB·OCR·스크린샷 전용) / 실패 0. 실제 DB E2E 3개(`db-to-file`, `migration` 2개)는 별도 실행으로 통과.

## 2026-11-20 후속: 대용량(청크) 이전, 원본 키 보존·시퀀스 재동기화

### 대용량 DB → DB (10,000행 초과, `MigrationService.runBulk` / `MigrationJobs`)
- 원본을 **기본 키 순서**(없으면 선택 컬럼 전체 순서)로 정렬해 OFFSET 페이지로 읽고, **청크(기본 5,000행, `tableflow.migration.chunk-size`)마다 별도 트랜잭션·영수증**으로 적재합니다. 청크 요청 ID는 (작업 요청 ID, 순번)에서 결정적으로 만들어, 같은 요청으로 다시 실행하면 이미 커밋된 청크는 영수증으로 건너뛰고 **이어서 진행**합니다.
- 백그라운드 작업: `POST /connections/migrate/jobs` → `GET /connections/migrate/jobs/{id}`(진행률) → `DELETE`(다음 청크 전에 취소). 작업 상태는 서버 메모리(최근 50개)에만 있고 DB 자격증명은 저장·응답하지 않습니다. 서버가 재시작되면 상태는 사라지지만 같은 요청 ID로 재제출하면 이어서 진행합니다. 서버 한도 `tableflow.migration.max-rows`(기본 1,000,000행).
- 점검(Dry-run)은 전체 행 수와 **처음 200행 표본**만 대상 스키마와 대조합니다(경고로 표시). 이후 행의 오류는 해당 청크에서 실패하며 앞선 청크는 커밋된 상태로 남습니다(메시지에 청크 번호·커밋된 행 수·재개 방법 안내).
- 한계: **OFFSET 방식이라 매우 큰 테이블의 뒤쪽 청크는 느려질 수 있음**, 실행 중 원본이 바뀌면 안 됨(행 수가 바뀌면 `SOURCE_CHANGED`로 중단, 청크 내용이 바뀌면 같은 요청 ID에서 409 충돌), 단일 서버 프로세스·메모리 작업 상태, 청크 단위 부분 성공(전체 원자성 아님), 큰 테이블의 소요 시간은 측정하지 않음(검증 규모 12,500행).
- **실서버 통합 4경로 통과**(`MigrationBulkIntegrationTests`, 5,432행·청크 1,000행): Oracle→PostgreSQL, PostgreSQL→MySQL, MySQL→MariaDB, MariaDB→Oracle. 점검 → 4번째 청크 실패(앞선 3,000행 커밋) → 원인 수정 후 같은 요청으로 재실행(**3개 청크 건너뜀, 5,432행 완료, 중복 0, 합계·값 일치**) → 모두 건너뛰는 재실행 → 취소(2,000행에서 중단) 후 재개 → 백그라운드 작업 진행률·완료.
- **실제 브라우저 E2E 통과:** Oracle 12,500행 → PostgreSQL(3개 청크). 한도 5,000이면 "일부만 이전하는 것을 막았습니다" 거부, 한도 20,000이면 대용량 점검(`대용량: 3개 청크`, 표본 경고, 대상 0행), 진행률, 완료(12,500·중복 0·합계 78131250), 작업 이력 표시.

### 원본 키 값 보존 + 시퀀스 재동기화(옵션 `preserveKeys`)
- 대상 자동 증가(IDENTITY/AUTO_INCREMENT) 컬럼에 매핑하면 기본은 거부하며 옵션 안내를 보여줍니다. 옵션을 켜면 원본 값을 그대로 넣고 적재 후 다음 번호를 최댓값 다음으로 맞춥니다: PostgreSQL `setval(pg_get_serial_sequence…)`, Oracle `MODIFY … GENERATED BY DEFAULT AS IDENTITY (START WITH LIMIT VALUE)`, MySQL/MariaDB는 DB가 AUTO_INCREMENT를 자동 갱신. PostgreSQL `GENERATED ALWAYS`는 `OVERRIDING SYSTEM VALUE`로 허용, **Oracle `GENERATED ALWAYS`는 값을 넣을 수 없으므로 명확한 메시지로 거부**합니다.
- 재동기화 실패(권한 등)는 적재를 되돌리지 않고 결과 안내에 경고를 남깁니다.
- **실서버 통과 6경로:** MySQL→PostgreSQL, PostgreSQL→MySQL, PostgreSQL→MariaDB, PostgreSQL→Oracle(BY DEFAULT), MySQL→PostgreSQL(ALWAYS), PostgreSQL→Oracle(ALWAYS 거부). 원본 키 10·20·30이 그대로 들어가고 다음 자동 번호가 31인지 확인. 실제 브라우저 E2E(Oracle→PostgreSQL identity)도 통과.
- 한계: 새로 만드는 테이블(`createTable`)에는 자동 증가 속성을 복제하지 않습니다. 외래 키·인덱스·기본값 복제와 이전 순서 자동 계산은 여전히 없습니다(경고만).

### 최종 회귀 (2026-11-20, 푸시 직전)
| 항목 | 결과 |
|---|---|
| 백엔드 `gradlew test` (실제 DB 없음) | 52개 중 실패 0 / 건너뜀 12(실제 DB 필요) |
| 백엔드 실서버 4종 포함 (`TABLEFLOW_TEST_JDBC=true`, 컨테이너 기동) | **77개 중 실패 0 / 건너뜀 4**(로컬 MySQL 전용) |
| 프런트엔드 `tsc`·production build | 통과 |
| Playwright 기본 | 10 통과 / 13 건너뜀(실제 DB·OCR·스크린샷 전용) / 실패 0 |
| Playwright 실서버(`migration`·`db-to-file`, 연속 2회 반복) | 5개 통과 / 실패 0 |
| 미검증 | 로컬 OCR 모델 실행, 원격 CI, 공개 배포, 수십만 행 이상 성능 |


## 2026-11-25: 관리자 JDBC 반입 및 SQL Server Developer

범위는 [진행 기록](INTRANET_PROGRESS.md) 참조. 사용자 요청으로 Tibero/CUBRID는 보류했다.

- SQL Server 2022 Developer `16.0.4325.4`, Microsoft JDBC `13.2.1.jre11` 반입 JAR 경로.
- SQL Server 실제 테스트 4개: PostgreSQL 왕복·Unicode/NULL/날짜/정밀도·동일 요청 재전송·IDENTITY 보존·분할 실패/재개·FK/인덱스/기본값·트리거/미신뢰 인증서 거부.
- 전체 실제 DB 회귀: 153개 통과, 4개 건너뜀, 실패 0. 기존 Oracle/PostgreSQL/MySQL/MariaDB 포함.
- 브라우저 7개 통과. 드라이버 2개는 API mock 기반 버전 전달/관리 키 비저장 검사다.
- Webpack 빌드/타입 검사 통과. 기본 Turbopack 빌드의 Node 하위 프로세스 오류는 미해결이며 원인은 확정하지 않았다.


## 2026-11-25 후속: Tibero 실험 어댑터와 CUBRID 제한 지원

- 기존 5종과 CUBRID를 포함한 전체 DB 회귀: 168개 중 **163개 통과, 5개 건너뜀, 실패 0**. Tibero 실서버 테스트 1개는 미실행이며 건너뜀에 포함된다.
- 이후 CUBRID 소유자 필터/일괄 이관을 포함한 관련 추가 검증: **11개 통과**(CUBRID 실제 6, CUBRID 단위 2, Tibero 단위 3). 전체 회귀와 중복되는 테스트를 합산하지 않는다.
- 실제 브라우저 CUBRID→PostgreSQL 및 PostgreSQL→CUBRID **2개 통과**. 실제 관리자 JAR 등록·연결·테이블 선택·사전 점검·동의·적재·사후 검증 경로다.
- 기존 브라우저 회귀 **7개 통과**. 그중 드라이버 2개는 API mock 기반이며 실제 DB 검증으로 세지 않는다.
- Webpack 프로덕션 빌드 및 TypeScript 검사 통과. 기존 Turbopack 오류 해결 여부는 검증 범위에 포함하지 않았다.
- 엔진 CUBRID `11.3.6.1321`, JAR `11.2.1.0040`(공식 서버 이미지 포함). 이미지 digest/JAR SHA-256은 [검증 기록](DBMS_EXPANSION.md)에 명시했다.
- Tibero는 정식 JAR/전용 서버가 없어 기본 비활성화·실서버 미검증이다. H2 Oracle 모드 ROWNUM 검사를 Tibero 실서버 검증으로 표현하지 않는다.
