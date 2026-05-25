package libcore

import (
	"path/filepath"
)

const Socket = "api.sock"

const (
	commandHello uint8 = iota
	commandQueryConnections
	commandSubscribeConnections
	commandCloseConnection
	commandQueryMemory
	commandQueryGoroutines
	commandQueryClashModes
	commandSubscribeClashMode
	commandSetClashMode
	commandUrlTest
	commandUrlFetch
	commandNewInstanceURLTest
	commandGroupURLTest
	commandSelectOutbound
	commandQueryProxySets
	commandResetNetwork
	commandClearLog
	commandSubscribeLogs
	commandImportDeepLink
	commandRunTask
	commandNewInstanceGroupURLTest
)

const (
	resultNoError uint8 = iota
	resultCommonError
)

func apiPath(basePath string) string {
	if basePath == "" {
		basePath = internalAssetsPath
	}
	return filepath.Join(basePath, Socket)
}
