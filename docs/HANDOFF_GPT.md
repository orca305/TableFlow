# TableFlow 인수인계 요약 (다른 AI 도구·개발자용)

작성: 2026-11-24 · 기준 커밋: `ce7da37` (`main`) · 저장소: <https://github.com/orca305/TableFlow>

이 문서는 ChatGPT/Codex 등 **다른 도구에서 이어서 개발**할 때 가장 먼저 읽는 요약입니다. 더 자세한 배경과 시행착오는 [HANDOFF.md](HANDOFF.md)(시간순 기록), 작업 규칙은 [CLAUDE.md](../CLAUDE.md), API는 [api.md](api.md), 검증 근거는 [TEST_RESULTS.md](TEST_RESULTS.md)에 있습니다. **문서와 코드가 다르면 코드와 테스트 결과를 믿고 문서를 고치세요.**

> **2026-11-25 구현 갱신:** [내부망 전환 진행 기록](INTRANET_PROGRESS.md). 공통 SQL·관리자 반입 JDBC JAR·버전 선택 및 SQL Server Developer 실서버 검증을 완료했다. SQL Server도 기본 제공 JAR 대신 관리자 반입 JAR를 사용한다. 후속 사용자 재개 요청으로 CUBRID 제한 지원과 Tibero 실험 어댑터를 추가했다. [DBMS 확장 검증](DBMS_EXPANSION.md) 참조. Tibero 실서버 검증·오프라인 설치·상태 DB·RabbitMQ·Celery는 남아 있다. 아래 기존 기능 목록과 구분한다.

## 1. 프로젝트 한 줄 소개

> **2026-11-25 제품 방향 갱신:** 개발자·DB 운영자용 **내부망 설치형**으로 개발한다. 사용자 반입 JDBC 드라이버, 오프라인 설치물, RabbitMQ + Java 이관 워커, Celery OCR, 후속 수집 에이전트가 목표다. 상세 설계·현재 코드 격차·개발 순서는 [INTRANET_ARCHITECTURE.md](INTRANET_ARCHITECTURE.md)를 먼저 읽는다. 이 항목들은 설계이며 아직 구현 완료가 아니다. 아래 기존 호스팅 과제보다 이 방향을 우선한다.

파일(CSV·Excel·PDF·이미지)과 관계형 DB(Oracle · PostgreSQL · MySQL · MariaDB)를 원본/대상으로 삼아 표 데이터를 검토·변환·이관하는 ETL/마이그레이션 도구입니다. 백엔드 Spring Boot(Java 21), 프런트엔드 Next.js 16/React 19/TypeScript, OCR은 FastAPI + RapidOCR(선택).

- 서버에 **로그인·권한이 없습니다.** 로컬·내부망 도구 전제입니다.
- 소개용 배포: <https://table-flow-blond.vercel.app/> — Vercel에는 프런트엔드만 있고 **백엔드가 없어 기능이 동작하지 않습니다.** 환경변수 `NEXT_PUBLIC_DEMO_MODE=true`로 빌드되어 경고 배너를 보여 주고 모든 API 호출을 막습니다.

## 2. 지금까지 개발한 기능

### 2-1. 파일 변환
- CSV(UTF-8/BOM, MS949, EUC-KR, 구분자 자동), XLSX(시트·헤더 행 선택, 수식 원문 보존), PDF 텍스트 표(최대 20페이지)
- 이미지·스캔 PDF 로컬 OCR(셀별 신뢰도, 원본 대조 후 검토 확인). 외부 OCR은 서버 허용 + 파일별 동의가 모두 있을 때만, 로컬 실패 시 자동 전환 금지
- 타입 추정, 셀 편집, 정규화(정수·소수·날짜·불리언), 필수값·길이·중복 검증
- 출력: Oracle/PostgreSQL/MySQL/MariaDB INSERT SQL, XLSX, CSV, DB 직접 적재(신규 테이블 생성·기본 키 지정 포함)

### 2-2. DB → DB 이관 (핵심)
- 연결·스키마/테이블/컬럼/기본 키 탐색, 읽기 전용 행 추출(DB → 파일/SQL, 최대 10,000행)
- **컬럼 매핑** 자동 추천(이름·업무 용어·유사도, 모호하면 미확정), 대상 타입·길이·정밀도 직접 지정
- **사전 점검**(행 수·타입 변환·거부 사유·생성될 DDL 미리보기) 후 동의 → 적재
- **대상 테이블 자동 생성** + 인덱스·기본값·외래 키 복제 옵션, 부모 테이블 이관 순서 계산(순환 감지)
- **분할 적재·재개**: 기본 키 keyset 읽기, 분할(기본 5,000행)마다 별도 트랜잭션 + 적재 영수증(`tableflow_load_receipts`). 같은 요청 ID로 재제출하면 완료된 분할을 건너뛰고 이어서 실행. 백그라운드 작업·진행률·취소. 작업 목록은 `data/jobs.json`에 영속화
- **원본 키 보존** + 시퀀스 재동기화(PostgreSQL `setval`, Oracle, MySQL/MariaDB)
- **행 조건(WHERE)·컬럼 선택**: `RowFilter`가 입력을 검증(WHERE 키워드·`;`·주석·DML/DDL·`UNION`·`SLEEP` 계열 차단)
- **값 변환 규칙**: 치환·계산식·날짜 형식·정규식·조건별 값(`ValueTransform`, BigDecimal 기반 안전한 평가기)
- **이관 후 검증**: 건수와 집계 비교(`MigrationVerifier`, 변환 컬럼은 원본에 규칙을 다시 적용한 결과와 비교)
- **일괄 이관**: 여러 테이블·스키마를 외래 키 순서로 통째 이관(최대 200개), 중단 후 재개
- **스케줄**: 매일 정해진 시각 또는 일정 간격(5분~7일), 매 실행 전 대상을 비우고 다시 적재하는 모드, 15초 주기 확인. 비밀번호를 저장한 연결만 사용

### 2-3. 공통
- 작업 이력(`data/job-history.jsonl`, 비밀번호·호스트·데이터 미저장), 저장 연결(사용자가 "비밀번호 저장"을 고른 연결만 AES-256-GCM으로 `data/connections.json`에 암호화 저장, 키는 `TABLEFLOW_SECRET_KEY` 또는 `data/connections.key`, 브라우저로 내려보내지 않음)
- 화면 메뉴: DB 이관 · 일괄 이관 · 파일 변환 · 스케줄 · 작업 이력 · 설정, 라이트/다크, 용어 툴팁
- 보안 한계값: 파일 10MB·10,000행·100열, JSON 32MB, IP당 POST 120회/분(`TABLEFLOW_POSTS_PER_MINUTE`), 동시 4건, DB 기능 기본 꺼짐 + 접속 호스트 allowlist

## 3. 검증 상태 (정직하게)

| 구분 | 결과 |
|---|---|
| 백엔드 `gradlew test`(6종 DB 실서버 설정, 2026-11-27) | 211개 중 실패 0, 건너뜀 6(성능 측정 1, Tibero 1, 로컬 MySQL 4) |
| Playwright(실서버·로컬 OCR 포함, 2026-11-27) | 38 통과, 4 건너뜀(CUBRID 2 전용 설정 필요, 스크린샷 2), 실패 0 |
| 실서버 이관 조합 | Oracle→PostgreSQL, PostgreSQL→Oracle, MySQL→MariaDB, MariaDB→PostgreSQL. **스케줄은 MySQL→MariaDB만** |
| 대용량 | Oracle→PostgreSQL 100만 행 41.4초(약 24,200행/초), 5개 컬럼, 백엔드 `-Xmx768m` |
| 원격 CI(GitHub Actions) | 성공(`50584a7` 이후). **CI에는 DB 컨테이너가 없어 실서버 통합·DB E2E는 건너뜀** — 로컬에서만 검증됨 |
| 미검증 | 외부 OCR 실제 호출, 다른 DB 서버 버전·드라이버, 공개 배포 시 백엔드 |

2026-11-27에 전체 실행 실패의 원인 하나를 확인해 고쳤습니다. 빠른 연속 실행이 요청 제한(1분 120건)을 넘으면 429 응답에 CORS 헤더가 없어 화면이 멈춘 것처럼 보였습니다(HANDOFF §16). 이전에 기록한 간헐 실패가 모두 이 원인인지는 확인하지 못했습니다.

## 4. 개발 환경과 명령

필수: **JDK 21**(이 PC의 기본 java는 17이므로 `JAVA_HOME=C:\Users\JBWOO\.jdks\openjdk-21.0.2` 필요), Node 20.9+, Docker Desktop, (OCR) Python 3.13. Windows PowerShell 기준입니다.

```powershell
# 기본 테스트(실제 DB 없이)
cd C:\dev\TableFlow\backend;  .\gradlew.bat test bootJar
cd C:\dev\TableFlow\frontend; npm ci; npx tsc --noEmit

# 개발 서버: 백엔드 8080, 프런트 3100
cd C:\dev\TableFlow; .\scripts\backend.ps1
cd C:\dev\TableFlow\frontend; npm run dev
# DB 기능을 화면에서 쓰려면 백엔드를 TABLEFLOW_JDBC_ENABLED=true TABLEFLOW_JDBC_LOAD_ENABLED=true 로 시작(기본은 꺼짐)
```

실제 DB 통합 테스트(전용 일회용 컨테이너, 루프백 15432/13316/13317/11521):

```powershell
cd C:\dev\TableFlow
docker compose -f infra/jdbc/compose.yml --profile oracle up -d --wait --wait-timeout 600   # Oracle 이미지 약 3GB
cd backend
$env:TABLEFLOW_TEST_JDBC='true'; $env:TABLEFLOW_TEST_JDBC_DBMS='oracle,postgresql,mysql,mariadb'
.\gradlew.bat test --rerun-tasks
```

브라우저 E2E(백엔드 jar와 프런트 프로덕션 빌드가 필요, 서버 코드를 바꿨으면 **다시 빌드**):

```powershell
cd C:\dev\TableFlow\backend; .\gradlew.bat bootJar
cd ..\frontend; $env:NEXT_PUBLIC_API_URL='http://localhost:18080/api/v1'; npm run build
$env:TABLEFLOW_TEST_JDBC='true'; $env:TABLEFLOW_TEST_LOCAL_OCR='true'
npx playwright test     # Playwright가 백엔드 18080·프런트 3101을 직접 띄움(JDK 21이 PATH에 있어야 함)
```

수동 확인용 백엔드: 별도 폴더에서 `java -jar backend/build/libs/tableflow-0.1.0.jar --server.port=18080 --tableflow.frontend-origin=http://localhost:3101 --tableflow.jdbc.enabled=true --tableflow.jdbc.load-enabled=true`(저장소 파일이 저장소 폴더에 생기지 않도록 작업 폴더를 분리).

## 5. 코드 구조

```text
backend/src/main/java/dev/tableflow/
  api/         ConnectionController(/api/v1/connections/*), ScheduleController·ScheduleService, WebConfig(CORS)
  connection/  JdbcExplorer(연결·메타데이터), JdbcReader(읽기·keyset·where), RowFilter, ConnectionStore(저장 연결 암호화)
  load/        JdbcLoader, MigrationService(DB→DB 핵심), MigrationJobs·JobStore, BatchMigration, ValueTransform,
               MigrationVerifier, ScheduleStore, TableDdl, JobHistory
backend/src/test/...   단위·통합 테스트(실서버는 TABLEFLOW_TEST_JDBC=true일 때만)
frontend/src/app/page.tsx            메뉴·화면 전환(모든 화면 마운트 후 숨김)
frontend/src/components/             MigratePage, BatchPage, FilePage, SchedulePage, ScheduleForm, TransformEditor,
                                     SettingsPage, HistoryPage, ConnectionPicker, TablePicker, ui
frontend/src/lib/                    api.ts(요청·데모 모드), connections.ts, workspace.ts(자동 매핑)
frontend/tests/*.spec.ts             Playwright(migration, batch, schedule, db-to-file, workflow …)
ai-service/                          FastAPI OCR(mock/local/external)
infra/jdbc/compose.yml               실서버 테스트용 컨테이너(Oracle·PG·MySQL·MariaDB)
.github/workflows                    CI("TableFlow CI", push마다 실행)
```

## 6. 반드시 지킬 규칙

- 화면 문구는 통일된 업무 용어: **이관**(이전 아님), **원본/대상**, **분할**(청크 아님), **사전 점검**(Dry-run 아님), **컬럼 매핑**, **기본 키**, **재개**. 새 문구도 따릅니다.
- 구현과 검증을 구분해 보고합니다(통과/실패/건너뜀, mock·H2 vs 실서버). **건너뛴 테스트를 검증으로 세지 않습니다.** 기존 동작을 보존하고 수정 후 회귀 테스트를 돌립니다.
- 위험한 작업(데이터·Docker 볼륨 삭제, 강제 push/reset, 운영 DB 변경)은 사용자 승인 후. **Docker 이미지·볼륨은 TableFlow 전용이 아닐 수 있으니 임의로 정리하지 않습니다.**
- 실제 DB 테스트는 `infra/jdbc/compose.yml` 전용 컨테이너에서만. DB 비밀번호는 Git·브라우저 저장소·로그·이력에 남기지 않습니다(예외: "비밀번호 저장" 연결의 암호화 저장).
- 커밋·푸시는 사용자가 요청할 때만. 작업을 끝내면 `docs/HANDOFF.md`와 이 문서를 갱신합니다.

## 7. 알려진 함정

- sqlplus로 테스트 데이터를 만들 때 한 줄에 `;` 문장을 여러 개 쓰면 첫 문장만 실행됩니다 → 문장마다 줄 분리.
- Playwright 백엔드는 JDK 21이 PATH에 있어야 합니다(기본 PATH는 17). Windows에서 OCR 서버는 `ai-service/.venv/Scripts/python.exe` 절대 경로, Linux CI는 `python`(이미 반영됨, `frontend/playwright.config.ts`).
- Docker Desktop이 시작 중 크래시(`sailor-ingest.sock`/`engine.sock`, 오류 1920)하면 소켓 폴더 이름을 바꾸는 방식으로 복구(상세는 HANDOFF §5). 이미지·볼륨은 지우지 마세요.
- Oracle `DATE`는 시간을 포함해 TIMESTAMP로 보고됩니다. PostgreSQL은 식별자 대소문자를 접기 때문에 대문자 컬럼은 따옴표로 지정하세요.
- 데모 모드로 빌드한 뒤에는 `frontend/.next`가 데모 빌드입니다. E2E 전에 `NEXT_PUBLIC_API_URL`로 다시 빌드하세요.
- `gh` CLI는 이 PC에 없습니다. CI 상태는 `https://api.github.com/repos/orca305/TableFlow/actions/runs`(공개 API)로 확인했습니다.

## 8. 앞으로 할 일 (우선순위 순)

### A. 포트폴리오 마무리 (가장 가까운 목표)
1. 신규 기능 스크린샷/GIF 추가(일괄 이관, 값 변환, 행 조건, 사후 검증, 스케줄). 현재 README 스크린샷 4장은 이전 기능 시점. `SCREENSHOTS=true npx playwright test tests/screenshots.spec.ts`가 기준
2. 커밋 이력에 테스트용 비밀번호(`TableFlowTest2026` 등)가 있는지 점검하고, 테스트 전용 값임을 README에 유지
3. 3~5분 시연 영상 또는 GIF, 아키텍처 그림 다듬기
4. LICENSE(MIT, 저작권자 "TableFlow contributors")의 종류·이름을 소유자가 확정
5. (선택) Vercel 소개용 배포 문구 점검 — 이미 배포됨, 백엔드 없음을 배너로 안내

### B. 기능 보강
1. ~~일괄 이관: 테이블별 컬럼 매핑·변환·행 조건 지정~~ **완료(2026-11-26, HANDOFF §15).** 남은 것: 화면에서 대상 타입·대상 테이블 이름 지정
2. ~~일괄 이관 후 검증 화면 연결~~ **완료(2026-11-26).** 값이 다른 같은 길이 문자열을 놓치는 집계 비교의 한계(실제 체크섬)는 남아 있다
3. ~~일괄 사전 점검 성능~~ **완료(2026-11-27, HANDOFF §16).** 연결·메타데이터 재사용. 200개 테이블, 원격 DB, 행이 많은 테이블의 시간은 재지 않았다
4. ~~재개된 테이블의 인덱스·FK DDL 재실행~~ **완료(일괄 2026-11-26, 단건 분할 이관 2026-11-27, HANDOFF §17).** 남은 것: 10,000행 이하 즉시 실행은 DDL 실패 후 다시 실행해도 보완하지 않는다
5. ~~값 변환 확장과 변환 컬럼 검증~~ **완료(2026-11-27, HANDOFF §18).** 남은 것: AND/OR 복합 조건, 값 단위 비교(실제 체크섬), 큰 테이블의 변환 검증 시간 측정
6. ~~순환 FK 처리(적재 후 추가), 기본값 복제 범위 확대~~ **완료(2026-11-27, HANDOFF §19).** 남은 것: 시퀀스·자동 증가·그 밖의 함수 기본값, 적재 후 거부된 FK만 다시 시도하는 기능
7. ~~DDL 비원자성 보완: 실패 시 새로 만든 빈 테이블 정리 옵션~~ **완료(2026-11-27, HANDOFF §20).** PostgreSQL도 빈 테이블이 남는다(이전 문서의 "PostgreSQL은 롤백"은 틀린 설명). 남은 것: 파일 → DB 적재에는 옵션이 없다
8. 작업 이력에서 실패한 이관을 바로 재개(요청 본문을 비밀번호 없이 저장해야 함)
9. 기본 키 없는 테이블의 대용량 성능(현재 OFFSET 방식)

### C. 운영·품질
1. **CI에 실서버 DB 컨테이너 추가**(PostgreSQL·MariaDB는 서비스 컨테이너로 가능, Oracle은 이미지가 커서 선택) → 실서버 테스트를 CI로 이동
2. 스케줄 실서버 검증을 다른 조합으로 확대, 시간대 지정, 실행 결과 알림, 증분 이관(변경분만)
3. 인증/접근 제어(공개하려면 필수): 최소 단일 사용자 로그인 또는 리버스 프록시 인증
4. 간헐 E2E 실패: 요청 제한 원인은 해결(HANDOFF §16). 다른 원인의 실패가 다시 나타나면 서버·브라우저 로그부터 확인
5. 성능 측정 확대: 다른 DB 조합, 컬럼이 많은 테이블, 큰 행
6. 외부 OCR 공급자 실제 호출 검증

### D. 확장 (여유 시)
1. 추가 DBMS: SQL Server, SQLite, Tibero, CUBRID, DB2(명세 Phase 5)
2. 백엔드 Dockerfile·실제 호스팅(Render/Fly.io 등)으로 라이브 데모(DB 기능은 끄고 파일 변환만 노출 권장)
3. SQL 파일 스크립트 내보내기(DDL 포함), 대상 DB 객체(뷰·시퀀스·트리거) 이관 범위 검토

## 9. 이어서 시작하는 방법

1. `git pull` 후 이 문서와 [CLAUDE.md](../CLAUDE.md)를 읽습니다.
2. `backend`에서 `gradlew test`, `frontend`에서 `npx tsc --noEmit`으로 기준선을 확인합니다(실패가 있으면 먼저 원인 파악).
3. 위 8번 목록에서 하나를 골라 **작게 구현 → 해당 테스트 추가(실서버 필요 시 전용 컨테이너) → 회귀 테스트 → 문서 갱신** 순으로 진행합니다.
4. 끝나면 변경 파일·검증 결과(통과/실패/건너뜀)·남은 과제를 한국어로 짧게 보고하고, `docs/HANDOFF.md`와 이 문서를 갱신합니다. 커밋·푸시는 요청받았을 때만 합니다.
