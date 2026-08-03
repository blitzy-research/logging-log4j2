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
 * <strong>Rendered bytes are the whole subject of this gate.</strong> What a capture cannot express — which
 * configuration was located, which appender it wired, which conversion pattern it was given, and how it buffers and
 * flushes — is evidence of a different kind, and it is held elsewhere: the sibling
 * {@code log4j1-parity/effective-config.adoc} records all of it per fixture from boot output observed under
 * {@code -Dlog4j2.debug}, which is the only place it can be observed rather than inferred. This class therefore
 * reads no value off a running configuration; it compares captures with committed baselines, and for the fixture
 * that suppresses every scripted event it compares emptiness against emptiness.
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

    /**
     * The three-line synchronous capture, one line per scripted event.
     * <p>
     * Every scripted event on this fixture renders a five-character level, so these three lines would render
     * identically through a bare {@code %p}: the rendered text alone cannot show that the level field keeps its
     * minimum width. That gap is not closed by inventing a shorter-levelled event no arm of the superseded
     * generation ever emitted; it is closed where the width actually lives, in the committed pattern text, whose
     * observed effective value the corpus's effective-configuration table records for this fixture.
     * </p>
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
     * The suppression fixture. Its root level admits neither scripted debug event, so nothing is rendered at all and
     * the capture is <em>byte-empty</em>.
     * <p>
     * Byte emptiness cannot, on its own, tell a preserved level filter from a dead fixture: a configuration that
     * never loaded, never resolved its appender reference or never opened its destination produces exactly the same
     * nothing. What this case owns is that the capture is empty and that the committed baseline is empty too — the
     * assertion below states both, so restoring output in either place fails immediately and by name. The companion
     * half of the evidence, that the fixture is alive and correctly wired, is the observed boot record in the
     * corpus's effective-configuration table for this fixture, which reports the configuration it was built from,
     * its root level {@code error}, and the started file appender its single reference resolves to. The emptiness
     * must never be replaced by an invented event admitted at {@code error}: rendering nothing is the fixture's
     * whole purpose.
     * </p>
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
     * The caller-location capture. Its single line is emitted from a fabricated caller identity, so the rendered
     * class and method are compared byte for byte while only the source line number is normalized — the single
     * tolerated output difference in the whole corpus, and the reason the class token's abbreviation option must
     * not be rewritten.
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
     * The throwable capture. The corpus assigns the scripted throwable a fixed stack trace, so the message line,
     * the exception line and every tab-indented frame are compared byte for byte — including whether the output
     * ends with a line separator, which is the evidence that settles how the implicitly appended throwable
     * converter behaves for this pattern.
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
     * The buffered, asynchronous capture. Stopping the context is what hands the queue over to be drained and the
     * buffered writer to be flushed, so the file is read only afterwards. The rendered line keeps its unpadded
     * level, the double space an absent context-map key leaves behind, and the trailing space the pattern places
     * before the line separator.
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
}
