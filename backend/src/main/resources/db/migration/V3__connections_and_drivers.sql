-- 저장한 DB 연결. password_enc는 AES-256-GCM 암호문(연결 id를 추가 인증 데이터로 사용)이며 키는 DB 밖에 있다.
CREATE TABLE connection (
    id            uuid         PRIMARY KEY,
    name          varchar(60)  NOT NULL UNIQUE,
    dbms          varchar(20)  NOT NULL,
    host          varchar(253) NOT NULL,
    port          integer      NOT NULL,
    database_name varchar(128) NOT NULL,
    username      varchar(128) NOT NULL,
    tls           boolean      NOT NULL,
    password_enc  text,
    driver_id     uuid,        -- 외래 키를 걸지 않는다(가져올 때 없는 참조가 있어도 연결 정보는 남긴다)
    saved_at      timestamptz  NOT NULL
);

-- 관리자가 반입한 JDBC 드라이버. 원본은 이 표이고 각 서버의 드라이버 폴더는 사본(캐시)이다.
CREATE TABLE jdbc_driver (
    id            uuid         PRIMARY KEY,
    dbms          varchar(20)  NOT NULL,
    version       varchar(64)  NOT NULL,
    sha256        char(64)     NOT NULL,
    size_bytes    bigint       NOT NULL,
    jar           bytea        NOT NULL,
    registered_at timestamptz  NOT NULL,
    UNIQUE (dbms, version),
    UNIQUE (dbms, sha256)
);
