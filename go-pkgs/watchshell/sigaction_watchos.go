// This file exists to make the package cgo, so sigaction_watchos.c is
// compiled and linked into any app that imports watchshell. The mechanism
// and the reason live in that file's header (WATCH-SIGACTION-FATAL).

//go:build darwin

package watchshell

import "C"
