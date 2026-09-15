package com.linkscope.core.slf4j;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers {@link LinkScopeLogger} as the SLF4J backend (via META-INF/services), so
 * library warnings land in the app log and SLF4J stops complaining about a missing provider.
 */
public final class LinkScopeSlf4jProvider implements SLF4JServiceProvider {
    /** Must match the major.minor of the slf4j-api on the classpath. */
    public static final String REQUESTED_API_VERSION = "2.0.99";

    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private final ILoggerFactory loggerFactory = name -> loggers.computeIfAbsent(name, LinkScopeLogger::new);
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        // nothing to prepare
    }
}
