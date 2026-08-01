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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.CountingNoOpAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comparison gate for the one custom logging component this module used to carry: a no-op appender that did nothing
 * but count the events handed to it. Its replacement is {@link CountingNoOpAppender}, a plugin Core already ships,
 * and this class proves the replacement receives exactly the events the superseded component was recorded receiving.
 * <p>
 * <strong>Count identity is output identity for this component.</strong> A counting no-op appender renders nothing:
 * both fixtures declare no layout at all and the replacement's factory hard-codes a null layout, so there is no
 * rendered text to diff and no conversion token to audit. The byte-identity criterion that would apply to a
 * component producing text has no subject here, because no custom layout or filter survives outside the retained
 * compatibility artifact.
 * </p>
 * <p>
 * Both fixtures are gated, because they differ in the one dimension that changes what the component observes: one
 * captures caller location on its asynchronous wrapper and the other does not. Each is an independent case with its
 * own isolated context, and the counts come from the committed baseline in the parity corpus rather than from
 * literals in this code. The effective-configuration evidence, and the record of the test-infrastructure
 * adaptations this migration made, live in the {@code effective-config.adoc} beside that baseline.
 * </p>
 */
class NoOpAppenderCountParityTest {

    /**
     * Classpath location of the recorded counts.
     * <p>
     * The capture behind this file cannot be repeated: the counter it was read from ceased to exist when the
     * superseded component was deleted. The file is therefore an oracle, never a knob.
     * </p>
     */
    private static final String COUNT_BASELINE_RESOURCE = "/log4j1-parity/noOpAppender.count.baseline.txt";

    /**
     * Name both fixtures bind the counting appender to.
     * <p>
     * It is deliberately not the name used by the Log4j 2 peer fixtures sitting in the same directory. This name was
     * carried over character for character from the configurations being translated, along with the appender
     * reference that selects it, so that the translation changes the schema and nothing else.
     * </p>
     */
    private static final String COUNTING_APPENDER_NAME = "NoOp";

    /** Configuration id of the asynchronous no-op fixture that does <em>not</em> capture caller location. */
    private static final String NO_LOCATION_CONFIG_ID = "T6";

    /** Classpath location of that fixture. Its name and location are unchanged by the translation. */
    private static final String NO_LOCATION_CONFIG_RESOURCE = "/perf-log4j12-async-noOpAppender.xml";

    /** Configuration id of the asynchronous no-op fixture that <em>does</em> capture caller location. */
    private static final String LOCATION_CONFIG_ID = "T7";

    /** Classpath location of that fixture. Its name and location are unchanged by the translation. */
    private static final String LOCATION_CONFIG_RESOURCE = "/perf-log4j12-async-location-noOpAppender.xml";

    /**
     * Grammar of a baseline data line: {@code <configId>=<count>}, with no space around the separator and a
     * non-negative decimal count carrying no sign, separator or suffix.
     * <p>
     * A full match is required rather than a search, and the id alphabet is exactly the two ids this gate covers, so
     * a line that has drifted is rejected instead of being partially understood. Together with the duplicate check
     * and the two presence checks in {@link #loadRecordedCounts()}, this is what establishes that the baseline holds
     * exactly two data lines: every data line is one of the two ids, neither may repeat, and both must appear.
     * </p>
     */
    private static final Pattern RECORDED_COUNT_LINE = Pattern.compile("^(T[67])=([0-9]+)$");

    /**
     * Immutable index of the recorded counts, built once from the committed baseline.
     * <p>
     * The map is unmodifiable and is never replaced, so this class holds no mutable state that could couple its two
     * cases together under forked, randomly ordered execution.
     * </p>
     */
    private static final Map<String, Long> RECORDED_COUNTS = loadRecordedCounts();

    @Test
    @DisplayName("perf-log4j12-async-noOpAppender.xml delivers the recorded event count")
    void asyncNoOpFixtureDeliversRecordedEventCount() throws URISyntaxException {
        assertRecordedCountIsDelivered(NO_LOCATION_CONFIG_ID, NO_LOCATION_CONFIG_RESOURCE);
    }

    @Test
    @DisplayName("perf-log4j12-async-location-noOpAppender.xml delivers the recorded event count")
    void asyncLocationNoOpFixtureDeliversRecordedEventCount() throws URISyntaxException {
        assertRecordedCountIsDelivered(LOCATION_CONFIG_ID, LOCATION_CONFIG_RESOURCE);
    }

    /**
     * Boots one fixture in its own context, replays that fixture's scripted events and asserts the counting appender
     * received exactly the recorded number of them.
     * <p>
     * <strong>The order of the four steps is load-bearing and must not be rearranged.</strong> Stopping a context
     * detaches its configuration before stopping it, so the appender has to be resolved while the context is still
     * running and the reference held across the stop; asking for it afterwards yields nothing. Stopping is also what
     * drains the asynchronous wrapper's queue into the appender — the wrapper's dispatcher is signalled, processes
     * whatever is left and is joined without a deadline, and the configuration stops asynchronous appenders ahead of
     * the appenders they feed — so the count has to be read after the stop. Read any earlier, it observes a queue
     * still in flight.
     * </p>
     * <p>
     * The remedy for an undercount is therefore this ordering and nothing else. Waiting for the queue, relaxing the
     * comparison, removing the asynchronous wrapper or editing the committed baseline would each turn a defect in
     * the migration into a passing test.
     * </p>
     *
     * @param configId the configuration id, which selects both the recorded count and the scripted events
     * @param configResourcePath absolute classpath path of the fixture, unchanged by the translation
     * @throws URISyntaxException if the fixture resolves but cannot be expressed as a URI
     */
    private static void assertRecordedCountIsDelivered(final String configId, final String configResourcePath)
            throws URISyntaxException {
        final long recordedCount = recordedCount(configId);
        final CountingNoOpAppender countingAppender;
        try (LoggerContext context = ParityCorpus.startContext(configId, configResourcePath)) {
            // 1. Resolve the appender while the configuration is still attached, and keep the reference.
            countingAppender = countingAppender(context, configId, configResourcePath);
            // 2. Replay exactly one fresh pass of this fixture's scripted events.
            ParityCorpus.replay(context, configId);
            // 3. Closing stops the context, which drains the asynchronous queue into the appender.
        }
        // 4. Only now is the counter complete.
        assertEquals(
                recordedCount,
                countingAppender.getCount(),
                configResourcePath + " delivered a different number of events to the appender named '"
                        + COUNTING_APPENDER_NAME + "' than the " + configId + " count recorded in "
                        + COUNT_BASELINE_RESOURCE
                        + "; that recording cannot be retaken, so a mismatch is a defect in the migration and never a"
                        + " baseline to adjust");
    }

    /**
     * Resolves the counting appender from a running configuration, by the name the fixture binds it to.
     * <p>
     * Resolution is by name and by name only. The superseded component published its counter as a field whereas the
     * replacement publishes an instance accessor, and that difference is bridged here purely by asking the
     * configuration for the appender and calling the accessor: the plugin is never constructed directly, never
     * subclassed, never read reflectively, and no adapter stands between the two shapes.
     * </p>
     * <p>
     * The type is checked explicitly rather than left to the assignment, so a fixture that bound this name to some
     * other appender reports what it bound instead of failing with a bare cast error.
     * </p>
     */
    private static CountingNoOpAppender countingAppender(
            final LoggerContext context, final String configId, final String configResourcePath) {
        final Appender declared = context.getConfiguration().getAppender(COUNTING_APPENDER_NAME);
        assertNotNull(
                declared,
                configResourcePath + " declares no appender named '" + COUNTING_APPENDER_NAME + "'; the " + configId
                        + " fixture must bind the counting no-op plugin to exactly that name");
        assertTrue(
                declared instanceof CountingNoOpAppender,
                configResourcePath + " binds the name '" + COUNTING_APPENDER_NAME + "' to "
                        + declared.getClass().getName() + " rather than to " + CountingNoOpAppender.class.getName()
                        + ", so the events it receives cannot be counted");
        return (CountingNoOpAppender) declared;
    }

    /**
     * Returns the count recorded for one configuration id.
     *
     * @throws IllegalStateException if the committed baseline carries no count for that id
     */
    private static long recordedCount(final String configId) {
        final Long recorded = RECORDED_COUNTS.get(configId);
        if (recorded == null) {
            throw new IllegalStateException("no count recorded for " + configId + " in " + COUNT_BASELINE_RESOURCE
                    + "; recorded ids are " + RECORDED_COUNTS.keySet());
        }
        return recorded.longValue();
    }

    /**
     * Parses the committed baseline into an immutable index.
     * <p>
     * Lines whose first non-whitespace character is {@code #} are comments and blank lines are permitted; both are
     * skipped. Every remaining line must match {@link #RECORDED_COUNT_LINE} in full, no id may repeat, and both ids
     * this gate covers must be present. A baseline that is missing, unreadable or malformed is a defect in the
     * corpus or in the build rather than an I/O condition to absorb, so it is reported as an
     * {@link IllegalStateException} naming the resource and the offending line, which keeps the diagnosis intact
     * even though this loader runs during class initialization.
     * </p>
     * <p>
     * The whole resource is decoded in one step with an explicit charset by the shared harness, and it is split with
     * a negative limit and no trimming, so the trailing empty segment left by the final line feed is simply skipped
     * as a blank line.
     * </p>
     */
    private static Map<String, Long> loadRecordedCounts() {
        final String baseline;
        try {
            baseline = ParityCorpus.readResource(COUNT_BASELINE_RESOURCE);
        } catch (final IOException readFailure) {
            throw new IllegalStateException("cannot read the recorded counts " + COUNT_BASELINE_RESOURCE, readFailure);
        }
        final Map<String, Long> counts = new LinkedHashMap<>();
        final String[] lines = baseline.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            final String line = lines[lineIndex];
            if (isSkippable(line)) {
                continue;
            }
            final int lineNumber = lineIndex + 1;
            final Matcher matcher = RECORDED_COUNT_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "malformed data line " + lineNumber + " in " + COUNT_BASELINE_RESOURCE + ": " + line);
            }
            final String configId = matcher.group(1);
            final Long duplicated = counts.put(configId, Long.valueOf(parseCount(matcher.group(2), lineNumber)));
            if (duplicated != null) {
                throw new IllegalStateException("duplicate count for " + configId + " on line " + lineNumber + " of "
                        + COUNT_BASELINE_RESOURCE + "; it was already recorded as " + duplicated);
            }
        }
        requireRecordedCount(counts, NO_LOCATION_CONFIG_ID);
        requireRecordedCount(counts, LOCATION_CONFIG_ID);
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Parses one recorded count, which is compared as a {@code long} because that is the width the appender's
     * accessor reports.
     * <p>
     * The grammar admits digits only, so the single remaining failure is a value too wide for that counter; it is
     * reported with the resource and the line rather than as a bare parse failure.
     * </p>
     */
    private static long parseCount(final String digits, final int lineNumber) {
        try {
            return Long.parseLong(digits);
        } catch (final NumberFormatException tooWide) {
            throw new IllegalStateException(
                    "the count on line " + lineNumber + " of " + COUNT_BASELINE_RESOURCE
                            + " does not fit a 64-bit counter: " + digits,
                    tooWide);
        }
    }

    /** Fails loudly when the committed baseline carries no count for an id this gate covers. */
    private static void requireRecordedCount(final Map<String, Long> counts, final String configId) {
        if (!counts.containsKey(configId)) {
            throw new IllegalStateException("missing count for " + configId + " in " + COUNT_BASELINE_RESOURCE
                    + "; the baseline records " + counts.keySet());
        }
    }

    /**
     * Returns {@code true} for a comment or a blank line. A comment is recognised by its first non-whitespace
     * character, so an indented comment is skipped too.
     */
    private static boolean isSkippable(final String line) {
        for (int index = 0; index < line.length(); index++) {
            final char character = line.charAt(index);
            if (!Character.isWhitespace(character)) {
                return character == '#';
            }
        }
        return true;
    }
}
