# 인수인계 (2026-11-20 기준)

> **2026-11-25 설계 기준:** [내부망 설치형 아키텍처](INTRANET_ARCHITECTURE.md). 사용자 요구: 오프라인 설치·사용자 JDBC 드라이버 반입·RabbitMQ/Java 워커·Celery OCR·향후 수집 에이전트. 구현 진행 상황은 [INTRANET_PROGRESS.md](INTRANET_PROGRESS.md)를 확인한다. SQL Server Developer 검증 후 사용자 재개 요청으로 Tibero/CUBRID를 추가했다. 최신 상태는 [DBMS 확장 검증](DBMS_EXPANSION.md) 참조. Tibero 실서버 검증은 남아 있으며 12단계 전체가 완료된 것은 아니다. 과거 문서의 간헐 테스트 실패 원인 확정 표현은 정정한다. 메모리 부족은 가설이며 원인은 아직 확정되지 않았다.

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

## 15. 2026-11-26 일괄 이관 보강: 테이블별 설정·검증·재개

- **테이블별 설정**: `BatchMigration.TableOption(table, mappings?, where?)` → 사전 점검·실행·검증·스케줄(`BatchRequest.tableOptions`)에 모두 적용. 화면은 `BatchTableSettings`(행 조건, 컬럼 제외, 대상 컬럼 이름, 변환 규칙). 설정하지 않은 테이블은 기존 자동 매핑 그대로다. 컬럼 이름 기본값 함수는 `lib/names.ts`로 옮겨 단건·일괄 화면이 함께 쓴다.
- **이관 후 검증**: `POST /connections/migrate/batch/verify`, 화면 "이관 결과 검증"(이번 작업에서 완료된 테이블만). 추가 적재한 테이블은 기존 행 때문에 불일치로 나오며, 변환 컬럼은 값 비교에서 제외한다.
- **재개 보완**: `Item.created`를 저장해, 이 작업이 만든 테이블에는 재개 때 `MigrationService.completeStructure`가 같은 컬럼 구성의 인덱스·FK가 없을 때만 추가한다. 원래 있던 대상 테이블은 바꾸지 않는다.
- **버그 수정**: 일괄 이관을 취소한 뒤 재개하면, 취소 당시 적재 중이던 테이블이 "대기" 상태라 `건너뜀` 옵션에서 부분 적재된 채 건너뛰어졌다. 이제 이어서 적재한다(테스트 포함). 이 수정 전에 저장된 작업은 `created` 정보가 없어 기존 동작을 따른다.
- 한계: 화면에서 대상 타입·대상 테이블 이름은 지정할 수 없다. 단건 분할 이관의 재개는 인덱스·FK를 보완하지 않는다. 부모 테이블에 행 조건을 걸면 자식에서 FK 위반이 날 수 있어 사전 점검에서 안내만 한다.
- 검증: 백엔드 전체(Oracle/PostgreSQL/MySQL/MariaDB/SQL Server/CUBRID 실서버 설정) 173개 중 실패 0, 건너뜀 5(Tibero 1, 로컬 MySQL 4). 신규 `BatchTableOptionsIntegrationTests`는 기존 4조합에서 통과(SQL Server·CUBRID의 테이블별 설정·구조 보완은 별도 검증하지 않음). Playwright 전체 42개 중 37 통과, 4 건너뜀, 1 실패(`변환 규칙` 시나리오 90초 시간 초과, 단독 2회 재실행 통과 — 기존에 관찰된 간헐 실패와 같은 양상이며 원인 미확정). `cubrid.spec.ts`는 전용 환경 설정 없이 실행해 건너뜀. TypeScript 검사와 프로덕션 빌드 통과.

## 16. 2026-11-27 일괄 사전 점검 속도 개선, 요청 제한 응답 수정

- **원인**: 일괄 사전 점검은 테이블 하나를 분석할 때 DB 연결을 여러 번 새로 열었다(Oracle에서 특히 느림).
- **수정**: `JdbcExplorer.reusing(...)` 범위 안에서는 같은 접속 정보의 읽기 전용 연결과 테이블·컬럼·인덱스/FK 조회 결과를 재사용한다. 호출 스레드에만 적용되고 끝나면 닫는다. 실패한 연결은 버린다. 일괄 사전 점검·이관 후 검증·실행 전 준비 조회에 적용했다. 적재와 DDL 단계는 기존대로 매번 연결한다. **범위 안에서 DDL을 실행하는 작업에는 쓰지 말 것**(캐시가 낡는다).
- **측정**(전용 컨테이너, 루프백, 빈 테이블 60개·컬럼 4개·FK 포함, 3회): Oracle→PostgreSQL 47.9/32.7/32.9초 → 6.2/1.6/1.5초, PostgreSQL→MariaDB 7.3/7.0/7.0초 → 0.6/0.5/0.5초. 측정 도구는 `BatchPlanPerformanceTests`(`TABLEFLOW_TEST_PERF_PLAN=true`일 때만). 200개 테이블, 원격 DB, 행이 많은 테이블(행 수 조회가 지배적)은 재지 않았다.
- **E2E 실패 원인 확인**: 전체 Playwright 실행에서 2개가 두 번 연속 같은 곳에서 실패했다. 브라우저 기록은 CORS 차단, 서버 로그는 preflight 뒤 POST가 앱에 도달하지 않음, 직전 60초 POST 120건이었다. `RequestLimits`의 429가 CORS 헤더 없이 나가고 있었다. 이제 허용 출처에는 헤더를 붙여 화면에 "요청이 많습니다" 사유가 전달된다. 기본 한도는 그대로이고 `tableflow.api.posts-per-minute`(`TABLEFLOW_POSTS_PER_MINUTE`)로 바꿀 수 있으며, Playwright 테스트 서버만 1000으로 올렸다. §10·§12 등에 적은 과거 간헐 실패가 모두 이 원인인지는 확인하지 못했다.
- 검증: 백엔드 전체(6종 DB 실서버 설정) 180개 중 실패 0, 건너뜀 6(성능 측정 1, Tibero 1, 로컬 MySQL 4). 신규 `ConnectionReuseIntegrationTests`(Oracle/PostgreSQL/MySQL/MariaDB)·`RequestLimitsTests` 통과. Playwright(JDBC·로컬 OCR) 42개 중 38 통과, 4 건너뜀(CUBRID 2, 스크린샷 2), 실패 0. TypeScript 검사 통과(프런트 소스는 테스트 설정만 변경). SQL Server·CUBRID의 속도 개선 효과는 재지 않았다.

## 17. 2026-11-27 단건 분할 이관 재개 때 인덱스·FK 보완

- **재현**: 대상에 같은 이름의 인덱스(MySQL 계열은 FK 제약)를 미리 두면 테이블 생성 뒤 DDL이 실패한다. 원인을 치우고 재개하면 작업은 성공하지만 외래 키가 빠진 채였다(수정 전 `MigrationResumeStructureIntegrationTests`의 Oracle↔PostgreSQL에서 FK 0개 확인).
- **수정**: `MigrationService.Progress.targetCreated/createdTarget`. CREATE TABLE 성공 직후 대상 식별값(DBMS·호스트·포트·DB·스키마·테이블)을 `JobStore.Stored.createdTarget`에 저장하고, 끝나지 않은 같은 작업을 재개할 때만 `completeStructure`를 부른다. 일괄 이관도 같은 경로를 쓰며 `Item.created`는 이제 CREATE가 실제로 성공했을 때만 true다(생성에 실패한 뒤 사용자가 직접 만든 테이블은 바꾸지 않는다).
- 한계: 10,000행 이하 즉시 실행(`/migrate`)은 작업 기록이 없어 보완하지 않는다. CREATE 성공과 기록 저장 사이에 서버가 꺼지면 보완 대상에서 빠진다. 이 수정 전에 실패한 단건 작업에는 기록이 없어 기존 동작을 따른다. DB가 거부해 만들 수 없는 인덱스·FK(데이터 중복, 타입 불일치 등)가 있으면 원인을 해결할 때까지 재개가 실패한다.
- 검증: 백엔드 전체(6종 DB 실서버 설정) 185개 중 실패 0, 건너뜀 6(성능 측정 1, Tibero 1, 로컬 MySQL 4). 신규 통합 테스트는 Oracle→PostgreSQL, PostgreSQL→Oracle, MySQL→MariaDB, MariaDB→MySQL 4조합 통과(SQL Server·CUBRID의 보완은 별도 검증하지 않음). Playwright 42개 중 38 통과, 4 건너뜀, 실패 0.

## 18. 2026-11-27 값 변환 확장, 변환 컬럼 검증, Oracle DATE 재개 수정

- **새 규칙**(`ValueTransform`): `dateFormat`, `regexReplace`, `regexExtract`, `case`. 날짜 형식은 허용 문자만 받고 엄격하게 해석한다(`YYYY`·`DD`도 연·일). 정규식은 문자 접근 횟수 상한으로 역추적 폭증을 끊는다. `Step`에 `cases` 필드를 더했다(기존 JSON 그대로 유효). 화면은 `TransformEditor`에 입력 추가.
- **변환 컬럼 검증**: `MigrationVerifier`가 원본을 5,000행씩 다시 읽어(`PK` 있으면 키 기반) 규칙 → 적재 때의 정규화(`TableEngine.normalize`)를 거친 집계를 만들고 대상 DB 집계와 비교한다. 일괄 이관 검증에도 적용된다. 서버 행 한도 초과 시 생략, CHAR 대상은 길이 비교 생략. 동기 요청이며 큰 테이블의 소요 시간은 재지 않았다.
- **날짜 → 일시**: 변환 결과가 날짜뿐이고 대상이 일시(Oracle DATE는 JDBC상 TIMESTAMP)면 00:00:00을 붙인다(`MigrationService.fit`).
- **버그 수정(테스트로 재현)**: 이 작업이 만든 Oracle 테이블에 날짜 변환 결과를 적재하다 중단하면, 재개 때 컬럼 타입을 기존 테이블 기준(일시)으로 다시 계산해 분할 1에서 검증 오류로 실패했다. 재개(`createdTarget`)에서는 생성 당시와 같은 타입으로 계산하고 기존 테이블의 DDL은 내지 않는다. 일괄 이관도 같은 경로다. 이 정보가 없는 경우(10,000행 이하 즉시 실행의 재시도, 사용자가 만든 테이블)는 기존 계산을 따른다.
- 검증: 백엔드 전체(6종 DB 실서버 설정) 194개 중 실패 0, 건너뜀 6(성능 측정 1, Tibero 1, 로컬 MySQL 4). 새 규칙·검증·재개 통합 테스트는 Oracle→PostgreSQL, PostgreSQL→Oracle, MySQL→MariaDB, MariaDB→PostgreSQL 4조합(5,200행) 통과. SQL Server·CUBRID에서는 새 규칙의 검증·재개를 따로 확인하지 않았다. Playwright 전체 42개 중 38 통과, 4 건너뜀, 실패 0. 그 뒤 괄호 번호 입력 폭만 고치고 타입 검사·빌드·`변환 규칙` 시나리오를 다시 통과했다.

## 19. 2026-11-27 순환·자기 참조 FK의 적재 후 생성, 기본값 복제 확대

- **자기 참조 FK**: 이전에는 복제하지 않았다. 이제 이번 작업이 만든 테이블에 한해 모든 행 적재 후 `fks_<테이블>_n`으로 만든다(즉시 실행·분할·일괄·재개). 같은 이름의 다른 스키마 테이블 참조는 계속 복제하지 않는다. 사전 점검의 DDL 미리보기에 해당 ALTER 문이 포함된다.
- **순환 참조**: 일괄 이관이 `cycles` 테이블을 모두 적재한 뒤, 작업이 만든 테이블에 빠진 FK를 `MigrationService.addForeignKeysAfterLoad`로 추가한다. 건너뛴 테이블·추가 적재한 기존 테이블은 바꾸지 않는다. 단건 이관을 테이블별로 하는 경우의 순환은 자동 처리하지 않는다.
- **실패 처리**: 적재 후 FK를 DB가 거부해도 작업은 성공하고 안내를 남긴다(이전에도 이런 FK는 만들지 않고 성공했다). 성공한 작업은 재개할 수 없으므로 데이터를 고친 뒤 DB에서 직접 추가해야 한다.
- **기본값**(`TableDdl.convertDefault`): 따옴표 음수(`'-1'::integer`), `.5` 형태, `b'1'`, 오늘 날짜(`CURRENT_DATE`·`curdate()`·`TRUNC(SYSDATE)`), `getdate()`·`sysdatetime()`, ISO 날짜·일시 리터럴을 추가했다. Oracle·Tibero 대상 날짜 기본값은 `DATE '…'`/`TIMESTAMP '…'`로 쓰고 형식이 다른 문자열은 복제하지 않는다. Oracle 원본의 `CURRENT_DATE`는 시각을 포함하므로 현재 시각으로 옮긴다. PostgreSQL 등의 `CURRENT_DATE`를 일시 컬럼에 옮길 때 이전에는 현재 시각이 되었으나 이제 오늘 0시다.
- 검증: 백엔드 전체(6종 DB 실서버 설정) 207개 중 실패 0, 건너뜀 6(성능 측정 1, Tibero 1, 로컬 MySQL 4). 신규 `CircularKeyAndDefaultIntegrationTests`: FK는 Oracle→PostgreSQL, PostgreSQL→Oracle, MySQL→MariaDB, MariaDB→MySQL, 기본값은 여기에 MariaDB→PostgreSQL, PostgreSQL→MySQL 통과. SQL Server·CUBRID·Tibero의 적재 후 FK와 기본값 확대는 실서버에서 따로 확인하지 않았다(SQL Server 식은 단위 테스트만). Playwright 전체 42개 중 38 통과, 4 건너뜀, 실패 0. 타입 검사·프로덕션 빌드 통과.

## 20. 2026-11-27 실패 시 새로 만든 빈 테이블 삭제(선택 옵션)

- **옵션**: `dropEmptyOnFailure`(기본 꺼짐). 즉시 실행·분할·일괄·재개·스케줄에 적용한다. `MigrationService.afterDroppingEmpty`가 **이번 실행에서 CREATE에 성공한** 테이블만, 행이 없을 때만 `DROP TABLE`(CASCADE·PURGE 없음)한다. 중지와 `LOAD_OUTCOME_UNKNOWN`은 제외. 삭제하면 `Progress.targetDropped`로 "이 작업이 만든 테이블" 기록을 지워, 재개 때 새로 만든다. 이 프로젝트에서 앱이 `DROP`을 실행하는 첫 경로이므로 조건을 넓힐 때는 주의할 것.
- 일부러 좁힌 점: 이전 실행에서 만든 빈 테이블(예: 서버가 꺼진 뒤 재개)은 이름만으로 같은 테이블임을 보장할 수 없어 삭제하지 않는다.
- **문서 정정**: PostgreSQL 대상도 DDL이 적재와 다른 연결에서 커밋되어 빈 테이블이 남는다(Oracle→PostgreSQL 통합 테스트로 확인). README와 파일→DB 적재의 생성 경고, 코드 주석의 "PostgreSQL은 롤백" 설명을 고쳤다. `TEST_RESULTS.md`와 §4의 과거 기록에 있는 같은 설명은 고치지 않았다.
- 검증: 백엔드 전체(6종 DB 실서버 설정) 211개 중 실패 0, 건너뜀 6(성능 측정 1, Tibero 1, 로컬 MySQL 4). 신규 `DropEmptyTableIntegrationTests`는 Oracle→PostgreSQL, PostgreSQL→Oracle, MySQL→MariaDB, MariaDB→MySQL 통과(옵션 끔, 분할 실패, 일부 적재, 중지, 기존 테이블, 즉시 실행, 일괄 재개). SQL Server·CUBRID·Tibero의 삭제는 실서버에서 확인하지 않았다. Playwright 전체 42개 중 38 통과, 4 건너뜀, 실패 0(옵션이 요청에 실리는지 확인 추가, 삭제 동작 자체는 백엔드 테스트로 검증).
- 백엔드 전체 실서버 테스트가 약 10분 걸린다. 10분 제한이 있는 도구에서는 백그라운드로 실행할 것.

## 21. 2026-11-27 기본 키 없는 테이블의 대용량 분할 이관

- **재현·측정**(`KeylessPagingPerformanceTests`, `TABLEFLOW_TEST_PERF_PAGING=true`일 때만. Oracle→PostgreSQL, 100만 행, 컬럼 5개, 분할 10,000행): 수정 전에는 고유 인덱스 유무와 관계없이 **분할 74/100에서 읽기 시간 초과로 실패**했다(같은 요청으로 재개해도 같은 지점). 원인은 OFFSET 페이지가 뒤로 갈수록 느려지는데 접속의 소켓 읽기 제한 10초가 문장 제한(30초)보다 먼저 걸린 것이다. 30만 행(약 15초)에서는 드러나지 않았다.
- **수정**: ① 기본 키가 없으면 모든 컬럼이 NOT NULL인 고유 인덱스(컬럼 수가 가장 적은 것)를 키로 쓴다. 조건부(부분)·식·NULL 허용·부동소수점 컬럼이 낀 인덱스는 쓰지 않는다. CUBRID·Tibero는 확인하지 않아 기본 키만 쓴다. ② 분할 읽기의 제한을 120초(`JdbcExplorer.allowSlow`)로 늘렸다. 같은 이유로 10초에 잘리던 이관 후 검증 집계와 적재 후 인덱스·FK DDL(문장 제한 300초)에도 적용했다.
- **결과**: 고유 인덱스 있음 39.6초(첫 분할 0.49초, 마지막 0.34초), 없음 351초로 완료(첫 0.44초, 마지막 10.8초). 키가 전혀 없는 테이블은 여전히 행 수에 따라 느려지며, 한 분할 읽기가 120초를 넘으면 실패한다.
- **주의**: 이 수정 전에 시작해 중단된 "기본 키 없음 + 고유 인덱스 있음" 작업은 페이지 순서가 바뀐다. 이미 적재한 분할의 내용이 달라지면 영수증 충돌(`LOAD_REQUEST_CONFLICT`)로 멈추고 중복 적재하지 않는다(코드 검토에 따른 판단이며 업그레이드 시나리오는 테스트하지 않았다).
- 검증: 백엔드 전체(6종 DB 실서버 설정) 220개 중 실패 0, 건너뜀 7(성능 측정 2, Tibero 1, 로컬 MySQL 4) / Playwright 42개 중 38 통과, 4 건너뜀, 실패 0. 신규 `UniqueKeyPagingIntegrationTests`는 4조합(복합 고유 인덱스, 중지·재개, 사용할 수 없는 인덱스)과 4개 DB의 12초 지연 문장(기본은 실패, 허용하면 성공)을 확인했다. 적재 후 DDL과 검증 집계의 시간 제한 변경은 큰 데이터로 재현하지 않았다.

## 22. 2026-11-27 CI에 실서버 DB 작업 추가

- `.github/workflows/ci.yml`의 `database` 작업: `infra/jdbc/compose.yml`(oracle 프로필)로 4종 DB를 띄우고 → Oracle 준비 → 백엔드 전체 테스트(`TABLEFLOW_TEST_JDBC`) → DB 브라우저 스펙 5개. 기존 `verify` 작업은 바꾸지 않았다. 제조사 드라이버가 필요한 SQL Server·CUBRID·Tibero는 제외했다. 컨테이너 계정은 compose의 일회용 테스트 값이다.
- **재현에서 발견**: 새 컨테이너로 처음 돌렸을 때 220개 중 15개 실패(모두 Oracle 관련). 새 Oracle은 앱 계정의 첫 INSERT가 25초 걸렸고(두 번째부터 0.06초), 테스트 연결의 소켓 읽기 제한 10초가 이 초기화를 계속 끊었다. CI에 첫 INSERT를 끝까지 실행하는 준비 단계를 넣었다. **로컬에서 Oracle 컨테이너를 새로 만들었을 때도 같은 준비가 필요하다**(CI 단계의 SQL 참고).
- **제품에도 해당할 수 있는 점(미수정)**: 방금 설치한 느린 Oracle에서는 앱의 적재도 접속 읽기 제한(10초)에 걸려 실패할 수 있다. `JdbcLoader`의 문장 제한(20~40초)과 `executeDdl`(30초)이 소켓 제한보다 길어 서로 맞지 않는다. §21처럼 `allowSlow`를 적용할지 검토할 것.
- 검증(로컬, 새 컨테이너, CI와 같은 순서): 백엔드 220개 중 실패 0, 건너뜀 17(SQL Server 4, CUBRID 6, Tibero 1, 로컬 MySQL 4, 성능 측정 2), DB 브라우저 18개 통과. Oracle은 새로 만들어 준비 단계를 거쳤고, PostgreSQL·MySQL·MariaDB는 직전 재현에서 새로 만든 것을 이어 썼다(그 첫 실행에서 비-Oracle 테스트는 모두 통과). Linux 러너에서는 실행하지 않았다.
- 새 컨테이너 재현에는 `TABLEFLOW_TEST_COMPOSE_PROJECT`(브라우저 테스트가 쓰는 compose 프로젝트 이름, 기본 `tableflow-jdbc-tests`)를 추가해 사용했다.

## 23. 2026-11-27 EAI 전환 설계

- 처음에는 ETL → ESB → EAI 확장 로드맵(포트폴리오 우선)을 만들었으나, 같은 날 사용자가 시연 제약을 없애고 EAI 기준 재설계를 요청했다. 그 초안을 [EAI 설계 기준](EAI_ARCHITECTURE.md)으로 다시 썼다(이전 파일 `INTEGRATION_ROADMAP.md`는 이름을 바꿈).
- 결정: EAI 플랫폼 / 기존 이관은 대량 배치 연계 모듈 / 혼합형 / 그림 설계기 / 내부망 설치 + 외부 연계 / Spring Integration.
- 단계 순서는 상의 후 **흐름 먼저**로 정했다(§13, 옛 6~11단계 대체). 인증은 3단계이며 그 전에는 루프백에서만 연다. 단계별 범위·완료 기준은 제안이고 §14의 열린 결정이 남았다. `CLAUDE.md`의 제품 방향 문구는 아직 바꾸지 않았다. 코드는 바꾸지 않았다.
- 내부망 설계 기준 §8의 12단계 상태를 "일부 구현"으로 바로잡았다.

## 24. 2026-11-27 EAI 1단계(상태 DB) 상세 설계

- [상태 DB 상세 설계](EAI_STEP1_STATE_DB.md) 초안을 작성했다. 사용자 결정: PostgreSQL 필수 / Spring JDBC + Flyway / 기존 JSON 가져오기 / 기존 저장만. 표 설계·실행권 수치·작업 순서는 제안이며 구현 전이다.
- 구현하면 기본 `gradlew test`에도 상태 DB 컨테이너가 필요해지고 `CLAUDE.md`·README의 명령을 고쳐야 한다. 코드는 아직 바꾸지 않았다.

## 25. 2026-11-27 설치 요구: 전부 포함 오프라인 설치

- 사용자 요구: 인터넷 없이 설치·동작하려면 의존성 파일, 도구, DB까지 함께 설치하는 형태여야 한다. [EAI 설계](EAI_ARCHITECTURE.md) §4.3과 [상태 DB 설계](EAI_STEP1_STATE_DB.md) §4·§10에 반영했다. 구현 전이다.
- 결정: Windows와 Linux 동시 지원(Linux는 RHEL 계열부터), 상태 DB는 내장 PostgreSQL만, 첫 설치 패키지는 2단계 직후 최소판.
- 미확정: OS 버전, 설치 시험용 가상 장비, 구성요소별 재배포 조건, 기존 사내 브로커 연결 선택지.

## 26. 2026-11-27 EAI 1-a: 상태 DB 접속·Flyway·상태 확인

- **지금부터 백엔드 실행과 모든 Spring 테스트에 상태 DB가 필요하다.** §2의 명령 전에 시험용은 `docker compose -f infra/state/compose.yml up -d --wait`(15443, `build.gradle`·`playwright.config.ts`가 기본으로 사용), 개발용은 `.env`에 `TABLEFLOW_STATE_DB_PASSWORD`를 넣고 `docker compose up -d --wait state-db`(15442, `scripts/backend.ps1`이 사용). 기존 `.env`에는 이 줄을 추가해야 한다. 루트 compose에서 MySQL만 띄울 때는 `docker compose up -d --wait mysql`.
- 코드: `dev.tableflow.state`(설정 검사, `DataSource`, 상태 확인), `db/migration/V1__state_foundation.sql`(`app_meta`), `/api/v1/health`에 `stateDb`(중단 시 503). 설정이 없거나 접속할 수 없으면 서버가 안내 후 시작하지 않는다.
- 아직 파일에 저장: 저장 연결·드라이버·스케줄·작업·이력(1-b~1-f). **1-f까지 끝나기 전에는 배포하지 말 것.**
- 검증: 백엔드 전체(6종 DB 실서버 설정) 228개 중 실패 0, 건너뜀 7(성능 측정 2, Tibero 1, 로컬 MySQL 4). 실제 jar로 설정 없음·접속 불가 시 시작 거부(로그에 비밀번호 없음), 정상 시작 200 → DB 중지 503 → 재시작 200 확인. Playwright 42개 중 38 통과, 4 건너뜀, 실패 0. 바뀐 CI는 푸시 전이라 GitHub에서 실행하지 않았다. `scripts/backend.ps1`은 이 PC의 개발용 `state-db`로 실제 시작해 상태 확인 200과 V1 적용을 확인했다(실행 로그에 비밀번호 없음). Linux에서는 실행하지 않았다.
- **GitHub 첫 실행(c668b07, 실행 45947322628)**: `verify` 성공. `database`는 컨테이너 기동·Oracle 준비·백엔드 실서버 테스트·프런트 빌드까지 성공했고 **DB 브라우저 테스트 단계가 실패**했다. 로그는 로그인해야 볼 수 있어 원인 미확인. 실패 내용이 공개 주석에 남도록 CI에 `--reporter=list,github`를 추가했다(아직 커밋 전).
