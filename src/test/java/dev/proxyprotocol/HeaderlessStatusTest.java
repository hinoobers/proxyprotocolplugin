package dev.proxyprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeaderlessStatusTest {
    private static void varint(ByteBuf out, int value) {
        do {
            int b = value & 127;
            value >>>= 7;
            out.writeByte(value == 0 ? b : b | 128);
        } while (value != 0);
    }

    private static ByteBuf handshake(int state, int hostLength) {
        ByteBuf body = Unpooled.buffer();
        varint(body, 0);
        varint(body, -1);
        varint(body, hostLength);
        for (int i = 0; i < hostLength; i++) body.writeByte('a');
        body.writeShort(25565);
        varint(body, state);
        ByteBuf packet = Unpooled.buffer();
        varint(packet, body.readableBytes());
        packet.writeBytes(body);
        body.release();
        return packet;
    }

    private static EmbeddedChannel channel(AtomicInteger errors) {
        return new EmbeddedChannel(new ProxyDecoder(4096, 0,
            address -> fail("Status must not rewrite the peer address"), cause -> errors.incrementAndGet()));
    }

    @Test void statusAndPingWorkAtEverySplitBoundaryIncludingProxyPrefixCollisions() {
        // Packet lengths 13 and 80 start with the first byte of a PROXY signature.
        for (int hostLength : new int[]{3, 9, 70, 255}) {
            ByteBuf stream = handshake(1, hostLength);
            stream.writeBytes(new byte[]{1, 0, 9, 1});
            stream.writeLong(123456L);
            for (int split = 0; split <= stream.readableBytes(); split++) {
                AtomicInteger errors = new AtomicInteger();
                EmbeddedChannel channel = channel(errors);
                channel.writeInbound(stream.copy(0, split));
                channel.writeInbound(stream.copy(split, stream.readableBytes() - split));
                ByteBuf actual = Unpooled.buffer();
                ByteBuf packet;
                while ((packet = (ByteBuf) channel.readInbound()) != null) {
                    actual.writeBytes(packet);
                    packet.release();
                }
                assertEquals(stream, actual);
                actual.release();
                assertTrue(channel.isOpen());
                assertEquals(0, errors.get());
                channel.finish();
            }
            stream.release();
        }
    }

    @Test void rejectsHeaderlessLoginAndLoginAfterStatusHandshake() {
        for (int state : new int[]{2, 3}) {
            AtomicInteger errors = new AtomicInteger();
            EmbeddedChannel channel = channel(errors);
            channel.writeInbound(handshake(state, 9));
            assertFalse(channel.isOpen());
            assertNull(channel.readInbound());
            assertEquals(1, errors.get());
            channel.finish();
        }
        AtomicInteger errors = new AtomicInteger();
        EmbeddedChannel channel = channel(errors);
        channel.writeInbound(handshake(1, 9));
        ((ByteBuf) channel.readInbound()).release();
        channel.writeInbound(handshake(2, 9));
        assertFalse(channel.isOpen());
        assertNull(channel.readInbound());
        assertEquals(1, errors.get());
        channel.finish();
    }

    @Test void rejectsMalformedAndOversizedHandshakes() {
        for (byte[] bytes : new byte[][]{
            {(byte) 128, (byte) 128, 1}, {6, 0, 47, 0, 0, 0, (byte) 128},
            {7, 0, 47, 0, 0, 0, 1, 0}, {6, 1, 47, 0, 0, 0, 1}}) {
            AtomicInteger errors = new AtomicInteger();
            EmbeddedChannel channel = channel(errors);
            channel.writeInbound(Unpooled.wrappedBuffer(bytes));
            assertFalse(channel.isOpen());
            assertEquals(1, errors.get());
            channel.finish();
        }
    }

    @Test void validProxyHeaderStillForwardsArbitraryMinecraftPayload() {
        AtomicInteger writes = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyDecoder(4096, 0,
            address -> writes.incrementAndGet(), cause -> fail(cause)));
        ByteBuf stream = Unpooled.copiedBuffer("PROXY TCP4 203.0.113.7 127.0.0.1 1234 25565\r\n", StandardCharsets.US_ASCII);
        ByteBuf login = handshake(2, 9);
        stream.writeBytes(login, login.readerIndex(), login.readableBytes());
        channel.writeInbound(stream);
        ByteBuf actual = (ByteBuf) channel.readInbound();
        assertEquals(login, actual);
        actual.release();
        login.release();
        assertEquals(1, writes.get());
        channel.finish();
    }
}
