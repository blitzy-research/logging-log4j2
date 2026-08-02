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
 * Rendered-output parity gate for this module's three translated logging configurations:
 * {@code log4j12-perf.xml}, {@code perf-log4j12.xml} and {@code perf-log4j12-async.xml}.
 * <p>
 * Each fixture boots in its own isolated logger context, is handed the one event its originating arm emitted, and the
 * file it writes is compared with the capture committed beside it in {@code /log4j1-parity/}. Those captures are
 * oracles: <strong>a divergence is a defect in the translated configuration</strong>, never a reason to widen the
 * normalizer, relax an assertion or edit a baseline. Rendered bytes are the whole subject — two configurations can
 * agree on every structural detail and still differ through a padding width, a name-abbreviation strategy, a coupling
 * between buffering and flushing, or a separator that survives an empty context-map key.
 * </p>
 * <p>
 * This class shares no code with the peer gate in the benchmark module. That module compiles at a higher release
 * level and depending on it would close a build cycle, so the event constants and the normalizer are restated here in
 * the API surface this module compiles against, and the two are kept in agreement by review. The normalizer
 * substitutes exactly four rendered elements and never a fifth; its source-line token is inert for these three
 * fixtures, none of whose patterns renders caller location, and is retained so the two remain comparable.
 * </p>
 * <p>
 * Two properties of these fixtures shape the code below. {@code perf-log4j12.xml} and its asynchronous sibling share
 * the destination {@code perftest.log}, which neither the ignore rules nor the licence-audit exclusions cover, so a
 * leftover copy would surface as an untracked file and fail the audit; every case therefore removes both destinations
 * before booting and again after reading its capture. And every fixture disables immediate flushing while one wraps
 * its appender asynchronously, so a capture is complete only once the context has been stopped — each case reads its
 * file strictly after closing its context.
 * </p>
 * <p>
 * No system property is set, cleared or read anywhere here: handing a non-null configuration location to the context
 * constructor bypasses property lookup altogether, which is what lets three configurations boot inside one virtual
 * machine, under forked and randomly ordered execution, without contending over a single global key.
 * </p>
 */
class Log4j1MigratedConfigParityTest {

    private static final String T8 = "T8";

    private static final String T8_CONFIG = "/log4j12-perf.xml";

    private static final String T8_BASELINE = "/log4j1-parity/log4j12-perf.baseline.txt";

    private static final String T9 = "T9";

    private static final String T9_CONFIG = "/perf-log4j12.xml";

    private static final String T9_BASELINE = "/log4j1-parity/perf-log4j12.baseline.txt";

    private static final String T10 = "T10";

    private static final String T10_CONFIG = "/perf-log4j12-async.xml";

    private static final String T10_BASELINE = "/log4j1-parity/perf-log4j12-async.baseline.txt";

    /**
     * Logger the comparison harness acquired for its superseded arm.
     * <p>
     * The harness gives all three logging generations the identical logger name on purpose, so that the arms remain
     * comparable. The name is therefore load-bearing and is carried character for character.
     * </p>
     */
    private static final String COMPARISON_LOGGER_NAME = "org.apache.logging.log4j.PerformanceComparison";

    private static final Level COMPARISON_LEVEL = Level.DEBUG;

    /** Message that arm emitted, reproduced exactly, including its terminating period. */
    private static final String COMPARISON_MESSAGE = "SEE IF THIS IS LOGGED 2.";

    /** Logger the asynchronous throughput-and-latency runner acquired for its superseded arm. */
    private static final String RUNNER_LOGGER_NAME = "org.apache.logging.log4j.core.async.perftest.RunLog4j1";

    /**
     * Level that runner emitted at.
     * <p>
     * It is {@code info}, which is the level of the runner's own latency loop — and, since every one of that
     * runner's emissions is at {@code info}, the only level any capture of it can carry. The peer corpus in the
     * benchmark module records this same runner, message and level for a fixture of its own, so the two corpora
     * agree; a divergence between them would mean one of the two had re-levelled a real event.
     * </p>
     */
    private static final Level RUNNER_LEVEL = Level.INFO;

    private static final String RUNNER_MESSAGE = "Short msg";

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

    /** Wall-clock timestamp rendered by the date token, in the default pattern of that token. */
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[,.]\\d{3}");

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

    private static final String PATH_TOKEN = "<PATH>";

    private static final String CONTEXT_NAME_PREFIX = "log4j1-parity-";

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

    /**
     * Boots one configuration in a logger context of its own, from its classpath location. The location is passed as
     * a non-null URI, which is what makes the context independent of every configuration system property, and the
     * context is named after the fixture so a leaked one is attributable.
     * <p>
     * The returned context is started and is the caller's to close; stopping it drains an asynchronous queue and
     * flushes a buffered writer. If the boot itself fails the partially started context is stopped here, because a
     * context that was never returned cannot be closed by anyone else.
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
     * Emits one scripted event through the supplied context. The logger is obtained from the context rather than the
     * static factory, so the event reaches the fixture under test and not whatever configuration happens to be
     * current. The level is passed explicitly rather than selected by a level-named method, keeping one emission path
     * for all three cases; that is unobservable here because no layout of these three renders a caller frame.
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
     * Substitutes the four non-deterministic or migration-dependent rendered elements, and nothing else. Every other
     * byte is compared literally, including level padding, logger-name abbreviation, every literal separator, the
     * double spaces an absent context-map key leaves behind and the trailing space one pattern places before the line
     * separator; nothing is trimmed, no run of spaces is collapsed and no line ending is rewritten.
     * <p>
     * <strong>Normalization is line-aware, and deliberately not a sequence of global replacements.</strong> A
     * timestamp is recognised only at the start of a line, and the thread and source-line tokens are substituted at
     * most once per line and only on a line carrying a timestamp, because the substituted regions are not
     * unambiguous in isolation: a thread name renders inside square brackets and a message may itself contain a
     * bracketed group, so a global replacement of that pattern would rewrite message text. Absolute paths carry no
     * such ambiguity and are replaced throughout, which is what lets a stack-trace continuation line be normalized
     * without being mistaken for an event.
     * </p>
     * <p>
     * Line structure survives exactly — the input is split keeping trailing empty fields and rejoined with the same
     * count, so whether the content ends with a separator is untouched — and each substitution is idempotent, an
     * already-normalized line being recognised by its leading token, which is what makes the normalizer safe to
     * apply to the committed baseline as well as to the capture.
     * </p>
     *
     * @param rendered rendered output, read whole and untrimmed
     * @return the normalized form
     */
    private static String normalize(final String rendered) {
        if (rendered.isEmpty()) {
            return rendered;
        }
        final String[] lines = rendered.split("\n", -1);
        final StringBuilder normalized = new StringBuilder(rendered.length());
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                normalized.append('\n');
            }
            normalized.append(normalizeLine(lines[index]));
        }
        return normalized.toString();
    }

    /**
     * Normalizes one rendered line, substituting the thread and source-line tokens only on a line that begins an
     * event.
     *
     * @param line one rendered line, without its separator
     * @return the normalized line
     */
    private static String normalizeLine(final String line) {
        String result = line;
        final Matcher timestamp = TIMESTAMP.matcher(result);
        final boolean timestamped;
        if (timestamp.lookingAt()) {
            result = TIMESTAMP_TOKEN + result.substring(timestamp.end());
            timestamped = true;
        } else {
            // An already-normalized line is recognised so that normalization stays idempotent.
            timestamped = result.startsWith(TIMESTAMP_TOKEN);
        }
        if (timestamped) {
            result = replaceFirst(result, BRACKETED_THREAD, THREAD_TOKEN);
            result = replaceFirst(result, SOURCE_LINE_NUMBER, SOURCE_LINE_TOKEN);
        }
        return replaceAll(result, ABSOLUTE_PATH, PATH_TOKEN);
    }

    /**
     * Replaces the first match of a pattern by index surgery.
     * <p>
     * Splicing by index rather than through a matcher's own replacement routine means the replacement text is taken
     * literally, with no escape or group-reference interpretation to guard against.
     * </p>
     *
     * @param input the text to substitute within
     * @param pattern the pattern to find
     * @param replacement the literal replacement text
     * @return the text with at most one substitution applied
     */
    private static String replaceFirst(final String input, final Pattern pattern, final String replacement) {
        final Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        return input.substring(0, matcher.start()) + replacement + input.substring(matcher.end());
    }

    /**
     * Replaces every match of a pattern by index surgery, scanning left to right without rescanning a token.
     *
     * @param input the text to substitute within
     * @param pattern the pattern to find
     * @param replacement the literal replacement text
     * @return the text with every match substituted
     */
    private static String replaceAll(final String input, final Pattern pattern, final String replacement) {
        final Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        final StringBuilder result = new StringBuilder(input.length());
        int copiedUpTo = 0;
        do {
            result.append(input, copiedUpTo, matcher.start()).append(replacement);
            copiedUpTo = matcher.end();
        } while (matcher.find());
        result.append(input, copiedUpTo, input.length());
        return result.toString();
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
