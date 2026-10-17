// Command herdr-focus-bridge forwards selected pane and workspace actions from a remote client to the Herdr instance
// on this machine.
//
// It listens on a private Unix socket. Each client connection speaks newline-delimited JSON, one object per line.
//
// Handshake, the first line of every connection:
//
//	-> {"id":"h1","method":"bridge.hello","params":{"protocol":1}}
//	<- {"id":"h1","result":{"type":"bridge_hello","protocol":1,"methods":["pane.focus","pane.close","pane.split","tab.create","workspace.create","bridge.stop"],"max_line_bytes":16384}}
//
// Requests, after the handshake. They are answered one at a time, in order. The helper validates and forwards only
// these Herdr actions:
//
//	-> {"id":"r1","method":"pane.focus","params":{"pane_id":"p_1"}}
//	<- Herdr's reply line, byte for byte. Its id is "r1".
//	-> {"id":"r2","method":"pane.close","params":{"pane_id":"p_1"}}
//	-> {"id":"r3","method":"pane.split","params":{"target_pane_id":"p_1","direction":"right","focus":true}}
//	-> {"id":"r4","method":"tab.create","params":{"workspace_id":"w_1","focus":true}}
//	-> {"id":"r5","method":"workspace.create","params":{"source_workspace_id":"w_1","focus":true}}
//
// Stop, sent only when the owner removes the bridge. The reply is written before the bridge drains its connections and
// exits, and the socket is removed on exit:
//
//	-> {"id":"s1","method":"bridge.stop","params":{}}
//	<- {"id":"s1","result":{"type":"bridge_stopping"}}
//
// Errors from the bridge use the same envelope, {"id":"r1","error":{"code":"...","message":"..."}}, with id null when
// the request had no usable id. Codes: bad_request, line_too_long, handshake_required, unsupported_protocol,
// unknown_method, too_many_connections, herdr_unavailable, herdr_timeout, herdr_bad_reply.
//
// A bad request, an unknown method, or a Herdr failure leaves the connection open. A failed handshake, an over-long
// line, an idle timeout, or shutdown closes it. The client then reconnects and handshakes again.
//
// Each forwarded request dials Herdr's API socket anew and reads one reply line, which is how Herdr serves its API.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

// maxUnixPathBytes is the longest socket path macOS accepts: sun_path holds 104 bytes including the terminator.
const maxUnixPathBytes = 103

type config struct {
	socket         string
	herdrSocket    string
	idleTimeout    time.Duration
	requestTimeout time.Duration
	maxConnections int
}

func main() {
	home, _ := os.UserHomeDir()

	var cfg config
	flag.StringVar(&cfg.socket, "socket", filepath.Join(home, ".config", "herdr-focus-bridge", "bridge.sock"),
		"absolute path of the bridge's Unix socket; its parent directory must be private (mode 0700)")
	flag.StringVar(&cfg.herdrSocket, "herdr-socket", filepath.Join(home, ".config", "herdr", "herdr.sock"),
		"absolute path of Herdr's API Unix socket")
	flag.DurationVar(&cfg.idleTimeout, "idle-timeout", 10*time.Minute,
		"close a connection that sends no request for this long")
	flag.DurationVar(&cfg.requestTimeout, "request-timeout", 10*time.Second,
		"deadline for one forwarded Herdr request, dial included")
	flag.IntVar(&cfg.maxConnections, "max-connections", 8, "maximum concurrent client connections")
	flag.Parse()

	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	if err := validateConfig(cfg); err != nil {
		log.Error("invalid flags", "err", err)
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	err := run(ctx, cfg, log)
	stop()
	if err != nil {
		log.Error("bridge failed", "err", err)
		os.Exit(1)
	}
}

func validateConfig(cfg config) error {
	if err := validateSocketPath("socket", cfg.socket); err != nil {
		return err
	}
	if err := validateSocketPath("herdr-socket", cfg.herdrSocket); err != nil {
		return err
	}
	if cfg.idleTimeout <= 0 || cfg.requestTimeout <= 0 {
		return errors.New("-idle-timeout and -request-timeout must be positive")
	}
	if cfg.maxConnections < 1 {
		return errors.New("-max-connections must be at least 1")
	}
	return nil
}

func validateSocketPath(name, path string) error {
	if !filepath.IsAbs(path) {
		return fmt.Errorf("-%s must be an absolute path", name)
	}
	if len(path) > maxUnixPathBytes {
		return fmt.Errorf("-%s is %d bytes; macOS allows at most %d", name, len(path), maxUnixPathBytes)
	}
	return nil
}
