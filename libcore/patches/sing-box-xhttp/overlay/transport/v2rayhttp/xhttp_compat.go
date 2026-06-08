package v2rayhttp

import (
	"context"
	"net/http"
	"strings"
)

func HWIDContext(ctx context.Context, headers http.Header) context.Context {
	for key, values := range headers {
		if strings.ToLower(key) == "x-hwid" {
			if len(values) != 0 {
				return context.WithValue(ctx, "hwid", values[0])
			}
		}
	}
	return ctx
}
