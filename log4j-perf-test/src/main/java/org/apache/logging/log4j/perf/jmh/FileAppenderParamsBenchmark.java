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

import static org.apache.logging.log4j.util.Unbox.box;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
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

/**
 * Benchmarks Log4j 2, the arm formerly driven by Log4j 1 (now also native Log4j 2, against the migrated Log4j 1
 * configuration), Logback and JUL using the DEBUG level which is enabled for this test. The configuration
 * for each uses a FileAppender
 */
@State(Scope.Thread)
public class FileAppenderParamsBenchmark {
    private FileHandler julFileHandler;
    Logger log4j2Logger;
    Logger log4j2RandomLogger;
    org.slf4j.Logger slf4jLogger;
    org.apache.logging.log4j.Logger log4j1Logger;
    java.util.logging.Logger julLogger;
    int j, k, m;

    @Setup
    public void setUp() throws Exception {
        System.setProperty("log4j.configurationFile", "log4j2-perf.xml");
        System.setProperty("logback.configurationFile", "logback-perf.xml");

        deleteLogFiles();

        log4j2Logger = LogManager.getLogger(getClass());
        log4j2RandomLogger = LogManager.getLogger("TestRandom");
        slf4jLogger = LoggerFactory.getLogger(getClass());
        // The arm formerly driven by Log4j 1.x is now native Log4j 2, and it is deliberately NOT bootstrapped
        // through a global selector property. `log4j.configurationFile` above already belongs to the Log4j 2
        // arm, and ConfigurationFactory returns on the first key it resolves, so a second global key would
        // silently mis-configure one of the two arms. Handing a non-null configuration URI to the LoggerContext
        // constructor skips property lookup altogether, keeping both arms independent inside a single JVM and
        // leaving the Log4j 2, Logback and JUL arms exactly as they were. The context itself is owned by the
        // benchmark-scoped holder at the foot of this class and shared by every JMH worker, because this state
        // is thread-scoped while the superseded generation shared one repository -- and one file appender --
        // per JVM.
        final LoggerContext log4j1Context = Log4j1ArmContext.acquire();
        boolean armReady = false;
        try {
            // The logger name is preserved byte-for-byte: Log4j 1.x derived it from clazz.getName(), and `clazz`
            // was whatever getClass() returned. JMH subclasses this @State class, so getClass() -- not a class
            // literal -- is what keeps the events on the logger they have always used.
            log4j1Logger = log4j1Context.getLogger(getClass().getName());

            julFileHandler = new FileHandler("target/testJulLog.log");
            julLogger = java.util.logging.Logger.getLogger(getClass().getName());
            julLogger.setUseParentHandlers(false);
            julLogger.addHandler(julFileHandler);
            julLogger.setLevel(Level.ALL);
            j = 0;
            armReady = true;
        } finally {
            if (!armReady) {
                // The JUL handler above can fail with an IOException, and JMH does not tear down a state
                // whose setup threw, so the shared context is handed back here rather than left started.
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
        final File log4jRandomFile = new File("target/testRandomlog4j2.log");
        log4jRandomFile.delete();
        final File log4j2File = new File("target/testlog4j2.log");
        log4j2File.delete();
        final File julFile = new File("target/testJulLog.log");
        julFile.delete();
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param1Log4j1Concat() {
        log4j1Logger.debug("This is a debug [" + ++j + "] message");
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param3Log4j1Concat() {
        log4j1Logger.debug("Val1=" + ++j + ", val2=" + ++k + ", val3=" + ++m);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param1Log4j2RAF() {
        log4j2RandomLogger.debug("This is a debug [{}] message", box(++j));
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param3Log4j2RAF() {
        log4j2RandomLogger.debug("Val1={}, val2={}, val3={}", box(++j), box(++k), box(++m));
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param1Log4j2File() {
        log4j2Logger.debug("This is a debug [{}] message", box(++j));
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param3Log4j2File() {
        log4j2Logger.debug("Val1={}, val2={}, val3={}", box(++j), box(++k), box(++m));
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param1LogbackFile() {
        slf4jLogger.debug("This is a debug [{}] message", ++j);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param3LogbackFile() {
        slf4jLogger.debug("Val1={}, val2={}, val3={}", (++j), (++k), (++m));
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param1JulFile() {
        // must specify sourceClass or JUL will look it up by walking the stack trace!
        julLogger.logp(Level.INFO, getClass().getName(), "param1JulFile", "This is a debug [{}] message", ++j);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void param3JulFile() {
        // must specify sourceClass or JUL will look it up by walking the stack trace!
        julLogger.logp(Level.INFO, getClass().getName(), "param3JulFile", "Val1={}, val2={}, val3={}", new Object[] {
            ++j, ++k, ++m
        });
    }
    /**
     * Benchmark-scoped owner of the native Log4j 2 context that replaces the process-wide Log4j 1.x
     * repository the migrated arm used to reach through a global selector property.
     *
     * <p>The superseded generation kept one repository -- and therefore one file appender -- per JVM,
     * shared by every JMH worker thread. Because the enclosing state is thread-scoped, a context built
     * inside {@code setUp()} would instead be built once per worker, giving each worker its own appender
     * and its own handle on {@code target/testlog4j.log}, which would change exactly the sharing and
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
                final URL configLocation = FileAppenderParamsBenchmark.class.getResource("/log4j12-perf.xml");
                final LoggerContext starting =
                        new LoggerContext("FileAppenderParamsBenchmark", null, configLocation.toURI());
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
