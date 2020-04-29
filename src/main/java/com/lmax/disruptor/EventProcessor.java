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

/**
 * An EventProcessor needs to be an implementation of a runnable that will poll for events from the {@link RingBuffer}
 * using the appropriate wait strategy.  It is unlikely that you will need to implement this interface yourself.
 * Look at using the {@link EventHandler} interface along with the pre-supplied BatchEventProcessor in the first
 * instance.
 * <p>
 * An EventProcessor will generally be associated with a Thread for execution.
 * <p></p>
 * 等待RingBuffer有可用消费事件。一个EventProcessor关联一个执行线程。
 * <p></p>
 * EventProcessor接口继承了Runnable接口，主要有两种实现：
 *      1)单线程批量处理{@link BatchEventProcessor}
 *      2)多线程处理{@link WorkProcessor}。
 * 在使用Disruptor帮助类构建消费者时，使用handleEventsWith方法传入多个EventHandler，内部使用多个BatchEventProcessor关联多个线程执行。
 *      这种情况类似JMS中的发布订阅模式，同一事件会被多个消费者并行消费。适用于同一事件触发多种操作。
 * 而使用Disruptor的handleEventsWithWorkerPool传入多个WorkHandler时，内部使用多个WorkProcessor关联多个线程执行。
 *      这种情况类似JMS的点对点模式，同一事件会被一组消费者其中之一消费。适用于提升消费者并行处理能力。
 *
 */
public interface EventProcessor extends Runnable
{
    /**
     * Get a reference to the {@link Sequence} being used by this {@link EventProcessor}.
     * <p>获取此{@link EventProcessor}使用的{@link Sequence}的引用。</p>
     *
     * @return reference to the {@link Sequence} for this {@link EventProcessor}
     */
    Sequence getSequence();

    /**
     * Signal that this EventProcessor should stop when it has finished consuming at the next clean break.
     * It will call {@link SequenceBarrier#alert()} to notify the thread to check status.
     */
    void halt();

    /**
     * @return HALTED & RUNNING 返回true；IDLE 返回 false
     */
    boolean isRunning();
}
