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
 * Coordination barrier for tracking the cursor for publishers and sequence of
 * dependent {@link EventProcessor}s for processing a data structure
 * <p></p>
 *
 * 序号屏障（协调屏障）：
 * 通过跟踪生产者的cursor 和 当前EventProcessor依赖的的sequence(dependentSequence/traceSequences)，来协调对共享数据结构的访问。
 * <p>
 * 主要目的：
 * <p>
 * 1.协调 消费者与生产者 和 消费者与消费者之间 的速度 (进度控制)
 * <p>
 * 2.保证 消费者与生产者 和 消费者与消费者之间 之间的可见性 (读写volatile变量实现的)
 *
 * <p></p>
 * SequenceBarrier实例引用被EventProcessor持有，用于等待并获取可用的消费事件，主要体现在waitFor这个方法。
 * 要实现这个功能，需要3点条件：
 *      <p>
 *     1.知道生产者的位置。
 *      <p>
 *     2.因为Disruptor支持消费者链，在不同的消费者组之间，要保证后边的消费者组只有在前消费者组中的消费者都处理完毕后，才能进行处理。
 *      <p>
 *     3.暂时没有事件可消费，在等待可用消费时，还需要使用某种等待策略进行等待。
 */
public interface SequenceBarrier
{
    /**
     * Wait for the given sequence to be available for consumption.
     * <p></p>
     * 在该屏障上等待，直到该序号的数据可以被消费。
     * 是否可消费取决于生产者的cursor 和 当前EventProcessor依赖的的sequence。
     *
     * @param sequence to wait for
     * @return the sequence up to which is available
     *          <p> 看见的最大进度(不一定可消费)</p>
     * @throws AlertException       if a status change has occurred for the Disruptor
     * @throws InterruptedException if the thread needs awaking on a condition variable.
     * @throws TimeoutException     if a timeout occurs while waiting for the supplied sequence.
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /**
     * Get the current cursor value that can be read.
     *
     * @return value of the cursor for entries that have been published.
     */
    long getCursor();

    /**
     * The current alert status for the barrier.
     *
     * @return true if in alert otherwise false.
     */
    boolean isAlerted();

    /**
     * Alert the {@link EventProcessor}s of a status change and stay in this status until cleared.
     * <p>
     * 通知EventProcessor有状态发生了改变(有点像中断 {@link Thread#interrupt()})
     * <p>
     * 当调用{@link EventProcessor#halt()}将调用此方法。
     */
    void alert();

    /**
     * Clear the current alert status.
     * <p>清除上一个状态标记</p>
     */
    void clearAlert();

    /**
     * Check if an alert has been raised and throw an {@link AlertException} if it has.
     * <p></p>
     * 检查标记，如果为true则抛出异常。
     * 目前主要是用于告诉等待策略，消费者已经被请求关闭，需要从等待中退出。
     *
     * @throws AlertException if alert has been raised.
     */
    void checkAlert() throws AlertException;
}
