module github.com/adriaanm/wata/tools/watch-spike/app

go 1.26

require github.com/ebitengine/purego v0.11.0-alpha.8

require github.com/adriaanm/wata/go-pkgs/macaudio v0.0.0

require github.com/adriaanm/wata/go-pkgs/appleptt v0.0.0 // indirect

replace github.com/adriaanm/wata/go-pkgs/macaudio => ../../../go-pkgs/macaudio

replace github.com/adriaanm/wata/go-pkgs/appleptt => ../../../go-pkgs/appleptt
