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
package org.apache.logging.log4j.perf.jmh;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.slf4j.LoggerFactory;

@State(Scope.Thread)
public class LoggingDisabledBenchmark {

    Logger log4j2Logger;
    org.slf4j.Logger slf4jLogger;
    org.apache.logging.log4j.Logger log4j1Logger;

    @Setup
    public void setUp() throws Exception {
        System.setProperty("log4j.configurationFile", "log4j2-perf2.xml");
        System.setProperty("logback.configurationFile", "logback-perf2.xml");

        deleteLogFiles();

        log4j2Logger = LogManager.getLogger(FileAppenderWithLocationBenchmark.class);
        slf4jLogger = LoggerFactory.getLogger(FileAppenderWithLocationBenchmark.class);
        // The migrated arm is configured by the benchmark-scoped holder at the foot of this class rather than
        // by a second global selector key, and that one context is shared by every JMH worker of the trial.
        final LoggerContext log4j1Context = Log4j1ArmContext.acquire();
        boolean armReady = false;
        try {
            // The cross-class logger name is deliberate and preserved verbatim: this benchmark has always
            // asked for a logger named after FileAppenderWithLocationBenchmark, and renaming it would move
            // the events onto a different logger, level and appender wiring.
            log4j1Logger = log4j1Context.getLogger(FileAppenderWithLocationBenchmark.class.getName());
            armReady = true;
        } finally {
            if (!armReady) {
                // JMH does not tear down a state whose setup threw, so the shared context is handed back
                // here instead of being left started for the remainder of the JVM's life.
                Log4j1ArmContext.release();
            }
        }
    }

    @TearDown
    public void tearDown() {
        System.clearProperty("log4j.configurationFile");
        Log4j1ArmContext.release();
        System.clearProperty("logback.configurationFile");

        deleteLogFiles();
    }

    private void deleteLogFiles() {
        final File logbackFile = new File("target/testlogback.log");
        logbackFile.delete();
        final File log4jFile = new File("target/testlog4j.log");
        log4jFile.delete();
        final File log4j2File = new File("target/testlog4j2.log");
        log4j2File.delete();
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void baseline() {}

    /*
      This benchmark tests the overhead of NewRelic on method calls. It is commented out so
      that we don't have to include the dependency during a "normal" build. Uncomment and add
      the New Relic Agent client dependency if you would like to test this.
    @Benchmark
    @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Trace(dispatcher = true)
    public void log4j2NewRelic() {
        log4j2Logger.debug("This won't be logged");
    } */

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void log4j2() {
        log4j2Logger.debug("This won't be logged");
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void slf4j() {
        slf4jLogger.debug("This won't be logged");
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void log4j2IsDebugEnabled() {
        if (log4j2Logger.isDebugEnabled()) {
            log4j2Logger.debug("This won't be logged");
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void slf4jIsDebugEnabled() {
        if (slf4jLogger.isDebugEnabled()) {
            slf4jLogger.debug("This won't be logged");
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void log4j1IsDebugEnabled() {
        if (log4j1Logger.isDebugEnabled()) {
            log4j1Logger.debug("This won't be logged");
        }
    }

    /**
     * Benchmark-scoped owner of the native Log4j 2 context that replaces the process-wide Log4j 1.x
     * repository the migrated arm used to reach through a global selector property.
     *
     * <p>The superseded generation kept one repository -- and therefore one appender graph -- per JVM,
     * shared by every JMH worker thread. Because the enclosing state is thread-scoped, a context built
     * inside {@code setUp()} would instead be built once per worker and would change the sharing and
     * contention this benchmark measures. The context is therefore held here, created by whichever worker
     * sets up first, shared by all the others, and reference counted so that it is stopped exactly once,
     * when the last worker of the trial tears down. Dropping the reference on the way out means a run that
     * replays several trials in a single JVM, such as {@code -f 0}, gets a freshly started context for each
     * trial rather than a stopped one.</p>
     */
    private static final class Log4j1ArmContext {

        private static LoggerContext context;

        private static int users;

        private Log4j1ArmContext() {}

        static synchronized LoggerContext acquire() throws URISyntaxException {
            if (context == null) {
                final URL configLocation = LoggingDisabledBenchmark.class.getResource("/log4j12-perf2.xml");
                final LoggerContext starting =
                        new LoggerContext("LoggingDisabledBenchmark", null, configLocation.toURI());
                try {
                    starting.start();
                } catch (final RuntimeException | Error startFailure) {
                    // A context that was never published cannot be stopped by anyone else.
                    Configurator.shutdown(starting);
                    throw startFailure;
                }
                context = starting;
            }
            users++;
            return context;
        }

        static synchronized void release() {
            if (users == 0) {
                return;
            }
            if (--users == 0) {
                Configurator.shutdown(context);
                context = null;
            }
        }
    }
}
