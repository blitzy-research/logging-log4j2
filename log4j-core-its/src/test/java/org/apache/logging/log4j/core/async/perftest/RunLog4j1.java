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
package org.apache.logging.log4j.core.async.perftest;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/**
 * Runs the throughput and latency tests through the arm that was migrated from Log4j 1.x.
 * <p>
 * {@link PerfTestDriver} launches one child JVM per row and forwards the row's configuration file name in
 * {@value org.apache.logging.log4j.core.config.ConfigurationFactory#CONFIGURATION_FILE_PROPERTY}. This runner resolves
 * that name itself and configures a logger context of its own from it, rather than relying on the global context, for
 * two reasons. The first is selection: the name is only resolvable when the row's fixture is on the child's classpath,
 * and the fixtures for this arm are owned by another module, so a child may well be handed a name that resolves to
 * nothing. The second is fidelity: when the superseded arm could not find its configuration it kept its own default
 * root level of DEBUG and owned no appender, so its {@code info} calls stayed enabled and were rendered nowhere - the
 * harness measured the full call path and wrote no output. Log4j 2's default configuration differs on both counts, with
 * a root logger at ERROR and a console appender, which would turn every timed call below into a disabled-level check
 * and would emit output the superseded arm never emitted. This runner therefore builds the superseded hierarchy
 * explicitly whenever the row's fixture cannot be resolved, and fails loudly rather than quietly if the resulting
 * hierarchy would not admit the events it is about to time.
 * </p>
 */
public class RunLog4j1 implements IPerfTestRunner {

    /**
     * Name of this runner's own logger context. Distinct from the global context so that the context this runner
     * configures cannot be displaced by, or displace, anything the harness configures globally.
     */
    private static final String CONTEXT_NAME = "run-log4j1-migrated";

    /**
     * Name carried by the configuration built when the row's fixture cannot be resolved. It appears in status output
     * and in the failure message below, so it names the semantics it reproduces rather than the mechanism.
     */
    private static final String SUPERSEDED_HIERARCHY_NAME = "superseded-default-hierarchy";

    private final LoggerContext context = createContext();

    final Logger LOGGER = context.getLogger(getClass().getName());

    /**
     * Verifies, once and before any measurement is taken, that the hierarchy this runner came up on admits the
     * {@code info} events its timed methods emit. A disabled logger would let both tests complete and report a
     * throughput or latency figure for a level check rather than for logging, which is a silent measurement error; an
     * exception here is the loud alternative.
     *
     * @throws IllegalStateException if the configured hierarchy does not admit {@code info}
     */
    public RunLog4j1() {
        if (!LOGGER.isInfoEnabled()) {
            throw new IllegalStateException("Logger '" + LOGGER.getName() + "' is not enabled for "
                    + Level.INFO
                    + " under configuration '"
                    + context.getConfiguration().getName()
                    + "', so the measurements below would time a disabled call rather than logging. Point "
                    + ConfigurationFactory.CONFIGURATION_FILE_PROPERTY
                    + " at a resolvable configuration whose root logger admits "
                    + Level.INFO
                    + ".");
        }
    }

    /**
     * Builds this runner's context from the row's configuration when that configuration can be resolved, and from the
     * superseded default hierarchy when it cannot. The property is read once, here, and only to honour what the driver
     * forwarded; the context itself is handed either a resolved location or a finished configuration, so it never
     * consults a property of its own and cannot be redirected by one after the fact.
     *
     * @return a started context
     */
    private static LoggerContext createContext() {
        final URI configLocation = resolveConfigurationLocation();
        final LoggerContext context;
        if (configLocation == null) {
            context = new LoggerContext(CONTEXT_NAME);
            context.start(supersededDefaultHierarchy());
        } else {
            context = new LoggerContext(CONTEXT_NAME, null, configLocation);
            context.start();
        }
        return context;
    }

    /**
     * Resolves the configuration named by
     * {@value org.apache.logging.log4j.core.config.ConfigurationFactory#CONFIGURATION_FILE_PROPERTY}, which is what
     * {@link PerfTestDriver} forwards to each child JVM. A name that denotes an existing file is taken as such;
     * otherwise it is looked up on the classpath, the form the driver's rows use.
     *
     * @return the resolved location, or {@code null} when the property is unset or names nothing that exists
     */
    private static URI resolveConfigurationLocation() {
        final String configName = System.getProperty(ConfigurationFactory.CONFIGURATION_FILE_PROPERTY);
        if (configName == null || configName.isEmpty()) {
            return null;
        }
        final File configFile = new File(configName);
        if (configFile.isFile()) {
            return configFile.toURI();
        }
        final String resourceName = configName.startsWith("/") ? configName.substring(1) : configName;
        final URL resource = RunLog4j1.class.getClassLoader().getResource(resourceName);
        if (resource == null) {
            return null;
        }
        try {
            return resource.toURI();
        } catch (final URISyntaxException error) {
            throw new IllegalStateException(
                    "The configuration named by " + ConfigurationFactory.CONFIGURATION_FILE_PROPERTY
                            + " resolved to a location that is not a valid URI: " + resource,
                    error);
        }
    }

    /**
     * Builds the hierarchy the superseded arm presented when its configuration file could not be found: a root logger
     * at DEBUG owning no appender. Log4j 1.x reached that state by leaving its own repository defaults in place; Log4j
     * 2's defaults differ on both the level and the appender, so the state has to be stated rather than inherited.
     *
     * @return a configuration with a root logger at DEBUG and no appenders
     */
    private static Configuration supersededDefaultHierarchy() {
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setConfigurationName(SUPERSEDED_HIERARCHY_NAME);
        builder.add(builder.newRootLogger(Level.DEBUG));
        return builder.build(false);
    }

    @Override
    public void runThroughputTest(final int lines, final Histogram histogram) {
        final long s1 = System.nanoTime();
        final Logger logger = LOGGER;
        for (int j = 0; j < lines; j++) {
            logger.info(THROUGHPUT_MSG);
        }
        final long s2 = System.nanoTime();
        final long opsPerSec = (1000L * 1000L * 1000L * lines) / (s2 - s1);
        histogram.addObservation(opsPerSec);
    }

    @Override
    public void runLatencyTest(
            final int samples, final Histogram histogram, final long nanoTimeCost, final int threadCount) {
        final Logger logger = LOGGER;
        for (int i = 0; i < samples; i++) {
            final long s1 = System.nanoTime();
            logger.info(LATENCY_MSG);
            final long s2 = System.nanoTime();
            final long value = s2 - s1 - nanoTimeCost;
            if (value > 0) {
                histogram.addObservation(value);
            }
            // wait 1 microsec
            final long PAUSE_NANOS = 10000 * threadCount;
            final long pauseStart = System.nanoTime();
            while (PAUSE_NANOS > (System.nanoTime() - pauseStart)) {
                // busy spin
            }
        }
    }

    @Override
    public void shutdown() {
        // Two shutdowns rather than one. The context created above is the one this runner logged through, so it has to
        // be stopped explicitly - nothing else holds a reference to it. LogManager.shutdown() stays because it is the
        // call this method has always made and the harness may have started the global context as well.
        Configurator.shutdown(context);
        LogManager.shutdown();
    }

    @Override
    public void log(final String finalMessage) {
        LOGGER.info(finalMessage);
    }
}
