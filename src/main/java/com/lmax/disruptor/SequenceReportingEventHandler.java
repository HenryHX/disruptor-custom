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
 * Used by the {@link BatchEventProcessor} to set a callback allowing the {@link EventHandler} to notify
 * when it has finished consuming an event if this happens after the {@link EventHandler#onEvent(Object, long, boolean)} call.
 * <p>
 * Typically this would be used when the handler is performing some sort of batching operation such as writing to an IO
 * device; after the operation has completed, the implementation should call {@link Sequence#set} to update the
 * sequence and allow other processes that are dependent on this handler to progress.
 *
 * <p></p>
 * {@link BatchEventProcessor}用于设置回调，允许{@link EventHandler}在{@link EventHandler#onEvent(Object, long, boolean)}调用后通知它已经完成了一个事件的使用。
 * <p>通常在处理程序执行某种批处理操作时使用，例如写入IO设备;一个批次消息中的每一个Event操作完成后，实现应该调用{@link Sequence#set}来更新序列，
 * 并允许依赖于此处理程序的其他进程继续前进。
 *
 * <pre>
 *
 * Notify the BatchEventProcessor that the sequence has progressed. Without this callback the sequence would not
 * be progressed until the batch has completely finished.
 *
 * private void notifyIntermediateProgress(final long sequence){
 *      if(++counter>NOTIFY_PROGRESS_THRESHOLD){
 *          sequenceCallback.set(sequence);
 *          counter=0;
 *      }
 * }
 * </pre>
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public interface SequenceReportingEventHandler<T>
    extends EventHandler<T>
{
    /**
     * Call by the {@link BatchEventProcessor} to setup the callback.
     *
     * @param sequenceCallback callback on which to notify the {@link BatchEventProcessor} that the sequence has progressed.
     */
    void setSequenceCallback(Sequence sequenceCallback);
}
