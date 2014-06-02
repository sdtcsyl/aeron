/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.aeron.util.concurrent.logbuffer;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;

import java.nio.ByteBuffer;

import static java.lang.Integer.valueOf;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.util.concurrent.logbuffer.BufferDescriptor.*;
import static uk.co.real_logic.aeron.util.concurrent.logbuffer.FrameDescriptor.*;

public class LogRebuilderTest
{
    private static final int LOG_BUFFER_CAPACITY = 1024 * 16;
    private static final int STATE_BUFFER_CAPACITY = STATE_BUFFER_LENGTH;

    private final AtomicBuffer logBuffer = mock(AtomicBuffer.class);
    private final AtomicBuffer stateBuffer = spy(new AtomicBuffer(new byte[STATE_BUFFER_CAPACITY]));
    private final StateViewer stateViewer = new StateViewer(stateBuffer);

    private LogRebuilder logRebuilder;

    @Before
    public void setUp()
    {
        when(valueOf(logBuffer.capacity())).thenReturn(valueOf(LOG_BUFFER_CAPACITY));

        logRebuilder = new LogRebuilder(logBuffer, stateBuffer);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionWhenCapacityNotMultipleOfAlignment()
    {
        final int logBufferCapacity = BufferDescriptor.LOG_MIN_SIZE + FRAME_ALIGNMENT + 1;

        when(logBuffer.capacity()).thenReturn(logBufferCapacity);

        logRebuilder = new LogRebuilder(logBuffer, stateBuffer);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionOnInsufficientStateBufferCapacity()
    {
        when(stateBuffer.capacity()).thenReturn(BufferDescriptor.STATE_BUFFER_LENGTH - 1);

        logRebuilder = new LogRebuilder(logBuffer, stateBuffer);
    }

    @Test
    public void shouldInsertIntoEmptyBuffer()
    {
        final AtomicBuffer packet = new AtomicBuffer(ByteBuffer.allocate(256));
        int srcOffset = 0;
        int length = 256;

        when(logBuffer.getInt(lengthOffset(0), LITTLE_ENDIAN)).thenReturn(length);

        logRebuilder.insert(packet, srcOffset, length);
        assertFalse(logRebuilder.isComplete());

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(logBuffer).putBytes(0, packet, srcOffset, length);
        inOrder.verify(stateBuffer).putIntOrdered(TAIL_COUNTER_OFFSET, length);
        inOrder.verify(stateBuffer).putIntOrdered(HIGH_WATER_MARK_OFFSET, length);
    }

    @Test
    public void shouldInsertLastFrameIntoBuffer()
    {
        final int frameLength = FRAME_ALIGNMENT * 2;
        final int srcOffset = 0;
        final int tail = LOG_BUFFER_CAPACITY - frameLength;
        final AtomicBuffer packet = new AtomicBuffer(ByteBuffer.allocate(FRAME_ALIGNMENT));
        packet.putShort(typeOffset(srcOffset), (short)PADDING_FRAME_TYPE, LITTLE_ENDIAN);
        packet.putInt(termOffsetOffset(srcOffset), tail, LITTLE_ENDIAN);
        packet.putInt(lengthOffset(srcOffset), frameLength, LITTLE_ENDIAN);

        when(stateBuffer.getInt(TAIL_COUNTER_OFFSET)).thenReturn(tail);
        when(stateBuffer.getInt(HIGH_WATER_MARK_OFFSET)).thenReturn(tail);
        when(logBuffer.getInt(lengthOffset(tail), LITTLE_ENDIAN)).thenReturn(frameLength);
        when(logBuffer.getShort(typeOffset(tail), LITTLE_ENDIAN)).thenReturn((short)PADDING_FRAME_TYPE);

        logRebuilder.insert(packet, srcOffset, frameLength);
        assertTrue(logRebuilder.isComplete());

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(logBuffer).putBytes(tail, packet, srcOffset, frameLength);
        inOrder.verify(stateBuffer).putIntOrdered(TAIL_COUNTER_OFFSET, tail + frameLength);
        inOrder.verify(stateBuffer).putIntOrdered(HIGH_WATER_MARK_OFFSET, tail + frameLength);
    }

    @Test
    public void shouldFillSingleGap()
    {
        final int length = FRAME_ALIGNMENT;
        final int srcOffset = 0;
        final int tail = FRAME_ALIGNMENT;
        final AtomicBuffer packet = new AtomicBuffer(ByteBuffer.allocate(length));
        packet.putInt(termOffsetOffset(srcOffset), tail, LITTLE_ENDIAN);

        stateBuffer.putInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT);
        stateBuffer.putInt(HIGH_WATER_MARK_OFFSET, FRAME_ALIGNMENT * 3);
        when(logBuffer.getInt(lengthOffset(0))).thenReturn(length);
        when(logBuffer.getInt(lengthOffset(FRAME_ALIGNMENT), LITTLE_ENDIAN)).thenReturn(length);
        when(logBuffer.getInt(lengthOffset(FRAME_ALIGNMENT * 2), LITTLE_ENDIAN)).thenReturn(length);

        logRebuilder.insert(packet, srcOffset, length);

        assertThat(stateViewer.tailVolatile(), is(FRAME_ALIGNMENT * 3));
        assertThat(stateViewer.highWaterMarkVolatile(), is(FRAME_ALIGNMENT * 3));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(logBuffer).putBytes(tail, packet, srcOffset, length);
        inOrder.verify(stateBuffer).putIntOrdered(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT * 3);
    }

    @Test
    public void shouldFillAfterAGap()
    {
        final int length = FRAME_ALIGNMENT;
        final int srcOffset = 0;
        final AtomicBuffer packet = new AtomicBuffer(ByteBuffer.allocate(length));
        packet.putInt(termOffsetOffset(srcOffset), FRAME_ALIGNMENT * 2, LITTLE_ENDIAN);

        stateBuffer.putInt(TAIL_COUNTER_OFFSET, 0);
        stateBuffer.putInt(HIGH_WATER_MARK_OFFSET, FRAME_ALIGNMENT * 2);
        when(logBuffer.getInt(lengthOffset(0), LITTLE_ENDIAN)).thenReturn(0);
        when(logBuffer.getInt(lengthOffset(FRAME_ALIGNMENT), LITTLE_ENDIAN)).thenReturn(length);

        logRebuilder.insert(packet, srcOffset, length);

        assertThat(stateViewer.tailVolatile(), is(0));
        assertThat(stateViewer.highWaterMarkVolatile(), is(FRAME_ALIGNMENT * 3));

        final InOrder inOrder = inOrder(logBuffer, stateBuffer);
        inOrder.verify(logBuffer).putBytes(FRAME_ALIGNMENT * 2, packet, srcOffset, length);
        inOrder.verify(stateBuffer).putIntOrdered(HIGH_WATER_MARK_OFFSET, FRAME_ALIGNMENT * 3);
    }

    @Test
    public void shouldFillGapButNotMoveTailOrHwm()
    {
        final int length = FRAME_ALIGNMENT;
        final int srcOffset = 0;
        final AtomicBuffer packet = new AtomicBuffer(ByteBuffer.allocate(length));
        packet.putInt(termOffsetOffset(srcOffset), FRAME_ALIGNMENT * 2, LITTLE_ENDIAN);

        stateBuffer.putInt(TAIL_COUNTER_OFFSET, FRAME_ALIGNMENT);
        stateBuffer.putInt(HIGH_WATER_MARK_OFFSET, FRAME_ALIGNMENT * 4);
        when(logBuffer.getInt(lengthOffset(0), LITTLE_ENDIAN)).thenReturn(FRAME_ALIGNMENT);
        when(logBuffer.getInt(lengthOffset(FRAME_ALIGNMENT), LITTLE_ENDIAN)).thenReturn(0);

        logRebuilder.insert(packet, srcOffset, length);

        assertThat(stateViewer.tailVolatile(), is(FRAME_ALIGNMENT));
        assertThat(stateViewer.highWaterMarkVolatile(), is(FRAME_ALIGNMENT * 4));

        verify(logBuffer).putBytes(FRAME_ALIGNMENT * 2, packet, srcOffset, length);
    }
}
