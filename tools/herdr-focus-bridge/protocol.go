package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"net"
	"time"
)

const (
	protocolVersion = 1

	methodHello     = "bridge.hello"
	methodPaneFocus = "pane.focus"
	methodStop      = "bridge.stop"

	maxRequestBytes = 16 * 1024 // a client line, including its newline
	maxReplyBytes   = 1024 * 1024
	maxIDBytes      = 128
	maxPaneIDBytes  = 128
	writeTimeout    = 10 * time.Second
)

// Bridge error codes. Herdr's own codes pass through untouched in its raw replies, so these never reuse them, except
// unknown_method, which carries the same meaning: the method is not available here.
const (
	codeBadRequest          = "bad_request"
	codeLineTooLong         = "line_too_long"
	codeHandshakeRequired   = "handshake_required"
	codeUnsupportedProtocol = "unsupported_protocol"
	codeUnknownMethod       = "unknown_method"
	codeTooManyConnections  = "too_many_connections"
	codeHerdrUnavailable    = "herdr_unavailable"
	codeHerdrTimeout        = "herdr_timeout"
	codeHerdrBadReply       = "herdr_bad_reply"
)

var errLineTooLong = errors.New("line too long")

// request is one client envelope. ID is nil when the envelope had no usable id.
type request struct {
	ID     *string         `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}

// bridgeError is an error the bridge answers with, in the same envelope as Herdr's errors.
type bridgeError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type helloResult struct {
	Type         string   `json:"type"`
	Protocol     int      `json:"protocol"`
	Methods      []string `json:"methods"`
	MaxLineBytes int      `json:"max_line_bytes"`
}

// decodeRequest parses one request envelope. On failure the returned request has a nil ID, so the error reply carries
// a null id instead of an unvalidated one.
func decodeRequest(line []byte) (request, *bridgeError) {
	var req request
	if err := json.Unmarshal(line, &req); err != nil {
		return request{}, &bridgeError{Code: codeBadRequest, Message: "request must be one JSON object"}
	}
	if req.ID == nil || !isToken(*req.ID, maxIDBytes) {
		return request{}, &bridgeError{Code: codeBadRequest, Message: "id must be a string of 1 to 128 printable bytes"}
	}
	return req, nil
}

// strictDecode decodes raw into v and rejects unknown fields. Missing params fail too.
func strictDecode(raw json.RawMessage, v any) error {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

// isToken reports whether s is 1 to limit bytes of printable ASCII with no spaces. Herdr's IDs have that shape, and it
// rules out control characters.
func isToken(s string, limit int) bool {
	if len(s) == 0 || len(s) > limit {
		return false
	}
	for i := range len(s) {
		if s[i] <= 0x20 || s[i] >= 0x7f {
			return false
		}
	}
	return true
}

// readLine returns the next line without its newline. A final line without one comes back with io.EOF. maxBytes counts
// the newline, so an over-long line is refused before it is buffered whole.
func readLine(r *bufio.Reader, maxBytes int) ([]byte, error) {
	var line []byte
	for {
		chunk, err := r.ReadSlice('\n')
		if len(line)+len(chunk) > maxBytes {
			return nil, errLineTooLong
		}
		line = append(line, chunk...)
		switch {
		case err == nil:
			return bytes.TrimSuffix(line, []byte("\n")), nil
		case errors.Is(err, bufio.ErrBufferFull):
			continue
		default:
			return line, err
		}
	}
}

// writeLine sends one line and its newline, bounded by writeTimeout.
func writeLine(conn net.Conn, line []byte) error {
	if err := conn.SetWriteDeadline(time.Now().Add(writeTimeout)); err != nil {
		return err
	}
	_, err := conn.Write(append(bytes.Clone(line), '\n'))
	return err
}

func resultLine(id string, result any) []byte {
	line, _ := json.Marshal(struct {
		ID     string `json:"id"`
		Result any    `json:"result"`
	}{id, result})
	return line
}

func errorLine(id *string, failure bridgeError) []byte {
	line, _ := json.Marshal(struct {
		ID    *string     `json:"id"`
		Error bridgeError `json:"error"`
	}{id, failure})
	return line
}
