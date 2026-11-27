# EAI 1단계 상세 설계: 상태 DB

작성: 2026-11-27. 상태: **1-a 구현**, 1-b 이후 구현 전. 상위 문서: [EAI 설계 기준](EAI_ARCHITECTURE.md) §10·§13.

## 1. 목적과 범위

로컬 JSON 파일에 있는 서버 상태를 PostgreSQL로 옮긴다. 2단계(브로커·일꾼)에서 여러 프로세스가 같은 상태를 안전하게 공유할 수 있게 하는 것이 핵심이다.

**포함:** 저장 연결, JDBC 드라이버, 스케줄, 이관 작업, 작업 이력, 기존 JSON 가져오기.
**제외:** 흐름·메시지·실패 보관 테이블(2단계), 사용자·권한·감사(3단계), 일꾼 분리(2·8단계). 화면과 API 응답 형식은 바꾸지 않는다.

## 2. 결정 사항 (2026-11-27)

| 항목 | 결정 |
|---|---|
| 저장 방식 | **PostgreSQL 필수.** 파일 저장 방식은 남기지 않는다 |
| 접근 방식 | **Spring JDBC**(`JdbcClient`), 테이블 버전 관리는 **Flyway** |
| 기존 데이터 | **가져오기 필요.** DB가 비어 있을 때 한 번, 원본은 백업으로 보관 |
| 범위 | **기존 저장만** |

## 3. 지금의 저장과 바뀌는 점

| 지금 | 파일 | 코드 | 한계 | 바뀜 |
|---|---|---|---|---|
| 저장 연결(최대 100) | `connections.json` + `connections.key` | `ConnectionStore` | 전체 파일 다시 쓰기, 프로세스 하나 전제 | `connection` 표. 키는 계속 DB 밖 |
| 드라이버(최대 32) | `drivers/*.jar`, `*.json` | `DriverCatalog` | 다른 장비의 일꾼이 JAR를 못 받음 | `jdbc_driver` 표에 JAR까지 저장, 로컬은 캐시 |
| 스케줄(최대 50) | `schedules.json` | `ScheduleStore`, `ScheduleService` | JVM 잠금만 있어 여러 서버면 중복 실행 | `schedule` 표 + 행 잠금 |
| 이관 작업(최근 50) | `jobs.json` | `JobStore`, `MigrationJobs` | 시작 시 실행 중 작업을 모두 '중단'으로 간주 | `migration_job` 표 + 실행권(heartbeat) |
| 작업 이력(500~1000) | `job-history.jsonl` | `JobHistory` | 파일 전체를 읽어 정리 | `job_history` 표 |

## 4. 설정과 설치

| 설정 | 환경 변수 | 비고 |
|---|---|---|
| 접속 URL | `TABLEFLOW_STATE_DB_URL` | 기본값 없음. 없으면 시작 실패와 안내 |
| 사용자 / 비밀번호 | `TABLEFLOW_STATE_DB_USER` / `TABLEFLOW_STATE_DB_PASSWORD` | 저장소·로그에 남기지 않음 |
| 암호 키 | `TABLEFLOW_SECRET_KEY` 또는 `data/connections.key` | 지금과 같음 |

- 지원 버전(제안): PostgreSQL 16 이상. 개발·CI는 17.
- 개발용 컨테이너: 루트 `compose.yaml`의 `state-db`(루프백 15442, 비밀번호는 `.env`). 시험용은 `infra/state/compose.yml`(루프백 15443, 공개된 일회용 비밀번호)로 따로 두며, 둘 다 이관 시험 대상 DB와 분리한다.
- 계정은 전용 스키마 `tableflow`에만 권한을 가진다. 상태 DB는 **설치물의 내장 PostgreSQL만** 지원하며 기본은 루프백 접속이다. 나중에 일꾼을 다른 장비에 둘 때는 인증서를 검증하는 TLS로만 접속을 연다.
- 상태 DB 접속은 앱 전용 `DataSource` 하나다. 이관 원본·대상 접속(`JdbcExplorer`)과 섞지 않는다. URL이 없을 때 시험용 H2가 자동 선택되지 않게 막는다.
- 오프라인·전부 포함([EAI 설계 §4.3](EAI_ARCHITECTURE.md)): **PostgreSQL 서버를 TableFlow 설치물에 넣는다.** 고객이 DB를 따로 설치하지 않는다. Flyway 스크립트와 Java 라이브러리는 jar에 포함한다. 자동 내려받기는 없다.
- 설치할 때 필요한 것(설치 패키지는 9단계, 준비는 지금): 전용 데이터 폴더, 루프백 전용 포트, 계정·비밀번호 생성, 서비스 등록, 백업·복구 명령, 큰 버전 업그레이드 절차. 위 접속 설정은 설치기가 채우며, 개발 환경에서만 직접 지정한다.
- 이미 있는 사내 DB에 연결하는 선택지는 두지 않는다(2026-11-27 결정). 나중에 추가할 수 있게 접속 설정은 코드와 분리해 둔다.
- Windows와 RHEL 계열을 모두 지원한다. 1단계 코드는 두 OS에서 시험하고, 최소 설치 패키지는 2단계 직후에 만든다.

## 5. 표 설계 (초안)

모두 `tableflow` 스키마, 시각은 `timestamptz`.

| 표 | 주요 컬럼 | 제약·색인 |
|---|---|---|
| `connection` | id uuid, name, dbms, host, port, database_name, username, tls, password_enc(null 가능), driver_id(null 가능), saved_at | PK id, UNIQUE name |
| `jdbc_driver` | id uuid, dbms, version, sha256, size_bytes, jar bytea, registered_at | UNIQUE (dbms, version), UNIQUE (dbms, sha256) |
| `schedule` | id uuid, name, kind, request jsonb, mode, interval_minutes, daily_at, refresh, enabled, created_at, next_run_at, last_run_at, last_state, last_message, last_job_id, source_label, target_label, version | PK id, 색인 (enabled, next_run_at) |
| `migration_job` | id varchar(64), state, status jsonb, definition jsonb(null 가능), source_label, target_label, created_target, owner_id, heartbeat_at, created_at, updated_at | PK id, 색인 (state), (updated_at) |
| `job_history` | id uuid, kind, request_id, started_at, duration_ms, status, source, target, row_count, error_code, message | PK id, 색인 (started_at desc) |
| `app_meta` | key, value, updated_at | PK key (가져오기 완료 표시 등) |

- `request`·`definition`에는 지금처럼 **비밀번호를 넣지 않는다.** 저장 직전에 비어 있는지 검사하고 시험으로 고정한다.
- `password_enc`는 지금의 AES-256-GCM(연결 id를 추가 인증 데이터로 사용) 암호문을 그대로 쓴다. 키가 DB 밖에 있으므로 DB 백업만으로는 복호화할 수 없다. **키는 DB와 따로 백업해야 한다.**
- 작업의 테이블별 결과(`items`)는 이 단계에서 `status` 안에 둔다. 일꾼을 나누는 8단계에서 별도 표를 검토한다.
- `driver_id`는 외래 키를 걸지 않는다(가져오기 때 이미 없는 참조가 있어도 실패하지 않고 경고).
- 보관 개수(작업 50, 이력 500)는 지금 동작을 유지한다.

## 6. 동작 규칙

### 6.1 스케줄: 한 번만 실행

한 트랜잭션에서 ① 실행 시각이 된 행을 `FOR UPDATE SKIP LOCKED`로 가져오고 ② 다음 실행 시각과 새 작업 id를 기록하고 ③ 같은 id의 작업 행을 `queued`로 만든다. 커밋한 서버만 실행한다.

- 이전 회차가 진행 중이면 지금처럼 이번 회차를 건너뛰고 안내를 남긴다.
- 서버가 꺼져 있던 동안의 회차는 지금처럼 한 번만 실행하고 다음 시각을 현재 기준으로 계산한다.
- "대상 비우고 다시 적재"는 실행권을 얻은 서버만 한다.

### 6.2 이관 작업: 실행권

- 작업을 실행하는 서버는 `owner_id`(시작할 때마다 새 값)를 기록하고 10초마다 `heartbeat_at`을 갱신한다.
- **갱신이 60초 넘게 끊긴** `queued`·`running` 작업만 `SERVER_RESTARTED`(중단, 재개 가능)로 바꾼다. 살아 있는 다른 서버의 작업은 건드리지 않는다.
- 같은 id의 중복 제출은 "진행 중이 아닐 때만 갱신"하는 한 문장으로 막는다.
- 실행권을 잃은 서버는 다음 분할을 시작하지 않는다. 적재 영수증이 중복 적재를 막는 것은 지금과 같다.
- 숫자(10초·60초)는 설정으로 바꿀 수 있게 한다.

### 6.3 상태 DB에 문제가 있을 때

| 상황 | 동작 |
|---|---|
| 시작할 때 접속 불가·마이그레이션 실패 | 서버를 시작하지 않고 원인을 알림 |
| 실행 중 이력 저장 실패 | 지금처럼 이관 결과에는 영향 없음. 로그 경고 |
| 실행 중 진행 상황 저장 실패 | 이관은 계속, 다음 저장에서 반영. 60초 넘게 갱신하지 못하면 새 분할을 시작하지 않음 |
| 연결·스케줄·작업 API 호출 실패 | 503 `STATE_DB_UNAVAILABLE`, 접속 정보를 노출하지 않는 안내 |
| 상태 확인(`/api/v1/health`) | 상태 DB 연결 여부를 포함 |

## 7. 기존 JSON 가져오기

1. 시작 시 `app_meta`에 완료 표시가 없고 JSON 파일이 있으면 실행한다.
2. 연결 → 드라이버(파일 해시 확인) → 스케줄 → 작업 → 이력 순으로 **한 트랜잭션**에 넣는다.
3. 성공하면 완료 표시를 남기고 원본을 `*.imported-<날짜>`로 이름만 바꾼다. **삭제하지 않는다.**
4. 하나라도 읽지 못하면 전부 되돌리고 시작을 중단한다. 이력의 손상된 줄만 지금처럼 건너뛰고 건수를 알린다.
5. 파일에서 실행 중이던 작업은 `SERVER_RESTARTED`로 가져온다.
6. DB에 데이터가 이미 있으면 덮어쓰지 않는다.

되돌리기: 백업 파일의 이름을 원래대로 바꾸고 이전 버전을 실행한다. **전환 뒤에 DB에 쌓인 내용은 파일로 되돌려 주지 않는다.**

## 8. 코드 변경

| 대상 | 변경 |
|---|---|
| `build.gradle` | `spring-boot-starter-jdbc`, `flyway-core`, `flyway-database-postgresql` |
| 새 패키지 `dev.tableflow.state` | DataSource·마이그레이션 설정, JSON 가져오기 |
| `ConnectionStore`, `DriverCatalog`, `JobHistory` | 공개 메서드는 유지하고 내부를 SQL로 교체 |
| `JobStore`, `MigrationJobs` | 행 단위 저장, 실행권·heartbeat, 중복 제출 방지 |
| `ScheduleStore`, `ScheduleService` | "전체 목록 저장" 방식을 행 단위 + 실행권 획득으로 교체 |
| `ApiErrors`, 상태 확인 | `STATE_DB_UNAVAILABLE` |
| 화면 | 변경 없음(새 오류 문구 표시만 확인) |
| 설정 | 파일 경로 설정(`tableflow.*.file`)은 가져오기 원본 위치로만 사용. `*.enabled`는 기능 켜고 끄기로 유지 |

## 9. 시험 계획

- **실제 PostgreSQL로 시험한다.** H2 호환 모드는 행 잠금·`jsonb` 동작이 달라 쓰지 않는다.
- 결과: 지금은 DB 없이 돌던 기본 `gradlew test`도 상태 DB 컨테이너가 필요해진다. CI의 `verify`·`database` 작업에 상태 DB를 추가하고 `CLAUDE.md`·README의 실행 명령을 고친다.
- 시험 간 격리: 시험마다 별도 스키마를 만들고 끝나면 지운다(전용 컨테이너 안에서만).

| 시험 | 확인 |
|---|---|
| 저장소별 | 지금의 동작(제한 개수, 비밀번호 규칙, 접속 정보 불일치 거부, 드라이버 해시 검증) 유지 |
| 스케줄 경쟁 | 서비스 두 개가 동시에 점검해도 한 번만 실행 |
| 실행권 | 살아 있는 작업은 다른 인스턴스가 중단 처리하지 않음, 끊긴 작업만 중단·재개 |
| 가져오기 | 표본 JSON의 건수·내용, 비밀번호 복호화, 백업 파일, 실패 시 전체 되돌림, 두 번 실행해도 같음 |
| DB 중단 | §6.3의 각 상황 |
| 비밀정보 | 표와 로그에 비밀번호 원문 없음 |
| 회귀 | 백엔드 전체, 실서버 통합, Playwright 전체 |

## 10. 작업 순서

각 항목은 시험이 통과한 상태로 끝낸다.

| 순서 | 작업 | 완료 기준 |
|---|---|---|
| 1-a ✔ | 의존성, 설정, Flyway V1(`app_meta`), 상태 확인, 개발·CI 컨테이너. 표는 단계마다 새 버전으로 추가 | 빈 DB에서 시작, 설정이 없으면 명확히 실패 |
| 1-b | 작업 이력 | 이력 화면 동작 유지 |
| 1-c | 저장 연결·드라이버 | 기존 연결·드라이버 시험 통과 |
| 1-d | 이관 작업·실행권 | 재개·재시작·경쟁 시험 |
| 1-e | 스케줄 | 중복 실행 방지 시험 |
| 1-f | JSON 가져오기 | 가져오기 시험 |
| 1-g | 전체 회귀, 설치 목록(버전·해시·라이선스) 갱신, 문서·명령 갱신 | §9 통과, 외부망 차단 상태에서 빌드된 결과물이 동작, 문서 일치 |

- 1-b~1-e가 끝나기 전에는 일부 상태가 파일에 남는다. **1-f까지 끝난 뒤에만 배포(커밋 묶음 반영)** 하는 것을 권한다.

## 11. 위험과 확인할 점

- 파일 변환만 쓰는 사용자도 PostgreSQL이 필요해진다(결정에 따른 결과).
- 상태 DB가 단일 장애 지점이 된다. 이중화는 EAI 설계 §14의 열린 결정이다.
- Spring의 DataSource 자동 설정이 기존 JDBC 기능에 영향을 주지 않는지 1-a에서 확인한다.
- 확인 필요(제안값으로 진행 가능): PostgreSQL 최소 버전 16, 개발용 포트 15442, heartbeat 10초·만료 60초, 보관 개수 유지.
