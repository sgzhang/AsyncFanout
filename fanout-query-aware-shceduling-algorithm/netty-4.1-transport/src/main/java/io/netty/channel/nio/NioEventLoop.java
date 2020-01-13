/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEYSET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };
    private final Callable<Integer> pendingTasksCallable = new Callable<Integer>() {
        @Override
        public Integer call() throws Exception {
            return NioEventLoop.super.pendingTasks();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String buglevel = SystemPropertyUtil.get(key);
        if (buglevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEYSET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    private Selector selector;
    private Selector unwrappedSelector;
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    private final SelectStrategy selectStrategy;

    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    // added by sgzhang
    private long selectTimestamp = -1;
    private HashMap<Integer, LinkedList<SelectionKey>> hashMap = new HashMap<Integer, LinkedList<SelectionKey>>();
    private HashMap<Integer, int[]> fanoutRecord = new HashMap<Integer, int[]>();
    private LinkedList<SelectionKey> longJobs = new LinkedList<>();
    private final int SELECT_THRESHOLD = 0;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEYSET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) ||
                // ensure the current selector implementation is what we can instrument.
                !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField);
                    if (cause != null) {
                        return cause;
                    }

                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    @Override
    public int pendingTasks() {
        // As we use a MpscQueue we need to ensure pendingTasks() is only executed from within the EventLoop as
        // otherwise we may see unexpected behavior (as size() is only allowed to be called by a single consumer).
        // See https://github.com/netty/netty/issues/5297
        if (inEventLoop()) {
            return super.pendingTasks();
        } else {
            return submit(pendingTasksCallable).syncUninterruptibly().getNow();
        }
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        try {
            ch.register(selector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
    }

    @Override
    protected void run() {
        for (;;) {
            try {
                switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE:
                        continue;
                    case SelectStrategy.SELECT:
                        // added by sgzhang
                        selectTimestamp = System.currentTimeMillis();

                        select(wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        //
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).

                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        runAllTasks();
                    }
                } else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private void selectWrapper() {
        try {
            switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
//            case SelectStrategy.CONTINUE:
//                continue;
            case SelectStrategy.SELECT:
                // added by sgzhang
                selectTimestamp = System.currentTimeMillis();

                select(wakenUp.getAndSet(false));

                // 'wakenUp.compareAndSet(false, true)' is always evaluated
                // before calling 'selector.wakeup()' to reduce the wake-up
                // overhead. (Selector.wakeup() is an expensive operation.)
                //
                // However, there is a race condition in this approach.
                // The race condition is triggered when 'wakenUp' is set to
                // true too early.
                //
                // 'wakenUp' is set to true too early if:
                // 1) Selector is waken up between 'wakenUp.set(false)' and
                //    'selector.select(...)'. (BAD)
                // 2) Selector is waken up between 'selector.select(...)' and
                //    'if (wakenUp.get()) { ... }'. (OK)
                //
                // In the first case, 'wakenUp' is set to true and the
                // following 'selector.select(...)' will wake up immediately.
                // Until 'wakenUp' is set to false again in the next round,
                // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                // any attempt to wake up the Selector will fail, too, causing
                // the following 'selector.select(...)' call to block
                // unnecessarily.
                //
                // To fix this problem, we wake up the selector again if wakenUp
                // is true immediately after selector.select(...).
                // It is inefficient in that it wakes up the selector for both
                // the first case (BAD - wake-up required) and the second case
                // (OK - no wake-up required).

                if (wakenUp.get()) {
                    selector.wakeup();
                }
                // fall through
            default:
            }
        } catch (Throwable t) {
            handleLoopException(t);
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        // added by sgzhang, log # of selected keys
        int selectedKeysCnt = 0,
                readKeysCnt = 0,
                writeKeysCnt = 0,
                acceptKeysCnt = 0;
        int record = -1;
        int max = -1;
        int firstLarge = -1, firstLargeRecord = -1;
        int secondLarge = -1, secondLargeRecord = -1;
        StringBuilder str = new StringBuilder();
//        HashMap<Integer, LinkedList<SelectionKey>> hashMap = new HashMap<Integer, LinkedList<SelectionKey>>();
//        selectedKeys.sort();  // sort the keys

        LinkedList<Integer> tmp = new LinkedList<>();
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            final Object a = k.attachment();

            // added by sgzhang
            selectedKeysCnt++;
            if ((k.readyOps() & SelectionKey.OP_CONNECT) != 0) {
                acceptKeysCnt++;
            }
            if ((k.readyOps() & SelectionKey.OP_READ) != 0) {
                readKeysCnt++;
            }
            if ((k.readyOps() & SelectionKey.OP_WRITE) != 0) {
                writeKeysCnt++;
            }

            // added by sgzhang
//            System.out.println("*****0001 selection keys -> " + k.interestOps());

            if (a instanceof AbstractNioChannel) {
                if (selectTimestamp > 0) {
                    ((AbstractNioChannel) a).setSelectTimestamp(selectTimestamp);
                }
//                str.append(((AbstractNioChannel) a).getPriority()).append("-")
//                                                    .append(System.currentTimeMillis()).append(",");
//                str.append("(").append(((AbstractNioChannel) a).getPriority().hashedSource)
//                   .append(",").append(((AbstractNioChannel) a).getPriority().fanout)
//                   .append("),");

                // short job first based on hashedSource
//                if (((AbstractNioChannel) a).getPriority().hashedSource % 2 != 0 &&
//                    ((AbstractNioChannel) a).getPriority().hashedSource > 0) {
//                    if (!longJobs.contains(k)) {
//                        longJobs.add(k);
//                    }
//                } else {
//                    str.append("(").append(((AbstractNioChannel) a).getPriority().hashedSource)
//                       .append(",").append(((AbstractNioChannel) a).getPriority().fanout)
//                       .append("),");
//                    processSelectedKey(k, (AbstractNioChannel) a);
//                }

                // test send request first
//                if (!hashMap.containsKey(((AbstractNioChannel) a).getPriority().hashedSource) &&
//                    ((AbstractNioChannel) a).getPriority().hashedSource > 1) {
//                    LinkedList<SelectionKey> list = new LinkedList<SelectionKey>();
//                    list.add(k);
//                    hashMap.put(((AbstractNioChannel) a).getPriority().hashedSource, list);
//                } else if (hashMap.containsKey(((AbstractNioChannel) a).getPriority().hashedSource) &&
//                      ((AbstractNioChannel) a).getPriority().hashedSource > 1) {
//                    hashMap.get(((AbstractNioChannel) a).getPriority().hashedSource).add(k);
//                } else {
//                    str.append("(").append(((AbstractNioChannel) a).getPriority().hashedSource)
//                       .append(",").append(((AbstractNioChannel) a).getPriority().fanout)
//                       .append("),");
//                    processSelectedKey(k, (AbstractNioChannel) a);
//                }

                if (!hashMap.containsKey(((AbstractNioChannel) a).getPriority().hashedSource) &&
                    ((AbstractNioChannel) a).getPriority().hashedSource > 1 &&
                    ((AbstractNioChannel) a).getPriority().fanout > 1) {
                    LinkedList<SelectionKey> list = new LinkedList<SelectionKey>();
                    list.add(k);
                    hashMap.put(((AbstractNioChannel) a).getPriority().hashedSource, list);
                    int[] intTmp = {((AbstractNioChannel) a).getPriority().fanout, 0};
                    fanoutRecord.put(((AbstractNioChannel) a).getPriority().hashedSource,
                                     intTmp);
                } else if (((AbstractNioChannel) a).getPriority().fanout == 1) {
                    str.append("(").append(((AbstractNioChannel) a).getPriority().hashedSource)
                       .append(",").append(((AbstractNioChannel) a).getPriority().fanout)
                       .append("),");
                    processSelectedKey(k, (AbstractNioChannel) a);
                } else if (hashMap.containsKey(((AbstractNioChannel) a).getPriority().hashedSource)) {
                    // check which conn has the largest waiting time in hashmap
                    if (fanoutRecord.get(((AbstractNioChannel) a).getPriority().hashedSource)[1] > secondLarge &&
                        fanoutRecord.get(((AbstractNioChannel) a).getPriority().hashedSource)[1] > firstLarge) {
                        firstLarge = fanoutRecord.get(((AbstractNioChannel) a).getPriority().hashedSource)[1];
                        firstLargeRecord = ((AbstractNioChannel) a).getPriority().hashedSource;
                    } else if (fanoutRecord.get(((AbstractNioChannel) a).getPriority().hashedSource)[1] >
                               secondLarge &&
                               fanoutRecord.get(((AbstractNioChannel) a).getPriority().hashedSource)[1] <
                               firstLarge) {
                        secondLarge = fanoutRecord.get(((AbstractNioChannel) a).getPriority().hashedSource)[1];
                        secondLargeRecord = ((AbstractNioChannel) a).getPriority().hashedSource;
                    }

                    if (!hashMap.get(((AbstractNioChannel) a).getPriority().hashedSource).contains(k)) {
                        hashMap.get(((AbstractNioChannel) a).getPriority().hashedSource).add(k);
                        if (hashMap.get(((AbstractNioChannel) a).getPriority().hashedSource).size() ==
                            fanoutRecord.get(((AbstractNioChannel) a).getPriority().hashedSource)[0]) {
//                            ((AbstractNioChannel) a).getPriority().fanout) {

                            if (((AbstractNioChannel) a).getPriority().hashedSource % 2 == 0) {
                                tmp.add(((AbstractNioChannel) a).getPriority().hashedSource);
                            } else {
                                for (SelectionKey sk : hashMap
                                        .get(((AbstractNioChannel) a).getPriority().hashedSource)) {
                                    str.append("(").append(((AbstractNioChannel) a).getPriority().hashedSource)
                                       .append(",").append(((AbstractNioChannel) a).getPriority().fanout)
                                       .append("),");
                                    processSelectedKey(sk, (AbstractNioChannel) sk.attachment());
                                    hashMap.remove(((AbstractNioChannel) a).getPriority().hashedSource);
                                    fanoutRecord.remove(((AbstractNioChannel) a).getPriority().hashedSource);
                                }
                            }
                        }
                    }
//                } else if (!hashMap.containsKey(((AbstractNioChannel) a).getPriority().hashedSource) &&
//                           ((AbstractNioChannel) a).getPriority().fanout < 0) {
//                    LinkedList<SelectionKey> list = new LinkedList<SelectionKey>();
//                    list.add(k);
//                    hashMap.put(((AbstractNioChannel) a).getPriority().hashedSource, list);
                } else {
                    str.append("(").append(((AbstractNioChannel) a).getPriority().hashedSource)
                       .append(",").append(((AbstractNioChannel) a).getPriority().fanout)
                       .append("),");
                    processSelectedKey(k, (AbstractNioChannel) a);
                }

//                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }

        }

        /**************************************/
        // short job first
//        if (!longJobs.isEmpty()) {
//            int DRAIN_LONGJOBS = (int) (0.2 * longJobs.size());   // range (0, 100]
//            int longjob_count = 0;
//            for (; /* longjob_count++ < 50 */ ;) {
//                if (!longJobs.isEmpty()) {
//                    SelectionKey kk = longJobs.pollFirst();
//                    str.append("(").append(((AbstractNioChannel) kk.attachment()).getPriority().hashedSource)
//                       .append(",").append(((AbstractNioChannel) kk.attachment()).getPriority().fanout)
//                       .append("),");
//                    processSelectedKey(kk, (AbstractNioChannel) kk.attachment());
//                } else {
//                    break;
//                }
//            }
//        }
        /*************************************/

        if (!tmp.isEmpty()) {
            for (int source : tmp) {
                for (SelectionKey ssk : hashMap.get(source)) {
                    str.append("(").append(source)
                       .append(",").append(((AbstractNioChannel) ssk.attachment())
                                                   .getPriority().fanout)
                       .append("),");
                    processSelectedKey(ssk, (AbstractNioChannel) ssk.attachment());
                    hashMap.remove(source);
                    fanoutRecord.remove(source);
                }
            }
            tmp.clear();
//                    System.err.println(Arrays.toString(tmp.toArray()));
        }
//
//        // count remaining time
        if (!hashMap.isEmpty()) {
            int count = 0;
//            int max = -1;
//            int record = -1;
            if (fanoutRecord.containsKey(firstLargeRecord)) {
                record = firstLargeRecord;
            } else if (fanoutRecord.containsKey(secondLargeRecord)) {
                record = secondLargeRecord;
            } else {
                Random random = new Random();
                Set keyArray = fanoutRecord.keySet();
                record = (int) keyArray.toArray()[random.nextInt(keyArray.size())];
//                record = (int) keyArray.toArray()[0];
            }

//            System.out.println();
            for (SelectionKey sk2 : hashMap.get(record)) {
                int tmpFanout = fanoutRecord.get(record)[0];
                tmpFanout -= 1;
                int[] _a = {tmpFanout, 0};
                fanoutRecord.put(record, _a);
                final Object a2 = sk2.attachment();

                if (a2 instanceof AbstractNioChannel) {
                    str.append("(").append(((AbstractNioChannel) a2).getPriority().hashedSource)
                       .append(",").append(((AbstractNioChannel) a2).getPriority().fanout)
                       .append("),");
                    processSelectedKey(sk2, (AbstractNioChannel) a2);
//                        selectedKeys.reset();
//                        selectWrapper();
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a2;
                    processSelectedKey(sk2, task);
                }
            }
            hashMap.get(record).clear();
//            System.out.println("first " + firstLargeRecord + "second " + secondLargeRecord + "record " + record);
            for (Map.Entry<Integer, int[]> entry : fanoutRecord.entrySet()) {
//                System.out.print(entry.getKey() + "=(" + entry.getValue()[0] + ", " + entry.getValue()[1] + "),");
                entry.getValue()[1]++;
            }
//            System.out.println();
        }

        if (!hashMap.isEmpty()) {
            int count = 0;
            for (Map.Entry<Integer, LinkedList<SelectionKey>> entry : hashMap.entrySet()) {
                for (SelectionKey sk2 : entry.getValue()) {
//                if (!entry.getValue().isEmpty()) {
//                    SelectionKey sk2 = entry.getValue().remove(n);
                    int tmpFanout = fanoutRecord.get(entry.getKey())[0];
                    tmpFanout -= 1;
                    int[] a = {tmpFanout, 0};
                    fanoutRecord.put(entry.getKey(), a);
                    final Object a2 = sk2.attachment();
                    if (a2 instanceof AbstractNioChannel) {
                        str.append("(").append(((AbstractNioChannel) a2).getPriority().hashedSource)
                           .append(",").append(((AbstractNioChannel) a2).getPriority().fanout)
                           .append("),");
                        processSelectedKey(sk2, (AbstractNioChannel) a2);
//                        selectedKeys.reset();
//                        selectWrapper();
                    } else {
                        @SuppressWarnings("unchecked")
                        NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a2;
                        processSelectedKey(sk2, task);
                    }
//                    break;
                }
//                hashMap.remove(entry.getKey());
//                hashMap.get(entry.getKey()).clear();
//                if (count++ > SELECT_THRESHOLD) {
//                    break;
//                }
            }
//            hashMap.clear();
        }

        // added by sgzhang
        if (selectTimestamp > 0 && str.length() != 0) {
//            System.out.println(selectTimestamp + "," + selectedKeysCnt + "-[" + acceptKeysCnt +
//                   "," + readKeysCnt + "," + writeKeysCnt + "]");
            System.out.println(selectTimestamp + "#" + System.currentTimeMillis() + "#[" + str.toString() + "]");
//            logger.warn(selectTimestamp + "," + selectedKeysCnt + "-[" + acceptKeysCnt +
//                        "," + readKeysCnt + "," + writeKeysCnt + "]");
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
//                System.out.println("******op_write");
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
//                System.out.println("******op_read");
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);
//            long selectDeadLineNanos = currentTimeNanos + 10000000L;  // set timeout 10ms
            for (;;) {
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                if (timeoutMillis <= 0) {
                    if (selectCnt == 0) {
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                int selectedKeys = selector.select(timeoutMillis);
                selectCnt ++;

                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                long time = System.nanoTime();
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The selector returned prematurely many times in a row.
                    // Rebuild the selector to work around the problem.
                    logger.warn(
                            "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                            selectCnt, selector);

                    rebuildSelector();
                    selector = this.selector;

                    // Select again to populate selectedKeys.
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
