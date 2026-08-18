//go:build !ios

package macaudio

import av "github.com/adriaanm/wata/go-pkgs/appleptt/avfaudio"

// macOS has no AVAudioSession: the engine talks to CoreAudio directly and
// there is nothing to activate. The iOS twin is session_ios.go.
func sessionActivate() error { return nil }

// On macOS the HAL vends the input device's format whether or not the engine
// has ever touched its input node, so there is nothing to prepare.
func prepareInput(e av.AVAudioEngine) {}
