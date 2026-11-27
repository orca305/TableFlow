# MySQL 실제 적재

파일 추출 → 변환·검증 → 대상 스키마 검사 → 사용자 확인 → JDBC 배치 INSERT → COMMIT까지 구현했습니다. 현재 실제 적재는 **MySQL 8.4 / 로컬 워크스페이스**를 지원합니다. Oracle은 SQL 파일 생성만 지원합니다.

## 로컬 실행

1. Docker Desktop을 실행합니다. `.env.example`을 `.env`로 복사하고 두 비밀번호를 각각 다른 임의 값으로 바꿉니다. `.env`는 Git에서 제외됩니다. 이미 초기화한 Docker 볼륨의 비밀번호는 파일 수정만으로 바뀌지 않습니다.
2. 저장소 루트에서 `docker compose up -d --wait mysql`를 실행합니다. 기존 프로젝트와 분리된 MySQL이 `127.0.0.1:3307`에서 실행됩니다. 최초 볼륨 생성 시 `infra/mysql/init.sql`로 샘플 대상과 영수증 테이블을 준비합니다.
3. 다음 PowerShell에서 백엔드를 실행합니다.

```powershell
cd C:\dev\TableFlow
. .\scripts\enable-local-db.ps1
.\scripts\backend.ps1
```

4. 프런트엔드를 실행하고 `http://localhost:3100`을 엽니다. 샘플 CSV를 업로드합니다. 컬럼을 사원번호→EMP_NO(문자열), 이름→EMP_NM(문자열), 입사일→HIRE_DT(날짜), 급여→SALARY(소수)로 매핑하고 검증합니다.
5. **MySQL DB 적재 → 적재 대상 확인 → 연결·스키마 검사 → 실제 적재 동의 → MySQL에 적재** 순으로 실행합니다. 성공한 행은 Docker 볼륨에 유지됩니다. 같은 사원번호를 새 요청으로 다시 넣으면 기본키 오류로 전체 롤백됩니다.

`docker compose stop`은 데이터를 보존합니다. 이 안내는 기존 DB/볼륨 삭제나 초기화를 수행하지 않습니다. `enable-local-db.ps1`은 현재 PowerShell 프로세스에만 설정을 넣으며 전역 환경을 바꾸지 않습니다.

## 서버 설정

| 변수 | 기본 / 의미 |
| --- | --- |
| TABLEFLOW_LOAD_ENABLED | false. 명시적으로 활성화해야 연결 |
| TABLEFLOW_LOAD_URL | `jdbc:mysql://host:port/database` 형식. URL 옵션/사용자 임의 URL은 허용하지 않음 |
| TABLEFLOW_LOAD_USER / PASSWORD | 서버 환경에서만 설정. 브라우저에 반환하거나 저장하지 않음 |
| TABLEFLOW_LOAD_TABLES | 쉼표로 구분한 허용 테이블명. 테이블 생성·임의 SQL 입력 없음 |
| TABLEFLOW_LOAD_SSL_MODE | VERIFY_IDENTITY. VERIFY_CA/REQUIRED도 지원. 로컬 Compose는 자체 인증서라 REQUIRED 사용 |

연결 계정에는 대상 및 `tableflow_load_receipts`의 SELECT/INSERT 권한이 필요합니다. 대상은 트리거가 없는 InnoDB 기본 테이블이어야 합니다. 운영자는 트리거가 없음을 확인해야 합니다(제한된 계정에서는 information_schema의 트리거 가시성이 제한될 수 있음). 샘플 Compose 계정은 테스트 DDL 정리를 위해 개발 전용 스키마 권한을 가집니다. 실제 환경은 별도 최소 권한 계정을 사용하세요.

DB 적재 API는 실제 원격 주소가 loopback이고 Origin이 지정 프런트엔드와 일치할 때만 허용합니다. X-Forwarded-For로 허용하지 않습니다. 인증 기능을 대신하는 공개 서비스 기능이 아닙니다. **공개 배포에서는 비활성화하고 `/api/v1/loads/**`를 리버스 프록시로 노출하지 마세요.** 인증/권한 없는 원격 적재는 이번 범위가 아닙니다.

## 무결성과 오류 처리

- 서버에서 표·식별자를 다시 검증하고 DB의 실제 컬럼/자료형/필수값/문자열 길이/소수 정밀도/일시 정밀도를 검사합니다.
- 문자열은 PreparedStatement, 숫자는 BigDecimal, 날짜/일시는 JDBC 타입으로 바인딩합니다. 부동소수점 변환을 하지 않으며 FLOAT/DOUBLE로 정밀도를 잃는 매핑은 허용하지 않습니다.
- 250행씩 JDBC 배치를 실행하되 전체 요청을 **단일 트랜잭션**으로 묶습니다. 테이블 생성·TRUNCATE·UPDATE·DELETE를 하지 않습니다.
- 대상 메타데이터 잠금을 트랜잭션 종료까지 유지하여 검사 후 테이블 엔진/스키마 변경을 막습니다. MyISAM/뷰/확인 가능한 트리거를 거부합니다.
- STRICT 모드, 값 정밀도 검사, JDBC 경고 거부로 조용한 잘림/반올림을 방지합니다. SQL 실패 시 이전 배치까지 롤백합니다.
- 접속/소켓/쿼리/잠금/배치 실행 시간에 제한이 있습니다. 기존 바깥 API 요청 제한도 적용됩니다.
- OCR 값은 별도의 원본 검토 헤더가 있어야 실제 적재 가능합니다.

## 요청 중복 방지

각 적재는 UUID 요청 ID를 사용합니다. 정규화한 포함 컬럼/값/대상 테이블의 SHA-256과 결과 행 수를 `tableflow_load_receipts`에 대상 INSERT와 **같은 트랜잭션**으로 저장합니다. 원본 데이터는 이 테이블에 저장하지 않습니다.

같은 ID/같은 내용이면 기존 결과를 반환하고 다시 INSERT하지 않습니다. 같은 ID/다른 내용은 409입니다. 동시 재시도도 한 건만 커밋합니다. 실패해 롤백된 요청은 영수증도 남지 않습니다. 영수증을 삭제하면 해당 ID의 중복 방지 보장이 사라집니다.

커밋 직후 연결이 끊기면 성공/실패를 단정하지 않고 결과 미확인을 반환합니다. **화면의 같은 요청 ID로 재시도**하면 영수증으로 결과를 확인합니다. 새로고침은 작업과 요청 ID를 잃으므로 결과 미확인 시 새 요청을 만들지 말고 먼저 DB와 영수증을 확인하세요. 이것은 요청 재전송 방지이며 서로 다른 요청 ID의 업무 중복을 제거하는 기능은 아닙니다. 업무 키의 UNIQUE/PRIMARY KEY도 준비해야 합니다.

## API

- `GET /api/v1/loads/target` → `{enabled,dbms:"mysql",tables}`. 비밀/호스트는 반환하지 않음
- `POST /api/v1/loads/check` → `{table,tableName}`, 응답 `{tableName,rows,columns}`. 쓰기 없음
- `POST /api/v1/loads/database` → `{table,tableName,requestId,confirmed:true}`, OCR은 `X-TableFlow-Reviewed:true`
- 성공 → `{requestId,tableName,insertedRows,replayed}`. 클라이언트는 네트워크 오류에 자동으로 새 요청 ID를 생성하지 않음
- 403 로컬 접근 제한, 409 요청 내용 충돌, 422 매핑/제약조건 오류, 503 비활성/연결/타임아웃/결과 미확인. DB 비밀번호/내부 SQL 오류 본문은 응답과 로그에 노출하지 않음

## 실제 검증

MySQL 8.4.13 Docker를 별도로 실행하여 검증했습니다. Java 통합 테스트는 임의 이름의 테스트 테이블만 만들고 종료 시 해당 테이블과 영수증만 정리합니다. 실제 DB 통합은 opt-in이며 H2/mock으로 대체하지 않습니다.

```powershell
cd C:\dev\TableFlow
docker compose up -d --wait mysql
. .\scripts\enable-local-db.ps1
$env:TABLEFLOW_MYSQL_PASSWORD=$env:TABLEFLOW_LOAD_PASSWORD
$env:TABLEFLOW_TEST_MYSQL='true'
$env:JAVA_HOME='C:\Users\JBWOO\.jdks\openjdk-21.0.2'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\backend\gradlew.bat -p backend test bootJar
cd frontend
$env:TABLEFLOW_TEST_LOCAL_OCR='true'
npm run test:e2e
```

검증 항목: 문자열 주입값/한글·고정밀 숫자·DATE·DATETIME(6)·불리언 보존, 재시도/충돌/동시 요청, MyISAM/잘못된 매핑 거부, **300행 중 마지막 CHECK 실패 후 0행·0영수증**, 원격/다른 Origin 차단, 브라우저에서 실제 적재 후 SQL로 내용 확인, 실패 시 DB 0행 확인. 브라우저 테스트는 임의 E2E 키의 행과 영수증만 정리합니다.

관련 근거: [MySQL 트랜잭션](https://dev.mysql.com/doc/refman/8.4/en/commit.html), [비트랜잭션 테이블 롤백 제한](https://dev.mysql.com/doc/refman/8.4/en/nontransactional-tables.html).
