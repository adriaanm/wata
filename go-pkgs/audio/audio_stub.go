//go:build !(linux && arm)

// Pure-Go no-op stub for every platform except the device (linux/arm). It lets
// the darwin host that runs `sgo ci` compile and vet this package with no cgo,
// no C toolchain, and no audio hardware. Every entry point errors loudly rather
// than emitting silently-wrong behavior (CLAUDE.md house rule).

package audio

import "errors"

var errStub = errors.New("audio: no-op stub build (real audio is linux/arm cgo only — run mklibs.sh and cross-build)")

type Encoder struct{}

func NewEncoder() (*Encoder, error)                   { return nil, errStub }
func (e *Encoder) Encode([]byte, []byte) (int, error) { return 0, errStub }
func (e *Encoder) Close()                             {}

type Decoder struct{}

func NewDecoder() (*Decoder, error)                   { return nil, errStub }
func (d *Decoder) Decode([]byte, []byte) (int, error) { return 0, errStub }
func (d *Decoder) Close()                             {}

type Playback struct{}

func OpenPlayback(uint) (*Playback, error)                 { return nil, errStub }
func OpenPlaybackTuned(_, _, _, _ uint) (*Playback, error) { return nil, errStub }
func (p *Playback) WriteFrames([]byte) error               { return errStub }
func (p *Playback) Start() error                           { return errStub }
func (p *Playback) State() int                             { return StateDisconnected }
func (p *Playback) Drain()                                 {}
func (p *Playback) Close()                                 {}

type Capture struct{}

func OpenCapture() (*Capture, error)              { return nil, errStub }
func (c *Capture) ReadFrames([]byte) (int, error) { return 0, errStub }
func (c *Capture) Close()                         {}

func SetupMixer()               {}
func SetPlaybackVolume(int) int { return -1 }

type VolCtl struct{}

func OpenVolCtl() (*VolCtl, error) { return nil, errStub }
func (v *VolCtl) Apply(int) int    { return -1 }
func (v *VolCtl) Close()           {}

// The route watchdog watches a codec that only the handset has, so off-device
// there is nothing to correct and the count is always 0.
type RouteCtl struct{}

func OpenRouteCtl() (*RouteCtl, error) { return nil, errStub }
func (r *RouteCtl) Reassert() int      { return 0 }
func (r *RouteCtl) Close()             {}
func RouteCorrections() int            { return 0 }
