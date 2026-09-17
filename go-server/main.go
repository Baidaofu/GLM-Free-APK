// GLM Bridge — Z.AI Proxy API.
//
// Thin entry point: the whole bridge lives in internal/zbridge (see README
// "Project Structure"), mirroring the DeepseekFreeAPI layout this project
// follows. Token collection is a separate binary under cmd/token-collector.
//
// Build:  go build -trimpath -ldflags="-s -w" -gcflags="all=-l=4" -o zai-api .
// Run:    ./zai-api            (or: go run .)

package main

import (

	_ "zai-api/internal/platform" // android: 注入可用的 DNS 解析器
	"zai-api/internal/zbridge"
)

func main() {
	zbridge.Run()
}
