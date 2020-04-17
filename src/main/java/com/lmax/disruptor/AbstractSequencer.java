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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.lmax.disruptor.util.Util;

/**
 * Base class for the various sequencer types (single/multi).  Provides
 * common functionality like the management of gating sequences (add/remove) and
 * ownership of the current cursor.
 * <p>抽象序号生成器，作为单生产者和多生产者序列号生成器的超类，实现一些公共的功能(添加删除gatingSequence)。</p>
 */
public abstract class AbstractSequencer implements Sequencer
{
    private static final AtomicReferenceFieldUpdater<AbstractSequencer, Sequence[]> SEQUENCE_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(AbstractSequencer.class, Sequence[].class, "gatingSequences");

    /**
     * 缓冲区大小(ringBuffer有效数据缓冲区大小)
     */
    protected final int bufferSize;
    /**
     * 消费者的等待策略。
     * 为何放在这里？因为SequenceBarrier由Sequencer创建，Barrier需要生产者的Sequence信息。
     */
    protected final WaitStrategy waitStrategy;
    /**
     * 生产者的序号序列，所有的生产者使用同一个序列。
     * 个人见解：改名叫cursor了，可能是做区分，代码里面的带cursor的都表示生产者们的Sequence。
     * <p></p>
     * 消费者与生产者之间的可见性保证是通过volatile变量的读写来保证的。
     * 消费者们观察生产者的进度，当看见生产者进度增大时，生产者这期间的操作对消费者来说都是可见的。
     * volatile的happens-before原则-----生产者的进度变大(写volatile)先于消费者看见它变大(读volatile)。
     * 在多生产者情况下，只能看见空间分配操作，要确定哪些数据发布还需要额外保证.
     * {@link #getHighestPublishedSequence(long, long)}
     * <p></p>
     * 注意：相同的可见性保证策略---后继消费者与其前驱消费者之间的可见性保证。
     * {@link com.lmax.disruptor.dsl.ConsumerInfo#getSequences()}
     */
    protected final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    /**
     * 门控Sequences，Sequencer必须和这些Sequence满足约束:
     * cursor-bufferSize <= Min(gatingSequence)
     * 即：所有的gatingSequences让出下一个插槽后，生产者才能获取该插槽。
     *
     * 对于生产者来讲，它只需要关注消费链最末端的消费者的进度（因为它们的进度是最慢的）。
     * 即：gatingSequences就是所有消费链末端的消费们所拥有的的Sequence。（想一想食物链）
     *
     * 类似{@link ProcessingSequenceBarrier#cursorSequence}
     */
    protected volatile Sequence[] gatingSequences = new Sequence[0];

    /**
     * Create with the specified buffer size and wait strategy.
     *
     * @param bufferSize   The total number of entries, must be a positive power of 2.
     * @param waitStrategy The wait strategy used by this sequencer
     */
    public AbstractSequencer(int bufferSize, WaitStrategy waitStrategy)
    {
        if (bufferSize < 1)
        {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        if (Integer.bitCount(bufferSize) != 1)
        {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        this.bufferSize = bufferSize;
        this.waitStrategy = waitStrategy;
    }

    /**
     * 获取生产者的生产进度(已发布的最大序号)
     * @see Sequencer#getCursor()
     */
    @Override
    public final long getCursor()
    {
        return cursor.get();
    }

    /**
     * @see Sequencer#getBufferSize()
     */
    @Override
    public final int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * @see Sequencer#addGatingSequences(Sequence...)
     */
    @Override
    public final void addGatingSequences(Sequence... gatingSequences)
    {
        SequenceGroups.addSequences(this, SEQUENCE_UPDATER, this, gatingSequences);
    }

    /**
     * @see Sequencer#removeGatingSequence(Sequence)
     */
    @Override
    public boolean removeGatingSequence(Sequence sequence)
    {
        return SequenceGroups.removeSequence(this, SEQUENCE_UPDATER, sequence);
    }

    /**
     * @see Sequencer#getMinimumSequence()
     */
    @Override
    public long getMinimumSequence()
    {
        return Util.getMinimumSequence(gatingSequences, cursor.get());
    }

    /**
     * @see Sequencer#newBarrier(Sequence...)
     */
    @Override
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack)
    {
        return new ProcessingSequenceBarrier(this, waitStrategy, cursor, sequencesToTrack);
    }

    /**
     * Creates an event poller for this sequence that will use the supplied data provider and
     * gating sequences.
     *
     * @param dataProvider    The data source for users of this event poller
     * @param gatingSequences Sequence to be gated on.
     * @return A poller that will gate on this ring buffer and the supplied sequences.
     */
    @Override
    public <T> EventPoller<T> newPoller(DataProvider<T> dataProvider, Sequence... gatingSequences)
    {
        return EventPoller.newInstance(dataProvider, this, new Sequence(), cursor, gatingSequences);
    }

    @Override
    public String toString()
    {
        return "AbstractSequencer{" +
            "waitStrategy=" + waitStrategy +
            ", cursor=" + cursor +
            ", gatingSequences=" + Arrays.toString(gatingSequences) +
            '}';
    }
}