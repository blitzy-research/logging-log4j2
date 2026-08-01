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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogBuilder;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Assertions;

/**
 * Shared harness for the behaviour-preservation gates that gate the in-place translation of this module's seven
 * superseded logging configurations into the Log4j 2 schema.
 * <p>
 * This class exists so that the committed baseline captures and the post-migration captures are produced by
 * <em>identical code</em>. It holds exactly three things and nothing else:
 * </p>
 * <ol>
 *   <li>the <strong>fixed event script</strong>, parsed from the sibling resource {@value #EVENT_SCRIPT_RESOURCE}
 *       and replayed by {@link #replay(LoggerContext, String)}, which supplies a fixed
 *       {@link StackTraceElement} for every event;</li>
 *   <li>the <strong>output normalizer</strong>, {@link #normalize(String)}, which substitutes exactly four
 *       rendered elements and never a fifth;</li>
 *   <li>the <strong>isolated logger-context helper</strong>, {@link #startContext(String, String)}, which boots a
 *       configuration from its classpath {@link java.net.URI} rather than from any system property.</li>
 * </ol>
 * <p>
 * Three cross-cutting contracts follow from those three parts, and each is implemented here rather than in the
 * consuming tests, so that both consumers observe the same behaviour:
 * </p>
 * <ul>
 *   <li><strong>Fixed caller identity.</strong> A plain {@code logger.debug(message)} issued from inside this
 *       harness would resolve the caller-location tokens to a frame belonging to this class. Only one fixture
 *       renders caller location, and its committed baseline names the benchmark arm that used to emit the event,
 *       so every event is emitted through a {@link LogBuilder} carrying an explicit, fabricated
 *       {@link StackTraceElement}. See {@link #fixedLocation(Event)}.</li>
 *   <li><strong>Fixed stack trace.</strong> The one scripted event that carries a throwable would otherwise
 *       render an environment-dependent trace. The throwable is therefore given a fixed trace, transcribed from
 *       the committed capture. See {@link #newFixedThrowable(String)}.</li>
 *   <li><strong>Fresh destination.</strong> Four fixtures share one destination file and none of them declares an
 *       append option, whose default appends. A capture is only meaningful when the destination is removed
 *       immediately before the configuration that writes it is booted. See
 *       {@link #deleteDestination(Path)}, {@link #TESTLOG4J_DESTINATION} and {@link #PERFTEST_DESTINATION}.</li>
 * </ul>
 * <p>
 * <strong>The committed script is verified, not merely trusted.</strong> Its records are asserted against
 * {@link #EXPECTED_MANIFEST} on load — configuration id, logger name, level, message text, throwable field and
 * order, entry for entry — and the per-id counts derived from that manifest are asserted too, so a fixture that
 * has drifted from this code fails loudly instead of silently weakening a gate. Every recorded logger name is the
 * name its arm's acquisition site requests, and one of them names a different benchmark than the arm that emitted
 * the event; all are carried character for character, because renaming any of them would move its events onto a
 * differently configured logger.
 * </p>
 * <p>
 * <strong>What this class deliberately does not do.</strong> It sets, clears and reads <em>no</em> system
 * property: handing a non-null configuration location to the {@link LoggerContext} constructor skips property
 * lookup altogether, which is what lets several configurations boot inside one virtual machine without
 * contending over a single global key. It contains no capture harness, no configuration writer, no documentation
 * generator and no second normalizer; the effective-configuration evidence and the rationale for every
 * translation decision live in the {@code effective-config.adoc} beside the baselines.
 * </p>
 * <p>
 * Every construct used here stays inside this module's own compiled API surface. The peer gate that covers the
 * other module's three configurations was written to the same shape but shares no code with this file: that module
 * compiles at a lower release level and a dependency on this one would close a build cycle, so it embeds its own
 * event constants and its own normalizer, and the two are kept in agreement by review rather than by reuse.
 * </p>
 */
final class ParityCorpus {

    /** Classpath location of the fixed event script. Grouped by configuration id, in significant order. */
    static final String EVENT_SCRIPT_RESOURCE = "/log4j1-parity/event-script.txt";

    /**
     * Destination shared by the four synchronous file fixtures. None of them declares an append option, and the
     * default appends, so this file must be removed immediately before each of those configurations is booted.
     */
    static final Path TESTLOG4J_DESTINATION = Paths.get("target", "testlog4j.log");

    /**
     * Destination of the asynchronous file fixture. It is declared relative with no directory, so it lands in the
     * module directory rather than under the build output directory. Neither the repository's ignore rules nor
     * the licence-audit exclusions cover it, so a leftover copy would show up as an untracked file and fail the
     * licence audit: it must be removed before the fixture is booted and again once the capture has been read.
     */
    static final Path PERFTEST_DESTINATION = Paths.get("perftest.log");

    /**
     * Method name carried by every fabricated caller frame.
     * <p>
     * Only one of the seven fixtures renders the caller-location tokens, and its committed baseline line renders
     * {@code FileAppenderWithLocationBenchmark.log4j1File}, so that caller identity must be reproduced exactly.
     * The value is the emitting arm of the with-location file-appender benchmark. It is applied uniformly to every
     * scripted event because a uniform fabricated frame keeps the harness deterministic, and because no other
     * fixture's layout renders a class, a method or a line number at all.
     * </p>
     */
    private static final String EMITTING_METHOD_NAME = "log4j1File";

    /**
     * Line number carried by every fabricated caller frame.
     * <p>
     * The value is deliberately arbitrary and is never compared: a source line number is an artefact of the
     * editing this migration performs, so it is the one rendered element the normalizer replaces. It must
     * nevertheless be <em>fixed</em>, so that a replay is reproducible, and <em>positive</em>, so that the line
     * converter renders digits — which is what the normalizer's anchor matches.
     * </p>
     */
    private static final int FIXED_SOURCE_LINE_NUMBER = 1;

    /**
     * Declaring class of the fabricated frames given to the scripted throwable.
     * <p>
     * This is a frame identity, not a type: no such class exists in this package, and none may be added, because
     * this package holds exactly this harness and the two tests that consume it. The name and both frames below
     * were transcribed from the committed capture rather than invented.
     * </p>
     */
    private static final String THROWABLE_FIXTURE_CLASS = "org.apache.logging.log4j.perf.parity.ThrowableParityFixture";

    /** Source file name of the fabricated throwable frames, as rendered by the plain throwable converter. */
    private static final String THROWABLE_FIXTURE_FILE = "ThrowableParityFixture.java";

    /** Simple name of the only exception type the script specifies. Resolved by a closed mapping, not by reflection. */
    private static final String ILLEGAL_STATE_EXCEPTION = "IllegalStateException";

    /**
     * Grammar of a script data line: {@code <configId>|<loggerName>|<LEVEL>|<message>[|<throwableSpec>]}.
     * <p>
     * A full match with capture groups is used in preference to splitting, because {@code [^|]} cannot cross a
     * delimiter: the grouping is therefore unambiguous, a message that happens to contain no delimiter is never
     * split further, and an absent fifth field is distinguishable from an empty one.
     * </p>
     * <p>
     * The level alphabet is closed at exactly the three levels the superseded arms emit — {@code DEBUG} on the
     * four file fixtures, {@code ERROR} on the throwable fixture, {@code INFO} on the two counting fixtures. It is
     * deliberately narrower than the level model: a level no arm emits could only enter this corpus as an invented
     * event, and an invented event proves nothing about a translation, because no capture of the superseded
     * generation ever recorded it. A data line at any other level is rejected outright rather than coerced or
     * replayed.
     * </p>
     */
    private static final Pattern DATA_LINE =
            Pattern.compile("^(T[1-7])\\|([^|]+)\\|(DEBUG|INFO|ERROR)\\|([^|]+)(?:\\|([^|]+))?$");

    /**
     * Grammar of a recorded-count record, {@code <configId>=<count>}, as used by the counting fixtures' baseline.
     * <p>
     * It is anchored and admits digits only, so a record that has been reordered, duplicated, renamed or given a
     * non-numeric count cannot be read as a valid one.
     * </p>
     */
    private static final Pattern COUNT_RECORD = Pattern.compile("^(T[1-7])=([0-9]+)$");

    /** Logger acquired by the plain file-appender benchmark, from a class literal. */
    private static final String FILE_APPENDER_LOGGER = "org.apache.logging.log4j.perf.jmh.FileAppenderBenchmark";

    /** Logger acquired by the parameterizing file-appender benchmark, from a class literal. */
    private static final String FILE_APPENDER_PARAMS_LOGGER =
            "org.apache.logging.log4j.perf.jmh.FileAppenderParamsBenchmark";

    /** Logger acquired by the benchmark that measures a suppressed call, from a class literal. */
    private static final String DEBUG_DISABLED_LOGGER = "org.apache.logging.log4j.perf.jmh.DebugDisabledBenchmark";

    /**
     * Logger acquired by the with-location file-appender benchmark, from a class literal.
     * <p>
     * One of the two events recorded on this name belongs to a <em>different</em> benchmark, which requests its
     * logger by this class literal rather than by its own. That reads like a copy-paste defect and it is carried
     * verbatim regardless: the name is the logger's identity, and therefore its configured level and appender
     * wiring, so "correcting" it would move the event onto a differently configured logger.
     * </p>
     */
    private static final String FILE_APPENDER_WITH_LOCATION_LOGGER =
            "org.apache.logging.log4j.perf.jmh.FileAppenderWithLocationBenchmark";

    /** Logger acquired by the throwable-rendering file-appender benchmark, from a class literal. */
    private static final String FILE_APPENDER_THROWABLE_LOGGER =
            "org.apache.logging.log4j.perf.jmh.FileAppenderThrowableBenchmark";

    /**
     * Logger acquired by the asynchronous throughput-and-latency runner, from a class literal.
     * <p>
     * This is the one recorded name outside the benchmark package, because the arm that emitted the event is an
     * integration-test runner rather than a benchmark.
     * </p>
     */
    private static final String RUN_LOG4J1_LOGGER = "org.apache.logging.log4j.core.async.perftest.RunLog4j1";

    /** Logger acquired by the asynchronous no-op-appender benchmark, from a class literal. */
    private static final String ASYNC_APPENDER_LOGGER =
            "org.apache.logging.log4j.perf.jmh.AsyncAppenderLog4j1Benchmark";

    /** Logger acquired by the asynchronous no-op-appender benchmark that also captures location. */
    private static final String ASYNC_APPENDER_LOCATION_LOGGER =
            "org.apache.logging.log4j.perf.jmh.AsyncAppenderLog4j1LocationBenchmark";

    /** The sixteen-character fixed-width message shared by the first arm of each counting fixture. */
    private static final String SIXTEEN_CHARACTER_MESSAGE = "aaaaaaaaaaaaaaaa";

    /** Throwable specification of the single scripted event that carries one. */
    private static final String THROWABLE_SPEC = ILLEGAL_STATE_EXCEPTION + ":Test Throwable";

    /**
     * The complete manifest of scripted events, in significant order: one entry per data line the committed script
     * must carry, rendered in the script's own grammar.
     * <p>
     * This is the semantic oracle, and it is stronger than a count on purpose. A count alone admits a fixture in
     * which an event has been re-levelled, moved to another logger, reworded, reordered or replaced by an invented
     * one — every drift that would silently weaken a parity gate while leaving its arithmetic intact. The manifest
     * pins the configuration id, the logger name, the level, the message text and the presence or absence of the
     * throwable field of every record, and pins their order, so the committed script and this code must agree
     * character for character or class initialization fails.
     * </p>
     * <p>
     * Twenty-one events, all of them derived from an arm of the superseded generation: three on {@code T1}, two on
     * {@code T2}, one each on {@code T3}, {@code T4} and {@code T5}, twelve on {@code T6} and one on {@code T7}.
     * <strong>No control, probe or otherwise invented event appears, and none may be added.</strong> An event no
     * arm emits carries no parity information, because no capture of the superseded generation ever recorded it;
     * the evidence such an event would seem to supply is obtained instead from the structural assertions the
     * consuming test makes while each fixture is started.
     * </p>
     */
    private static final List<String> EXPECTED_MANIFEST = expectedManifest();

    /**
     * Event count expected for each configuration id, in the manifest's own encounter order.
     * <p>
     * Derived from {@link #EXPECTED_MANIFEST} rather than restated, so the two can never disagree. A script that
     * does not yield exactly these counts is a defect in the fixture or in this code, never something to
     * accommodate.
     * </p>
     */
    private static final Map<String, Integer> EXPECTED_EVENT_COUNTS = expectedEventCounts(EXPECTED_MANIFEST);

    /** Immutable, ordered event index, built once from the committed script. */
    private static final Map<String, List<Event>> EVENTS_BY_CONFIG_ID = loadEventScript();

    private ParityCorpus() {}

    // -----------------------------------------------------------------------------------------------------------
    // Part (a) -- the fixed event script
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Returns the scripted events for one configuration id, in the script's own encounter order.
     * <p>
     * Order is significant: the messages of the concatenating arms embed counter values that were captured for one
     * fresh pass in exactly this order, so replaying a group out of order, or twice, changes the rendered text.
     * </p>
     *
     * @param configId a configuration id, {@code T1} through {@code T7}
     * @return an unmodifiable list of events, never empty
     * @throws IllegalArgumentException if the id is not one the committed script defines
     */
    static List<Event> events(final String configId) {
        final List<Event> events = EVENTS_BY_CONFIG_ID.get(configId);
        if (events == null) {
            throw new IllegalArgumentException("no such configuration id in " + EVENT_SCRIPT_RESOURCE + ": " + configId
                    + "; known ids are " + EVENTS_BY_CONFIG_ID.keySet());
        }
        return events;
    }

    /**
     * Builds the expected event manifest. Declared as a method rather than as a literal because the list is
     * assembled with only constructs available at the API surface shared with the peer module.
     * <p>
     * Every entry names the arm that emitted the event and reproduces the text that arm rendered. The messages the
     * concatenating arms built are carried as their rendered result, including the counter values one fresh pass
     * produced, which is why the order of the entries is part of the oracle rather than an incidental detail.
     * </p>
     */
    private static List<String> expectedManifest() {
        final List<String> manifest = new ArrayList<>();

        // T1 -- the synchronous shared-destination fixture whose root level admits debug. One event from the plain
        // file-appender arm and two from the parameterizing arm, whose second message embeds instance counters.
        manifest.add(record("T1", FILE_APPENDER_LOGGER, Level.DEBUG, "This is a debug message"));
        manifest.add(record("T1", FILE_APPENDER_PARAMS_LOGGER, Level.DEBUG, "This is a debug [1] message"));
        manifest.add(record("T1", FILE_APPENDER_PARAMS_LOGGER, Level.DEBUG, "Val1=2, val2=1, val3=1"));

        // T2 -- the same layout and destination at root level error. Both events are suppressed by that root
        // level, which is precisely the evidence this fixture exists to give, so its capture is byte-empty.
        manifest.add(record("T2", DEBUG_DISABLED_LOGGER, Level.DEBUG, "This is a debug [2] message"));
        manifest.add(record("T2", FILE_APPENDER_WITH_LOCATION_LOGGER, Level.DEBUG, "This won't be logged"));

        // T3 -- the only fixture whose layout renders the caller class, method and line.
        manifest.add(record("T3", FILE_APPENDER_WITH_LOCATION_LOGGER, Level.DEBUG, "This is a debug message"));

        // T4 -- the only event that carries a throwable, and so the only record with a fifth field.
        manifest.add(record("T4", FILE_APPENDER_THROWABLE_LOGGER, Level.ERROR, "Caught an exception", THROWABLE_SPEC));

        // T5 -- the asynchronous file fixture. Recorded at debug, the level its own arm emits. The peer corpus
        // records this same runner at info for its own fixtures; the two are deliberately not harmonised, because
        // each corpus records the level its own arm emitted.
        manifest.add(record("T5", RUN_LOG4J1_LOGGER, Level.DEBUG, "Short msg"));

        // T6 -- the counting no-op fixture: the fixed-width arm followed by the eleven concatenating arms, in
        // ascending parameter count. Twelve events, matching the twelve annotated arms of the benchmark.
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, SIXTEEN_CHARACTER_MESSAGE));
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1"));
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1, p2=2"));
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1, p2=2, p3=3"));
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1, p2=2, p3=3, p4=4"));
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1, p2=2, p3=3, p4=4, p5=5"));
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6"));
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7"));
        manifest.add(record("T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7, p8=8"));
        manifest.add(record(
                "T6", ASYNC_APPENDER_LOGGER, Level.INFO, "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7, p8=8, p9=9"));
        manifest.add(record(
                "T6",
                ASYNC_APPENDER_LOGGER,
                Level.INFO,
                "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7, p8=8, p9=9, p10=10"));
        manifest.add(record(
                "T6",
                ASYNC_APPENDER_LOGGER,
                Level.INFO,
                "p1=1, p2=2, p3=3, p4=4, p5=5, p6=6, p7=7, p8=8, p9=9, p10=10, p11=11"));

        // T7 -- the same counting fixture with location capture enabled. One event, matching its one annotated arm.
        manifest.add(record("T7", ASYNC_APPENDER_LOCATION_LOGGER, Level.INFO, SIXTEEN_CHARACTER_MESSAGE));

        return Collections.unmodifiableList(manifest);
    }

    /** Renders one manifest entry with no throwable field, in the script's own grammar. */
    private static String record(
            final String configId, final String loggerName, final Level level, final String message) {
        return configId + '|' + loggerName + '|' + level.name() + '|' + message;
    }

    /** Renders one manifest entry carrying a throwable specification, in the script's own grammar. */
    private static String record(
            final String configId,
            final String loggerName,
            final Level level,
            final String message,
            final String throwableSpec) {
        return record(configId, loggerName, level, message) + '|' + throwableSpec;
    }

    /**
     * Derives the expected per-id event counts from the manifest, preserving the manifest's encounter order.
     *
     * @param manifest the expected event manifest
     * @return an unmodifiable, insertion-ordered map from configuration id to event count
     */
    private static Map<String, Integer> expectedEventCounts(final List<String> manifest) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final String entry : manifest) {
            final String configId = entry.substring(0, entry.indexOf('|'));
            final Integer running = counts.get(configId);
            counts.put(configId, running == null ? 1 : running.intValue() + 1);
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Parses the committed script into an immutable, ordered index.
     * <p>
     * Comments and blank lines are skipped by {@link #dataLines(String)}, which is the single place either fixture's
     * skipping rule is expressed. Every remaining line must match {@link #DATA_LINE} in full, and the resulting
     * sequence must equal {@link #EXPECTED_MANIFEST} entry for entry and in order. Verifying the manifest before
     * the counts is deliberate: an entry that has been re-levelled, moved to another logger, reworded, reordered or
     * replaced by an invented event is reported as exactly that, at the record where it occurs, rather than as an
     * arithmetic mismatch or — worse — not at all.
     * </p>
     * <p>
     * A fixture that is missing, unreadable or malformed is a defect in the corpus or in the build, so it is
     * reported as an {@link IllegalStateException} carrying the resource path. That keeps the diagnosis intact
     * even though this loader runs during class initialization.
     * </p>
     */
    private static Map<String, List<Event>> loadEventScript() {
        final List<String> recorded = dataLines(EVENT_SCRIPT_RESOURCE);
        verifyManifest(recorded);
        final Map<String, List<Event>> index = new LinkedHashMap<>();
        for (int recordIndex = 0; recordIndex < recorded.size(); recordIndex++) {
            final String line = recorded.get(recordIndex);
            final Matcher matcher = DATA_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "malformed data record " + (recordIndex + 1) + " in " + EVENT_SCRIPT_RESOURCE + ": " + line);
            }
            final String configId = matcher.group(1);
            final Event event = new Event(
                    configId,
                    matcher.group(2),
                    // The strict form is used on purpose: an unknown level must fail loudly rather than resolve
                    // to null and be emitted as something the script never asked for.
                    Level.valueOf(matcher.group(3)),
                    matcher.group(4),
                    matcher.group(5));
            List<Event> group = index.get(configId);
            if (group == null) {
                group = new ArrayList<>();
                index.put(configId, group);
            }
            group.add(event);
        }
        return verifyEventCounts(index);
    }

    /**
     * Reads a corpus fixture and returns its significant lines, in encounter order, with comments and blank lines
     * removed.
     * <p>
     * Both fixtures this harness serves — the event script and the counting fixtures' recorded-count baseline —
     * carry ordered records interleaved with explanatory comments. The skipping rule therefore lives here once and
     * neither consumer restates it, so the two can never disagree about what counts as a record.
     * </p>
     * <p>
     * A line whose first non-whitespace character is {@code #} is a comment and a line that is empty or entirely
     * whitespace is blank; both are dropped. Nothing else is dropped, trimmed or reordered: a record is returned
     * exactly as it was written, because its bytes are part of what the caller verifies.
     * </p>
     *
     * @param resourcePath absolute classpath path of the fixture
     * @return an unmodifiable list of the fixture's significant lines, in file order
     * @throws IllegalStateException if the fixture is missing or unreadable
     */
    static List<String> dataLines(final String resourcePath) {
        final String content;
        try {
            content = readResource(resourcePath);
        } catch (final IOException readFailure) {
            throw new IllegalStateException("cannot read the corpus fixture " + resourcePath, readFailure);
        }
        final List<String> records = new ArrayList<>();
        for (final String line : content.split("\n", -1)) {
            if (!isSkippable(line)) {
                records.add(line);
            }
        }
        return Collections.unmodifiableList(records);
    }

    /**
     * Parses an ordered {@code <configId>=<count>} fixture into an insertion-ordered map.
     * <p>
     * The counting fixtures record their observed event counts as such a fixture, and <em>the order of its records
     * is part of the contract</em>: the two fixtures differ only in whether location is captured, so a reader that
     * accepted them in either order would also accept a baseline in which their two counts had been swapped. This
     * parser therefore preserves encounter order in the returned map and rejects a duplicated id outright, leaving
     * the caller to assert the exact expected sequence.
     * </p>
     *
     * @param resourcePath absolute classpath path of the fixture
     * @return an unmodifiable map from configuration id to recorded count, in the fixture's own record order
     * @throws IllegalStateException if the fixture is missing, unreadable, malformed, or repeats an id
     */
    static Map<String, Integer> readRecordedCounts(final String resourcePath) {
        final List<String> records = dataLines(resourcePath);
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            final String record = records.get(recordIndex);
            final Matcher matcher = COUNT_RECORD.matcher(record);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "malformed count record " + (recordIndex + 1) + " in " + resourcePath + ": " + record);
            }
            final String configId = matcher.group(1);
            if (counts.containsKey(configId)) {
                throw new IllegalStateException("duplicate configuration id " + configId + " at count record "
                        + (recordIndex + 1) + " in " + resourcePath);
            }
            counts.put(configId, Integer.valueOf(matcher.group(2)));
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Returns {@code true} for a comment or blank line. A comment is recognised by its first non-whitespace
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

    /**
     * Verifies the recorded data records against {@link #EXPECTED_MANIFEST}, entry for entry and in order.
     * <p>
     * The comparison is by whole record, so it covers the configuration id, the logger name, the level, the message
     * text and the presence, absence and content of the throwable field in one step, and it covers their order.
     * The first divergence is reported with the record ordinal and both texts, because that is what identifies the
     * drift; a length mismatch is reported before any positional comparison, so a truncated or extended fixture
     * cannot be described as a mismatch at its first surplus record.
     * </p>
     *
     * @param recorded the fixture's data records, in file order
     * @throws IllegalStateException on any divergence from the manifest
     */
    private static void verifyManifest(final List<String> recorded) {
        if (recorded.size() != EXPECTED_MANIFEST.size()) {
            throw new IllegalStateException("wrong number of data records in " + EVENT_SCRIPT_RESOURCE + ": expected "
                    + EXPECTED_MANIFEST.size() + " but found " + recorded.size());
        }
        for (int recordIndex = 0; recordIndex < EXPECTED_MANIFEST.size(); recordIndex++) {
            final String expected = EXPECTED_MANIFEST.get(recordIndex);
            final String actual = recorded.get(recordIndex);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("data record " + (recordIndex + 1) + " of " + EXPECTED_MANIFEST.size()
                        + " in " + EVENT_SCRIPT_RESOURCE + " does not match the expected manifest;" + " expected ["
                        + expected + "] but found [" + actual + "]");
            }
        }
    }

    /**
     * Verifies the parsed index against the counts derived from the manifest, then freezes it.
     *
     * @throws IllegalStateException if any id is missing, unexpected, or carries the wrong number of events
     */
    private static Map<String, List<Event>> verifyEventCounts(final Map<String, List<Event>> index) {
        for (final Map.Entry<String, List<Event>> group : index.entrySet()) {
            if (!EXPECTED_EVENT_COUNTS.containsKey(group.getKey())) {
                throw new IllegalStateException("unexpected configuration id in " + EVENT_SCRIPT_RESOURCE + ": "
                        + group.getKey() + "; expected one of " + EXPECTED_EVENT_COUNTS.keySet());
            }
        }
        final Map<String, List<Event>> frozen = new LinkedHashMap<>();
        for (final Map.Entry<String, Integer> expected : EXPECTED_EVENT_COUNTS.entrySet()) {
            final String configId = expected.getKey();
            final List<Event> group = index.get(configId);
            final int actual = group == null ? 0 : group.size();
            if (actual != expected.getValue().intValue()) {
                throw new IllegalStateException("wrong event count for " + configId + " in " + EVENT_SCRIPT_RESOURCE
                        + ": expected " + expected.getValue() + " but found " + actual);
            }
            frozen.put(configId, Collections.unmodifiableList(group));
        }
        return Collections.unmodifiableMap(frozen);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Part (a-bis) -- replay, with a fixed caller frame and a fixed stack trace
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Replays one fresh pass of a configuration id's scripted events through the supplied context, in script
     * order.
     * <p>
     * Every event is emitted through the fluent builder, which is the only public route that accepts an explicit
     * caller frame. Level filtering is honoured on that route, so an event whose level the fixture's own
     * configuration suppresses contributes nothing to the capture — which is exactly the evidence the suppressed
     * events exist to provide, and never a defect to be worked around by re-levelling them.
     * </p>
     * <p>
     * This method performs no draining and no flushing of its own. An asynchronous fixture hands its events to a
     * background thread and a buffered appender holds them in memory, so the caller must stop the context before
     * reading the capture, and must retrieve any appender reference it needs <em>before</em> the replay, because
     * stopping a context detaches its configuration.
     * </p>
     *
     * @param context a started, isolated context booted from the configuration under test
     * @param configId the configuration id whose events are to be replayed
     */
    static void replay(final LoggerContext context, final String configId) {
        for (final Event event : events(configId)) {
            emit(context, event);
        }
    }

    /**
     * Emits one scripted event with an explicit caller frame and, where the script specifies one, an explicit
     * throwable carrying a fixed stack trace.
     */
    private static void emit(final LoggerContext context, final Event event) {
        final Logger logger = context.getLogger(event.loggerName());
        LogBuilder builder = logger.atLevel(event.level()).withLocation(fixedLocation(event));
        if (event.throwableSpec() != null) {
            builder = builder.withThrowable(newFixedThrowable(event.throwableSpec()));
        }
        // The pre-rendered message is logged as a single string, never as a format plus parameters.
        builder.log(event.message());
    }

    /**
     * Builds the fabricated caller frame for one event.
     * <p>
     * The declaring class is the event's <em>own logger name</em>, so that the class converter, which abbreviates
     * to the trailing name part, renders the same simple name the committed capture records. The no-argument
     * overload of the builder's location method is deliberately not used: it walks the stack and would resolve to
     * a frame inside this harness. Constructing a log event directly and appending it to an appender is likewise
     * rejected, because it would bypass logger and level filtering and destroy the suppression evidence.
     * </p>
     */
    private static StackTraceElement fixedLocation(final Event event) {
        final String declaringClass = event.loggerName();
        return new StackTraceElement(
                declaringClass, EMITTING_METHOD_NAME, simpleName(declaringClass) + ".java", FIXED_SOURCE_LINE_NUMBER);
    }

    /** Returns the trailing dot-separated part of a name, or the whole name when it carries no dot. */
    private static String simpleName(final String qualifiedName) {
        final int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    /**
     * Builds the throwable a scripted event specifies and gives it a fixed stack trace.
     * <p>
     * The specification is {@code SimpleName:message}, split on its <em>first</em> colon so that a message may
     * contain further colons. The simple name is resolved through a closed mapping rather than by reflective class
     * loading or by concatenating a package prefix, so an unrecognised name fails loudly instead of resolving to
     * something the script never asked for.
     * </p>
     * <p>
     * The trace matters as much as the type. The original throwable was created in a static initializer, so its
     * natural trace named the initializer, the factory method that built it and whatever first triggered class
     * initialization — all environment-dependent, and two of those frames sit in a file this migration edits.
     * The fixture's layout renders the throwable in full and compares it byte for byte, so the trace is replaced
     * with the fixed pair of frames transcribed from the committed capture. A fresh array is built on every call,
     * so no mutable array is shared between callers.
     * </p>
     *
     * @param throwableSpec the optional fifth field of a script data line
     * @return a throwable whose type, message and stack trace are all fixed
     * @throws IllegalArgumentException if the specification is malformed or names an unmapped exception type
     */
    private static Throwable newFixedThrowable(final String throwableSpec) {
        final int separator = throwableSpec.indexOf(':');
        if (separator < 1 || separator == throwableSpec.length() - 1) {
            throw new IllegalArgumentException(
                    "malformed throwable specification, expected SimpleName:message but found: " + throwableSpec);
        }
        final String simpleName = throwableSpec.substring(0, separator);
        final String message = throwableSpec.substring(separator + 1);
        final Throwable throwable;
        if (ILLEGAL_STATE_EXCEPTION.equals(simpleName)) {
            throwable = new IllegalStateException(message);
        } else {
            throw new IllegalArgumentException("unmapped throwable type in the event script: " + simpleName
                    + "; the corpus maps only " + ILLEGAL_STATE_EXCEPTION);
        }
        throwable.setStackTrace(fixedThrowableStackTrace());
        return throwable;
    }

    /**
     * Returns the fixed stack trace given to the scripted throwable, newest frame first, exactly as the committed
     * capture renders it.
     */
    private static StackTraceElement[] fixedThrowableStackTrace() {
        return new StackTraceElement[] {
            new StackTraceElement(THROWABLE_FIXTURE_CLASS, "emit", THROWABLE_FIXTURE_FILE, 42),
            new StackTraceElement(THROWABLE_FIXTURE_CLASS, "main", THROWABLE_FIXTURE_FILE, 17)
        };
    }

    // -----------------------------------------------------------------------------------------------------------
    // Part (b) -- the output normalizer: exactly four tokens, deliberately minimal
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
     * The default date format, anchored to the start of a line. The fractional separator is accepted in either of
     * its two spellings so that the anchor cannot be defeated by a formatter variant; this is one token either
     * way.
     */
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[,.]\\d{3}");

    /**
     * A bracketed thread name. Only the <em>first</em> occurrence on a timestamp-bearing line is replaced: the
     * thread field always precedes the message, and one scripted message contains a bracketed number of its own
     * that must survive untouched.
     */
    private static final Pattern BRACKETED_THREAD = Pattern.compile("\\[[^\\]\\n]*\\]");

    /**
     * A rendered source line number, anchored on the two spaces and the separator that follow it in the only
     * layout that renders caller location. Anchoring this tightly is what keeps the line numbers inside a rendered
     * stack frame — which are part of the compared output — out of reach of this token.
     */
    private static final Pattern SOURCE_LINE_NUMBER = Pattern.compile(":\\d+(?=  - )");

    /**
     * A genuinely absolute filesystem path: a root-anchored path with at least one segment, or a drive-letter
     * path, in either case starting at a token boundary so that a relative destination such as the shared
     * build-output file or the module-relative asynchronous destination is never matched.
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
     *       thread, because both generations record it into the event when the event is constructed, so an
     *       asynchronous fixture renders the emitter and never the dispatcher;</li>
     *   <li>the source line number, which is an artefact of the editing this migration performs; the class and
     *       method components rendered beside it are <em>not</em> normalized;</li>
     *   <li>absolute filesystem paths, which are workspace-dependent.</li>
     * </ol>
     * <p>
     * <strong>Everything else is compared byte for byte</strong>: the level text and its padding width, the
     * logger-name abbreviation, the method name, every literal separator, the rendering of an absent context-map
     * key — which renders as nothing and therefore produces byte-significant double spaces and one significant
     * trailing space — and the full rendering of a throwable, tab-indented frames included. Nothing is trimmed,
     * no run of spaces is collapsed and no line separator is rewritten: the captures and the baselines are both
     * produced on the project's documented build baseline, so a line-separator token would be a fifth token and
     * is forbidden.
     * </p>
     * <p>
     * The function is pure and idempotent: it holds no state, so it is safe under forked, randomly ordered
     * execution, and normalizing an already-normalized baseline returns it unchanged. Lines are split and rejoined
     * on the line-feed character with a negative limit, so the presence or absence of a final newline — the
     * evidence that settles whether the implicit throwable converter adds one — survives intact.
     * </p>
     *
     * @param rendered captured or baseline text, never {@code null}
     * @return the normalized text
     */
    static String normalize(final String rendered) {
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
     * The thread and line-number tokens are confined to lines that carry a timestamp. Every in-scope layout that
     * renders a thread name or a line number also opens with the timestamp, so the restriction costs nothing and
     * it forecloses two real hazards: a rendered message that contains brackets, and the rendered frames of a
     * throwable, whose parenthesised line numbers are part of the compared output.
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
     * Index surgery is used throughout in preference to the regular-expression replacement methods, because a
     * replacement string is otherwise interpreted: a captured backslash or dollar sign in rendered output would
     * either be consumed or raise an error. Splicing the literal token in cannot misfire.
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
    // Part (c) -- the isolated logger-context helper
    // -----------------------------------------------------------------------------------------------------------

    /** Prefix of the context name given to each booted fixture, so no two fixtures share a context identity. */
    private static final String CONTEXT_NAME_PREFIX = "parity-";

    /**
     * Boots one fixture in its own logger context, configured from its classpath location.
     * <p>
     * <strong>Handing a non-null configuration location to the constructor is mandatory, not stylistic.</strong>
     * The configuration factory guards its entire system-property-reading block on the location being absent, so a
     * non-null location makes the context property-independent by construction: no configuration-file property of
     * any generation is consulted. That is what lets several fixtures boot inside one virtual machine, under
     * forked and randomly ordered execution, without contending over a single global key — and it is why this
     * harness sets, clears and reads no system property at all. In particular it never enables status-logger
     * debugging: that evidence belongs to the effective-configuration document beside the baselines, and enabling
     * it here would alter the status output of everything else sharing the machine.
     * </p>
     * <p>
     * The returned context is started and is the caller's to close. Prefer a try-with-resources block, since the
     * context is auto-closeable and closing it stops it; a caller that keeps the reference may instead stop it
     * with the null-safe shutdown helper in a teardown. Both funnel to the same stop, and stopping is what drains
     * an asynchronous appender's queue and flushes a buffered writer, so it must happen before the capture is
     * read. If the boot itself fails, the partially started context is stopped here rather than left running,
     * because a context that was never published cannot be closed by anyone else.
     * </p>
     *
     * @param configId the configuration id, used to name the context
     * @param configResourcePath absolute classpath path of the configuration, which keeps its original name and
     *     location and is therefore selected by that name
     * @return the started context
     * @throws URISyntaxException if the located resource cannot be expressed as a URI
     */
    static LoggerContext startContext(final String configId, final String configResourcePath)
            throws URISyntaxException {
        final URL configLocation = ParityCorpus.class.getResource(configResourcePath);
        Assertions.assertNotNull(configLocation, "missing configuration resource: " + configResourcePath);
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

    // -----------------------------------------------------------------------------------------------------------
    // Reading captures and baselines, and the fresh-destination contract
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Reads a classpath resource as text.
     * <p>
     * The whole byte content is decoded in one step with an explicit charset. Reading by lines is deliberately
     * avoided: it would discard whether the content ends with a newline, and that disposition is part of what the
     * comparison asserts. A zero-length resource yields an empty string rather than an empty line.
     * </p>
     *
     * @param resourcePath absolute classpath path of the resource
     * @return the decoded content, with no trimming of any kind
     * @throws IOException if the resource cannot be read
     */
    static String readResource(final String resourcePath) throws IOException {
        final URL url = ParityCorpus.class.getResource(resourcePath);
        Assertions.assertNotNull(url, "missing classpath resource: " + resourcePath);
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
     * Reads a file as text, using the same decoding and the same newline discipline as {@link #readResource}, so
     * that a capture and a baseline are always read by identical code.
     *
     * @param file the file to read
     * @return the decoded content, with no trimming of any kind
     * @throws IOException if the file cannot be read
     */
    static String readFile(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * Removes a destination file if it is present.
     * <p>
     * This is the fresh-destination contract, and it is load-bearing. Four fixtures write to one shared
     * destination and none of them declares an append option, whose default appends; the file managers are also
     * keyed by destination name and reference-counted, so a stale file must be removed while no context holds its
     * manager — that is, immediately before the next fixture is booted, never during a capture. Without it, one
     * fixture's capture would carry the previous fixture's lines, and the fixture whose root level suppresses every
     * scripted event — whose capture must be byte-empty — would appear to have rendered them.
     * </p>
     * <p>
     * The removal is idempotent and reports what it did rather than discarding the outcome, so a caller that cares
     * whether a stale file was present can say so.
     * </p>
     *
     * @param destination the destination file
     * @return {@code true} if a file existed and was removed, {@code false} if there was nothing to remove
     * @throws IOException if the file exists but cannot be removed
     */
    static boolean deleteDestination(final Path destination) throws IOException {
        return Files.deleteIfExists(destination);
    }

    /**
     * One scripted event: an immutable value carrying the four mandatory fields of a script data line plus the
     * optional throwable specification.
     */
    static final class Event {

        private final String configId;

        private final String loggerName;

        private final Level level;

        private final String message;

        private final String throwableSpec;

        private Event(
                final String configId,
                final String loggerName,
                final Level level,
                final String message,
                final String throwableSpec) {
            this.configId = configId;
            this.loggerName = loggerName;
            this.level = level;
            this.message = message;
            this.throwableSpec = throwableSpec;
        }

        /** Configuration id this event belongs to, {@code T1} through {@code T7}. */
        String configId() {
            return configId;
        }

        /**
         * Logger name, carried character for character from the script.
         * <p>
         * Every recorded name is the name its arm's acquisition site requests, and one of them names a different
         * benchmark than the arm that emitted the event. None may be "corrected": the name is the logger's
         * identity, and therefore its configured level and appender wiring.
         * </p>
         */
        String loggerName() {
            return loggerName;
        }

        /** Level the event is emitted at, exactly as scripted. */
        Level level() {
            return level;
        }

        /**
         * Fully rendered message text.
         * <p>
         * Messages that the original arms built by concatenation are carried here as their rendered result and
         * are emitted as a single string. They are deliberately <em>not</em> converted to a parameterized form:
         * the parameterized arms already exist as separate benchmarks, so converting would destroy the very
         * comparison the concatenating arms exist to make.
         * </p>
         */
        String message() {
            return message;
        }

        /**
         * Optional throwable specification, of the form {@code SimpleName:message}, or {@code null} when the
         * event carries no throwable.
         */
        String throwableSpec() {
            return throwableSpec;
        }

        @Override
        public String toString() {
            return configId
                    + '|'
                    + loggerName
                    + '|'
                    + level
                    + '|'
                    + message
                    + (throwableSpec == null ? "" : "|" + throwableSpec);
        }
    }
}
