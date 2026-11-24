package dev.tableflow.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.connection.JdbcExplorer.*;
import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.transform.TableEngine;
import dev.tableflow.export.TableExporter.InvalidTable;
import dev.tableflow.load.DatabaseLoader.LoadFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

@Service
public class JdbcLoader {
    private static final String RECEIPTS="tableflow_load_receipts";
    private final JdbcExplorer explorer; private final TableEngine engine; private final DatabaseLoader mysql;
    private final ObjectMapper json; private final boolean enabled;
    public JdbcLoader(JdbcExplorer explorer, TableEngine engine, DatabaseLoader mysql, ObjectMapper json,
        @Value("${tableflow.jdbc.load-enabled:false}") boolean enabled) {
        this.explorer=explorer; this.engine=engine; this.mysql=mysql; this.json=json; this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    public record Plan(String dbms,String schema,String tableName,int rows,List<String> columns) {}
    private Table validate(Table table,String schema,String name) {
        if (!enabled) throw new LoadFailure(503,"JDBC_LOAD_DISABLED","DB 적재를 활성화하려면 TABLEFLOW_JDBC_LOAD_ENABLED=true로 백엔드를 실행해주세요.");
        for(String id:List.of(schema,name)) if(id.isBlank() || id.length()>128 || id.chars().anyMatch(c->c<32)) throw new IllegalArgumentException("대상 스키마와 테이블을 선택해주세요.");
        if(name.equalsIgnoreCase(RECEIPTS)) throw new IllegalArgumentException("영수증 테이블에는 사용자 데이터를 적재할 수 없습니다.");
        Table normalized=engine.transform(table);
        var result=engine.validate(normalized); if(!result.valid()) throw new InvalidTable(result);
        if(table.rows().isEmpty()) throw new IllegalArgumentException("적재할 데이터가 없습니다.");
        return normalized;
    }
    public Plan check(Credentials c,Table table,String schema,String name) { return check(c,table,schema,name,Set.of()); }
    /** keepIdentity: 원본 값을 그대로 넣을 자동 증가(IDENTITY/serial) 대상 컬럼 이름. 비어 있으면 자동 증가 컬럼 매핑을 거부한다. */
    public Plan check(Credentials c,Table table,String schema,String name,Set<String> keepIdentity) {
        Table output=validate(table,schema,name);
        return explorer.withConnection(c,true,connection->{
            try { inspect(connection,c.dbms(),schema,name,output,keepIdentity); }
            catch(SQLException e) { throw rejected(e); }
            return new Plan(c.dbms().name(),schema,name,output.rows().size(),included(output).stream().map(Column::targetName).toList());
        });
    }
    public DatabaseLoader.Result load(Credentials c,Table table,String schema,String name,String requestId) { return load(c,table,schema,name,requestId,Set.of()); }
    public DatabaseLoader.Result load(Credentials c,Table table,String schema,String name,String requestId,Set<String> keepIdentity) {
        Table output=validate(table,schema,name); String hash=fingerprint(c,output,schema,name);
        return explorer.withConnection(c,false,connection->{
            if(family(c.dbms())) connection.setCatalog(schema);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false); boolean committing=false;
            try {
                var previous=receipt(connection,c.dbms(),schema,requestId,hash);
                if(previous!=null) { connection.rollback(); return previous; }
                settings(connection,c.dbms(),schema,name);
                inspect(connection,c.dbms(),schema,name,output,keepIdentity);
                // Reserve the id inside the same transaction; concurrent retries serialize on its primary key.
                try(var st=connection.prepareStatement("INSERT INTO "+qualified(c.dbms(),schema,RECEIPTS)+" (request_id,payload_hash,table_name,inserted_rows) VALUES (?,?,?,?)")) {
                    st.setQueryTimeout(20); st.setString(1,requestId);st.setString(2,hash);st.setString(3,name);st.setInt(4,output.rows().size());st.executeUpdate();
                }
                var columns=included(output);
                if (c.dbms() == Dbms.sqlserver && !keepIdentity.isEmpty()) identityInsert(connection, c.dbms(), schema, name, true);
                String names=String.join(",",columns.stream().map(col->quote(c.dbms(),col.targetName())).toList());
                String override=c.dbms()==Dbms.postgresql && !keepIdentity.isEmpty() ? " OVERRIDING SYSTEM VALUE" : ""; // GENERATED ALWAYS identity도 원본 값 허용
                String sql="INSERT INTO "+qualified(c.dbms(),schema,name)+" ("+names+")"+override+" VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
                try(var st=connection.prepareStatement(sql)) {
                    st.setQueryTimeout(20); int batch=0; long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
                    for(var row:output.rows()) {
                        for(int i=0;i<columns.size();i++) {var col=columns.get(i);bind(st,i+1,row.cells().get(col.id()).value(),col.dataType(),c.dbms());}
                        st.addBatch(); if(++batch%250==0) execute(st,deadline);
                    }
                    if(batch%250!=0) execute(st,deadline);
                }
                if (c.dbms() == Dbms.sqlserver && !keepIdentity.isEmpty()) identityInsert(connection, c.dbms(), schema, name, false);
                committing=true; connection.commit();
                return new DatabaseLoader.Result(requestId,name,output.rows().size(),false);
            } catch(SQLException e) {
                try {connection.rollback();} catch(SQLException ignored) {}
                if(committing) throw new LoadFailure(503,"LOAD_OUTCOME_UNKNOWN","커밋 결과를 확인하지 못했습니다. 같은 요청 ID로 재시도해주세요.");
                if((e.getSQLState()!=null && e.getSQLState().startsWith("23")) || e.getErrorCode()==1) {
                    var previous=receipt(connection,c.dbms(),schema,requestId,hash); if(previous!=null) return previous;
                }
                throw rejected(e);
            } catch(RuntimeException e) {try{connection.rollback();}catch(SQLException ignored){} throw e;}
        });
    }
    private void settings(Connection c,Dbms db,String schema,String table) throws SQLException {
        try(var st=c.createStatement()) {
            st.setQueryTimeout(20);
            if (db == Dbms.sqlserver) {
                st.execute("SET XACT_ABORT ON"); st.execute("SET LOCK_TIMEOUT 10000");
                for (String target : List.of(table, RECEIPTS))
                    try (var rs = st.executeQuery("SELECT TOP (1) 1 FROM " + qualified(db, schema, target) + " WITH (TABLOCKX, HOLDLOCK)")) { while (rs.next()) {} }
            } else if(family(db)) {
                st.execute("SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'");
                st.execute("SET SESSION innodb_lock_wait_timeout=10"); st.execute("SET SESSION lock_wait_timeout=10");
                for(String target:List.of(table,RECEIPTS)) try(var rs=st.executeQuery("SELECT 1 FROM "+qualified(db,schema,target)+" LIMIT 0")){}
            } else {
                if(db==Dbms.postgresql) {st.execute("SET LOCAL statement_timeout='20s'");st.execute("SET LOCAL lock_timeout='10s'");}
                for(String target:List.of(table,RECEIPTS)) st.execute("LOCK TABLE "+qualified(db,schema,target)+ ((db==Dbms.oracle) ? " IN ROW EXCLUSIVE MODE WAIT 10" : " IN ROW EXCLUSIVE MODE"));
            }
        }
    }
    private void inspect(Connection c,Dbms db,String schema,String name,Table table,Set<String> keepIdentity) throws SQLException {
        if(family(db)) {
            c.setCatalog(schema); Set<String> keys=new HashSet<>();
            try(var rs=c.getMetaData().getPrimaryKeys(schema,null,RECEIPTS)){while(rs.next())keys.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}
            if(!keys.equals(Set.of("request_id")))throw schema("영수증 테이블의 request_id 단일 기본 키를 확인해주세요.");
            mysql.inspect(c,name,included(table),table);return;
        }
        var meta=c.getMetaData(); String escape=meta.getSearchStringEscape();
        for(String target:List.of(name,RECEIPTS)) {
            try(var rows=meta.getTables(null,JdbcExplorer.literalPattern(meta,schema),JdbcExplorer.literalPattern(meta,target),new String[]{"TABLE"})) {
                if(!rows.next()) throw schema("대상 테이블과 tableflow_load_receipts 테이블을 먼저 준비해주세요.");
            }
        }
        if (db == Dbms.sqlserver) {
            try (var st = c.prepareStatement("SELECT HAS_PERMS_BY_NAME(?, 'SCHEMA', 'VIEW DEFINITION')")) {
                st.setQueryTimeout(20); st.setString(1, schema);
                try (var rs = st.executeQuery()) { if (!rs.next() || rs.getInt(1) != 1) throw schema("SQL Server 대상 스키마의 VIEW DEFINITION 권한이 필요합니다. 트리거 여부를 확인해야 합니다."); }
            }
        }
        String triggerSql=db==Dbms.sqlserver ? "SELECT COUNT(*) FROM sys.triggers t JOIN sys.tables c ON c.object_id=t.parent_id JOIN sys.schemas s ON s.schema_id=c.schema_id WHERE s.name=? AND c.name IN (?,?) AND t.is_disabled=0"
            : (db==Dbms.oracle) ? "SELECT COUNT(*) FROM ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME IN (?,?) AND STATUS='ENABLED'"
            : "SELECT COUNT(*) FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname IN (?,?) AND NOT t.tgisinternal";
        try(var st=c.prepareStatement(triggerSql)) { st.setQueryTimeout(20);st.setString(1,schema);st.setString(2,name);st.setString(3,RECEIPTS);try(var rs=st.executeQuery()){rs.next();if(rs.getInt(1)>0)throw schema("사용자 트리거가 있는 대상·영수증 테이블은 지원하지 않습니다.");}}
        Set<String> pk=new HashSet<>();try(var rs=meta.getPrimaryKeys(null,schema,RECEIPTS)){while(rs.next())pk.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}
        if(!pk.equals(Set.of("request_id")))throw schema("영수증 테이블의 request_id 단일 기본 키를 확인해주세요.");
        Map<String,Column> mapped=new HashMap<>();included(table).forEach(col->mapped.put(col.targetName(),col)); Set<String> found=new HashSet<>();
        try(var rs=meta.getColumns(null,JdbcExplorer.literalPattern(meta,schema),JdbcExplorer.literalPattern(meta,name),"%")) {
            while(rs.next()) {
                // Oracle의 COLUMN_DEF는 LONG이라 ResultSet 컬럼 순서대로 읽어야 한다(순서가 바뀌면 ORA-17027).
                String target=rs.getString("COLUMN_NAME");Column col=mapped.get(target);
                int type=rs.getInt("DATA_TYPE"),size=rs.getInt("COLUMN_SIZE"),scale=rs.getInt("DECIMAL_DIGITS");
                boolean required=rs.getInt("NULLABLE")==DatabaseMetaData.columnNoNulls;
                String columnDefault=rs.getString("COLUMN_DEF");
                boolean identity="YES".equals(rs.getString("IS_AUTOINCREMENT"));boolean generated="YES".equals(rs.getString("IS_GENERATEDCOLUMN"));
                if(col==null){if(required && columnDefault==null && !generated && !identity)throw schema("필수 대상 컬럼 매핑이 누락되었습니다: "+target);continue;}
                found.add(target);
                if(generated || (identity && !keepIdentity.contains(target)) || !compatible(col.dataType(),type,db))throw schema("대상 컬럼 자료형·생성 컬럼 여부를 확인해주세요: "+target);
                for(var row:table.rows()) {
                    String value=row.cells().get(col.id()).value();
                    if(value==null){if(required)throw schema("대상 DB 필수값이 비어 있습니다: "+target);continue;}
                    if(col.dataType()==DataType.string && size>0 && (db == Dbms.sqlserver ? value.length() : value.codePointCount(0,value.length()))>size)throw schema("대상 DB 문자열 길이를 초과했습니다: "+target);
                    if(col.dataType()==DataType.string && db==Dbms.postgresql && value.indexOf(0)>=0)throw schema("PostgreSQL 문자열에는 NUL 문자를 저장할 수 없습니다.");
                    if((col.dataType()==DataType.integer || col.dataType()==DataType.decimal) && (type==Types.NUMERIC || type==Types.DECIMAL) && size>0 && scale>=0) {
                        var n=new BigDecimal(value).stripTrailingZeros();int integral=n.signum()==0?0:Math.max(0,n.precision()-n.scale());
                        if(Math.max(0,n.scale())>scale || integral>size-scale)throw schema("대상 숫자 정밀도를 초과했습니다: "+target);
                    }
                    if(col.dataType()==DataType.datetime && java.time.LocalDateTime.parse(value).getNano()%(long)Math.pow(10,9-Math.min(9,Math.max(0,scale)))!=0)throw schema("대상 일시 정밀도를 초과했습니다: "+target);
                }
            }
        }
        if(found.size()!=mapped.size())throw schema("대상 DB에 없는 컬럼입니다. 대소문자까지 정확히 매핑해주세요.");
    }
    private boolean compatible(DataType source,int target,Dbms db) {
        return switch(source) {
            case string -> Set.of(Types.CHAR,Types.VARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGVARCHAR,Types.LONGNVARCHAR).contains(target);
            case integer -> Set.of(Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL).contains(target);
            case decimal -> target==Types.NUMERIC || target==Types.DECIMAL;
            case date -> target==Types.DATE || ((db==Dbms.oracle) && target==Types.TIMESTAMP);
            case datetime -> target==Types.TIMESTAMP;
            case bool -> target==Types.BOOLEAN || target==Types.BIT || ((db==Dbms.oracle) && (target==Types.NUMERIC || target==Types.DECIMAL));
        };
    }
    private void identityInsert(Connection connection, Dbms db, String schema, String name, boolean enabled) throws SQLException {
        try (var st = connection.createStatement()) {
            st.setQueryTimeout(20); st.execute("SET IDENTITY_INSERT " + qualified(db, schema, name) + (enabled ? " ON" : " OFF"));
        }
    }
    private DatabaseLoader.Result receipt(Connection c,Dbms db,String schema,String id,String hash)throws SQLException {
        try(var st=c.prepareStatement("SELECT payload_hash,table_name,inserted_rows FROM "+qualified(db,schema,RECEIPTS)+" WHERE request_id=?")) {
            st.setQueryTimeout(20);st.setString(1,id);try(var rs=st.executeQuery()){
                if(!rs.next())return null;if(!hash.equals(rs.getString(1)))throw new LoadFailure(409,"LOAD_REQUEST_CONFLICT","같은 요청 ID에 다른 데이터 또는 대상이 전달되었습니다.");
                return new DatabaseLoader.Result(id,rs.getString(2),rs.getInt(3),true);
            }
        }
    }
    private void bind(PreparedStatement st,int i,String value,DataType type,Dbms db)throws SQLException {
        if(value==null){st.setNull(i,switch(type){case string->Types.VARCHAR;case integer,decimal->Types.NUMERIC;case date->Types.DATE;case datetime->Types.TIMESTAMP;case bool->(db==Dbms.oracle)?Types.NUMERIC:Types.BOOLEAN;});return;}
        switch(type){case string->st.setString(i,value);case integer,decimal->st.setBigDecimal(i,new BigDecimal(value));case date->st.setDate(i,java.sql.Date.valueOf(value));case datetime->st.setTimestamp(i,Timestamp.valueOf(java.time.LocalDateTime.parse(value)));case bool->{if((db==Dbms.oracle))st.setInt(i,value.equals("true")?1:0);else st.setBoolean(i,value.equals("true"));}}
    }
    private void execute(PreparedStatement st,long deadline)throws SQLException {
        long remaining=deadline-System.nanoTime();if(remaining<=0)throw new LoadFailure(503,"LOAD_TIMEOUT","제한 시간을 초과하여 전체 적재를 취소했습니다.");
        st.setQueryTimeout((int)Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(remaining)));st.executeBatch();
        if(st.getWarnings()!=null)throw new SQLException("conversion warning","22000");st.clearBatch();
    }
    private String fingerprint(Credentials c,Table table,String schema,String name) {
        try {
            var columns=included(table);var values=table.rows().stream().map(row->columns.stream().map(col->row.cells().get(col.id()).value()).toList()).toList();
            var mapping=columns.stream().map(col->List.of(col.targetName(),col.dataType().name())).toList();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of(c.dbms(),c.host(),c.port(),c.database(),c.username(),schema,name,mapping,values))));
        }catch(Exception e){throw new IllegalStateException("Unable to fingerprint load");}
    }
    private List<Column> included(Table t){return t.columns().stream().filter(Column::included).toList();}
    private static boolean family(Dbms db){return dev.tableflow.connection.SqlDialect.catalogBased(db);}
    private static String quote(Dbms db,String name){return dev.tableflow.connection.SqlDialect.quote(db,name);}
    private static String qualified(Dbms db,String schema,String name){return quote(db,schema)+"."+quote(db,name);}
    private static String pattern(String name,String escape){return name.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");}
    private LoadFailure schema(String message){return new LoadFailure(422,"LOAD_SCHEMA",message);}
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(JdbcLoader.class);
    private LoadFailure rejected(SQLException e){
        String state=Optional.ofNullable(e.getSQLState()).orElse("");
        log.warn("적재 거부/실패 sqlState={} vendorCode={} type={}",state,e.getErrorCode(),e.getClass().getSimpleName()); // 메시지에는 데이터 값이 섞일 수 있어 남기지 않는다
        if(state.startsWith("23") || state.startsWith("22") || Set.of(1,1400,1438,12899,2290,3819,4025).contains(e.getErrorCode()))return new LoadFailure(422,"LOAD_REJECTED","DB 제약조건 또는 값 변환에 실패해 전체 적재를 취소했습니다.");
        if(state.startsWith("42") || e.getErrorCode()==942)return schema("대상 테이블·영수증 테이블과 INSERT/SELECT 권한을 확인해주세요.");
        return new LoadFailure(503,"LOAD_FAILED","적재에 실패해 전체 작업을 취소했습니다. 연결·권한·테이블 설정을 확인해주세요.");
    }
}
