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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LifeCycle;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.FileManager;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
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
 * so the writer flushes and any queue drains, then read, normalize and compare. Nothing is read before the stop:
 * four fixtures disable immediate flush and the fifth is both buffered and asynchronous, so a capture taken while
 * the context still runs is a race rather than a result. Destinations must also be fresh, since four fixtures
 * share one file and none of them truncates.
 * </p>
 * <p>
 * <strong>Acceptance is an empty normalized diff.</strong> {@link ParityCorpus#normalize(String)} substitutes
 * exactly four rendered elements — timestamp, thread name, source line number and absolute path — so every other
 * byte is compared literally, including level padding, logger-name abbreviation, the caller class and method
 * rendered beside the one normalized line number, the double spaces and trailing space an absent context-map key
 * leaves behind, and a throwable's tab-indented rendering. <strong>A non-empty diff is a defect in the translated
 * configuration</strong>, never a reason to widen the normalizer, weaken an assertion, wait for output that has
 * not arrived, or edit a committed baseline.
 * </p>
 * <p>
 * One fixture needs more than a text comparison. The fixture whose root level is {@code error} admits none of the
 * levels its scripted events carry, so its byte-empty capture can express no wiring at all; its configuration is
 * therefore asserted structurally as well, and its conversion pattern is bound to the constant its {@code debug}
 * sibling uses, which is the only route by which it inherits that sibling's rendered proof that the level field
 * keeps its minimum width.
 * </p>
 */
class Log4j1ConfigParityTest {

    private static final String T1 = "T1";

    private static final String T2 = "T2";

    private static final String T3 = "T3";

    private static final String T4 = "T4";

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

    /** The level conversion the padded fixtures configure, isolated so its rendering can be observed on its own. */
    private static final String PADDED_LEVEL_CONVERSION = "%5p";

    /** The same conversion without a field width. It differs from the above in nothing else. */
    private static final String UNPADDED_LEVEL_CONVERSION = "%p";

    /**
     * A level whose name is shorter than the configured field width, so that the padding is observable at all. It is
     * used only by the direct-rendering case and is never emitted into a capture, because re-levelling a scripted
     * event would change what the corpus observes.
     */
    private static final Level SHORTER_THAN_THE_FIELD = Level.WARN;

    /** The same length as the field width, which is why no capture can distinguish the two conversions. */
    private static final Level EXACTLY_THE_FIELD_WIDTH = Level.DEBUG;

    /** {@link #SHORTER_THAN_THE_FIELD} right-aligned in the five-column field: one pad space, then the name. */
    private static final String PADDED_LEVEL_FIELD = " WARN";

    /** {@link #SHORTER_THAN_THE_FIELD} with no field width applied: the bare name, unpadded. */
    private static final String UNPADDED_LEVEL_FIELD = "WARN";

    /**
     * Context id of the direct-rendering case. It boots the same fixture as the first capture case but under its own
     * name, so the two never share a logger context however the run orders them.
     */
    private static final String LEVEL_FIELD_PROBE_ID = "T1-level-field";

    /** Message of the probe event. Never rendered, because the probe conversions name only the level. */
    private static final String LEVEL_FIELD_PROBE_MESSAGE = "level field probe";

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

    private static final String ASYNC_SINK_APPENDER_NAME = "File";

    /**
     * Destination of the asynchronous fixture, declared relative with no directory. It therefore lands in the
     * module directory rather than under the build output directory, which is why it is removed after every case.
     */
    private static final String ASYNC_FILE_NAME = "perftest.log";

    /** Conversion pattern of the asynchronous fixture. The space before the line separator is real. */
    private static final String ASYNC_PATTERN = "%d %p %c{1} [%t] %X{aKey} %m %n";

    private static final int ASYNC_QUEUE_CAPACITY = 262144;

    /**
     * Append disposition of the four synchronous fixtures. None of them states the option, and both generations
     * default it to true, so the translation preserves it by staying silent.
     */
    private static final boolean SHARED_APPEND = true;

    /**
     * Append disposition of the asynchronous fixture, which states {@code append="false"} explicitly because its
     * superseded form did. Truncating on start rather than appending is part of what the fixture measures.
     */
    private static final boolean ASYNC_APPEND = false;

    /**
     * Buffer size every fixture's destination ends up with. No fixture states a size, so each one takes the
     * default, and the default is the same 8192 bytes the superseded generation buffered with — which is why
     * omitting the buffering options is a faithful translation rather than a silent change. The manager reports a
     * negative size when a destination is not buffered at all, so this assertion also catches a fixture that lost
     * its buffer.
     */
    private static final int FILE_BUFFER_SIZE = 8192;

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
    // The no-error-status contract
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Collects the status events of the case in progress. A new test instance is created per case, so each case
     * starts with an empty recorder without any explicit reset.
     */
    private final ErrorStatusRecorder statusRecorder = new ErrorStatusRecorder();

    /**
     * Registers the recorder before every case, ahead of the boot.
     * <p>
     * Registration has to precede the boot because the events worth catching are emitted while the fixture is being
     * located and built: a mistyped attribute, an unresolved plugin, an unwritable destination and an unresolved
     * appender reference are all reported through this channel and none of them stops the context from starting.
     * Registration also silences the fallback console listener for the duration of the case, which is not the point
     * but is the right outcome: an error the run must fail on should be an assertion failure naming the fixture,
     * not a line on the console that a passing build hides.
     * </p>
     */
    @BeforeEach
    void recordStatusBeforeBoot() {
        StatusLogger.getLogger().registerListener(statusRecorder);
    }

    /**
     * Removes the recorder after every case, whether it passed or failed, so nothing leaks into the case that runs
     * next in this JVM. Removal closes the listener; the recorder's close is deliberately inert, so the evidence a
     * failing case is judged on cannot be erased by its own teardown.
     */
    @AfterEach
    void stopRecordingStatus() {
        StatusLogger.getLogger().removeListener(statusRecorder);
    }

    /**
     * Asserts that nothing has been reported at {@code ERROR} or above so far in the case.
     * <p>
     * This is the one assertion that catches an unresolved appender reference, an invalid attribute or a silent
     * fallback that still happens to render matching bytes: all three are reported through the status channel and
     * none of them stops a context from starting, so without this gate they would pass unnoticed whenever the
     * capture they produce is coincidentally identical.
     * </p>
     *
     * @param subject what the failure message should call the thing at fault
     * @param phase the span of the case the assertion covers, phrased to complete the failure message
     */
    private void assertNoErrorStatus(final String subject, final String phase) {
        final List<StatusData> errors = statusRecorder.snapshot();
        assertTrue(
                errors.isEmpty(),
                () -> subject + " reported " + errors.size() + " status event(s) at ERROR or above " + phase
                        + ". Such an event is a defect in the translated configuration or in this harness, never"
                        + " noise to be tolerated, and it is reported here rather than left to surface as a"
                        + " confusing diff:" + render(errors));
    }

    /**
     * Renders recorded status events for a failure message, one per line, each with its level, its formatted
     * message and its throwable if it carries one.
     */
    private static String render(final List<StatusData> errors) {
        final StringBuilder rendering = new StringBuilder();
        for (final StatusData error : errors) {
            rendering.append(System.lineSeparator()).append("  ").append(error.getFormattedStatus());
        }
        return rendering.toString();
    }

    /**
     * Status listener that records everything the logging system reports at {@code ERROR} or above.
     * <p>
     * The level is the listener's own, so it narrows what this recorder receives without widening or narrowing
     * what any other listener receives, and it leaves the warnings and debug traces the fixtures legitimately
     * produce unrecorded rather than turning them into failures.
     * </p>
     */
    private static final class ErrorStatusRecorder implements StatusListener {

        /**
         * Recorded events. The list is thread-safe because the asynchronous fixture reports from its own background
         * thread while the thread driving the case reads what has been recorded.
         */
        private final List<StatusData> recorded = new CopyOnWriteArrayList<>();

        @Override
        public void log(final StatusData data) {
            recorded.add(data);
        }

        @Override
        public Level getStatusLevel() {
            return Level.ERROR;
        }

        /**
         * Does nothing. Removing a listener closes it, and the events recorded up to that point have to outlive the
         * removal so that a teardown cannot erase what a failing case is judged on.
         */
        @Override
        public void close() {
            // Intentionally inert; see the contract above.
        }

        /** Returns a stable copy of what has been recorded so far, so a message cannot contradict its own test. */
        private List<StatusData> snapshot() {
            return new ArrayList<>(recorded);
        }
    }

    /**
     * The three-line synchronous capture, one line per scripted event.
     * <p>
     * Every scripted event on this fixture renders a five-character level, so these three lines would render
     * identically through a bare {@code %p}: the rendered text alone cannot show that the level field keeps its
     * minimum width. That gap is closed twice over, and in neither case by inventing a shorter-levelled event that no
     * arm of the superseded generation ever emitted: by the structural assertion below, which compares this fixture's
     * configured conversion pattern character for character while the fixture is started, and by
     * {@link #paddedLevelFieldKeepsItsMinimumWidth()}, which renders a shorter level through this same pattern's level
     * conversion beside the oracle rather than inside it.
     * </p>
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
     * The suppression fixture. Its root level admits neither scripted debug event, so nothing is rendered at all and
     * the capture is <em>byte-empty</em>.
     * <p>
     * Byte emptiness on its own is worthless evidence, because a fixture that never loaded, never resolved its
     * appender reference or never opened its destination produces exactly the same nothing. The two halves of this
     * case therefore carry the evidence together: the structural assertions establish that the configuration was
     * built by the schema's own factory, reached its started state, carries root level {@code error}, resolved its
     * single appender reference to a started file appender on the shared destination, and renders through the very
     * pattern its {@code debug} sibling renders; the byte-empty capture then establishes that the level filter still
     * applies. Neither half may be dropped, and the emptiness must not be replaced by an invented event admitted at
     * {@code error} — the fixture's whole purpose is that it renders nothing.
     * </p>
     */
    @Test
    @DisplayName("log4j12-perf2.xml suppresses both scripted debug events and renders a byte-empty capture")
    void log4j12Perf2RendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T2, T2_CONFIG)) {
            assertSharedFileFixture(context.getConfiguration(), Level.ERROR, PADDED_LEVEL_PATTERN);
            ParityCorpus.replay(context, T2);
        }
        assertRenderedParity(T2, T2_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
        assertByteEmpty(T2, T2_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The caller-location capture. Its single line is emitted from a fabricated caller identity, so the rendered
     * class and method are compared byte for byte while only the source line number is normalized — the single
     * tolerated output difference in the whole corpus, and the reason the class token's abbreviation option must
     * not be rewritten.
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
    // Supplementary evidence, deliberately outside the oracle
    // -----------------------------------------------------------------------------------------------------------

    /**
     * The padded level field, verified directly instead of through a capture.
     * <p>
     * Three of the fixtures configure {@code %5p}, and no capture can show that the field width survived the
     * translation: every level any of them admits is exactly five characters wide, so {@code %5p} and a bare
     * {@code %p} render identically. That is an arithmetic property of the levels in play, not a gap in the
     * fixtures, and it is <strong>not</strong> closed by fabricating a shorter level into a workload or a baseline.
     * Doing so would re-level a scripted emission and, worse, would redefine the oracle the migration is measured
     * against. Supplementary evidence belongs beside the oracle, never inside it.
     * </p>
     * <p>
     * So this case takes the conversion the fixture itself configures — asserted here, on the fixture's own booted
     * configuration, exactly as the capture cases assert it — and renders a shorter level through it and through
     * its width-less twin, in isolation. It replays no scripted event, writes nothing to a destination, reads no
     * capture and compares no baseline. Its final assertion states the arithmetic explicitly: at the field width
     * the two conversions are indistinguishable, which is precisely why this case has to exist.
     * </p>
     */
    @Test
    @DisplayName("%5p right-aligns a shorter level in a five-column field, which no capture can show")
    void paddedLevelFieldKeepsItsMinimumWidth() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(LEVEL_FIELD_PROBE_ID, T1_CONFIG)) {
            final Configuration configuration = context.getConfiguration();
            assertSharedFileFixture(configuration, Level.DEBUG, PADDED_LEVEL_PATTERN);
            assertTrue(
                    PADDED_LEVEL_PATTERN.contains(PADDED_LEVEL_CONVERSION),
                    "the fixture's asserted conversion pattern no longer carries " + PADDED_LEVEL_CONVERSION
                            + ", so this case would prove nothing about it: " + PADDED_LEVEL_PATTERN);

            final LogEvent shorter = probeEvent(SHORTER_THAN_THE_FIELD);
            assertEquals(
                    PADDED_LEVEL_FIELD,
                    renderLevelField(configuration, PADDED_LEVEL_CONVERSION, shorter),
                    PADDED_LEVEL_CONVERSION + " no longer right-aligns " + SHORTER_THAN_THE_FIELD
                            + " in a five-column field, so the padded level field of the translated fixtures is not"
                            + " preserved");
            assertEquals(
                    UNPADDED_LEVEL_FIELD,
                    renderLevelField(configuration, UNPADDED_LEVEL_CONVERSION, shorter),
                    UNPADDED_LEVEL_CONVERSION + " padded " + SHORTER_THAN_THE_FIELD
                            + ", which would make the comparison above meaningless");

            final LogEvent atWidth = probeEvent(EXACTLY_THE_FIELD_WIDTH);
            assertEquals(
                    renderLevelField(configuration, UNPADDED_LEVEL_CONVERSION, atWidth),
                    renderLevelField(configuration, PADDED_LEVEL_CONVERSION, atWidth),
                    EXACTLY_THE_FIELD_WIDTH + " is as wide as the field, so the two conversions must render it"
                            + " identically; if they no longer do, the reason this case exists has changed and the"
                            + " capture cases may be able to carry the evidence themselves");
        }
    }

    /**
     * Builds the probe event. It carries a level, a logger name and a message so that it is a well-formed event, but
     * only its level is ever rendered, because the conversions below name nothing else.
     */
    private static LogEvent probeEvent(final Level level) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(Log4j1ConfigParityTest.class.getName())
                .setLevel(level)
                .setMessage(new SimpleMessage(LEVEL_FIELD_PROBE_MESSAGE))
                .build();
    }

    /**
     * Renders one event through a layout carrying nothing but the supplied level conversion, built against the
     * fixture's own started configuration. Isolating the conversion is what makes the rendered field directly
     * comparable: no timestamp, thread name or message text can obscure the padding.
     */
    private static String renderLevelField(
            final Configuration configuration, final String levelConversion, final LogEvent event) {
        return PatternLayout.newBuilder()
                .setConfiguration(configuration)
                .setPattern(levelConversion)
                .build()
                .toSerializable(event);
    }

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
    private void assertRenderedParity(final String configId, final String baselineResource, final Path destination)
            throws IOException {
        assertNoErrorStatus(configId, "while its script was replayed and its context stopped");
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

    /**
     * Asserts that a suppression fixture rendered nothing whatsoever, on both sides of the comparison.
     * <p>
     * The normalized comparison already covers this, but only implicitly: it would keep passing if a line were added
     * to the committed baseline and the same line began to be rendered. Asserting emptiness against the raw bytes of
     * both the baseline and the capture states the contract directly, so that restoring the suppressed fixture's
     * output — in either place — fails immediately and by name.
     * </p>
     *
     * @param configId the configuration id under test, named in every failure message
     * @param baselineResource absolute classpath path of the committed baseline, which must itself be byte-empty
     * @param destination file the fixture was configured to write
     * @throws IOException if the baseline or the capture cannot be read
     */
    private static void assertByteEmpty(final String configId, final String baselineResource, final Path destination)
            throws IOException {
        assertEquals(
                "",
                ParityCorpus.readResource(baselineResource),
                "the committed baseline " + baselineResource + " must be byte-empty, because " + configId
                        + " suppresses every scripted event; a line here would be an invented event, not a capture");
        assertEquals(
                0L,
                Files.size(destination),
                configId + " rendered " + Files.size(destination) + " bytes to " + destination
                        + ", but its root level admits none of the levels its scripted events carry");
    }

    private void assertSharedFileFixture(
            final Configuration configuration, final Level expectedRootLevel, final String expectedPattern) {
        assertConfigurationStarted(configuration);
        assertRootLogger(configuration, expectedRootLevel, SHARED_APPENDER_NAME);
        assertFileAppender(configuration, SHARED_APPENDER_NAME, SHARED_FILE_NAME, expectedPattern, SHARED_APPEND);
    }

    /**
     * Asserts the wiring of the asynchronous fixture, whose root logger references the wrapper rather than the file
     * appender. The wrapper's blocking behaviour, queue depth and absence of location capture are asserted because
     * they are the fixture's asynchronous disposition, which the translation preserves rather than tunes.
     *
     * @param configuration the started configuration of the booted fixture
     */
    private void assertAsynchronousFileFixture(final Configuration configuration) {
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
        assertFileAppender(configuration, ASYNC_SINK_APPENDER_NAME, ASYNC_FILE_NAME, ASYNC_PATTERN, ASYNC_APPEND);
    }

    /**
     * Asserts that a fixture was located, built by the schema's own factory and started. The type check is what
     * rules out a silent fallback: a context that failed to find its configuration still starts, with a default
     * configuration that writes somewhere else entirely, and a capture compared against that would fail for a
     * reason that has nothing to do with the translation.
     */
    private void assertConfigurationStarted(final Configuration configuration) {
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
        assertNoErrorStatus("the fixture", "while being located, built and started");
    }

    /**
     * Asserts a fixture's root logger: its level, its additivity, and that it carries exactly one appender
     * reference, by the expected name, resolved to an actual appender.
     * <p>
     * Additivity is asserted true because every translated fixture states {@code additivity="true"} on its root
     * explicitly, which is not redundancy: the root logger's builder holds an omitted value in a primitive
     * {@code boolean} and therefore resolves it to false, while every other logger's builder holds it in a
     * {@code Boolean} and treats absence as true. The superseded generation's effective value was true, so on a
     * root logger only an explicit declaration preserves it.
     * </p>
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
     * Append disposition, buffer size and the flush flag are asserted together because the three are the fixture's
     * write disposition, and the two generations reach it by different routes: the superseded one inferred no-flush
     * from buffering, while this one takes each option independently. A capture cannot see any of them — a fixture
     * that truncated where it should append, or that lost its buffer and flushed every event, renders exactly the
     * same bytes.
     */
    private static void assertFileAppender(
            final Configuration configuration,
            final String appenderName,
            final String expectedFileName,
            final String expectedPattern,
            final boolean expectedAppend) {
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
        final FileManager manager = fileAppender.getManager();
        assertNotNull(manager, "appender " + appenderName + " holds no file manager");
        final String appendMessage = "append disposition of appender " + appenderName
                + ", which decides whether a run adds to the previous run's destination or truncates it";
        if (expectedAppend) {
            assertTrue(manager.isAppend(), appendMessage);
        } else {
            assertFalse(manager.isAppend(), appendMessage);
        }
        assertEquals(
                FILE_BUFFER_SIZE,
                manager.getBufferSize(),
                "buffer size of appender " + appenderName + "; the fixture states none, so the default the"
                        + " effective-configuration tables record is what it must take, and a negative size here"
                        + " would mean the destination is not buffered at all");
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
