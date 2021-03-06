/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.blitz4j;

import com.netflix.logging.log4jAdapter.NFPatternLayout;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import com.netflix.servo.monitor.Timer;
import org.apache.log4j.Appender;
import org.apache.log4j.Category;
import org.apache.log4j.Level;
import org.apache.log4j.MDC;
import org.apache.log4j.spi.AppenderAttachable;
import org.apache.log4j.spi.LocationInfo;
import org.apache.log4j.spi.LoggingEvent;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The utility class that caches the context of logging such as location
 * information.
 * 
 * <p>
 * It is expensive to find out the location information (ie) calling class, line
 * number etc of the logger and hence caching would be useful whenever possible.
 * This class also generates location information slightly more efficiently than
 * log4j.
 * <p>
 * 
 * @author Karthik Ranganathan
 * 
 */
public class LoggingContext {

    public static final String CONTEXT_LEVEL = "contextlevel";
    private static final BlitzConfig CONFIGURATION = LoggingConfiguration.getInstance().getConfiguration();
    private static final String LOCATION_INFO = "locationInfo";
    private ThreadLocal<StackTraceElement> stackLocal = new ThreadLocal<StackTraceElement>();
    private ThreadLocal<LoggingEvent> loggingEvent = new ThreadLocal<LoggingEvent>();
    private ThreadLocal<Level> contextLevel = new ThreadLocal<Level>();
    private final AtomicReference<HashSet<Category>> loggerNeedsLocationRef = new AtomicReference<>(new HashSet<Category>());

    private static final LoggingContext instance = new LoggingContext();
    private Timer stackTraceTimer = Monitors.newTimer("getStacktraceElement",
            TimeUnit.NANOSECONDS);

    private LoggingContext() {
        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            if (CONFIGURATION.shouldPrintLoggingErrors()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Gets the starting calling stack trace element of a given stack which
     * matches the given class name. Given the wrapper class name, the match
     * continues until the last stack trace element of the wrapper class is
     * matched.
     * 
     * @param stackClass
     *            - The class to be matched for. Get the last matching class
     *            down the stack
     * @return - StackTraceElement which denotes the calling point of given
     *         class or wrapper class
     */
    public StackTraceElement getStackTraceElement(Class stackClass) {

        Stopwatch s = stackTraceTimer.start();
        Throwable t = new Throwable();
        StackTraceElement[] stArray = t.getStackTrace();
        int stackSize = stArray.length;
        StackTraceElement st = null;
        for (int i = 0; i < stackSize; i++) {
            boolean found = false;
            while (stArray[i].getClassName().equals(stackClass.getName())) {
                ++i;
                found = true;
            }
            if (found) {
                st = stArray[i];
            }
        }

        s.stop();

        return st;
    }

    /**
     * Get the location information of the calling class
     * 
     * @param wrapperClassName
     *            - The wrapper that indicates the caller
     * @return the location information
     */
    public LocationInfo getLocationInfo(Class wrapperClassName) {
        LocationInfo locationInfo = null;

        try {
            if (stackLocal.get() == null) {
                stackLocal.set(this.getStackTraceElement(wrapperClassName));
            }

            locationInfo = new LocationInfo(stackLocal.get().getFileName(),
                    stackLocal.get().getClassName(), stackLocal.get()
                            .getMethodName(), stackLocal.get().getLineNumber()
                            + "");
        } catch (Throwable e) {
            if (CONFIGURATION
                    .shouldPrintLoggingErrors()) {
                e.printStackTrace();
            }
        }
        return locationInfo;
    }

    /**
     * Clears any logging information that was cached for the purpose of
     * logging.
     */
    private void clearLocationInfo() {
        MDC.remove(LOCATION_INFO);
        stackLocal.set(null);
    }

    public static LoggingContext getInstance() {
        return instance;
    }

    /**
     * Generate the location information of the given logging event and cache
     * it.
     * 
     * @param event
     *            The logging event for which the location information needs to
     *            be determined.
     * @return The location info object contains information about the logger.
     */
    public LocationInfo generateLocationInfo(LoggingEvent event) {
        // If the event is not the same, clear the cache
        if (event != loggingEvent.get()) {
            loggingEvent.set(event);
            clearLocationInfo();
        }
        LocationInfo locationInfo = null;
        try {
            // We should only generate location info if the caller is using NFPatternLayout otherwise this is expensive and unused.
            if (isUsingNFPatternLayout(event.getLogger())) {
                locationInfo = (LocationInfo) LoggingContext
                        .getInstance()
                        .getLocationInfo(Class.forName(event.getFQNOfLoggerClass()));
                if (locationInfo != null) {
                    MDC.put(LOCATION_INFO, locationInfo);
                }
            }
        } catch (Throwable e) {
            if (CONFIGURATION !=null && CONFIGURATION
                    .shouldPrintLoggingErrors()) {
                e.printStackTrace();
            }
        }
        return locationInfo;
    }

    private boolean isUsingNFPatternLayout(Category logger) {
        if (logger == null) {
            return false;
        }

        HashSet<Category> loggerNeedsLocation = loggerNeedsLocationRef.get();
        // If we've already seen this logger and it needs location info, assume it still does.
        // Due to reconfiguration, it's possible it doesn't anymore, but this is rare so we optimize
        // for a fast return on loggers previously known to need location info.
        if (loggerNeedsLocation.contains(logger)) {
            return true;
        }

        // If any of the appenders in the tree below need location information remember this logger and return.
        if (isUsingNFPatternLayout(logger.getAllAppenders())) {
            do {
                HashSet<Category> copy = new HashSet<>(loggerNeedsLocation);
                copy.add(logger);
                if (loggerNeedsLocationRef.compareAndSet(loggerNeedsLocation, copy)) {
                    return true;
                }
                loggerNeedsLocation = loggerNeedsLocationRef.get();
            } while(true);
        }

        // If this is not an additive logger, our search is done, otherwise we must look at parents.
        if (!logger.getAdditivity()) {
            return false;
        }

        Category parentLogger = logger.getParent();
        if (parentLogger == null) {
            return false;
        }

        // Now we need to traverse all parents and remember the top level logger whose parents need
        // location info if additivity was set to true.
        if(isUsingNFPatternLayout(parentLogger)) {
            do {
                HashSet<Category> copy = new HashSet<>(loggerNeedsLocation);
                copy.add(logger);
                if (loggerNeedsLocationRef.compareAndSet(loggerNeedsLocation, copy)) {
                    return true;
                }
                loggerNeedsLocation = loggerNeedsLocationRef.get();
            } while(true);
        }

        // An exhaustive search returned nothing.  We want location information to show up when
        // a reconfiguration occurs so we don't cache the result.  This is still a much cheaper
        // cost than generating a stack trace and so we are happy to pay it every time we log
        // if we don't actually need the location information.
        return false;

    }

    private boolean isUsingNFPatternLayout(Enumeration enumeration) {
        if (enumeration == null) {
            return false;
        }

        while(enumeration.hasMoreElements()) {
            Object maybeAppender = enumeration.nextElement();
            if (maybeAppender instanceof Appender) {
                Appender a = (Appender) maybeAppender;
                if (a.getLayout() instanceof NFPatternLayout) {
                    return true;
                }
            }

            if (maybeAppender instanceof AppenderAttachable) {
                AppenderAttachable aa = (AppenderAttachable) maybeAppender;
                if (isUsingNFPatternLayout(aa.getAllAppenders())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get the location information of the logging event. If the information has
     * been cached it is retrieved from the MDC (for asynchronous events MDCs
     * are retained), else it is generated.
     * 
     * @param event
     *            - The logging event
     * @return- The location information of the logging event.
     */
    public LocationInfo getLocationInfo(LoggingEvent event) {
        if (event != loggingEvent.get()) {
            loggingEvent.set(event);
            clearLocationInfo();
        }
        // For async appenders, the locationInfo is set in the MDC and not with
        // the thread since the thread that processes the logging is different
        // from the one that
        // generates location information.
        LocationInfo locationInfo = (LocationInfo) event.getMDC(LOCATION_INFO);
        if (locationInfo == null) {
            locationInfo = this.generateLocationInfo(event);
        }

        return locationInfo;
    }
    
    /**
     * Set the context {@link Level} for the request-based logging
     * @param level - The level of logging to be enabled for this request
     */
    public void setContextLevel(Level level) {
        MDC.put(CONTEXT_LEVEL, level);
    }
    
    /**
     * Clears the context {@link Level} set for the request-based logging
     */
    public void clearContextLevel() {
        MDC.remove(CONTEXT_LEVEL);
    }
    
    
    /**
     * Get the context {@link Level} for the request-based logging
     * @param level - The level of logging to be enabled for this request
     */
    public Level getContextLevel() {
        return (Level)MDC.get(CONTEXT_LEVEL);
    }
    
    

}
