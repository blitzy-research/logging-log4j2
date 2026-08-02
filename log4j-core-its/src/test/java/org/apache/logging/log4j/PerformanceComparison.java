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
package org.apache.logging.log4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.test.util.Profiler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Use this class to analyze performance between Log4j and other logging frameworks.
 * <p>
 * This class is not selected by a normal build. The module's inherited Failsafe {@code groups} filter names JUnit 4
 * category types, which JUnit 5 reads as literal tag names and matches against nothing, and the module's Surefire
 * execution is skipped outright. The {@code performance-comparison} profile in this module's POM exists to run it, and
 * fails rather than passes if it selects no test:
 * </p>
 *
 * <pre>{@code mvn -pl log4j-core-its -Pperformance-comparison verify}</pre>
 * <p>
 * The three arms are configured independently. The two arms selected by global properties -
 * {@value #CONFIG} for Log4j 2 and {@value #LOGBACK_CONFIG} for Logback - are left exactly as they were. The arm
 * migrated from Log4j 1.x is configured through a logger context of its own, because it and the native arm are now the
 * same implementation and one global property could only ever select one of the two.
 * </p>
 */
@Tag("PerformanceTests")
class PerformanceComparison {

    /**
     * Serves the configuration that was migrated from Log4j 1.x. It is held in a logger context of its own rather than
     * in the global one, because this class drives three logging generations inside a single JVM: two of them are now
     * the same implementation, so a single global configuration property could only ever select one of the two.
     * {@link #setupClass()} therefore configures this context on both of its paths without letting Log4j 2 resolve a
     * configuration itself: it is always started from a configuration it is <em>handed</em> directly - the migrated
     * fixture's own location when that fixture is on the classpath, and the explicitly built hierarchy returned by
     * {@link #supersededDefaultHierarchy()} when it is not. Neither path consults a configuration property, so neither
     * arm can displace the other. Assigned by {@link #setupClass()}, which JUnit runs before it instantiates this
     * class, and assigned only once that setup has both started the context and validated it - a setup that fails
     * either step releases the context it started and publishes nothing here - so the instance initializer below
     * always observes a started, validated context. Stopped again by {@link #cleanupClass()} so that no started
     * context outlives the test.
     */
    private static LoggerContext migratedContext;

    private final Logger logger = LogManager.getLogger(PerformanceComparison.class.getName());
    private final org.slf4j.Logger logbacklogger = org.slf4j.LoggerFactory.getLogger(PerformanceComparison.class);
    // Deliberately the same logger name as the two arms above, so that the three measurements stay comparable.
    private final org.apache.logging.log4j.Logger log4jlogger =
            migratedContext.getLogger(PerformanceComparison.class.getName());

    // How many times should we try to log:
    private static final int COUNT = 500000;
    private static final int PROFILE_COUNT = 500000;
    private static final int WARMUP = 50000;

    private static final String CONFIG = "log4j2-perf.xml";
    private static final String LOGBACK_CONFIG = "logback-perf.xml";
    private static final String LOG4J_CONFIG = "log4j12-perf.xml";

    private static final String LOGBACK_CONF = "logback.configurationFile";

    private static final String MIGRATED_CONTEXT_NAME = "performance-comparison-migrated";

    /**
     * Name carried by the configuration built when {@link #LOG4J_CONFIG} is not on this module's test classpath. It
     * appears in status output and in assertion failures, so it names the semantics it reproduces rather than the
     * mechanism that builds it.
     */
    private static final String SUPERSEDED_HIERARCHY_NAME = "superseded-default-hierarchy";

    /**
     * Where {@link #LOG4J_CONFIG} resolved to, or {@code null} when it is not on this module's test classpath. Recorded
     * once by {@link #setupClass()} so that every assertion below can state which of the two configurations the
     * migrated arm is actually running without resolving the resource a second time and risking a different answer.
     */
    private static URL migratedConfigLocation;

    @BeforeAll
    static void setupClass() throws URISyntaxException {
        System.setProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY, CONFIG);
        System.setProperty(LOGBACK_CONF, LOGBACK_CONFIG);
        assertEquals(
                CONFIG,
                System.getProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY),
                "the native arm's configuration property must be in force before any logger is acquired");
        assertEquals(
                LOGBACK_CONFIG,
                System.getProperty(LOGBACK_CONF),
                "the Logback arm's configuration property must be in force before any logger is acquired");
        // The migrated configuration is selected by pointing a dedicated context straight at it, rather than by
        // setting a global property, so that it cannot displace the native configuration selected just above.
        // That fixture belongs to another module, whose test resources are not published, so it is absent from this
        // module's test classpath: the lookup below returns null here today and the built-hierarchy branch is the path
        // actually taken. When it is absent, handing the null location to the context would not leave the arm
        // unconfigured: the context would resolve a location from the very configuration property the native arm sets
        // two lines above, report a failed reconfiguration when that value does not resolve, and otherwise fall back
        // to Log4j 2's own default configuration, whose root logger is ERROR and which owns a console appender.
        // None of that is what was observed before the migration, where the superseded arm found no configuration,
        // kept its default root level of DEBUG, and owned no appender at all. The absent-fixture branch therefore
        // builds that hierarchy explicitly - root at DEBUG, zero appenders - so the arm keeps admitting the events it
        // is timing while still rendering them nowhere. Building the configuration also keeps the arm
        // property-independent in both branches: a context that is handed a configuration, like one handed a
        // location, reads no configuration property, and an unresolvable fixture surfaces as a configured context
        // rather than as an exception thrown out of class setup.
        migratedConfigLocation = PerformanceComparison.class.getResource("/" + LOG4J_CONFIG);
        // Constructing a context starts nothing, so it needs no guard; everything after it does. A started context
        // owns a configuration, whatever appenders that configuration declares and a shutdown callback, and this one
        // is deliberately absent from every context registry - that absence is what keeps it from displacing the
        // native arm, and it also means the local below is the only reference to it in the process. So if either
        // start throws, or if the validation that follows rejects what came up, letting the failure leave this method
        // with the local still started would leak a running context for the lifetime of the fork, with nothing left
        // able to reach it. Both starts and the validation therefore run under a guard that releases the context and
        // withdraws the two properties this method set, and the static field is published only once the arm has both
        // started and been validated - so a failed setup leaves exactly nothing behind.
        final LoggerContext starting = migratedConfigLocation == null
                ? new LoggerContext(MIGRATED_CONTEXT_NAME)
                : new LoggerContext(MIGRATED_CONTEXT_NAME, null, migratedConfigLocation.toURI());
        boolean armReady = false;
        try {
            if (migratedConfigLocation == null) {
                starting.start(supersededDefaultHierarchy());
            } else {
                starting.start();
            }
            assertMigratedArmIsConfigured(starting);
            armReady = true;
        } finally {
            if (!armReady) {
                Configurator.shutdown(starting);
                System.clearProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY);
                System.clearProperty(LOGBACK_CONF);
            }
        }
        // Published only once the context is started and validated, so the instance initializer cannot observe a
        // partial one and a failed setup publishes nothing at all.
        migratedContext = starting;
    }

    /**
     * Builds the hierarchy that the superseded Log4j 1.x arm presented when its configuration file could not be found:
     * a root logger at DEBUG owning no appender. Log4j 1.x reached that state by leaving its own repository defaults in
     * place; Log4j 2's defaults differ on both counts, so the state has to be stated rather than inherited.
     *
     * @return a configuration, ready to be started, with a root logger at DEBUG and no appenders
     */
    private static Configuration supersededDefaultHierarchy() {
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setConfigurationName(SUPERSEDED_HIERARCHY_NAME);
        builder.add(builder.newRootLogger(Level.DEBUG));
        return builder.build(false);
    }

    /**
     * Asserts that the migrated arm came up on the configuration {@link #setupClass()} selected for it, and that the
     * arm therefore measures what it claims to. The assertions are deliberately branch-aware: the presence of
     * {@link #LOG4J_CONFIG} on the classpath is a property of the module's test scope, not of this class, so it is
     * never asserted. What is asserted in both branches is that the configuration in force is the one this class
     * chose, and that its root logger admits DEBUG - the level the timed calls below use.
     * <p>
     * The context is taken as an argument rather than read from {@link #migratedContext}, because these assertions run
     * <em>before</em> that field is published: an arm that fails them is released rather than measured, so it must
     * never have been visible to anything.
     * </p>
     *
     * @param context the started context {@link #setupClass()} means to publish, still held only in its local
     */
    private static void assertMigratedArmIsConfigured(final LoggerContext context) {
        assertTrue(context.isStarted(), "the migrated arm's context must be started before it is measured");
        final Configuration configuration = context.getConfiguration();
        assertNotNull(configuration, "the migrated arm's context must hold a configuration");
        final ConfigurationSource source = configuration.getConfigurationSource();
        if (migratedConfigLocation == null) {
            assertSame(
                    ConfigurationSource.NULL_SOURCE,
                    source,
                    "with the fixture absent the arm must run the hierarchy built for it, which has no source");
            assertEquals(
                    SUPERSEDED_HIERARCHY_NAME,
                    configuration.getName(),
                    "with the fixture absent the arm must run the hierarchy built for it, not a default one");
            assertTrue(
                    configuration.getRootLogger().getAppenders().isEmpty(),
                    "the superseded arm rendered to no destination when its configuration was absent, so neither may"
                            + " its replacement");
        } else {
            assertNotNull(source.getLocation(), "a fixture-backed configuration must report where it was read from");
            assertTrue(
                    source.getLocation().endsWith(LOG4J_CONFIG),
                    "the arm must be built from " + LOG4J_CONFIG + " but was built from " + source.getLocation());
        }
        assertEquals(
                Level.DEBUG,
                configuration.getRootLogger().getLevel(),
                "the migrated arm's root logger must admit DEBUG, the level every timed call below uses");
    }

    @AfterAll
    static void cleanupClass() {
        System.clearProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY);
        System.clearProperty(LOGBACK_CONF);
        // Stopping the context here is what keeps it from outliving the test: Surefire reuses no fork, but it does
        // randomise order, so nothing may be left started behind. The field is cleared afterwards so that a context
        // stopped once is never handed to the shutdown call a second time; the stopped context is held in a local so
        // that the assertion below can still be made against it once the field no longer refers to it.
        final LoggerContext stopped = migratedContext;
        if (stopped != null) {
            Configurator.shutdown(stopped);
            migratedContext = null;
        }
        new File("target/testlog4j.log").deleteOnExit();
        new File("target/testlog4j2.log").deleteOnExit();
        new File("target/testlogback.log").deleteOnExit();
        // Asserted after the teardown actions rather than between them, so that a failing assertion reports an
        // incomplete cleanup instead of causing one.
        assertNull(
                System.getProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY),
                "the native arm's configuration property must not outlive the test");
        assertNull(
                System.getProperty(LOGBACK_CONF), "the Logback arm's configuration property must not outlive the test");
        assertNotNull(stopped, "the migrated arm's context must have been created by setup");
        assertFalse(stopped.isStarted(), "the migrated arm's context must not outlive the test");
    }

    @Test
    void testPerformance() {

        // A timing loop over a disabled logger measures the level check and nothing else, which would silently turn
        // this comparison into a comparison of level checks. Assert the migrated arm admits its events before any
        // measurement is taken. Only the migrated arm is asserted: the other two arms are selected by global
        // properties naming fixtures that this module does not own, so whether they are enabled is a property of the
        // test scope rather than of this class.
        assertTrue(
                log4jlogger.isDebugEnabled(),
                "the migrated arm must admit the debug events it is about to time, or the measurement below is of a"
                        + " disabled call rather than of logging");

        log4j(WARMUP);
        logback(WARMUP);
        log4j2(WARMUP);

        if (Profiler.isActive()) {
            System.out.println("Profiling Log4j 2.0");
            Profiler.start();
            final long result = log4j2(PROFILE_COUNT);
            Profiler.stop();
            System.out.println("###############################################");
            System.out.println("Log4j 2.0: " + result);
            System.out.println("###############################################");
        } else {
            doRun();
            doRun();
            doRun();
            doRun();
        }
    }

    private void doRun() {
        System.out.print("Log4j    : ");
        System.out.println(log4j(COUNT));

        System.out.print("Logback  : ");
        System.out.println(logback(COUNT));

        System.out.print("Log4j 2.0: ");
        System.out.println(log4j2(COUNT));

        System.out.println("###############################################");
    }

    // @Test
    private void testRawPerformance() throws Exception {
        final OutputStream os = new FileOutputStream("target/testos.log", true);
        final long result1 = writeToStream(COUNT, os);
        os.close();
        final OutputStream bos = new BufferedOutputStream(new FileOutputStream("target/testbuffer.log", true));
        final long result2 = writeToStream(COUNT, bos);
        bos.close();
        final Writer w = new FileWriter("target/testwriter.log", true);
        final long result3 = writeToWriter(COUNT, w);
        w.close();
        final FileOutputStream cos = new FileOutputStream("target/testchannel.log", true);
        final FileChannel channel = cos.getChannel();
        final long result4 = writeToChannel(COUNT, channel);
        cos.close();
        System.out.println("###############################################");
        System.out.println("FileOutputStream: " + result1);
        System.out.println("BufferedOutputStream: " + result2);
        System.out.println("FileWriter: " + result3);
        System.out.println("FileChannel: " + result4);
        System.out.println("###############################################");
    }

    private long log4j(final int loop) {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            log4jlogger.debug("SEE IF THIS IS LOGGED " + j + '.');
        }
        return (System.nanoTime() - start) / loop;
    }

    private long logback(final int loop) {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            logbacklogger.debug("SEE IF THIS IS LOGGED " + j + '.');
        }
        return (System.nanoTime() - start) / loop;
    }

    private long log4j2(final int loop) {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            logger.debug("SEE IF THIS IS LOGGED " + j + '.');
        }
        return (System.nanoTime() - start) / loop;
    }

    private long writeToWriter(final int loop, final Writer w) throws Exception {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            w.write("SEE IF THIS IS LOGGED " + j + '.');
        }
        return (System.nanoTime() - start) / loop;
    }

    private long writeToStream(final int loop, final OutputStream os) throws Exception {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            os.write(getBytes("SEE IF THIS IS LOGGED " + j + '.'));
        }
        return (System.nanoTime() - start) / loop;
    }

    private long writeToChannel(final int loop, final FileChannel channel) throws Exception {
        final Integer j = Integer.valueOf(2);
        final ByteBuffer buf = ByteBuffer.allocateDirect(8 * 1024);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            channel.write(getByteBuffer(buf, "SEE IF THIS IS LOGGED " + j + '.'));
        }
        return (System.nanoTime() - start) / loop;
    }

    private ByteBuffer getByteBuffer(final ByteBuffer buf, final String s) {
        buf.clear();
        buf.put(s.getBytes());
        buf.flip();
        return buf;
    }

    private byte[] getBytes(final String s) {
        return s.getBytes();
    }
}
