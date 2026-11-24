# 인수인계 (2026-11-20 기준)

> **2026-11-25 설계 기준:** [내부망 설치형 아키텍처](INTRANET_ARCHITECTURE.md). 사용자 요구: 오프라인 설치·사용자 JDBC 드라이버 반입·RabbitMQ/Java 워커·Celery OCR·향후 수집 에이전트. 구현 진행 상황은 [INTRANET_PROGRESS.md](INTRANET_PROGRESS.md)를 확인한다. SQL Server Developer 실서버 검증 후 사용자 요청으로 이번 범위는 SQL Server까지로 제한했다. Tibero/CUBRID 이후는 보류이며 12단계 전체가 완료된 것은 아니다. 과거 문서의 간헐 테스트 실패 원인 확정 표현은 정정한다. 메모리 부족은 가설이며 원인은 아직 확정되지 않았다.

> **최신 요약본은 [HANDOFF_GPT.md](HANDOFF_GPT.md)** (2026-11-24, 개발한 기능·남은 할 일·실행 명령). 이 문서는 시간순 상세 기록입니다.

다른 PC(데스크탑)나 새 세션에서 이어서 개발할 때 **가장 먼저 읽는 문서**입니다. Claude Code 세션 기록은 PC에 로컬로 저장되어 다른 PC로 이어지지 않으므로, 이어서 하려면 이 문서와 코드가 기준입니다. 문서와 코드가 다르면 **코드와 테스트 결과를 믿고** 문서를 고치세요.

- 저장소: <https://github.com/orca305/TableFlow> · 브랜치 `main`
- 기준: 2026-11-20 UI 개편·기능 추가까지 반영(§7). 시작점은 `c66af51`
- 상태 요약: [README](../README.md) 상단 표, 상세 근거는 [TEST_RESULTS](TEST_RESULTS.md), 기능 현황은 [DEVELOPMENT_STATUS](DEVELOPMENT_STATUS.md)

## 1. 지금 무엇이 되어 있나

| 흐름 | 상태 | 검증 수준 |
|---|---|---|
| File → SQL / File → File (CSV·XLSX·PDF·OCR) | 구현 | 단위·E2E |
| File → DB (Oracle/PostgreSQL/MySQL/MariaDB) | 구현 | **실서버 통합 통과** |
| DB → File / DB → SQL | 구현 | H2 단위 + 화면 계약(mock) + **실제 브라우저·PostgreSQL E2E** |
| DB → DB | 구현 | 실서버 8경로 + 실제 브라우저 Oracle→PostgreSQL |
| 대용량(10,000행 초과, 청크·백그라운드·재개) | 구현 | 실서버 4경로 + 브라우저 12,500행 |
| 원본 키 보존 + 시퀀스 재동기화 | 구현 | 실서버 6경로 |
| 대상 테이블 자동 생성 | 구현(CREATE만) | 실서버 7경로 |
| 작업 이력 | 구현 | 단위 + 브라우저 |

검증 서버(일회용 Docker): PostgreSQL 17.12, MySQL 8.4.13, MariaDB 11.8.9, Oracle Free 23.26.3. **그 외 서버 버전·레거시 드라이버는 미검증**입니다.

## 2. 데스크탑에서 처음 시작하기

필수: JDK 21, Node 20.9+, Docker Desktop. OCR을 쓰면 Python 3.13.

```powershell
git clone https://github.com/orca305/TableFlow.git C:\dev\TableFlow   # 이미 있으면: git pull
cd C:\dev\TableFlow\frontend; npm ci
cd ..\backend; .\gradlew.bat test            # 기본 테스트(실제 DB 없이)
```

개발 서버: 백엔드 8080(`scripts\backend.ps1` 또는 `.\gradlew.bat bootRun`), 프런트 3100(`npm run dev`). DB 기능을 화면에서 쓰려면 백엔드를 `TABLEFLOW_JDBC_ENABLED=true TABLEFLOW_JDBC_LOAD_ENABLED=true`로 시작하세요(기본은 둘 다 꺼짐).

### 실제 DB 통합 테스트(일회용 컨테이너)

```powershell
cd C:\dev\TableFlow
docker compose -f infra/jdbc/compose.yml --profile oracle up -d --wait --wait-timeout 600   # Oracle 이미지 약 3GB, 디스크 여유 필요
cd backend
$env:TABLEFLOW_TEST_JDBC='true'; $env:TABLEFLOW_TEST_JDBC_DBMS='oracle,postgresql,mysql,mariadb'
.\gradlew.bat test --rerun-tasks      # 기대: 77개 중 실패 0, 건너뜀 4(로컬 MySQL 전용)
Remove-Item Env:TABLEFLOW_TEST_JDBC; Remove-Item Env:TABLEFLOW_TEST_JDBC_DBMS
```

실제 브라우저 E2E(백엔드 jar와 프런트 프로덕션 빌드가 필요):

```powershell
cd C:\dev\TableFlow\backend; .\gradlew.bat bootJar
cd ..\frontend; $env:NEXT_PUBLIC_API_URL='http://localhost:18080/api/v1'; npm run build; Remove-Item Env:NEXT_PUBLIC_API_URL
$env:TABLEFLOW_TEST_JDBC='true'; $env:TABLEFLOW_JDBC_ENABLED='true'; $env:TABLEFLOW_JDBC_LOAD_ENABLED='true'
npx playwright test tests/migration.spec.ts tests/db-to-file.spec.ts    # 기대: 5 통과
```

테스트용 DB는 `infra/jdbc/compose.yml`의 **전용 컨테이너만** 사용하세요(루프백 15432/13316/13317/11521, 고정 테스트 비밀번호). 운영·개인 DB로 포트를 바꿔 실행하지 마세요. 통합 테스트는 `TF_*` 테이블과 `tableflow_load_receipts`를 만들고 지웁니다.

## 3. 구조 한눈에

- `backend/.../connection/` `JdbcExplorer`(연결·메타데이터·보안 검증), `JdbcReader`(읽기 전용 추출, 정렬 페이지)
- `backend/.../load/` `JdbcLoader`(트랜잭션·영수증·배치 INSERT), `MigrationService`(DB→DB 핵심: 레이아웃/변환/Dry-run/청크 실행/키 보존), `MigrationJobs`(백그라운드·진행률·취소), `TableDdl`(CREATE TABLE 생성), `JobHistory`(이력 JSON Lines)
- `backend/.../api/ConnectionController.java` 모든 `/connections/*` API(로컬 요청·Origin·호스트 allowlist 제한)
- `frontend/src/components/` `DatabaseExplorer`(소스/대상 DB 패널), `MigrationPanel`(DB→DB), `JobHistory`
- `frontend/src/lib/workspace.ts` 자동 매핑 추천 `suggestMapping`
- 테스트: `backend/src/test/.../connection/*IntegrationTests`(실서버, `TABLEFLOW_TEST_JDBC=true`일 때만), `frontend/tests/*.spec.ts`
- API 계약: [api.md](api.md)

## 4. 알려진 한계 (다음 작업 후보, 우선순위 순)

1. **(진행) 대형 테이블 성능:** 기본 키가 있으면 청크 읽기를 키 기반(keyset, `JdbcReader.readKeyPage`)으로 바꿨습니다(2026-11-20, H2 단위 + **실서버 4종 통합 테스트 통과: 78개 중 실패 0, 건너뜀 4**. 실서버가 Oracle ORA-00960[선택 컬럼과 키 컬럼 중복]을 잡아 키 컬럼에 별칭 `TF_KEY_n`을 붙여 수정 — **수십만 행 성능 측정은 아직 미실시**). 기본 키가 없는 테이블은 기존 OFFSET 방식입니다. 다음: 실서버 검증 후 대용량 측정.
2. **FK·인덱스·기본값 복제, 이전 순서 자동 계산** 없음(FK는 경고만). 새 테이블에는 자동 증가 속성도 복제하지 않음.
3. **작업 상태가 서버 메모리**에만 있음(재시작 시 목록 소실). 같은 요청 ID로 재제출하면 이어서 진행은 됩니다. 필요하면 파일/DB에 작업 목록을 영속화하세요.
4. **새 테이블 생성의 원자성:** PostgreSQL 외에는 DDL이 롤백되지 않아 적재 실패 시 빈 테이블이 남을 수 있음.
5. **미검증:** 로컬 OCR 모델 실행(Python 서비스), 원격 CI, 공개 배포, 외부 OCR 실제 호출, 다른 DB 서버 버전. 공개 데모는 파일 변환만 노출하고 DB 기능은 끄세요(기본값).
6. 향후(명세 Phase 5): SQL Server·SQLite·CUBRID·Tibero·DB2, 증분 동기화, 스케줄링.

## 5. 함정과 해결 (실제로 겪은 것)

- **Docker Desktop이 시작 중 크래시**(`sailor-ingest.sock` / `docker-secrets-engine\engine.sock`, 오류 1920): 디스크가 가득 찼던 사고 때 깨진 유닉스 소켓 파일이 원인. 삭제는 안 되고 **폴더 이름 변경**으로 해결 — Docker 종료 → `wsl --shutdown` → `%LOCALAPPDATA%\Docker\run`과 `%LOCALAPPDATA%\docker-secrets-engine`을 이름 변경 → 재시작. **이미지·볼륨·다른 프로젝트 데이터는 지우지 마세요.**
- **sqlplus로 테스트 데이터를 만들 때 한 줄에 `;` 문장을 여러 개 쓰지 마세요**(실행되지 않음). 문장마다 줄을 나누세요.
- **PowerShell 5.1 `Set-Content -Encoding UTF8`은 BOM을 붙여** Java 컴파일을 깨뜨립니다. 소스 편집은 BOM 없는 UTF-8로.
- **Oracle `getColumns`의 `COLUMN_DEF`는 LONG**이라 ResultSet 컬럼 순서대로 읽어야 합니다(`ORA-17027`). `JdbcLoader`에 수정 반영됨.
- **MySQL은 컬럼에 붙인 `REFERENCES`를 무시**합니다. FK 테스트는 `FOREIGN KEY (...) REFERENCES ...` 구문을 쓰세요.
- Oracle `DATE`는 JDBC에서 TIMESTAMP로 보고되어 시간을 보존합니다(DATE 대상으로 옮겨도 시간 손실 변환이 필요 없음).
- Playwright는 백엔드를 18080/프런트를 3101로 직접 띄우며 `reuseExistingServer`입니다. 서버 코드를 바꿨으면 jar와 프런트를 **다시 빌드**하세요. 작업 이력 파일은 서버 작업 디렉터리(`frontend/data/job-history.jsonl`)에 쌓이며 git에서 제외됩니다. 테스트는 이전 실행의 이력이 남아 있어도 통과하도록 `first()`를 씁니다.
- Gradle 테스트의 이력 파일은 `build/tmp/test-history.jsonl`로 분리되어 있습니다(`build.gradle`).

## 6. 작업 원칙 (이 프로젝트의 합의)

- 새 프로젝트가 아니라 **기존 기능을 보존하며 점진 개선**. 구현과 검증 완료를 구분해서 보고(통과/실패/건너뜀/mock/실서버).
- 문서에 "완료"라고 적혀 있어도 코드와 테스트로 확인. 건너뛴 테스트는 검증으로 치지 않기.
- 데이터 삭제·Git 강제 작업·운영 환경 변경 같은 위험한 작업은 사용자 승인 후. DB 비밀번호는 Git·브라우저 저장소에 두지 않기. 실제 DB 적재는 기본 비활성화·명시적 동의.
- 진행 안내·설명·최종 보고는 **한국어**, 코드·명령·경로·로그만 원문 유지. 끝날 때 변경 파일·검증 결과·남은 과제를 짧게 보고하고 이 문서를 갱신.
- 포트폴리오 목표일(2026-11-23)에는 완성된 핵심 흐름의 안정적 시연이 우선입니다. 시연 순서는 README의 "3분 시연"과 스크린샷을 참고하세요.

## 7. 2026-11-20 UI 개편과 기능 추가 (이 문서의 §1~6보다 우선)

**화면(프런트엔드 전면 개편)** — 디자인 시안은 `docs/ui-concepts/`(final-a.html이 확정안, tableflow-icon.svg가 로고).
- 메뉴 4개: **DB 이관**(원본 → 대상 → 검토 → 이관), **파일 변환**(파일 또는 DB 테이블 → SQL/Excel/CSV 출력 또는 DB 적재), **작업 이력**, **설정**. 라이트/다크(해·달 아이콘, 선택 기억), 용어마다 도움말 툴팁(`data-tip`, `components/ui.tsx`의 `TipLayer`).
- 구조: `app/page.tsx`(헤더·메뉴, 모든 화면을 마운트해 두고 숨김) · `components/{MigratePage,FilePage,HistoryPage,SettingsPage,ConnectionPicker,TablePicker,ui}.tsx` · `lib/connections.ts`. 옛 `DatabaseExplorer/MigrationPanel/JobHistory`는 삭제.
- 용어: 이관·원본/대상·분할·사전 점검·컬럼 매핑·기본 키·재개(CLAUDE.md 참고). 백엔드 사용자 메시지도 같은 표기로 바꿨다.
- 설정 화면은 DB 조회·적재 스위치를 **보여주기만** 한다. 이 기능은 서버 환경변수(`TABLEFLOW_JDBC_ENABLED`, `TABLEFLOW_JDBC_LOAD_ENABLED`)라 화면에서 바꿀 수 없다.
- DB 이관 화면에는 파일 변환의 "중복 행 처리"가 없다(DB→DB의 중복은 DB 제약으로 거부된다).
- 기존 "서버 등록 MySQL 적재"(`/loads/*`) UI는 제거했다(백엔드 API는 그대로). 같은 기능은 DB 적재(`/connections/load`)의 MySQL로 대체되고 `database.spec.ts`가 검증한다.

**백엔드**
- 작업별 분할 단위(`chunkSize`), 컬럼 호환성 분석(`/migrate/analyze`: 타입·길이·정밀도 + 실제 최대 길이), 행 수 조회(`/count`), 파일→DB 신규 테이블 생성(`createTable`).
- **연결 저장소**(`connection/ConnectionStore`): 연결 정보 저장 + 선택 시 암호화한 비밀번호 저장. 다른 서버로 비밀번호가 쓰이지 않도록 접속 정보 일치 검사, 암호문은 항목 ID에 묶임(AAD). 환경변수 `TABLEFLOW_CONNECTIONS_ENABLED`, `TABLEFLOW_CONNECTIONS_SAVE_PASSWORD`, `TABLEFLOW_SECRET_KEY`. 파일 `data/connections.json`·`data/connections.key`는 git 제외. 서버 작업 디렉터리 기준이라 **개발 서버를 다른 폴더에서 띄우면 저장 목록이 달라진다.**
- 키 기반(keyset) 청크 읽기는 실서버 4종 검증 완료(성능 측정은 미실시).
- CORS가 DELETE를 허용하지 않아 브라우저에서 "대용량 이관 중지"와 연결 삭제가 막히던 문제를 고쳤다(`WebConfig`).

**검증(2026-11-20, 실서버 Oracle/PostgreSQL/MySQL/MariaDB 컨테이너)**
- 백엔드: `gradlew test` + `TABLEFLOW_TEST_JDBC=true`로 전체 통과(신규: `ConnectionStoreTests` 8, `MigrationCompatIntegrationTests` 4경로, `FileLoadCreateIntegrationTests` 4종, 분할 단위 케이스).
- Playwright: `TABLEFLOW_TEST_JDBC=true npx playwright test` 23 통과, 6 건너뜀(로컬 OCR 4, 스크린샷 2). **한 번은 전체 실행에서 두 테스트가 "API에 연결할 수 없습니다"로 실패했다가 단독·재실행에서는 통과했다(원인 미확정, 서버 로그에 오류 없음).** 다시 나타나면 조사하세요.
- 서버를 직접 띄워 테스트할 때는 `--tableflow.jdbc.enabled=true --tableflow.jdbc.load-enabled=true`가 필요하고, 저장소 파일이 저장소 폴더에 생기지 않도록 별도 작업 폴더에서 실행하세요.

**남은 과제**
1. 대형 테이블(수십만 행) 이관 성능 측정, FK·인덱스·기본값 복제, 서버 재시작 후 작업 목록 복원(§4).
2. 작업 이력에서 실패한 이관을 바로 재개하는 기능(이력에 매핑 정보가 없어 불가능 — 필요하면 요청 본문(비밀번호 제외)을 암호화 저장).
3. 파일→DB 신규 테이블은 기본 키 없이 생성된다.
4. 로컬 OCR 모델, 원격 CI, 공개 배포는 여전히 미검증.

**2026-11-21 후속 (4건)**
- **신규 테이블 대상 타입·길이 직접 지정**(`Mapping.type/size/scale`, 검토 화면), 컬럼 매핑에 PK/FK 표시(`ColumnInfo.foreignKey`), 메뉴 활성 표시 단색 채움, 미리보기 표를 가로 스크롤 없이 폭에 맞춤.
- **파일→DB 신규 테이블 기본 키 지정**(`primaryKey`, 파일 변환의 DB 적재).
- **신규 테이블 구조 복제**: 인덱스·기본값·FK(`Extras`, `TableDdl.createIndex/addForeignKey/convertDefault`, `JdbcExplorer.extras`) + **부모 테이블 이관 순서 안내**(`FkPlan`). 기본값은 리터럴·불리언·현재 시각만 옮긴다. DDL은 롤백되지 않는다(PostgreSQL 제외).
- **작업 목록 영속화와 재개**(`JobStore`, `MigrationJobs.restore/list`, `/migrate/jobs`, `/resume`): 서버 재시작 후에도 목록이 남고 작업 이력 화면에서 재개한다. 비밀번호는 저장하지 않으며, 저장된 연결(savedId)이 아니면 재개 때 입력한다. 10,000행 이하의 동기 이관은 한 트랜잭션이라 목록에 남지 않는다.
- **검증**: 백엔드 `gradlew test` + 실서버 4종 109개 통과(건너뜀 4), Playwright 26 통과(건너뜀 6: 로컬 OCR 4, 스크린샷 2). 신규 통합 테스트: `MigrationExtrasIntegrationTests`, 대상 타입 지정·기본 키 지정 케이스, `MigrationJobsPersistenceTests`, 재개 e2e.
- **e2e 간헐 실패의 원인(환경)**: 이 PC에서 전체 e2e를 돌리면 1~2개 테스트가 매번 다른 곳에서 실패했다(단독 실행은 통과). 서버 로그에 오류가 없고, DB 명령(`docker exec`)이 느린 것도 아니었으며, 실패 시점에 PC 메모리가 심하게 부족했다(총 31GB 중 여유 약 2GB, 페이지 파일 거의 가득, 다른 프로그램 하나가 약 12GB 사용). 한 번은 `docker exec` 자체가 실패했다. 메모리 압박으로 Java·Chrome·Docker가 수십 초 멈추거나 네트워크 버퍼가 부족해 요청이 끊기는 것으로 판단한다(코드 버그 아님). 대기 시간을 늘려(`timeout 90s`, `expect 15s`) 완화했다. **안정적으로 돌리려면 메모리 여유가 충분한 PC에서 실행하거나 다른 무거운 프로그램을 닫으세요.** 다시 나타나면 `free memory`부터 확인하세요.

**2026-11-21 추가 검증·개발**
- **성능(키 기반 청크 읽기 + 분할 이관, 실서버 Oracle → PostgreSQL, 신규 테이블, 분할 단위 10,000행)**: 30만 행 14.4초(약 20,900행/초), **100만 행 41.4초(약 24,200행/초)**. 분할마다 걸린 시간이 처음부터 끝까지 거의 일정하다(OFFSET 방식의 뒤쪽 청크 지연 없음). 행 수·중복 없음 확인. 5개 컬럼, 백엔드 `-Xmx768m` 기준이며 네트워크·행 크기에 따라 달라진다.
- **이관 순서 계산**(`POST /connections/order`, 원본 선택의 "이관 순서 계산"): FK 기준 단계별 순서와 순환 감지. 검토 화면의 "부모 테이블 이관" 버튼으로 부모 테이블 이관 화면으로 바로 이동한다. 실서버 e2e로 부모 이관 후 자식에 FK가 생성되는 것까지 확인.
- **로컬 OCR 실모델 검증**: 파이썬 11개 통과, 브라우저 e2e(이미지·한글·스캔 PDF → 검토 → SQL/Excel) 4개 통과. Playwright 설정이 Windows에서 OCR 서버를 자동으로 못 띄우던 문제를 절대 경로로 수정했다.
- **원격 CI**: 최근 4개 커밋(`4100df2`~`d3d187c`)의 GitHub Actions가 모두 성공. CI에는 실제 Oracle/PostgreSQL/MariaDB 컨테이너가 없어 실서버 통합·e2e는 로컬에서만 돈다(`TABLEFLOW_TEST_JDBC=true`).

**남은 과제(우선순위 순)**
1. 공개 배포(Java 호스팅 선정, HTTPS, 접근 경계) — 아직 하지 않았다. 공개 데모는 파일 변환만 노출하고 DB 기능은 끄는 것을 권한다.
2. 외부 OCR 공급자 실제 호출 검증(키 필요), 원격 CI에 실서버 DB 컨테이너 추가.
3. 순환 FK 자동 처리(지금은 안내만), 여러 테이블을 한 번에 이관하는 일괄 실행(지금은 테이블 단위로 순서에 따라 하나씩).

## 8. 2026-11-21 ETL 확장 1단계: 이관 후 검증

- `MigrationVerifier` + `POST /connections/migrate/verify`: 이관 완료 후 화면에서 자동 실행되고 "다시 검증" 버튼이 있다. 4개 DB 조합 실서버 통합 테스트(일치/값 변경/행 삭제)와 Playwright(3행, 12,500행)로 확인했다.
- 남은 단계: 2) 행 조건(WHERE)·일부 컬럼 선택, 3) 컬럼 값 변환 규칙, 4) 여러 테이블·스키마 통째 이관, 5) 스케줄링. 행 조건을 넣으면 `verify`의 `where` 인자(원본에만 적용)를 연결해야 한다.

## 9. 2026-11-21 ETL 확장 2단계: 행 조건과 컬럼 선택

- 행 조건(WHERE): `RowFilter`(검사) → `JdbcReader`(count/readCounted/readPage/readKeyPage/extract에 where) → `MigrationService.Extras.where` → `MigrationVerifier`(원본에만 적용). 화면은 원본 선택의 "이관할 행 조건"과 "조건 확인"이며, 조건을 입력하면 확인하기 전에는 원본을 확정할 수 없다.
- 컬럼 선택은 기존 컬럼 매핑에서 대상 컬럼을 비워 제외하는 방식이다. 원본 데이터 미리보기(샘플 200행)는 조건을 적용하지 않는다.
- 검증: 단위 테스트 `RowFilterTests`, 4개 DB 조합 실서버 `MigrationRowFilterIntegrationTests`(점검·분석·소량·분할·검증·거부), Playwright 행 조건 시나리오. 백엔드 전체 123개 통과(4개 건너뜀).
- Playwright 이관 스펙은 전체 실행 시 매번 서로 다른 1개가 실패했다가 단독 재실행에서는 모두 통과했다(PC 메모리 부족에 따른 간헐 실패로 보이나 확정하지 못했다).
- 다음: 3) 컬럼 값 변환 규칙, 4) 여러 테이블·스키마 통째 이관, 5) 스케줄링.

## 10. 2026-11-21 ETL 확장 3단계: 컬럼 값 변환 규칙

- `ValueTransform`(규칙 엔진·계산기) + `Mapping.steps` → `MigrationService.layout/materialize`에서 청크마다 적용한다. SQL을 만들지 않고 서버에서 문자열로 계산하므로 주입 위험이 없다. 계산식은 사칙연산·괄호·{컬럼}만 지원한다(함수 없음). 빈 값이 끼면 결과도 빈 값.
- 규칙이 참조하는 컬럼은 `readColumns`가 매핑 원본 컬럼 뒤에 덧붙여 읽고, `materialize`가 결과 표에서 뺀다(CELL_KEYS 검증 때문에 필수).
- 한계: 변환 컬럼은 `MigrationVerifier` 값 비교에서 제외한다. 신규 테이블의 기본 타입은 문자열(계산식은 소수 20,4)이며 길이·정밀도는 대상 타입 지정으로 바꾼다. 날짜 변환·정규식·조건부(CASE) 규칙은 아직 없다.
- 화면: 컬럼 매핑의 "변환 규칙" 열 → `TransformEditor`(미완성 규칙이 있으면 이관 차단).
- 검증: `ValueTransformTests`(단위 6), 4개 DB 조합 실서버 `MigrationTransformIntegrationTests`(신규/기존 테이블, 소량/분할, 검증 제외, 오류 거부), Playwright 변환 규칙 시나리오.
- 다음: 4) 여러 테이블·스키마 통째 이관, 5) 스케줄링.

## 11. 2026-11-21 ETL 확장 4단계: 일괄 이관(여러 테이블·스키마)

- `BatchMigration`(plan/run) + `/migrate/batch[/check]` + 화면 "일괄 이관" 메뉴(`BatchPage`). 기존 `MigrationJobs`에 얹었다: 테이블 수를 `totalChunks`, 누적 행을 `doneRows`로 쓰고 테이블별 상태는 `Status.items`(JobStore에도 저장)에 둔다. 작업 이력의 재개 버튼이 그대로 동작한다(정의에 `tables`가 있으면 일괄 재개로 분기).
- 순서: `MigrationService.order`(FK 부모 먼저) → 같은 단계 안은 이름순, 순환 참조 테이블은 맨 뒤. FK 복제를 켜면 선택 범위 안의 부모가 먼저 만들어져 자식에 FK가 붙는다. 범위 밖 부모를 가진 FK는 기존 규칙대로 건너뛴다.
- 대상은 항상 원본 구조로 새로 생성한다(이름은 DBMS 관례로 변환, 이미 있으면 건너뜀/추가 적재 선택). 행이 0인 원본은 `runBulk`가 구조만 만든다(이전에는 "이관할 데이터가 없습니다" 오류).
- 재개: 테이블별 요청 ID는 `UUID(batchId:table)`에서 결정적으로 만든다. 완료·건너뜀 테이블은 읽지도 않고 넘어가며, 실패·중단 테이블은 이미 만들어진 상태여도 이어서 적재한다(이때는 인덱스·FK DDL이 다시 실행되지 않는 한계가 있다).
- 한계: 테이블별 컬럼 매핑·변환 규칙·행 조건은 지정할 수 없다(전체 컬럼 자동 매핑). 사전 점검은 테이블마다 분석을 돌리므로 수백 개에서는 느릴 수 있다. 일괄 이관 후 건수 검증은 화면에서 제공하지 않는다(단건 검증 API만 있음).
- 검증: 4개 DB 조합 실서버 `BatchMigrationIntegrationTests`(순서, 빈 테이블, FK 복제, 건너뜀, 실패 후 재개), Playwright `batch.spec.ts`.
- 다음: 5) 스케줄링.

## 12. 2026-11-21 ETL 확장 5단계: 스케줄링(반복 실행)

- `ScheduleStore`(data/schedules.json, git 제외) + `ScheduleService`(15초마다 점검하는 단일 스레드) + `ScheduleController`. 화면: 이관/일괄 이관 실행 패널의 "스케줄로 등록"(`ScheduleForm`)과 "스케줄" 메뉴(`SchedulePage`: 지금 실행·중지/사용·삭제, 마지막 결과).
- 정의는 요청 본문(비밀번호 제거, savedId만)을 보관하고, 실행할 때 `forRun`으로 새 요청 ID·동의 완료·행 수 상한 최대로 복사해 기존 `submitJob/submitBatch`를 호출한다 → 작업 이력·진행률·재개와 같은 경로. 서버가 꺼져 있던 동안 지난 회차는 켜지면 한 번 실행된다.
- 반복 적재가 중복·키 충돌을 만들지 않도록 `refresh=replace`(실행 전 대상 행 삭제, 일괄은 자식부터)를 권장 기본값으로 둔다. `append`는 키가 겹치면 실패한다.
- `ConnectionPicker`: 연결을 새로 저장하면 입력란의 비밀번호를 비우고 savedId로 연결한다(이전에는 저장 직후 자동 재확인이 비밀번호 방식으로 바뀌어 스케줄에 쓸 수 없었다).
- 한계: 서버 프로세스가 켜져 있는 동안에만 실행(OS 스케줄러 아님), 시간대는 서버 기준, 증분(변경분만) 이관은 없다(행 조건으로 범위를 좁힐 수 있으나 replace는 대상 전체를 비운다). 실행 결과 알림(메일 등)은 없다.
- 검증: `ScheduleIntegrationTests`(등록 규칙, 시각 경과 시 실행, 전체 교체, 일괄 스케줄, 중지; MySQL→MariaDB 실서버), Playwright `schedule.spec.ts`.

## 13. 2026-11-22 공개 준비 문서 정리
- README를 현재 기능(4종 DB 이관·일괄 이관·변환·검증·스케줄) 기준으로 다시 썼다. 사용 전제(인증 없음, 내부망·로컬 도구), 검증 현황(실서버 vs CI), 알려진 한계를 앞부분에 명시했다.
- `LICENSE`(MIT, 저작권자 표기 "TableFlow contributors")를 추가했다. 저작권자 이름·라이선스 종류는 소유자가 확정해 바꿔도 된다.
- `docs/architecture.md`에 현재 DB 이관 구성(다이어그램)을 추가하고 낡은 문구("스케줄러 없음", "JDBC 미구현")를 고쳤다. `docs/DEVELOPMENT_STATUS.md` 상단에 기록 시점 안내를 달았다.
- 아직 안 한 공개 준비: 신규 기능(일괄 이관·스케줄·변환) 스크린샷, 접근 보호(인증), 공개 배포, 처리량 추가 측정. 커밋 이력에 테스트용 비밀번호가 있는지 점검은 별도.

## 14. 2026-11-22 Vercel 소개용 배포 준비
- Vercel은 프런트엔드만 호스팅하므로 백엔드 없이 올리면 기능이 동작하지 않는다. 포트폴리오 소개용으로만 올리고 그 사실을 화면에 알린다.
- `NEXT_PUBLIC_DEMO_MODE=true`(빌드 시점): `frontend/src/lib/api.ts`가 모든 API 호출을 네트워크 없이 막고(`demoMode`), `app/page.tsx`가 경고 배너를 보여 준다. 로컬에서 데모 모드로 빌드해 배너와 "API 요청 0건"을 확인했다(기본 모드·E2E는 영향 없음, 단 Playwright 실행 전에는 `NEXT_PUBLIC_API_URL`로 다시 빌드할 것).
- 실제 배포(Vercel 계정 로그인, 프로젝트 생성, 도메인 `*.vercel.app`)는 사용자 계정이 필요해 이 문서 작성 시점에는 하지 않았다. 설정: Root Directory `frontend`, 환경변수 `NEXT_PUBLIC_DEMO_MODE=true`.
