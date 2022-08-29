package eu.siacs.conversations.utils;


import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;

/**
 * Copyright (C) 2012 Bartek Przybylski
 * Copyright (C) 2015 ownCloud Inc.
 * Copyright (C) 2016 Daniel Gultsch
 */

public abstract class ConversationsFileObserver {
    private static final Executor EVENT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int MASK = FileObserver.DELETE | FileObserver.MOVED_FROM | FileObserver.CREATE;


    private final String path;
    private final List<SingleFileObserver> mObservers = new ArrayList<>();
    private final AtomicBoolean shouldStop = new AtomicBoolean(true);

    protected ConversationsFileObserver(String path) {
        this.path = path;
    }

    public void startWatching() {
        shouldStop.set(false);
        startWatchingInternal();
    }

    private synchronized void startWatchingInternal() {
        final Stack<String> stack = new Stack<>();
        stack.push(path);

        while (!stack.empty()) {
            if (shouldStop.get()) {
                Log.d(Config.LOGTAG, "file observer received command to stop");
                return;
            }
            final String parent = stack.pop();
            final File path = new File(parent);
            mObservers.add(new SingleFileObserver(path, MASK));
            final File[] files = path.listFiles();
            for (final File file : (files == null ? new File[0] : files)) {
                if (shouldStop.get()) {
                    Log.d(Config.LOGTAG, "file observer received command to stop");
                    return;
                }
                if (file.isDirectory() && file.getName().charAt(0) != '.') {
                    final String currentPath = file.getAbsolutePath();
                    if (depth(file) <= 8 && !stack.contains(currentPath) && !observing(file)) {
                        stack.push(currentPath);
                    }
                }
            }
        }
        for (FileObserver observer : mObservers) {
            observer.startWatching();
        }
    }

    private static int depth(File file) {
        int depth = 0;
        while ((file = file.getParentFile()) != null) {
            depth++;
        }
        return depth;
    }

    private boolean observing(final File path) {
        for (final SingleFileObserver observer : mObservers) {
            if (path.equals(observer.path)) {
                return true;
            }
        }
        return false;
    }

    public void stopWatching() {
        shouldStop.set(true);
        stopWatchingInternal();
    }

    private synchronized void stopWatchingInternal() {
        for (FileObserver observer : mObservers) {
            observer.stopWatching();
        }
        mObservers.clear();
    }

    abstract public void onEvent(final int event, File path);

    public void restartWatching() {
        stopWatching();
        startWatching();
    }

    private class SingleFileObserver extends FileObserver {
        private final File path;

        SingleFileObserver(final File path, final int mask) {
            super(path.getAbsolutePath(), mask);
            this.path = path;
        }

        @Override
        public void onEvent(final int event, final String filename) {
            if (filename == null) {
                Log.d(Config.LOGTAG, "ignored file event with NULL filename (event=" + event + ")");
                return;
            }
            EVENT_EXECUTOR.execute(() -> {
                final File file = new File(this.path, filename);
                if ((event & FileObserver.ALL_EVENTS) == FileObserver.CREATE) {
                    if (file.isDirectory()) {
                        Log.d(Config.LOGTAG, "file observer observed new directory creation " + file);
                        if (!observing(file)) {
                            final SingleFileObserver observer = new SingleFileObserver(file, MASK);
                            observer.startWatching();
                        }
                    }
                    return;
                }
                ConversationsFileObserver.this.onEvent(event, file);
            });
        }
    }
}
