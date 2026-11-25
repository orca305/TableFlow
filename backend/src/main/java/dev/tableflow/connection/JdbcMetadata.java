package dev.tableflow.connection;

import dev.tableflow.connection.JdbcExplorer.Dbms;
import java.sql.*;
import java.util.*;

/** JDBC 표준 메타데이터에서 빠진 CUBRID 자동 증가 정보를 읽는다. */
public final class JdbcMetadata {
    private JdbcMetadata() {}
    public static Set<String> autoColumns(Connection c, Dbms db, String schema, String table) throws SQLException {
        if (db != Dbms.cubrid) return Set.of();
        Set<String> columns = new HashSet<>();
        try (var st = c.createStatement()) {
            st.setQueryTimeout(20);
            try (var rows = st.executeQuery("SHOW COLUMNS FROM " + SqlDialect.quote(db, schema) + "." + SqlDialect.quote(db, table))) {
                while (rows.next()) if (Optional.ofNullable(rows.getString("Extra")).orElse("").contains("auto_increment")) columns.add(rows.getString("Field"));
            }
        }
        return columns;
    }
}
