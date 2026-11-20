# 인수인계 (2026-11-20 기준)

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

**2026-11-20 후속**: 신규 테이블 이관에서 대상 타입·길이·소수 자릿수를 컬럼별로 직접 지정(백엔드 `Mapping.type/size/scale`, 화면 검토 단계), 컬럼 매핑에 PK/FK 표시(`ColumnInfo.foreignKey`), 메뉴 활성 표시를 단색 채움으로 변경, 미리보기 표를 가로 스크롤 없이 폭에 맞춤(줄임표+툴팁). **이 후속 변경은 백엔드 기본 단위 테스트만 통과 확인했고, 실서버·Playwright 테스트는 아직 돌리지 않았다.**
