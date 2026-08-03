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
    org.apache.logging.log4j.Logger log4j1Logger;
    private LoggerContext log4j1Context;

    @Setup
    public void setUp() throws Exception {
        System.setProperty("log4j.configurationFile", "log4j2-perf2.xml");
        System.setProperty("logback.configurationFile", "logback-perf2.xml");

        deleteLogFiles();

        log4j2Logger = LogManager.getLogger(FileAppenderWithLocationBenchmark.class);
        slf4jLogger = LoggerFactory.getLogger(FileAppenderWithLocationBenchmark.class);
        final URL log4j1ConfigLocation = LoggingDisabledBenchmark.class.getResource("/log4j12-perf2.xml");
        // A fixture that was renamed or left out of the artifact is reported by name here, rather than as a bare
        // NullPointerException from the URI conversion below.
        if (log4j1ConfigLocation == null) {
            throw new IllegalStateException("missing configuration resource: /log4j12-perf2.xml");
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
                new LoggerContext("LoggingDisabledBenchmark", null, log4j1ConfigLocation.toURI());
        boolean armReady = false;
        try {
            starting.start();
            // The cross-class logger name is intentional: this benchmark requests the logger named after
            // FileAppenderWithLocationBenchmark, which is also the name its other two arms acquire above.
            // Changing it would change the logger's identity, and with it the level and appender wiring the
            // events resolve to.
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
