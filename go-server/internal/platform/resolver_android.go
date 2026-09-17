//go:build android

// Package platform 存放平台特定的兼容性补丁。
//
// Android 上没有 /etc/resolv.conf，纯 Go 构建（CGO_ENABLED=0）的 DNS 解析
// 会退化为向 127.0.0.1:53 发包，导致所有出站域名解析失败。
// 这里在 android 构建下注入一个显式 DNS 服务器列表的 net.DefaultResolver，
// 可用环境变量 ZAI_DNS_SERVERS（逗号分隔，可省略端口）覆盖默认列表。
//
// 移植自 ds2api-android 的 patches/0001-android-dns-resolver.patch。
package platform

import (
	"context"
	"fmt"
	"net"
	"os"
	"strings"
	"sync/atomic"
)

var defaultAndroidDNSServers = []string{
	"223.5.5.5:53",    // 阿里 DNS
	"119.29.29.29:53", // 腾讯 DNSPod
	"1.2.4.8:53",      // CNNIC
	"8.8.8.8:53",      // Google（备用，供代理/VPN 场景）
}

func init() {
	servers := resolveServers()
	var dialer net.Dialer
	var next uint32
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			// Go 解析器传入的 address 是不可用的系统配置（如 127.0.0.1:53），
			// 忽略并轮换使用显式服务器。
			i := atomic.AddUint32(&next, 1)
			server := servers[int(i)%len(servers)]
			return dialer.DialContext(ctx, "udp", server)
		},
	}
	fmt.Printf("[platform] android DNS resolver active, servers=%s (override with ZAI_DNS_SERVERS)\n", strings.Join(servers, ","))
}

func resolveServers() []string {
	raw := strings.TrimSpace(os.Getenv("ZAI_DNS_SERVERS"))
	if raw == "" {
		return defaultAndroidDNSServers
	}
	var out []string
	for _, s := range strings.Split(raw, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if !strings.Contains(s, ":") {
			s += ":53"
		}
		out = append(out, s)
	}
	if len(out) == 0 {
		return defaultAndroidDNSServers
	}
	return out
}
