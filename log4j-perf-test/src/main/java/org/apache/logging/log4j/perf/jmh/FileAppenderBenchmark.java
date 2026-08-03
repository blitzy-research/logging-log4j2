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
 * Benchmarks Log4j 2 in two separately configured logger contexts, Logback and JUL using the DEBUG level which is
 * enabled for this test. The configuration for each uses a FileAppender
 */
@State(Scope.Thread)
public class FileAppenderBenchmark {
    public static final String MESSAGE = "This is a debug message";
    private FileHandler julFileHandler;

    Logger log4j2Logger;
    Logger log4j2AsyncAppender;
    Logger log4j2AsyncLogger;
    Logger log4j2AsyncDisruptor;
    Logger log4j2RandomLogger;
    Logger log4j2MemoryLogger;
    org.slf4j.Logger slf4jLogger;
    org.slf4j.Logger slf4jAsyncLogger;
    org.apache.logging.log4j.Logger log4j1Logger;
    private LoggerContext log4j1Context;
    java.util.logging.Logger julLogger;

    @Setup
    public void setUp() throws Exception {
        System.setProperty("log4j.configurationFile", "log4j2-perf.xml");
        System.setProperty("logback.configurationFile", "logback-perf.xml");

        deleteLogFiles();

        log4j2Logger = LogManager.getLogger(FileAppenderBenchmark.class);
        log4j2AsyncAppender = LogManager.getLogger("AsyncAppender");
        log4j2AsyncDisruptor = LogManager.getLogger("AsyncDisruptorAppender");
        log4j2AsyncLogger = LogManager.getLogger("AsyncLogger");
        // log4j2MemoryLogger = LogManager.getLogger("MemoryMapped");
        log4j2RandomLogger = LogManager.getLogger("TestRandom");
        slf4jLogger = LoggerFactory.getLogger(FileAppenderBenchmark.class);
        slf4jAsyncLogger = LoggerFactory.getLogger("Async");
        // This arm gets a logger context of its own instead of a global selector property. `log4j.configurationFile`
        // above belongs to the other Log4j 2 arm -- which owns the loggers acquired just above, including the
        // asynchronous ones -- and ConfigurationFactory returns on the first key it resolves, so a second global key
        // would silently mis-configure one of the two arms. A non-null configuration URI makes the LoggerContext
        // constructor skip property lookup altogether, which is what keeps the two arms independent
        // inside a single JVM.
        final URL log4j1ConfigLocation = FileAppenderBenchmark.class.getResource("/log4j12-perf.xml");
        // A fixture that was renamed or left out of the artifact is reported by name here, rather than as a bare
        // NullPointerException from the URI conversion below.
        if (log4j1ConfigLocation == null) {
            throw new IllegalStateException("missing configuration resource: /log4j12-perf.xml");
        }
        // The context is started into a local and published to the field only once every remaining setup step
        // has succeeded, because JMH does not invoke the teardown of a state whose setup threw. The JUL handler
        // constructed below reaches the filesystem and can therefore fail: a context assigned before that
        // failure would stay started for the remainder of the JVM's life, holding its configuration, its file
        // manager and its shutdown callback, with nothing left able to reach it.
        final LoggerContext starting = new LoggerContext("FileAppenderBenchmark", null, log4j1ConfigLocation.toURI());
        boolean armReady = false;
        try {
            starting.start();
            // The exact class name is required here: it is the name the Log4j 2, Logback and JUL arms use, so
            // every arm emits on one logger name and the measurements stay comparable.
            log4j1Logger = starting.getLogger(FileAppenderBenchmark.class.getName());

            julFileHandler = new FileHandler("target/testJulLog.log");
            julLogger = java.util.logging.Logger.getLogger(getClass().getName());
            julLogger.setUseParentHandlers(false);
            julLogger.addHandler(julFileHandler);
            julLogger.setLevel(Level.ALL);
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
        final File log4jMemoryFile = new File("target/testMappedlog4j2.log");
        log4jMemoryFile.delete();
        final File log4j2File = new File("target/testlog4j2.log");
        log4j2File.delete();
        final File julFile = new File("target/testJulLog.log");
        julFile.delete();
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j2RAF() {
        log4j2RandomLogger.debug(MESSAGE);
    }

    /*@BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j2MMF() {
        log4j2MemoryLogger.debug(MESSAGE);
    }*/

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j2AsyncAppender() {
        log4j2AsyncAppender.debug(MESSAGE);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j2AsyncDisruptor() {
        log4j2AsyncDisruptor.debug(MESSAGE);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j2AsyncLogger() {
        log4j2AsyncLogger.debug(MESSAGE);
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
    public void log4j2Builder() {
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
    public void logbackAsyncFile() {
        slf4jAsyncLogger.debug(MESSAGE);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void log4j1File() {
        log4j1Logger.debug(MESSAGE);
    }

    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Benchmark
    public void julFile() {
        // must specify sourceClass or JUL will look it up by walking the stack trace!
        julLogger.logp(Level.INFO, getClass().getName(), "julFile", MESSAGE);
    }
}
