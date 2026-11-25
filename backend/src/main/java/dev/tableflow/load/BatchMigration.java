package dev.tableflow.load;

import dev.tableflow.connection.JdbcExplorer;
import dev.tableflow.connection.JdbcExplorer.ColumnInfo;
import dev.tableflow.connection.JdbcExplorer.Credentials;
import dev.tableflow.connection.JdbcExplorer.Dbms;
import dev.tableflow.connection.JdbcReader;
import dev.tableflow.load.DatabaseLoader.LoadFailure;
import dev.tableflow.load.MigrationService.Mapping;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * 여러 테이블(또는 스키마 전체)을 한 작업으로 이관한다. 대상 테이블은 항상 원본 구조로 새로 만들고(이미 있으면 건너뛰거나 추가 적재),
 * 외래 키의 부모 테이블부터 차례로 처리하므로 FK 복제를 선택하면 부모가 먼저 만들어진 뒤 자식에 FK가 붙는다.
 * 테이블마다 분할 영수증을 쓰므로 실패하면 멈추고, 같은 요청으로 재개하면 완료된 테이블은 건너뛰고 중단된 테이블은 이어서 진행한다.
 */
@Service
public class BatchMigration {
    public static final int MAX_TABLES = 200;
    private static final Set<Integer> UNSUPPORTED = Set.of(-2, -3, -4, 2004, 2003, 2002, 2000, 1111, 2009, 2013);

    private final JdbcExplorer explorer; private final JdbcReader reader; private final MigrationService migration;
    public BatchMigration(JdbcExplorer explorer, JdbcReader reader, MigrationService migration) { this.explorer = explorer; this.reader = reader; this.migration = migration; }

    /** state: pending / running / succeeded / skipped / failed. */
    public record Item(String table, String target, String state, long totalRows, long doneRows, String message) {}
    public interface BatchProgress extends MigrationService.Progress { void items(List<Item> items); }
    /** ifExists: 대상에 같은 이름 테이블이 있을 때 skip(건너뜀, 기본) 또는 append(추가 적재). */
    public record Options(boolean preserveKeys, boolean copyIndexes, boolean copyDefaults, boolean copyForeignKeys, String ifExists, Integer chunkSize) {
        boolean append() { return "append".equals(ifExists); }
        MigrationService.Extras extras() { return new MigrationService.Extras(copyIndexes, copyDefaults, copyForeignKeys, null); }
    }
    public record PlanItem(String table, String target, long rows, int columns, List<String> excluded, boolean targetExists, String action, String level, List<String> messages) {}
    public record Plan(List<PlanItem> items, List<String> cycles, List<String> notes, long totalRows, boolean blocked) {}

    /** 대상 테이블·컬럼 이름 규칙: 영문/숫자/_만 남기고 DBMS 관례(PostgreSQL 소문자, Oracle 대문자, MySQL 계열 그대로)에 맞춘다. */
    public static String convert(Dbms dbms, String name, String prefix) {
        String clean = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("[^A-Za-z0-9_]", "_").replaceAll("^_+|_+$", "");
        if (clean.isEmpty()) clean = prefix;
        if (Character.isDigit(clean.charAt(0))) clean = prefix.charAt(0) + "_" + clean;
        if (clean.length() > 60) clean = clean.substring(0, 60);
        return dbms == Dbms.postgresql || dbms == Dbms.cubrid ? clean.toLowerCase(Locale.ROOT) : dev.tableflow.connection.SqlDialect.oracleTypes(dbms) ? clean.toUpperCase(Locale.ROOT) : clean;
    }

    private record Prepared(String table, String target, boolean exists, List<Mapping> mappings, List<String> excluded) {}

    /** 한 테이블의 자동 매핑: 이관할 수 없는 타입은 제외하고, 이름은 변환 규칙으로 정한다. 대상이 이미 있으면 실제 컬럼 이름에 대소문자 무시로 맞춘다. */
    private Prepared prepare(Credentials source, String sourceSchema, String table, Credentials target, String targetSchema, Set<String> usedTargets, Map<String, String> targetTables) {
        String wanted = convert(target.dbms(), table, "TBL");
        String actual = targetTables.get(wanted.toLowerCase(Locale.ROOT));
        String targetName = actual != null ? actual : wanted;
        if (!usedTargets.add(targetName.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("서로 다른 원본 테이블이 같은 대상 이름(" + targetName + ")이 됩니다: " + table);
        Map<String, String> existingColumns = new HashMap<>();
        if (actual != null) for (ColumnInfo c : explorer.columns(target, targetSchema, actual).items()) existingColumns.put(c.name().toLowerCase(Locale.ROOT), c.name());
        List<Mapping> mappings = new ArrayList<>(); List<String> excluded = new ArrayList<>(); Set<String> used = new HashSet<>();
        for (ColumnInfo c : explorer.columns(source, sourceSchema, table).items()) {
            if (UNSUPPORTED.contains(c.jdbcType())) { excluded.add(c.name()); continue; }
            String name = convert(target.dbms(), c.name(), "COL"), base = name; int n = 2;
            while (!used.add(name.toLowerCase(Locale.ROOT))) name = base + "_" + n++;
            if (actual != null) name = existingColumns.getOrDefault(name.toLowerCase(Locale.ROOT), name);
            mappings.add(new Mapping(c.name(), name));
        }
        if (mappings.isEmpty()) throw new IllegalArgumentException("이관할 수 있는 컬럼이 없습니다: " + table);
        return new Prepared(table, targetName, actual != null, mappings, excluded);
    }
    private Map<String, String> targetTables(Credentials target, String schema) {
        Map<String, String> map = new HashMap<>();
        for (String t : explorer.tables(target, schema).items()) map.put(t.toLowerCase(Locale.ROOT), t);
        return map;
    }
    private static void checkTables(List<String> tables) {
        if (tables == null || tables.isEmpty()) throw new IllegalArgumentException("이관할 테이블을 하나 이상 선택해주세요.");
        if (tables.size() > MAX_TABLES) throw new IllegalArgumentException("한 번에 최대 " + MAX_TABLES + "개 테이블까지 이관할 수 있습니다.");
        if (new HashSet<>(tables).size() != tables.size()) throw new IllegalArgumentException("같은 테이블이 중복 선택되었습니다.");
    }
    /** 부모가 먼저 오는 처리 순서. 서로 참조하는 테이블(순환)은 맨 뒤에 둔다. */
    private List<String> ordered(Credentials source, String schema, List<String> tables, List<String> cycles) {
        var plan = migration.order(source, schema, tables);
        cycles.addAll(plan.cycles());
        List<String> out = new ArrayList<>(plan.order().stream().map(MigrationService.OrderItem::table).toList());
        for (String t : tables) if (!out.contains(t)) out.add(t);
        return out;
    }

    // ---------------------------------------------------------------- 사전 점검
    public Plan plan(Credentials source, String sourceSchema, Credentials target, String targetSchema, List<String> tables, Options o) {
        checkTables(tables);
        List<String> cycles = new ArrayList<>(); List<String> order = ordered(source, sourceSchema, tables, cycles);
        Map<String, String> existing = targetTables(target, targetSchema);
        Set<String> used = new HashSet<>(); List<PlanItem> items = new ArrayList<>(); long total = 0; boolean blocked = false;
        for (String table : order) {
            Prepared p;
            try { p = prepare(source, sourceSchema, table, target, targetSchema, used, existing); }
            catch (RuntimeException e) { items.add(new PlanItem(table, "", 0, 0, List.of(), false, "error", "error", List.of(JobHistory.describe(e)[1]))); blocked = true; continue; }
            String action = !p.exists() ? "create" : o.append() ? "append" : "skip";
            long rows = 0; String level = "ok"; List<String> messages = new ArrayList<>();
            if (!p.excluded().isEmpty()) messages.add("이관할 수 없는 타입이라 제외: " + String.join(", ", p.excluded()));
            if (cycles.contains(table)) messages.add("서로 참조하는 테이블이라 FK를 복제하지 못할 수 있습니다. 이관 후 DB에서 FK를 추가하세요.");
            if (action.equals("skip")) { messages.add("대상에 같은 이름의 테이블이 있어 건너뜁니다."); try { rows = reader.count(source, sourceSchema, table); } catch (RuntimeException ignored) { /* 건수는 참고용 */ } }
            else try {
                var a = migration.analyze(source, sourceSchema, table, target, targetSchema, p.target(), p.mappings(), true, o.preserveKeys(), o.extras());
                rows = a.sourceRows();
                if (rows > a.maxRows()) { level = "error"; messages.add("원본이 " + rows + "행으로 서버 한도(" + a.maxRows() + "행)를 넘습니다."); }
                for (var c : a.columns()) if (c.level().equals("error")) { level = "error"; messages.add(c.source() + ": " + c.detail()); } else if (c.level().equals("warn") && !level.equals("error")) level = "warn";
                messages.addAll(a.warnings().stream().filter(w -> !messages.contains(w)).limit(5).toList());
            } catch (RuntimeException e) { level = "error"; messages.add(JobHistory.describe(e)[1]); }
            if (level.equals("error")) blocked = true;
            total += action.equals("skip") ? 0 : rows;
            items.add(new PlanItem(table, p.target(), rows, p.mappings().size(), p.excluded(), p.exists(), action, level, messages));
        }
        List<String> notes = new ArrayList<>();
        notes.add("부모 테이블(외래 키로 참조되는 테이블)부터 차례로 이관합니다." + (o.copyForeignKeys() ? " 선택한 범위 안의 부모가 먼저 만들어지므로 FK를 복제합니다." : ""));
        if (!cycles.isEmpty()) notes.add("서로 참조하는 테이블: " + String.join(", ", cycles) + " — 맨 뒤에 이관하며 이 테이블들의 FK는 복제되지 않을 수 있습니다.");
        return new Plan(items, cycles, notes, total, blocked);
    }

    /** 반복 실행(전체 교체)용: 대상에 이미 있는 이관 대상 테이블의 행을 자식 테이블부터 지운다. */
    public int purgeTargets(Credentials source, String sourceSchema, Credentials target, String targetSchema, List<String> tables) {
        checkTables(tables);
        List<String> order = ordered(source, sourceSchema, tables, new ArrayList<>());
        Map<String, String> existing = targetTables(target, targetSchema);
        List<String> names = new ArrayList<>();
        for (String table : order) { String actual = existing.get(convert(target.dbms(), table, "TBL").toLowerCase(Locale.ROOT)); if (actual != null) names.add(actual); }
        Collections.reverse(names);
        return migration.purgeTables(target, targetSchema, names);
    }

    // ---------------------------------------------------------------- 실행
    /** prior: 이전 실행의 테이블별 결과(재개). 완료된 테이블은 건너뛰고, 실패·중단된 테이블은 이미 만들어져 있어도 이어서 적재한다. */
    public MigrationService.Outcome run(String batchId, Credentials source, String sourceSchema, Credentials target, String targetSchema, List<String> tables,
                                        Options o, List<Item> prior, BatchProgress progress) {
        checkTables(tables);
        List<String> cycles = new ArrayList<>(); List<String> order = ordered(source, sourceSchema, tables, cycles);
        Map<String, String> existing = targetTables(target, targetSchema);
        Map<String, Item> before = new HashMap<>(); for (Item i : prior == null ? List.<Item>of() : prior) before.put(i.table(), i);
        Set<String> used = new HashSet<>(); List<Prepared> prepared = new ArrayList<>(); long totalRows = 0; Map<String, Long> counts = new HashMap<>();
        for (String table : order) {
            Prepared p = prepare(source, sourceSchema, table, target, targetSchema, used, existing);
            prepared.add(p); long n = reader.count(source, sourceSchema, table); counts.put(table, n); totalRows += n;
        }
        List<Item> items = new ArrayList<>();
        for (Prepared p : prepared) {
            Item b = before.get(p.table());
            items.add(b != null && (b.state().equals("succeeded") || b.state().equals("skipped")) ? b : new Item(p.table(), p.target(), "pending", counts.get(p.table()), 0, null));
        }
        progress.total(items.size(), totalRows); progress.items(items);
        long inserted = 0; int done = 0; int skipped = 0; List<String> notes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (items.get(i).state().equals("succeeded")) { inserted += items.get(i).doneRows(); done++; } else if (items.get(i).state().equals("skipped")) { done++; skipped++; }
        progress.chunk(done, inserted, 0);
        for (int i = 0; i < prepared.size(); i++) {
            Prepared p = prepared.get(i); Item cur = items.get(i);
            if (cur.state().equals("succeeded") || cur.state().equals("skipped")) continue;
            if (progress.cancelled()) throw new LoadFailure(409, "MIGRATION_CANCELLED", "취소했습니다. 완료된 테이블 " + done + "개는 유지되며, 같은 요청으로 재개하면 이어서 진행합니다.");
            boolean resumedTable = before.containsKey(p.table()) && (before.get(p.table()).state().equals("failed") || before.get(p.table()).state().equals("running"));
            if (p.exists() && !o.append() && !resumedTable) {
                items.set(i, new Item(p.table(), p.target(), "skipped", cur.totalRows(), 0, "대상에 같은 이름의 테이블이 있어 건너뛰었습니다.")); skipped++; done++;
                progress.items(items); progress.chunk(done, inserted, 0); continue;
            }
            items.set(i, new Item(p.table(), p.target(), "running", cur.totalRows(), 0, null)); progress.items(items);
            final int index = i; final long base = inserted; final int doneSoFar = done;
            MigrationService.Progress child = new MigrationService.Progress() {
                @Override public void total(int chunks, long rows) { }
                @Override public void chunk(int c, long rows, int replayed) {
                    Item now = items.get(index); items.set(index, new Item(now.table(), now.target(), "running", now.totalRows(), rows, null));
                    progress.items(items); progress.chunk(doneSoFar, base + rows, 0);
                }
                @Override public boolean cancelled() { return progress.cancelled(); }
            };
            try {
                String id = UUID.nameUUIDFromBytes((batchId + ":" + p.table()).getBytes(StandardCharsets.UTF_8)).toString();
                int chunk = o.chunkSize() == null ? migration.defaultChunkSize() : o.chunkSize();
                var out = migration.runBulk(source, sourceSchema, p.table(), target, targetSchema, p.target(), p.mappings(), Integer.MAX_VALUE, true, o.preserveKeys(), id, child, chunk, o.extras());
                items.set(i, new Item(p.table(), p.target(), "succeeded", cur.totalRows(), out.insertedRows(), out.notes().isEmpty() ? null : String.join(" ", out.notes())));
                inserted += out.insertedRows(); done++; progress.items(items); progress.chunk(done, inserted, 0);
            } catch (RuntimeException e) {
                if (e instanceof LoadFailure f && "MIGRATION_CANCELLED".equals(f.code)) { items.set(i, new Item(p.table(), p.target(), "pending", cur.totalRows(), 0, "취소됨")); progress.items(items); throw e; }
                String[] d = JobHistory.describe(e);
                items.set(i, new Item(p.table(), p.target(), "failed", cur.totalRows(), items.get(i).doneRows(), d[1])); progress.items(items);
                throw new LoadFailure(e instanceof LoadFailure f ? f.status : 422, d[0], "테이블 " + p.table() + "에서 실패했습니다. " + d[1] + " 앞선 " + done
                    + "개 테이블은 완료되었습니다. 원인을 해결한 뒤 재개하면 완료된 테이블은 건너뛰고 이 테이블부터 이어서 진행합니다.");
            }
        }
        notes.add("테이블 " + (done - skipped) + "개를 이관했습니다" + (skipped > 0 ? "(건너뜀 " + skipped + "개)" : "") + ".");
        return new MigrationService.Outcome(batchId, targetSchema, inserted, false, notes, items.size(), 0);
    }
}
