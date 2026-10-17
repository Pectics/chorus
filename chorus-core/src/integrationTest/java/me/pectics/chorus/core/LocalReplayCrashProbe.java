/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 6
 * related: chorus-core/src/integrationTest/java/me/pectics/chorus/core/LocalReplayGuardTest.java;
 *          gradle/spotbugs-exclude.xml
 * sync: Exit with live connections so the parent verifies recovery of committed and uncommitted WAL.
 * @endcontext
 */
package me.pectics.chorus.core;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.local.LocalReplayGuard;

/** Child process intentionally exits without resource cleanup to exercise WAL recovery. */
public final class LocalReplayCrashProbe {
    private LocalReplayCrashProbe() {}

    public static void main(String[] arguments) throws Exception {
        Path file = Path.of(arguments[0]);
        var guard =
                new LocalReplayGuard(
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
            var insert =
                    connection.prepareStatement(
                            "INSERT INTO chorus_consumed_tickets VALUES ('gateway', 'crash-ticket', ?)");
            insert.setLong(1, TestSupport.NOW.plusSeconds(17).toEpochMilli());
            insert.executeUpdate();
        }
        Runtime.getRuntime().halt(0);
    }
}
