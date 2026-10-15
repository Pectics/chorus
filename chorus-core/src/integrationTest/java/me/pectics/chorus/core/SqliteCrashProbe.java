package me.pectics.chorus.core;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import me.pectics.chorus.core.state.ReplayDecision;
import me.pectics.chorus.core.state.sqlite.SqliteReplayGuard;

/** Child process intentionally exits without resource cleanup to exercise WAL recovery. */
public final class SqliteCrashProbe {
    private SqliteCrashProbe() {}

    public static void main(String[] arguments) throws Exception {
        Path file = Path.of(arguments[0]);
        var guard =
                new SqliteReplayGuard(
                        file,
                        "backend",
                        Duration.ofSeconds(2),
                        10,
                        Duration.ofMillis(500),
                        Clock.fixed(TestSupport.NOW, ZoneOffset.UTC));
        if (arguments[1].equals("committed")) {
            if (guard.consume("gateway", "crash-ticket", "backend", TestSupport.NOW.plusSeconds(15))
                            .toCompletableFuture()
                            .get()
                    != ReplayDecision.ACCEPTED) {
                Runtime.getRuntime().halt(1);
            }
        } else {
            var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            var statement = connection.createStatement();
            statement.execute("BEGIN IMMEDIATE");
            statement.execute(
                    "INSERT INTO chorus_consumed_tickets VALUES ('gateway', 'crash-ticket', "
                            + TestSupport.NOW.plusSeconds(17).toEpochMilli()
                            + ")");
        }
        Runtime.getRuntime().halt(0);
    }
}
