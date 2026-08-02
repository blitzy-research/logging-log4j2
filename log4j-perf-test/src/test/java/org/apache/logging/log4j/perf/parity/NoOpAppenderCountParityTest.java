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

import java.io.IOException;
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

    /**
     * Logger name every event scripted for the fixture without location capture carries.
     * <p>
     * Stated here rather than read from the script, so that the identity assertion below is an independent
     * expectation and not a restatement of the value it checks. The benchmark that selects this fixture acquires its
     * logger from the class of its own state object, and the harness instantiates a generated subclass of that
     * state class, so the name resolved at runtime lies in the generated subpackage and carries the generated-state
     * suffix. That resolved name is what this fixture's events are emitted under, and it is what is pinned here.
     * </p>
     */
    private static final String NO_LOCATION_LOGGER_NAME =
            "org.apache.logging.log4j.perf.jmh.jmh_generated.AsyncAppenderLog4j1Benchmark_jmhType";

    /** Logger name every event scripted for the location-capturing fixture carries, resolved the same way. */
    private static final String LOCATION_LOGGER_NAME =
            "org.apache.logging.log4j.perf.jmh.jmh_generated.AsyncAppenderLog4j1LocationBenchmark_jmhType";

    /** Level both fixtures' events are emitted at, exactly as their benchmarks emit them. */
    private static final Level SCRIPTED_LEVEL = Level.INFO;

    /** Message of the fixed-string arm, which opens the first fixture's script and is the second fixture's only event. */
    private static final String FIXED_STRING_MESSAGE = "aaaaaaaaaaaaaaaa";

    /**
     * The eleven concatenating arms' already-rendered messages, in the order the arms declare them.
     * <p>
     * A count cannot see a message, so a fixture that delivered eleven other strings — or the same eleven in another
     * order — would satisfy a count-only gate. They are stated here in their concatenated form deliberately: the
     * parameterized form exists as a separate benchmark, so rewriting these into placeholders would erase the very
     * comparison the concatenating arms exist to make.
     * </p>
     */
    private static final String[] CONCATENATED_MESSAGES = {
        "p1=1",
        "p1=1, p2=2",
        "p1=1, p2=2, p3=3",
        "p1=1, p2=2, p3=3, p4=4",
        "p1=1, p2=2, p3=3, p4=4, p5=5",
        "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6",
        "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7",
        "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7, p8=8",
        "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7, p8=8, p9=9",
        "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7, p8=8, p9=9, p10=10",
        "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7, p8=8, p9=9, p10=10, p11=11",
    };

    /** Root level both fixtures declare, which admits the scripted level. */
    private static final Level ROOT_LEVEL = Level.DEBUG;

    /** Name both fixtures bind the asynchronous wrapper to, upper case as the translated fixtures spell it. */
    private static final String ASYNC_APPENDER_NAME = "ASYNC";

    /** Queue depth both fixtures declare on that wrapper, held verbatim from the configurations translated. */
    private static final int ASYNC_QUEUE_CAPACITY = 262144;

    /**
     * Immutable index of the recorded counts, built once from the committed baseline by the shared harness.
     * <p>
     * The grammar, the comment and blank-line skipping rule and the duplicate-id rejection all live in
     * {@link ParityCorpus#readRecordedCounts(String)}, so this gate and the event-script gate cannot disagree about
     * what a record is. The map is unmodifiable, insertion-ordered and never replaced, so this class holds no mutable
     * state that could couple its cases together under forked, randomly ordered execution — and the order the
     * baseline recorded its two fixtures in survives into {@link #recordedCountsAreExactlyTheTwoFixturesInOrder()},
     * which is where it is asserted.
     * </p>
     */
    private static final Map<String, Integer> RECORDED_COUNTS =
            ParityCorpus.readRecordedCounts(COUNT_BASELINE_RESOURCE);

    /**
     * Asserts that the committed baseline records exactly the two fixtures this gate covers, in exactly that order,
     * each carrying exactly the number of events the fixed event script assigns it.
     * <p>
     * Order is part of the contract rather than a presentation detail. The two fixtures differ in one dimension only,
     * whether their asynchronous wrapper captures caller location, so a reader that accepted the records in either
     * order would equally accept a baseline whose two counts had been swapped — and swapped counts are exactly the
     * shape a defect in the location-capturing path would take. Comparing the whole ordered record sequence in one
     * step therefore covers the order, both counts, the absence of any third record and the absence of a repeat.
     * </p>
     * <p>
     * The expected counts are derived from the event script rather than written here as literals, which makes this
     * assertion strictly stronger than a pair of hard-coded numbers: it fails both when the baseline drifts from the
     * script and when the script drifts from the baseline, and the script is itself pinned record for record by the
     * shared harness's manifest.
     * </p>
     * <p>
     * The fixture's <em>raw</em> bytes are asserted as well, and not merely its records. This fixture is documented
     * as pure payload — two records and nothing else — and the record reader deliberately drops comment and blank
     * lines, so on records alone an inserted comment, a leading blank line, a missing final line separator or a
     * surplus trailing one would all pass unnoticed while contradicting that documented form. The expected raw text
     * is assembled from the very same derived records, so this second assertion adds a form constraint without
     * introducing a competing statement of the counts.
     * </p>
     *
     * @throws IOException if the committed fixture cannot be read
     */
    @Test
    @DisplayName("noOpAppender.count.baseline.txt records exactly T6 then T7, each matching its scripted event count")
    void recordedCountsAreExactlyTheTwoFixturesInOrder() throws IOException {
        final List<String> expected = new ArrayList<>();
        expected.add(NO_LOCATION_CONFIG_ID
                + '='
                + ParityCorpus.events(NO_LOCATION_CONFIG_ID).size());
        expected.add(LOCATION_CONFIG_ID
                + '='
                + ParityCorpus.events(LOCATION_CONFIG_ID).size());
        assertEquals(
                expected,
                ParityCorpus.dataLines(COUNT_BASELINE_RESOURCE),
                COUNT_BASELINE_RESOURCE + " must record exactly these two counts, in this order, matching the events"
                        + " the fixed event script assigns to " + NO_LOCATION_CONFIG_ID + " and "
                        + LOCATION_CONFIG_ID
                        + "; a reordered, renamed, repeated, added or re-valued record is a drift between the two"
                        + " fixtures and never a baseline to adjust");
        final StringBuilder expectedRawForm = new StringBuilder();
        for (final String record : expected) {
            expectedRawForm.append(record).append('\n');
        }
        assertEquals(
                expectedRawForm.toString(),
                ParityCorpus.readResource(COUNT_BASELINE_RESOURCE),
                COUNT_BASELINE_RESOURCE + " must be pure payload: exactly those two records, one per line, each"
                        + " terminated by a single line separator, with no comment, no blank line, no surplus"
                        + " separator and no other byte");
    }

    @Test
    @DisplayName("perf-log4j12-async-noOpAppender.xml delivers the recorded event count")
    void asyncNoOpFixtureDeliversRecordedEventCount() throws URISyntaxException {
        assertRecordedCountIsDelivered(NO_LOCATION_CONFIG_ID, NO_LOCATION_CONFIG_RESOURCE, NO_LOCATION_LOGGER_NAME);
    }

    @Test
    @DisplayName("perf-log4j12-async-location-noOpAppender.xml delivers the recorded event count")
    void asyncLocationNoOpFixtureDeliversRecordedEventCount() throws URISyntaxException {
        assertRecordedCountIsDelivered(LOCATION_CONFIG_ID, LOCATION_CONFIG_RESOURCE, LOCATION_LOGGER_NAME);
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
     * @param expectedLoggerName the logger name every event of this fixture must carry
     * @throws URISyntaxException if the fixture resolves but cannot be expressed as a URI
     */
    private static void assertRecordedCountIsDelivered(
            final String configId, final String configResourcePath, final String expectedLoggerName)
            throws URISyntaxException {
        final long recordedCount = recordedCount(configId);
        assertScriptedEventIdentity(configId, expectedLoggerName);
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
     * Asserts the identity of the events this fixture replays: every one of them on the expected logger, at the
     * expected level.
     * <p>
     * A count alone cannot see either dimension. Twelve events delivered on the wrong logger, or re-levelled so a
     * different filter admits them, produce exactly the recorded count and would pass a count-only gate. The
     * expected name is stated as a constant in this class rather than read back from the script, so this assertion
     * is an independent expectation; the script itself is pinned record for record by the shared harness's manifest.
     * </p>
     *
     * @param configId the configuration id whose scripted events are checked
     * @param expectedLoggerName the resolved logger name every one of those events must carry
     */
    private static void assertScriptedEventIdentity(final String configId, final String expectedLoggerName) {
        final List<ParityCorpus.Event> events = ParityCorpus.events(configId);
        assertFalse(events.isEmpty(), "the fixed event script assigns no events to " + configId);
        final String[] expectedMessages = expectedMessages(configId);
        assertEquals(
                expectedMessages.length,
                events.size(),
                "number of events the corpus scripts for " + configId
                        + "; this gate states the identity of every one of them, so the two must agree exactly");
        for (int index = 0; index < events.size(); index++) {
            final ParityCorpus.Event event = events.get(index);
            assertEquals(
                    expectedLoggerName,
                    event.loggerName(),
                    "logger name of scripted event " + (index + 1) + " of " + configId
                            + "; the name is carried character for character from what the acquisition site resolves"
                            + " at runtime and must not be shortened to the declared state class");
            assertEquals(
                    SCRIPTED_LEVEL,
                    event.level(),
                    "level of scripted event " + (index + 1) + " of " + configId
                            + "; the level is the one the arm emits and must not be re-levelled");
            assertEquals(
                    expectedMessages[index],
                    event.message(),
                    "message of scripted event " + (index + 1) + " of " + configId
                            + ", compared character for character in its already-rendered form");
            assertNull(
                    event.throwableSpec(),
                    "throwable of scripted event " + (index + 1) + " of " + configId
                            + "; neither counting fixture scripts a throwable, so none may appear here");
        }
    }

    /**
     * Returns the ordered messages of one fixture: the fixed-string arm followed by the eleven concatenating arms for
     * the fixture without location capture, and the fixed-string arm alone for the fixture with it.
     * <p>
     * The order is the order the arms declare, and it is stated here rather than read back from the script, so that
     * this gate is an independent expectation instead of a restatement of the file it checks.
     * </p>
     *
     * @param configId the configuration id whose scripted messages are expected
     * @return the expected messages, in order
     */
    private static String[] expectedMessages(final String configId) {
        if (LOCATION_CONFIG_ID.equals(configId)) {
            return new String[] {FIXED_STRING_MESSAGE};
        }
        if (!NO_LOCATION_CONFIG_ID.equals(configId)) {
            throw new IllegalStateException("this gate covers " + NO_LOCATION_CONFIG_ID + " and " + LOCATION_CONFIG_ID
                    + " only, but was asked for " + configId);
        }
        final String[] messages = new String[CONCATENATED_MESSAGES.length + 1];
        messages[0] = FIXED_STRING_MESSAGE;
        System.arraycopy(CONCATENATED_MESSAGES, 0, messages, 1, CONCATENATED_MESSAGES.length);
        return messages;
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
