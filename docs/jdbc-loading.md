# 멀티 DBMS 실제 적재

2026-11-19. Oracle/PostgreSQL/MySQL/MariaDB 공통 JDBC 적재 경로를 구현했습니다. 실제 서버 검증은 아래 환경 문제로 아직 수행하지 못했습니다.

## 사용

백엔드 실행 환경에 `TABLEFLOW_JDBC_ENABLED=true`, `TABLEFLOW_JDBC_LOAD_ENABLED=true`를 설정합니다. 기본값은 모두 false이며 기존 로컬 접근/Origin/호스트 allowlist와 TLS 검증을 공유합니다. UI의 대상 DBMS 선택·계정 입력이 실제 적재 연결에 사용됩니다. 기존 서버 등록 MySQL 적재는 접힌 별도 영역에 유지했습니다.

1. 대상 스키마에 기존 업무 테이블과 영수증 테이블을 준비합니다. `infra/jdbc/{dbms}-receipts.sql` 또는 화면의 준비 SQL 다운로드를 사용하세요. 앱이 DDL을 자동 실행하지 않습니다.
2. CSV/XLSX 등 자료를 불러와 실제 DB 컬럼명과 대소문자까지 맞추고 데이터를 검증합니다.
3. 데이터베이스 적재 → DBMS·접속 정보 → 연결 테스트 → 스키마 조회 → 대상 테이블 선택.
4. 대상·스키마 검사 후 DBMS·스키마·테이블·행 수를 확인하고 실제 INSERT에 동의합니다.
5. 선택한 DB에 적재합니다. 오류 후 재시도할 때 연결·표·대상을 바꾸지 않아야 같은 요청 ID를 유지합니다.

DDL 준비 계정과 별도로 적재 계정에 대상/영수증 SELECT·INSERT 및 메타데이터 조회·테이블 잠금 권한을 부여하세요. PostgreSQL은 스키마 USAGE도 필요합니다. 트리거 조회 권한이 부족하면 검사를 통과하지 못할 수 있습니다. DB 비밀번호는 기존과 같이 화면 메모리/요청 범위에만 존재하며 작업 이력에 저장하지 않습니다.

## 동작과 경계

- 기존 TableEngine 엄격 검증과 정규화를 서버에서 다시 수행합니다. 중복 행은 DB 적재에서 오류입니다.
- 고정 드라이버/host allowlist/로컬 요청 제한. 스키마·테이블 이름은 JDBC 메타데이터로 확인하고 DBMS별로 인용합니다. 값은 PreparedStatement로 바인딩합니다.
- 기본 테이블만 허용합니다. 문자열/정수/정밀 숫자/날짜/로컬 일시/불리언 매핑, 필수 컬럼·길이·정밀도·일시 소수 초를 검사합니다. 지원하지 않는 타입/생성·자동 증가 컬럼 매핑은 거부합니다(기존 MySQL 검사는 기존 규칙 유지).
- MySQL/MariaDB는 대상·영수증 InnoDB와 트리거 부재, strict SQL mode를 검사합니다. PostgreSQL/Oracle도 사용자 트리거가 있는 대상·영수증 테이블을 거부합니다.
- 영수증의 request_id 단일 PK를 확인하고 대상 INSERT와 같은 트랜잭션에서 영수증을 먼저 예약합니다. 250행 단위로 배치 처리합니다. 같은 ID/같은 데이터는 replay, 같은 ID/다른 데이터는 409입니다. 비밀번호는 fingerprint에 포함하지 않습니다.
- 제약조건/변환 경고/실행 오류 시 rollback합니다. 커밋 결과 불명확 시 같은 요청 ID로 재시도해야 합니다. 비트랜잭션 엔진, 외부 부작용 트리거, DB 링크·뷰·DDL·UPDATE·DELETE·이관 자동화는 범위 밖입니다.
- 타임아웃은 드라이버 socket/connect 설정, SQL/잠금 제한, 배치 실행 시간 제한으로 적용합니다. DNS/메타데이터/커밋을 합친 절대 전체 시간 상한은 보장하지 않습니다.

## 실제 검증 상태

**2026-11-20 실제 서버 통과:** PostgreSQL 17.12, MySQL 8.4.13(Connector/J 9.7.0), MariaDB 11.8.9(4.0.0), Oracle Free 23.26.3(ojdbc11). 연결·버전·잘못된 인증·메타데이터·한글/역슬래시/고정밀 숫자 저장·커밋·replay·충돌·전체 롤백과 DB→DB 이전 8개 경로를 일회용 컨테이너에서 확인했습니다([결과](TEST_RESULTS.md)). 이전 Docker 장애(디스크 부족 후 깨진 소켓 파일)는 폴더 이름 변경으로 복구했으며 이미지·볼륨은 삭제하지 않았습니다.

아래 명령으로 재실행할 수 있습니다. Oracle 이미지는 용량이 커 별도 profile이며 충분한 여유 공간이 필요합니다(이번 실행에서 디스크 사용량 변화 없음, 여유 약 14GB).

```powershell
cd C:\dev\TableFlow
docker compose -f infra/jdbc/compose.yml up -d --wait
# Oracle까지 포함하려면 위 명령 대신:
# docker compose -f infra/jdbc/compose.yml --profile oracle up -d --wait --wait-timeout 600
cd backend
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
$env:TABLEFLOW_TEST_JDBC='true'
$env:TABLEFLOW_TEST_JDBC_DBMS='postgresql,mysql,mariadb'
# Oracle 포함 시 'oracle,postgresql,mysql,mariadb'
.\gradlew.bat test --tests '*JdbcLoadIntegrationTests' --rerun-tasks
Remove-Item Env:TABLEFLOW_TEST_JDBC
Remove-Item Env:TABLEFLOW_TEST_JDBC_DBMS
```

통합 테스트는 전용 테스트 DB의 TF_LOAD_TEST와 tableflow_load_receipts를 삭제·재생성합니다. 운영/개인 DB로 포트를 바꾸어 실행하지 마세요. 고정된 비민감 fixture 비밀번호는 이 테스트 전용 컨테이너에만 사용합니다. 테스트는 실제 연결/버전/잘못된 인증/메타데이터/한글·역슬래시·고정밀 숫자 저장/커밋/replay/충돌/300번째 행 실패 시 앞선 250행까지 rollback/동시 동일 요청을 확인하도록 구성했습니다. SQL 준비와 compose 자체도 실제 DB가 시작된 뒤 최종 확인이 필요합니다.
