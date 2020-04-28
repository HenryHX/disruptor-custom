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
 * Implement this interface in your {@link EventHandler} to be notified when a thread for the
 * {@link BatchEventProcessor} starts and shuts down.
 */
public interface LifecycleAware
{
    /**
     * Called once on thread start before first event is available.
     * <p>在第一个事件可用之前，在线程启动时调用一次。</p>
     */
    void onStart();

    /**
     * <p>Called once just before the thread is shutdown.</p>
     * <p>仅在线程关闭之前调用一次。
     * <p>
     * Sequence event processing will already have stopped before this method is called. No events will
     * be processed after this message.
     * <p></p>
     * 在调用此方法之前，序列事件处理已经停止。此消息之后将不处理任何事件。
     */
    void onShutdown();
}
