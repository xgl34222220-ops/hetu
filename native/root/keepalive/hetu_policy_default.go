//go:build !hetu_root

package keepalive

// Preserve upstream Android VPN behavior in the JNI build.
func hetuDisableKeepAlive(requested bool, platform string) bool {
	return requested || platform == "android"
}
