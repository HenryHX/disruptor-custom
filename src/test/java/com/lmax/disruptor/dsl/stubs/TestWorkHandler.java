package com.lmax.disruptor.dsl.stubs;

import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.support.TestEvent;

import java.util.concurrent.atomic.AtomicBoolean;

public class TestWorkHandler implements WorkHandler<TestEvent>
{
    private final AtomicBoolean readyToProcessEvent = new AtomicBoolean(false);
    private volatile boolean stopped = false;

    @Override
    public void onEvent(final TestEvent event) throws Exception
    {
        System.out.println(Thread.currentThread().getName() + " before work onEvent: readyToProcessEvent = " + readyToProcessEvent);
        waitForAndSetFlag(false);
        System.out.println(Thread.currentThread().getName() + " after work onEvent: readyToProcessEvent = " + readyToProcessEvent);    }

    public void processEvent()
    {
        System.out.println(Thread.currentThread().getName() + " before work processEvent: readyToProcessEvent = " + readyToProcessEvent);
        waitForAndSetFlag(true);
        System.out.println(Thread.currentThread().getName() + " after work processEvent: readyToProcessEvent = " + readyToProcessEvent);
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
}
