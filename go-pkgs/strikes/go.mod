module github.com/adriaanm/wata/go-pkgs/strikes

go 1.26

// GOMOD-STAGE-NO-MVS: pinned down to go-pkgs/gioshell's x/image version —
// sgo's go.mod stage errors on a version conflict between godeps instead of
// running MVS. Lift to latest when the stage resolves versions itself.
require golang.org/x/image v0.26.0

require golang.org/x/text v0.32.0 // indirect
