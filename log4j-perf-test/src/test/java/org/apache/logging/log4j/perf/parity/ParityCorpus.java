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
 * Shared harness for the output-parity gates over this module's seven translated logging configurations, holding the
 * fixed event script, the normalizer and the isolated-context helper so that a committed baseline and the capture
 * compared against it are produced by <em>identical code</em>. Three contracts live here rather than in the consuming
 * tests: events carry a fabricated {@link StackTraceElement}, because a plain {@code logger.debug(message)} would
 * resolve the caller-location tokens into this harness; the one scripted throwable is given a fixed trace, which would
 * otherwise render environment-dependent frames; and a destination is removed immediately before the configuration
 * that writes it boots. The peer gate over the other module's three configurations shares no code with this file,
 * because that module compiles at a lower release level and depending on this one would close a build cycle.
 */
final class ParityCorpus {

    static final String EVENT_SCRIPT_RESOURCE = "/log4j1-parity/event-script.txt";

    /**
     * Destination shared by the four synchronous file fixtures, none of which declares an append option; the default
     * appends, so this file is removed immediately before each of those configurations boots.
     */
    static final Path TESTLOG4J_DESTINATION = Paths.get("target", "testlog4j.log");

    /**
     * Destination of the asynchronous file fixture, declared relative with no directory, so it lands in the module
     * directory where neither the ignore rules nor the licence-audit exclusions cover it: a leftover copy would show
     * up as an untracked file and fail the licence audit.
     */
    static final Path PERFTEST_DESTINATION = Paths.get("perftest.log");

    /**
     * Method name carried by every fabricated caller frame. Only one fixture renders the caller-location tokens and
     * its committed baseline renders {@code FileAppenderWithLocationBenchmark.log4j1File}, so this is that arm's
     * method name, applied uniformly because no other fixture's layout renders a caller.
     */
    private static final String EMITTING_METHOD_NAME = "log4j1File";

    /**
     * Line number carried by every fabricated caller frame. Never compared, because the normalizer replaces it, but
     * it must be fixed so a replay is reproducible and positive so the converter renders digits.
     */
    private static final int FIXED_SOURCE_LINE_NUMBER = 1;

    /**
     * Declaring class of the fabricated frames given to the scripted throwable: a frame identity, not a type. No
     * such class exists; the name and both frames below were transcribed from the committed capture.
     */
    private static final String THROWABLE_FIXTURE_CLASS = "org.apache.logging.log4j.perf.parity.ThrowableParityFixture";

    private static final String THROWABLE_FIXTURE_FILE = "ThrowableParityFixture.java";

    private static final String ILLEGAL_STATE_EXCEPTION = "IllegalStateException";

    /**
     * Grammar of a script data line: {@code <configId>|<loggerName>|<LEVEL>|<message>[|<throwableSpec>]}. A full match
     * with capture groups is used in preference to splitting, because {@code [^|]} cannot cross a delimiter, so a
     * message is never split further and an absent fifth field is distinguishable from an empty one. The level alphabet
     * is closed at the three levels the recorded arms emit, because a level no arm emits could only enter this corpus
     * as an invented event.
     */
    private static final Pattern DATA_LINE =
            Pattern.compile("^(T[1-7])\\|([^|]+)\\|(DEBUG|INFO|ERROR)\\|([^|]+)(?:\\|([^|]+))?$");

    private static final String FILE_APPENDER_LOGGER = "org.apache.logging.log4j.perf.jmh.FileAppenderBenchmark";

    /**
     * Logger acquired by the parameterizing file-appender benchmark, whose acquisition is <em>dynamic</em>: it asks
     * for the class of its own state object and the harness instantiates a generated subclass, so the resolved name
     * carries the generated subpackage and suffix, which the committed baseline abbreviates.
     */
    private static final String FILE_APPENDER_PARAMS_LOGGER =
            "org.apache.logging.log4j.perf.jmh.jmh_generated.FileAppenderParamsBenchmark_jmhType";

    private static final String DEBUG_DISABLED_LOGGER = "org.apache.logging.log4j.perf.jmh.DebugDisabledBenchmark";

    /**
     * Logger acquired by the with-location file-appender benchmark. One of the two events recorded on this name
     * belongs to a <em>different</em> benchmark, which requests its logger by this class literal rather than its own;
     * that is carried verbatim regardless, because the name is the logger's identity and so its appender wiring.
     */
    private static final String FILE_APPENDER_WITH_LOCATION_LOGGER =
            "org.apache.logging.log4j.perf.jmh.FileAppenderWithLocationBenchmark";

    private static final String FILE_APPENDER_THROWABLE_LOGGER =
            "org.apache.logging.log4j.perf.jmh.FileAppenderThrowableBenchmark";

    /**
     * Logger acquired by the asynchronous throughput-and-latency runner, the one recorded name outside the benchmark
     * package. That arm is instantiated directly and never subclassed, so no generated name arises here.
     */
    private static final String RUN_LOG4J1_LOGGER = "org.apache.logging.log4j.core.async.perftest.RunLog4j1";

    /**
     * Logger acquired dynamically by the asynchronous no-op-appender benchmark, so the recorded name carries the
     * generated subpackage and suffix. This fixture writes no file, so the consuming test asserts the name directly.
     */
    private static final String ASYNC_APPENDER_LOGGER =
            "org.apache.logging.log4j.perf.jmh.jmh_generated.AsyncAppenderLog4j1Benchmark_jmhType";

    private static final String ASYNC_APPENDER_LOCATION_LOGGER =
            "org.apache.logging.log4j.perf.jmh.jmh_generated.AsyncAppenderLog4j1LocationBenchmark_jmhType";

    private static final String SIXTEEN_CHARACTER_MESSAGE = "aaaaaaaaaaaaaaaa";

    private static final String THROWABLE_SPEC = ILLEGAL_STATE_EXCEPTION + ":Test Throwable";

    /**
     * The complete manifest of scripted events, in significant order, rendered in the script's own grammar. It is
     * stronger than a count on purpose: a count alone admits an event that has been re-levelled, moved to another
     * logger, reworded, reordered or invented, every drift that would silently weaken a parity gate while leaving its
     * arithmetic intact. <strong>No invented event appears and none may be added</strong>, because an event no arm
     * emits carries no parity information.
     */
    private static final List<String> EXPECTED_MANIFEST = expectedManifest();

    private static final Map<String, Integer> EXPECTED_EVENT_COUNTS = expectedEventCounts(EXPECTED_MANIFEST);

    private static final Map<String, List<Event>> EVENTS_BY_CONFIG_ID = loadEventScript();

    private ParityCorpus() {}

    /**
     * Returns the scripted events for one configuration id, in the script's own encounter order. Order is
     * significant: the concatenating arms' messages embed counter values captured for one fresh pass in this order,
     * so replaying a group out of order, or twice, changes the rendered text.
     */
    static List<Event> events(final String configId) {
        final List<Event> events = EVENTS_BY_CONFIG_ID.get(configId);
        if (events == null) {
            throw new IllegalArgumentException("no such configuration id in " + EVENT_SCRIPT_RESOURCE + ": " + configId
                    + "; known ids are " + EVENTS_BY_CONFIG_ID.keySet());
        }
        return events;
    }

    /** Assembled in a method rather than a literal, using only the API surface shared with the peer module. */
    private static List<String> expectedManifest() {
        final List<String> manifest = new ArrayList<>();

        // T1 -- synchronous, shared destination, root level admits debug. One event from the plain file-appender
        // arm and two from the parameterizing arm, whose second message embeds instance counters.
        manifest.add(record("T1", FILE_APPENDER_LOGGER, Level.DEBUG, "This is a debug message"));
        manifest.add(record("T1", FILE_APPENDER_PARAMS_LOGGER, Level.DEBUG, "This is a debug [1] message"));
        manifest.add(record("T1", FILE_APPENDER_PARAMS_LOGGER, Level.DEBUG, "Val1=2, val2=1, val3=1"));

        // T2 -- the same layout and destination at root level error. Both events are suppressed by that root level,
        // which is the evidence this fixture exists to give, so its capture is byte-empty.
        manifest.add(record("T2", DEBUG_DISABLED_LOGGER, Level.DEBUG, "This is a debug [2] message"));
        manifest.add(record("T2", FILE_APPENDER_WITH_LOCATION_LOGGER, Level.DEBUG, "This won't be logged"));

        // T3 -- the only fixture whose layout renders the caller class, method and line.
        manifest.add(record("T3", FILE_APPENDER_WITH_LOCATION_LOGGER, Level.DEBUG, "This is a debug message"));

        // T4 -- the only event that carries a throwable, and so the only record with a fifth field.
        manifest.add(record("T4", FILE_APPENDER_THROWABLE_LOGGER, Level.ERROR, "Caught an exception", THROWABLE_SPEC));

        // T5 -- the asynchronous file fixture. Recorded at info because every emission of the runner this entry is
        // carried from is at info: the level comes from the same source as the name and the message, never from
        // whatever the fixture's root level would admit.
        manifest.add(record("T5", RUN_LOG4J1_LOGGER, Level.INFO, "Short msg"));

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

    private static String record(
            final String configId, final String loggerName, final Level level, final String message) {
        return configId + '|' + loggerName + '|' + level.name() + '|' + message;
    }

    private static String record(
            final String configId,
            final String loggerName,
            final Level level,
            final String message,
            final String throwableSpec) {
        return record(configId, loggerName, level, message) + '|' + throwableSpec;
    }

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
     * Parses the committed script into an immutable, ordered index. Every data line must match {@link #DATA_LINE} in
     * full and the sequence must equal {@link #EXPECTED_MANIFEST} in order; verifying the manifest before the counts
     * reports a drifted entry as exactly that, at the record where it occurs.
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
     * Returns a corpus fixture's significant lines, in encounter order, with {@code #}-comment and blank lines dropped.
     * Both fixtures interleave records with explanatory comments, so the skipping rule lives here once; nothing else is
     * dropped, trimmed or reordered, because a record's bytes are part of what the caller verifies.
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
     * Verifies the recorded data records against {@link #EXPECTED_MANIFEST}, by whole record and in order, so one step
     * covers the id, logger name, level, message, throwable field and order. A length mismatch is reported first, so a
     * truncated fixture is not reported at its first surplus record.
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

    /**
     * Replays one fresh pass of a configuration id's scripted events through the supplied context, in script order,
     * through the fluent builder -- the only public route accepting an explicit caller frame. Level filtering is
     * honoured there, so an event the fixture suppresses contributes nothing to the capture, which is the evidence the
     * suppressed events exist to provide. No draining happens here: the caller must stop the context before reading the
     * capture, and must retrieve any appender reference <em>before</em> the replay, because stopping a context detaches
     * its configuration.
     */
    static void replay(final LoggerContext context, final String configId) {
        for (final Event event : events(configId)) {
            emit(context, event);
        }
    }

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
     * Builds the fabricated caller frame for one event, whose declaring class is the event's <em>own logger name</em> so
     * the class converter renders the simple name the committed capture records. The no-argument overload of the
     * builder's location method is not used, because it walks the stack into this harness, and appending a constructed
     * log event directly is rejected, because it would bypass level filtering and destroy the suppression evidence.
     */
    private static StackTraceElement fixedLocation(final Event event) {
        final String declaringClass = event.loggerName();
        return new StackTraceElement(
                declaringClass, EMITTING_METHOD_NAME, simpleName(declaringClass) + ".java", FIXED_SOURCE_LINE_NUMBER);
    }

    private static String simpleName(final String qualifiedName) {
        final int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    /**
     * Builds the throwable a scripted event specifies and gives it a fixed stack trace. The specification is
     * {@code SimpleName:message}, split on its <em>first</em> colon, and the name is resolved through a closed mapping
     * rather than reflective class loading, so an unrecognised name fails loudly. Natural frames are
     * environment-dependent while the fixture renders the throwable in full and compares it byte for byte, so the trace
     * is replaced with the frames transcribed from the committed capture. The comparison therefore proves the throwable
     * <em>rendering</em> -- ordering, header shape, the tab-and-{@code at } prefix, the plain rather than extended
     * converter, the single terminating separator -- and nothing about a real throwable's frames, which costs nothing
     * because frames are captured at construction and only their rendering was in question.
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

    private static StackTraceElement[] fixedThrowableStackTrace() {
        return new StackTraceElement[] {
            new StackTraceElement(THROWABLE_FIXTURE_CLASS, "emit", THROWABLE_FIXTURE_FILE, 42),
            new StackTraceElement(THROWABLE_FIXTURE_CLASS, "main", THROWABLE_FIXTURE_FILE, 17)
        };
    }

    private static final String TIMESTAMP_TOKEN = "<TS>";

    private static final String THREAD_TOKEN = "[<THREAD>]";

    private static final String LINE_TOKEN = ":<LINE>";

    private static final String PATH_TOKEN = "<PATH>";

    /**
     * The default date format, anchored to the start of a line, accepting either fractional separator so the anchor
     * cannot be defeated by a formatter variant.
     */
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[,.]\\d{3}");

    /**
     * A bracketed thread name. Only the <em>first</em> occurrence on a timestamp-bearing line is replaced: the thread
     * field precedes the message, and one scripted message contains a bracketed number that must survive untouched.
     */
    private static final Pattern BRACKETED_THREAD = Pattern.compile("\\[[^\\]\\n]*\\]");

    /**
     * A rendered source line number, anchored on the two spaces and separator that follow it in the only layout
     * rendering caller location, which keeps the line numbers inside a rendered stack frame out of reach.
     */
    private static final Pattern SOURCE_LINE_NUMBER = Pattern.compile(":\\d+(?=  - )");

    /**
     * A genuinely absolute filesystem path, starting at a token boundary so a relative destination is never matched.
     */
    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("(?<![^\\s(\\[])(?:[A-Za-z]:[\\\\/]|/)[\\w.+~@%$-]+(?:[\\\\/][\\w.+~@%$-]+)*[\\\\/]?");

    /**
     * Normalizes rendered output so a capture can be compared with a committed baseline. Exactly four elements are
     * substituted and <strong>no fifth may ever be added</strong>: timestamp, thread name, source line number -- whose
     * neighbouring class and method components are <em>not</em> normalized -- and absolute paths. <strong>Everything
     * else is compared byte for byte</strong>, including level padding, logger-name abbreviation, every literal
     * separator, the double space and trailing space an absent context-map key leaves behind, and a throwable's
     * tab-indented frames. Nothing is trimmed, no run of spaces is collapsed and no line separator is rewritten,
     * because that would be the forbidden fifth token; lines split and rejoin on the line feed with a negative limit,
     * so a final newline's presence survives. The function is pure and idempotent.
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
     * Normalizes one line. The thread and line-number tokens are confined to timestamp-bearing lines, which costs
     * nothing because every layout rendering either also opens with the timestamp, and forecloses two hazards: a message
     * containing brackets, and a throwable's frames, whose line numbers are compared output.
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
     * Replaces the first match of a pattern by index surgery, used in preference to the regular-expression replacement
     * methods because those interpret the replacement string: a captured backslash or dollar sign in rendered output
     * would either be consumed or raise an error.
     */
    private static String replaceFirst(final String input, final Pattern pattern, final String replacement) {
        final Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        return input.substring(0, matcher.start()) + replacement + input.substring(matcher.end());
    }

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

    private static final String CONTEXT_NAME_PREFIX = "log4j1-parity-";

    /**
     * Boots one fixture in its own logger context from its classpath location, which keeps its original name.
     * <strong>A non-null configuration location is mandatory, not stylistic:</strong> the configuration factory guards
     * its entire system-property-reading block on the location being absent, so a non-null location makes the context
     * property-independent by construction, which lets several fixtures boot in one virtual machine without contending
     * over a global key. The started context is the caller's to close, and stopping it is what drains an asynchronous
     * queue and flushes a buffered writer, so that must precede reading the capture; a failed boot is stopped here,
     * because an unpublished context cannot be closed by anyone else.
     * <p>
     *   Every boot additionally asserts that the fixture suppressed the JVM shutdown hook. That is a real invariant of
     *   the translated set rather than a stylistic preference: the superseded generation installed no hook, relying on
     *   an explicit shutdown call, and in this generation registering one makes {@code LoggerContext.start()} reach
     *   {@code LogManager.getFactory()}, which builds the process-global context factory and, permanently, the context
     *   selector it reads from {@code Log4jContextSelector} at that instant -- so a benchmark arm that assigns that
     *   property after a fixture has booted would silently be handed the default selector. Asserting it here turns the
     *   invariant into a gate over every fixture this corpus boots, so dropping the attribute from one of them fails
     *   the build instead of quietly displacing a neighbouring arm.
     * </p>
     */
    static LoggerContext startContext(final String configId, final String configResourcePath)
            throws URISyntaxException {
        final URL configLocation = ParityCorpus.class.getResource(configResourcePath);
        Assertions.assertNotNull(configLocation, "missing configuration resource: " + configResourcePath);
        final LoggerContext starting = new LoggerContext(CONTEXT_NAME_PREFIX + configId, null, configLocation.toURI());
        boolean started = false;
        try {
            starting.start();
            Assertions.assertFalse(
                    starting.getConfiguration().isShutdownHookEnabled(),
                    () -> configResourcePath + " must declare shutdownHook=\"disable\": registering a JVM shutdown"
                            + " hook initialises the process-global context factory and freezes its context selector");
            started = true;
        } finally {
            if (!started) {
                Configurator.shutdown(starting);
            }
        }
        return starting;
    }

    /**
     * Reads a classpath resource as text, decoding the whole byte content in one step with an explicit charset.
     * Reading by lines would discard whether the content ends with a newline, and that is part of what the comparison
     * asserts.
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
     * Reads a file with the same decoding and newline discipline as {@link #readResource}, so a capture and a baseline
     * are read by identical code.
     */
    static String readFile(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * Removes a destination file if present. This fresh-destination contract is load-bearing: four fixtures write to
     * one shared destination, none declares an append option and the default appends, while file managers are keyed by
     * destination name and reference-counted, so a stale file must be removed while no context holds its manager --
     * immediately before the next fixture boots, never during a capture. Without it one fixture's capture would carry
     * the previous fixture's lines, and the fixture whose capture must be byte-empty would appear to have rendered them.
     */
    static boolean deleteDestination(final Path destination) throws IOException {
        return Files.deleteIfExists(destination);
    }

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

        String configId() {
            return configId;
        }

        /**
         * Logger name, carried character for character from the script: the name its arm's acquisition site resolves, which
         * for the arms asking for the class of their own state object is the generated state subclass. None may be
         * "corrected", because the name is the logger's identity and so its appender wiring.
         */
        String loggerName() {
            return loggerName;
        }

        Level level() {
            return level;
        }

        /**
         * Fully rendered message text, emitted as a single string and deliberately <em>not</em> parameterized: the
         * parameterized arms already exist as separate benchmarks, so converting would destroy the comparison the
         * concatenating arms exist to make.
         */
        String message() {
            return message;
        }

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
