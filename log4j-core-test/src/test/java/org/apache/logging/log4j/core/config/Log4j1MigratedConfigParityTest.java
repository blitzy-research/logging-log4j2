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
 * Rendered-output parity gate for this module's three translated logging configurations: {@code log4j12-perf.xml},
 * {@code perf-log4j12.xml} and {@code perf-log4j12-async.xml}. Each boots in its own isolated logger context, is
 * handed the one event its originating arm emitted, and the file it writes is compared with the capture committed
 * beside it in {@code /log4j1-parity/}. Those captures are oracles: <strong>a divergence is a defect in the
 * translated configuration</strong>, never a reason to widen the normalizer, relax an assertion or edit a baseline.
 * Rendered bytes are the whole subject, because two configurations can agree on every structural detail and still
 * differ through a padding width, a name-abbreviation strategy, a coupling between buffering and flushing, or a
 * separator that survives an empty context-map key.
 * <p>
 * The constants and normalizer are restated here rather than shared with the peer gate in the benchmark module,
 * because that module compiles at a higher release level and depending on it would close a build cycle; the two are
 * kept in agreement by review. No system property is set, cleared or read here: a non-null configuration location
 * bypasses property lookup, which lets three configurations boot in one virtual machine, under forked and randomly
 * ordered execution, without contending over a global key.
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
     * Logger name the comparison harness emits this fixture's event on, carried character for character: all three of
     * its arms share this one name, so it must not be rewritten.
     */
    private static final String COMPARISON_LOGGER_NAME = "org.apache.logging.log4j.PerformanceComparison";

    private static final Level COMPARISON_LEVEL = Level.DEBUG;

    /** Message that arm emitted, reproduced exactly, including its terminating period. */
    private static final String COMPARISON_MESSAGE = "SEE IF THIS IS LOGGED 2.";

    /** Logger the asynchronous throughput-and-latency runner acquired for its superseded arm. */
    private static final String RUNNER_LOGGER_NAME = "org.apache.logging.log4j.core.async.perftest.RunLog4j1";

    /**
     * Level the runner emits at, from the same source as the logger name and the message, never from whatever the
     * fixture's root level would admit.
     */
    private static final Level RUNNER_LEVEL = Level.INFO;

    private static final String RUNNER_MESSAGE = "Short msg";

    /** Destination of the padded-level fixture, under the build output directory the ignore rules already cover. */
    private static final Path TESTLOG4J_DESTINATION = Paths.get("target", "testlog4j.log");

    /**
     * Destination shared by the buffered fixture and its asynchronous sibling; module-relative, so neither the ignore
     * rules nor the licence-audit exclusions cover it and a leftover copy would fail the audit.
     */
    private static final Path PERFTEST_DESTINATION = Paths.get("perftest.log");

    /** Wall-clock timestamp rendered by the date token, in the default pattern of that token. */
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[,.]\\d{3}");

    private static final String TIMESTAMP_TOKEN = "<TS>";

    /**
     * Bracketed thread name; only the first occurrence on a timestamp-bearing line is replaced, because the thread
     * field precedes the message and a message could contain brackets of its own.
     */
    private static final Pattern BRACKETED_THREAD = Pattern.compile("\\[[^\\]\\n]*\\]");

    private static final String THREAD_TOKEN = "[<THREAD>]";

    /**
     * Source line number, anchored on the separator that follows it so the line numbers inside a rendered stack frame
     * stay out of reach. Inert for these fixtures, and retained so this normalizer stays identical to the peer
     * module's.
     */
    private static final Pattern SOURCE_LINE_NUMBER = Pattern.compile(":\\d+(?=  - )");

    private static final String SOURCE_LINE_TOKEN = ":<LINE>";

    /** Absolute filesystem path, matched only from a token boundary so a relative destination is never matched. */
    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("(?<![^\\s(\\[])(?:[A-Za-z]:[\\\\/]|/)[\\w.+~@%$-]+(?:[\\\\/][\\w.+~@%$-]+)*[\\\\/]?");

    private static final String PATH_TOKEN = "<PATH>";

    private static final String CONTEXT_NAME_PREFIX = "log4j1-parity-";

    /**
     * Removes both destinations before a fixture boots rather than afterwards: file managers are keyed by destination
     * name and reference-counted, so a stale file must be removed while no context holds its manager. Two fixtures
     * share one destination and all three truncate on open, but a removal depending on either fact would silently stop
     * protecting the comparison if a fixture's options were translated differently.
     */
    @BeforeEach
    void removeDestinationsBeforeBoot() throws IOException {
        removeDestinations();
    }

    /**
     * Removes both destinations once a capture has been read, so no case leaves a file for the next one or the audit.
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
     * The padded-level capture, whose layout pads the level to five characters and renders a context-map key this event
     * never populates, so the committed line carries a five-character level followed by the <em>double</em> space the
     * empty key leaves before the separator. Both are compared literally.
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
     * The buffered synchronous capture. Buffering and immediate flushing are independent options that both default to
     * on, so this fixture states the flush disposition explicitly, and stopping the context is what flushes the buffer.
     * Its layout renders an unpadded level, a double space where the empty context-map key sits, and a literal space
     * before the line separator, which is significant and compared like every other byte.
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
     * The asynchronous capture, reaching the same buffered appender through an asynchronous wrapper, so the event goes
     * to a background thread and the file stays empty until the context is stopped -- which drains the queue and then
     * flushes the buffer. The rendered bytes must equal the synchronous fixture's, because interposing the wrapper
     * changes delivery and nothing else.
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
     * Boots one configuration in a logger context of its own from its classpath location, passed as a non-null URI,
     * which is what makes the context independent of every configuration system property. The started context is the
     * caller's to close, and stopping it drains an asynchronous queue and flushes a buffered writer; a boot that fails
     * is stopped here, because a context that was never returned cannot be closed by anyone else.
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
     * Emits one scripted event through the supplied context. The logger comes from the context rather than the static
     * factory, so the event reaches the fixture under test and not whatever configuration is current, and the level is
     * passed explicitly, keeping one emission path for all three cases.
     */
    private static void emit(
            final LoggerContext context, final String loggerName, final Level level, final String message) {
        context.getLogger(loggerName).log(level, message);
    }

    /**
     * Compares one capture with its committed baseline through the idempotent normalizer, both sides read by identical
     * code. The file's presence is asserted first, so a fixture that never opened its destination is reported as such
     * rather than as a missing-file exception.
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
     * Substitutes exactly four rendered elements and nothing else. Every other byte is compared literally, including
     * level padding, logger-name abbreviation, every literal separator, the double spaces an absent context-map key
     * leaves behind and the trailing space one pattern places before the line separator; nothing is trimmed, no run of
     * spaces is collapsed and no line ending is rewritten. <strong>Normalization is line-aware, not a sequence of global
     * replacements:</strong> a timestamp is recognised only at the start of a line, and the thread and source-line
     * tokens are substituted at most once per line and only on a timestamp-bearing line, because a thread name renders
     * inside square brackets and a message may contain a bracketed group of its own. Absolute paths carry no such
     * ambiguity and are replaced throughout. Line structure survives exactly, the input being split keeping trailing
     * empty fields and rejoined with the same count, and each substitution is idempotent.
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
     * Normalizes one rendered line, substituting the thread and source-line tokens only where a timestamp anchors them.
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
     * Replaces the first match of a pattern by index surgery, because the regular-expression replacement methods
     * interpret the replacement string: a captured backslash or dollar sign in rendered output would be consumed or
     * raise an error.
     */
    private static String replaceFirst(final String input, final Pattern pattern, final String replacement) {
        final Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        return input.substring(0, matcher.start()) + replacement + input.substring(matcher.end());
    }

    /** Replaces every match of a pattern by index surgery, scanning left to right, for the same reason. */
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
     * Reads a classpath resource as text, decoding all bytes in one step with an explicit charset; reading by lines
     * would discard whether the content ends with a newline, which is part of what the comparison asserts.
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
     * Reads a file with the same decoding and newline discipline as the resource reader, so a capture and a baseline are
     * read by identical code.
     */
    private static String readFile(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
