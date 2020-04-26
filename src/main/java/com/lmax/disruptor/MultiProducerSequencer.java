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

import sun.misc.Unsafe;

import com.lmax.disruptor.util.Util;


/**
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Suitable for use for sequencing across multiple publisher threads.</p>
 *
 * <p> * Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#next()}, to determine the highest available sequence that can be read, then
 * {@link Sequencer#getHighestPublishedSequence(long, long)} should be used.</p>
 *
 * <p>多生产者模型下的序号生成器,适用于多个发布线程进行定序。</p>
 * <p>
 * 注意:
 * 在使用该序号生成器时，不同的生产者调用{@link Sequencer#next()}都会更新cursor value，
 * 所以调用{@link Sequencer#getCursor()}后必须 调用{@link Sequencer#getHighestPublishedSequence(long, long)}
 * 确定真正可用的序号。（因为多生产者模型下，生产者之间是无锁的，预分配空间，那么真正填充的数据可能是非连续的），因此需要确认
 */
public final class MultiProducerSequencer extends AbstractSequencer
{
    private static final Unsafe UNSAFE = Util.getUnsafe();
    // 获取int[]数组类的第一个元素与该类起始位置的偏移。
    private static final long BASE = UNSAFE.arrayBaseOffset(int[].class);
    // 数组每个元素需要占用的地址偏移量，也有可能返回0。BASE和SCALE都是为了操作availableBuffer
    private static final long SCALE = UNSAFE.arrayIndexScale(int[].class);

    /**
     * 上次获取到的最小序号缓存，会被并发的访问，因此用Sequence，而单线程的Sequencer中则使用了一个普通long变量。
     * 在任何时候查询了消费者进度信息时都需要更新它。
     * 某些时候可以减少{@link #gatingSequences}的遍历(减少volatile读操作)。
     *
     * Util.getMinimumSequence(gatingSequences, current)的查询结果是递增的，但是缓存结果不一定的是递增，变量的更新存在竞态条件，
     * 它可能会被设置为一个更小的值。
     *
     * gatingSequenceCache 的更新采用的都是set,因为本身就可能设置为一个错误的值(更小的值)，使用volatile写也无法解决该问题，
     * 使用set可以减少内存屏障消耗
     * {@link SingleProducerSequencerFields#cachedValue}
     */
    private final Sequence gatingSequenceCache = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    // availableBuffer tracks the state of each ringbuffer slot
    // see below for more details on the approach
    // 多生产者模式下，标记哪些序号是真正被填充了数据的。 (用于获取连续的可用空间)
    // 其实就是表明数据是属于第几环
    private final int[] availableBuffer;
    /**
     * 用于快速的计算序号对应的下标，“与”计算就可以，本质上和RingBuffer中计算插槽位置一样
     * {@link RingBufferFields#elementAt(long)}
     */
    private final int indexMask;
    /**
     * 用于计算sequence可用标记的偏移量(“与”计算)
     */
    private final int indexShift;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public MultiProducerSequencer(int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
        availableBuffer = new int[bufferSize];
        indexMask = bufferSize - 1;
        indexShift = Util.log2(bufferSize);
        initialiseAvailableBuffer();
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(gatingSequences, requiredCapacity, cursor.get());
    }

    private boolean hasAvailableCapacity(Sequence[] gatingSequences, final int requiredCapacity, long cursorValue)
    {
        long wrapPoint = (cursorValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = gatingSequenceCache.get();

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > cursorValue)
        {
            long minSequence = Util.getMinimumSequence(gatingSequences, cursorValue);
            gatingSequenceCache.set(minSequence);

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence)
    {
        cursor.set(sequence);
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n)
    {
        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }

        long current;
        long next;

        do
        {
            // 当前游标值，初始化时是-1
            current = cursor.get();
            next = current + n;

            long wrapPoint = next - bufferSize;
            long cachedGatingSequence = gatingSequenceCache.get();

            if (wrapPoint > cachedGatingSequence || cachedGatingSequence > current)
            {
                long gatingSequence = Util.getMinimumSequence(gatingSequences, current);

                if (wrapPoint > gatingSequence)
                {
                    LockSupport.parkNanos(1); // TODO, should we spin based on the wait strategy?
                    continue;
                }

                gatingSequenceCache.set(gatingSequence);
            }
            else if (cursor.compareAndSet(current, next))
            {
                break;
            }
        }
        while (true);

        return next;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long current;
        long next;

        do
        {
            current = cursor.get();
            next = current + n;

            if (!hasAvailableCapacity(gatingSequences, n, current))
            {
                throw InsufficientCapacityException.INSTANCE;
            }
        }
        while (!cursor.compareAndSet(current, next));

        return next;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long consumed = Util.getMinimumSequence(gatingSequences, cursor.get());
        long produced = cursor.get();
        return getBufferSize() - (produced - consumed);
    }

    private void initialiseAvailableBuffer()
    {
        for (int i = availableBuffer.length - 1; i != 0; i--)
        {
            setAvailableBufferValue(i, -1);
        }

        setAvailableBufferValue(0, -1);
    }

    /**
     * 对比SingleProducerSequencer的publish，MultiProducerSequencer的publish没有设置cursor，
     * 而是将内部使用的availableBuffer数组对应位置进行设置。
     * <p>
     * availableBuffer是一个记录RingBuffer槽位状态的数组，通过对序列值sequence取ringBuffer大小的模，
     * 获得槽位号，再通过与ringBuffer大小相除，获取序列值所在的圈数，进行设置。
     * <p>
     * 这里没有直接使用模运算和触发运算，而使用更高效的位与和右移操作。
     *
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {
        setAvailable(sequence);
        // 如果使用BlokingWaitStrategy，才会进行通知。否则不会操作
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        for (long l = lo; l <= hi; l++)
        {
            setAvailable(l);
        }
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * The below methods work on the availableBuffer flag.
     * <p>
     * The prime reason is to avoid a shared sequence object between publisher threads.
     * (Keeping single pointers tracking start and end would require coordination
     * between the threads).
     * <p>
     * 主要原因是为了避免在发布服务线程之间共享sequence对象。(需要线程之间的协调以保持单指针跟踪开始和结束)。
     * <p>
     * --  Firstly we have the constraint that the delta between the cursor and minimum
     * gating sequence will never be larger than the buffer size (the code in
     * next/tryNext in the Sequence takes care of that).
     * -- Given that; take the sequence value and mask off the lower portion of the
     * sequence as the index into the buffer (indexMask). (aka modulo operator)
     * -- The upper portion of the sequence becomes the value to check for availability.
     * ie: it tells us how many times around the ring buffer we've been (aka division)
     * -- Because we can't wrap without the gating sequences moving forward (i.e. the
     * minimum gating sequence is effectively our last available position in the
     * buffer), when we have new data and successfully claimed a slot we can simply
     * write over the top.
     * <p></p>
     * ——首先，我们有一个限制，即游标和最小gating序列之间的增量永远不会大于缓冲区大小(序列中的next/tryNext代码会处理这个问题)。
     *        （防止生产者太快，覆盖未消费完的数据）
     * ——考虑到 将序列值和indexMask进行与操作，得到插槽的index。(又名模运算符)
     * ——序列的上部成为检查可用性的值。它告诉我们，我们已经绕着环形缓冲区转了多少圈(又名除法)
     * ——因为如果不向前移动gating序列，我们就无法进行包装(例如，最小gating序列实际上是缓冲区中最后可用的位置)，
     *   当我们有新数据并成功地声明了一个插槽时，我们可以简单地在上面写入。
     */
    private void setAvailable(final long sequence)
    {
        setAvailableBufferValue(calculateIndex(sequence), calculateAvailabilityFlag(sequence));
    }

    private void setAvailableBufferValue(int index, int flag)
    {
        // 使用Unsafe更新属性，因为是直接操作内存，所以需要计算元素位置对应的内存位置bufferAddress
        long bufferAddress = (index * SCALE) + BASE;
        // availableBuffer是标志可用位置的int数组，初始全为-1。
        // 随着sequence不断上升，buffer中固定位置的flag（也就是sequence和bufferSize相除的商）会一直增大。
        UNSAFE.putOrderedInt(availableBuffer, bufferAddress, flag);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        int index = calculateIndex(sequence);
        int flag = calculateAvailabilityFlag(sequence);
        long bufferAddress = (index * SCALE) + BASE;
        return UNSAFE.getIntVolatile(availableBuffer, bufferAddress) == flag;
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++)
        {
            if (!isAvailable(sequence))
            {
                return sequence - 1;
            }
        }

        return availableSequence;
    }

    /**
     * 求除/
     *      计算sequence对应可用标记，标记其实就是第几环
     *      sequence / bufferSize , bufferSize = 2^indexShift。
     * <pre>
     * 如buffersize = 8, indexShift = Util.log2(bufferSize) = 3
     *     sequence = 0(0000)        sequence>>>3 = 0(0)
     *     sequence = 6(0110)        sequence>>>3 = 0(0)
     *     sequence = 7(0111)        sequence>>>3 = 0(0)
     *     sequence = 8(1000)        sequence>>>3 = 1(1)
     *     sequence = 14(1110)       sequence>>>3 = 1(1)
     *     sequence = 15(1111)       sequence>>>3 = 1(1)
     *     sequence = 16(10000)      sequence>>>3 = 2(10)
     *     sequence = 23(10111)      sequence>>>3 = 2(10)
     *     sequence = 24(11000)      sequence>>>3 = 3(11)
     *     sequence = 31(11111)      sequence>>>3 = 3(11)
     *     sequence = 32(100000)     sequence>>>3 = 4(100)
     *     sequence = 39(100111)     sequence>>>3 = 4(100)
     *     sequence = 40(101000)     sequence>>>3 = 5(101)
     *     sequence = 47(101111)     sequence>>>3 = 5(101)
     *     sequence = 48(110000)     sequence>>>3 = 6(110)
     * </pre>
     */
    private int calculateAvailabilityFlag(final long sequence)
    {
        return (int) (sequence >>> indexShift);
    }

    /**
     * 求模%
     *      计算sequence对应的下标(插槽位置)
     *      直接使用序号 与 掩码（2的平方-1，也就是一个全1的二进制表示）,相当于 sequence % (bufferSize), bufferSize = indexMask + 1
     * <pre>
     * 如buffersize = 8, indexMask = bufferSize - 1 = 7(0111);
     *     sequence = 0(0000)        sequence&7 = 0(0)
     *     sequence = 6(0110)        sequence&7 = 6(0110)
     *     sequence = 7(0111)        sequence&7 = 7(0111)
     *     sequence = 8(1000)        sequence&7 = 0(0000)
     *     sequence = 14(1110)       sequence&7 = 6(0110)
     *     sequence = 15(1111)       sequence&7 = 7(0111)
     *     sequence = 16(10000)      sequence&7 = 0(0000)
     *     sequence = 23(10111)      sequence&7 = 7(0111)
     *     sequence = 24(11000)      sequence&7 = 0(0000)
     *     sequence = 31(11111)      sequence&7 = 7(0111)
     * </pre>
     */
    private int calculateIndex(final long sequence)
    {
        return ((int) sequence) & indexMask;
    }
}
