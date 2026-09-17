// diag: Android network diagnostics for the zai-api port.
// Checks DNS, uTLS handshake (same config as zbridge), ALPN result,
// and verifies that an ALPN h2/h2c-capable client can talk to Z.AI.
package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"time"

	utls "github.com/refraction-networking/utls"

	_ "zai-api/internal/platform" // android: 注入可用的 DNS 解析器
)

// utlsConnAdapter exposes the utls handshake result through the
// crypto/tls.ConnectionState interface that net/http Transport checks.
type utlsConnAdapter struct {
	*utls.UConn
}

func (a *utlsConnAdapter) ConnectionState() tls.ConnectionState {
	s := a.UConn.ConnectionState()
	return tls.ConnectionState{
		HandshakeComplete:  s.HandshakeComplete,
		NegotiatedProtocol: s.NegotiatedProtocol,
		ServerName:         s.ServerName,
		PeerCertificates:   s.PeerCertificates,
		VerifiedChains:     s.VerifiedChains,
		Version:            s.Version,
		CipherSuite:        s.CipherSuite,
	}
}

func dialUTLS(ctx context.Context, network, addr string) (net.Conn, error) {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	raw, err := (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	config := &utls.Config{
		ServerName: host,
		NextProtos: []string{"h2", "http/1.1"}, // real Chrome order
	}
	uConn := utls.UClient(raw, config, utls.HelloChrome_Auto)
	if err := uConn.HandshakeContext(ctx); err != nil {
		raw.Close()
		return nil, err
	}
	fmt.Println("[dial] negotiated:", uConn.ConnectionState().NegotiatedProtocol, "server:", raw.RemoteAddr())
	return &utlsConnAdapter{uConn}, nil
}

func main() {
	client := &http.Client{
		Transport: &http.Transport{
			DialTLSContext:       dialUTLS,
			ForceAttemptHTTP2:    true,
			TLSHandshakeTimeout:  15 * time.Second,
			ResponseHeaderTimeout: 20 * time.Second,
		},
		Timeout: 30 * time.Second,
	}
	for _, url := range []string{"https://chat.z.ai/", "https://chat.z.ai/api/models"} {
		req, _ := http.NewRequestWithContext(context.Background(), "GET", url, nil)
		req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36")
		resp, err := client.Do(req)
		if err != nil {
			fmt.Println("[req] FAIL:", url, err)
			continue
		}
		b := make([]byte, 120)
		n, _ := resp.Body.Read(b)
		fmt.Printf("[req] %s -> %d proto=%s body: %s\n", url, resp.StatusCode, resp.Proto, string(b[:n]))
		resp.Body.Close()
	}
}
