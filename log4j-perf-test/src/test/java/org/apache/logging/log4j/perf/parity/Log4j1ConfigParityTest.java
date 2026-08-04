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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rendered-output parity gate for the five file-writing configurations of this module whose schema was translated in
 * place. Each case is independent: remove the destination, boot the fixture in its own logger context from its
 * classpath location, replay {@link ParityCorpus}'s fixed event script, <em>stop the context</em> so the writer
 * flushes and any queue drains, then read, normalize and compare. Nothing is read before the stop, because four
 * fixtures disable immediate flush and the fifth is both buffered and asynchronous, so an earlier read is a race.
 * <strong>Acceptance is an empty normalized diff</strong>, and a non-empty one is a defect in the translated
 * configuration -- never a reason to widen the normalizer, weaken an assertion, wait for output, or edit a baseline.
 * What a capture cannot express -- which configuration was located, which appender it wired, which pattern it was
 * given, how it buffers and flushes -- is held per fixture in the sibling {@code log4j1-parity/effective-config.adoc},
 * so this class reads no value off a running configuration.
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

    /**
     * Removes both destinations before every case. Four fixtures write to one shared file, none truncates and
     * append-on-open is the default, so a stale file would leave every capture after the first carrying the previous
     * case's lines, for only some of the random orders the run uses.
     */
    @BeforeEach
    void removeDestinationsBeforeBoot() throws IOException {
        removeDestinations();
    }

    /**
     * Removes both destinations after every case, passed or failed: the asynchronous fixture's destination is
     * module-relative and covered by neither the ignore rules nor the licence exclusions, so a leftover copy fails the
     * audit.
     */
    @AfterEach
    void removeDestinationsAfterCapture() throws IOException {
        removeDestinations();
    }

    private static void removeDestinations() throws IOException {
        ParityCorpus.deleteDestination(ParityCorpus.TESTLOG4J_DESTINATION);
        ParityCorpus.deleteDestination(ParityCorpus.PERFTEST_DESTINATION);
    }

    /**
     * The three-line synchronous capture. Every scripted event here renders a five-character level, so these lines would
     * render identically through a bare {@code %p}: the width is verified in the committed pattern text instead.
     */
    @Test
    @DisplayName("log4j12-perf.xml renders its committed baseline byte for byte")
    void log4j12PerfRendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T1, T1_CONFIG)) {
            ParityCorpus.replay(context, T1);
        }
        assertRenderedParity(T1, T1_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The suppression fixture: its root level admits neither scripted debug event, so the capture is
     * <em>byte-empty</em>. Emptiness alone cannot tell a preserved level filter from a dead fixture, so this case owns
     * that both capture and baseline are empty, while the observed boot record shows the fixture is alive and wired.
     */
    @Test
    @DisplayName("log4j12-perf2.xml suppresses both scripted debug events and renders a byte-empty capture")
    void log4j12Perf2RendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T2, T2_CONFIG)) {
            ParityCorpus.replay(context, T2);
        }
        assertRenderedParity(T2, T2_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
        assertByteEmpty(T2, T2_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The caller-location capture, emitted from a fabricated caller identity, so the rendered class and method are
     * compared byte for byte while only the source line number is normalized -- the single tolerated output difference
     * in the corpus, and the reason the class token's abbreviation option must not be rewritten.
     */
    @Test
    @DisplayName("log4j12-perfloc.xml renders its committed baseline byte for byte, caller class and method included")
    void log4j12PerflocRendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T3, T3_CONFIG)) {
            ParityCorpus.replay(context, T3);
        }
        assertRenderedParity(T3, T3_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The throwable capture, whose throwable carries a fixed stack trace, so the message line, the exception line and
     * every tab-indented frame are compared byte for byte -- including whether the output ends with a line separator,
     * which settles how the implicitly appended throwable converter behaves here.
     */
    @Test
    @DisplayName("log4j12-perf-file-throwable.xml renders its committed baseline byte for byte, throwable included")
    void log4j12PerfFileThrowableRendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T4, T4_CONFIG)) {
            ParityCorpus.replay(context, T4);
        }
        assertRenderedParity(T4, T4_BASELINE, ParityCorpus.TESTLOG4J_DESTINATION);
    }

    /**
     * The buffered, asynchronous capture. Stopping the context drains the queue and flushes the writer, so the file is
     * read only afterwards, and the line keeps its unpadded level, its double space and the trailing space the pattern
     * places before the line separator.
     */
    @Test
    @DisplayName("perf-log4j12-async.xml renders its committed baseline byte for byte through its async wrapper")
    void perfLog4j12AsyncRendersItsBaseline() throws Exception {
        try (LoggerContext context = ParityCorpus.startContext(T5, T5_CONFIG)) {
            ParityCorpus.replay(context, T5);
        }
        assertRenderedParity(T5, T5_BASELINE, ParityCorpus.PERFTEST_DESTINATION);
    }

    /**
     * Compares one capture with its committed baseline through the corpus's idempotent normalizer, both sides read by
     * identical code. The file's presence is asserted first, so a fixture that never opened its destination is reported
     * as such rather than as a missing-file exception.
     */
    private void assertRenderedParity(final String configId, final String baselineResource, final Path destination)
            throws IOException {
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
     * Asserts that a suppression fixture rendered nothing, on both sides: the normalized comparison would keep passing
     * if a line were added to the baseline and then rendered.
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
}
