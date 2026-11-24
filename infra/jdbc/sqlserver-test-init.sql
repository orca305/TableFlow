-- 일회용 루프백 테스트 컨테이너 전용. 운영 DB에서 실행하지 않는다.
IF DB_ID('tableflow') IS NULL CREATE DATABASE tableflow;
GO
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name='tableflow')
    CREATE LOGIN tableflow WITH PASSWORD='TableFlowTest2026', CHECK_POLICY=OFF;
GO
USE tableflow;
GO
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name='tableflow')
    CREATE USER tableflow FOR LOGIN tableflow;
ALTER ROLE db_owner ADD MEMBER tableflow;
GO
