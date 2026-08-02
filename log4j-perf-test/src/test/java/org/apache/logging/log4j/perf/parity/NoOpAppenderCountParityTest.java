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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.CountingNoOpAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comparison gate for the custom counting no-op appender of the two asynchronous fixtures, now bound to
 * {@link CountingNoOpAppender}: it asserts that the appender receives exactly the number of events recorded in the
 * committed baseline.
 * <p>
 * <strong>Count identity is output identity for this component.</strong> A counting no-op appender renders nothing —
 * both fixtures declare no layout and the plugin's factory hard-codes a null layout — so there is no rendered text
 * to diff and no conversion token to audit, and the byte-identity criterion that applies to a component producing
 * text has no subject here.
 * </p>
 * <p>
 * Both fixtures are gated because they differ in the one dimension that changes what the appender observes: whether
 * their asynchronous wrapper captures caller location. Each is an independent case with its own isolated context,
 * and the expected counts come from the committed baseline rather than from literals in this code.
 * </p>
 */
class NoOpAppenderCountParityTest {

    /**
     * Classpath location of the recorded counts, which are the authoritative oracle for both fixtures. A count that
     * does not match is a defect in the migration and must never be adjusted here to make the comparison pass.
     */
    private static final String COUNT_BASELINE_RESOURCE = "/log4j1-parity/noOpAppender.count.baseline.txt";

    /**
     * Name both fixtures bind the counting appender to, character for character. It is deliberately <em>not</em> the
     * name the Log4j 2 peer fixtures in the same directory use, so the two must not be conflated.
     */
    private static final String COUNTING_APPENDER_NAME = "NoOp";

    private static final String NO_LOCATION_CONFIG_ID = "T6";

    private static final String NO_LOCATION_CONFIG_RESOURCE = "/perf-log4j12-async-noOpAppender.xml";

    private static final String LOCATION_CONFIG_ID = "T7";

    private static final String LOCATION_CONFIG_RESOURCE = "/perf-log4j12-async-location-noOpAppender.xml";

    /** Root level both fixtures declare, which admits the scripted level. */
    private static final Level ROOT_LEVEL = Level.DEBUG;

    /** Name both fixtures bind the asynchronous wrapper to, upper case as the translated fixtures spell it. */
    private static final String ASYNC_APPENDER_NAME = "ASYNC";

    /** Queue depth both fixtures declare on that wrapper, held verbatim from the configurations translated. */
    private static final int ASYNC_QUEUE_CAPACITY = 262144;

    /**
     * Immutable index of the recorded counts, built once from the committed baseline by the shared harness and
     * validated on the way in.
     * <p>
     * The grammar, the comment and blank-line skipping rule and the duplicate-id rejection all live in
     * {@link ParityCorpus#readRecordedCounts(String)}, so this gate and the event-script gate cannot disagree about
     * what a record is. The map is unmodifiable, insertion-ordered and never replaced, so this class holds no mutable
     * state that could couple its cases together under forked, randomly ordered execution.
     * </p>
     */
    private static final Map<String, Integer> RECORDED_COUNTS =
            validated(ParityCorpus.readRecordedCounts(COUNT_BASELINE_RESOURCE));

    /**
     * Rejects a committed baseline that does not record exactly the two fixtures this gate covers, in exactly that
     * order.
     * <p>
     * Order is part of the contract rather than a presentation detail. The two fixtures differ in one dimension only,
     * whether their asynchronous wrapper captures caller location, so accepting the records in either order would
     * equally accept a baseline whose two counts had been swapped — and swapped counts are exactly the shape a defect
     * in the location-capturing path would take. This is a precondition on the harness's own input, not a case of its
     * own: a malformed baseline must fail every case that reads it rather than one case dedicated to the file.
     * </p>
     *
     * @param recordedCounts the records the harness parsed out of the committed baseline
     * @return the same records, once their identity and order are established
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
     * received exactly the recorded number of them.
     * <p>
     * <strong>The ordering here is load-bearing and must not be rearranged.</strong> Stopping a context detaches
     * its configuration before stopping it, so the appender must be resolved while the context still runs and the
     * reference held across the stop; asking for it afterwards yields nothing. Stopping is also what drains the
     * asynchronous wrapper's queue into the appender, so the count must be read after the stop — read any earlier,
     * it observes a queue still in flight.
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
            countingAppender = countingAppender(context, configId, configResourcePath);
            assertAsynchronousNoOpWiring(
                    context.getConfiguration(), configResourcePath, LOCATION_CONFIG_ID.equals(configId));
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
     * Asserts the wiring the counting appender sits behind: the root logger, the asynchronous wrapper's preserved
     * disposition, and the wrapper's sole delegate.
     * <p>
     * These are the dimensions a count cannot observe. A fixture that dropped its wrapper, discarded on a full queue
     * instead of blocking, shrank the queue, or gained or lost caller location would still deliver the same number
     * of events to the same appender. Location capture is the one dimension in which the two fixtures differ, so it
     * is asserted in both polarities. The counting appender's absent layout is asserted too, because it is what
     * makes count identity the whole of output identity for this component.
     * </p>
     *
     * @param configuration the started configuration of the booted fixture
     * @param configResourcePath the fixture, named in failure messages
     * @param expectedIncludeLocation whether this fixture's wrapper captures caller location
     */
    private static void assertAsynchronousNoOpWiring(
            final Configuration configuration, final String configResourcePath, final boolean expectedIncludeLocation) {
        // The very first assertion is that the configuration was built from the resource this case named. The two
        // fixtures differ in one attribute and bind the same appender name to the same counting plugin, so a case that
        // booted its sibling - or that silently fell back to some other configuration altogether - would satisfy every
        // remaining assertion below except the polarity of location capture, and would deliver an identical count.
        // Identity of the source is therefore established before anything else is read off the configuration.
        ParityCorpus.assertBuiltFromResource(configuration, configResourcePath);
        final LoggerConfig rootLogger = configuration.getRootLogger();
        assertNotNull(rootLogger, configResourcePath + " built no root logger");
        assertEquals(ROOT_LEVEL, rootLogger.getLevel(), "level of the root logger of " + configResourcePath);
        assertTrue(rootLogger.isAdditive(), "additivity of the root logger of " + configResourcePath);
        final List<AppenderRef> references = rootLogger.getAppenderRefs();
        assertEquals(1, references.size(), "number of appender references on the root logger of " + configResourcePath);
        assertEquals(
                ASYNC_APPENDER_NAME,
                references.get(0).getRef(),
                "appender reference on the root logger of " + configResourcePath);
        final Appender wrapper = configuration.getAppender(ASYNC_APPENDER_NAME);
        assertNotNull(wrapper, configResourcePath + " declares no appender named " + ASYNC_APPENDER_NAME);
        assertTrue(
                wrapper instanceof AsyncAppender,
                configResourcePath + " binds " + ASYNC_APPENDER_NAME + " to "
                        + wrapper.getClass().getName()
                        + " rather than to an asynchronous appender, so the fixture's asynchronous disposition was"
                        + " not preserved");
        final AsyncAppender asyncAppender = (AsyncAppender) wrapper;
        assertTrue(
                asyncAppender.isStarted(),
                "appender " + ASYNC_APPENDER_NAME + " of " + configResourcePath + " did not start");
        assertTrue(
                asyncAppender.isBlocking(),
                "appender " + ASYNC_APPENDER_NAME + " of " + configResourcePath + " must block when its queue is"
                        + " full, as its superseded form did, rather than discard events");
        assertEquals(
                ASYNC_QUEUE_CAPACITY,
                asyncAppender.getQueueCapacity(),
                "queue depth of appender " + ASYNC_APPENDER_NAME + " of " + configResourcePath
                        + ", which is held verbatim");
        final String locationMessage = "caller-location capture on appender " + ASYNC_APPENDER_NAME + " of "
                + configResourcePath + ", which is the one dimension in which the two no-op fixtures differ";
        if (expectedIncludeLocation) {
            assertTrue(asyncAppender.isIncludeLocation(), locationMessage);
        } else {
            assertFalse(asyncAppender.isIncludeLocation(), locationMessage);
        }
        final String[] delegates = asyncAppender.getAppenderRefStrings();
        assertEquals(
                1,
                delegates.length,
                "number of appenders " + ASYNC_APPENDER_NAME + " of " + configResourcePath + " delegates to");
        assertEquals(
                COUNTING_APPENDER_NAME,
                delegates[0],
                "appender " + ASYNC_APPENDER_NAME + " of " + configResourcePath + " delegates to");
        assertNull(
                configuration.getAppender(COUNTING_APPENDER_NAME).getLayout(),
                "the counting appender of " + configResourcePath + " must carry no layout, which is why its count is"
                        + " the whole of its output");
    }

    /**
     * Resolves the counting appender from a running configuration, by the exact name the fixture binds it to and by
     * nothing else: the plugin is never constructed directly, subclassed or read reflectively. The concrete type is
     * checked explicitly rather than left to the assignment, so a fixture that bound this name to some other
     * appender reports what it bound instead of failing with a bare cast error.
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
     * Returns the count recorded for one configuration id, widened to the width the appender's accessor reports. A
     * missing id is reported by name rather than allowed to unbox to a null-pointer failure, so a baseline that
     * stopped covering one of the two fixtures says so.
     *
     * @throws IllegalStateException if the committed baseline carries no count for that id
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
