package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"net"
	"time"
)

// herdrRequest is what the bridge sends to Herdr. It is built from validated fields, never from client bytes, so no
// other method or parameter can reach Herdr.
type herdrRequest struct {
	ID     string     `json:"id"`
	Method string     `json:"method"`
	Params paneTarget `json:"params"`
}

type paneTarget struct {
	PaneID string `json:"pane_id"`
}

// forwardPaneFocus sends one pane.focus over its own connection to Herdr's API socket and returns Herdr's reply line,
// unchanged. The reply must answer id.
func forwardPaneFocus(socketPath, id, paneID string, timeout time.Duration) ([]byte, *bridgeError) {
	deadline := time.Now().Add(timeout)
	conn, err := (&net.Dialer{Deadline: deadline}).Dial("unix", socketPath)
	if err != nil {
		return nil, herdrFailure(err)
	}
	defer conn.Close()
	if err := conn.SetDeadline(deadline); err != nil {
		return nil, herdrFailure(err)
	}

	body, _ := json.Marshal(herdrRequest{ID: id, Method: methodPaneFocus, Params: paneTarget{PaneID: paneID}})
	if _, err := conn.Write(append(body, '\n')); err != nil {
		return nil, herdrFailure(err)
	}

	reply, err := readLine(bufio.NewReader(conn), maxReplyBytes)
	switch {
	case errors.Is(err, errLineTooLong):
		return nil, &bridgeError{Code: codeHerdrBadReply, Message: "Herdr reply is larger than the bridge allows"}
	case errors.Is(err, io.EOF) && len(reply) == 0:
		return nil, &bridgeError{Code: codeHerdrUnavailable, Message: "Herdr closed the connection without replying"}
	case err != nil && !errors.Is(err, io.EOF):
		return nil, herdrFailure(err)
	}

	var head struct {
		ID *string `json:"id"`
	}
	if json.Unmarshal(reply, &head) != nil || head.ID == nil || *head.ID != id {
		return nil, &bridgeError{Code: codeHerdrBadReply, Message: "Herdr reply does not answer this request"}
	}
	return reply, nil
}

// herdrFailure maps a transport error to a bridge error. The message stays generic, so socket paths do not reach the
// client.
func herdrFailure(err error) *bridgeError {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return &bridgeError{Code: codeHerdrTimeout, Message: "Herdr did not answer in time"}
	}
	return &bridgeError{Code: codeHerdrUnavailable, Message: "Herdr request failed"}
}
