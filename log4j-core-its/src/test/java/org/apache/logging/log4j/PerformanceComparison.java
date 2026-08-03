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
package org.apache.logging.log4j;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.test.util.Profiler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Use this class to analyze performance between Log4j and other logging frameworks.
 */
@Tag("PerformanceTests")
class PerformanceComparison {

    /**
     * Serves the arm migrated from Log4j 1.x, in a logger context of its own rather than in the global one. This class
     * drives three logging generations inside a single JVM and two of them are now the same implementation, so a
     * single global configuration property could only ever select one of the two. Assigned by {@link #setupClass()},
     * which JUnit runs before it instantiates this class, and stopped again by {@link #cleanupClass()} so that no
     * started context outlives the test.
     */
    private static LoggerContext migratedContext;

    private final Logger logger = LogManager.getLogger(PerformanceComparison.class.getName());
    private final org.slf4j.Logger logbacklogger = org.slf4j.LoggerFactory.getLogger(PerformanceComparison.class);
    // Deliberately the same logger name as the two arms above, so that the three measurements stay comparable.
    private final org.apache.logging.log4j.Logger log4jlogger =
            migratedContext.getLogger(PerformanceComparison.class.getName());

    // How many times should we try to log:
    private static final int COUNT = 500000;
    private static final int PROFILE_COUNT = 500000;
    private static final int WARMUP = 50000;

    private static final String CONFIG = "log4j2-perf.xml";
    private static final String LOGBACK_CONFIG = "logback-perf.xml";
    private static final String LOG4J_CONFIG = "log4j12-perf.xml";

    private static final String LOGBACK_CONF = "logback.configurationFile";

    private static final String MIGRATED_CONTEXT_NAME = "PerformanceComparison-log4j1-arm";

    @BeforeAll
    static void setupClass() throws Exception {
        System.setProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY, CONFIG);
        System.setProperty(LOGBACK_CONF, LOGBACK_CONFIG);
        // The migrated arm is configured by pointing a context of its own straight at its configuration rather than by
        // setting a global property, so that it cannot displace the configuration the native arm selects above. That
        // fixture belongs to another module whose test resources are not published, so this lookup returns null on this
        // module's test classpath - exactly as the Log4j 1.x lookup it replaces did - and a null location reproduces
        // that pre-existing "no explicit configuration" outcome rather than newly failing the test.
        final URL migratedConfigLocation = PerformanceComparison.class.getResource("/" + LOG4J_CONFIG);
        migratedContext = migratedConfigLocation == null
                ? new LoggerContext(MIGRATED_CONTEXT_NAME)
                : new LoggerContext(MIGRATED_CONTEXT_NAME, null, migratedConfigLocation.toURI());
        migratedContext.start();
    }

    @AfterAll
    static void cleanupClass() {
        System.clearProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY);
        System.clearProperty(LOGBACK_CONF);
        // Stopping the context here is what keeps it from outliving the test: no fork is reused, but order is
        // randomised, so nothing may be left started behind. The call is null-safe.
        Configurator.shutdown(migratedContext);
        new File("target/testlog4j.log").deleteOnExit();
        new File("target/testlog4j2.log").deleteOnExit();
        new File("target/testlogback.log").deleteOnExit();
    }

    @Test
    void testPerformance() {

        log4j(WARMUP);
        logback(WARMUP);
        log4j2(WARMUP);

        if (Profiler.isActive()) {
            System.out.println("Profiling Log4j 2.0");
            Profiler.start();
            final long result = log4j2(PROFILE_COUNT);
            Profiler.stop();
            System.out.println("###############################################");
            System.out.println("Log4j 2.0: " + result);
            System.out.println("###############################################");
        } else {
            doRun();
            doRun();
            doRun();
            doRun();
        }
    }

    private void doRun() {
        System.out.print("Log4j    : ");
        System.out.println(log4j(COUNT));

        System.out.print("Logback  : ");
        System.out.println(logback(COUNT));

        System.out.print("Log4j 2.0: ");
        System.out.println(log4j2(COUNT));

        System.out.println("###############################################");
    }

    // @Test
    private void testRawPerformance() throws Exception {
        final OutputStream os = new FileOutputStream("target/testos.log", true);
        final long result1 = writeToStream(COUNT, os);
        os.close();
        final OutputStream bos = new BufferedOutputStream(new FileOutputStream("target/testbuffer.log", true));
        final long result2 = writeToStream(COUNT, bos);
        bos.close();
        final Writer w = new FileWriter("target/testwriter.log", true);
        final long result3 = writeToWriter(COUNT, w);
        w.close();
        final FileOutputStream cos = new FileOutputStream("target/testchannel.log", true);
        final FileChannel channel = cos.getChannel();
        final long result4 = writeToChannel(COUNT, channel);
        cos.close();
        System.out.println("###############################################");
        System.out.println("FileOutputStream: " + result1);
        System.out.println("BufferedOutputStream: " + result2);
        System.out.println("FileWriter: " + result3);
        System.out.println("FileChannel: " + result4);
        System.out.println("###############################################");
    }

    private long log4j(final int loop) {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            log4jlogger.debug("SEE IF THIS IS LOGGED " + j + '.');
        }
        return (System.nanoTime() - start) / loop;
    }

    private long logback(final int loop) {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            logbacklogger.debug("SEE IF THIS IS LOGGED " + j + '.');
        }
        return (System.nanoTime() - start) / loop;
    }

    private long log4j2(final int loop) {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            logger.debug("SEE IF THIS IS LOGGED " + j + '.');
        }
        return (System.nanoTime() - start) / loop;
    }

    private long writeToWriter(final int loop, final Writer w) throws Exception {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            w.write("SEE IF THIS IS LOGGED " + j + '.');
        }
        return (System.nanoTime() - start) / loop;
    }

    private long writeToStream(final int loop, final OutputStream os) throws Exception {
        final Integer j = Integer.valueOf(2);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            os.write(getBytes("SEE IF THIS IS LOGGED " + j + '.'));
        }
        return (System.nanoTime() - start) / loop;
    }

    private long writeToChannel(final int loop, final FileChannel channel) throws Exception {
        final Integer j = Integer.valueOf(2);
        final ByteBuffer buf = ByteBuffer.allocateDirect(8 * 1024);
        final long start = System.nanoTime();
        for (int i = 0; i < loop; i++) {
            channel.write(getByteBuffer(buf, "SEE IF THIS IS LOGGED " + j + '.'));
        }
        return (System.nanoTime() - start) / loop;
    }

    private ByteBuffer getByteBuffer(final ByteBuffer buf, final String s) {
        buf.clear();
        buf.put(s.getBytes());
        buf.flip();
        return buf;
    }

    private byte[] getBytes(final String s) {
        return s.getBytes();
    }
}
