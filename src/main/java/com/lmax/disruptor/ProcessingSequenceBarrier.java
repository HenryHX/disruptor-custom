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


import com.lmax.disruptor.util.Util;

/**
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 * <p>消费者使用的{@link EventProcessor}序号屏障</p>
 * <p>{@link SequenceBarrier}用于控制{@link EventProcessor}使用给定的等待策略控制自己
 * 在cursor sequence和从属{@link EventProcessor}(s) sequence上的行为。</p>
 */
final class ProcessingSequenceBarrier implements SequenceBarrier
{
    // 等待可用消费时，指定的等待策略
    private final WaitStrategy waitStrategy;
    /**
     * 依赖的上组消费者的序号，如果当前为第一组则为cursorSequence（即生产者发布cursor序列），否则使用FixedSequenceGroup封装上组消费者序列
     * <p></p>
     * EventProcessor(事件处理器)的Sequence必须小于等于依赖的Sequence
     * 来自于{@link com.lmax.disruptor.dsl.EventHandlerGroup#sequences}
     *
     * 对于直接和Sequencer相连的消费者，它依赖的Sequence就是Sequencer的Sequence。
     * 对于跟在其它消费者屁股后面的消费者，它依赖的Sequence就是它跟随的所有消费者的Sequence。
     *
     * 类似 {@link AbstractSequencer#gatingSequences}
     * dependentSequence
     */
    private final Sequence dependentSequence;
    /**
     * 是否请求了关闭消费者
     * 当触发halt时，将标记alerted为true
     */
    private volatile boolean alerted = false;
    /**
     * 生产者的进度(cursor)
     * 依赖该屏障的EventProcessor的进度【必然要小于等于】生产者的进度
     */
    private final Sequence cursorSequence;
    /**
     * 序号生成器(来自生产者)
     * <p>{@link MultiProducerSequencer} 或 {@link SingleProducerSequencer}
     */
    private final Sequencer sequencer;

    ProcessingSequenceBarrier(
        final Sequencer sequencer,
        final WaitStrategy waitStrategy,
        final Sequence cursorSequence,
        final Sequence[] dependentSequences)
    {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;
        if (0 == dependentSequences.length)
        {
            // 如果消费者不依赖于其它的消费者，那么只需要与生产者的进度进行协调
            dependentSequence = cursorSequence;
        }
        else
        {
            // 如果依赖于其它消费者，那么追踪其它消费者的进度。
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    public long waitFor(final long sequence)
        throws AlertException, InterruptedException, TimeoutException
    {
        // 检查是否停止服务
        checkAlert();

        // 获取最大可用序号 sequence为给定序号，一般为当前序号+1，
        // cursorSequence记录生产者最新位置，dependentSequence为依赖的Sequence[]
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);

        // 产生比预期的sequence小,可能序号被重置回老的的oldSequence值
        //可参考https://github.com/LMAX-Exchange/disruptor/issues/76
        if (availableSequence < sequence)
        {
            return availableSequence;
        }

        // 目标sequence已经发布了，这里获取真正的最大序号(和生产者模型有关)
        // 获取最大的可用的已经发布的sequence，可能比sequence小
        //      会在多生产者中出现，当生产者1获取到序号13，生产者2获取到14；
        //      生产者1没发布，生产者2发布，会导致获取的可用序号为12，而sequence为13
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    /**
     * 这里实际返回的是依赖的生产者/消费者的进度 - 因为当依赖其它消费者时，查询生产者进度对于等待是没有意义的
     * @return
     */
    @Override
    public long getCursor()
    {
        /**
         * the minimum sequence found or Long.MAX_VALUE if the array is empty.
         * {@link Util#getMinimumSequence(com.lmax.disruptor.Sequence[])}
         * <p>
         *     这里实际返回的是依赖的生产者/消费者的进度 - 因为当依赖其它消费者时，查询生产者进度对于等待是没有意义的
         * </p>
         */
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted()
    {
        return alerted;
    }

    @Override
    public void alert()
    {
        // 标记为被中断，并唤醒正在等待的消费者
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert()
    {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException
    {
        if (alerted)
        {
            throw AlertException.INSTANCE;
        }
    }
}