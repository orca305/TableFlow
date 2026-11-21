package dev.tableflow.load;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 외래 키 기준 이관 순서 계산(부모 먼저, 순환 감지). DB 없이 알고리즘만 검증한다. */
class MigrationOrderTests {
    Map<String, Set<String>> deps(Object... pairs) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], new TreeSet<>(Arrays.asList((String[]) pairs[i + 1])));
        return m;
    }
    String[] none = {};

    @Test void parentsComeBeforeChildrenInLevels() {
        var plan = MigrationService.computeOrder(deps("ORDER_ITEM", new String[]{"ORDERS", "PRODUCT"}, "ORDERS", new String[]{"CUSTOMER"}, "CUSTOMER", none, "PRODUCT", none), Map.of());
        var level = new HashMap<String, Integer>(); plan.order().forEach(i -> level.put(i.table(), i.level()));
        assertEquals(0, level.get("CUSTOMER")); assertEquals(0, level.get("PRODUCT"));
        assertEquals(1, level.get("ORDERS")); assertEquals(2, level.get("ORDER_ITEM"));
        assertEquals(List.of("CUSTOMER", "PRODUCT", "ORDERS", "ORDER_ITEM"), plan.order().stream().map(MigrationService.OrderItem::table).toList());
        assertTrue(plan.cycles().isEmpty());
    }

    @Test void cyclesAreReportedInsteadOfLoopingForever() {
        var plan = MigrationService.computeOrder(deps("A", new String[]{"B"}, "B", new String[]{"A"}, "C", none, "D", new String[]{"A"}), Map.of());
        assertEquals(List.of("C"), plan.order().stream().map(MigrationService.OrderItem::table).toList());
        assertEquals(Set.of("A", "B", "D"), new HashSet<>(plan.cycles()));  // D는 순환하는 A에 의존해 함께 정할 수 없다
    }

    @Test void externalParentsAreKeptForGuidance() {
        var plan = MigrationService.computeOrder(deps("EMP", new String[]{}), Map.of("EMP", Set.of("DEPT")));
        assertEquals(List.of("DEPT"), plan.order().get(0).externalParents());
        assertEquals(0, plan.order().get(0).level());
    }
}
