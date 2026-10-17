package main

import (
	"errors"
	"fmt"
	"io/fs"
	"net"
	"os"
	"syscall"
)

// preparePrivateDir creates the socket's directory if it is missing. An existing directory must already be ours and
// private. It is never chmodded, because it may not be ours to change.
func preparePrivateDir(dir string) error {
	info, err := os.Lstat(dir)
	if errors.Is(err, fs.ErrNotExist) {
		if err := os.Mkdir(dir, 0o700); err != nil {
			return fmt.Errorf("create private directory %s: %w", dir, err)
		}
		return nil
	}
	if err != nil {
		return err
	}
	switch {
	case !info.IsDir():
		return fmt.Errorf("%s is not a directory", dir)
	case !ownedByCurrentUser(info):
		return fmt.Errorf("%s is not owned by the current user", dir)
	case info.Mode().Perm()&0o077 != 0:
		return fmt.Errorf("%s must not be accessible to group or others (mode %o)", dir, info.Mode().Perm())
	}
	return nil
}

// acquireLock takes an exclusive lock for the life of the process, so only one bridge owns a socket path. The lock
// is held until the returned file is closed.
func acquireLock(path string) (*os.File, error) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("another bridge is using %s: %w", path, err)
	}
	return f, nil
}

// claimSocketPath clears a socket left by a bridge that exited without cleaning up. The caller holds the lock, so an
// existing socket is a leftover. Anything else at the path is an error, and nothing is removed.
func claimSocketPath(path string) error {
	info, err := os.Lstat(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if info.Mode()&fs.ModeSocket == 0 || !ownedByCurrentUser(info) {
		return fmt.Errorf("%s exists and is not a socket owned by the current user; refusing to remove it", path)
	}
	return os.Remove(path)
}

// listenOwned binds path with mode 0600 from the start, through the umask. It returns the socket's identity so that
// cleanup can tell our socket from a replacement. Go's unlink-on-close is turned off, because it deletes by name.
func listenOwned(path string) (*net.UnixListener, os.FileInfo, error) {
	previous := syscall.Umask(0o177)
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	syscall.Umask(previous)
	if err != nil {
		return nil, nil, err
	}
	ln.SetUnlinkOnClose(false)

	info, err := os.Lstat(path)
	if err == nil && info.Mode().Perm() != 0o600 {
		err = fmt.Errorf("socket %s has mode %o, expected 600", path, info.Mode().Perm())
	}
	if err != nil {
		_ = ln.Close()
		if info != nil {
			_ = removeOwnedSocket(path, info)
		}
		return nil, nil, err
	}
	return ln, info, nil
}

// removeOwnedSocket unlinks path only while it is still the socket that was bound.
func removeOwnedSocket(path string, owned os.FileInfo) error {
	current, err := os.Lstat(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if !os.SameFile(current, owned) {
		return fmt.Errorf("%s was replaced; leaving it in place", path)
	}
	return os.Remove(path)
}

func ownedByCurrentUser(info os.FileInfo) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	return ok && int(stat.Uid) == os.Getuid()
}
