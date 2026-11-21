package dev.tableflow.load;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 반복 실행(스케줄) 정의를 서버 로컬 JSON 파일에 보관한다. 정의의 요청 본문에는 DB 비밀번호가 없고,
 * 저장된 연결의 ID(savedId)만 있어 실행할 때 서버가 저장된 비밀번호를 사용한다.
 */
@Service
public class ScheduleStore {
    private static final Logger log = LoggerFactory.getLogger(ScheduleStore.class);

    /** kind: single | batch. mode: interval(intervalMinutes마다) | daily(dailyAt 시각, 서버 시간대). refresh: append(추가 적재) | replace(실행 전에 대상 테이블의 행을 지우고 다시 적재). */
    public record Schedule(String id, String name, String kind, JsonNode request, String mode, Integer intervalMinutes, String dailyAt, String refresh,
                           boolean enabled, String createdAt, String lastRunAt, String lastState, String lastMessage, String lastJobId, String nextRunAt,
                           String source, String target) {
        public Schedule with(boolean enabled, String lastRunAt, String lastState, String lastMessage, String lastJobId, String nextRunAt) {
            return new Schedule(id, name, kind, request, mode, intervalMinutes, dailyAt, refresh, enabled, createdAt, lastRunAt, lastState, lastMessage, lastJobId, nextRunAt, source, target);
        }
    }

    private final boolean enabled; private final Path file; private final ObjectMapper json;
    public ScheduleStore(@Value("${tableflow.schedules.enabled:true}") boolean enabled, @Value("${tableflow.schedules.file:data/schedules.json}") String file, ObjectMapper json) {
        this.enabled = enabled; this.file = Path.of(file); this.json = json;
    }
    public boolean enabled() { return enabled; }

    public synchronized List<Schedule> all() {
        if (!enabled || !Files.isRegularFile(file)) return new ArrayList<>();
        try { return new ArrayList<>(json.readValue(Files.readAllBytes(file), new TypeReference<List<Schedule>>() {})); }
        catch (IOException | RuntimeException e) { log.warn("스케줄 파일을 읽지 못했습니다: {}", e.getClass().getSimpleName()); return new ArrayList<>(); }
    }
    public synchronized void saveAll(List<Schedule> items) {
        if (!enabled) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(items));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) { log.warn("스케줄을 저장하지 못했습니다: {}", e.getClass().getSimpleName()); }
    }
}
