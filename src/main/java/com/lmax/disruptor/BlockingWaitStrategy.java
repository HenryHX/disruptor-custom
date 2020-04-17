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

import com.lmax.disruptor.util.ThreadHints;

/**
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 * <p>当吞吐量和低延迟不如CPU资源重要时，可以使用此策略。
 */
public final class BlockingWaitStrategy implements WaitStrategy
{
    private final Object mutex = new Object();

    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        // 当前cursor小于给定序号，也就是无可用Event
        if (cursorSequence.get() < sequence)
        {
            synchronized (mutex)
            {
                // 当给定的序号大于生产者cursor序号时，进行等待
                while (cursorSequence.get() < sequence)
                {
                    /**
                     * 消费者在等待可用消费事件时，会循环调用barrier.checkAlert()，再去调用锁的条件等待，等待可用消费事件。
                     * 有三个地方可以唤醒等待中的消费线程。
                     * 两种是在Sequencer实现类中
                     *      1、是有可用事件发布，通知消费线程继续消费；
                     *          {@link MultiProducerSequencer#publish(long)}
                     *          {@link SingleProducerSequencer#publish(long)}
                     *      2、是在调用next()获取可用的RingBuffer槽位时，发现RingBuffer满了（生产者速度大于消费者，导致生产者没有可用位置发布事件），
                     *          将唤醒消费者线程，此功能在3.3.5版本新增（Resignal any waiting threads when trying to publish to a full ring buffer ）。
                     *          开始我百思不得，为什么要在buffer满了的时候不断唤醒消费者线程，直到看到这个issue才明白。
                     *          大意是在log4j2中使用Disruptor时发生了死锁，为了避免在发布事件时，由于某种原因导致没有通知到消费者，
                     *          在生产者尝试往一个已满的buffer发布数据时，就会再通知消费者进行消费。
                     *          而这个bug最终也被Log4j认领，与Disruptor无关。
                     *          Disruptor这里的再次通知也是为了更加保险。
                     *      3、关闭Disruptor时，消费者关闭前将会处理完当前批次数据
                     *          （并非RingBuffer的所有数据，而是此次循环取出的最大可用序号以下的所有未处理数据），
                     *          如果消费者线程当前在等待状态，将被唤醒并终结。
                     *          {@link ProcessingSequenceBarrier#alert()}
                     *
                     *
                     */
                    barrier.checkAlert();
                    // 循环等待，在Sequencer中publish进行唤醒；等待消费时也会在循环中定时唤醒。
                    // 循环等待的原因，是要检查alert状态。如果不检查将导致不能关闭Disruptor。
                    mutex.wait();
                }
            }
        }

        // 给定序号大于上一个消费者组最慢消费者（如当前消费者为第一组则和生产者游标序号比较, 前一个循环已经保证）序号时，需要等待。
        // 不能超前消费上一个消费者组未消费完毕的事件。
        // 那么为什么这里没有锁呢？
        //      => 可以想一下此时的场景，代码运行至此，已能保证生产者有新事件，如果进入循环，说明上一组消费者还未消费完毕。
        // 而通常我们的消费者都是较快完成任务的，所以这里才会考虑使用Busy Spin的方式等待上一组消费者完成消费。
        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
            // 使用Busy Spin的方式等待可能出现的上一个消费者组未消费完成的情况。
            ThreadHints.onSpinWait();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        synchronized (mutex)
        {
            mutex.notifyAll();
        }
    }

    @Override
    public String toString()
    {
        return "BlockingWaitStrategy{" +
            "mutex=" + mutex +
            '}';
    }
}
