const std = @import("std");

/// FIFO of channel opens waiting for the single libssh2 open slot. Pure: no libssh2, no locks.
/// The owner serializes access under its registry lock.
pub fn OpenQueue(comptime T: type) type {
    return struct {
        entries: std.ArrayList(T) = .empty,

        const Self = @This();

        pub fn len(self: *const Self) usize {
            return self.entries.items.len;
        }

        /// Appends `item` unless `limit` jobs are already waiting. Refusal is a retryable
        /// condition for the caller, not an error in the queue.
        pub fn admit(self: *Self, allocator: std.mem.Allocator, item: T, limit: usize) error{ TooManyPending, OutOfMemory }!void {
            if (self.entries.items.len >= limit) return error.TooManyPending;
            try self.entries.append(allocator, item);
        }

        pub fn popFront(self: *Self) ?T {
            if (self.entries.items.len == 0) return null;
            return self.entries.orderedRemove(0);
        }

        /// Removes the first entry equal to `item`. Returns whether one was removed.
        pub fn remove(self: *Self, item: T) bool {
            for (self.entries.items, 0..) |queued, index| {
                if (std.meta.eql(queued, item)) {
                    _ = self.entries.orderedRemove(index);
                    return true;
                }
            }
            return false;
        }

        /// Removes entries for which `isDead` is true, in order, and writes them to `out`.
        /// At most `out.len` are removed per call; the rest stay queued for the next pass, so
        /// one pass never does unbounded work. Live entries keep their relative order.
        pub fn pruneDead(self: *Self, out: []T, comptime isDead: fn (T) bool) usize {
            var written: usize = 0;
            var index: usize = 0;
            while (index < self.entries.items.len and written < out.len) {
                const entry = self.entries.items[index];
                if (isDead(entry)) {
                    out[written] = entry;
                    written += 1;
                    _ = self.entries.orderedRemove(index);
                } else {
                    index += 1;
                }
            }
            return written;
        }

        pub fn clear(self: *Self) void {
            self.entries.clearRetainingCapacity();
        }

        pub fn deinit(self: *Self, allocator: std.mem.Allocator) void {
            self.entries.deinit(allocator);
        }
    };
}

/// True once at least `limit_ns` has elapsed since `started_ns` on a monotonic clock.
/// Saturating, so a clock that reads earlier than `started_ns` never reports expiry.
pub fn deadlineExpired(started_ns: u64, now_ns: u64, limit_ns: u64) bool {
    return now_ns -| started_ns >= limit_ns;
}

const Job = union(enum) {
    stream: u32,
    op: u32,
};

fn neverDead(_: Job) bool {
    return false;
}

fn isOpDead(job: Job) bool {
    return switch (job) {
        .op => |id| id % 3 == 0,
        .stream => false,
    };
}

test "admit refuses at the limit and keeps FIFO order" {
    var queue: OpenQueue(u32) = .{};
    defer queue.deinit(std.testing.allocator);

    try queue.admit(std.testing.allocator, 1, 2);
    try queue.admit(std.testing.allocator, 2, 2);
    try std.testing.expectError(error.TooManyPending, queue.admit(std.testing.allocator, 3, 2));
    try std.testing.expectEqual(@as(usize, 2), queue.len());
    try std.testing.expectEqual(@as(?u32, 1), queue.popFront());
    try std.testing.expectEqual(@as(?u32, 2), queue.popFront());
    try std.testing.expectEqual(@as(?u32, null), queue.popFront());
}

test "remove drops only the first match and reports whether it found one" {
    var queue: OpenQueue(u32) = .{};
    defer queue.deinit(std.testing.allocator);

    try queue.admit(std.testing.allocator, 7, 8);
    try queue.admit(std.testing.allocator, 9, 8);
    try std.testing.expect(queue.remove(9));
    try std.testing.expect(!queue.remove(9));
    try std.testing.expectEqual(@as(?u32, 7), queue.popFront());
}

test "pruneDead removes dead entries from the middle and keeps live order" {
    var queue: OpenQueue(Job) = .{};
    defer queue.deinit(std.testing.allocator);
    // Ops 3 and 6 are dead; stream 4 is live and must not be treated as dead.
    for ([_]Job{ .{ .op = 1 }, .{ .op = 3 }, .{ .stream = 4 }, .{ .op = 6 }, .{ .op = 2 } }) |job| {
        try queue.admit(std.testing.allocator, job, 16);
    }

    var out: [8]Job = undefined;
    const pruned = queue.pruneDead(&out, isOpDead);

    try std.testing.expectEqual(@as(usize, 2), pruned);
    try std.testing.expectEqual(Job{ .op = 3 }, out[0]);
    try std.testing.expectEqual(Job{ .op = 6 }, out[1]);
    try std.testing.expectEqual(@as(usize, 3), queue.len());
    try std.testing.expectEqual(Job{ .op = 1 }, queue.popFront().?);
    try std.testing.expectEqual(Job{ .stream = 4 }, queue.popFront().?);
    try std.testing.expectEqual(Job{ .op = 2 }, queue.popFront().?);
}

test "pruneDead is bounded by the output buffer and leaves the rest for the next pass" {
    var queue: OpenQueue(Job) = .{};
    defer queue.deinit(std.testing.allocator);
    for ([_]u32{ 3, 6, 9, 12 }) |id| try queue.admit(std.testing.allocator, .{ .op = id }, 16);

    var out: [2]Job = undefined;
    try std.testing.expectEqual(@as(usize, 2), queue.pruneDead(&out, isOpDead));
    try std.testing.expectEqual(@as(usize, 2), queue.len());
    try std.testing.expectEqual(@as(usize, 2), queue.pruneDead(&out, isOpDead));
    try std.testing.expectEqual(@as(usize, 0), queue.len());
}

test "pruneDead with nothing dead removes nothing" {
    var queue: OpenQueue(Job) = .{};
    defer queue.deinit(std.testing.allocator);
    try queue.admit(std.testing.allocator, .{ .stream = 1 }, 4);

    var out: [4]Job = undefined;
    try std.testing.expectEqual(@as(usize, 0), queue.pruneDead(&out, neverDead));
    try std.testing.expectEqual(@as(usize, 1), queue.len());
}

test "deadline is exact at the limit, saturating, and never fires early" {
    const limit: u64 = 30_000_000_000;
    try std.testing.expect(!deadlineExpired(100, 100 + limit - 1, limit));
    try std.testing.expect(deadlineExpired(100, 100 + limit, limit));
    // A clock that reads earlier than the start must not overflow into expiry.
    try std.testing.expect(!deadlineExpired(500, 10, limit));
    // Near the top of the range the sum would overflow; the check must still hold.
    try std.testing.expect(deadlineExpired(std.math.maxInt(u64) - 5, std.math.maxInt(u64), 3));
}

test "randomized operations match a reference model" {
    var queue: OpenQueue(u32) = .{};
    defer queue.deinit(std.testing.allocator);
    var model: std.ArrayList(u32) = .empty;
    defer model.deinit(std.testing.allocator);

    var prng = std.Random.DefaultPrng.init(0x5eed_0bee);
    const random = prng.random();
    var next_id: u32 = 1;
    for (0..20_000) |_| {
        switch (random.uintLessThan(u8, 4)) {
            0 => {
                const limit = random.uintLessThan(usize, 8) + 1;
                const admitted = queue.admit(std.testing.allocator, next_id, limit);
                if (model.items.len >= limit) {
                    try std.testing.expectError(error.TooManyPending, admitted);
                } else {
                    try admitted;
                    try model.append(std.testing.allocator, next_id);
                }
                next_id += 1;
            },
            1 => {
                const expected: ?u32 = if (model.items.len == 0) null else model.orderedRemove(0);
                try std.testing.expectEqual(expected, queue.popFront());
            },
            2 => {
                const victim = if (model.items.len == 0) 0 else model.items[random.uintLessThan(usize, model.items.len)];
                const found = queue.remove(victim);
                var index: usize = 0;
                while (index < model.items.len and model.items[index] != victim) index += 1;
                if (index < model.items.len) {
                    _ = model.orderedRemove(index);
                    try std.testing.expect(found);
                } else {
                    try std.testing.expect(!found);
                }
            },
            else => {
                var out: [4]u32 = undefined;
                const dead = struct {
                    fn check(id: u32) bool {
                        return id % 5 == 0;
                    }
                }.check;
                const pruned = queue.pruneDead(&out, dead);
                var expected_count: usize = 0;
                var index: usize = 0;
                while (index < model.items.len and expected_count < out.len) {
                    if (model.items[index] % 5 == 0) {
                        try std.testing.expectEqual(model.items[index], out[expected_count]);
                        _ = model.orderedRemove(index);
                        expected_count += 1;
                    } else {
                        index += 1;
                    }
                }
                try std.testing.expectEqual(expected_count, pruned);
            },
        }
        try std.testing.expectEqual(model.items.len, queue.len());
    }
}
