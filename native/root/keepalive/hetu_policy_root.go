//go:build hetu_root

package keepalive

// A Root transparent proxy owns the server-facing TCP connection. The app's
// socket keepalive cannot protect that second connection, so honor the user's
// Mihomo setting and the upstream idle/interval defaults on Android as well.
func hetuDisableKeepAlive(requested bool, _ string) bool {
	return requested
}
