/*
 * Copyright 2012 LMAX Ltd.
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
 * Coordinates claiming sequences for access to a data structure while tracking dependent {@link Sequence}s
 * <p>
 *
 * 序号生成器，为生产者们提供序号。
 *
 * 同时追踪消费序号的消费链末端们的消费者进度{@link Sequence},以协调序号生成器和消费者们之间的速度。
 */
public interface Sequencer extends Cursored, Sequenced
{
    /**
     * Set to -1 as sequence starting point
     */
    long INITIAL_CURSOR_VALUE = -1L;

    /**
     * Claim a specific sequence.  Only used if initialising the ring buffer to
     * a specific value.
     * <p></p>
     * 将生产者的序号(光标)移动到指定位置.（仅在初始化RingBuffer时使用）
     * 注意：这是个很危险的方法，不要在运行期间执行，存在生产者与生产者竞争和生产者与消费者竞争，
     * 可能造成数据丢失，各种运行异常，仅能在初始化阶段使用，目前已不需要使用。
     * 现在默认的初始化都是使用的{@link #INITIAL_CURSOR_VALUE}）
     *
     * @param sequence The sequence to initialise too.
     */
    void claim(long sequence);

    /**
     * Confirms if a sequence is published and the event is available for use; non-blocking.
     * <p>用非阻塞方式，确认指定序号的数据是否可用(是否已发布)。</p>
     *
     * @param sequence of the buffer to check
     * @return true if the sequence is available for use, false if not
     */
    boolean isAvailable(long sequence);

    /**
     * Add the specified gating sequences to this instance of the Disruptor.  They will
     * safely and atomically added to the list of gating sequences.
     * <p></p>
     *
     * 添加需要追踪的门控序列（新增的末端消费者消费序列/进度）到{@link AbstractSequencer#gatingSequences}，
     * Sequencer（生产者）会持续跟踪它们的进度信息，以协调生产者和消费者之间的速度。
     * 即生产者想使用一个序号时必须等待所有的 门控序列 处理完该序号。
     *
     * @param gatingSequences The sequences to add.
     */
    void addGatingSequences(Sequence... gatingSequences);

    /**
     * Remove the specified sequence from this sequencer.
     * <p></p>
     * 移除这些门控Sequence(消费者消费序列/进度)，不再跟踪它们的进度信息；
     * 注意：如果移除了所有的消费者，那么生产者便不会被阻塞！
     * 也就能{@link RingBuffer#next()} 死循环中醒来。
     *
     * 所以让生产者安全的发布事件，不需要使用{@link #tryNext()}，不停的重试，直接使用{@link #next()}，
     * 如果消费者在shutdown以后remove掉自己，那么生产者就会从{@link RingBuffer#next()}中返回！
     *
     * @param sequence to be removed.
     * @return <code>true</code> if this sequence was found, <code>false</code> otherwise.
     */
    boolean removeGatingSequence(Sequence sequence);

    /**
     * Create a new SequenceBarrier to be used by an EventProcessor to track which messages
     * are available to be read from the ring buffer given a list of sequences to track.
     * <p></p>
     * 创建一个新的SequenceBarrier，EventProcessor使用它来跟踪哪些消息可以从环形缓冲区中读取，并给出要跟踪的sequences列表。
     * 为啥放在Sequencer接口中？ Barrier需要知道序号生成器(Sequencer)的生产进度，需要持有Sequencer(this)对象引用。
     *
     * @param sequencesToTrack All of the sequences that the newly constructed barrier will wait on.
     *                         所有需要追踪的sequences，其实也是所有要追踪的前置消费者。
     *                         即消费者只能消费被这些Sequence代表的消费者们已经消费的序列
     * @return A sequence barrier that will track the specified sequences.
     * @see SequenceBarrier
     */
    SequenceBarrier newBarrier(Sequence... sequencesToTrack);

    /**
     * Get the minimum sequence value from all of the gating sequences
     * added to this ringBuffer.
     * <p></p>
     * 获取序号生成器(Sequencer自身)和 所有追踪的消费者们的进度信息中的最小序号。
     * 当没有消费者时，返回生产者的进度值cursor。
     *
     * @return The minimum gating sequence or the cursor sequence if
     * no sequences have been added.
     */
    long getMinimumSequence();

    /**
     * Get the highest sequence number that can be safely read from the ring buffer.  Depending
     * on the implementation of the Sequencer this call may need to scan a number of values
     * in the Sequencer.  The scan will range from nextSequence to availableSequence.  If
     * there are no available values <code>&gt;= nextSequence</code> the return value will be
     * <code>nextSequence - 1</code>.  To work correctly a consumer should pass a value that
     * is 1 higher than the last sequence that was successfully processed.
     * <p></p>
     *
     * 获取可以从环形缓冲区安全读取的最大序列号。根据Sequencer的实现，此调用可能需要扫描Sequencer中的许多值。
     * 扫描范围从nextSequence到availableSequence。
     * 如果没有可用的值<code>&gt;= nextSequence</code>，则返回值为<code> nextSequence - 1 </code>。
     * 要正确工作，consumer应该传递一个比最后一个成功处理的序列高1的值。
     *
     * 多生产者模式下可能是不连续的，由于{@link Sequencer#next(int)} next是预分配的，因此可能部分数据还未被填充
     *
     * @param nextSequence      The sequence to start scanning from.
     * @param availableSequence The sequence to scan to.
     *                          <p>
     *                          多生产者模式下，已发布的数据可能是不连续的，因此不能直接该序号进行消费。
     *                          必须顺序的消费，不能跳跃
     * @return The highest value that can be safely read, will be at least <code>nextSequence - 1</code>.
     *          <p>
     * 	       返回的值可以安全的读(必须是连续的)，最小返回 nextSequence - 1，即consumer消费的最后一个序号，此时返回时EventProcessor什么也不做
     */
    long getHighestPublishedSequence(long nextSequence, long availableSequence);

    <T> EventPoller<T> newPoller(DataProvider<T> provider, Sequence... gatingSequences);
}