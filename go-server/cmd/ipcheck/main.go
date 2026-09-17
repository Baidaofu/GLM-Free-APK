package main

import (
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"time"

	_ "zai-api/internal/platform"
)

func main() {
	proxy := ""
	if len(os.Args) > 1 {
		proxy = os.Args[1]
	}
	client := &http.Client{Timeout: 15 * time.Second}
	if proxy != "" {
		pu, err := url.Parse(proxy)
		if err != nil {
			fmt.Println("proxy parse:", err)
			return
		}
		client.Transport = &http.Transport{Proxy: http.ProxyURL(pu)}
	}
	resp, err := client.Get("https://myip.ipip.net")
	if err != nil {
		fmt.Println("FAIL:", err)
		return
	}
	b, _ := io.ReadAll(resp.Body)
	fmt.Printf("via %q -> %s\n", proxy, string(b))
}
