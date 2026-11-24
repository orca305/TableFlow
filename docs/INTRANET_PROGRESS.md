# 내부망 설치형 전환 진행 기록

기준: 2026-11-25. **사용자 요청에 따라 이번 변경은 공통 JDBC 기반·드라이버 반입·SQL Server Developer까지다. Tibero/CUBRID는 보류했으며 제품 코드·테스트·배포 설정에서 제외했다.** 12단계 전체 완료를 의미하지 않는다.

## 반영 범위

- `SqlDialect`: 기존 4종 DB와 SQL Server의 식별자 인용·페이지 조회·문자열 길이·스키마/카탈로그 규칙 통합. DBMS별 조회/적재/신규 테이블/키 보존 기능 상태를 API로 제공한다.
- `DriverCatalog`: 관리자 JAR 반입, 정식 드라이버 클래스 포함 검사, DBMS/버전별 UUID, SHA-256, 원자적 등록, 재시작 후 목록 복원. 최대 10MB/32개. 버전명은 관리자 입력 식별값이며 공급자 인증을 의미하지 않는다.
- `SingleJarClassLoader`: 버전별 격리 로더. Manifest의 외부 Class-Path를 따라가지 않으며 인터넷 다운로드를 하지 않는다. 선택 의존성은 자동 설치하지 않는다. Windows JAR 리소스 캐시의 파일 잠금 문제를 방지했다.
- `Credentials.driverId`: 저장 연결·비밀번호 대조·작업 저장/재개에 유지. 생략하면 기존 기본 제공 드라이버를 사용한다. 다른 드라이버나 접속 정보로 저장 연결을 변경하면 비밀번호를 다시 입력해야 한다.
- 설정 화면에서 JAR 등록·목록 조회, 연결 화면에서 버전 선택. 관리자 키는 브라우저 저장소에 기록하지 않으며 요청 후 또는 설정 화면을 벗어나면 입력 상태를 제거한다.
- 드라이버 삭제/실행 중 교체는 지원하지 않는다. 참조 중 파일 삭제를 막기 위해 삭제 API 자체를 제공하지 않는다.
- SQL Server: Microsoft JDBC 연결/TLS·OFFSET/FETCH·Unicode NVARCHAR/DATETIME2/BIT·트리거/권한 점검·IDENTITY_INSERT·분할 재개·인덱스/기본값/FK 복제 지원.
- 실제 DB 테스트는 실행 플래그를 Gradle 입력으로 구분하고 외부 DB 상태에 대한 UP-TO-DATE 결과를 재사용하지 않는다.

## 관리자 반입 설정

- `TABLEFLOW_DRIVERS_ADMIN_KEY`: 32자 이상, 미설정이면 관리 API 거부. 환경의 비밀 설정으로 제공하고 Git·명령 이력·로그에 남기지 않는다.
- `TABLEFLOW_DRIVERS_DIRECTORY`: 기본 `data/drivers`, Git 제외.
- `GET /api/v1/driver-catalog`, `POST /api/v1/driver-catalog`.
- 헤더 `X-TableFlow-Admin-Key` + 루프백 주소 + 허용 Origin이 필요하다.
- 등록 multipart: `dbms`, `version`, `file`.
- 접속 요청: 선택 항목의 UUID를 `driverId`로 전달한다. 등록 드라이버 실패 시 기본 제공 드라이버로 자동 대체하지 않는다.
- 등록 JAR는 서버에서 실행되는 신뢰 코드다. 클래스 로더 분리는 악성 코드 샌드박스가 아니다. 신뢰하는 공급자 JAR만 반입한다.
- 원격 관리자·사용자 로그인·역할·감사 기능은 미구현이다. 현재 로컬 관리자 키가 내부 서버 인증을 대체하지 않는다.

## SQL Server Developer 실서버

2026-11-25 사용자가 Developer 약관 동의와 전용 테스트 컨테이너 설치·검증을 승인했다.

- 서버: SQL Server 2022 Developer, 제품 버전 `16.0.4325.4`.
- fixture: `infra/jdbc/compose.sqlserver.yml`, 별도 프로젝트, 루프백 `11433`만 공개. 이미지 digest는 파일에 고정했다.
- Microsoft JDBC `13.2.1.jre11`, Maven Central 배포 파일의 SHA-256을 확인한 뒤 관리자 반입 경로로 검증했다.
- JAR SHA-256: `2d137aa308c78932878952fe3624ea090ecfe644414b8b23e71a305f425698c5`.
- 공급자 JAR는 Git/기본 배포물에 포함하지 않았다. 로컬 검증 사본은 `backend/build/drivers/`에만 있다.
- 전체 DB 접근은 여전히 `TABLEFLOW_JDBC_ENABLED=true`, 적재는 `TABLEFLOW_JDBC_LOAD_ENABLED=true` 설정이 필요하다. SQL Server는 기본 활성화되어 있으며 `TABLEFLOW_SQLSERVER_ENABLED=false`로 별도 차단할 수 있다.

### 재현

1. Developer 약관에 동의한 환경에서 `TABLEFLOW_SQLSERVER_ACCEPT_EULA=Y` 설정 후 `docker compose -f infra/jdbc/compose.sqlserver.yml up -d --wait`.
2. 컨테이너 로컬 sqlcmd에 `infra/jdbc/sqlserver-test-init.sql`을 입력해 전용 빈 DB와 테스트 계정을 준비한다. 초기화가 완료되기 전에는 로그인하지 않는다. 운영 DB에서는 실행하지 않는다.
3. `TABLEFLOW_TEST_SQLSERVER_DRIVER`에 정식 Microsoft JDBC JAR 경로, `TABLEFLOW_TEST_SQLSERVER=true` 설정.
4. `backend/gradlew.bat test --tests '*SqlServerIntegrationTests'` 실행. PostgreSQL 왕복 테스트에는 기존 전용 `15432` fixture도 필요하다.
5. 기존 4종 DB 회귀는 `TABLEFLOW_TEST_JDBC=true`, `TABLEFLOW_TEST_JDBC_DBMS=oracle,postgresql,mysql,mariadb`로 함께 실행한다.

sqlcmd의 `-C`는 자체 서명된 루프백 fixture 초기화에만 사용한다. 제품 JDBC는 `trustServerCertificate=false`를 유지하며, 신뢰할 수 없는 인증서 연결을 거부하는 실제 테스트를 포함한다.

근거: [Microsoft 연결 속성](https://learn.microsoft.com/en-us/sql/connect/jdbc/setting-the-connection-properties?view=sql-server-ver17), [페이지 조회](https://learn.microsoft.com/en-us/sql/t-sql/queries/select-order-by-clause-transact-sql?view=sql-server-ver17), [컨테이너 설치·라이선스](https://learn.microsoft.com/en-us/sql/linux/quickstart-install-connect-docker?view=sql-server-ver17).

## 검증

SQL Server 실서버 테스트 4개 통과:

1. 한글 데이터·신규 테이블·동일 요청 재전송과 중복 적재 방지.
2. PostgreSQL↔SQL Server 왕복: 한글·이모지·후행 공백·날짜·일시·정밀도·NULL 및 집계 대조.
3. 2,501행 분할 이관: 1,000행 이후 실패 → 완료 분할 재사용 → 중복 없이 재개, IDENTITY 보존 후 다음 자동 번호 확인.
4. FK·인덱스·Unicode 기본값 복제, 중복 키 롤백, 사용자 트리거 거부, 신뢰하지 않는 TLS 인증서 거부.

다른 SQL Server 버전/에디션, 모든 DBMS 간 전체 조합, SQL Server 수백만 행 성능을 검증했다고 주장하지 않는다. SQL 파일 내보내기 방언은 기존 4종이며 SQL Server는 JDBC 조회·이관·직접 적재 지원이다.

전체 회귀 및 최종 브라우저 결과는 아래 실행 결과에 기록한다. 기본 Turbopack 빌드는 Node 하위 프로세스 종료 오류가 발생했으며 원인은 미확정이다. Webpack 프로덕션 빌드로 검증했다.

## 중단 범위

Tibero/CUBRID와 6~12단계(오프라인 실행·상태 PostgreSQL·RabbitMQ/Java 워커·Celery OCR·인증/감사·OS 설치 패키지·ETL 보강)는 이번 작업에 포함하지 않는다. 사용자 재개 요청 전에는 진행하지 않는다.

### 최종 실행 결과 (SQL Server 범위 제한 후)

- 백엔드 전체: 153개 통과, 4개 건너뜀, 실패 0. 기존 4종 DB와 SQL Server 실제 테스트를 함께 실행했다.
- SQL Server 실제 테스트: 4개 통과. 단위 테스트 2개와 구분한다.
- 브라우저: 7개 통과(드라이버 선택/관리 키 비저장 2개 + CSV/다운로드/오류/모바일/메뉴 흐름 5개). 드라이버 2개는 API mock 기반이다.
- Webpack 프로덕션 빌드 및 TypeScript 검사 통과, git diff --check 통과.
- Tibero/CUBRID 테스트 결과는 이번 지원/검증 수치에 포함하지 않았다.
