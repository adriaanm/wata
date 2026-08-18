module github.com/adriaanm/wata/tools/ios-hello/app

go 1.26

require (
	github.com/adriaanm/wata/go-pkgs/appleptt v0.0.0
	github.com/adriaanm/wata/go-pkgs/iosshell v0.0.0
	github.com/adriaanm/wata/go-pkgs/iosui v0.0.0
	github.com/ebitengine/purego v0.11.0-alpha.8
)

require github.com/adriaanm/wata/go-pkgs/macaudio v0.0.0 // indirect

replace github.com/adriaanm/wata/go-pkgs/appleptt => ../../../go-pkgs/appleptt

replace github.com/adriaanm/wata/go-pkgs/iosshell => ../../../go-pkgs/iosshell

replace github.com/adriaanm/wata/go-pkgs/iosui => ../../../go-pkgs/iosui

replace github.com/adriaanm/wata/go-pkgs/macaudio => ../../../go-pkgs/macaudio
