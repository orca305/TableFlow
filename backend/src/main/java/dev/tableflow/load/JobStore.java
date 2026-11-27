package dev.tableflow.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 대용량 이관 작업의 상태와 "다시 실행할 수 있는 정의"를 서버 로컬 JSON 파일에 보관한다(서버를 다시 시작해도 목록이 남는다).
 * 정의에는 DB 비밀번호를 넣지 않는다. 재개할 때는 저장된 연결의 비밀번호(savedId)를 쓰거나 사용자가 다시 입력한다.
 */
@Service
public class JobStore {
    private static final Logger log = LoggerFactory.getLogger(JobStore.class);
    private static final int KEEP = 50;
    /** definition: 비밀번호를 비운 요청 본문. source/target: 화면에 보여줄 이름(비밀번호·호스트 없음). createdTarget: 이 작업이 만든 대상 테이블 식별값(없으면 null). */
    public record Stored(String id, MigrationJobs.Status status, JsonNode definition, String source, String target, String createdTarget) {
        public Stored(String id, MigrationJobs.Status status, JsonNode definition, String source, String target) { this(id, status, definition, source, target, null); }
    }

    private final boolean enabled; private final Path file; private final ObjectMapper json;
    public JobStore(@Value("${tableflow.jobs.enabled:true}") boolean enabled, @Value("${tableflow.jobs.file:data/jobs.json}") String file, ObjectMapper json) {
        this.enabled = enabled; this.file = Path.of(file); this.json = json;
    }
    public boolean enabled() { return enabled; }

    /** 오래된 순서. 읽기에 실패하면 빈 목록(작업 실행에는 영향 없음). */
    public synchronized List<Stored> all() {
        if (!enabled || !Files.isRegularFile(file)) return List.of();
        try { return json.readValue(Files.readAllBytes(file), new TypeReference<List<Stored>>() {}); }
        catch (IOException | RuntimeException e) { log.warn("작업 목록 파일을 읽지 못했습니다: {}", e.getClass().getSimpleName()); return List.of(); }
    }
    public synchronized void put(Stored item) {
        if (!enabled) return;
        try {
            List<Stored> items = new ArrayList<>(all());
            items.removeIf(s -> s.id().equals(item.id())); items.add(item);
            while (items.size() > KEEP) items.remove(0);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(items));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) { log.warn("작업 목록을 저장하지 못했습니다: {}", e.getClass().getSimpleName()); }
    }
}
