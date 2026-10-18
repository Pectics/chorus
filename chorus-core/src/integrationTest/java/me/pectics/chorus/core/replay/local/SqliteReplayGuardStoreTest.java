/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 5.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuardStore.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/replay/local/SqliteReplayGuardStore.java
 * sync: Exercise synchronous retention, rollback, and resource ownership without guard checks.
 * @endcontext
 */
package me.pectics.chorus.core.replay.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.DriverManager;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuardStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteReplayGuardStoreTest {
    private static final long NOW_MILLIS = 1000;

    @TempDir Path directory;

    @Test
    void commonStorageContractRejectsExpiredConsumptionAndReclaimsExpiredRecords()
            throws Exception {
        try (var storage =
                new SqliteReplayGuardStore(directory.resolve("replay.db"), "backend", 500)) {
            assertCommonStorageRetentionContract(storage);
        }
    }

    @Test
    void closeReleasesTheOwnedConnectionAndFurtherConsumptionFails() throws Exception {
        try (var store =
                new SqliteReplayGuardStore(directory.resolve("replay.db"), "backend", 500)) {
            store.close();
            assertThat(store.consume("gateway", "ticket", NOW_MILLIS + 1, NOW_MILLIS))
                    .isEqualTo(ReplayDecision.UNAVAILABLE);
        }
    }

    @Test
    void failedConsumptionRollsBackBeforeAnotherTicketCanBeConsumed() throws Exception {
        Path file = directory.resolve("replay.db");
        try (var store = new SqliteReplayGuardStore(file, "backend", 500);
                var observer = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = observer.createStatement()) {
            statement.execute(
                    """
                    CREATE TRIGGER reject_consumption BEFORE INSERT ON chorus_consumed_tickets
                    BEGIN
                        SELECT RAISE(ABORT, 'Injected consumption failure');
                    END
                    """);
            assertThat(store.consume("gateway", "failed", NOW_MILLIS + 1, NOW_MILLIS))
                    .isEqualTo(ReplayDecision.UNAVAILABLE);
            statement.execute("DROP TRIGGER reject_consumption");
            assertThat(store.consume("gateway", "next", NOW_MILLIS + 1, NOW_MILLIS))
                    .isEqualTo(ReplayDecision.ACCEPTED);
        }
    }

    private static void assertCommonStorageRetentionContract(ReplayGuardStore store) {
        assertThat(store.consume("gateway", "ticket", NOW_MILLIS - 1, NOW_MILLIS))
                .isEqualTo(ReplayDecision.UNAVAILABLE);
        assertThat(store.consume("gateway", "ticket", NOW_MILLIS, NOW_MILLIS))
                .isEqualTo(ReplayDecision.UNAVAILABLE);
        assertThat(store.consume("gateway", "ticket", NOW_MILLIS + 1, NOW_MILLIS))
                .isEqualTo(ReplayDecision.ACCEPTED);
        assertThat(store.consume("gateway", "ticket", NOW_MILLIS + 1, NOW_MILLIS))
                .isEqualTo(ReplayDecision.REPLAYED);
        assertThat(store.consume("gateway", "ticket", NOW_MILLIS + 2, NOW_MILLIS + 1))
                .isEqualTo(ReplayDecision.ACCEPTED);
    }
}
