const std = @import("std");
const ssh_output_buffer = @import("ssh_output_buffer.zig");

pub const c = @cImport({
    // NDK 29 bionic headers use clang nullability keywords in array
    // parameter position and an overloadable ioctl; zig 0.16's translate-c
    // (clang 21) rejects both. Neutralize for translation.
    @cDefine("_Nullable", "");
    @cDefine("_Nonnull", "");
    @cDefine("_Null_unspecified", "");
    @cDefine("BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD", "");
    @cInclude("jni.h");
    @cInclude("libssh2.h");
    @cInclude("libssh2_sftp.h");
    @cInclude("unistd.h");
    @cInclude("poll.h");
    @cInclude("fcntl.h");
    @cInclude("netinet/in.h");
    @cInclude("netinet/tcp.h");
    @cInclude("pthread.h");
    @cInclude("semaphore.h");
    @cInclude("sys/socket.h");
});

pub const SshNativeSession = struct {
    // zig 0.16 removed std.Thread.Mutex in favor of std.Io.Mutex, which
    // requires an Io handle for every lock/unlock. The session threads don't
    // have one, so wrap the pthread mutex we already link instead.
    const Mutex = struct {
        inner: c.pthread_mutex_t = std.mem.zeroes(c.pthread_mutex_t),

        pub fn lock(self: *Mutex) void {
            _ = c.pthread_mutex_lock(&self.inner);
        }

        pub fn unlock(self: *Mutex) void {
            _ = c.pthread_mutex_unlock(&self.inner);
        }
    };

    // Same rationale as Mutex: std.Thread.Semaphore moved to std.Io.Semaphore
    // (needs an Io) in zig 0.16, so wrap a pthread semaphore instead.
    const Semaphore = struct {
        inner: c.sem_t = std.mem.zeroes(c.sem_t),
        initialized: bool = false,

        pub fn init(self: *Semaphore) void {
            _ = c.sem_init(&self.inner, 0, 0);
            self.initialized = true;
        }

        pub fn deinit(self: *Semaphore) void {
            if (self.initialized) _ = c.sem_destroy(&self.inner);
            self.initialized = false;
        }

        pub fn wait(self: *Semaphore) void {
            while (true) {
                if (c.sem_wait(&self.inner) == 0) return;
                if (std.c.errno(-1) != .INTR) return;
            }
        }

        pub fn post(self: *Semaphore) void {
            _ = c.sem_post(&self.inner);
        }
    };

    // Use page_allocator for ArrayList buffers to bypass Android Scudo entirely.
    // Scudo's strict chunk-header validation is incompatible with the reallocation
    // patterns from Zig ArrayLists combined with libssh2's own malloc usage.
    const queue_allocator = std.heap.page_allocator;
    const socket_poll_timeout_ms: c_int = 1000;
    const keepalive_interval_seconds: c_uint = 10;
    // Deliver shell output to the JVM in ~64 KiB batches instead of one JNI
    // callback + Handler message per libssh2 read chunk (16 KiB).
    const output_flush_threshold_bytes: usize = 64 * 1024;

    /// One interactive PTY channel on the session. The JVM owns its lifetime:
    /// the struct stays valid until `closeShell` or session `deinit`, even after
    /// the remote shell exits, so a stale handle never dangles.
    pub const ShellChannel = struct {
        owner: *SshNativeSession,
        // Only touched on the loop thread; null once the remote side closed.
        channel: ?*c.LIBSSH2_CHANNEL,
        open: std.atomic.Value(bool),
        output_lock: Mutex = .{},
        output_queue: std.ArrayList(u8) = .empty,
        callback_lock: Mutex = .{},
        java_vm: ?*c.JavaVM = null,
        output_callback: ?c.jobject = null,
        output_callback_method: ?c.jmethodID = null,
        output_closed_method: ?c.jmethodID = null,

        pub fn isRunning(self: *ShellChannel) bool {
            return self.open.load(.acquire) and self.owner.running.load(.acquire);
        }

        pub fn writeKeystrokes(self: *ShellChannel, data: []const u8) void {
            const owner = self.owner;
            const copy = owner.allocator.dupe(u8, data) catch return;
            const command = owner.allocator.create(Command) catch {
                owner.allocator.free(copy);
                return;
            };
            command.* = .{ .kind = .write, .shell = self, .data = copy, .owned = true };
            if (!owner.enqueue(command)) {
                owner.allocator.free(copy);
                owner.allocator.destroy(command);
            }
        }

        pub fn resize(self: *ShellChannel, cols: c_int, rows: c_int) void {
            const owner = self.owner;
            const command = owner.allocator.create(Command) catch return;
            command.* = .{ .kind = .resize, .shell = self, .cols = cols, .rows = rows, .owned = true };
            if (!owner.enqueue(command)) owner.allocator.destroy(command);
        }

        pub fn setOutputCallback(self: *ShellChannel, env: *c.JNIEnv, callback: c.jobject) void {
            self.callback_lock.lock();
            defer self.callback_lock.unlock();
            self.clearOutputCallbackLocked(env);
            if (callback == null) return;

            var java_vm: ?*c.JavaVM = null;
            if (env.*.*.GetJavaVM.?(env, &java_vm) != 0) return;
            const global_callback = env.*.*.NewGlobalRef.?(env, callback) orelse return;
            const callback_class = env.*.*.GetObjectClass.?(env, callback) orelse {
                env.*.*.DeleteGlobalRef.?(env, global_callback);
                return;
            };
            const callback_method = env.*.*.GetMethodID.?(env, callback_class, "onNativeSshOutput", "([B)V") orelse {
                env.*.*.DeleteLocalRef.?(env, callback_class);
                env.*.*.DeleteGlobalRef.?(env, global_callback);
                return;
            };
            const closed_method = env.*.*.GetMethodID.?(env, callback_class, "onNativeSshClosed", "()V") orelse {
                env.*.*.DeleteLocalRef.?(env, callback_class);
                env.*.*.DeleteGlobalRef.?(env, global_callback);
                return;
            };
            env.*.*.DeleteLocalRef.?(env, callback_class);
            self.java_vm = java_vm;
            self.output_callback = global_callback;
            self.output_callback_method = callback_method;
            self.output_closed_method = closed_method;
            self.deliverOutputLocked(env);
        }

        fn hasCallback(self: *ShellChannel) bool {
            self.callback_lock.lock();
            defer self.callback_lock.unlock();
            return self.output_callback != null;
        }

        fn deliverOutput(self: *ShellChannel, env: ?*c.JNIEnv) void {
            self.callback_lock.lock();
            defer self.callback_lock.unlock();
            self.deliverOutputLocked(env);
        }

        fn deliverOutputLocked(self: *ShellChannel, current_env: ?*c.JNIEnv) void {
            const callback = self.output_callback orelse return;
            const method = self.output_callback_method orelse return;
            var attachment = JniAttachment.acquire(self.java_vm, current_env) orelse return;
            defer attachment.release();
            const env = attachment.env;

            self.output_lock.lock();
            defer self.output_lock.unlock();
            if (self.output_queue.items.len == 0) return;
            const bytes = env.*.*.NewByteArray.?(env, @intCast(self.output_queue.items.len)) orelse return;
            defer env.*.*.DeleteLocalRef.?(env, bytes);
            env.*.*.SetByteArrayRegion.?(env, bytes, 0, @intCast(self.output_queue.items.len), @ptrCast(self.output_queue.items.ptr));
            env.*.*.CallVoidMethod.?(env, callback, method, bytes);
            self.output_queue.clearRetainingCapacity();
        }

        fn deliverClosed(self: *ShellChannel, current_env: ?*c.JNIEnv) void {
            self.callback_lock.lock();
            defer self.callback_lock.unlock();
            const callback = self.output_callback orelse return;
            const method = self.output_closed_method orelse return;
            var attachment = JniAttachment.acquire(self.java_vm, current_env) orelse return;
            defer attachment.release();
            attachment.env.*.*.CallVoidMethod.?(attachment.env, callback, method);
        }

        fn clearOutputCallbackLocked(self: *ShellChannel, env: ?*c.JNIEnv) void {
            if (self.output_callback) |callback| {
                if (env) |jni| jni.*.*.DeleteGlobalRef.?(jni, callback);
            }
            self.java_vm = null;
            self.output_callback = null;
            self.output_callback_method = null;
            self.output_closed_method = null;
        }

        fn destroy(self: *ShellChannel, env: ?*c.JNIEnv) void {
            self.callback_lock.lock();
            self.clearOutputCallbackLocked(env);
            self.callback_lock.unlock();
            self.output_queue.deinit(queue_allocator);
            self.owner.allocator.destroy(self);
        }
    };

    const JniAttachment = struct {
        java_vm: *c.JavaVM,
        env: *c.JNIEnv,
        attached: bool,

        fn acquire(java_vm_opt: ?*c.JavaVM, current_env: ?*c.JNIEnv) ?JniAttachment {
            const java_vm = java_vm_opt orelse return null;
            if (current_env) |env| return .{ .java_vm = java_vm, .env = env, .attached = false };
            var env: ?*c.JNIEnv = null;
            if (java_vm.*.*.AttachCurrentThreadAsDaemon.?(java_vm, @ptrCast(&env), null) != 0) return null;
            return .{ .java_vm = java_vm, .env = env orelse return null, .attached = true };
        }

        fn release(self: *JniAttachment) void {
            if (self.attached) _ = self.java_vm.*.*.DetachCurrentThread.?(self.java_vm);
        }
    };

    allocator: std.mem.Allocator,
    session: *c.LIBSSH2_SESSION,
    socket_fd: c_int,
    wake_pipe: [2]c_int,
    command_lock: Mutex,
    command_queue: std.ArrayList(*Command),
    // Guards `shells`. The loop thread iterates it; open/close/deinit mutate it.
    shells_lock: Mutex = .{},
    shells: std.ArrayList(*ShellChannel) = .empty,
    // Loop-thread only: channels whose close is still waiting on the socket.
    closing_channels: std.ArrayList(*c.LIBSSH2_CHANNEL) = .empty,
    // Set once at init; the loop thread attaches with it.
    java_vm: ?*c.JavaVM,
    thread: ?std.Thread,
    running: std.atomic.Value(bool),
    // SFTP read-ahead: one 256 KiB staging buffer holds the chunk fetched
    // while the JVM drains the previous one, hiding the network RTT behind
    // local disk I/O. libssh2 stays on the single loop thread; at most one
    // read request is ever in flight.
    sftp_staging: ?[]u8 = null,
    sftp_staging_handle: ?*anyopaque = null,
    sftp_staging_len: usize = 0,
    sftp_staging_read_handle: ?*anyopaque = null,
    sftp_staging_armed: bool = false,
    sftp_staging_eof: bool = false,
    sftp_staging_error: bool = false,
    const CommandKind = enum {
        write,
        resize,
        callback,
    };

    const Command = struct {
        kind: CommandKind,
        shell: ?*ShellChannel = null,
        data: ?[]u8 = null,
        cols: c_int = 0,
        rows: c_int = 0,
        callback: ?*const fn (*SshNativeSession, *Command) void = null,
        context: ?*anyopaque = null,
        completion: ?*Semaphore = null,
        owned: bool = false,
    };

    const ExecContext = struct {
        command: []const u8,
        output: *std.ArrayList(u8),
        error_output: *std.ArrayList(u8),
        exit_status: c_int = -1,
        success: bool = false,
    };

    const OpenShellContext = struct {
        term_type: []const u8,
        cols: c_int,
        rows: c_int,
        command: ?[]const u8,
        result: ?*ShellChannel = null,
    };

    const CloseShellContext = struct {
        shell: *ShellChannel,
        done: bool = false,
    };

    const SftpInitContext = struct {
        result: ?*anyopaque = null,
    };

    const SftpCloseContext = struct {
        handle: *anyopaque,
    };

    const SftpListContext = struct {
        handle: *anyopaque,
        path: []const u8,
        output: *std.ArrayList(u8),
        success: bool = false,
    };

    const SftpMkdirContext = struct {
        handle: *anyopaque,
        path: []const u8,
        permissions: c_int,
        success: bool = false,
    };

    const SftpDeleteContext = struct {
        handle: *anyopaque,
        path: []const u8,
        success: bool = false,
    };

    const SftpRenameContext = struct {
        handle: *anyopaque,
        old_path: []const u8,
        new_path: []const u8,
        success: bool = false,
    };

    const SftpOpenContext = struct {
        sftp: *anyopaque,
        path: []const u8,
        flags: c_int,
        mode: c_int,
        result: ?*anyopaque = null,
    };

    const SftpFileCloseContext = struct {
        handle: *anyopaque,
    };

    const SftpReadContext = struct {
        handle: *anyopaque,
        buffer: []u8,
        result: isize = -1,
    };

    const SftpWriteContext = struct {
        handle: *anyopaque,
        buffer: []const u8,
        result: isize = -1,
    };

    fn setSocketOption(socket_fd: c_int, level: c_int, option: c_int, value: c_int) void {
        var option_value = value;
        _ = c.setsockopt(
            socket_fd,
            level,
            option,
            &option_value,
            @intCast(@sizeOf(c_int)),
        );
    }

    fn configureSocketLiveness(socket_fd: c_int) void {
        // SSH is a packet protocol with its own framing: Nagle's algorithm only
        // adds delayed-ACK latency to interactive keystroke round trips, so
        // disable it like OpenSSH does. Set it here on the raw fd so it applies
        // deterministically, independent of how the fd was created.
        if (@hasDecl(c, "TCP_NODELAY")) {
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_NODELAY, 1);
        }

        // Size socket buffers for high-bandwidth-delay-product links so SFTP
        // and bulk shell output aren't limited by the small Android defaults.
        setSocketOption(socket_fd, c.SOL_SOCKET, c.SO_RCVBUF, 1 << 20);
        setSocketOption(socket_fd, c.SOL_SOCKET, c.SO_SNDBUF, 1 << 20);

        setSocketOption(socket_fd, c.SOL_SOCKET, c.SO_KEEPALIVE, 1);

        if (@hasDecl(c, "TCP_KEEPIDLE")) {
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_KEEPIDLE, 15);
        }
        if (@hasDecl(c, "TCP_KEEPINTVL")) {
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_KEEPINTVL, 5);
        }
        if (@hasDecl(c, "TCP_KEEPCNT")) {
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_KEEPCNT, 3);
        }
        if (@hasDecl(c, "TCP_USER_TIMEOUT")) {
            // Bound unacknowledged SSH keepalives on platforms that expose it.
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_USER_TIMEOUT, 20_000);
        }
    }

    pub fn init(
        allocator: std.mem.Allocator,
        socket_fd: c_int,
        username: []const u8,
        password_or_key: []const u8,
        is_password: bool,
        java_vm: ?*c.JavaVM,
    ) !*SshNativeSession {
        _ = c.libssh2_init(0);

        const session = c.libssh2_session_init_ex(null, null, null, null) orelse return error.SessionInitFailed;
        errdefer _ = c.libssh2_session_free(session);

        _ = c.libssh2_session_set_blocking(session, 0);
        _ = c.libssh2_session_flag(session, c.LIBSSH2_FLAG_COMPRESS, 1);

        const flags = c.fcntl(socket_fd, c.F_GETFL, @as(c_int, 0));
        _ = c.fcntl(socket_fd, c.F_SETFL, flags | c.O_NONBLOCK);
        configureSocketLiveness(socket_fd);

        while (true) {
            const rc = c.libssh2_session_handshake(session, socket_fd);
            if (rc == 0) break;
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                try waitSocket(socket_fd, session);
                continue;
            }
            return error.HandshakeFailed;
        }

        if (is_password) {
            const password_c = try allocator.dupeZ(u8, password_or_key);
            defer allocator.free(password_c);
            while (true) {
                const rc = c.libssh2_userauth_password_ex(
                    session,
                    username.ptr,
                    @intCast(username.len),
                    password_c.ptr,
                    @intCast(password_or_key.len),
                    null,
                );
                if (rc == 0) break;
                if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    try waitSocket(socket_fd, session);
                    continue;
                }
                return error.AuthFailed;
            }
        } else {
            while (true) {
                const rc = c.libssh2_userauth_publickey_frommemory(
                    session,
                    username.ptr,
                    username.len,
                    null,
                    0,
                    password_or_key.ptr,
                    password_or_key.len,
                    null,
                );
                if (rc == 0) break;
                if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    try waitSocket(socket_fd, session);
                    continue;
                }
                return error.AuthFailed;
            }
        }

        // Keep idle sessions from surviving a dead Wi-Fi link indefinitely.
        c.libssh2_keepalive_config(session, 1, keepalive_interval_seconds);

        var wake_p: [2]c_int = undefined;
        if (c.pipe(&wake_p) != 0) return error.PipeFailed;
        errdefer {
            _ = c.close(wake_p[0]);
            _ = c.close(wake_p[1]);
        }

        _ = c.fcntl(wake_p[0], c.F_SETFL, c.O_NONBLOCK);

        const self = try allocator.create(SshNativeSession);
        self.* = .{
            .allocator = allocator,
            .session = session,
            .socket_fd = socket_fd,
            .wake_pipe = wake_p,
            .command_lock = .{},
            .command_queue = .empty,
            .java_vm = java_vm,
            .thread = null,
            .running = std.atomic.Value(bool).init(true),
        };
        return self;
    }

    pub fn start(self: *SshNativeSession) !void {
        self.thread = try std.Thread.spawn(.{ .allocator = self.allocator }, runLoop, .{self});
    }

    pub fn deinit(self: *SshNativeSession, env: ?*c.JNIEnv) void {
        self.running.store(false, .release);
        self.signalCommandWake();
        if (self.thread) |thread| {
            const thread_id: c.pthread_t = @intCast(@intFromPtr(thread.getHandle()));
            if (c.pthread_equal(thread_id, c.pthread_self()) == 0) {
                thread.join();
            }
        } else {
            _ = c.libssh2_session_disconnect_ex(self.session, c.SSH_DISCONNECT_BY_APPLICATION, "Shutdown", "");
            _ = c.libssh2_session_free(self.session);
        }

        _ = c.close(self.wake_pipe[0]);
        _ = c.close(self.wake_pipe[1]);
        // The loop thread has exited, so nothing else references the shells.
        for (self.shells.items) |shell| shell.destroy(env);
        self.shells.deinit(queue_allocator);
        self.closing_channels.deinit(queue_allocator);
        self.command_queue.deinit(queue_allocator);
        if (self.sftp_staging) |staging| queue_allocator.free(staging);

        const allocator = self.allocator;
        allocator.destroy(self);
    }

    /// Opens a PTY channel running `command`, or the login shell when null.
    pub fn openShell(
        self: *SshNativeSession,
        term_type: []const u8,
        cols: c_int,
        rows: c_int,
        command: ?[]const u8,
    ) ?*ShellChannel {
        var context = OpenShellContext{ .term_type = term_type, .cols = cols, .rows = rows, .command = command };
        if (!self.dispatchSync(openShellCallback, &context)) return null;
        return context.result;
    }

    /// Closes the channel and frees `shell`. The caller must not use the handle afterwards.
    pub fn closeShell(self: *SshNativeSession, shell: *ShellChannel, env: ?*c.JNIEnv) void {
        var context = CloseShellContext{ .shell = shell };
        if (!self.dispatchSync(closeShellCallback, &context) or !context.done) {
            // The loop already stopped (or is stopping and cancelled the command).
            // Unlink under the lock so its exit path can't touch the shell; the
            // libssh2 channel itself is released by session_free.
            self.shells_lock.lock();
            _ = self.unlinkShellLocked(shell);
            self.shells_lock.unlock();
        }
        // JNI global refs must be dropped with the caller's env, not the loop thread's.
        shell.destroy(env);
    }

    pub fn execCommand(
        self: *SshNativeSession,
        command: []const u8,
        output: *std.ArrayList(u8),
        error_output: *std.ArrayList(u8),
    ) !c_int {
        var context = ExecContext{
            .command = command,
            .output = output,
            .error_output = error_output,
        };
        if (!self.dispatchSync(execCommandCallback, &context) or !context.success) {
            return error.CommandExecFailed;
        }
        return context.exit_status;
    }

    pub fn sftpInit(self: *SshNativeSession) ?*anyopaque {
        var context = SftpInitContext{};
        if (!self.dispatchSync(sftpInitCallback, &context)) return null;
        return context.result;
    }

    pub fn sftpClose(self: *SshNativeSession, handle: *anyopaque) void {
        var context = SftpCloseContext{ .handle = handle };
        _ = self.dispatchSync(sftpCloseCallback, &context);
    }

    pub fn sftpListFiles(self: *SshNativeSession, handle: *anyopaque, path: []const u8, output: *std.ArrayList(u8)) bool {
        var context = SftpListContext{ .handle = handle, .path = path, .output = output };
        return self.dispatchSync(sftpListCallback, &context) and context.success;
    }

    pub fn sftpMkdir(self: *SshNativeSession, handle: *anyopaque, path: []const u8, permissions: c_int) bool {
        var context = SftpMkdirContext{ .handle = handle, .path = path, .permissions = permissions };
        return self.dispatchSync(sftpMkdirCallback, &context) and context.success;
    }

    pub fn sftpDelete(self: *SshNativeSession, handle: *anyopaque, path: []const u8) bool {
        var context = SftpDeleteContext{ .handle = handle, .path = path };
        return self.dispatchSync(sftpDeleteCallback, &context) and context.success;
    }

    pub fn sftpRename(self: *SshNativeSession, handle: *anyopaque, old_path: []const u8, new_path: []const u8) bool {
        var context = SftpRenameContext{ .handle = handle, .old_path = old_path, .new_path = new_path };
        return self.dispatchSync(sftpRenameCallback, &context) and context.success;
    }

    pub fn sftpFileOpen(self: *SshNativeSession, handle: *anyopaque, path: []const u8, flags: c_int, mode: c_int) ?*anyopaque {
        var context = SftpOpenContext{ .sftp = handle, .path = path, .flags = flags, .mode = mode };
        if (!self.dispatchSync(sftpOpenCallback, &context)) return null;
        return context.result;
    }

    pub fn sftpFileClose(self: *SshNativeSession, handle: *anyopaque) void {
        var context = SftpFileCloseContext{ .handle = handle };
        _ = self.dispatchSync(sftpFileCloseCallback, &context);
    }

    pub fn sftpFileRead(self: *SshNativeSession, handle: *anyopaque, buffer: []u8) isize {
        var context = SftpReadContext{ .handle = handle, .buffer = buffer };
        if (!self.dispatchSync(sftpReadCallback, &context)) return -1;
        return context.result;
    }

    pub fn sftpFileWrite(self: *SshNativeSession, handle: *anyopaque, buffer: []const u8) isize {
        var context = SftpWriteContext{ .handle = handle, .buffer = buffer };
        if (!self.dispatchSync(sftpWriteCallback, &context)) return -1;
        return context.result;
    }

    pub fn waitSocket(socket_fd: c_int, session: *c.LIBSSH2_SESSION) !void {
        var fds = [_]c.struct_pollfd{.{ .fd = socket_fd, .events = 0, .revents = 0 }};
        const directions = c.libssh2_session_block_directions(session);
        if ((directions & c.LIBSSH2_SESSION_BLOCK_INBOUND) != 0) fds[0].events |= c.POLLIN;
        if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) fds[0].events |= c.POLLOUT;

        const poll_result = c.poll(&fds, 1, socket_poll_timeout_ms);
        if (poll_result < 0) return error.SocketPollFailed;
        if ((fds[0].revents & (c.POLLERR | c.POLLHUP | c.POLLNVAL)) != 0) {
            return error.SocketClosed;
        }

        var seconds_to_next: c_int = 0;
        if (c.libssh2_keepalive_send(session, &seconds_to_next) != 0) {
            return error.SocketClosed;
        }
    }

    fn runLoop(self: *SshNativeSession) void {
        var jni_env: ?*c.JNIEnv = null;
        var attached = false;

        if (self.java_vm) |vm| {
            if (vm.*.*.AttachCurrentThreadAsDaemon.?(vm, @ptrCast(&jni_env), null) == 0) {
                attached = true;
            }
        }
        defer {
            if (attached) {
                if (self.java_vm) |vm| {
                    _ = vm.*.*.DetachCurrentThread.?(vm);
                }
            }
        }

        var fds = [_]c.struct_pollfd{
            .{ .fd = self.socket_fd, .events = c.POLLIN, .revents = 0 },
            .{ .fd = self.wake_pipe[0], .events = c.POLLIN, .revents = 0 },
        };

        while (self.running.load(.acquire)) {
            var seconds_to_next: c_int = 0;
            if (c.libssh2_keepalive_send(self.session, &seconds_to_next) != 0) {
                self.running.store(false, .release);
                break;
            }

            // libssh2 operations can consume socket packets while leaving shell data in the
            // channel's internal buffer. Drain it before poll; otherwise no fd readiness remains
            // to wake an idle shell until a later keypress or resize generates more traffic.
            self.processRead(jni_env);
            if (!self.running.load(.acquire)) break;

            fds[0].events = c.POLLIN;
            const directions = c.libssh2_session_block_directions(self.session);
            if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) fds[0].events |= c.POLLOUT;

            // An idle session only needs to wake to send keepalives: sleep up
            // to the keepalive deadline instead of a fixed 1 s tick (10x fewer
            // wakeups). Any pending write or inbound data still wakes poll
            // immediately, so latency is unaffected. Clamp the deadline to the
            // configured interval so a libssh2-side anomaly can never overflow
            // the c_int multiply or busy-loop the poll.
            var poll_timeout_ms: c_int = socket_poll_timeout_ms;
            if (seconds_to_next > 0) {
                const clamped_seconds = @min(seconds_to_next, @as(c_int, @intCast(keepalive_interval_seconds)));
                const keepalive_deadline_ms: c_int = clamped_seconds * 1000;
                if (keepalive_deadline_ms > poll_timeout_ms) poll_timeout_ms = keepalive_deadline_ms;
            }

            const poll_result = c.poll(&fds, 2, poll_timeout_ms);
            if (poll_result < 0) {
                self.running.store(false, .release);
                break;
            }
            if (poll_result == 0) continue;

            if ((fds[1].revents & c.POLLIN) != 0) {
                var dummy: [128]u8 = undefined;
                _ = c.read(self.wake_pipe[0], &dummy, dummy.len);
                self.processCommands();
            }

            const socket_events = fds[0].revents;
            if ((socket_events & (c.POLLIN | c.POLLOUT | c.POLLERR | c.POLLHUP | c.POLLNVAL)) != 0) {
                self.processRead(jni_env);
                self.processCommands();
                // Advance an in-flight SFTP prefetch whenever the socket wakes,
                // so the next chunk is ready by the time the JVM asks for it.
                if (self.sftp_staging_armed) {
                    if (self.sftp_staging_read_handle) |file_handle| {
                        _ = self.tryCompleteSftpPrefetch(file_handle);
                    }
                }
                if ((socket_events & (c.POLLERR | c.POLLHUP | c.POLLNVAL)) != 0) {
                    self.running.store(false, .release);
                }
            }
        }

        self.cancelQueuedCommands();
        // session_free releases the libssh2 channels; the ShellChannel structs
        // stay alive for the JVM until closeShell or deinit.
        self.shells_lock.lock();
        for (self.shells.items) |shell| {
            if (!shell.open.load(.acquire)) continue;
            shell.deliverOutput(jni_env);
            shell.open.store(false, .release);
            shell.channel = null;
            shell.deliverClosed(jni_env);
        }
        self.shells_lock.unlock();
        _ = c.libssh2_session_disconnect_ex(self.session, c.SSH_DISCONNECT_BY_APPLICATION, "Shutdown", "");
        _ = c.libssh2_session_free(self.session);
    }

    fn processRead(self: *SshNativeSession, env: ?*c.JNIEnv) void {
        self.retryClosingChannels();
        var buffer: [32768]u8 = undefined;
        self.shells_lock.lock();
        defer self.shells_lock.unlock();
        for (self.shells.items) |shell| {
            if (!self.running.load(.acquire)) return;
            self.readShell(shell, env, &buffer);
        }
    }

    fn readShell(self: *SshNativeSession, shell: *ShellChannel, env: ?*c.JNIEnv, buffer: []u8) void {
        const channel = shell.channel orelse return;
        // A newly opened shell can emit its greeting before the terminal worker
        // registers the JVM callback. Retain that startup window with a strict
        // cap so channel setup cannot lose the prompt or grow without bound.
        const have_callback = shell.hasCallback();
        while (self.running.load(.acquire)) {
            const count = c.libssh2_channel_read(channel, buffer.ptr, buffer.len);
            if (count > 0) {
                shell.output_lock.lock();
                const queued = ssh_output_buffer.append(
                    &shell.output_queue,
                    queue_allocator,
                    buffer[0..@intCast(count)],
                    have_callback,
                ) catch shell.output_queue.items.len;
                shell.output_lock.unlock();
                // Flush mid-drain once a batch has accumulated so a continuous
                // burst doesn't grow an unbounded queue or stall the JVM side;
                // the flush below the loop covers the tail.
                if (queued >= output_flush_threshold_bytes) {
                    shell.deliverOutput(env);
                }
            } else if (count == c.LIBSSH2_ERROR_EAGAIN) {
                break;
            } else if (count == 0 or count == c.LIBSSH2_ERROR_CHANNEL_CLOSED) {
                // The remote shell exited; only this channel ends.
                shell.deliverOutput(env);
                shell.open.store(false, .release);
                shell.channel = null;
                self.releaseChannel(channel);
                shell.deliverClosed(env);
                return;
            } else {
                self.running.store(false, .release);
                break;
            }
        }
        shell.deliverOutput(env);
    }

    fn processCommands(self: *SshNativeSession) void {
        while (true) {
            self.command_lock.lock();
            const command = if (self.command_queue.items.len == 0) null else self.command_queue.orderedRemove(0);
            self.command_lock.unlock();
            const current = command orelse break;

            const owned = current.owned;
            const data_to_free = current.data;
            const completion = current.completion;

            switch (current.kind) {
                .write => if (current.shell) |shell| self.processWrite(shell, data_to_free orelse &[_]u8{}),
                .resize => if (current.shell) |shell| self.processResize(shell, current.cols, current.rows),
                .callback => if (current.callback) |callback| callback(self, current),
            }

            if (owned) {
                if (data_to_free) |data| self.allocator.free(data);
                self.allocator.destroy(current);
            }

            if (completion) |comp| comp.post();
        }
    }

    fn processWrite(self: *SshNativeSession, shell: *ShellChannel, data: []const u8) void {
        const channel = shell.channel orelse return;
        var written: usize = 0;
        while (written < data.len and self.running.load(.acquire)) {
            const result = c.libssh2_channel_write(channel, data[written..].ptr, data.len - written);
            if (result > 0) {
                written += @intCast(result);
            } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch break;
            } else {
                break;
            }
        }
    }

    fn processResize(self: *SshNativeSession, shell: *ShellChannel, cols: c_int, rows: c_int) void {
        const channel = shell.channel orelse return;
        while (self.running.load(.acquire)) {
            const result = c.libssh2_channel_request_pty_size_ex(channel, cols, rows, 0, 0);
            if (result == 0) return;
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn enqueue(self: *SshNativeSession, command: *Command) bool {
        self.command_lock.lock();
        defer self.command_lock.unlock();
        if (!self.running.load(.acquire)) return false;
        self.command_queue.append(queue_allocator, command) catch return false;
        self.signalCommandWake();
        return true;
    }

    fn dispatchSync(self: *SshNativeSession, callback: *const fn (*SshNativeSession, *Command) void, context: *anyopaque) bool {
        var completion: Semaphore = .{};
        completion.init();
        defer completion.deinit();
        var command = Command{ .kind = .callback, .callback = callback, .context = context, .completion = &completion };
        if (!self.enqueue(&command)) return false;
        completion.wait();
        return true;
    }

    fn cancelQueuedCommands(self: *SshNativeSession) void {
        while (true) {
            self.command_lock.lock();
            const command = if (self.command_queue.items.len == 0) null else self.command_queue.orderedRemove(0);
            self.command_lock.unlock();
            const current = command orelse break;

            const owned = current.owned;
            const data_to_free = current.data;
            const completion = current.completion;

            if (owned) {
                if (data_to_free) |data| self.allocator.free(data);
                self.allocator.destroy(current);
            }

            if (completion) |comp| comp.post();
        }
    }

    fn signalCommandWake(self: *SshNativeSession) void {
        const dummy: u8 = 1;
        _ = c.write(self.wake_pipe[1], &dummy, 1);
    }

    /// Never blocks: a close that would wait on the socket is retried by the
    /// loop, so a dead link can't stall other channels or a closeShell caller.
    fn releaseChannel(self: *SshNativeSession, channel: *c.LIBSSH2_CHANNEL) void {
        if (c.libssh2_channel_free(channel) != c.LIBSSH2_ERROR_EAGAIN) return;
        // If this append fails, session_free still reclaims the channel.
        self.closing_channels.append(queue_allocator, channel) catch {};
    }

    fn retryClosingChannels(self: *SshNativeSession) void {
        var index: usize = 0;
        while (index < self.closing_channels.items.len) {
            if (c.libssh2_channel_free(self.closing_channels.items[index]) == c.LIBSSH2_ERROR_EAGAIN) {
                index += 1;
            } else {
                _ = self.closing_channels.swapRemove(index);
            }
        }
    }

    fn unlinkShellLocked(self: *SshNativeSession, shell: *ShellChannel) bool {
        for (self.shells.items, 0..) |item, index| {
            if (item == shell) {
                _ = self.shells.swapRemove(index);
                return true;
            }
        }
        return false;
    }

    fn openShellCallback(self: *SshNativeSession, command: *Command) void {
        const context: *OpenShellContext = @ptrCast(@alignCast(command.context.?));
        const channel = self.openShellChannel(context) catch return;
        const shell = self.allocator.create(ShellChannel) catch {
            self.releaseChannel(channel);
            return;
        };
        shell.* = .{ .owner = self, .channel = channel, .open = std.atomic.Value(bool).init(true) };
        self.shells_lock.lock();
        defer self.shells_lock.unlock();
        self.shells.append(queue_allocator, shell) catch {
            self.releaseChannel(channel);
            self.allocator.destroy(shell);
            return;
        };
        context.result = shell;
    }

    fn openShellChannel(self: *SshNativeSession, context: *OpenShellContext) !*c.LIBSSH2_CHANNEL {
        const channel = while (true) {
            const ch = c.libssh2_channel_open_ex(
                self.session,
                "session",
                @intCast("session".len),
                c.LIBSSH2_CHANNEL_WINDOW_DEFAULT,
                c.LIBSSH2_CHANNEL_PACKET_DEFAULT,
                null,
                0,
            );
            if (ch) |value| break value;
            if (c.libssh2_session_last_error(self.session, null, null, 0) != c.LIBSSH2_ERROR_EAGAIN) {
                return error.ChannelOpenFailed;
            }
            try waitSocket(self.socket_fd, self.session);
        };
        errdefer self.releaseChannel(channel);

        while (true) {
            const rc = c.libssh2_channel_request_pty_ex(
                channel,
                context.term_type.ptr,
                @intCast(context.term_type.len),
                null,
                0,
                context.cols,
                context.rows,
                0,
                0,
            );
            if (rc == 0) break;
            if (rc != c.LIBSSH2_ERROR_EAGAIN) return error.PtyRequestFailed;
            try waitSocket(self.socket_fd, self.session);
        }

        for ([_][]const u8{ "LC_ALL", "LANG", "LC_CTYPE" }) |env_name| {
            var attempt: i32 = 0;
            while (attempt < 50) : (attempt += 1) {
                const rc = c.libssh2_channel_setenv_ex(channel, env_name.ptr, @intCast(env_name.len), "en_US.UTF-8", @intCast("en_US.UTF-8".len));
                if (rc == 0) break;
                if (rc != c.LIBSSH2_ERROR_EAGAIN) break;
                try waitSocket(self.socket_fd, self.session);
            }
        }

        while (true) {
            const rc = if (context.command) |cmd|
                c.libssh2_channel_process_startup(channel, "exec", @intCast("exec".len), cmd.ptr, @intCast(cmd.len))
            else
                c.libssh2_channel_process_startup(channel, "shell", @intCast("shell".len), null, 0);
            if (rc == 0) break;
            if (rc != c.LIBSSH2_ERROR_EAGAIN) return error.ShellStartFailed;
            try waitSocket(self.socket_fd, self.session);
        }
        return channel;
    }

    fn closeShellCallback(self: *SshNativeSession, command: *Command) void {
        const context: *CloseShellContext = @ptrCast(@alignCast(command.context.?));
        const shell = context.shell;
        self.shells_lock.lock();
        _ = self.unlinkShellLocked(shell);
        self.shells_lock.unlock();
        if (shell.channel) |channel| {
            shell.open.store(false, .release);
            shell.channel = null;
            self.releaseChannel(channel);
        }
        context.done = true;
    }

    fn execCommandCallback(self: *SshNativeSession, command: *Command) void {
        const context: *ExecContext = @ptrCast(@alignCast(command.context.?));
        context.exit_status = self.execCommandOwned(
            context.command,
            context.output,
            context.error_output,
        ) catch return;
        context.success = true;
    }

    fn execCommandOwned(
        self: *SshNativeSession,
        command: []const u8,
        output: *std.ArrayList(u8),
        error_output: *std.ArrayList(u8),
    ) !c_int {
        const cmd_c = try self.allocator.dupeZ(u8, command);
        defer self.allocator.free(cmd_c);

        const exec_channel = while (true) {
            const channel = c.libssh2_channel_open_ex(self.session, "session", @intCast("session".len), c.LIBSSH2_CHANNEL_WINDOW_DEFAULT, c.LIBSSH2_CHANNEL_PACKET_DEFAULT, null, 0);
            if (channel) |value| break value;
            const err = c.libssh2_session_last_error(self.session, null, null, 0);
            if (err == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch return error.ChannelOpenFailed;
                continue;
            }
            return error.ChannelOpenFailed;
        };
        defer _ = c.libssh2_channel_free(exec_channel);

        while (true) {
            const result = c.libssh2_channel_process_startup(exec_channel, "exec", 4, cmd_c.ptr, @intCast(command.len));
            if (result == 0) break;
            if (result == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch return error.CommandExecFailed;
                continue;
            }
            return error.CommandExecFailed;
        }

        var stdout_done = false;
        var stderr_done = false;
        var stdout_buffer: [4096]u8 = undefined;
        var stderr_buffer: [4096]u8 = undefined;
        while (!stdout_done or !stderr_done) {
            var made_progress = false;

            if (!stdout_done) {
                while (true) {
                    const result = c.libssh2_channel_read(
                        exec_channel,
                        &stdout_buffer,
                        stdout_buffer.len,
                    );
                    if (result > 0) {
                        try output.appendSlice(self.allocator, stdout_buffer[0..@intCast(result)]);
                        made_progress = true;
                    } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                        break;
                    } else if (result == 0 or result == c.LIBSSH2_ERROR_CHANNEL_CLOSED) {
                        stdout_done = true;
                        break;
                    } else {
                        return error.ReadFailed;
                    }
                }
            }

            if (!stderr_done) {
                while (true) {
                    const result = c.libssh2_channel_read_stderr(
                        exec_channel,
                        &stderr_buffer,
                        stderr_buffer.len,
                    );
                    if (result > 0) {
                        try error_output.appendSlice(
                            self.allocator,
                            stderr_buffer[0..@intCast(result)],
                        );
                        made_progress = true;
                    } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                        break;
                    } else if (result == 0 or result == c.LIBSSH2_ERROR_CHANNEL_CLOSED) {
                        stderr_done = true;
                        break;
                    } else {
                        return error.ReadFailed;
                    }
                }
            }

            if ((!stdout_done or !stderr_done) and !made_progress) {
                waitSocket(self.socket_fd, self.session) catch return error.ReadFailed;
            }
        }

        return c.libssh2_channel_get_exit_status(exec_channel);
    }

    fn sftpInitCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpInitContext = @ptrCast(@alignCast(command.context.?));
        while (self.running.load(.acquire)) {
            const handle = c.libssh2_sftp_init(self.session);
            if (handle) |value| {
                context.result = @ptrCast(value);
                return;
            }
            if (c.libssh2_session_last_error(self.session, null, null, 0) != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpCloseCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpCloseContext = @ptrCast(@alignCast(command.context.?));
        const handle: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        // Complete an in-flight file prefetch before tearing the SFTP session
        // down so no orphaned request outlives it.
        if (self.sftp_staging_armed) {
            if (self.sftp_staging_read_handle) |file_handle| self.drainSftpPrefetch(file_handle);
        }
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_shutdown(handle);
            if (result == 0 or result != c.LIBSSH2_ERROR_EAGAIN) break;
            waitSocket(self.socket_fd, self.session) catch return;
        }
        self.sftpResetStaging();
    }

    fn appendJsonEscaped(output: *std.ArrayList(u8), allocator: std.mem.Allocator, str: []const u8) !void {
        for (str) |ch| {
            switch (ch) {
                '"' => try output.appendSlice(allocator, "\\\""),
                '\\' => try output.appendSlice(allocator, "\\\\"),
                '\n' => try output.appendSlice(allocator, "\\n"),
                '\r' => try output.appendSlice(allocator, "\\r"),
                '\t' => try output.appendSlice(allocator, "\\t"),
                else => {
                    if (ch < 0x20) {
                        var buf: [6]u8 = undefined;
                        const s = try std.fmt.bufPrint(&buf, "\\u00{x:0>2}", .{ch});
                        try output.appendSlice(allocator, s);
                    } else {
                        try output.append(allocator, ch);
                    }
                },
            }
        }
    }

    fn sftpListCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpListContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        const dir = while (self.running.load(.acquire)) {
            const handle = c.libssh2_sftp_opendir(sftp, context.path.ptr);
            if (handle) |value| break value;
            if (c.libssh2_session_last_error(self.session, null, null, 0) != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        } else return;
        defer _ = c.libssh2_sftp_closedir(dir);

        context.output.appendSlice(self.allocator, "[") catch return;
        var filename: [1024]u8 = undefined;
        var longentry: [1024]u8 = undefined;
        var attributes: c.LIBSSH2_SFTP_ATTRIBUTES = undefined;
        var first = true;
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_readdir_ex(dir, &filename, filename.len, &longentry, longentry.len, &attributes);
            if (result > 0) {
                const name = filename[0..@intCast(result)];
                if (std.mem.eql(u8, name, ".") or std.mem.eql(u8, name, "..")) continue;
                const is_dir = (attributes.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) != 0 and (attributes.permissions & 0xF000) == 0x4000;
                if (!first) context.output.appendSlice(self.allocator, ",") catch return;
                first = false;

                context.output.appendSlice(self.allocator, "{\"name\":\"") catch return;
                appendJsonEscaped(context.output, self.allocator, name) catch return;
                context.output.appendSlice(self.allocator, "\",\"path\":\"") catch return;
                appendJsonEscaped(context.output, self.allocator, context.path) catch return;
                if (!std.mem.endsWith(u8, context.path, "/")) {
                    context.output.appendSlice(self.allocator, "/") catch return;
                }
                appendJsonEscaped(context.output, self.allocator, name) catch return;
                const entry_suffix = std.fmt.allocPrint(self.allocator, "\",\"isDir\":{s},\"size\":{},\"permissions\":{},\"mtime\":{}}}", .{
                    if (is_dir) "true" else "false",
                    if (is_dir or (attributes.flags & c.LIBSSH2_SFTP_ATTR_SIZE) == 0) 0 else attributes.filesize,
                    if ((attributes.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) == 0) 0 else attributes.permissions,
                    if ((attributes.flags & c.LIBSSH2_SFTP_ATTR_ACMODTIME) == 0) 0 else attributes.mtime * 1000,
                }) catch return;
                defer self.allocator.free(entry_suffix);
                context.output.appendSlice(self.allocator, entry_suffix) catch return;
            } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch return;
            } else if (result == 0) {
                context.output.appendSlice(self.allocator, "]") catch return;
                context.success = true;
                return;
            } else return;
        }
    }

    fn sftpMkdirCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpMkdirContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_mkdir_ex(sftp, context.path.ptr, @intCast(context.path.len), @intCast(context.permissions));
            if (result == 0) context.success = true;
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpDeleteCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpDeleteContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        for (0..2) |attempt| {
            while (self.running.load(.acquire)) {
                const result = if (attempt == 0)
                    c.libssh2_sftp_unlink_ex(sftp, context.path.ptr, @intCast(context.path.len))
                else
                    c.libssh2_sftp_rmdir_ex(sftp, context.path.ptr, @intCast(context.path.len));
                if (result == 0) {
                    context.success = true;
                    return;
                }
                if (result != c.LIBSSH2_ERROR_EAGAIN) break;
                waitSocket(self.socket_fd, self.session) catch return;
            }
        }
    }

    fn sftpRenameCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpRenameContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_rename_ex(
                sftp,
                context.old_path.ptr,
                @intCast(context.old_path.len),
                context.new_path.ptr,
                @intCast(context.new_path.len),
                c.LIBSSH2_SFTP_RENAME_OVERWRITE | c.LIBSSH2_SFTP_RENAME_ATOMIC | c.LIBSSH2_SFTP_RENAME_NATIVE,
            );
            if (result == 0) {
                context.success = true;
                return;
            }
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpOpenCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpOpenContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.sftp));
        while (self.running.load(.acquire)) {
            const handle = c.libssh2_sftp_open_ex(sftp, context.path.ptr, @intCast(context.path.len), @intCast(context.flags), @intCast(context.mode), c.LIBSSH2_SFTP_OPENFILE);
            if (handle) |value| {
                context.result = @ptrCast(value);
                return;
            }
            if (c.libssh2_session_last_error(self.session, null, null, 0) != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpFileCloseCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpFileCloseContext = @ptrCast(@alignCast(command.context.?));
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(context.handle));
        // Complete any in-flight prefetch for this handle before closing so
        // libssh2 never sees a request for a freed handle.
        self.drainSftpPrefetch(context.handle);
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_close_handle(handle);
            if (result == 0 or result != c.LIBSSH2_ERROR_EAGAIN) break;
            waitSocket(self.socket_fd, self.session) catch return;
        }
        self.sftpResetStaging();
    }

    fn sftpStagingBuffer(self: *SshNativeSession) ?[]u8 {
        if (self.sftp_staging) |buffer| return buffer;
        const buffer = queue_allocator.alloc(u8, 256 * 1024) catch return null;
        self.sftp_staging = buffer;
        return buffer;
    }

    fn sftpResetStaging(self: *SshNativeSession) void {
        self.sftp_staging_handle = null;
        self.sftp_staging_read_handle = null;
        self.sftp_staging_len = 0;
        self.sftp_staging_armed = false;
        self.sftp_staging_eof = false;
        self.sftp_staging_error = false;
    }

    // Complete an armed (in-flight) prefetch read into staging if the socket
    // has data. Returns true once the staging slot holds a finished state
    // (bytes available, EOF, or error); returns false while still in flight.
    fn tryCompleteSftpPrefetch(self: *SshNativeSession, handle: *anyopaque) bool {
        if (!self.sftp_staging_armed) {
            return self.sftp_staging_len > 0 or self.sftp_staging_eof or self.sftp_staging_error;
        }
        const staging = self.sftpStagingBuffer() orelse {
            self.sftp_staging_error = true;
            self.sftp_staging_armed = false;
            return true;
        };
        const result = c.libssh2_sftp_read(@ptrCast(@alignCast(handle)), staging.ptr, staging.len);
        if (result > 0) {
            self.sftp_staging_len = @intCast(result);
        } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
            return false;
        } else if (result == 0) {
            self.sftp_staging_eof = true;
        } else {
            self.sftp_staging_error = true;
        }
        self.sftp_staging_armed = false;
        return true;
    }

    // Issue the next chunk read into staging without waiting for it, so it
    // completes while the JVM drains the chunk just served.
    fn armSftpPrefetch(self: *SshNativeSession, file_handle: *anyopaque) void {
        const staging = self.sftpStagingBuffer() orelse return;
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(file_handle));
        const result = c.libssh2_sftp_read(handle, staging.ptr, staging.len);
        if (result > 0) {
            self.sftp_staging_len = @intCast(result);
        } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
            self.sftp_staging_armed = true;
            self.sftp_staging_read_handle = file_handle;
        } else if (result == 0) {
            self.sftp_staging_eof = true;
        } else {
            self.sftp_staging_error = true;
        }
    }

    // Block (on the loop thread, via waitSocket) until the armed prefetch
    // request is finished. Used before closing handles/sessions so libssh2
    // never sees an orphaned in-flight request.
    fn drainSftpPrefetch(self: *SshNativeSession, file_handle: *anyopaque) void {
        while (self.running.load(.acquire) and self.sftp_staging_armed) {
            if (self.tryCompleteSftpPrefetch(file_handle)) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    // Copy the staged chunk into the caller's buffer, keep any remainder, and
    // arm the next fetch. Returns false when staging held no data.
    fn serveSftpStaging(self: *SshNativeSession, context: *SftpReadContext, file_handle: *anyopaque) bool {
        if (self.sftp_staging_len == 0) return false;
        const available = self.sftp_staging.?[0..self.sftp_staging_len];
        const count = @min(available.len, context.buffer.len);
        @memcpy(context.buffer[0..count], available[0..count]);
        self.sftp_staging_len -= count;
        if (self.sftp_staging_len > 0) {
            std.mem.copyForwards(u8, available[0..self.sftp_staging_len], available[count..]);
        }
        context.result = @intCast(count);
        if (count > 0) self.armSftpPrefetch(file_handle);
        return true;
    }

    fn sftpReadCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpReadContext = @ptrCast(@alignCast(command.context.?));
        const file_handle: *anyopaque = context.handle;
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(file_handle));

        if (self.sftp_staging_handle != file_handle) {
            // Handle changed (or closed): finish any in-flight request for the
            // old handle before discarding its staging state.
            if (self.sftp_staging_armed) {
                if (self.sftp_staging_read_handle) |old_handle| self.drainSftpPrefetch(old_handle);
            }
            self.sftpResetStaging();
            self.sftp_staging_handle = file_handle;
        }

        // Serve a finished prefetch chunk without touching the socket.
        if (self.serveSftpStaging(context, file_handle)) return;
        if (self.sftp_staging_eof) {
            context.result = 0;
            self.sftpResetStaging();
            return;
        }
        if (self.sftp_staging_error) {
            self.sftpResetStaging();
            return;
        }

        // A read request is already in flight toward staging: wait for it.
        while (self.sftp_staging_armed and self.running.load(.acquire)) {
            if (self.tryCompleteSftpPrefetch(file_handle)) {
                if (self.serveSftpStaging(context, file_handle)) return;
                if (self.sftp_staging_eof) {
                    context.result = 0;
                    self.sftpResetStaging();
                    return;
                }
                self.sftpResetStaging();
                return; // read error
            }
            waitSocket(self.socket_fd, self.session) catch return;
        }

        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_read(handle, context.buffer.ptr, context.buffer.len);
            if (result >= 0) {
                context.result = @intCast(result);
                if (result > 0) self.armSftpPrefetch(file_handle);
                return;
            }
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpWriteCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpWriteContext = @ptrCast(@alignCast(command.context.?));
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_write(handle, context.buffer.ptr, context.buffer.len);
            if (result >= 0) {
                context.result = @intCast(result);
                return;
            }
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }
};
