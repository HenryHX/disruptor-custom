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

import java.util.concurrent.atomic.AtomicInteger;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    private static final int IDLE = 0;
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    private final AtomicInteger running = new AtomicInteger(IDLE);
    private ExceptionHandler<? super T> exceptionHandler = new FatalExceptionHandler();
    /**
     * 数据提供者，默认是{@link RingBuffer}，也可替换为自己的数据结构
     */
    private final DataProvider<T> dataProvider;
    /**
     * 默认为{@link ProcessingSequenceBarrier}
     */
    private final SequenceBarrier sequenceBarrier;
    // 此EventProcessor对应的用户自定义的EventHandler实现
    private final EventHandler<? super T> eventHandler;
    // 当前执行位置
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final TimeoutHandler timeoutHandler;
    // 每次循环取得一批可用事件后，在实际处理前调用
    private final BatchStartAware batchStartAware;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     *                        <p>数据存储结构如RingBuffer
     * @param sequenceBarrier on which it is waiting.
     *                        <p>用于跟踪生产者游标和前置消费者Sequence，协调数据处理</p>
     * @param eventHandler    is the delegate to which events are dispatched.
     *                        <p>用户实现的事件处理器，也就是实际的消费者</p>
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware =
            (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler =
            (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        running.set(HALTED);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get() != IDLE;
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
        // 线程是否运行
        if (running.compareAndSet(IDLE, RUNNING))
        {
            // 将ProcessingSequenceBarrier的alerted设置成false
            sequenceBarrier.clearAlert();

            // start事件处理
            notifyStart();
            try
            {
                if (running.get() == RUNNING)
                {
                    processEvents();
                }
            }
            finally
            {
                notifyShutdown();
                running.set(IDLE);
            }
        }
        else
        {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (running.get() == RUNNING)
            {
                throw new IllegalStateException("Thread is already running");
            }
            else
            {
                earlyExit();
            }
        }
    }

    /**
     * 消费者(Consumer)现在只需要通过简单通过ProcessingSequenceBarrier拿到可用的Ringbuffer中的Sequence序号就可以可以读取数据了。
     * 因为这些新的节点的确已经写入了数据（RingBuffer本身的序号已经更新），而且消费者对这些节点的唯一操作是读而不是写，因此访问不用加锁。
     * 不仅代码实现起来可以更加安全和简单，而且不用加锁使得速度更快。
     *
     * 另一个好处是可以用多个消费者(Consumer)去读同一个RingBuffer，不需要加锁，也不需要用另外的队列来协调不同的线程(消费者)。
     * 这样你可以在Disruptor的协调下实现真正的并发数据处理。
     */
    private void processEvents()
    {
        T event = null;
        // 获取当前事件处理器的下一个sequence
        long nextSequence = sequence.get() + 1L;

        while (true)
        {
            try
            {
                // 从ProcessingSequenceBarrier获取可用的availableSequence
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                // 下一个nextSequence比可用的availableSequence小的时候，获取事件，并触发事件处理
                if (batchStartAware != null && availableSequence >= nextSequence)
                {
                    batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                }

                while (nextSequence <= availableSequence)
                {
                    event = dataProvider.get(nextSequence);
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    nextSequence++;
                }

                // 设置当前事件处理器已经处理的sequence
                sequence.set(availableSequence);
            }
            catch (final TimeoutException e)
            {
                // 超时处理
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                /**
                 * 如果一组消费者共享同一个{@link ProcessingSequenceBarrier}
                 * 其中一个消费者调用了{@link BatchEventProcessor#halt()}，该组内的所有消费者都会执行以下操作：
                 *      1、{@link ProcessingSequenceBarrier#alert()}设置alerted = true，唤醒等待在WaitStrategy.waitFor上的线程
                 *      2、barrier.checkAlert();抛出AlertException异常，消费者处理进入当前的catch处理逻辑
                 * 因为只有调用halt的那个消费者running会变成halt，所以其他消费者可以继续while(true)循环，会一直进入该catch处理逻辑
                 *
                 *
                 */
                if (running.get() != RUNNING)
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                // 异常事件处理
                exceptionHandler.handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    private void earlyExit()
    {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     * <p>在处理器关闭之前立即通知EventHandler</p>
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}