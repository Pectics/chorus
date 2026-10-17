package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.local.LocalReplayGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalReplayGuardTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock(TestSupport.NOW);

    private LocalReplayGuard open(Path path, int capacity) throws Exception {
        return new LocalReplayGuard(
                path, "backend", Duration.ofSeconds(2), capacity, Duration.ofMillis(500), clock);
    }

    private CompletableFuture<ReplayDecision> consume(LocalReplayGuard guard, String jti) {
        return guard.consume("gateway", jti, "backend", TestSupport.NOW.plusSeconds(15))
                .toCompletableFuture();
    }

    @Test
    void concurrentConsumptionSucceedsExactlyOnceAndSurvivesReopening() throws Exception {
        Path file = directory.resolve("replay.db");
        try (var guard = open(file, 200)) {
            var attempts = new ArrayList<CompletableFuture<ReplayDecision>>();
            for (int i = 0; i < 100; i++) {
                attempts.add(CompletableFuture.supplyAsync(() -> consume(guard, "same").join()));
            }
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
            assertThat(attempts.stream().map(CompletableFuture::join).toList())
                    .filteredOn(result -> result == ReplayDecision.ACCEPTED)
                    .hasSize(1);
            assertThat(attempts.stream().map(CompletableFuture::join).toList())
                    .filteredOn(result -> result == ReplayDecision.REPLAYED)
                    .hasSize(99);
        }
        try (var reopened = open(file, 200)) {
            assertThat(consume(reopened, "same").get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.REPLAYED);
        }
    }

    @Test
    void completionSeesCommittedStateFromAnotherConnectionAndEnforcesDurableSettings()
            throws Exception {
        Path file = directory.resolve("replay.db");
        try (var guard = open(file, 10)) {
            consume(guard, "committed")
                    .thenAccept(
                            result -> {
                                assertThat(result).isEqualTo(ReplayDecision.ACCEPTED);
                                try (var reader =
                                                DriverManager.getConnection("jdbc:sqlite:" + file);
                                        var statement = reader.createStatement();
                                        var rows =
                                                statement.executeQuery(
                                                        "SELECT COUNT(*) FROM chorus_consumed_tickets")) {
                                    assertThat(rows.next()).isTrue();
                                    assertThat(rows.getInt(1)).isEqualTo(1);
                                } catch (Exception e) {
                                    throw new AssertionError(e);
                                }
                            })
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            try (var reader = DriverManager.getConnection("jdbc:sqlite:" + file);
                    var statement = reader.createStatement();
                    var rows = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(rows.getString(1)).isEqualTo("wal");
            }
        }
    }

    @Test
    void capacityNeverEvictsRecordsStillAcceptedDuringClockSkew() throws Exception {
        try (var guard = open(directory.resolve("replay.db"), 1)) {
            assertThat(consume(guard, "first").get()).isEqualTo(ReplayDecision.ACCEPTED);
            assertThat(consume(guard, "second").get()).isEqualTo(ReplayDecision.CAPACITY_EXCEEDED);
            clock.now = TestSupport.NOW.plusSeconds(15);
            assertThat(consume(guard, "first").get()).isEqualTo(ReplayDecision.REPLAYED);
            assertThat(consume(guard, "second").get()).isEqualTo(ReplayDecision.CAPACITY_EXCEEDED);
            clock.now = TestSupport.NOW.plusSeconds(17);
            assertThat(
                            guard.consume(
                                            "gateway",
                                            "new",
                                            "backend",
                                            clock.instant().plusSeconds(15))
                                    .toCompletableFuture()
                                    .get())
                    .isEqualTo(ReplayDecision.ACCEPTED);
        }
    }

    @Test
    void blockedDatabaseDoesNotBlockCallerAndTimeoutDoesNotConsume() throws Exception {
        Path file = directory.resolve("replay.db");
        try (var guard = open(file, 10);
                var blocker = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = blocker.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            var pending = consume(guard, "locked");
            assertThat(pending.isDone()).isFalse();
            assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.UNAVAILABLE);
            statement.execute("ROLLBACK");
            assertThat(consume(guard, "locked").get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.ACCEPTED);
        }
    }

    @Test
    void concurrentIndependentGuardsShareOneAtomicConsumptionBoundary() throws Exception {
        Path file = directory.resolve("replay.db");
        try (var first = open(file, 10);
                var second = open(file, 10)) {
            var a = consume(first, "shared");
            var b = consume(second, "shared");
            assertThat(java.util.List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ReplayDecision.ACCEPTED, ReplayDecision.REPLAYED);
        }
    }

    @Test
    void rejectsWrongOwnerChangedToleranceCorruptionAndClosedGuard() throws Exception {
        Path file = directory.resolve("replay.db");
        var guard = open(file, 10);
        guard.close();
        assertThat(consume(guard, "closed").get()).isEqualTo(ReplayDecision.UNAVAILABLE);
        assertThatThrownBy(
                        () ->
                                new LocalReplayGuard(
                                        file,
                                        "other",
                                        Duration.ofSeconds(2),
                                        10,
                                        Duration.ofMillis(500),
                                        clock))
                .isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(
                        () ->
                                new LocalReplayGuard(
                                        file,
                                        "backend",
                                        Duration.ofSeconds(3),
                                        10,
                                        Duration.ofMillis(500),
                                        clock))
                .isInstanceOf(java.sql.SQLException.class);
        Path corrupt = directory.resolve("corrupt.db");
        Files.writeString(corrupt, "corrupt database, do not replace");
        assertThatThrownBy(() -> open(corrupt, 10)).isInstanceOf(java.sql.SQLException.class);
        assertThat(Files.readString(corrupt)).isEqualTo("corrupt database, do not replace");
    }

    @Test
    void existingStateWithAMissingLedgerIsNotReinitialized() throws Exception {
        Path file = directory.resolve("replay.db");
        try (var guard = open(file, 10)) {
            assertThat(consume(guard, "used").get()).isEqualTo(ReplayDecision.ACCEPTED);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE chorus_consumed_tickets");
        }
        assertThatThrownBy(() -> open(file, 10)).isInstanceOf(java.sql.SQLException.class);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT name FROM sqlite_schema WHERE name='chorus_consumed_tickets'")) {
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void abruptProcessExitRecoversCommittedWalAndDiscardsUncommittedWrites() throws Exception {
        for (String mode : java.util.List.of("committed", "uncommitted")) {
            Path file = directory.resolve(mode + ".db");
            Process child =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("chorus.integration.classpath"),
                                    LocalReplayCrashProbe.class.getName(),
                                    file.toString(),
                                    mode)
                            .redirectErrorStream(true)
                            .start();
            try {
                assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue())
                        .withFailMessage(new String(child.getInputStream().readAllBytes()))
                        .isZero();
            } finally {
                child.destroyForcibly();
            }
            try (var guard = open(file, 10)) {
                assertThat(consume(guard, "crash-ticket").get(5, TimeUnit.SECONDS))
                        .isEqualTo(
                                mode.equals("committed")
                                        ? ReplayDecision.REPLAYED
                                        : ReplayDecision.ACCEPTED);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
