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
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.perf.util.BenchmarkMessageParams;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Tests Async Appender performance when including caller location information, for the benchmark arm formerly driven by
 * Log4j-1.2. The arm now runs on the native Log4j 2 API against the migrated
 * {@code perf-log4j12-async-location-noOpAppender.xml} configuration.
 */
@State(Scope.Benchmark)
public class AsyncAppenderLog4j1LocationBenchmark {
    Logger logger;

    @Setup(Level.Trial)
    public void up() {
        System.setProperty("log4j2.configurationFile", "perf-log4j12-async-location-noOpAppender.xml");
        logger = LogManager.getLogger(getClass());
    }

    @TearDown(Level.Trial)
    public void down() {
        LogManager.shutdown();
        // The selector set by up() is withdrawn as soon as this trial's context is gone, so that the next
        // trial in the same JVM is configured by its own setup rather than by this one's leftovers. Log4j 2
        // normalises `log4j2.configurationFile` and the legacy `log4j.configurationFile` spelling onto a
        // single property and prefers the canonical spelling, and the Log4j 2 peers of this benchmark set the
        // legacy one; a value surviving here would therefore out-rank theirs and silently boot them on this
        // benchmark's location-capturing no-op topology whenever several trials share a JVM (`-f 0`). Both
        // spellings are cleared so the property is genuinely unset, not merely shadowed.
        System.clearProperty("log4j2.configurationFile");
        System.clearProperty("log4j.configurationFile");
        new File("perftest.log").delete();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void throughputSimple() {
        logger.info(BenchmarkMessageParams.TEST);
    }
}
