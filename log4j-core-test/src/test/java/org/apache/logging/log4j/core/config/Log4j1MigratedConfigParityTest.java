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
package org.apache.logging.log4j.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour-preservation gate for the three superseded logging configurations this module carries, each of which was
 * translated in place into the Log4j 2 schema: {@code log4j12-perf.xml}, {@code perf-log4j12.xml} and
 * {@code perf-log4j12-async.xml}.
 * <p>
 * Each fixture is booted in its own isolated logger context, handed the one event its originating arm emitted, and
 * the file it writes is then compared with the capture committed beside it in {@code /log4j1-parity/}. Those captures
 * were taken from the superseded generation before the translation landed and cannot be retaken, so they are oracles
 * rather than knobs: <strong>a divergence is a defect in the translated configuration</strong>, never a reason to
 * widen the normalizer, relax an assertion or edit a baseline.
 * </p>
 * <p>
 * <strong>Rendered output is the whole subject.</strong> This class deliberately makes no assertion about the built
 * configuration, the appenders it resolved or the text of the configuration files. Two configurations can agree on
 * every structural detail and still render different bytes — through a padding width, a name-abbreviation strategy,
 * a coupling between buffering and flushing, or a separator that survives an empty context-map key — and it is
 * exactly those bytes the translation had to preserve. The observed effective-configuration evidence for all three
 * fixtures, and the rationale for every translation decision, live in the {@code effective-config.adoc} beside the
 * baselines; this class is the rendered-output gate.
 * </p>
 * <p>
 * Three properties of these particular fixtures shape the code below.
 * </p>
 * <ul>
 *   <li><strong>No caller location is rendered.</strong> None of the three patterns carries a class, method or line
 *       token, so each event is emitted through the plain logging call and the caller frame is unobservable. The
 *       line-number token remains implemented in {@link #normalize(String)} — the normalizer is shared in intent with
 *       its peer corpus and must stay comparable to it — and is simply inert here.</li>
 *   <li><strong>Two fixtures share one destination.</strong> {@code perf-log4j12.xml} and its asynchronous sibling
 *       both write {@code perftest.log}, and neither the repository's ignore rules nor the licence-audit exclusions
 *       cover that name, so a leftover copy would surface as an untracked file and fail the audit. Every case
 *       therefore removes both destinations before it boots anything and again once it has read its capture.</li>
 *   <li><strong>Stopping is what produces the output.</strong> Every fixture disables immediate flushing and one of
 *       them wraps its appender asynchronously, so the capture is complete only after the context has been stopped.
 *       Each case reads its file strictly after closing its context.</li>
 * </ul>
 * <p>
 * No system property is set, cleared or read anywhere in this class. Handing a non-null configuration location to the
 * context constructor bypasses property lookup altogether, which is what lets three configurations boot inside one
 * virtual machine — under forked, randomly ordered execution — without contending over a single global key.
 * </p>
 */
class Log4j1MigratedConfigParityTest {

    // -----------------------------------------------------------------------------------------------------------
    // The three fixtures. Every configuration keeps the file name and classpath location it had before the
    // translation, so each is loaded from the classpath root under that name; nothing here is renamed.
    // -----------------------------------------------------------------------------------------------------------

    /** Synchronous fixture whose layout pads the level field. Truncates its destination on open. */
    private static final String T8 = "T8";

    /** Classpath location of that fixture. */
    private static final String T8_CONFIG = "/log4j12-perf.xml";

    /** Capture committed for that fixture. */
    private static final String T8_BASELINE = "/log4j1-parity/log4j12-perf.baseline.txt";

    /** Buffered synchronous fixture, whose destination is module-relative. */
    private static final String T9 = "T9";

    /** Classpath location of that fixture. */
    private static final String T9_CONFIG = "/perf-log4j12.xml";

    /** Capture committed for that fixture. */
    private static final String T9_BASELINE = "/log4j1-parity/perf-log4j12.baseline.txt";

    /** The same buffered appender behind an asynchronous wrapper, writing the same destination. */
    private static final String T10 = "T10";

    /** Classpath location of that fixture. */
    private static final String T10_CONFIG = "/perf-log4j12-async.xml";

    /** Capture committed for that fixture. */
    private static final String T10_BASELINE = "/log4j1-parity/perf-log4j12-async.baseline.txt";

    // -----------------------------------------------------------------------------------------------------------
    // The scripted events. One per fixture, each reproducing what its originating arm emitted: the same logger
    // name, the same level and the same message text. None may be renamed, re-levelled or reworded.
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Logger the comparison harness acquired for its superseded arm.
     * <p>
     * The harness gives all three logging generations the identical logger name on purpose, so that the arms remain
     * comparable. The name is therefore load-bearing and is carried character for character.
     * </p>
     */
    private static final String COMPARISON_LOGGER_NAME = "org.apache.logging.log4j.PerformanceComparison";

    /** Level that arm emitted at. */
    private static final Level COMPARISON_LEVEL = Level.DEBUG;

    /** Message that arm emitted, reproduced exactly, including its terminating period. */
    private static final String COMPARISON_MESSAGE = "SEE IF THIS IS LOGGED 2.";

    /** Logger the asynchronous throughput-and-latency runner acquired for its superseded arm. */
    private static final String RUNNER_LOGGER_NAME = "org.apache.logging.log4j.core.async.perftest.RunLog4j1";

    /**
     * Level that runner emitted at.
     * <p>
     * It is {@code info}, which is the level of the runner's own latency loop. The peer corpus in the benchmark
     * module records this same runner at {@code debug} for a fixture of its own, and the two are deliberately
     * <em>not</em> harmonised: each corpus records the level the arm it captured actually emitted.
     * </p>
     */
    private static final Level RUNNER_LEVEL = Level.INFO;

    /** Message that runner emitted, reproduced exactly. */
    private static final String RUNNER_MESSAGE = "Short msg";

    // -----------------------------------------------------------------------------------------------------------
    // Destinations and the fresh-destination contract
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Destination of the padded-level fixture. It is declared under the build output directory, so a leftover copy
     * is ignored by the repository and invisible to the licence audit; it is nevertheless removed around every case,
     * because a stale file would otherwise be read as this fixture's capture.
     */
    private static final Path TESTLOG4J_DESTINATION = Paths.get("target", "testlog4j.log");

    /**
     * Destination shared by the buffered fixture and its asynchronous sibling. It is declared relative with no
     * directory, so it lands in the module directory rather than under the build output directory, where neither the
     * ignore rules nor the licence-audit exclusions cover it.
     */
    private static final Path PERFTEST_DESTINATION = Paths.get("perftest.log");

    // -----------------------------------------------------------------------------------------------------------
    // The normalizer. Exactly four rendered elements are substituted, and never a fifth.
    // -----------------------------------------------------------------------------------------------------------

    /** Wall-clock timestamp rendered by the date token, in the default pattern of that token. */
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[,.]\\d{3}");

    /** Placeholder substituted for a rendered timestamp. */
    private static final String TIMESTAMP_TOKEN = "<TS>";

    /**
     * Bracketed thread name, as each of the three patterns renders it.
     * <p>
     * The match cannot cross a line and cannot contain a closing bracket, and none of the three scripted messages
     * contains a bracket of either kind, so the only run this can match is the thread field itself. Substituting the
     * whole bracketed run rather than its contents keeps the substitution idempotent, which is what lets an
     * already-normalized baseline be normalized again for symmetry.
     * </p>
     */
    private static final Pattern BRACKETED_THREAD = Pattern.compile("\\[[^\\]\\n]*\\]");

    /** Placeholder substituted for a rendered bracketed thread name. */
    private static final String THREAD_TOKEN = "[<THREAD>]";

    /**
     * Source line number, recognised only where a caller-location pattern places one.
     * <p>
     * A source line number is an artefact of the editing a migration performs and is the one rendered element these
     * corpora tolerate normalizing. None of the three fixtures here renders caller location, so this substitution is
     * inert; it is retained so that this normalizer stays token-for-token comparable with the peer corpus that does
     * render it, and so that adding a location-rendering fixture needs no new tolerance.
     * </p>
     */
    private static final Pattern SOURCE_LINE_NUMBER = Pattern.compile(":\\d+(?=  - )");

    /** Placeholder substituted for a rendered source line number, separator included. */
    private static final String SOURCE_LINE_TOKEN = ":<LINE>";

    /**
     * Absolute filesystem path, whether rendered with forward or backward separators.
     * <p>
     * The lookbehind requires the match to begin at the start of the input or after whitespace or an opening bracket
     * or parenthesis, so a path is only recognised where one could actually have been rendered. None of the three
     * fixtures renders a path — no throwable, no file token and no path-valued context-map key appears — so this
     * substitution is inert here too, and is retained for the same reason as the line-number token.
     * </p>
     */
    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("(?<![^\\s(\\[])(?:[A-Za-z]:[\\\\/]|/)[\\w.+~@%$-]+(?:[\\\\/][\\w.+~@%$-]+)*[\\\\/]?");

    /** Placeholder substituted for a rendered absolute path. */
    private static final String PATH_TOKEN = "<PATH>";

    /** Prefix of every context name, so a leaked context is attributable to this gate. */
    private static final String CONTEXT_NAME_PREFIX = "log4j1-parity-";

    // -----------------------------------------------------------------------------------------------------------
    // The fresh-destination contract, applied around every case
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Removes both destinations before a fixture boots.
     * <p>
     * This runs before rather than after the boot on purpose: the file managers are keyed by destination name and
     * reference-counted, so a stale file must be removed while no context holds its manager. Two of the three
     * fixtures share one destination and all three truncate on open, but a removal that depended on either fact
     * would silently stop protecting the comparison if a fixture's options were ever translated differently.
     * </p>
     */
    @BeforeEach
    void removeDestinationsBeforeBoot() throws IOException {
        removeDestinations();
    }

    /**
     * Removes both destinations once a capture has been read, so no case leaves a file behind for the next one or for
     * the licence audit.
     */
    @AfterEach
    void removeDestinationsAfterCapture() throws IOException {
        removeDestinations();
    }

    /** Removes both destinations. The removal is idempotent, so either outcome is legitimate and neither is asserted. */
    private static void removeDestinations() throws IOException {
        Files.deleteIfExists(TESTLOG4J_DESTINATION);
        Files.deleteIfExists(PERFTEST_DESTINATION);
    }

    // -----------------------------------------------------------------------------------------------------------
    // One independent case per fixture
    // -----------------------------------------------------------------------------------------------------------

    /**
     * The padded-level capture.
     * <p>
     * Its layout pads the level to a minimum of five characters and renders a context-map key that this event never
     * populates, so the committed line carries a five-character level followed by the <em>double</em> space that the
     * empty key leaves in front of the separator. Both are compared literally: nothing is trimmed and no run of
     * spaces is collapsed.
     * </p>
     */
    @Test
    @DisplayName("log4j12-perf.xml renders its committed baseline byte for byte")
    void log4j12PerfRendersItsBaseline() throws Exception {
        try (LoggerContext context = startContext(T8, T8_CONFIG)) {
            emit(context, COMPARISON_LOGGER_NAME, COMPARISON_LEVEL, COMPARISON_MESSAGE);
        }
        assertRenderedParity(T8, T8_BASELINE, TESTLOG4J_DESTINATION);
    }

    /**
     * The buffered synchronous capture.
     * <p>
     * The superseded appender coupled buffering to flushing, disabling immediate flush as soon as buffering was
     * requested, whereas the replacement treats the two as independent options that both default to on. The
     * translated fixture therefore has to state the flush disposition explicitly, and this case is what proves it
     * did: the capture is read only after the context has been stopped, which is what flushes the buffer.
     * </p>
     * <p>
     * Its layout renders an unpadded level, a context-map key this event never populates — leaving a double space
     * before the message — and a literal space between the message and the line separator. That trailing space is
     * significant and is compared like every other byte.
     * </p>
     */
    @Test
    @DisplayName("perf-log4j12.xml renders its committed baseline byte for byte, trailing space included")
    void perfLog4j12RendersItsBaseline() throws Exception {
        try (LoggerContext context = startContext(T9, T9_CONFIG)) {
            emit(context, RUNNER_LOGGER_NAME, RUNNER_LEVEL, RUNNER_MESSAGE);
        }
        assertRenderedParity(T9, T9_BASELINE, PERFTEST_DESTINATION);
    }

    /**
     * The asynchronous capture. Its root logger reaches the same buffered appender through an asynchronous wrapper,
     * so the event is handed to a background thread and the file stays empty until the context is stopped: stopping
     * drains the wrapper's queue into the appender and then flushes the appender's buffer. Read any earlier, the
     * capture would be a queue still in flight. The rendered bytes must be identical to those of the synchronous
     * fixture, because interposing the wrapper is required to change delivery and nothing else.
     */
    @Test
    @DisplayName("perf-log4j12-async.xml renders its committed baseline byte for byte through its async wrapper")
    void perfLog4j12AsyncRendersItsBaseline() throws Exception {
        try (LoggerContext context = startContext(T10, T10_CONFIG)) {
            emit(context, RUNNER_LOGGER_NAME, RUNNER_LEVEL, RUNNER_MESSAGE);
        }
        assertRenderedParity(T10, T10_BASELINE, PERFTEST_DESTINATION);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Booting, emitting and comparing
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Boots one configuration in a logger context of its own, from the configuration's classpath location.
     * <p>
     * The location is passed as a non-null URI, which is what makes the context independent of every configuration
     * system property: the factory consults those properties only when it is given no location, so three fixtures
     * can be booted in one virtual machine without any of them observing or disturbing global state. The context is
     * named after the fixture, so a leaked context is attributable.
     * </p>
     * <p>
     * The returned context is started and is the caller's to close; closing it stops it, and stopping is what drains
     * an asynchronous queue and flushes a buffered writer. If the boot itself fails the partially started context is
     * stopped here rather than left running, because a context that was never returned cannot be closed by anyone
     * else.
     * </p>
     *
     * @param configId the fixture id, used to name the context
     * @param configResourcePath absolute classpath path of the configuration, under its original name
     * @return the started context
     * @throws URISyntaxException if the located resource cannot be expressed as a URI
     */
    private static LoggerContext startContext(final String configId, final String configResourcePath)
            throws URISyntaxException {
        final URL configLocation = Log4j1MigratedConfigParityTest.class.getResource(configResourcePath);
        assertNotNull(configLocation, "missing configuration resource: " + configResourcePath);
        final LoggerContext starting = new LoggerContext(CONTEXT_NAME_PREFIX + configId, null, configLocation.toURI());
        boolean started = false;
        try {
            starting.start();
            started = true;
        } finally {
            if (!started) {
                Configurator.shutdown(starting);
            }
        }
        return starting;
    }

    /**
     * Emits one scripted event through the supplied context.
     * <p>
     * The logger is obtained from the context rather than from the static factory, so the event is guaranteed to
     * reach the fixture under test and not whatever configuration happens to be current. The level is supplied
     * explicitly instead of choosing a level-named method, which keeps one emission path for all three cases; that
     * choice is unobservable here because none of the three layouts renders a class, a method or a line number, so
     * no caller frame is ever resolved.
     * </p>
     *
     * @param context the started context that owns the fixture
     * @param loggerName the logger name to emit on, carried verbatim from the originating arm
     * @param level the level to emit at
     * @param message the message text to emit, rendered as-is
     */
    private static void emit(
            final LoggerContext context, final String loggerName, final Level level, final String message) {
        context.getLogger(loggerName).log(level, message);
    }

    /**
     * Compares one capture with its committed baseline through the normalizer.
     * <p>
     * Both sides are read and normalized by identical code, so the comparison cannot be skewed by how either side
     * was obtained; the normalizer is idempotent, which is what makes normalizing an already-normalized baseline a
     * safe way to guarantee that symmetry. The presence of the file is asserted first, so a fixture that never opened
     * its destination is reported as such rather than surfacing as a missing-file exception.
     * </p>
     *
     * @param configId the fixture id, named in every failure message
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
        final String expected = normalize(readResource(baselineResource));
        final String actual = normalize(readFile(destination));
        assertEquals(
                expected,
                actual,
                configId + " diverged from the committed baseline " + baselineResource
                        + ". A non-empty normalized diff is a defect in the translated configuration, and never a"
                        + " reason to widen the normalizer, relax this assertion or edit the baseline");
    }

    /**
     * Substitutes the four non-deterministic or migration-dependent rendered elements, and nothing else.
     * <p>
     * Every other byte survives to be compared literally: the level text and its padding width, the logger-name
     * abbreviation, every literal separator, the byte-significant double spaces an absent context-map key leaves
     * behind, and the significant trailing space one of the patterns places before the line separator. Nothing is
     * trimmed, no run of spaces is collapsed and no line ending is rewritten.
     * </p>
     * <p>
     * The substitutions are applied in a fixed order, timestamp first, so that a later pattern cannot match text an
     * earlier one has already replaced. Each is idempotent, so applying the whole normalizer to output it has
     * already produced changes nothing — which is why it is safe to run it over the committed baseline as well as
     * over the capture.
     * </p>
     *
     * @param rendered rendered output, read whole and untrimmed
     * @return the normalized form
     */
    private static String normalize(final String rendered) {
        String normalized = TIMESTAMP.matcher(rendered).replaceAll(Matcher.quoteReplacement(TIMESTAMP_TOKEN));
        normalized = BRACKETED_THREAD.matcher(normalized).replaceAll(Matcher.quoteReplacement(THREAD_TOKEN));
        normalized = SOURCE_LINE_NUMBER.matcher(normalized).replaceAll(Matcher.quoteReplacement(SOURCE_LINE_TOKEN));
        return ABSOLUTE_PATH.matcher(normalized).replaceAll(Matcher.quoteReplacement(PATH_TOKEN));
    }

    /**
     * Reads a classpath resource as text.
     * <p>
     * The whole byte content is decoded in one step with an explicit charset. Reading by lines is deliberately
     * avoided: it would discard whether the content ends with a line separator, and that disposition is part of what
     * the comparison asserts.
     * </p>
     *
     * @param resourcePath absolute classpath path of the resource
     * @return the decoded content, with no trimming of any kind
     * @throws IOException if the resource cannot be read
     */
    private static String readResource(final String resourcePath) throws IOException {
        final URL url = Log4j1MigratedConfigParityTest.class.getResource(resourcePath);
        assertNotNull(url, "missing classpath resource: " + resourcePath);
        final Path path;
        try {
            path = Paths.get(url.toURI());
        } catch (final URISyntaxException malformed) {
            // A resource that resolves but cannot be expressed as a URI is a build defect, not an I/O condition.
            throw new IllegalStateException("cannot resolve the classpath resource " + resourcePath, malformed);
        }
        return readFile(path);
    }

    /**
     * Reads a file as text, using the same decoding and the same newline discipline as {@link #readResource(String)},
     * so that a capture and a baseline are always read by identical code.
     *
     * @param file the file to read
     * @return the decoded content, with no trimming of any kind
     * @throws IOException if the file cannot be read
     */
    private static String readFile(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
