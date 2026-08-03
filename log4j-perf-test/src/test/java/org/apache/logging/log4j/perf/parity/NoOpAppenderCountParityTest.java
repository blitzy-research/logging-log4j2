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

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.CountingNoOpAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comparison gate for the counting no-op appender the two asynchronous fixtures bind: it asserts that
 * {@link CountingNoOpAppender} receives exactly the number of events recorded in the committed baseline.
 * <strong>Count identity is output identity for this component</strong> -- a counting no-op appender renders nothing,
 * both fixtures declare no layout and the plugin's factory hard-codes a null layout, so there is neither rendered
 * text to diff nor a conversion token to audit. Both fixtures are gated because they differ in the one dimension that
 * changes what the appender observes: whether their asynchronous wrapper captures caller location. What a count
 * cannot observe -- the wrapper, its queue depth, its blocking disposition and its location capture -- is recorded
 * per fixture in the sibling {@code log4j1-parity/effective-config.adoc}.
 */
class NoOpAppenderCountParityTest {

    /**
     * Classpath location of the recorded counts. A count that does not match is a defect in the translated configuration
     * and must never be adjusted here to make the comparison pass.
     */
    private static final String COUNT_BASELINE_RESOURCE = "/log4j1-parity/noOpAppender.count.baseline.txt";

    /**
     * Name both fixtures bind the counting appender to, character for character, and deliberately <em>not</em> the name
     * the Log4j 2 peer fixtures in the same directory use.
     */
    private static final String COUNTING_APPENDER_NAME = "NoOp";

    private static final String NO_LOCATION_CONFIG_ID = "T6";

    private static final String NO_LOCATION_CONFIG_RESOURCE = "/perf-log4j12-async-noOpAppender.xml";

    private static final String LOCATION_CONFIG_ID = "T7";

    private static final String LOCATION_CONFIG_RESOURCE = "/perf-log4j12-async-location-noOpAppender.xml";

    /**
     * Grammar of one recorded-count line, {@code <configId>=<count>}, anchored and admitting only the two identifiers
     * this gate covers followed by decimal digits, so a record that has been renamed or given a sign, separator, unit or
     * surrounding space is rejected rather than read as valid. Comments and blank lines never reach it, because
     * {@link ParityCorpus#dataLines(String)} drops them first.
     */
    private static final Pattern COUNT_RECORD = Pattern.compile("^(T[67])=([0-9]+)$");

    /**
     * Immutable index of the recorded counts, validated for record grammar, identity, order and agreement with the
     * scripted event cardinality, and never replaced, so no mutable state couples the cases together.
     */
    private static final Map<String, Integer> RECORDED_COUNTS = validated(readRecordedCounts());

    /**
     * Parses the committed baseline into an insertion-ordered index, preserving record order because it is part of the
     * contract and rejecting a repeated identifier rather than letting it overwrite silently.
     */
    private static Map<String, Integer> readRecordedCounts() {
        final List<String> records = ParityCorpus.dataLines(COUNT_BASELINE_RESOURCE);
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            final String record = records.get(recordIndex);
            final Matcher matcher = COUNT_RECORD.matcher(record);
            if (!matcher.matches()) {
                throw new IllegalStateException("malformed count record " + (recordIndex + 1) + " in "
                        + COUNT_BASELINE_RESOURCE + ": '" + record
                        + "'; a payload line must match " + COUNT_RECORD.pattern()
                        + ", and anything explanatory belongs in a '#' comment");
            }
            final String configId = matcher.group(1);
            if (counts.containsKey(configId)) {
                throw new IllegalStateException("duplicate configuration id " + configId + " at count record "
                        + (recordIndex + 1) + " in " + COUNT_BASELINE_RESOURCE);
            }
            counts.put(configId, Integer.valueOf(matcher.group(2)));
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Rejects a committed baseline whose records are not the ones the scripted events imply: the two fixtures this gate
     * covers, in that order, each carrying the number of events the committed script replays into it. Order is part of
     * the contract, because the fixtures differ in one dimension only, so accepting either order would equally accept a
     * baseline whose counts had been swapped -- exactly the shape a defect in the location-capturing path would take.
     * <strong>The counts are checked against a second, independent artefact rather than trusted:</strong> the committed
     * script's records for a fixture are exactly the events replayed into it, both fixtures admit every scripted level
     * and no filter stands between wrapper and counter, so the number of records the script holds for an id <em>is</em>
     * the number its appender must receive. An edit to either artefact alone therefore fails here.
     */
    private static Map<String, Integer> validated(final Map<String, Integer> recordedCounts) {
        final List<String> recordedIds = new ArrayList<>(recordedCounts.keySet());
        final List<String> expectedIds = new ArrayList<>();
        expectedIds.add(NO_LOCATION_CONFIG_ID);
        expectedIds.add(LOCATION_CONFIG_ID);
        if (!expectedIds.equals(recordedIds)) {
            throw new IllegalStateException(COUNT_BASELINE_RESOURCE + " must record exactly " + expectedIds
                    + ", in that order, but records " + recordedIds
                    + "; a reordered, renamed, repeated or added record is a drift between the two fixtures and never"
                    + " a baseline to adjust");
        }
        for (int index = 0; index < expectedIds.size(); index++) {
            final String configId = expectedIds.get(index);
            final int scripted = ParityCorpus.events(configId).size();
            final int recorded = recordedCounts.get(configId).intValue();
            if (scripted != recorded) {
                throw new IllegalStateException(COUNT_BASELINE_RESOURCE + " records " + recorded + " events for "
                        + configId + " while the committed event script replays " + scripted
                        + " into it; the two artefacts must agree, and the one to correct is whichever of them stopped"
                        + " describing what the superseded appender observed -- never this comparison");
            }
        }
        return recordedCounts;
    }

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
     * received exactly the recorded number of them. <strong>The ordering is load-bearing and must not be
     * rearranged.</strong> Stopping a context detaches its configuration before stopping it, so the appender must be
     * resolved while the context still runs and the reference held across the stop; stopping is also what drains the
     * wrapper's queue into the appender, so an earlier read of the count observes a queue still in flight. That ordering
     * is the only remedy for an undercount: waiting for the queue, relaxing the comparison, removing the wrapper or
     * editing the baseline would each turn a defect into a passing test.
     */
    private static void assertRecordedCountIsDelivered(final String configId, final String configResourcePath)
            throws URISyntaxException {
        final long recordedCount = recordedCount(configId);
        final CountingNoOpAppender countingAppender;
        try (LoggerContext context = ParityCorpus.startContext(configId, configResourcePath)) {
            countingAppender = countingAppender(context, configId, configResourcePath);
            ParityCorpus.replay(context, configId);
        }
        assertEquals(
                recordedCount,
                countingAppender.getCount(),
                configResourcePath + " delivered a different number of events to the appender named '"
                        + COUNTING_APPENDER_NAME + "' than the " + configId + " count recorded in "
                        + COUNT_BASELINE_RESOURCE
                        + "; that recording predates the deletion of the counter it was read from, so a mismatch is a"
                        + " defect in the migration and never a baseline to adjust");
    }

    /**
     * Resolves the counting appender by the exact name the fixture binds it to and by nothing else: the plugin is never
     * constructed directly, subclassed or read reflectively. Resolution yields an {@link Appender}, so the concrete type
     * is checked explicitly rather than left to the assignment, and a fixture that bound this name elsewhere reports what
     * it bound instead of failing with a bare cast error.
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
     * Returns the count recorded for one id, widened to the appender accessor's width; a missing id is reported by name
     * rather than allowed to unbox to a null-pointer failure.
     */
    private static long recordedCount(final String configId) {
        final Integer recorded = RECORDED_COUNTS.get(configId);
        if (recorded == null) {
            throw new IllegalStateException("no count recorded for " + configId + " in " + COUNT_BASELINE_RESOURCE
                    + "; recorded ids are " + RECORDED_COUNTS.keySet());
        }
        return recorded.longValue();
    }
}
