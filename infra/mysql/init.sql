CREATE TABLE TB_EMPLOYEE (
    EMP_NO VARCHAR(20) PRIMARY KEY,
    EMP_NM VARCHAR(100) NOT NULL,
    HIRE_DT DATE NOT NULL,
    SALARY DECIMAL(20,2) NOT NULL,
    CONSTRAINT positive_salary CHECK (SALARY >= 0)
) ENGINE=InnoDB;

-- This receipt and the target INSERTs commit together. No source data is stored here.
CREATE TABLE tableflow_load_receipts (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    payload_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    inserted_rows INT NOT NULL,
    committed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
