module github.com/adriaanm/wata/tools/phone-spike/watamobile

go 1.26.3

require (
	github.com/adriaanm/wata/tools/phone-spike/watacore v0.0.0
	golang.org/x/mobile v0.0.0-20260803200217-62cee1672c8e
)

// The sgola emission is a build product under watabind/.sgo/ (gitignored):
// `sgo build` with `emitpackage watacore` writes the importable package dir,
// and this replace points at it.
replace github.com/adriaanm/wata/tools/phone-spike/watacore => ../watabind/.sgo/watacore-pkg
