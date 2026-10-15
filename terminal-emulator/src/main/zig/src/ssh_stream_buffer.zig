const std = @import("std");

/// Fixed-capacity FIFO of bytes. Not thread-safe: the owner serializes access.
pub const Ring = struct {
    buffer: []u8,
    head: usize = 0,
    len: usize = 0,

    pub fn init(allocator: std.mem.Allocator, capacity: usize) !Ring {
        std.debug.assert(capacity > 0);
        return .{ .buffer = try allocator.alloc(u8, capacity) };
    }

    pub fn deinit(self: *Ring, allocator: std.mem.Allocator) void {
        allocator.free(self.buffer);
        self.* = undefined;
    }

    pub fn freeSpace(self: *const Ring) usize {
        return self.buffer.len - self.len;
    }

    /// Appends as much of `bytes` as fits and returns the number appended.
    pub fn push(self: *Ring, bytes: []const u8) usize {
        const count = @min(bytes.len, self.freeSpace());
        var written: usize = 0;
        while (written < count) {
            const tail = (self.head + self.len) % self.buffer.len;
            const chunk = @min(count - written, self.buffer.len - tail);
            @memcpy(self.buffer[tail..][0..chunk], bytes[written..][0..chunk]);
            self.len += chunk;
            written += chunk;
        }
        return count;
    }

    /// Contiguous unread bytes from the head, oldest first. Stays valid until `consume`
    /// drops them, because pushes only write into free space.
    pub fn peek(self: *const Ring) []const u8 {
        const end = @min(self.head + self.len, self.buffer.len);
        return self.buffer[self.head..end];
    }

    /// Drops the first `count` bytes. `count` must not exceed `len`.
    pub fn consume(self: *Ring, count: usize) void {
        std.debug.assert(count <= self.len);
        self.head = (self.head + count) % self.buffer.len;
        self.len -= count;
    }

    /// Copies up to `dst.len` bytes out, oldest first, and returns the number copied.
    pub fn pop(self: *Ring, dst: []u8) usize {
        const count = @min(dst.len, self.len);
        var copied: usize = 0;
        while (copied < count) {
            const source = self.peek();
            const chunk = @min(count - copied, source.len);
            @memcpy(dst[copied..][0..chunk], source[0..chunk]);
            self.consume(chunk);
            copied += chunk;
        }
        return count;
    }
};

test "push and pop preserve order across the wrap point" {
    var ring = try Ring.init(std.testing.allocator, 8);
    defer ring.deinit(std.testing.allocator);

    try std.testing.expectEqual(@as(usize, 6), ring.push("abcdef"));
    var first: [4]u8 = undefined;
    try std.testing.expectEqual(@as(usize, 4), ring.pop(&first));
    try std.testing.expectEqualStrings("abcd", &first);

    // "gh" lands at the start of the buffer, so the unread bytes "efgh" wrap.
    try std.testing.expectEqual(@as(usize, 4), ring.push("ghij"));
    var rest: [8]u8 = undefined;
    try std.testing.expectEqual(@as(usize, 6), ring.pop(&rest));
    try std.testing.expectEqualStrings("efghij", rest[0..6]);
}

test "push accepts only what fits and reports the count" {
    var ring = try Ring.init(std.testing.allocator, 4);
    defer ring.deinit(std.testing.allocator);

    try std.testing.expectEqual(@as(usize, 4), ring.push("123456"));
    try std.testing.expectEqual(@as(usize, 0), ring.freeSpace());
    try std.testing.expectEqual(@as(usize, 0), ring.push("x"));
}

test "peek returns only the contiguous run up to the wrap point" {
    var ring = try Ring.init(std.testing.allocator, 4);
    defer ring.deinit(std.testing.allocator);

    _ = ring.push("abc");
    ring.consume(2);
    // Unread "c" sits at index 2, so "de" wraps: "d" at index 3 and "e" at index 0.
    _ = ring.push("de");

    try std.testing.expectEqualStrings("cd", ring.peek());
    ring.consume(2);
    try std.testing.expectEqualStrings("e", ring.peek());
    ring.consume(1);
    try std.testing.expectEqualStrings("", ring.peek());
}

test "every byte value survives repeated partial drains" {
    var ring = try Ring.init(std.testing.allocator, 100);
    defer ring.deinit(std.testing.allocator);

    var input: [256]u8 = undefined;
    for (&input, 0..) |*byte, index| byte.* = @intCast(index);

    var output: [256]u8 = undefined;
    var written: usize = 0;
    var read: usize = 0;
    while (read < output.len) {
        written += ring.push(input[written..]);
        read += ring.pop(output[read..][0..@min(37, output.len - read)]);
    }
    try std.testing.expectEqualSlices(u8, &input, &output);
}
