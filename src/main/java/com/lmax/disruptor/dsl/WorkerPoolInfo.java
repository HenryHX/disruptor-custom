package com.lmax.disruptor.dsl;

import com.lmax.disruptor.*;

import java.util.concurrent.Executor;

/**
 * 线程池消费者信息对象/工作者池信息。
 * （WorkPool整体是一个消费者，是一个多线程的消费者）
 */
class WorkerPoolInfo<T> implements ConsumerInfo
{
    private final WorkerPool<T> workerPool;
    private final SequenceBarrier sequenceBarrier;
    private boolean endOfChain = true;

    WorkerPoolInfo(final WorkerPool<T> workerPool, final SequenceBarrier sequenceBarrier)
    {
        this.workerPool = workerPool;
        this.sequenceBarrier = sequenceBarrier;
    }

    @Override
    public Sequence[] getSequences()
    {
        return workerPool.getWorkerSequences();
    }

    @Override
    public SequenceBarrier getBarrier()
    {
        return sequenceBarrier;
    }

    @Override
    public boolean isEndOfChain()
    {
        return endOfChain;
    }

    @Override
    public void start(Executor executor)
    {
        workerPool.start(executor);
    }

    @Override
    public void halt()
    {
        workerPool.halt();
    }

    @Override
    public void markAsUsedInBarrier()
    {
        endOfChain = false;
    }

    @Override
    public boolean isRunning()
    {
        return workerPool.isRunning();
    }
}
