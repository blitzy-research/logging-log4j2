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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rendered-output parity gate for the three logging fixtures owned by this module whose schema was translated from
 * the superseded Log4j 1.x generation to the Log4j 2 schema in place: {@code log4j12-perf.xml},
 * {@code perf-log4j12.xml} and {@code perf-log4j12-async.xml}.
 * <p>
 * The three fixtures keep their original file names and classpath locations, so every string literal and every
 * command line that names one of them stays valid; only the schema inside each file changed. This class is what
 * turns "the translation preserves behaviour" from a claim into a build gate, and it does so the only way that is
 * admissible evidence: by comparing the <em>bytes the appender wrote</em> against a capture taken from the
 * superseded generation before any dependency was removed. Nothing here reads a configuration attribute, inspects
 * an appender or a layout, or parses the translated XML — a translation that is inspected rather than executed can
 * agree letter for letter and still render differently.
 * </p>
 * <p>
 * Each case is independent and follows exactly one sequence:
 * </p>
 * <ol>
 *   <li>remove the destination the fixture is configured to write, so the capture cannot inherit stale lines;</li>
 *   <li>boot the fixture in its <em>own</em> logger context, from its classpath location;</li>
 *   <li>replay the fixed event script recorded for that fixture, under the logger names, levels and messages its
 *       real consumer uses;</li>
 *   <li><em>stop the context</em>, which drains any queue and flushes the writer;</li>
 *   <li>read the produced file, normalize it and compare it with the committed baseline;</li>
 *   <li>remove the destination again, whatever the outcome.</li>
 * </ol>
 * <p>
 * Step four is not optional and it is not a formality. All three fixtures disable immediate flush, two of them
 * additionally buffer their writer, and one hands its events to a background thread before they are ever rendered.
 * A read taken while the context still runs is a race rather than a result. Stopping the context stops the
 * asynchronous wrapper first, which drains its queue into the file appender, and then stops the file appender,
 * which flushes its encoder buffer. Waiting on a timer instead, relaxing immediate flush, shrinking a buffer or
 * replacing the asynchronous wrapper with a synchronous one would each make output appear sooner while destroying
 * the very disposition these fixtures exist to preserve — they serve a benchmark harness, which is a measurement
 * instrument — so none of those is done here and none may be introduced.
 * </p>
 * <p>
 * <strong>Acceptance is an empty normalized diff.</strong> The normalizer substitutes exactly four rendered
 * elements — timestamp, thread name, source line number and absolute filesystem path — and no fifth may ever be
 * added. Every other byte is compared literally: the level text <em>and its padding width</em>, the logger-name
 * abbreviation, every literal separator, and the rendering of an absent context-map key, which contributes nothing
 * and therefore leaves byte-significant double spaces in all three layouts and one significant trailing space in
 * two of them. Nothing is trimmed, right-stripped or whitespace-collapsed anywhere in this class. A non-empty diff
 * is a defect in the translated fixture; it is never a reason to widen the normalizer, weaken an assertion, wait
 * for output that has not arrived, or edit a committed capture.
 * </p>
 * <p>
 * The committed oracle is captured <em>text</em> rather than a live comparison between two logging generations,
 * because the superseded generation is gone from the dependency graph: the captures were taken while it was still
 * present, the capture harness was not committed, and no source file here refers to it. The captures, together
 * with the effective-configuration document that accompanies them under {@code src/test/resources/log4j1-parity},
 * are the whole of the evidence, and this class is their only consumer.
 * </p>
 *
 * @see <a href="https://logging.apache.org/log4j/2.x/manual/migration.html">Migrating from Log4j 1.x</a>
 */
class Log4j1MigratedConfigParityTest {

    // -----------------------------------------------------------------------------------------------------------
    // Fixture identity: the three translated configurations, their captures and their destinations
    // -----------------------------------------------------------------------------------------------------------

    /** Identifier of the synchronous fixture whose layout renders a five-character level field. */
    private static final String T8 = "T8";

    /** Identifier of the synchronous, buffered fixture. */
    private static final String T9 = "T9";

    /** Identifier of the asynchronous fixture, which wraps T9's file appender in a queue. */
    private static final String T10 = "T10";

    /**
     * Classpath location of the T8 fixture. The name and the location are the ones it always had: renaming a
     * fixture would invalidate every literal that selects it, and a fixture dropped into this module's resources
     * under a discoverable default name would be picked up by every other test in the directory.
     */
    private static final String T8_CONFIG = "/log4j12-perf.xml";

    /** Classpath location of the T9 fixture, likewise unchanged. */
    private static final String T9_CONFIG = "/perf-log4j12.xml";

    /** Classpath location of the T10 fixture, likewise unchanged. */
    private static final String T10_CONFIG = "/perf-log4j12-async.xml";

    /** Committed capture for T8: two lines, whose second line is the field-width evidence. */
    private static final String T8_BASELINE = "/log4j1-parity/log4j12-perf.baseline.txt";

    /** Committed capture for T9. */
    private static final String T9_BASELINE = "/log4j1-parity/perf-log4j12.baseline.txt";

    /**
     * Committed capture for T10. It is byte-identical to T9's, as it must be — the two fixtures differ only in
     * whether the same file appender is reached directly or through a queue — and the two captures are
     * nevertheless kept as separate files, so that a change to either fixture is caught against its own evidence.
     */
    private static final String T10_BASELINE = "/log4j1-parity/perf-log4j12-async.baseline.txt";

    /**
     * Destination of the T8 fixture, resolved against the module directory exactly as the fixture declares it.
     * This is the fixture's own configured path and is deliberately not derived from a build property: what the
     * gate exercises is the destination the translation carried over.
     */
    private static final Path TESTLOG4J_DESTINATION = Paths.get("target", "testlog4j.log");

    /**
     * Destination shared by the T9 and T10 fixtures, module-relative and outside the build output directory,
     * exactly as both fixtures declare it. Because it is shared, each case removes it both before and after
     * itself: no case may rely on another having run first, and none may leave the file behind.
     */
    private static final Path PERFTEST_DESTINATION = Paths.get("perftest.log");

    // -----------------------------------------------------------------------------------------------------------
    // The fixed event script, embedded here rather than kept as a resource
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Logger name used by the T8 fixture's consumer.
     * <p>
     * That consumer acquires <em>three</em> loggers, one per logging generation, and gives all three this
     * identical name, derived from the same class. It is deliberate rather than a copy-paste defect: the class
     * exists to compare the generations against each other and the arms stay comparable only while they log under
     * a shared name. The name is therefore carried across character for character, and one name serving several
     * generations is never treated as something to disambiguate.
     * </p>
     */
    private static final String T8_LOGGER = "org.apache.logging.log4j.PerformanceComparison";

    /**
     * Logger name used by the consumer of both the T9 and the T10 fixtures, which acquires it from its own runtime
     * class and emits every one of its three statements at {@code INFO}.
     */
    private static final String T9_AND_T10_LOGGER = "org.apache.logging.log4j.core.async.perftest.RunLog4j1";

    /**
     * The fixed event script: every event replayed by this gate, in script order.
     * <p>
     * The script lives here as constants rather than as a resource for two independent reasons. The capture
     * directory beside the baselines holds exactly the three captures and their effective-configuration document,
     * and no fourth kind of file; and the sibling module that keeps an equivalent script as a resource cannot be
     * depended upon from here — it is itself acquiring a test-scope dependency on this module, so the reverse edge
     * would be a dependency cycle; it neither installs nor deploys and builds no test artefact, so there is
     * nothing to depend on; and it is built after this module, so the edge would also reverse reactor order.
     * </p>
     * <p>
     * Three of the four events are the emissions the fixtures' real consumers make, carried across with their
     * logger name, their level and their fully rendered message unchanged. The message of the first is the
     * <em>result</em> of the concatenation its consumer performs: no argument anywhere in this migration was
     * converted to a parameterized form, because the parameterized arms already exist as separate benchmarks and
     * converting would destroy the comparison the concatenating arms exist to make.
     * </p>
     * <p>
     * The second event is the one addition, and it is a control rather than a consumer emission. T8's layout
     * renders the level through a five-character minimum field, and its consumer emits at {@code DEBUG}, which is
     * exactly five characters and therefore needs no padding: a capture of that event alone renders identically
     * whether the field width is configured or not, so deleting the width from the translated fixture would leave
     * the capture unchanged and the gate would keep passing. Replaying one further event at {@code WARN} — four
     * characters, right-aligned into the field, on the same logger name so that the level is the only difference
     * between the two captured lines — closes that hole. It re-levels nothing, since the consumer's own
     * {@code DEBUG} event is still replayed first and unchanged, and it changes no appender option, no root level,
     * no buffer size and no flush setting. The single leading space it produces is the whole of the evidence that
     * the field width survived translation, and it is why the T8 comparison must cover the entire capture and
     * never merely its first line.
     * </p>
     * <p>
     * The list is unmodifiable and holds immutable values, so the script cannot be perturbed by a case and is safe
     * under forked, randomly ordered execution.
     * </p>
     */
    private static final List<ScriptedEvent> SCRIPT = Collections.unmodifiableList(Arrays.asList(
            new ScriptedEvent(T8, T8_LOGGER, Level.DEBUG, "SEE IF THIS IS LOGGED 2."),
            new ScriptedEvent(T8, T8_LOGGER, Level.WARN, "Field width control event"),
            new ScriptedEvent(T9, T9_AND_T10_LOGGER, Level.INFO, "Short msg"),
            new ScriptedEvent(T10, T9_AND_T10_LOGGER, Level.INFO, "Short msg")));

    // -----------------------------------------------------------------------------------------------------------
    // The fresh-destination contract
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Removes both destinations before every case.
     * <p>
     * This is the only safe moment to do it: no context holds a file manager yet, so the removal cannot interrupt
     * a capture, and the manager a fixture opens afterwards is keyed by its destination name and reference
     * counted. Two of the three fixtures write the same file, and the cases are randomly ordered, so a stale file
     * would otherwise let one case's capture carry another's lines — intermittently, and only for some orders.
     * </p>
     */
    @BeforeEach
    void removeDestinationsBeforeBoot() throws IOException {
        removeDestinations();
    }

    /**
     * Removes both destinations after every case, whether it passed or failed.
     * <p>
     * It runs after the case's own try-with-resources block has stopped the context, so a file is never removed
     * while its manager is still open. Leaving a capture behind is not merely untidy: the module-relative
     * destination sits outside the build output directory and is covered neither by the repository's ignore rules
     * nor by the licence audit's exclusions, so a leftover copy would surface as an unlicensed file and fail that
     * audit.
     * </p>
     */
    @AfterEach
    void removeDestinationsAfterCapture() throws IOException {
        removeDestinations();
    }

    /**
     * Removes both destinations if they are present.
     * <p>
     * The removal is idempotent and reports whether anything was there to remove. Both answers are legitimate — a
     * stale capture before a boot, nothing at all after a clean case — so neither is asserted. A destination that
     * exists but cannot be removed raises, which is the outcome that does matter, because it means a manager is
     * still holding the file.
     * </p>
     */
    private static void removeDestinations() throws IOException {
        Files.deleteIfExists(TESTLOG4J_DESTINATION);
        Files.deleteIfExists(PERFTEST_DESTINATION);
    }

    // -----------------------------------------------------------------------------------------------------------
    // One independent case per fixture
    // -----------------------------------------------------------------------------------------------------------

    /**
     * The two-line synchronous capture: the consumer's own event, and the control that makes the five-character
     * level field observable. The whole capture is compared, both lines of it, for the reason given on the script.
     */
    @Test
    @DisplayName("log4j12-perf.xml renders its committed baseline byte for byte")
    void log4j12PerfRendersItsBaseline() throws Exception {
        try (final LoggerContext context = startContext(T8, T8_CONFIG)) {
            replay(context, T8);
        }
        assertRenderedParity(T8, T8_BASELINE, TESTLOG4J_DESTINATION);
    }

    /**
     * The synchronous, buffered capture. Its fixture is the one whose superseded form declared buffered writing
     * but no flush setting, because that generation inferred one from the other; this generation does not, so the
     * translation states both. Whether the inference survived is not asserted by reading those attributes — it is
     * settled by the fact that a capture appears at all, and appears in full, only once the context has stopped.
     */
    @Test
    @DisplayName("perf-log4j12.xml renders its committed baseline byte for byte")
    void perfLog4j12RendersItsBaseline() throws Exception {
        try (final LoggerContext context = startContext(T9, T9_CONFIG)) {
            replay(context, T9);
        }
        assertRenderedParity(T9, T9_BASELINE, PERFTEST_DESTINATION);
    }

    /**
     * The asynchronous capture, and the case that most depends on the ordering this class imposes: its event is
     * handed to a background thread, rendered into a buffered writer and only then flushed, all of which happens
     * while the context is stopping. The rendered thread name is the <em>producing</em> thread, recorded when the
     * event was constructed rather than when it was written, so the capture is comparable with its synchronous
     * twin token for token.
     */
    @Test
    @DisplayName("perf-log4j12-async.xml renders its committed baseline byte for byte")
    void perfLog4j12AsyncRendersItsBaseline() throws Exception {
        try (final LoggerContext context = startContext(T10, T10_CONFIG)) {
            replay(context, T10);
        }
        assertRenderedParity(T10, T10_BASELINE, PERFTEST_DESTINATION);
    }

    // -----------------------------------------------------------------------------------------------------------
    // The comparison itself
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Compares one capture with its committed baseline through the normalizer.
     * <p>
     * Both sides are read and normalized by identical code, so the comparison cannot be skewed by how either side
     * was obtained; the normalizer is idempotent, which is what makes normalizing an already-normalized baseline a
     * safe way to guarantee that symmetry. The presence of the file is asserted first, so a fixture that never
     * opened its destination is reported as such instead of surfacing as a missing-file exception. The whole file
     * is compared — never a line of it, never a prefix.
     * </p>
     *
     * @param configId identifier of the fixture under test, named in every failure message
     * @param baselineResource classpath location of the committed capture
     * @param destination file the fixture was configured to write
     * @throws IOException if the capture or the baseline cannot be read
     */
    private static void assertRenderedParity(
            final String configId, final String baselineResource, final Path destination) throws IOException {
        assertTrue(
                Files.isRegularFile(destination),
                configId + " produced no capture at " + destination
                        + "; either the fixture was not located or its appender never opened its destination");
        final String expected = normalize(readResource(baselineResource));
        final String actual = normalize(readFile(destination));
        assertEquals(
                expected,
                actual,
                configId + " diverged from the committed baseline " + baselineResource
                        + ". A non-empty normalized diff is a defect in the translated fixture, and never a reason"
                        + " to widen the normalizer, relax this assertion or edit the baseline");
    }

    // -----------------------------------------------------------------------------------------------------------
    // Booting a fixture, and replaying its events
    // -----------------------------------------------------------------------------------------------------------

    /** Prefix of the name given to each booted context, so that no two fixtures share a context identity. */
    private static final String CONTEXT_NAME_PREFIX = "log4j1-parity-";

    /**
     * Boots one fixture in its own logger context, configured from its classpath location.
     * <p>
     * <strong>Handing a non-null configuration location to the constructor is mandatory, not stylistic.</strong>
     * The configuration factory guards its entire system-property-reading block on the location being absent, so a
     * non-null location makes the context property-independent by construction: no configuration-file property of
     * any generation is consulted, and none needs to be set. That is what allows three fixtures to boot inside one
     * virtual machine, under forked and randomly ordered execution, without contending over a single global key —
     * and it is why this class sets, clears and reads no system property at all. A global selector would leak into
     * every other test in the module.
     * </p>
     * <p>
     * The returned context is started and is the caller's to close; every case closes it with try-with-resources,
     * which stops it on the failure path too, and stopping is what drains a queue and flushes a writer. If the
     * boot itself fails, the partially started context is stopped here rather than left running, because a context
     * that was never handed back cannot be closed by anyone else.
     * </p>
     *
     * @param configId identifier of the fixture, used to name the context
     * @param configResourcePath classpath location of the fixture
     * @return the started context
     * @throws URISyntaxException if the located fixture cannot be expressed as a URI
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
     * Replays one fresh pass of a fixture's scripted events through the supplied context, in script order.
     * <p>
     * Each event is emitted as a level and a single, fully rendered string, which is the plainest route the logging
     * API offers: none of the three layouts in this corpus renders the caller's class, method or line, so nothing
     * here fabricates a caller frame, and a format string with parameters is never used because the recorded
     * messages are results rather than templates. Level filtering applies on this route exactly as it does to the
     * fixtures' real consumers.
     * </p>
     * <p>
     * This method performs no draining and no flushing of its own. The caller must stop the context before reading
     * the capture.
     * </p>
     *
     * @param context a started, isolated context booted from the fixture under test
     * @param configId identifier of the fixture whose events are to be replayed
     */
    private static void replay(final LoggerContext context, final String configId) {
        for (final ScriptedEvent event : SCRIPT) {
            if (configId.equals(event.configId())) {
                context.getLogger(event.loggerName()).log(event.level(), event.message());
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Reading captures and baselines
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Reads a classpath resource as text, by resolving it to a file and deferring to {@link #readFile(Path)}, so
     * that a baseline and a capture are always decoded by identical code.
     *
     * @param resourcePath classpath location of the resource
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
     * Reads a file as text.
     * <p>
     * The whole byte content is decoded in one step with an explicit charset. Reading by lines is deliberately
     * avoided: it would discard whether the content ends with a line separator, and that disposition is part of
     * what the comparison asserts. A zero-length file yields an empty string rather than an empty line, and no
     * character — least of all a trailing space — is removed.
     * </p>
     *
     * @param file the file to read
     * @return the decoded content, with no trimming of any kind
     * @throws IOException if the file cannot be read
     */
    private static String readFile(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------------------------------------------
    // The output normalizer: exactly four tokens, deliberately minimal
    // -----------------------------------------------------------------------------------------------------------

    /** Replacement for a rendered wall-clock timestamp. */
    private static final String TIMESTAMP_TOKEN = "<TS>";

    /** Replacement for a rendered thread name, brackets retained. */
    private static final String THREAD_TOKEN = "[<THREAD>]";

    /** Replacement for a rendered source line number, the preceding colon retained. */
    private static final String LINE_TOKEN = ":<LINE>";

    /** Replacement for a rendered absolute filesystem path. */
    private static final String PATH_TOKEN = "<PATH>";

    /**
     * The default date format, matched only at the start of a line. The fractional separator is accepted in either
     * of its two spellings so that the anchor cannot be defeated by a formatter variant; this is one token either
     * way.
     */
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[,.]\\d{3}");

    /**
     * A bracketed thread name. Only the <em>first</em> occurrence on a timestamp-bearing line is replaced: the
     * thread field always precedes the message in all three layouts, so a message that happened to contain
     * brackets of its own would survive untouched.
     */
    private static final Pattern BRACKETED_THREAD = Pattern.compile("\\[[^\\]\\n]*\\]");

    /**
     * A rendered source line number, anchored on the separator that follows it in a layout that renders caller
     * location. Anchoring it this tightly is what keeps line numbers that are part of the compared output out of
     * its reach. <em>Inert in this corpus:</em> none of the three fixtures renders the caller's class, method or
     * line, so the token never fires here. It is retained so that this module's normalizer stays byte-identical to
     * the sibling module's, and so that the two corpora cannot drift apart.
     */
    private static final Pattern SOURCE_LINE_NUMBER = Pattern.compile(":\\d+(?=  - )");

    /**
     * A genuinely absolute filesystem path: a root-anchored path with at least one segment, or a drive-letter
     * path, in either case starting at a token boundary so that a relative destination — such as this corpus's
     * build-output capture or its module-relative one — is never matched.
     */
    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("(?<![^\\s(\\[])(?:[A-Za-z]:[\\\\/]|/)[\\w.+~@%$-]+(?:[\\\\/][\\w.+~@%$-]+)*[\\\\/]?");

    /**
     * Normalizes rendered output so that a capture can be compared with a committed baseline.
     * <p>
     * Exactly four rendered elements are substituted, and no fifth may ever be added:
     * </p>
     * <ol>
     *   <li>the timestamp, which is wall-clock;</li>
     *   <li>the thread name, which is assigned by whatever ran the emission — always the <em>producing</em>
     *       thread, because the name is recorded into the event when the event is constructed, so the
     *       asynchronous fixture renders the emitter and never the dispatcher;</li>
     *   <li>the source line number, which is an artefact of the editing this migration performs; the class and
     *       method components rendered beside it are <em>not</em> normalized;</li>
     *   <li>absolute filesystem paths, which are workspace-dependent.</li>
     * </ol>
     * <p>
     * <strong>Everything else is compared byte for byte</strong>: the level text and its padding width, the
     * logger-name abbreviation, every literal separator, and the rendering of an absent context-map key — which
     * renders as nothing and therefore produces byte-significant double spaces and, in two of the three fixtures,
     * one significant trailing space. Nothing is trimmed, no run of spaces is collapsed and no line separator is
     * rewritten: captures and baselines are both produced on the project's documented build baseline, so a
     * line-separator token would be a fifth token and is forbidden.
     * </p>
     * <p>
     * The function is pure and idempotent: it holds no state, so it is safe under forked, randomly ordered
     * execution, and normalizing an already-normalized baseline returns it unchanged. Lines are split and rejoined
     * on the line-feed character with a negative limit, so the presence or absence of a final line separator
     * survives intact.
     * </p>
     *
     * @param rendered captured or baseline text, never {@code null}
     * @return the normalized text
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
     * Normalizes one line.
     * <p>
     * The thread and line-number tokens are confined to lines that carry a timestamp. Every layout in this corpus
     * that renders a thread name also opens with the timestamp, so the restriction costs nothing and it forecloses
     * a real hazard: a rendered message that contains brackets of its own.
     * </p>
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
            result = replaceFirst(result, SOURCE_LINE_NUMBER, LINE_TOKEN);
        }
        return replaceAll(result, ABSOLUTE_PATH, PATH_TOKEN);
    }

    /**
     * Replaces the first match of a pattern by index surgery.
     * <p>
     * Index surgery is used in preference to the regular-expression replacement methods, because a replacement
     * string is otherwise interpreted: a captured backslash or dollar sign in rendered output would either be
     * consumed or raise an error. Splicing the literal token in cannot misfire.
     * </p>
     */
    private static String replaceFirst(final String input, final Pattern pattern, final String replacement) {
        final Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        return input.substring(0, matcher.start()) + replacement + input.substring(matcher.end());
    }

    /** Replaces every match of a pattern by index surgery, scanning left to right without rescanning a token. */
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

    // -----------------------------------------------------------------------------------------------------------
    // One scripted event
    // -----------------------------------------------------------------------------------------------------------

    /**
     * One scripted event: an immutable value carrying the fixture it belongs to, the logger name it is emitted
     * under, its level and its fully rendered message.
     */
    private static final class ScriptedEvent {

        private final String configId;

        private final String loggerName;

        private final Level level;

        private final String message;

        ScriptedEvent(final String configId, final String loggerName, final Level level, final String message) {
            this.configId = configId;
            this.loggerName = loggerName;
            this.level = level;
            this.message = message;
        }

        /** Identifier of the fixture this event belongs to. */
        String configId() {
            return configId;
        }

        /**
         * Logger name, carried character for character from the fixture's consumer. It is never "improved": the
         * name is the logger's identity, and therefore its configured level and its appender wiring.
         */
        String loggerName() {
            return loggerName;
        }

        /** Level the event is emitted at, exactly as scripted. */
        Level level() {
            return level;
        }

        /**
         * Fully rendered message text, emitted as a single string. A message its consumer built by concatenation
         * is carried here as the concatenated result and is deliberately not converted to a parameterized form.
         */
        String message() {
            return message;
        }

        @Override
        public String toString() {
            return configId + '|' + loggerName + '|' + level + '|' + message;
        }
    }
}
