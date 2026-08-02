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
 * Benchmarks Log4j 2, the arm formerly driven by Log4j 1 (now also native Log4j 2, against the migrated Log4j 1
 * configuration), Logback and JUL using the DEBUG level which is enabled for this test. The configuration
 * for each uses a FileAppender
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
        // The arm formerly driven by Log4j 1.x is now native Log4j 2, and it is deliberately NOT bootstrapped
        // through a global selector property. `log4j.configurationFile` above already belongs to the Log4j 2
        // arm -- which owns six loggers here, including the asynchronous ones -- and ConfigurationFactory
        // returns on the first key it resolves, so a second global key would silently mis-configure one of
        // the two arms. Handing a non-null configuration URI to the LoggerContext constructor skips property
        // lookup altogether, keeping both arms independent inside a single JVM and leaving the Log4j 2,
        // Logback and JUL arms exactly as they were.
        final URL log4j1ConfigLocation = FileAppenderBenchmark.class.getResource("/log4j12-perf.xml");
        log4j1Context = new LoggerContext("FileAppenderBenchmark", null, log4j1ConfigLocation.toURI());
        log4j1Context.start();
        // The logger name is preserved byte-for-byte: Log4j 1.x derived it from clazz.getName(), which is
        // exactly the argument used here, so the events stay on the logger they have always used.
        log4j1Logger = log4j1Context.getLogger(FileAppenderBenchmark.class.getName());

        julFileHandler = new FileHandler("target/testJulLog.log");
        julLogger = java.util.logging.Logger.getLogger(getClass().getName());
        julLogger.setUseParentHandlers(false);
        julLogger.addHandler(julFileHandler);
        julLogger.setLevel(Level.ALL);
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
