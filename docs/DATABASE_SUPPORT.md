# DBMS 지원 범위

2026-11-20. SQL 파일 생성과 서버 연결·적재는 별개 기능입니다.

| DBMS | INSERT SQL 생성 | JDBC 연결/탐색 | 실제 적재 | DB 행 추출 / DB→DB |
|---|---|---|---|---|
| Oracle | 구현 | **실서버 통과**(Free 23.26.3) | **실서버 통과** | 이전 원본·대상으로 통과 |
| PostgreSQL | 구현 | **실서버 통과**(17.12) | **실서버 통과** | 통과 |
| MySQL | 구현 | **실서버 통과**(8.4.13) | **실서버 통과** | 통과 |
| MariaDB | 구현 | **실서버 통과**(11.8.9) | **실서버 통과** | 통과 |

고정 드라이버: MySQL Connector/J **9.7.0**, PostgreSQL **42.7.14**, MariaDB **4.0.0**, Oracle ojdbc11 **23.26.4.0.0**. 네 드라이버의 실제 로딩과 URL/보안 속성, 메타데이터 호출 계약을 테스트했습니다. 2026-11-20 일회용 컨테이너(위 서버 버전)에서 실제 접속·메타데이터·적재·DB→DB를 검증했습니다. 다른 서버 버전(예: Oracle 11g/12c, MySQL 5.7)과의 호환성은 검증하지 않았으며 레거시 드라이버는 향후 과제입니다.

SQL은 기존 테이블에 실행할 INSERT 파일이며 CREATE/ALTER, 자동 적재, 타입 변환 가능성 검증을 의미하지 않습니다. 식별자는 ASCII 단일 이름 64자, PostgreSQL은 63자입니다. Oracle 문자열 4,000 UTF-8 바이트/숫자 38자리, MySQL·MariaDB 숫자 65자리 및 날짜 1000년 이상, PostgreSQL 숫자 애플리케이션 제한 1,000자리입니다. 일시 소수 초는 Oracle 9자리, 나머지 6자리이며 타임존은 지원하지 않습니다. 빈 문자열과 공백만 있는 값은 NULL입니다.

## 다음 구현 순서

1. 실제 DBMS별 연결·인증 실패·스키마/테이블/컬럼·PK 조회 통합 검증. 현재 호출 계약은 Mockito로 검증했습니다.
2. 조회 목록 검색·페이지 처리: 현재 스키마/테이블 최대 200개, 컬럼 최대 500개와 잘림 표시입니다.
3. 메타데이터 기반 매핑 후보·일치 근거·미매핑 표시. 현재 제안은 헤더 이름 규칙뿐입니다.
4. 읽기 전용 DB 추출 → 파일 출력, 이후 대상별 배치 적재·트랜잭션·롤백을 검증한 DB 이관.

기존 MySQL 적재의 로컬 접근/서버 allowlist/기본 비활성화/동의/트랜잭션 경계는 [적재 문서](database-loading.md)를 따릅니다. 새 탐색기는 로컬 요청과 서버에 명시한 호스트만 허용합니다. 임의 JDBC URL/드라이버 JAR 입력은 받지 않습니다.

## 연결 탐색 실행

백엔드를 시작하는 PowerShell에서 설정합니다. `.env`는 Docker Compose용이며 Spring Boot가 자동으로 읽지 않습니다.

```powershell
cd C:\dev\TableFlow\backend
$env:TABLEFLOW_JDBC_ENABLED='true'
$env:TABLEFLOW_JDBC_ALLOWED_HOSTS='localhost,127.0.0.1'
.\gradlew.bat bootRun
```

화면의 **데이터베이스 연결** → DBMS·호스트·포트·데이터베이스명(Oracle은 서비스명)·계정 입력 → 연결 테스트 → 스키마 조회 → 스키마·테이블 선택 순서입니다. 대상 적재 화면에서도 다른 DB의 스키마를 조회할 수 있으나 기존 MySQL 적재 설정과 연결 정보는 공유하지 않습니다. 대상 연결은 이제 공통 JDBC INSERT 적재에 사용할 수 있습니다. 소스 모드에서는 읽기 전용 행 추출을, "DB → DB 이전" 탭에서는 이전을 사용할 수 있습니다.

기본 TLS는 인증서와 호스트를 검증합니다. 원격 DB는 TLS가 필수이며, 인증기관/DB 인증서는 서버 JVM 또는 드라이버의 표준 신뢰 저장소를 사전에 설정해야 합니다. UI에서 인증서를 업로드하거나 검증을 우회하지 않습니다. 개발용 loopback DB만 TLS 해제가 가능합니다. Oracle SID/Wallet/SSH 터널/IPv6 직접 입력은 이번 범위 밖입니다. 드라이버 기본 포트는 Oracle 1521, PostgreSQL 5432, MySQL/MariaDB 3306입니다.

비밀번호는 요청마다 POST 본문으로 전달되고 연결은 try-with-resources로 닫습니다. 서버/브라우저 저장소에 저장하지 않으며 request record의 toString도 마스킹합니다. 입력 수정 시 조회 결과를 초기화하고, 연결 지우기·화면 전환 시 비밀번호를 폐기합니다. 드라이버 connect timeout 5초, socket read timeout 10초를 설정하지만 DNS/TLS/드라이버 동작을 합친 전체 작업의 절대 시간 제한을 보장하지는 않습니다. API의 동시 요청 제한은 기존 공통 제한을 사용합니다. setReadOnly는 드라이버 힌트이므로 DB 계정에도 읽기 전용 권한을 부여해야 합니다.

드라이버 공식 설정 참고: [PostgreSQL](https://jdbc.postgresql.org/documentation/use/), [MariaDB](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j), [Oracle](https://docs.oracle.com/en/database/oracle/oracle-database/21/jajdb/constant-values.html). 라이브러리는 Maven Central에서 서버 빌드 시 받으며 사용자 파일을 외부에 전송하지 않습니다. 재배포 시 각 드라이버의 라이선스/고지를 확인하세요.

## 멀티 DB 적재 추가

4종 공통 적재를 구현했고 2026-11-20 실제 서버에서 검증했습니다. [설정·영수증 SQL·제약·실제 테스트](jdbc-loading.md)
