package verification;

import example.todos.TodoApplication;
import example.todos.data.mapper.CategoryMapper;
import example.todos.data.model.CategoryRow;
import example.todos.service.error.BusinessViolation;
import example.todos.service.error.RefusedException;
import example.todos.service.runtime.OperationRunner;
import example.todos.service.runtime.SequenceNumbers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 操作本体から独立して、採番・確定・取消・確認の境界を実 DB で検査する。 */
@SpringBootTest(classes = TodoApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:operation-verification;DB_CLOSE_DELAY=-1", "server.port=0"
})
class OperationRunnerTest {
    @Autowired OperationRunner runner;
    @Autowired PlatformTransactionManager manager;
    @Autowired CategoryMapper categories;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM category");
    }

    void insert(String id) {
        var row = new CategoryRow();
        row.setId(id);
        row.setName("検証 " + id);
        categories.insert(row);
    }

    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class); }

    @Test void concurrentNumberingDoesNotReuseIdsAcrossTheDigitBoundary() throws Exception {
        insert("CAT-999");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 32; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return runner.execute(() -> {
                    var id = SequenceNumbers.nextId(categories.maxId(), "CAT-", 3);
                    insert(id);
                    return id;
                });
            }));
            start.countDown();
            var unique = new HashSet<String>();
            for (var result : results) assertTrue(unique.add(result.get(20, TimeUnit.SECONDS)));
            assertEquals(32, unique.size());
            assertTrue(unique.contains("CAT-1000"));
            assertTrue(unique.contains("CAT-1031"));
            assertEquals(33, count());
        }
    }

    @Test void refusalRollsBackEveryWriteAndTheNextOperationCanRun() {
        var refusal = new RefusedException(new BusinessViolation("check.test", null, Map.of()));
        assertSame(refusal, assertThrows(RefusedException.class, () -> runner.execute(() -> {
            insert("CAT-001");
            insert("CAT-002");
            throw refusal;
        })));
        assertEquals(0, count());
        assertEquals("CAT-001", runner.execute(() -> {
            var id = SequenceNumbers.nextId(categories.maxId(), "CAT-", 3);
            insert(id);
            return id;
        }));
        assertEquals(1, count());
    }

    @Test void previewReturnsAResultWithoutCommittingEvenAnAccidentalWrite() {
        assertEquals("CAT-001", runner.preview(() -> SequenceNumbers.nextId(categories.maxId(), "CAT-", 3)));
        assertEquals(0, count());
        assertEquals("結果", runner.preview(() -> { insert("CAT-001"); return "結果"; }));
        assertEquals(0, count());
        runner.execute(() -> { insert("CAT-001"); return null; });
        assertEquals(1, count());
    }

    @Test void nestedAndExternalTransactionsAreRejectedBeforeRunningTheBody() {
        var outer = new TransactionTemplate(manager);
        outer.execute(status -> {
            assertThrows(IllegalStateException.class, () -> runner.execute(() -> fail("外側に参加させない")));
            return null;
        });
        runner.execute(() -> {
            assertThrows(IllegalStateException.class, () -> runner.preview(() -> fail("入れ子を実行しない")));
            insert("CAT-001");
            return null;
        });
        assertEquals(1, count());
    }

    @Test void theNextOperationWaitsForCommitCompletion() throws Exception { completionBoundary(false); }
    @Test void theNextOperationWaitsForRollbackCompletion() throws Exception { completionBoundary(true); }

    void completionBoundary(boolean reject) throws Exception {
        var completing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondAttempt = new CountDownLatch(1);
        var secondBody = new CountDownLatch(1);
        var completionStatus = new java.util.concurrent.atomic.AtomicInteger(-1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> runner.execute(() -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        completionStatus.set(status);
                        completing.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("完了通知の待機が終わりません");
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    }
                });
                insert("CAT-001");
                if (reject) throw new IllegalArgumentException("取り消す");
                return 1;
            }));
            assertTrue(completing.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondAttempt.countDown();
                return runner.execute(() -> {
                    secondBody.countDown();
                    return count();
                });
            });
            try {
                assertTrue(secondAttempt.await(10, TimeUnit.SECONDS));
                assertFalse(secondBody.await(200, TimeUnit.MILLISECONDS), "完了通知の途中で次の操作を始めない");
            } finally { release.countDown(); }
            if (reject) assertInstanceOf(IllegalArgumentException.class,
                assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS)).getCause());
            else assertEquals(1, first.get(10, TimeUnit.SECONDS));
            assertEquals(reject ? 0 : 1, second.get(10, TimeUnit.SECONDS));
            assertEquals(reject ? TransactionSynchronization.STATUS_ROLLED_BACK : TransactionSynchronization.STATUS_COMMITTED,
                completionStatus.get());
        } finally { release.countDown(); }
    }

    @Test void numberingKeepsPrefixesAndRejectsMalformedOrOverflowingPositions() {
        assertEquals("TODO-001-S001", SequenceNumbers.nextId(null, "TODO-001-S", 3));
        assertEquals("X-100000000000000000000", SequenceNumbers.nextId("X-99999999999999999999", "X-", 3));
        assertThrows(IllegalStateException.class, () -> SequenceNumbers.nextId("OTHER-001", "CAT-", 3));
        assertThrows(IllegalStateException.class, () -> SequenceNumbers.nextId("CAT--1", "CAT-", 3));
        assertThrows(IllegalStateException.class, () -> SequenceNumbers.nextId("CAT-", "CAT-", 3));
        assertEquals(1L, SequenceNumbers.nextPosition(null));
        assertEquals(2L, SequenceNumbers.nextPosition(1L));
        assertThrows(IllegalStateException.class, () -> SequenceNumbers.nextPosition(-1L));
        assertThrows(ArithmeticException.class, () -> SequenceNumbers.nextPosition(Long.MAX_VALUE));
    }
}
