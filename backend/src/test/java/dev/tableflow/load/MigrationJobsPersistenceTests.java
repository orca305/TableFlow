package dev.tableflow.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 작업 목록이 서버를 다시 시작해도 남고, 실행 중이던 작업은 '중단됨'으로 복원되며, 재개 정보에 비밀번호가 없는지 확인한다. */
class MigrationJobsPersistenceTests {
    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper();

    JobStore store() { return new JobStore(true, dir.resolve("jobs.json").toString(), json); }
    ObjectNode definition(String sourceSavedId) {
        ObjectNode def = json.createObjectNode();
        var src = def.putObject("source"); src.put("host", "localhost"); src.put("password", "");
        if (sourceSavedId != null) src.put("savedId", sourceSavedId); else src.putNull("savedId");
        var tgt = def.putObject("target"); tgt.put("host", "localhost"); tgt.put("password", ""); tgt.put("savedId", "123e4567-e89b-12d3-a456-426614174000");
        return def;
    }
    MigrationJobs.Status running(String id) { return new MigrationJobs.Status(id, "running", 12500, 5000, 3, 1, 0, null, null, "2026-11-21T00:00:00Z", null, List.of()); }

    @Test void runningJobBecomesResumableInterruptedAfterRestart() {
        var store = store();
        store.put(new JobStore.Stored("job-1", running("job-1"), definition(null), "Oracle · HR.EMP", "PostgreSQL · public.emp"));
        var restarted = new MigrationJobs(store); restarted.restore();       // 서버 재시작
        var info = restarted.list().get(0);
        assertEquals("failed", info.status().state()); assertEquals("SERVER_RESTARTED", info.status().errorCode());
        assertTrue(info.resumable()); assertEquals("Oracle · HR.EMP", info.source());
        assertTrue(info.sourceNeedsPassword());   // 원본은 저장된 비밀번호가 없어 재개할 때 입력해야 한다
        assertFalse(info.targetNeedsPassword());  // 대상은 저장된 연결(savedId)로 비밀번호를 쓴다
        assertEquals(5000, info.status().doneRows());
        assertNotNull(restarted.definition("job-1"));
        var again = new MigrationJobs(store); again.restore();
        assertEquals("failed", again.get("job-1").state(), "다시 열어도 변경된 상태가 유지된다");
    }

    @Test void finishedAndFailedJobsSurviveRestartWithoutPasswords() throws Exception {
        var store = store(); var jobs = new MigrationJobs(store);
        jobs.submit("ok-1", definition("123e4567-e89b-12d3-a456-426614174001"), "A", "B", progress -> { progress.total(1, 10); progress.chunk(1, 10, 0); return new MigrationService.Outcome("ok-1", "t", 10, false, List.of(), 1, 0); });
        jobs.submit("bad-1", definition(null), "C", "D", progress -> { throw new IllegalArgumentException("테스트 실패"); });
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && jobs.list().stream().anyMatch(i -> i.status().state().equals("queued") || i.status().state().equals("running"))) Thread.sleep(20);
        var after = new MigrationJobs(store); after.restore();
        var byId = new HashMap<String, MigrationJobs.Info>(); after.list().forEach(i -> byId.put(i.status().id(), i));
        assertEquals("succeeded", byId.get("ok-1").status().state()); assertFalse(byId.get("ok-1").resumable());
        assertEquals("failed", byId.get("bad-1").status().state()); assertTrue(byId.get("bad-1").resumable());
        assertEquals("INVALID_INPUT", byId.get("bad-1").status().errorCode());
        assertFalse(Files.readString(dir.resolve("jobs.json")).toLowerCase().contains("\"password\" : \"s"), "저장 파일에 비밀번호가 없다");
    }

    @Test void disabledStoreKeepsNothing() {
        var off = new JobStore(false, dir.resolve("none.json").toString(), json);
        off.put(new JobStore.Stored("x", running("x"), definition(null), "", ""));
        assertTrue(off.all().isEmpty()); assertFalse(Files.exists(dir.resolve("none.json")));
    }
}
