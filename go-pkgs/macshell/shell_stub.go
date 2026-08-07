//go:build !darwin

// The non-darwin build: wata-mac is a macOS app, but the emitted module must
// still COMPILE everywhere the repo's gates build things (the same reason
// go-pkgs/gioshell has a window-free stub). Every entry point errors or
// no-ops loudly enough that a misdirected run says what it is.

package macshell

import "errors"

var errDarwinOnly = errors.New("macshell: darwin only — wata-mac needs macOS")

func StartHeadless(scale int)          {}
func Start(scale int, title string)    {}
func RunApp()                          {}
func Terminate()                       {}
func Apply(wire string) error          { return errDarwinOnly }
func NextKey() int                     { return -1 }
func PushKeyCode(code int, phase int)  {}
func TreeDump() (string, error)        { return "", errDarwinOnly }
func Login(hs, user string) string     { return "" }
func NextCommand() string              { return "" }
func SetAccount(hs, user string)       {}
func ShowPrefs()                       {}
func RequestNotifyAuth()               {}
func NotifyAvailable() bool            { return false }
func Notify(title, body string) string { return "darwin only" }
func SetBadge(n int)                   {}
func Frontmost() bool                  { return true }
func SetFrontmost(on bool)             {}
func SetNotifyPlay(on bool)            {}
func ShowDevices()                     {}
func SetHandsets(tsv string)           {}
func SetNetworks(tsv string)           {}
func SetPending(tsv string)            {}
func SetRoster(tsv string)             {}
func SetDevStatus(s string)            {}
func TakePSK() string                  { return "" }
func DevSelect(kind string, i int)     {}
func DevType(field, s string)          {}
func DevClick(name string)             {}
func DevDecision() string              { return "" }
