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
 * Benchmarks Log4j 2, Log4j 1, Logback and JUL using the DEBUG level which is enabled for this test. The configuration
 * for each uses a FileAppender
 */
@State(Scope.Thread)
public class FileAppenderParamsBenchmark {
    private FileHandler julFileHandler;
    Logger log4j2Logger;
    Logger log4j2RandomLogger;
    org.slf4j.Logger slf4jLogger;
    Logger log4j1Logger;
    java.util.logging.Logger julLogger;
    int j, k, m;

    /**
     * Isolated logger context backing the formerly-Log4j-1.x arm. Held as a field so that
     * {@link #tearDown()} can shut it down deterministically.
     */
    LoggerContext log4j1Context;

    @Setup
    public void setUp() throws Exception {
        System.setProperty("log4j.configurationFile", "log4j2-perf.xml");
        System.setProperty("logback.configurationFile", "logback-perf.xml");

        deleteLogFiles();

        log4j2Logger = LogManager.getLogger(getClass());
        log4j2RandomLogger = LogManager.getLogger("TestRandom");
        slf4jLogger = LoggerFactory.getLogger(getClass());
        // The arm formerly driven by Log4j 1.x is now a native Log4j 2 arm, configured through an
        // isolated LoggerContext so that it does not contend with the Log4j 2 arm for the global
        // `log4j.configurationFile` selector set above.
        final URL log4j1Config = getClass().getClassLoader().getResource("log4j12-perf.xml");
        log4j1Context = new LoggerContext("FileAppenderParamsBenchmarkLog4j1", null, log4j1Config.toURI());
        log4j1Context.start();
        // Logger name preserved byte-identically: the original called getLogger(getClass()), and the
        // Log4j 1.x bridge derived the name from clazz.getName().
        log4j1Logger = log4j1Context.getLogger(getClass().getName());

        julFileHandler = new FileHandler("target/testJulLog.log");
        julLogger = java.util.logging.Logger.getLogger(getClass().getName());
        julLogger.setUseParentHandlers(false);
        julLogger.addHandler(julFileHandler);
        julLogger.setLevel(Level.ALL);
        j = 0;
    }

    @TearDown
    public void tearDown() {
        System.clearProperty("log4j.configurationFile");
        System.clearProperty("logback.configurationFile");

        Configurator.shutdown(log4j1Context);

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
