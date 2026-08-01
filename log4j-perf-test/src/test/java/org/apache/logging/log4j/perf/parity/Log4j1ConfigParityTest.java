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
package org.apache.logging.log4j.perf.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LifeCycle;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rendered-output parity gate for the five file-writing configurations of this module whose schema was translated
 * in place: {@code log4j12-perf.xml}, {@code log4j12-perf2.xml}, {@code log4j12-perfloc.xml},
 * {@code log4j12-perf-file-throwable.xml} and {@code perf-log4j12-async.xml}.
 * <p>
 * Each case is independent and follows one sequence: remove the destination, boot the fixture in its own logger
 * context from its classpath location, replay {@link ParityCorpus}'s fixed event script, <em>stop the context</em>
 * so the writer flushes and any queue drains, read the produced file, normalize it and compare it with the
 * baseline captured from the superseded generation before any dependency was removed. Nothing is read before the
 * stop: four of the fixtures disable immediate flush and the fifth is both buffered and asynchronous, so a
 * capture taken while the context still runs is a race rather than a result.
 * </p>
 * <p>
 * <strong>Acceptance is an empty normalized diff.</strong> The normalizer belongs to {@link ParityCorpus} and
 * substitutes exactly four rendered elements — timestamp, thread name, source line number and absolute path — so
 * every other byte is compared literally: the level text and its padding width, the logger-name abbreviation, the
 * caller class and method rendered beside the one normalized line number, every literal separator, the
 * byte-significant double spaces an absent context-map key leaves behind, the one significant trailing space, and
 * a throwable's full tab-indented rendering. Nothing is trimmed and no run of spaces is collapsed.
 * </p>
 * <p>
 * <strong>A non-empty diff is a defect in the translated configuration.</strong> It is never a reason to widen the
 * normalizer, to weaken an assertion, to wait for output that has not arrived, or to edit a committed baseline.
 * </p>
 * <p>
 * Two obligations go beyond comparing text, and both are the corpus's own. First, the fixture whose root level is
 * {@code error} admits none of the levels its siblings emit, so its capture is a single control line that cannot
 * express its own wiring: the corpus requires that fixture's configuration to be asserted structurally as well,
 * and requires its conversion pattern to be bound to the pattern its {@code debug} sibling renders, because that
 * is the only route by which it inherits the sibling's rendered proof that the level field keeps its minimum
 * width. Second, the destinations must be fresh, since four fixtures share one file and none of them truncates.
 * Both are honoured below. The observed effective-configuration evidence and the rationale for every translation
 * decision live in the {@code effective-config.adoc} beside the baselines rather than here; this class is the
 * rendered-output gate.
 * </p>
 */
class Log4j1ConfigParityTest {

    // -----------------------------------------------------------------------------------------------------------
    // The five fixtures. Every configuration keeps its original file name and classpath location, so each is
    // loaded from the classpath root under that name; nothing here is renamed and no name is discovered.
    // -----------------------------------------------------------------------------------------------------------

    /** Synchronous file fixture, root level {@code debug}, padded level token. */
    private static final String T1 = "T1";

    /** The same fixture at root level {@code error}, which is what makes its capture a suppression proof. */
    private static final String T2 = "T2";

    /** The only fixture that renders caller location through its pattern. */
    private static final String T3 = "T3";

    /** The only fixture whose script entry carries a throwable. */
    private static final String T4 = "T4";

    /** The buffered, asynchronous file fixture, whose destination is module-relative. */
    private static final String T5 = "T5";

    private static final String T1_CONFIG = "/log4j12-perf.xml";

    private static final String T2_CONFIG = "/log4j12-perf2.xml";

    private static final String T3_CONFIG = "/log4j12-perfloc.xml";

    private static final String T4_CONFIG = "/log4j12-perf-file-throwable.xml";

    private static final String T5_CONFIG = "/perf-log4j12-async.xml";

    private static final String T1_BASELINE = "/log4j1-parity/log4j12-perf.baseline.txt";

    private static final String T2_BASELINE = "/log4j1-parity/log4j12-perf2.baseline.txt";

    private static final String T3_BASELINE = "/log4j1-parity/log4j12-perfloc.baseline.txt";

    private static final String T4_BASELINE = "/log4j1-parity/log4j12-perf-file-throwable.baseline.txt";

    private static final String T5_BASELINE = "/log4j1-parity/perf-log4j12-async.baseline.txt";

    // -----------------------------------------------------------------------------------------------------------
    // Structural expectations, carried verbatim from the translated fixtures
    // -----------------------------------------------------------------------------------------------------------

    /** Appender name shared by the four synchronous fixtures. */
    private static final String SHARED_APPENDER_NAME = "TestLogfile";

    /**
     * Destination declared by the four synchronous fixtures, as the fixture spells it. The literal is deliberately
     * not derived from the corpus's platform-resolved path constant: what is asserted here is the appender's
     * configured destination string, which is the fixture's own text.
     */
    private static final String SHARED_FILE_NAME = "target/testlog4j.log";

    /**
     * Conversion pattern of the two fixtures whose only difference is their root level.
     * <p>
     * Asserting this one constant on both is what lets the {@code error} fixture inherit the {@code debug}
     * fixture's rendered proof that {@code %5p} keeps its minimum field width. The {@code error} fixture admits
     * only five-character levels, so no event it admits can exercise the width, and fabricating a shorter level
     * would mean re-levelling an event, which behaviour preservation forbids.
     * </p>
     */
    private static final String PADDED_LEVEL_PATTERN = "%d %5p [%t] %c{1} %X{transactionId} - %m%n";

    /**
     * Conversion pattern of the caller-location fixture. The class token keeps its bare integer option, which
     * retains that many trailing name parts and is exactly the superseded generation's semantics; the dotted form
     * used by the independently authored benchmark peer beside the fixture selects a different abbreviation
     * strategy and would change every rendered line.
     */
    private static final String CALLER_LOCATION_PATTERN = "%d %5p [%t] %C{1}.%M:%L %X{transactionId} - %m%n";

    /**
     * Conversion pattern of the throwable fixture. It names no explicit throwable specifier and ends with the line
     * separator, so a plain throwable converter is appended implicitly and the full trace is rendered into the
     * capture and compared byte for byte, without the extended converter's jar-and-version suffixes.
     */
    private static final String MESSAGE_ONLY_PATTERN = "%m%n";

    /** Name of the asynchronous wrapper. Upper case, and not interchangeable with the peer fixtures' spelling. */
    private static final String ASYNC_APPENDER_NAME = "ASYNC";

    /** Name of the file appender the asynchronous wrapper delegates to. */
    private static final String ASYNC_SINK_APPENDER_NAME = "File";

    /**
     * Destination of the asynchronous fixture, declared relative with no directory. It therefore lands in the
     * module directory rather than under the build output directory, which is why it is removed after every case.
     */
    private static final String ASYNC_FILE_NAME = "perftest.log";

    /** Conversion pattern of the asynchronous fixture. The space before the line separator is real. */
    private static final String ASYNC_PATTERN = "%d %p %c{1} [%t] %X{aKey} %m %n";

    /** Queue depth of the asynchronous wrapper, held verbatim from the superseded fixture's buffer size. */
    private static final int ASYNC_QUEUE_CAPACITY = 262144;

    // -----------------------------------------------------------------------------------------------------------
    // The fresh-destination contract
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Removes both destinations before every case.
     * <p>
     * Four of the five fixtures write to one shared file and none of them asks to truncate, while append-on-open
     * is the default, so a stale file would be appended to and every capture after the first would carry the
     * previous case's lines. Removing the destinations here is the only safe moment: no context holds a file
     * manager yet, and the removal cannot interrupt a capture. Without it the suppression fixture would appear to
     * have rendered its sibling's lines, and it would do so only for some of the random orders the test run uses.
     * </p>
     */
    @BeforeEach
    void removeDestinationsBeforeBoot() throws IOException {
        removeDestinations();
    }

    /**
     * Removes both destinations after every case, whether it passed or failed.
     * <p>
     * The asynchronous fixture's destination is module-relative, and neither the repository's ignore rules nor the
     * licence audit's exclusions cover it, so a leftover copy would surface as an untracked file and fail the
     * audit. This teardown runs after the context has been stopped by the case's own try-with-resources block, so
     * the file is never removed while its manager is open.
     * </p>
     */
    @AfterEach
    void removeDestinationsAfterCapture() throws IOException {
        removeDestinations();
    }

    /**
     * Removes both destinations. The corpus's removal is idempotent and reports whether a stale file was present;
     * that outcome is deliberately not asserted here, because either answer is legitimate.
     */
    private static void removeDestinations() throws IOException {
        ParityCorpus.deleteDestination(ParityCorpus.TESTLOG4J_DESTINATION);
        ParityCorpus.deleteDestination(ParityCorpus.PERFTEST_DESTINATION);
    }

    // -----------------------------------------------------------------------------------------------------------
    // One independent case per fixture
    // -----------------------------------------------------------------------------------------------------------

    /**
     * The four-line synchronous capture: three benchmark-derived events and the field-width control that makes
     * {@code %5p} observable, since every benchmark-derived event on this fixture renders a five-character level
     * and would therefore render identically through a bare {@code %p}.
     */
    @Test
    @DisplayName("log4j12-perf.xml renders its committed baseline byte for byte")
    void log4j12PerfRendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T1, T1_CONFIG)) {
            assertSharedFileFixture(context.getConfiguration(), Level.DEBUG, PADDED_LEVEL_PATTERN);
            ParityCorpus.replay(context, T1);
        }
        assertRenderedParity(T1, T1_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The suppression fixture. Its root level admits neither scripted debug event, so the capture is the single
     * positive control the corpus adds at {@code error}, and the two halves carry the evidence together: the
     * control line is present, so the configuration loaded, the named appender is wired to the root and the
     * destination is right; neither debug message is present, so the level filter still applies. Either half alone
     * would be satisfied by a fixture that never worked at all, which is why the structural assertions below are
     * required rather than optional.
     */
    @Test
    @DisplayName("log4j12-perf2.xml renders its committed baseline byte for byte and suppresses both debug events")
    void log4j12Perf2RendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T2, T2_CONFIG)) {
            assertSharedFileFixture(context.getConfiguration(), Level.ERROR, PADDED_LEVEL_PATTERN);
            ParityCorpus.replay(context, T2);
        }
        assertRenderedParity(T2, T2_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The caller-location capture. Both of its lines are emitted from one fabricated caller identity, so the
     * rendered class and method are compared byte for byte on both while only the source line number is
     * normalized — the single tolerated output difference in the whole corpus, and the reason the class token's
     * abbreviation option must not be rewritten.
     */
    @Test
    @DisplayName("log4j12-perfloc.xml renders its committed baseline byte for byte, caller class and method included")
    void log4j12PerflocRendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T3, T3_CONFIG)) {
            assertSharedFileFixture(context.getConfiguration(), Level.DEBUG, CALLER_LOCATION_PATTERN);
            ParityCorpus.replay(context, T3);
        }
        assertRenderedParity(T3, T3_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The throwable capture. The corpus assigns the scripted throwable a fixed stack trace, so the message line,
     * the exception line and every tab-indented frame are compared byte for byte — including whether the output
     * ends with a line separator, which is the evidence that settles how the implicitly appended throwable
     * converter behaves for this pattern.
     */
    @Test
    @DisplayName("log4j12-perf-file-throwable.xml renders its committed baseline byte for byte, throwable included")
    void log4j12PerfFileThrowableRendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T4, T4_CONFIG)) {
            assertSharedFileFixture(context.getConfiguration(), Level.DEBUG, MESSAGE_ONLY_PATTERN);
            ParityCorpus.replay(context, T4);
        }
        assertRenderedParity(T4, T4_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The buffered, asynchronous capture. Stopping the context is what hands the queue over to be drained and the
     * buffered writer to be flushed, so the file is read only afterwards. The rendered line keeps its unpadded
     * level, the double space an absent context-map key leaves behind, and the trailing space the pattern places
     * before the line separator.
     */
    @Test
    @DisplayName("perf-log4j12-async.xml renders its committed baseline byte for byte through its async wrapper")
    void perfLog4j12AsyncRendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T5, T5_CONFIG)) {
            assertAsynchronousFileFixture(context.getConfiguration());
            ParityCorpus.replay(context, T5);
        }
        assertRenderedParity(T5, T5_BASELINE, ParityCorpus.PERFTEST_DESTINATION);
    }

    // -----------------------------------------------------------------------------------------------------------
    // The comparison itself
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Compares one capture with its committed baseline through the corpus's normalizer.
     * <p>
     * Both sides are read and normalized by identical code, so the comparison cannot be skewed by how either side
     * was obtained; the normalizer is idempotent, which is what makes normalizing an already-normalized baseline a
     * safe way to guarantee that symmetry. The presence of the file is asserted first, so a fixture that never
     * opened its destination is reported as such instead of surfacing as a missing-file exception.
     * </p>
     *
     * @param configId the configuration id under test, named in every failure message
     * @param baselineResource absolute classpath path of the committed baseline
     * @param destination file the fixture was configured to write
     * @throws IOException if the baseline or the capture cannot be read
     */
    private static void assertRenderedParity(
            final String configId, final String baselineResource, final Path destination) throws IOException {
        assertTrue(
                Files.isRegularFile(destination),
                configId + " produced no capture at " + destination
                        + "; either the fixture did not load or its appender never opened its destination");
        final String expected = ParityCorpus.normalize(ParityCorpus.readResource(baselineResource));
        final String actual = ParityCorpus.normalize(ParityCorpus.readFile(destination));
        assertEquals(
                expected,
                actual,
                configId + " diverged from the committed baseline " + baselineResource
                        + ". A non-empty normalized diff is a defect in the translated configuration, and never a"
                        + " reason to widen the normalizer, relax this assertion or edit the baseline");
    }

    // -----------------------------------------------------------------------------------------------------------
    // Structural assertions, made while the configuration is still started
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Asserts the wiring of one of the four synchronous fixtures: it loaded and started, its root logger carries
     * the expected level and additivity and resolves its single reference, and its file appender writes the
     * expected destination with the expected conversion pattern and with immediate flush disabled.
     *
     * @param configuration the started configuration of a booted fixture
     * @param expectedRootLevel level the fixture declares on its root logger
     * @param expectedPattern conversion pattern the fixture declares, compared character for character
     */
    private static void assertSharedFileFixture(
            final Configuration configuration, final Level expectedRootLevel, final String expectedPattern) {
        assertConfigurationStarted(configuration);
        assertRootLogger(configuration, expectedRootLevel, SHARED_APPENDER_NAME);
        assertFileAppender(configuration, SHARED_APPENDER_NAME, SHARED_FILE_NAME, expectedPattern);
    }

    /**
     * Asserts the wiring of the asynchronous fixture, whose root logger references the wrapper rather than the file
     * appender. The wrapper's blocking behaviour, queue depth and absence of location capture are asserted because
     * they are the fixture's asynchronous disposition, which the translation preserves rather than tunes.
     *
     * @param configuration the started configuration of the booted fixture
     */
    private static void assertAsynchronousFileFixture(final Configuration configuration) {
        assertConfigurationStarted(configuration);
        assertRootLogger(configuration, Level.DEBUG, ASYNC_APPENDER_NAME);
        final Appender wrapper = configuration.getAppender(ASYNC_APPENDER_NAME);
        assertNotNull(wrapper, "the fixture declares no appender named " + ASYNC_APPENDER_NAME);
        assertTrue(
                wrapper instanceof AsyncAppender,
                "appender " + ASYNC_APPENDER_NAME + " is a "
                        + wrapper.getClass().getName()
                        + " rather than an asynchronous appender, so the fixture's asynchronous disposition"
                        + " was not preserved");
        final AsyncAppender asyncAppender = (AsyncAppender) wrapper;
        assertTrue(asyncAppender.isStarted(), "appender " + ASYNC_APPENDER_NAME + " did not start");
        assertTrue(
                asyncAppender.isBlocking(),
                "appender " + ASYNC_APPENDER_NAME + " must block when its queue is full, as its superseded form did,"
                        + " rather than discard events");
        assertEquals(
                ASYNC_QUEUE_CAPACITY,
                asyncAppender.getQueueCapacity(),
                "queue depth of appender " + ASYNC_APPENDER_NAME + ", which is held verbatim");
        assertFalse(
                asyncAppender.isIncludeLocation(),
                "appender " + ASYNC_APPENDER_NAME + " must not capture caller location, which the fixture never"
                        + " asked for and which would change the cost of every event");
        final String[] sinkNames = asyncAppender.getAppenderRefStrings();
        assertEquals(1, sinkNames.length, "number of appenders " + ASYNC_APPENDER_NAME + " delegates to");
        assertEquals(ASYNC_SINK_APPENDER_NAME, sinkNames[0], "appender " + ASYNC_APPENDER_NAME + " delegates to");
        assertFileAppender(configuration, ASYNC_SINK_APPENDER_NAME, ASYNC_FILE_NAME, ASYNC_PATTERN);
    }

    /**
     * Asserts that a fixture was located, built by the schema's own factory and started. The type check is what
     * rules out a silent fallback: a context that failed to find its configuration still starts, with a default
     * configuration that writes somewhere else entirely, and a capture compared against that would fail for a
     * reason that has nothing to do with the translation.
     */
    private static void assertConfigurationStarted(final Configuration configuration) {
        assertNotNull(configuration, "the isolated context booted without a configuration");
        assertTrue(
                configuration instanceof XmlConfiguration,
                "expected the fixture to have been built by the XML configuration factory but found a "
                        + configuration.getClass().getName()
                        + ", which means the fixture was not located and a fallback was used instead");
        assertEquals(
                LifeCycle.State.STARTED,
                configuration.getState(),
                "the fixture was located but did not reach the started state");
    }

    /**
     * Asserts a fixture's root logger: its level, its additivity — which neither generation's fixture declared
     * before the translation stated it explicitly, and which both default to true — and that it carries exactly one
     * appender reference, by the expected name, resolved to an actual appender.
     */
    private static void assertRootLogger(
            final Configuration configuration, final Level expectedLevel, final String expectedAppenderRef) {
        final LoggerConfig rootLogger = configuration.getRootLogger();
        assertNotNull(rootLogger, "the fixture built no root logger");
        assertEquals(expectedLevel, rootLogger.getLevel(), "level of the fixture's root logger");
        assertTrue(rootLogger.isAdditive(), "additivity of the fixture's root logger");
        final List<AppenderRef> references = rootLogger.getAppenderRefs();
        assertNotNull(references, "the fixture's root logger carries no appender references");
        assertEquals(1, references.size(), "number of appender references on the fixture's root logger");
        assertEquals(
                expectedAppenderRef, references.get(0).getRef(), "appender reference on the fixture's root logger");
        assertTrue(
                rootLogger.getAppenders().containsKey(expectedAppenderRef),
                "the root logger's reference to " + expectedAppenderRef + " was not resolved to an appender");
    }

    /**
     * Asserts one file appender: that it exists under the expected name, is a file appender, started, writes the
     * expected destination, keeps immediate flush disabled as its superseded form did, and carries a pattern layout
     * whose conversion pattern matches character for character.
     */
    private static void assertFileAppender(
            final Configuration configuration,
            final String appenderName,
            final String expectedFileName,
            final String expectedPattern) {
        final Appender appender = configuration.getAppender(appenderName);
        assertNotNull(appender, "the fixture declares no appender named " + appenderName);
        assertTrue(
                appender instanceof FileAppender,
                "appender " + appenderName + " is a " + appender.getClass().getName()
                        + " rather than a file appender, so the fixture's destination kind was not preserved");
        final FileAppender fileAppender = (FileAppender) appender;
        assertTrue(fileAppender.isStarted(), "appender " + appenderName + " did not start");
        assertEquals(expectedFileName, fileAppender.getFileName(), "destination of appender " + appenderName);
        assertFalse(
                fileAppender.getImmediateFlush(),
                "appender " + appenderName + " must keep immediate flush disabled, as its superseded form did;"
                        + " the two generations do not infer it from each other's options");
        final Layout<?> layout = fileAppender.getLayout();
        assertNotNull(layout, "appender " + appenderName + " carries no layout");
        assertTrue(
                layout instanceof PatternLayout,
                "appender " + appenderName + " carries a " + layout.getClass().getName()
                        + " rather than a pattern layout");
        assertEquals(
                expectedPattern,
                ((PatternLayout) layout).getConversionPattern(),
                "conversion pattern of appender " + appenderName + ", compared character for character");
    }
}
