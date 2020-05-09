/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.locks.LockSupport;

/**
 * Sleeping strategy that initially spins, then uses a Thread.yield(), and
 * eventually sleep (<code>LockSupport.parkNanos(n)</code>) for the minimum
 * number of nanos the OS and JVM will allow while the
 * {@link com.lmax.disruptor.EventProcessor}s are waiting on a barrier.
 * <p>
 * This strategy is a good compromise between performance and CPU resource.
 * Latency spikes can occur after quiet periods.  It will also reduce the impact
 * on the producing thread as it will not need signal any conditional variables
 * to wake up the event handling thread.
 *
 * <p></p>
 * 当{@link com.lmax.disruptor.EventProcessor}s正在等待一个屏障时：
 * 休眠策略，开始是自旋的，然后使用Thread.yield()，最后休眠(<code>LockSupport.parkNanos(n)</code>)，
 * 以获得操作系统和JVM允许的最小数量的nanos。
 *
 * <p>此策略是性能和CPU资源之间的一个很好的折衷。潜伏期峰值可以在安静期之后出现。
 * 它还将减少对产生者线程的影响，因为它不需要发出任何条件变量的信号来唤醒事件处理线程。
 */
public final class SleepingWaitStrategy implements WaitStrategy
{
    private static final int DEFAULT_RETRIES = 200;
    private static final long DEFAULT_SLEEP = 100;

    private final int retries;
    private final long sleepTimeNs;

    public SleepingWaitStrategy()
    {
        this(DEFAULT_RETRIES, DEFAULT_SLEEP);
    }

    public SleepingWaitStrategy(int retries)
    {
        this(retries, DEFAULT_SLEEP);
    }

    public SleepingWaitStrategy(int retries, long sleepTimeNs)
    {
        this.retries = retries;
        this.sleepTimeNs = sleepTimeNs;
    }

    @Override
    public long waitFor(
        final long sequence, Sequence cursor, final Sequence dependentSequence, final SequenceBarrier barrier)
        throws AlertException
    {
        long availableSequence;
        int counter = retries;

        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            counter = applyWaitMethod(barrier, counter);
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
    }

    /**
     * waitFor方法的处理逻辑如下：
     *
     * 如果没有可用的序列号，则：
     *
     * 首先，自旋重试100次（此值可设置，默认200次），如果在重试过程中，存在可用的序列号，则直接返回可用的序列号。
     *
     * 否则，如果重试指定次数以后，还是没有可用序列号，则继续自旋重试，但这时每重试一次，便调用Thread.yield方法，
     *      放弃CPU的使用权，让其它线程可以使用CPU。当该线程再次获取CPU使用权时，继续重试，如果还没有可用的序列号，
     *      则继续放弃CPU使用权等待。此循环最多100次。
     *
     * 最后，如果还没有可用的序列号，则调用LockSupport.parkNanos方法阻塞线程，直到存在可用的序列号。
     *      当LockSupport.parkNanos方法由于超时返回后，如果还没有可用的序列号，则该线程获取CPU使用权以后，
     *      可能继续调用LockSupport.parkNanos方法阻塞线程。
     */
    private int applyWaitMethod(final SequenceBarrier barrier, int counter)
        throws AlertException
    {
        barrier.checkAlert();

        if (counter > 100)
        {
            --counter;
        }
        else if (counter > 0)
        {
            --counter;
            Thread.yield();
        }
        else
        {
            LockSupport.parkNanos(sleepTimeNs);
        }

        return counter;
    }
}
