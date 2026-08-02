/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.logging.log4j.perf.jmh;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Proves that a benchmark whose {@code @Setup} fails <em>after</em> it has started the logger context of its migrated
 * Log4j 1.x arm leaves nothing behind: the context is released, and the field that would have owned it is never
 * published.
 * <p>
 * This is not a hypothetical. JMH does not invoke the {@code @TearDown} of a state whose {@code @Setup} threw, so a
 * context assigned to the field before a later failure would stay started for the remainder of the fork's life,
 * holding its configuration, its file manager and its shutdown callback, with nothing left able to reach it. Two of
 * the migrated arms do real work after the start: they construct a {@link java.util.logging.FileHandler}, which
 * reaches the filesystem and can therefore fail. Those two are the classes exercised here; the remaining migrated
 * arms carry the identical guard, but their only post-start step is obtaining a logger from the context they have
 * just started, into which no failure can be injected from outside.
 * </p>
 * <p>
 * The oracle is deliberately observational rather than structural. A started context registers a file manager under
 * the literal destination its configuration names, and stopping the context unregisters that manager while leaving
 * the destination on disk. So <em>destination present</em> establishes that the context really did start — without
 * which the rest of the proof would be vacuous — and <em>manager absent</em> establishes that the started context was
 * then released. {@code target/testlog4j.log} is the destination of the migrated configuration alone: the Log4j 2,
 * Logback and JUL arms of these same benchmarks write to four other files, so the manager's presence attributes
 * unambiguously to the arm under test.
 * </p>
 */
class MigratedArmSetupFailureTest {

    /**
     * The destination named by {@code log4j12-perf.xml}, the migrated configuration both benchmarks under test boot.
     */
    private static final String MIGRATED_DESTINATION = "target/testlog4j.log";

    /** The path both benchmarks hand to {@link java.util.logging.FileHandler}, and the injection point. */
    private static final String JUL_DESTINATION = "target/testJulLog.log";

    /**
     * How many suffixed variants of {@link #JUL_DESTINATION} are occupied alongside the destination itself. See
     * {@link #occupyJulDestinations()} for why more than the destination has to be occupied.
     */
    private static final int JUL_UNIQUENESS_HEADROOM = 4;

    /** The two selector properties the benchmarks set for their unmigrated arms. */
    private static final String LOG4J2_SELECTOR_PROPERTY = "log4j.configurationFile";

    private static final String LOGBACK_SELECTOR_PROPERTY = "logback.configurationFile";

    @BeforeEach
    void injectTheSetupFailure() throws IOException {
        Files.createDirectories(Paths.get("target"));
        removeJulArtefacts();
        removeQuietly(new File(MIGRATED_DESTINATION));
        occupyJulDestinations();
    }

    @AfterEach
    void withdrawTheSetupFailure() {
        // Cleared here as well as in the benchmark's own teardown, so that an assertion failing before that teardown
        // runs cannot leak a selector property into the rest of this fork.
        System.clearProperty(LOG4J2_SELECTOR_PROPERTY);
        System.clearProperty(LOGBACK_SELECTOR_PROPERTY);
        removeJulArtefacts();
        removeQuietly(new File(MIGRATED_DESTINATION));
    }

    @Test
    @DisplayName("FileAppenderBenchmark releases the context it started when a later setup step fails")
    void fileAppenderBenchmarkReleasesItsContextWhenSetupFails() throws ReflectiveOperationException {
        final FileAppenderBenchmark benchmark = new FileAppenderBenchmark();
        assertFailedSetupOwnsNothing(benchmark, benchmark::setUp, benchmark::tearDown);
    }

    @Test
    @DisplayName("FileAppenderParamsBenchmark releases the context it started when a later setup step fails")
    void fileAppenderParamsBenchmarkReleasesItsContextWhenSetupFails() throws ReflectiveOperationException {
        final FileAppenderParamsBenchmark benchmark = new FileAppenderParamsBenchmark();
        assertFailedSetupOwnsNothing(benchmark, benchmark::setUp, benchmark::tearDown);
    }

    private void assertFailedSetupOwnsNothing(final Object benchmark, final Executable setUp, final Executable tearDown)
            throws ReflectiveOperationException {

        assertFalse(
                AbstractManager.hasManager(MIGRATED_DESTINATION),
                "precondition: no file manager may be registered for " + MIGRATED_DESTINATION + " before the setup");
        assertFalse(
                Files.exists(Paths.get(MIGRATED_DESTINATION)),
                "precondition: " + MIGRATED_DESTINATION + " must be absent before the setup");

        final IOException failure = assertThrows(
                IOException.class,
                setUp,
                "the setup must fail: every destination its JUL handler can open is occupied by a directory");
        assertTrue(
                failure.getMessage().startsWith(JUL_DESTINATION),
                "the failure must be the injected one, raised after the context started, not an earlier one: "
                        + failure.getMessage());

        // The context had already started by the time the injected failure arrived: it opened its destination. Without
        // this the two assertions below would hold of a context that was never started, proving nothing.
        assertTrue(
                Files.isRegularFile(Paths.get(MIGRATED_DESTINATION)),
                MIGRATED_DESTINATION + " must exist: the migrated context starts before the injected failure");

        // The started context was released rather than left running for the rest of the fork's life.
        assertFalse(
                AbstractManager.hasManager(MIGRATED_DESTINATION),
                "a failed setup must release the context it started, unregistering the manager for "
                        + MIGRATED_DESTINATION);

        // And it was never published, so no later code can mistake a stopped context for a usable one.
        assertNull(publishedContext(benchmark), "a failed setup must leave the context field unpublished");

        // JMH skips the teardown of a state whose setup threw. Running it anyway must still be harmless, because that
        // is what makes the unpublished field safe rather than merely unused.
        assertDoesNotThrow(tearDown, "the teardown must tolerate a state whose setup failed");
        assertFalse(
                AbstractManager.hasManager(MIGRATED_DESTINATION),
                "the teardown must not register a manager for " + MIGRATED_DESTINATION);
    }

    /**
     * Reads the context field of a benchmark whose setup has failed. Reflection is the only way in: the field is
     * private, and it is private because publishing it is precisely what this test asserts does not happen.
     */
    private static LoggerContext publishedContext(final Object benchmark) throws ReflectiveOperationException {
        final Field field = benchmark.getClass().getDeclaredField("log4j1Context");
        field.setAccessible(true);
        return (LoggerContext) field.get(benchmark);
    }

    /**
     * Occupies every path a {@link java.util.logging.FileHandler} constructed for {@link #JUL_DESTINATION} can open
     * within one fork, using a non-empty directory: no {@code FileOutputStream} can open one, and no
     * {@code File#delete()} can remove one, so the benchmark's own log-file cleanup cannot clear the injection either.
     * <p>
     * More than the destination itself has to be occupied. A {@code FileHandler} whose construction fails leaves its
     * lock in the handler class's per-JVM lock set, so the next construction in the same JVM finds that lock taken and
     * appends an increasing uniqueness suffix to the destination instead — measured on this JDK as
     * {@code target/testJulLog.log}, then {@code target/testJulLog.log.1}, and so on. Occupying the suffixed names as
     * well keeps the injection deterministic for every attempt this class makes, and keeps it working unchanged on a
     * JDK that releases the lock instead and therefore reuses the first name. An attempt that ran out of occupied
     * names would construct its handler successfully and fail this test's {@code assertThrows}, so the injection
     * cannot degrade into a silent pass.
     * </p>
     */
    private static void occupyJulDestinations() throws IOException {
        for (final Path destination : julDestinations()) {
            Files.createDirectories(destination);
            Files.write(destination.resolve("occupied.txt"), "occupied".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<Path> julDestinations() {
        final List<Path> destinations = new ArrayList<>();
        destinations.add(Paths.get(JUL_DESTINATION));
        for (int uniqueness = 1; uniqueness <= JUL_UNIQUENESS_HEADROOM; uniqueness++) {
            destinations.add(Paths.get(JUL_DESTINATION + "." + uniqueness));
        }
        return destinations;
    }

    /**
     * Removes the occupied directories and any lock file a failed {@code FileHandler} construction left beside them.
     * Best effort by design: a lock file the fork still holds open is undeletable on some platforms, and its presence
     * obstructs nothing, since the occupied destinations are re-created rather than required to be absent.
     */
    private static void removeJulArtefacts() {
        final File[] entries = new File("target").listFiles();
        if (entries != null) {
            for (final File entry : entries) {
                if (entry.getName().startsWith("testJulLog.log")) {
                    removeQuietly(entry);
                }
            }
        }
    }

    private static void removeQuietly(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                removeQuietly(child);
            }
        }
        file.delete();
    }
}
