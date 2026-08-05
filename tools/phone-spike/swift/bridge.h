// The bridging header for the Swift shell. gomobile names the macOS framework
// `Watamobile-Macos` (target suffix, not our choice), and a clang module whose
// name contains a hyphen cannot be `import`ed from Swift — so the shell reaches
// the bound ObjC API through this header instead of a module import.
#import <Watamobile-Macos/Watamobile.objc.h>
