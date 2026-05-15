package libcore

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/url"
	"time"

	"libcore/vario"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
)

const defaultUrlFetchMaxBody = 4096

func (b *boxInstance) urlFetch(tag, link string, timeout int32, maxBody int32) (string, error) {
	if maxBody <= 0 || maxBody > 65536 {
		maxBody = defaultUrlFetchMaxBody
	}
	var detour adapter.Outbound
	if tag == "" {
		detour = b.Outbound().Default()
	} else {
		var loaded bool
		detour, loaded = b.Outbound().Outbound(tag)
		if !loaded {
			return "", E.New(tag, " is not found")
		}
	}
	linkURL, err := url.Parse(link)
	if err != nil {
		return "", err
	}
	hostname := linkURL.Hostname()
	port := linkURL.Port()
	if port == "" {
		switch linkURL.Scheme {
		case "http":
			port = "80"
		case "https":
			port = "443"
		default:
			return "", E.New("unsupported url scheme: ", linkURL.Scheme)
		}
	}
	ctx, cancel := context.WithTimeout(b.ctx, time.Duration(timeout)*time.Millisecond)
	defer cancel()
	conn, err := detour.DialContext(ctx, "tcp", M.ParseSocksaddrHostPortStr(hostname, port))
	if err != nil {
		return "", err
	}
	defer conn.Close()
	if N.NeedHandshakeForWrite(conn) {
		// handshake timing included in HTTP below
	}
	req, err := http.NewRequest(http.MethodGet, link, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "Husi/1.0")
	client := http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return conn, nil
			},
			TLSClientConfig: &tls.Config{
				Time:    ntp.TimeFuncFromContext(ctx),
				RootCAs: adapter.RootPoolFromContext(ctx),
			},
		},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
		Timeout: C.TCPTimeout,
	}
	defer client.CloseIdleConnections()
	resp, err := client.Do(req.WithContext(ctx))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 399 {
		return "", E.New("HTTP ", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, int64(maxBody)))
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (c *Client) UrlFetch(tag, link string, timeout, maxBody int32) (string, error) {
	err := vario.WriteUint8(c.conn, commandUrlFetch)
	if err != nil {
		return "", E.Cause(err, "write command")
	}
	err = vario.WriteString(c.conn, tag)
	if err != nil {
		return "", E.Cause(err, "write tag")
	}
	err = vario.WriteString(c.conn, link)
	if err != nil {
		return "", E.Cause(err, "write link")
	}
	err = vario.WriteInt32(c.conn, timeout)
	if err != nil {
		return "", E.Cause(err, "write timeout")
	}
	err = vario.WriteInt32(c.conn, maxBody)
	if err != nil {
		return "", E.Cause(err, "write maxBody")
	}
	resultCode, err := vario.ReadUint8(c.conn)
	if err != nil {
		return "", E.Cause(err, "read result code")
	}
	if resultCode != resultNoError {
		message, err := vario.ReadString(c.conn)
		if err != nil {
			return "", E.Cause(err, "read error message")
		}
		return "", E.New(message)
	}
	body, err := vario.ReadString(c.conn)
	if err != nil {
		return "", E.Cause(err, "read body")
	}
	return body, nil
}

func (s *Service) handleUrlFetch(conn io.ReadWriter, instance *boxInstance) error {
	tag, err := vario.ReadString(conn)
	if err != nil {
		return E.Cause(err, "read tag")
	}
	link, err := vario.ReadString(conn)
	if err != nil {
		return E.Cause(err, "read link")
	}
	timeout, err := vario.ReadInt32(conn)
	if err != nil {
		return E.Cause(err, "read timeout")
	}
	maxBody, err := vario.ReadInt32(conn)
	if err != nil {
		return E.Cause(err, "read maxBody")
	}
	body, err := instance.urlFetch(tag, link, timeout, maxBody)
	if err != nil {
		_ = vario.WriteUint8(conn, resultCommonError)
		_ = vario.WriteString(conn, err.Error())
		return nil
	}
	err = vario.WriteUint8(conn, resultNoError)
	if err != nil {
		return E.Cause(err, "write result")
	}
	err = vario.WriteString(conn, body)
	if err != nil {
		return E.Cause(err, "write body")
	}
	return nil
}
