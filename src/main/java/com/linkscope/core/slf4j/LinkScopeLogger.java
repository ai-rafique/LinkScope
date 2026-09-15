package com.linkscope.core.slf4j;

import com.linkscope.core.LogSink;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * SLF4J logger that forwards WARN and ERROR from client libraries (Kafka, Paho) into the
 * shared LinkScope log under the {@code LIB} tag, and drops everything quieter. Nothing
 * else in LinkScope logs through SLF4J.
 */
final class LinkScopeLogger extends AbstractLogger {
    static final String TAG = "LIB";
    private final String shortName;

    LinkScopeLogger(String name) {
        this.name = name;
        int dot = name.lastIndexOf('.');
        this.shortName = dot >= 0 ? name.substring(dot + 1) : name;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern, Object[] arguments,
                                               Throwable throwable) {
        String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
        String text = shortName + ": " + message
                + (throwable == null || throwable.getMessage() == null ? "" : " (" + throwable.getMessage() + ")");
        if (level == Level.ERROR) {
            LogSink.get().error(TAG, text);
        } else {
            LogSink.get().info(TAG, "[WARN] " + text);
        }
    }

    @Override
    public boolean isTraceEnabled() {
        return false;
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return false;
    }

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return false;
    }

    @Override
    public boolean isInfoEnabled() {
        return false;
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return false;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return true;
    }
}
