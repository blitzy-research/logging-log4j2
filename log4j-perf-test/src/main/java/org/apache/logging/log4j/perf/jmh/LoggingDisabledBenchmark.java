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
    Logger log4j1Logger;

    /**
     * Isolated logger context backing the formerly-Log4j-1.x arm. Held as a field so that
     * {@link #tearDown()} can shut it down deterministically.
     */
    LoggerContext log4j1Context;

    @Setup
    public void setUp() throws Exception {
        System.setProperty("log4j.configurationFile", "log4j2-perf2.xml");
        System.setProperty("logback.configurationFile", "logback-perf2.xml");

        deleteLogFiles();

        log4j2Logger = LogManager.getLogger(FileAppenderWithLocationBenchmark.class);
        slf4jLogger = LoggerFactory.getLogger(FileAppenderWithLocationBenchmark.class);
        // The arm formerly driven by Log4j 1.x is now a native Log4j 2 arm, configured through an
        // isolated LoggerContext so that it does not contend with the Log4j 2 arm for the global
        // `log4j.configurationFile` selector set above.
        final URL log4j1Config = LoggingDisabledBenchmark.class.getClassLoader().getResource("log4j12-perf2.xml");
        log4j1Context = new LoggerContext("LoggingDisabledBenchmarkLog4j1", null, log4j1Config.toURI());
        log4j1Context.start();
        // The logger name intentionally names a DIFFERENT class (FileAppenderWithLocationBenchmark).
        // This is preserved verbatim: renaming it would move these events onto another logger and
        // therefore onto different configured levels and appenders.
        log4j1Logger = log4j1Context.getLogger(FileAppenderWithLocationBenchmark.class.getName());
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
}
