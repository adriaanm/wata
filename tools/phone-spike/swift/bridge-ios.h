// The iOS-simulator counterpart of bridge.h. The iOS framework keeps the plain
// name `Watamobile` (gomobile only suffixes the macOS one), so `import
// Watamobile` would work here — the shell uses a bridging header on both legs
// so that main.swift is byte-identical across them.
#import <Watamobile/Watamobile.objc.h>
