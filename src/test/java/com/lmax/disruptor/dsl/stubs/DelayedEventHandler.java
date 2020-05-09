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
package com.lmax.disruptor.dsl.stubs;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.LifecycleAware;
import com.lmax.disruptor.support.TestEvent;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

public class DelayedEventHandler implements EventHandler<TestEvent>, LifecycleAware
{
    private final AtomicBoolean readyToProcessEvent = new AtomicBoolean(false);
    private volatile boolean stopped = false;
    private final CyclicBarrier barrier;

    public DelayedEventHandler(CyclicBarrier barrier)
    {
        this.barrier = barrier;
    }

    public DelayedEventHandler()
    {
        this(new CyclicBarrier(2));
    }

    @Override
    public void onEvent(final TestEvent entry, final long sequence, final boolean endOfBatch) throws Exception
    {
        System.out.println(Thread.currentThread().getName() + " before onEvent: readyToProcessEvent = " + readyToProcessEvent);
        waitForAndSetFlag(false);
        System.out.println(Thread.currentThread().getName() + " after onEvent: readyToProcessEvent = " + readyToProcessEvent);
    }

    /**
     * 如果不调用该方法，onEvent会阻塞在while循环， 因为readyToProcessEvent初始值是false，compareAndSet(true, false)返回false
     * <p>==> <p>onEvent 等待 readyToProcessEvent=true
     *     <p>processEvent 设置readyToProcessEvent=true
     *     <p>onEvent继续执行 设置readyToProcessEvent=false
     *
     *     <p>以上为一个循环，同一个线程，下一次执行onEvent方法时，依然要等待 readyToProcessEvent=true，即需要执行processEvent方法
     */
    public void processEvent()
    {
        System.out.println(Thread.currentThread().getName() + " before processEvent: readyToProcessEvent = " + readyToProcessEvent);
        waitForAndSetFlag(true);
        System.out.println(Thread.currentThread().getName() + " after processEvent: readyToProcessEvent = " + readyToProcessEvent);
    }

    public void stopWaiting()
    {
        stopped = true;
    }

    private void waitForAndSetFlag(final boolean newValue)
    {
        while (!stopped && !Thread.currentThread().isInterrupted() &&
            !readyToProcessEvent.compareAndSet(!newValue, newValue))
        {
            Thread.yield();
        }
    }

    @Override
    public void onStart()
    {
        try
        {
            barrier.await();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        catch (BrokenBarrierException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onShutdown()
    {
    }

    public void awaitStart() throws InterruptedException, BrokenBarrierException
    {
        barrier.await();
    }
}
