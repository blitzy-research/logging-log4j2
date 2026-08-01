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

import java.net.URL;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.slf4j.LoggerFactory;

/**
 * Benchmarks Log4j 2, Log4j 1, and Logback using the DEBUG level which is disabled for this test. One of the primary
 * performance concerns of logging frameworks is adding minimal overhead when logging is disabled. Some users disable
 * all logging in production, while others disable finer logging levels in production. This benchmark demonstrates the
 * overhead in calling {@code logger.isDebugEnabled()} and {@code logger.debug()}.
 */
@State(Scope.Thread)
public class DebugDisabledBenchmark {
    Logger log4jLogger;
    org.slf4j.Logger slf4jLogger;
    org.apache.logging.log4j.Logger log4jClassicLogger;
    private LoggerContext log4j1Context;
    Integer j;

    @Setup
    public void setUp() throws Exception {
        System.setProperty("log4j.configurationFile", "log4j2-perf2.xml");
        System.setProperty("logback.configurationFile", "logback-perf2.xml");

        log4jLogger = LogManager.getLogger(DebugDisabledBenchmark.class);
        slf4jLogger = LoggerFactory.getLogger(DebugDisabledBenchmark.class);
        // The arm formerly driven by Log4j 1.x is now native Log4j 2, and it is deliberately NOT bootstrapped
        // through a global selector property. `log4j.configurationFile` above already belongs to the Log4j 2
        // arm, and ConfigurationFactory returns on the first key it resolves, so a second global key would
        // silently mis-configure one of the two arms. Handing a non-null configuration URI to the
        // LoggerContext constructor skips property lookup altogether, keeping both arms independent inside a
        // single JVM and leaving the Log4j 2 and Logback arms exactly as they were.
        final URL log4j1ConfigLocation = DebugDisabledBenchmark.class.getResource("/log4j12-perf2.xml");
        log4j1Context = new LoggerContext("DebugDisabledBenchmark", null, log4j1ConfigLocation.toURI());
        log4j1Context.start();
        // The logger name is preserved byte-for-byte: Log4j 1.x derived it from clazz.getName(), which is
        // exactly the argument used here, so all three arms keep sharing a single logger name.
        log4jClassicLogger = log4j1Context.getLogger(DebugDisabledBenchmark.class.getName());
        j = Integer.valueOf(2);
    }

    @TearDown
    public void tearDown() {
        System.clearProperty("log4j.configurationFile");
        Configurator.shutdown(log4j1Context);
        System.clearProperty("logback.configurationFile");
    }

    @Benchmark
    public boolean baseline() {
        return true;
    }

    @Benchmark
    public boolean log4jIsDebugEnabled() {
        return log4jLogger.isDebugEnabled();
    }

    @Benchmark
    public boolean slf4jIsDebugEnabled() {
        return slf4jLogger.isDebugEnabled();
    }

    @Benchmark
    public boolean log4jClassicIsDebugEnabled() {
        return log4jClassicLogger.isDebugEnabled();
    }

    @Benchmark
    public void log4jDebugStringConcatenation() {
        log4jLogger.debug("This is a debug [" + j + "] message");
    }

    @Benchmark
    public void slf4jDebugStringConcatenation() {
        slf4jLogger.debug("This is a debug [" + j + "] message");
    }

    @Benchmark
    public void log4jClassicDebugStringConcatenation() {
        log4jClassicLogger.debug("This is a debug [" + j + "] message");
    }

    @Benchmark
    public void log4jDebugParameterizedString() {
        log4jLogger.debug("This is a debug [{}] message", j);
    }

    @Benchmark
    public void slf4jDebugParameterizedString() {
        slf4jLogger.debug("This is a debug [{}] message", j);
    }
}
