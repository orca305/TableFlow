package dev.tableflow.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tableflow.load.DatabaseLoader.LoadFailure;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class JobHistoryTests {
    @TempDir Path dir;
    JobHistory history(boolean enabled, Path file) { return new JobHistory(enabled, file.toString(), new ObjectMapper()); }

    @Test void recordsSuccessReplayAndFailureNewestFirstWithoutSecrets() throws Exception {
        var h = history(true, dir.resolve("sub/history.jsonl"));
        h.run("migrate", "r1", "oracle · FREEPDB1 · APP.SRC", "postgresql · tableflow · public.dst", () -> new DatabaseLoader.Result("r1", "dst", 3, false));
        h.run("migrate", "r1", "a", "b", () -> new DatabaseLoader.Result("r1", "dst", 3, true));
        var failure = assertThrows(LoadFailure.class, () -> h.run("load", "r2", "file.csv", "mysql · tableflow · tableflow.t",
            () -> { throw new LoadFailure(422, "LOAD_REJECTED", "DB 제약조건 또는 값 변환에 실패해 전체 적재를 취소했습니다."); }));
        assertEquals("LOAD_REJECTED", failure.code);
        var items = h.recent(10);
        assertEquals(3, items.size());
        assertEquals("failed", items.get(0).status()); assertEquals("LOAD_REJECTED", items.get(0).errorCode()); assertEquals(0, items.get(0).rows());
        assertEquals("replayed", items.get(1).status());
        assertEquals("success", items.get(2).status()); assertEquals(3, items.get(2).rows()); assertEquals("r1", items.get(2).requestId());
        assertEquals(1, h.recent(1).size());
        String raw = Files.readString(dir.resolve("sub/history.jsonl"));
        assertFalse(raw.toLowerCase().contains("password")); assertFalse(raw.contains("localhost"));
    }

    @Test void unknownExceptionsDoNotLeakTheirMessage() {
        var h = history(true, dir.resolve("h.jsonl"));
        assertThrows(IllegalStateException.class, () -> h.run("load", "r", "s", "t", () -> { throw new IllegalStateException("jdbc:postgresql://secret-host/db password=hunter2"); }));
        var entry = h.recent(1).getFirst();
        assertEquals("INTERNAL_ERROR", entry.errorCode());
        assertFalse(entry.message().contains("hunter2")); assertFalse(entry.message().contains("secret-host"));
    }

    @Test void trimsOldEntriesAndSkipsCorruptLines() throws Exception {
        Path file = dir.resolve("t.jsonl"); var h = history(true, file);
        for (int i = 0; i < 1001; i++) h.run("load", "r" + i, "s", "t", () -> new DatabaseLoader.Result("r", "t", 1, false));
        assertEquals(500, Files.readAllLines(file).size());
        assertEquals("r1000", h.recent(1).getFirst().requestId());
        Files.writeString(file, "{not json\n", StandardOpenOption.APPEND);
        assertEquals(200, h.recent(500).size()); // 손상된 줄은 건너뛰고 상한 200건
    }

    @Test void disabledOrUnwritableHistoryNeverBreaksTheJob() throws Exception {
        var off = history(false, dir.resolve("off.jsonl"));
        assertEquals(1, off.run("load", "r", "s", "t", () -> new DatabaseLoader.Result("r", "t", 1, false)).insertedRows());
        assertFalse(Files.exists(dir.resolve("off.jsonl"))); assertTrue(off.recent(5).isEmpty());
        Path blocker = dir.resolve("blocker"); Files.writeString(blocker, "x"); // 파일 아래에 디렉터리를 만들 수 없다
        var broken = history(true, blocker.resolve("h.jsonl"));
        assertEquals(2, broken.run("load", "r", "s", "t", () -> new DatabaseLoader.Result("r", "t", 2, false)).insertedRows());
        assertTrue(broken.recent(5).isEmpty());
    }
}
