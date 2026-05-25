package libcore

import (
	"context"
	"crypto/rand"
	"io"
	"syscall"
	"time"

	"libcore/protect"
	"libcore/vario"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"

	"github.com/xchacha20-poly1305/libping"
	"golang.org/x/sync/errgroup"
)

func ignoreProtectError() control.Func {
	return func(network, address string, conn syscall.RawConn) error {
		_ = control.Raw(conn, func(fd uintptr) error {
			_ = protect.Protect(ProtectPath, int(fd))
			return nil
		})
		return nil
	}
}

// IcmpPing use ICMP to probe the address. `timeout` is Millisecond.
func IcmpPing(address string, timeout int32) (latency int32, err error) {
	payload := make([]byte, 40)
	_, _ = rand.Read(payload)

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()

	t, err := libping.IcmpPing(ctx, M.ParseSocksaddr(address), payload, ignoreProtectError())
	if err != nil {
		return -1, err
	}

	return int32(t.Milliseconds()), nil
}

// TcpPing try create TCP connection to target. `timeout` is Millisecond.
func TcpPing(host, port string, timeout int32) (latency int32, err error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()

	l, err := libping.TcpPing(ctx, M.ParseSocksaddrHostPortStr(host, port), ignoreProtectError())
	if err != nil {
		return -1, err
	}

	return int32(l.Milliseconds()), nil
}

// urlTest perform URL test for tag using link and timeout as millisecond.
// If tag is empty, it will use the default outbound.
func (b *boxInstance) urlTest(tag, link string, timeout int32) (latency int32, err error) {
	var detour adapter.Outbound
	if tag == "" {
		detour = b.Outbound().Default()
	} else {
		var loaded bool
		detour, loaded = b.Outbound().Outbound(tag)
		if !loaded {
			return -1, E.New(tag, " is not found")
		}
	}

	ctx, cancel := context.WithTimeout(b.ctx, time.Duration(timeout)*time.Millisecond)
	defer cancel()

	// cancel context can't interrupt in time.
	chLatency := make(chan uint16, 1)
	go func() {
		var t uint16
		t, err = urltest.URLTest(ctx, link, detour)
		if err != nil {
			close(chLatency)
			return
		}
		chLatency <- t

		historyStorage := b.historyStorage()
		if historyStorage == nil {
			return
		}
		realTag := group.RealTag(detour)
		historyStorage.StoreURLTestHistory(realTag, &adapter.URLTestHistory{
			Time:  time.Now(),
			Delay: t,
		})
	}()
	select {
	case <-ctx.Done():
		return -1, ctx.Err()
	case t, loaded := <-chLatency:
		if !loaded {
			return -1, err
		}
		return int32(t), nil
	}
}

func (c *Client) GroupTest(tag, link string, timeout int32) error {
	err := vario.WriteUint8(c.conn, commandGroupURLTest)
	if err != nil {
		return E.Cause(err, "write command")
	}
	err = vario.WriteString(c.conn, tag)
	if err != nil {
		return E.Cause(err, "write tag")
	}
	err = vario.WriteString(c.conn, link)
	if err != nil {
		return E.Cause(err, "write link")
	}
	err = vario.WriteInt32(c.conn, timeout)
	if err != nil {
		return E.Cause(err, "write timeout")
	}
	resultCode, err := vario.ReadUint8(c.conn)
	if err != nil {
		return E.Cause(err, "read result code")
	}
	if resultCode != resultNoError {
		message, err := vario.ReadString(c.conn)
		if err != nil {
			return E.Cause(err, "read error message")
		}
		return E.New(message)
	}
	return nil
}

func (s *Service) handleGroupTest(conn io.ReadWriter, instance *boxInstance) error {
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

	outboundManager := instance.Outbound()
	outbound, loaded := outboundManager.Outbound(tag)
	if !loaded {
		err = E.New("group [", tag, "] is not found")
		_ = vario.WriteUint8(conn, resultCommonError)
		_ = vario.WriteString(conn, err.Error())
		return err
	}
	outboundGroup, isOutboundGroup := outbound.(adapter.OutboundGroup)
	if !isOutboundGroup {
		err = E.New("[", tag, "] is not a group")
		_ = vario.WriteUint8(conn, resultCommonError)
		_ = vario.WriteString(conn, err.Error())
		return err
	}

	ctx, cancel := context.WithTimeout(instance.ctx, time.Duration(timeout)*time.Millisecond)
	defer cancel()

	if urlTestGroup, isURLTestGroup := outboundGroup.(adapter.URLTestGroup); isURLTestGroup {
		_, err = urlTestGroup.URLTest(ctx)
		if err != nil {
			_ = vario.WriteUint8(conn, resultCommonError)
			_ = vario.WriteString(conn, err.Error())
			return err
		}
	} else {
		historyStorage := instance.api.HistoryStorage()
		if historyStorage == nil {
			return nil
		}
		outbounds := common.FilterNotNil(common.Map(outboundGroup.All(), func(it string) adapter.Outbound {
			itOutbound, _ := outboundManager.Outbound(it)
			return itOutbound
		}))
		errGroup, _ := errgroup.WithContext(ctx)
		errGroup.SetLimit(10)
		checked := make(map[string]bool)
		for _, detour := range outbounds {
			tag := detour.Tag()
			realTag := group.RealTag(detour)
			if checked[realTag] {
				continue
			}
			checked[realTag] = true
			p, loaded := outboundManager.Outbound(realTag)
			if !loaded {
				continue
			}
			errGroup.Go(func() error {
				t, err := urltest.URLTest(ctx, link, p)
				if err != nil {
					log.DebugContext(ctx, "outbound ", tag, " unavailable: ", err)
				} else {
					log.DebugContext(ctx, "outbound ", tag, " available: ", t, "ms")
				}
				historyStorage.StoreURLTestHistory(realTag, &adapter.URLTestHistory{
					Time:  time.Now(),
					Delay: t,
				})
				return nil
			})
		}
		_ = errGroup.Wait()
	}

	err = vario.WriteUint8(conn, resultNoError)
	if err != nil {
		return E.Cause(err, "write result code")
	}
	return nil
}

func (c *Client) NewInstanceURLTest(config, tag, link string, timeout int32) (int32, error) {
	err := vario.WriteUint8(c.conn, commandNewInstanceURLTest)
	if err != nil {
		return -1, E.Cause(err, "write command")
	}
	err = vario.WriteString(c.conn, config)
	if err != nil {
		return -1, E.Cause(err, "write config")
	}
	err = vario.WriteString(c.conn, tag)
	if err != nil {
		return -1, E.Cause(err, "write tag")
	}
	err = vario.WriteString(c.conn, link)
	if err != nil {
		return -1, E.Cause(err, "write link")
	}
	err = vario.WriteInt32(c.conn, timeout)
	if err != nil {
		return -1, E.Cause(err, "write timeout")
	}
	resultCode, err := vario.ReadUint8(c.conn)
	if err != nil {
		return -1, E.Cause(err, "read result code")
	}
	if resultCode != resultNoError {
		errMsg, err := vario.ReadString(c.conn)
		if err != nil {
			return -1, E.Cause(err, "read error message")
		}
		return -1, E.New(errMsg)
	}
	latency, err := vario.ReadInt32(c.conn)
	if err != nil {
		return -1, E.Cause(err, "read latency")
	}
	return latency, nil
}

func (s *Service) handleNewInstanceURLTest(conn io.ReadWriter) error {
	config, err := vario.ReadString(conn)
	if err != nil {
		return E.Cause(err, "read config")
	}
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

	latency, err := s.newInstanceURLTest(config, tag, link, timeout)
	if err != nil {
		_ = vario.WriteUint8(conn, resultCommonError)
		_ = vario.WriteString(conn, err.Error())
		return nil
	}

	err = vario.WriteUint8(conn, resultNoError)
	if err != nil {
		return E.Cause(err, "write result")
	}
	err = vario.WriteInt32(conn, latency)
	if err != nil {
		return E.Cause(err, "write latency")
	}
	return nil
}

func (s *Service) newInstanceURLTest(config, tag, link string, timeout int32) (int32, error) {
	instance, err := newBoxInstance(config, s.platformInterface, true)
	if err != nil {
		return -1, E.Cause(err, "create instance")
	}
	defer instance.Close()
	err = instance.Start()
	if err != nil {
		return -1, E.Cause(err, "start instance")
	}
	return instance.urlTest(tag, link, timeout)
}

func (c *Client) UrlTest(tag, link string, timeout int32) (int32, error) {
	err := vario.WriteUint8(c.conn, commandUrlTest)
	if err != nil {
		return -1, E.Cause(err, "write command")
	}
	err = vario.WriteString(c.conn, tag)
	if err != nil {
		return -1, E.Cause(err, "write tag")
	}
	err = vario.WriteString(c.conn, link)
	if err != nil {
		return -1, E.Cause(err, "write link")
	}
	err = vario.WriteInt32(c.conn, timeout)
	if err != nil {
		return -1, E.Cause(err, "write timeout")
	}
	resultCode, err := vario.ReadUint8(c.conn)
	if err != nil {
		return -1, E.Cause(err, "read result code")
	}
	if resultCode != resultNoError {
		message, err := vario.ReadString(c.conn)
		if err != nil {
			return -1, E.Cause(err, "read error message")
		}
		return -1, E.New(message)
	}
	latency, err := vario.ReadInt32(c.conn)
	if err != nil {
		return -1, E.Cause(err, "read latency")
	}
	return latency, nil
}

func (c *Client) NewInstanceGroupURLTest(config, groupTag, link string, timeout int32) (map[string]int32, error) {
	err := vario.WriteUint8(c.conn, commandNewInstanceGroupURLTest)
	if err != nil {
		return nil, E.Cause(err, "write command")
	}
	err = vario.WriteString(c.conn, config)
	if err != nil {
		return nil, E.Cause(err, "write config")
	}
	err = vario.WriteString(c.conn, groupTag)
	if err != nil {
		return nil, E.Cause(err, "write group tag")
	}
	err = vario.WriteString(c.conn, link)
	if err != nil {
		return nil, E.Cause(err, "write link")
	}
	err = vario.WriteInt32(c.conn, timeout)
	if err != nil {
		return nil, E.Cause(err, "write timeout")
	}
	resultCode, err := vario.ReadUint8(c.conn)
	if err != nil {
		return nil, E.Cause(err, "read result code")
	}
	if resultCode != resultNoError {
		errMsg, err := vario.ReadString(c.conn)
		if err != nil {
			return nil, E.Cause(err, "read error message")
		}
		return nil, E.New(errMsg)
	}
	count, err := vario.ReadUvarint(c.conn)
	if err != nil {
		return nil, E.Cause(err, "read delay count")
	}
	delays := make(map[string]int32, int(count))
	for i := uint64(0); i < count; i++ {
		tag, err := vario.ReadString(c.conn)
		if err != nil {
			return nil, E.Cause(err, "read tag")
		}
		delay, err := vario.ReadInt32(c.conn)
		if err != nil {
			return nil, E.Cause(err, "read delay")
		}
		if delay > 0 {
			delays[tag] = delay
		}
	}
	return delays, nil
}

func (s *Service) handleNewInstanceGroupURLTest(conn io.ReadWriter) error {
	config, err := vario.ReadString(conn)
	if err != nil {
		return E.Cause(err, "read config")
	}
	groupTag, err := vario.ReadString(conn)
	if err != nil {
		return E.Cause(err, "read group tag")
	}
	link, err := vario.ReadString(conn)
	if err != nil {
		return E.Cause(err, "read link")
	}
	timeout, err := vario.ReadInt32(conn)
	if err != nil {
		return E.Cause(err, "read timeout")
	}

	delays, err := s.newInstanceGroupURLTest(config, groupTag, link, timeout)
	if err != nil {
		_ = vario.WriteUint8(conn, resultCommonError)
		_ = vario.WriteString(conn, err.Error())
		return nil
	}

	err = vario.WriteUint8(conn, resultNoError)
	if err != nil {
		return E.Cause(err, "write result")
	}
	err = vario.WriteUvarint(conn, uint64(len(delays)))
	if err != nil {
		return E.Cause(err, "write delay count")
	}
	for tag, delay := range delays {
		err = vario.WriteString(conn, tag)
		if err != nil {
			return E.Cause(err, "write tag")
		}
		err = vario.WriteInt32(conn, delay)
		if err != nil {
			return E.Cause(err, "write delay")
		}
	}
	return nil
}

func (s *Service) newInstanceGroupURLTest(config, groupTag, link string, timeout int32) (map[string]int32, error) {
	instance, err := newBoxInstance(config, s.platformInterface, true)
	if err != nil {
		return nil, E.Cause(err, "create instance")
	}
	defer instance.Close()
	err = instance.Start()
	if err != nil {
		return nil, E.Cause(err, "start instance")
	}

	outboundManager := instance.Outbound()
	outbound, loaded := outboundManager.Outbound(groupTag)
	if !loaded {
		return nil, E.New("group [", groupTag, "] is not found")
	}
	outboundGroup, isOutboundGroup := outbound.(adapter.OutboundGroup)
	if !isOutboundGroup {
		return nil, E.New("[", groupTag, "] is not a group")
	}

	ctx, cancel := context.WithTimeout(instance.ctx, time.Duration(timeout)*time.Millisecond)
	defer cancel()

	if urlTestGroup, isURLTestGroup := outboundGroup.(adapter.URLTestGroup); isURLTestGroup {
		_, err = urlTestGroup.URLTest(ctx)
		if err != nil {
			return nil, err
		}
	} else {
		historyStorage := instance.api.HistoryStorage()
		if historyStorage == nil {
			return map[string]int32{}, nil
		}
		outbounds := common.FilterNotNil(common.Map(outboundGroup.All(), func(it string) adapter.Outbound {
			itOutbound, _ := outboundManager.Outbound(it)
			return itOutbound
		}))
		errGroup, _ := errgroup.WithContext(ctx)
		errGroup.SetLimit(10)
		checked := make(map[string]bool)
		for _, detour := range outbounds {
			tag := detour.Tag()
			realTag := group.RealTag(detour)
			if checked[realTag] {
				continue
			}
			checked[realTag] = true
			p, loaded := outboundManager.Outbound(realTag)
			if !loaded {
				continue
			}
			errGroup.Go(func() error {
				t, err := urltest.URLTest(ctx, link, p)
				if err != nil {
					log.DebugContext(ctx, "outbound ", tag, " unavailable: ", err)
				} else {
					log.DebugContext(ctx, "outbound ", tag, " available: ", t, "ms")
					historyStorage.StoreURLTestHistory(realTag, &adapter.URLTestHistory{
						Time:  time.Now(),
						Delay: t,
					})
				}
				return nil
			})
		}
		_ = errGroup.Wait()
	}

	historyStorage := instance.historyStorage()
	if historyStorage == nil {
		return map[string]int32{}, nil
	}
	delays := make(map[string]int32)
	for _, memberTag := range outboundGroup.All() {
		detour, loaded := outboundManager.Outbound(memberTag)
		if !loaded {
			continue
		}
		realTag := group.RealTag(detour)
		history := historyStorage.LoadURLTestHistory(realTag)
		if history != nil && history.Delay > 0 {
			delays[memberTag] = int32(history.Delay)
		}
	}
	return delays, nil
}

func (s *Service) handleUrlTest(conn io.ReadWriter, instance *boxInstance) error {
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
	latency, err := instance.urlTest(tag, link, timeout)
	if err != nil {
		_ = vario.WriteUint8(conn, resultCommonError)
		_ = vario.WriteString(conn, err.Error())
		return nil
	}
	err = vario.WriteUint8(conn, resultNoError)
	if err != nil {
		return E.Cause(err, "write result")
	}
	err = vario.WriteInt32(conn, latency)
	if err != nil {
		return E.Cause(err, "write latency")
	}
	return nil
}
