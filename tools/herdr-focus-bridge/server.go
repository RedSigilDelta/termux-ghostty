package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"path/filepath"
	"sync"
	"time"
)

const (
	handshakeTimeout = 10 * time.Second
	shutdownGrace    = 5 * time.Second
	acceptRetryDelay = 100 * time.Millisecond
)

// bridge serves client connections. Each connection gets one goroutine, and its requests are answered in order.
type bridge struct {
	herdrSocket    string
	idleTimeout    time.Duration
	requestTimeout time.Duration
	log            *slog.Logger

	// slots caps concurrent connections. A slot is taken before a connection is tracked and freed in release.
	slots chan struct{}

	// mu guards closing and conns. Read deadlines are armed under it, so drain cannot race a connection that is
	// about to re-arm its deadline.
	mu      sync.Mutex
	closing bool
	conns   map[net.Conn]struct{}
	wg      sync.WaitGroup

	// stopRequested is closed once, by a bridge.stop request after its reply is written. run then shuts down.
	stopRequested chan struct{}
	stopOnce      sync.Once
}

// run serves until ctx is cancelled or a bridge.stop request arrives. It then drains the connections and removes its socket.
func run(ctx context.Context, cfg config, log *slog.Logger) error {
	if err := preparePrivateDir(filepath.Dir(cfg.socket)); err != nil {
		return err
	}
	lock, err := acquireLock(cfg.socket + ".lock")
	if err != nil {
		return err
	}
	defer lock.Close()

	if err := claimSocketPath(cfg.socket); err != nil {
		return err
	}
	ln, owned, err := listenOwned(cfg.socket)
	if err != nil {
		return err
	}

	b := &bridge{
		herdrSocket:    cfg.herdrSocket,
		idleTimeout:    cfg.idleTimeout,
		requestTimeout: cfg.requestTimeout,
		log:            log,
		slots:          make(chan struct{}, cfg.maxConnections),
		conns:          make(map[net.Conn]struct{}),
		stopRequested:  make(chan struct{}),
	}
	log.Info("listening", "socket", cfg.socket, "herdr_socket", cfg.herdrSocket)

	accepted := make(chan struct{})
	go func() {
		defer close(accepted)
		b.acceptLoop(ln)
	}()
	select {
	case <-ctx.Done():
	case <-b.stopRequested:
	}
	log.Info("shutting down")
	_ = ln.Close()
	<-accepted
	b.drain(shutdownGrace)

	if err := removeOwnedSocket(cfg.socket, owned); err != nil {
		log.Warn("socket not removed", "err", err)
	}
	return nil
}

func (b *bridge) acceptLoop(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			b.log.Warn("accept failed", "err", err)
			time.Sleep(acceptRetryDelay)
			continue
		}
		select {
		case b.slots <- struct{}{}:
		default:
			go refuse(conn)
			continue
		}
		if !b.track(conn) {
			<-b.slots
			_ = conn.Close()
			continue
		}
		go b.serveConn(conn)
	}
}

// refuse tells a client over the connection cap why it was turned away.
func refuse(conn net.Conn) {
	defer conn.Close()
	_ = writeLine(conn, errorLine(nil, bridgeError{Code: codeTooManyConnections, Message: "too many connections"}))
}

func (b *bridge) serveConn(conn net.Conn) {
	defer b.release(conn)
	reader := bufio.NewReader(conn)
	if !b.handshake(conn, reader) {
		return
	}
	for {
		line, ok := b.nextLine(conn, reader, b.idleTimeout)
		if !ok {
			return
		}
		if err := b.handle(conn, line); err != nil {
			return
		}
	}
}

// handshake reads the first line, which must be bridge.hello for a protocol version this bridge speaks.
func (b *bridge) handshake(conn net.Conn, reader *bufio.Reader) bool {
	line, ok := b.nextLine(conn, reader, handshakeTimeout)
	if !ok {
		return false
	}
	req, failure := decodeRequest(line)
	if failure != nil {
		_ = writeLine(conn, errorLine(req.ID, *failure))
		return false
	}
	if req.Method != methodHello {
		_ = writeLine(conn, errorLine(req.ID, bridgeError{
			Code:    codeHandshakeRequired,
			Message: "the first request must be bridge.hello",
		}))
		return false
	}
	var hello struct {
		Protocol *int `json:"protocol"`
	}
	if err := strictDecode(req.Params, &hello); err != nil || hello.Protocol == nil {
		_ = writeLine(conn, errorLine(req.ID, bridgeError{Code: codeBadRequest, Message: "params.protocol must be an integer"}))
		return false
	}
	if *hello.Protocol != protocolVersion {
		_ = writeLine(conn, errorLine(req.ID, bridgeError{
			Code:    codeUnsupportedProtocol,
			Message: fmt.Sprintf("this bridge speaks protocol %d", protocolVersion),
		}))
		return false
	}
	return writeLine(conn, resultLine(*req.ID, helloResult{
		Type:     "bridge_hello",
		Protocol: protocolVersion,
		Methods: []string{
			methodPaneFocus,
			methodPaneClose,
			methodPaneSplit,
			methodTabCreate,
			methodWorkspaceCreate,
			methodStop,
		},
		MaxLineBytes: maxRequestBytes,
	})) == nil
}

// nextLine returns the next non-blank line and reports false when the connection should end. An over-long line is
// answered first, since the framing is lost after it.
func (b *bridge) nextLine(conn net.Conn, reader *bufio.Reader, timeout time.Duration) ([]byte, bool) {
	for {
		if !b.armRead(conn, timeout) {
			return nil, false
		}
		line, err := readLine(reader, maxRequestBytes)
		if errors.Is(err, errLineTooLong) {
			_ = writeLine(conn, errorLine(nil, bridgeError{
				Code:    codeLineTooLong,
				Message: fmt.Sprintf("a request line may be at most %d bytes", maxRequestBytes),
			}))
			return nil, false
		}
		// A final line without a newline is still a request. Any other error ends the connection.
		if err != nil && !(errors.Is(err, io.EOF) && len(line) > 0) {
			return nil, false
		}
		if len(bytes.TrimSpace(line)) > 0 {
			return line, true
		}
		if err != nil {
			return nil, false
		}
	}
}

// handle answers one request line. The returned error is a failed write, which ends the connection.
func (b *bridge) handle(conn net.Conn, line []byte) error {
	req, failure := decodeRequest(line)
	if failure != nil {
		return writeLine(conn, errorLine(req.ID, *failure))
	}
	switch req.Method {
	case methodPaneFocus:
		return b.paneFocus(conn, req)
	case methodPaneClose:
		return b.paneClose(conn, req)
	case methodPaneSplit:
		return b.paneSplit(conn, req)
	case methodTabCreate:
		return b.tabCreate(conn, req)
	case methodWorkspaceCreate:
		return b.workspaceCreate(conn, req)
	case methodStop:
		return b.stop(conn, req)
	default:
		return writeLine(conn, errorLine(req.ID, bridgeError{Code: codeUnknownMethod, Message: "method is not supported"}))
	}
}

// stop acknowledges bridge.stop, then asks run to shut down. The reply is written first, so the requester gets it before
// its own connection drains. Nothing is stopped unless the reply was written.
func (b *bridge) stop(conn net.Conn, req request) error {
	var params struct{}
	if err := strictDecode(req.Params, &params); err != nil {
		return writeLine(conn, errorLine(req.ID, bridgeError{Code: codeBadRequest, Message: "params must be an empty object"}))
	}
	if err := writeLine(conn, resultLine(*req.ID, struct {
		Type string `json:"type"`
	}{Type: "bridge_stopping"})); err != nil {
		return err
	}
	b.stopOnce.Do(func() { close(b.stopRequested) })
	return nil
}

func (b *bridge) paneFocus(conn net.Conn, req request) error {
	var params struct {
		PaneID *string `json:"pane_id"`
	}
	if err := strictDecode(req.Params, &params); err != nil || params.PaneID == nil || !isToken(*params.PaneID, maxPaneIDBytes) {
		return writeLine(conn, errorLine(req.ID, bridgeError{
			Code:    codeBadRequest,
			Message: "params.pane_id must be a string of 1 to 128 printable bytes",
		}))
	}
	return b.forward(conn, req, methodPaneFocus, paneIDParams{PaneID: *params.PaneID})
}

func (b *bridge) paneClose(conn net.Conn, req request) error {
	var params struct {
		PaneID *string `json:"pane_id"`
	}
	if err := strictDecode(req.Params, &params); err != nil || params.PaneID == nil || !isToken(*params.PaneID, maxPaneIDBytes) {
		return writeLine(conn, errorLine(req.ID, bridgeError{
			Code:    codeBadRequest,
			Message: "params.pane_id must be a string of 1 to 128 printable bytes",
		}))
	}
	return b.forward(conn, req, methodPaneClose, paneIDParams{PaneID: *params.PaneID})
}

func (b *bridge) paneSplit(conn net.Conn, req request) error {
	var params struct {
		TargetPaneID *string `json:"target_pane_id"`
		Direction    *string `json:"direction"`
		Focus        *bool   `json:"focus"`
	}
	if err := strictDecode(req.Params, &params); err != nil || params.TargetPaneID == nil ||
		!isToken(*params.TargetPaneID, maxPaneIDBytes) || params.Direction == nil || params.Focus == nil {
		return writeLine(conn, errorLine(req.ID, bridgeError{
			Code:    codeBadRequest,
			Message: "params must include a valid target_pane_id, direction, and focus",
		}))
	}
	if *params.Direction != "right" && *params.Direction != "down" {
		return writeLine(conn, errorLine(req.ID, bridgeError{
			Code:    codeBadRequest,
			Message: "params.direction must be right or down",
		}))
	}
	return b.forward(conn, req, methodPaneSplit, paneSplitParams{
		TargetPaneID: *params.TargetPaneID,
		Direction:    *params.Direction,
		Focus:        *params.Focus,
	})
}

func (b *bridge) workspaceCreate(conn net.Conn, req request) error {
	var params struct {
		SourceWorkspaceID *string `json:"source_workspace_id"`
		Focus             *bool   `json:"focus"`
	}
	if err := strictDecode(req.Params, &params); err != nil || params.SourceWorkspaceID == nil ||
		!isToken(*params.SourceWorkspaceID, maxIDBytes) || params.Focus == nil {
		return writeLine(conn, errorLine(req.ID, bridgeError{
			Code:    codeBadRequest,
			Message: "params must include a valid source_workspace_id and focus",
		}))
	}
	return b.forward(conn, req, methodWorkspaceCreate, workspaceCreateParams{
		SourceWorkspaceID: *params.SourceWorkspaceID,
		Focus:             *params.Focus,
	})
}

func (b *bridge) tabCreate(conn net.Conn, req request) error {
	var params struct {
		WorkspaceID *string `json:"workspace_id"`
		Focus       *bool   `json:"focus"`
	}
	if err := strictDecode(req.Params, &params); err != nil || params.WorkspaceID == nil ||
		!isToken(*params.WorkspaceID, maxIDBytes) || params.Focus == nil {
		return writeLine(conn, errorLine(req.ID, bridgeError{
			Code:    codeBadRequest,
			Message: "params must include a valid workspace_id and focus",
		}))
	}
	return b.forward(conn, req, methodTabCreate, tabCreateParams{
		WorkspaceID: *params.WorkspaceID,
		Focus:       *params.Focus,
	})
}

func (b *bridge) forward(conn net.Conn, req request, method string, params any) error {
	reply, failure := forwardHerdrRequest(b.herdrSocket, *req.ID, method, params, b.requestTimeout)
	if failure != nil {
		return writeLine(conn, errorLine(req.ID, *failure))
	}
	return writeLine(conn, reply)
}

// armRead sets the read deadline for the next line. It fails once draining has begun.
func (b *bridge) armRead(conn net.Conn, timeout time.Duration) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closing {
		return false
	}
	return conn.SetReadDeadline(time.Now().Add(timeout)) == nil
}

// track registers conn so drain can find it. It fails once draining has begun.
func (b *bridge) track(conn net.Conn) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closing {
		return false
	}
	b.conns[conn] = struct{}{}
	b.wg.Add(1)
	return true
}

func (b *bridge) release(conn net.Conn) {
	b.mu.Lock()
	delete(b.conns, conn)
	b.mu.Unlock()
	_ = conn.Close()
	<-b.slots
	b.wg.Done()
}

// drain stops reading new requests. Connections finish the request they are on, then close. Any still open after
// grace are closed forcibly. Their in-flight Herdr request is bounded by requestTimeout, so drain returns soon after.
func (b *bridge) drain(grace time.Duration) {
	b.mu.Lock()
	b.closing = true
	for conn := range b.conns {
		_ = conn.SetReadDeadline(time.Now())
	}
	b.mu.Unlock()

	done := make(chan struct{})
	go func() {
		b.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(grace):
		b.mu.Lock()
		for conn := range b.conns {
			_ = conn.Close()
		}
		b.mu.Unlock()
		<-done
	}
}
