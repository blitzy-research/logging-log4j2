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
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
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
 * Benchmarks Log4j 2 in two separately configured logger contexts, Logback and JUL using the DEBUG level which is
 * enabled for this test. The configuration for each uses a FileAppender and captures caller location information
 */
@State(Scope.Thread)
public class FileAppenderWithLocationBenchmark {
    public static final String MESSAGE = "This is a debug message";
    private FileHandler julFileHandler;

    Logger log4j2Logger;
    Logger log4j2RandomLogger;
    org.slf4j.Logger slf4jLogger;
    org.apache.logging.log4j.Logger log4j1Logger;
    private LoggerContext log4j1Context;

    @Setup
    public void setUp() throws Exception {
        System.setProperty("log4j.configurationFile", "log4j2-perfloc.xml");
        System.setProperty("logback.configurationFile", "logback-perfloc.xml");

        deleteLogFiles();

        log4j2Logger = LogManager.getLogger(FileAppenderWithLocationBenchmark.class);
        log4j2RandomLogger = LogManager.getLogger("TestRandom");
        slf4jLogger = LoggerFactory.getLogger(FileAppenderWithLocationBenchmark.class);
        // This arm gets a logger context of its own instead of a global selector property. `log4j.configurationFile`
        // above belongs to the other Log4j 2 arm -- which owns the two loggers acquired just above -- and
        // ConfigurationFactory returns on the first key it resolves, so a second global key would silently
        // mis-configure one of the two arms. That is especially damaging here: the peer configuration abbreviates
        // the caller class with %C{1.} while this arm's configuration uses %C{1}, a different abbreviation, so a
        // hijack would change rendered output rather than fail loudly. A non-null configuration URI makes the
        // LoggerContext constructor skip property lookup altogether, which is what keeps the two arms independent
        // inside a single JVM.
        final URL log4j1ConfigLocation = FileAppenderWithLocationBenchmark.class.getResource("/log4j12-perfloc.xml");
        // A fixture that was renamed or left out of the artifact is reported by name here, rather than as a bare
        // NullPointerException from the URI conversion below.
        if (log4j1ConfigLocation == null) {
            throw new IllegalStateException("missing configuration resource: /log4j12-perfloc.xml");
        }
        // That fixture declares shutdownHook="disable", and the attribute is load-bearing rather than cosmetic.
        // Registering the hook makes LoggerContext.start() reach LogManager.getFactory(), which builds the
        // process-global context factory and, permanently, the context selector it reads from Log4jContextSelector
        // at that instant -- so a peer arm assigning that property afterwards would silently be handed the default
        // selector instead of the one it asked for. Suppressing the hook is also what the superseded generation did:
        // it installed none, relying on an explicit shutdown call, exactly as the teardown below does. Any fixture
        // booted through a context of its own must therefore keep the attribute; the parity gates assert it.
        // The context is started into a local and published to the field only once this arm is fully
        // initialised, because JMH does not invoke the teardown of a state whose setup threw: a context
        // assigned before a later failure would stay started for the remainder of the JVM's life, holding its
        // configuration and its file manager, with nothing left able to reach it.
        final LoggerContext starting =
                new LoggerContext("FileAppenderWithLocationBenchmark", null, log4j1ConfigLocation.toURI());
        boolean armReady = false;
        try {
            starting.start();
            // The exact class name is required here: it is the name the Log4j 2, Logback and JUL arms use, so
            // every arm emits on one logger name and the measurements stay comparable.
            log4j1Logger = starting.getLogger(FileAppenderWithLocationBenchmark.class.getName());
            armReady = true;
        } finally {
            if (!armReady) {
                Configurator.shutdown(starting);
            }
        }
        log4j1Context = starting;
    }

    @TearDown
    public void tearDown() {
        System.clearProperty("log4j.configurationFile");
        Configurator.shutdown(log4j1Context);
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
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j2RAF() {
        log4j2RandomLogger.debug(MESSAGE);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j2File() {
        log4j2Logger.debug(MESSAGE);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j2FluentFile() {
        log4j2Logger.atDebug().withLocation().log(MESSAGE);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void logbackFile() {
        slf4jLogger.debug(MESSAGE);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j1File() {
        log4j1Logger.debug(MESSAGE);
    }
}
