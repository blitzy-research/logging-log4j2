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
package org.apache.logging.log4j.core.async.perftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.appender.CountingNoOpAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the contract between {@link PerfTestDriver} and {@link RunLog4j1} to what it claims, executably.
 * <p>
 * The driver launches one child JVM per row and hands it the row's configuration file name in a system property; the
 * runner has to arrive at a hierarchy that admits the events the row times. Neither half of that contract was checked
 * by anything before this class existed, and neither half is checked by running the harness: a runner whose events are
 * all disabled still completes both tests and still prints a throughput and a latency figure. This class asserts the
 * three things that would otherwise fail silently - which selector the driver forwards, what the runner does when the
 * forwarded name resolves, and what it does when the forwarded name resolves to nothing.
 * </p>
 * <p>
 * It runs in a Surefire execution of its own, declared in this module's POM, because the module skips Surefire outright
 * and its inherited Failsafe {@code groups} filter names JUnit 4 category types that JUnit 5 matches against nothing.
 * </p>
 */
class Log4j1PerfTestContractTest {

    /**
     * The configuration file name carried by the driver's two synchronous Log4j 1.x rows.
     *
     * @see PerfTestDriver
     */
    private static final String SYNC_ROW_CONFIG = "perf-log4j12.xml";

    /**
     * The configuration file name carried by the driver's two asynchronous Log4j 1.x rows.
     *
     * @see PerfTestDriver
     */
    private static final String ASYNC_ROW_CONFIG = "perf-log4j12-async.xml";

    /**
     * The selector Log4j 1.x read, and which the driver must no longer forward. Spelled out here rather than referenced
     * through a constant because no constant for it survives the migration - which is the point of the assertion.
     */
    private static final String SUPERSEDED_SELECTOR = "-Dlog4j.configuration=";

    private static final String PROBE_FIXTURE_NAME = "RunLog4j1ProbeFixture";

    private static final String PROBE_APPENDER_NAME = "Counter";

    /**
     * Cleared after every test, whether the test set it or not, so that no test can observe a selector left behind by
     * another. Surefire randomises order, so this is not hypothetical.
     */
    @AfterEach
    void clearSelector() {
        System.clearProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY);
    }

    @Test
    void driverForwardsOnlyTheNativeSelectorForTheSynchronousRow() throws Exception {
        assertRowForwardsOnlyTheNativeSelector(SYNC_ROW_CONFIG, "Sync");
    }

    @Test
    void driverForwardsOnlyTheNativeSelectorForTheAsynchronousRow() throws Exception {
        assertRowForwardsOnlyTheNativeSelector(ASYNC_ROW_CONFIG, "Async Appender");
    }

    /**
     * Asserts the child-argument contract for one Log4j 1.x row: the row's configuration is forwarded under the native
     * selector, the superseded selector is not forwarded at all, and the child is told to instantiate
     * {@link RunLog4j1}. The {@link PerfTestDriver.Setup} is built with exactly the arguments the driver's private row
     * builder uses for a single-threaded row, since that builder cannot be reached from here.
     *
     * @param config the row's configuration file name
     * @param rowName the row's display name
     */
    private void assertRowForwardsOnlyTheNativeSelector(final String config, final String rowName) throws Exception {
        final PerfTestDriver.Setup setup = new PerfTestDriver.Setup(
                PerfTest.class, PerfTestDriver.Runner.Log4j12, rowName, config, 1, PerfTestDriver.WaitStrategy.Block);
        final List<String> arguments = setup.processArguments("java");
        try {
            int nativeSelectors = 0;
            for (final String argument : arguments) {
                assertFalse(
                        argument.startsWith(SUPERSEDED_SELECTOR),
                        "the superseded Log4j 1.x selector must no longer be forwarded to the child, but found "
                                + argument);
                if (argument.startsWith("-Dlog4j.configurationFile=")) {
                    nativeSelectors++;
                }
            }
            assertEquals(1, nativeSelectors, "the child must be handed exactly one Log4j 2 configuration selector");
            assertTrue(
                    arguments.contains("-Dlog4j.configurationFile=" + config),
                    "the child must be handed the row's configuration under the native selector");
            assertTrue(
                    arguments.contains("-Dlogback.configurationFile=" + config),
                    "the Logback selector is out of scope for this migration and must still be forwarded unchanged");

            // The last five arguments are the child's own, in the order the driver appends them.
            final int size = arguments.size();
            assertEquals(
                    PerfTest.class.getName(),
                    arguments.get(size - 5),
                    "a single-threaded row must run in the single-threaded harness");
            assertEquals(
                    RunLog4j1.class.getName(),
                    arguments.get(size - 4),
                    "a Log4j 1.x row must instantiate the migrated runner");
            assertEquals(rowName, arguments.get(size - 3), "the row's name must reach the child unchanged");
            assertEquals("1", arguments.get(size - 1), "the row's thread count must reach the child unchanged");
        } finally {
            // Setup's constructor creates this file; nothing else removes it.
            new File(arguments.get(arguments.size() - 2)).delete();
        }
    }

    @Test
    void runnerReproducesTheSupersededHierarchyWhenTheRowConfigurationIsAbsent() {
        // This is the branch that actually occurs under the driver: the Log4j 1.x rows name fixtures owned by another
        // module, so the forwarded name resolves to nothing on the child's classpath.
        System.clearProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY);
        final RunLog4j1 runner = new RunLog4j1();
        try {
            final Configuration configuration = configurationOf(runner);
            assertSame(
                    ConfigurationSource.NULL_SOURCE,
                    configuration.getConfigurationSource(),
                    "with the row's configuration absent the runner must run the hierarchy it builds, which is read"
                            + " from no source");
            assertEquals(
                    Level.DEBUG,
                    configuration.getRootLogger().getLevel(),
                    "the superseded arm kept its own default root level of DEBUG when its configuration was absent");
            assertTrue(
                    configuration.getRootLogger().getAppenders().isEmpty(),
                    "the superseded arm owned no appender when its configuration was absent, so it rendered its events"
                            + " nowhere");
            assertTrue(
                    runner.LOGGER.isInfoEnabled(),
                    "the events the throughput and latency tests emit must stay enabled, or both tests time a level"
                            + " check instead of logging");
            // Emitting through the runner proves the events are accepted rather than merely admitted by a level check.
            runner.log("probe event with no destination");
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void runnerBuildsItsContextFromTheRowConfigurationWhenItResolves(@TempDir final Path tempDir) throws Exception {
        final Path fixture = writeProbeFixture(tempDir, Level.DEBUG);
        System.setProperty(
                ConfigurationFactory.CONFIGURATION_FILE_PROPERTY,
                fixture.toAbsolutePath().toString());
        final RunLog4j1 runner = new RunLog4j1();
        try {
            final Configuration configuration = configurationOf(runner);
            assertEquals(
                    PROBE_FIXTURE_NAME,
                    configuration.getName(),
                    "when the forwarded name resolves, the runner must run that configuration rather than build one");
            final ConfigurationSource source = configuration.getConfigurationSource();
            assertNotNull(source.getLocation(), "a file-backed configuration must report where it was read from");
            assertTrue(
                    source.getLocation().endsWith(fixture.getFileName().toString()),
                    "the runner must be built from " + fixture + " but was built from " + source.getLocation());
            assertEquals(
                    Level.DEBUG,
                    configuration.getRootLogger().getLevel(),
                    "the resolved configuration's own root level must be in force");

            final CountingNoOpAppender counter = configuration.getAppender(PROBE_APPENDER_NAME);
            assertNotNull(counter, "the resolved configuration's appender must have been built");
            final long before = counter.getCount();
            runner.log("probe event with a destination");
            assertEquals(
                    before + 1,
                    counter.getCount(),
                    "an event emitted through the runner must reach the destination the resolved configuration wires");
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void runnerRefusesAConfigurationThatWouldDisableTheEventsItTimes(@TempDir final Path tempDir) throws Exception {
        // The failure mode this guards against is silent: a runner whose info events are all disabled still completes
        // both tests and still reports a figure, for a level check rather than for logging.
        final Path fixture = writeProbeFixture(tempDir, Level.ERROR);
        System.setProperty(
                ConfigurationFactory.CONFIGURATION_FILE_PROPERTY,
                fixture.toAbsolutePath().toString());
        final IllegalStateException failure = assertThrows(IllegalStateException.class, RunLog4j1::new);
        assertTrue(
                failure.getMessage().contains(RunLog4j1.class.getName()),
                "the failure must name the logger that is disabled, but was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains(PROBE_FIXTURE_NAME),
                "the failure must name the configuration that disabled it, but was: " + failure.getMessage());
    }

    /**
     * Writes a Log4j 2 configuration whose root logger sits at the given level and writes to a counting appender, so
     * that a test can assert both what level is in force and whether events actually arrive.
     *
     * @param tempDir the directory to write into
     * @param rootLevel the level to give the root logger
     * @return the written file
     */
    private static Path writeProbeFixture(final Path tempDir, final Level rootLevel) throws Exception {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Configuration name=\"" + PROBE_FIXTURE_NAME + "\">\n"
                + "  <Appenders>\n"
                + "    <CountingNoOp name=\"" + PROBE_APPENDER_NAME + "\"/>\n"
                + "  </Appenders>\n"
                + "  <Loggers>\n"
                + "    <Root level=\"" + rootLevel.name().toLowerCase(Locale.ROOT) + "\">\n"
                + "      <AppenderRef ref=\"" + PROBE_APPENDER_NAME + "\"/>\n"
                + "    </Root>\n"
                + "  </Loggers>\n"
                + "</Configuration>\n";
        final Path fixture = tempDir.resolve("run-log4j1-probe.xml");
        Files.write(fixture, xml.getBytes(StandardCharsets.UTF_8));
        return fixture;
    }

    /**
     * Reads the configuration the runner is actually logging through, from the runner's own logger rather than from any
     * global registry, so that the assertions describe the object under test and not a coincidence.
     *
     * @param runner the runner to inspect
     * @return the configuration in force for that runner's logger
     */
    private static Configuration configurationOf(final RunLog4j1 runner) {
        final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) runner.LOGGER;
        assertEquals(
                RunLog4j1.class.getName(),
                logger.getName(),
                "the runner's logger name is a contract surface of the migrated arm and must not drift");
        final Configuration configuration = logger.getContext().getConfiguration();
        assertNotNull(configuration, "the runner's context must hold a configuration");
        return configuration;
    }
}
