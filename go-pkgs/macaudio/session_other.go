//go:build !ios

package macaudio

// macOS has no AVAudioSession: the engine talks to CoreAudio directly and
// there is nothing to activate. The iOS twin is session_ios.go.
func sessionActivate() error { return nil }
