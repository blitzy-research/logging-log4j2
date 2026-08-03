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
public class FileAppenderParamsBenchmark {
    private FileHandler julFileHandler;
    Logger log4j2Logger;
    Logger log4j2RandomLogger;
    org.slf4j.Logger slf4jLogger;
    org.apache.logging.log4j.Logger log4j1Logger;
    private LoggerContext log4j1Context;
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
        // This arm gets a logger context of its own instead of a global selector property. `log4j.configurationFile`
        // above belongs to the other Log4j 2 arm, and ConfigurationFactory returns on the first key it resolves, so
        // a second global key would silently mis-configure one of the two arms. A non-null configuration URI makes
        // the LoggerContext constructor skip property lookup altogether, which is what keeps the two arms
        // independent inside a single JVM.
        final URL log4j1ConfigLocation = FileAppenderParamsBenchmark.class.getResource("/log4j12-perf.xml");
        // A fixture that was renamed or left out of the artifact is reported by name here, rather than as a bare
        // NullPointerException from the URI conversion below.
        if (log4j1ConfigLocation == null) {
            throw new IllegalStateException("missing configuration resource: /log4j12-perf.xml");
        }
        // The context is started into a local and published to the field only once all fallible setup steps
        // have succeeded, because JMH does not invoke the teardown of a state whose setup threw. The JUL handler
        // constructed below reaches the filesystem and can therefore fail: a context assigned before that
        // failure would stay started for the remainder of the JVM's life, holding its configuration, its file
        // manager and its shutdown callback, with nothing left able to reach it. The counter reset that follows
        // publication cannot fail, so it is left where it reads best.
        final LoggerContext starting =
                new LoggerContext("FileAppenderParamsBenchmark", null, log4j1ConfigLocation.toURI());
        boolean armReady = false;
        try {
            starting.start();
            // getClass() -- not a class literal -- is required here, because it is what the Log4j 2, Logback and
            // JUL arms above resolve their names from as well. JMH subclasses this @State class, so the resolved
            // name is the generated subclass's, and every arm emits on that one name.
            log4j1Logger = starting.getLogger(getClass().getName());

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
        j = 0;
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
}
