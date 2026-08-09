module github.com/adriaanm/wata/go-pkgs/iosshell

go 1.26

require (
	github.com/adriaanm/wata/go-pkgs/appleptt v0.0.0-00010101000000-000000000000
	github.com/adriaanm/wata/go-pkgs/iosui v0.0.0-00010101000000-000000000000
	github.com/ebitengine/purego v0.11.0-alpha.8
)

replace github.com/adriaanm/wata/go-pkgs/appleptt => ../appleptt

replace github.com/adriaanm/wata/go-pkgs/iosui => ../iosui
