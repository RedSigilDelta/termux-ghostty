//! Host-side harness for the production stream pump and byte pipe.
//!
//! It drives the real `SshNativeSession` stream code (`pumpStreamWith`, the `StreamChannel`
//! methods, the open queue, and pruning) with `ScriptedIo`, a stand-in that returns libssh2's
//! return conventions on request. It proves the stream state machine and its threading under
//! scripted peers. It does NOT prove libssh2 behaviour: no libssh2 code runs here.
//!
//! Build and run on the host (headers from the JDK and the pinned libssh2 are needed for the
//! `c` import; see docs/fork/plans/HERDR_TRANSPORT_VALIDATION.md, section 2.5):
//!
//!   zig test -lc -I "$JDK/include" -I "$JDK/include/darwin" -I "$LIBSSH2_INCLUDE" \
//!     terminal-emulator/src/main/zig/src/ssh_stream_harness.zig
const std = @import("std");
const sns = @import("ssh_native_session.zig");

const SshNativeSession = sns.SshNativeSession;
const StreamChannel = SshNativeSession.StreamChannel;
const c = sns.c;

/// Matches `max_queued_opens` and `max_live_streams` in ssh_native_session.zig.
const max_queued_opens: usize = 64;
const eagain: isize = c.LIBSSH2_ERROR_EAGAIN;

// libssh2 is not linked into the host harness. The retire path can reach `libssh2_channel_free`,
// but the harness never attaches a channel, so this stub must not run. It panics so a
// harness bug that does attach one fails loudly instead of silently succeeding.
export fn libssh2_channel_free(channel: ?*anyopaque) c_int {
    _ = channel;
    @panic("harness attached a libssh2 channel");
}
const socket_disconnect: c_int = c.LIBSSH2_ERROR_SOCKET_DISCONNECT;

/// A real `SshNativeSession` wired just enough for stream operations: a wake pipe, a registry,
/// and an allocator. No libssh2 session exists; no channel is ever attached.
const Fixture = struct {
    owner: SshNativeSession = undefined,
    fds: [2]c_int = .{ -1, -1 },

    fn setup(self: *Fixture) !void {
        if (c.pipe(&self.fds) != 0) return error.PipeFailed;
        _ = c.fcntl(self.fds[0], c.F_SETFL, c.O_NONBLOCK);
        _ = c.fcntl(self.fds[1], c.F_SETFL, c.O_NONBLOCK);
        const owner = &self.owner;
        owner.allocator = std.testing.allocator;
        owner.wake_pipe = self.fds;
        owner.running = std.atomic.Value(bool).init(true);
        owner.streams_lock = .{};
        owner.streams = .empty;
        owner.ops = .empty;
        owner.open_queue = .{};
        owner.open_slot = null;
    }

    /// Retires whatever a test left linked. Tests release their own streams first, so a leak
    /// is reported by the testing allocator rather than hidden here.
    fn teardown(self: *Fixture) void {
        self.owner.open_queue.clear();
        self.owner.failStreams();
        self.owner.retireStreams();
        self.owner.open_queue.deinit(std.heap.page_allocator);
        self.owner.streams.deinit(std.heap.page_allocator);
        self.owner.ops.deinit(std.heap.page_allocator);
        _ = c.close(self.fds[0]);
        _ = c.close(self.fds[1]);
    }

    /// Links a stream the way `openStreamLocal` does, then does what `driveOpen` does once
    /// libssh2 reports success. The loop never runs here, so the queue entry is removed.
    fn openStream(self: *Fixture, path: []const u8) !*StreamChannel {
        const stream = try self.owner.openStreamLocal(path);
        _ = self.owner.open_queue.remove(.{ .stream = stream });
        stream.setPhase(.open, .none, "");
        return stream;
    }
};

/// Scripted stand-in for libssh2. Pump-thread owned: nothing else may touch it while a pump runs.
const ScriptedIo = struct {
    sink: std.ArrayList(u8) = .empty,
    write_limit: usize = std.math.maxInt(usize),
    write_eagain_every: usize = 0,
    write_calls: usize = 0,
    write_error: ?c_int = null,
    /// Bytes the peer sends. `peer_closed` makes the peer's end-of-stream visible after them.
    inbound: []const u8 = "",
    inbound_pos: usize = 0,
    read_limit: usize = std.math.maxInt(usize),
    read_eagain_every: usize = 0,
    read_calls: usize = 0,
    peer_closed: bool = false,
    released: usize = 0,
    error_code: c_int = 0,

    fn deinit(self: *ScriptedIo) void {
        self.sink.deinit(std.testing.allocator);
    }

    pub fn write(self: *ScriptedIo, bytes: []const u8) isize {
        self.write_calls += 1;
        if (self.write_error) |code| return code;
        if (self.write_eagain_every != 0 and self.write_calls % self.write_eagain_every == 0) return eagain;
        const count = @min(bytes.len, self.write_limit);
        self.sink.appendSlice(std.testing.allocator, bytes[0..count]) catch @panic("sink OOM");
        return @intCast(count);
    }

    pub fn read(self: *ScriptedIo, buffer: []u8) isize {
        self.read_calls += 1;
        if (self.read_eagain_every != 0 and self.read_calls % self.read_eagain_every == 0) return eagain;
        if (self.inbound_pos >= self.inbound.len) return if (self.peer_closed) 0 else eagain;
        const count = @min(buffer.len, self.read_limit, self.inbound.len - self.inbound_pos);
        @memcpy(buffer[0..count], self.inbound[self.inbound_pos..][0..count]);
        self.inbound_pos += count;
        return @intCast(count);
    }

    pub fn eof(self: *ScriptedIo) bool {
        return self.peer_closed and self.inbound_pos >= self.inbound.len;
    }

    pub fn lastError(self: *ScriptedIo) struct { code: c_int, message: []const u8 } {
        return .{ .code = self.error_code, .message = "scripted failure" };
    }

    pub fn release(self: *ScriptedIo) void {
        self.released += 1;
    }
};

fn pumpOnce(stream: *StreamChannel, io: *ScriptedIo) bool {
    return SshNativeSession.pumpStreamWith(stream, io);
}

fn patterned(comptime n: usize, seed: u8) [n]u8 {
    var out: [n]u8 = undefined;
    for (&out, 0..) |*byte, index| byte.* = @truncate(index *% 131 +% seed);
    return out;
}

fn patternedAlloc(allocator: std.mem.Allocator, n: usize, seed: u8) ![]u8 {
    const out = try allocator.alloc(u8, n);
    for (out, 0..) |*byte, index| byte.* = @truncate(index *% 131 +% seed);
    return out;
}

/// Writes all of `data`, retrying briefly on a full ring. Returns false if the stream stopped.
fn writeAll(stream: *StreamChannel, data: []const u8) bool {
    var offset: usize = 0;
    var attempts: usize = 0;
    while (offset < data.len) : (attempts += 1) {
        if (attempts > 100_000) return false;
        const result = stream.write(data[offset..], 1);
        if (result < 0) return false;
        offset += @intCast(result);
    }
    return true;
}

/// Reads until end-of-stream or a failure, appending to `out`. Returns the final status.
fn readUntilEnd(stream: *StreamChannel, out: *std.ArrayList(u8), allocator: std.mem.Allocator) c_int {
    var buffer: [4096]u8 = undefined;
    var attempts: usize = 0;
    while (attempts < 100_000) : (attempts += 1) {
        const result = stream.read(&buffer, 1);
        if (result > 0) {
            out.appendSlice(allocator, buffer[0..@intCast(result)]) catch @panic("reader OOM");
        } else if (result == 0) {
            continue;
        } else {
            return result;
        }
    }
    return -99;
}

/// Closes, retires, and releases a stream the test owns, so the allocator sees it freed.
fn finishStream(fx: *Fixture, stream: *StreamChannel) void {
    stream.close();
    fx.owner.retireStreams();
    stream.release();
}

test "pump delivers a whole message through partial writes and EAGAIN" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();
    const stream = try fx.openStream("/tmp/herdr-harness-a.sock");
    var io = ScriptedIo{ .write_limit = 7, .write_eagain_every = 3 };
    defer io.deinit();

    const message = try patternedAlloc(std.testing.allocator, 1000, 1);
    defer std.testing.allocator.free(message);
    try std.testing.expect(writeAll(stream, message));
    try std.testing.expectEqual(@as(c_int, 0), stream.flush(0));

    var passes: usize = 0;
    while (stream.flush(0) != 1) : (passes += 1) {
        try std.testing.expect(passes < 10_000);
        _ = pumpOnce(stream, &io);
    }
    try std.testing.expectEqualSlices(u8, message, io.sink.items);
    try std.testing.expect(!pumpOnce(stream, &io));
    finishStream(&fx, stream);
}

test "a full inbound ring throttles the pump, and a read wakes the loop" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();
    const stream = try fx.openStream("/tmp/herdr-harness-b.sock");
    const peer = try patternedAlloc(std.testing.allocator, 200 * 1024, 2);
    defer std.testing.allocator.free(peer);
    var io = ScriptedIo{ .inbound = peer, .read_limit = 16 * 1024 };
    defer io.deinit();

    // 64 KiB fills the ring exactly, so the pump stops throttled rather than reporting more work.
    try std.testing.expect(!pumpOnce(stream, &io));
    try std.testing.expectEqual(@as(usize, 64 * 1024), io.inbound_pos);
    try std.testing.expect(stream.inbound_throttled);

    // Clear any wakeups from setup, then a JVM read must wake the loop.
    var sink: [64]u8 = undefined;
    while (c.read(fx.fds[0], &sink, sink.len) > 0) {}
    var buffer: [1024]u8 = undefined;
    try std.testing.expectEqual(@as(c_int, 1024), stream.read(&buffer, 0));
    try std.testing.expect(c.read(fx.fds[0], &sink, sink.len) > 0);
    try std.testing.expect(!stream.inbound_throttled);

    // Drain the rest through the pump and the reader; the bytes must match the peer exactly.
    var received = std.ArrayList(u8).empty;
    defer received.deinit(std.testing.allocator);
    try received.appendSlice(std.testing.allocator, &buffer);
    var passes: usize = 0;
    while (received.items.len < peer.len) : (passes += 1) {
        try std.testing.expect(passes < 100_000);
        _ = pumpOnce(stream, &io);
        const result = stream.read(&buffer, 0);
        if (result > 0) try received.appendSlice(std.testing.allocator, buffer[0..@intCast(result)]);
    }
    try std.testing.expectEqualSlices(u8, peer, received.items);
    finishStream(&fx, stream);
}

test "peer end-of-stream arrives after buffered bytes, and writes then fail as a disconnect" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();
    const stream = try fx.openStream("/tmp/herdr-harness-c.sock");
    const peer = try patternedAlloc(std.testing.allocator, 40 * 1024, 3);
    defer std.testing.allocator.free(peer);
    var io = ScriptedIo{ .inbound = peer, .peer_closed = true, .read_limit = 16 * 1024 };
    defer io.deinit();

    _ = pumpOnce(stream, &io);
    try std.testing.expectEqual(@as(usize, 1), io.released);
    try std.testing.expect(stream.channel == null);

    var received = std.ArrayList(u8).empty;
    defer received.deinit(std.testing.allocator);
    try std.testing.expectEqual(@as(c_int, -1), readUntilEnd(stream, &received, std.testing.allocator));
    try std.testing.expectEqualSlices(u8, peer, received.items);

    try std.testing.expectEqual(@as(c_int, -2), stream.write("late", 0));
    try std.testing.expectEqual(@as(c_int, 3), stream.failureCode());
    finishStream(&fx, stream);
}

test "a libssh2 write failure fails the stream and is classified as a disconnect" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();
    const stream = try fx.openStream("/tmp/herdr-harness-d.sock");
    var io = ScriptedIo{ .write_error = socket_disconnect, .error_code = socket_disconnect };
    defer io.deinit();

    try std.testing.expect(writeAll(stream, "pending bytes"));
    try std.testing.expect(!pumpOnce(stream, &io));
    try std.testing.expectEqual(@as(c_int, 3), stream.failureCode());
    try std.testing.expectEqual(@as(c_int, -2), stream.write("more", 0));
    try std.testing.expectEqual(@as(c_int, -2), stream.flush(0));
    finishStream(&fx, stream);
}

test "close discards unflushed bytes and wakes a waiting flusher" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();
    const stream = try fx.openStream("/tmp/herdr-harness-e.sock");

    try std.testing.expect(writeAll(stream, "never sent"));
    try std.testing.expectEqual(@as(c_int, 0), stream.flush(0));
    stream.close();
    try std.testing.expectEqual(@as(c_int, -1), stream.flush(0));
    try std.testing.expectEqual(@as(c_int, -1), stream.write("x", 0));
    var buffer: [4]u8 = undefined;
    try std.testing.expectEqual(@as(c_int, -1), stream.read(&buffer, 0));
    fx.owner.retireStreams();
    stream.release();
}

test "cancelled queued opens are pruned, which frees queue and live-stream capacity" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();

    var streams: [max_queued_opens]*StreamChannel = undefined;
    for (&streams, 0..) |*slot, index| {
        slot.* = try fx.owner.openStreamLocal(if (index % 2 == 0) "/tmp/herdr-q.sock" else "/tmp/herdr-r.sock");
    }
    try std.testing.expectError(error.TooManyPending, fx.owner.openStreamLocal("/tmp/herdr-overflow.sock"));

    // The JVM cancels one queued open. Pruning removes it from the queue before it can take the slot.
    streams[0].close();
    fx.owner.pruneDeadOpens();
    try std.testing.expectEqual(max_queued_opens - 1, fx.owner.open_queue.len());

    // Retiring the closed stream frees its live-stream entry, so a new open is admitted.
    fx.owner.retireStreams();
    const replacement = try fx.owner.openStreamLocal("/tmp/herdr-replacement.sock");
    _ = fx.owner.open_queue.remove(.{ .stream = replacement });

    for (streams[1..]) |stream| stream.close();
    fx.owner.retireStreams();
    for (streams[1..]) |stream| stream.release();
    streams[0].release();
    replacement.close();
    replacement.release();
}

test "cancelling a queued exec op wakes the loop and is pruned from the queue" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();

    const op = try fx.owner.execOp("true");
    try std.testing.expectEqual(@as(usize, 1), fx.owner.open_queue.len());
    op.release();
    // The release wakes the loop, so the pipe now holds a wakeup for it to act on.
    var sink: [8]u8 = undefined;
    try std.testing.expect(c.read(fx.fds[0], &sink, sink.len) > 0);

    fx.owner.pruneDeadOpens();
    try std.testing.expectEqual(@as(usize, 0), fx.owner.open_queue.len());
    try std.testing.expect(op.finished);

    // The loop would reap this op; the harness does the same bookkeeping without running it.
    for (fx.owner.ops.items, 0..) |queued, index| {
        if (queued == op) {
            _ = fx.owner.ops.orderedRemove(index);
            break;
        }
    }
    op.destroy();
}

test "release before retire defers the free; retire then frees exactly once" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();
    const stream = try fx.openStream("/tmp/herdr-harness-f.sock");
    try std.testing.expect(writeAll(stream, "unsent"));
    stream.close();
    stream.release();
    // Released but not yet retired: the memory must still be live, so a flush on it is safe.
    try std.testing.expectEqual(@as(c_int, -1), stream.flush(0));
    // The loop's retire is what frees it. The testing allocator fails the test if it never runs.
    fx.owner.retireStreams();
}

/// Shared state for the threaded runs. The writer owns `payload` order; `flushed` counts bytes whose
/// flush returned, so the main thread can check them against what the pump actually sent.
const Threaded = struct {
    stream: *StreamChannel,
    payload: []const u8,
    flushed: std.atomic.Value(usize) = .init(0),
    writer_done: std.atomic.Value(bool) = .init(false),
    stop_pump: std.atomic.Value(bool) = .init(false),
    seed: u64,
};

fn writerMain(shared: *Threaded) void {
    var prng = std.Random.DefaultPrng.init(shared.seed);
    const random = prng.random();
    var offset: usize = 0;
    while (offset < shared.payload.len) {
        const chunk_len = @min(shared.payload.len - offset, random.uintLessThan(usize, 300) + 1);
        const chunk = shared.payload[offset..][0..chunk_len];
        if (!writeAll(shared.stream, chunk)) break;
        var spins: usize = 0;
        while (true) : (spins += 1) {
            if (spins > 1_000_000) break;
            const status = shared.stream.flush(1);
            if (status == 1) {
                offset += chunk_len;
                shared.flushed.store(offset, .release);
                break;
            }
            if (status < 0) break;
        }
        if (offset < shared.payload.len and shared.stream.flush(0) < 0) break;
    }
    shared.writer_done.store(true, .release);
}

fn pumpMain(shared: *Threaded, io: *ScriptedIo) void {
    while (!shared.stop_pump.load(.acquire)) {
        _ = pumpOnce(shared.stream, io);
    }
}

fn readerMain(stream: *StreamChannel, expected: []const u8, failure: *std.atomic.Value(bool)) void {
    var buffer: [257]u8 = undefined;
    var offset: usize = 0;
    var spins: usize = 0;
    while (spins < 1_000_000) : (spins += 1) {
        const result = stream.read(&buffer, 1);
        if (result > 0) {
            const count: usize = @intCast(result);
            if (offset + count > expected.len or !std.mem.eql(u8, buffer[0..count], expected[offset..][0..count])) {
                failure.store(true, .release);
                return;
            }
            offset += count;
        } else if (result == -1) {
            break;
        } else if (result < 0) {
            failure.store(true, .release);
            return;
        }
    }
    if (offset != expected.len) failure.store(true, .release);
}

test "threaded: writer, pump, and reader keep order and never report unsent bytes as flushed" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();

    var seed: u64 = 1;
    while (seed <= 24) : (seed += 1) {
        const stream = try fx.openStream("/tmp/herdr-harness-threads.sock");
        const outgoing = try patternedAlloc(std.testing.allocator, 6000 + seed * 97, @truncate(seed));
        defer std.testing.allocator.free(outgoing);
        const incoming = try patternedAlloc(std.testing.allocator, 9000 + seed * 53, @truncate(seed *% 7));
        defer std.testing.allocator.free(incoming);

        var io = ScriptedIo{
            .write_limit = 1 + @as(usize, @intCast(seed % 13)),
            .write_eagain_every = 2 + @as(usize, @intCast(seed % 5)),
            .inbound = incoming,
            .read_limit = 1 + @as(usize, @intCast((seed * 3) % 29)),
            .read_eagain_every = 3 + @as(usize, @intCast(seed % 4)),
            .peer_closed = true,
        };
        var shared = Threaded{ .stream = stream, .payload = outgoing, .seed = seed };
        var reader_failed = std.atomic.Value(bool).init(false);

        const pump = try std.Thread.spawn(.{}, pumpMain, .{ &shared, &io });
        const writer = try std.Thread.spawn(.{}, writerMain, .{&shared});
        const reader = try std.Thread.spawn(.{}, readerMain, .{ stream, incoming, &reader_failed });
        writer.join();
        reader.join();
        shared.stop_pump.store(true, .release);
        pump.join();

        // Every byte the writer saw flushed must be on the wire, in order, and nothing else.
        const flushed = shared.flushed.load(.acquire);
        try std.testing.expect(io.sink.items.len >= flushed);
        try std.testing.expectEqualSlices(u8, outgoing[0..io.sink.items.len], io.sink.items);
        try std.testing.expect(!reader_failed.load(.acquire));
        try std.testing.expectEqual(@as(usize, incoming.len), io.inbound_pos);

        io.deinit();
        finishStream(&fx, stream);
    }
}
test "threaded: a close during a flush ends both sides without a deadlock or a torn payload" {
    var fx: Fixture = .{};
    try fx.setup();
    defer fx.teardown();

    var seed: u64 = 1;
    while (seed <= 24) : (seed += 1) {
        const stream = try fx.openStream("/tmp/herdr-harness-close.sock");
        const outgoing = try patternedAlloc(std.testing.allocator, 20_000, @truncate(seed));
        defer std.testing.allocator.free(outgoing);

        var io = ScriptedIo{ .write_limit = 3 + @as(usize, @intCast(seed % 11)), .write_eagain_every = 4 };
        var shared = Threaded{ .stream = stream, .payload = outgoing, .seed = seed };

        const pump = try std.Thread.spawn(.{}, pumpMain, .{ &shared, &io });
        const writer = try std.Thread.spawn(.{}, writerMain, .{&shared});

        // Close after the writer has flushed a seed-dependent amount, mid-stream.
        const trigger = 1000 + @as(usize, @intCast(seed * 311));
        var waited: usize = 0;
        while (shared.flushed.load(.acquire) < trigger and !shared.writer_done.load(.acquire) and waited < 1_000_000) : (waited += 1) {
            std.Thread.yield() catch {};
        }
        stream.close();
        writer.join();
        shared.stop_pump.store(true, .release);
        pump.join();

        // Flushed bytes were drained before close, so they are a prefix of what the peer received.
        const flushed = shared.flushed.load(.acquire);
        try std.testing.expect(io.sink.items.len >= flushed);
        try std.testing.expectEqualSlices(u8, outgoing[0..io.sink.items.len], io.sink.items);
        try std.testing.expect(io.sink.items.len <= outgoing.len);

        io.deinit();
        finishStream(&fx, stream);
    }
}
