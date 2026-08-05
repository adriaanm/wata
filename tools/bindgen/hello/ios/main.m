// The ObjC shell of the PushToTalk hello: a window, four controls, and a log.
//
// It exists only because an iOS app needs a UIApplicationMain and a bundle.
// Every PushToTalk call is in the Go archive next door (../hellopt), through
// the bindings tools/bindgen generated — nothing here talks to the framework.

#import <UIKit/UIKit.h>
#import "libhello.h"

@interface WataHelloController : UIViewController
@property(nonatomic, strong) UITextView *log;
@end

@implementation WataHelloController

- (UIButton *)button:(NSString *)title action:(SEL)action {
  UIButton *b = [UIButton buttonWithType:UIButtonTypeSystem];
  [b setTitle:title forState:UIControlStateNormal];
  b.titleLabel.font = [UIFont systemFontOfSize:20];
  [b addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
  return b;
}

- (void)viewDidLoad {
  [super viewDidLoad];
  self.view.backgroundColor = UIColor.systemBackgroundColor;

  self.log = [[UITextView alloc] init];
  self.log.editable = NO;
  self.log.font = [UIFont monospacedSystemFontOfSize:12 weight:UIFontWeightRegular];

  UIButton *talk = [UIButton buttonWithType:UIButtonTypeSystem];
  [talk setTitle:@"HOLD TO TALK" forState:UIControlStateNormal];
  talk.titleLabel.font = [UIFont boldSystemFontOfSize:24];
  [talk addTarget:self action:@selector(talkDown) forControlEvents:UIControlEventTouchDown];
  [talk addTarget:self
                action:@selector(talkUp)
      forControlEvents:UIControlEventTouchUpInside | UIControlEventTouchUpOutside |
                       UIControlEventTouchCancel];

  UIStackView *row = [[UIStackView alloc] initWithArrangedSubviews:@[
    [self button:@"Boot" action:@selector(boot)],
    [self button:@"Join" action:@selector(join)],
    [self button:@"Leave" action:@selector(leave)],
  ]];
  row.distribution = UIStackViewDistributionFillEqually;

  UIStackView *stack =
      [[UIStackView alloc] initWithArrangedSubviews:@[ row, talk, self.log ]];
  stack.axis = UILayoutConstraintAxisVertical;
  stack.spacing = 12;
  stack.translatesAutoresizingMaskIntoConstraints = NO;
  [self.view addSubview:stack];

  UILayoutGuide *safe = self.view.safeAreaLayoutGuide;
  [NSLayoutConstraint activateConstraints:@[
    [stack.topAnchor constraintEqualToAnchor:safe.topAnchor constant:12],
    [stack.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor constant:12],
    [stack.trailingAnchor constraintEqualToAnchor:safe.trailingAnchor constant:-12],
    [stack.bottomAnchor constraintEqualToAnchor:safe.bottomAnchor constant:-12],
    [talk.heightAnchor constraintEqualToConstant:96],
  ]];

  [NSTimer scheduledTimerWithTimeInterval:0.5
                                  repeats:YES
                                    block:^(NSTimer *t) { [self refresh]; }];
}

- (void)refresh {
  char *text = HelloLog();
  self.log.text = [NSString stringWithUTF8String:text];
  free(text);
}

- (void)boot { HelloBoot(); }
- (void)join { HelloJoin(); }
- (void)leave { HelloLeave(); }
- (void)talkDown { HelloTalk(); }
- (void)talkUp { HelloStopTalk(); }

@end

@interface WataHelloDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic, strong) UIWindow *window;
@end

@implementation WataHelloDelegate
- (BOOL)application:(UIApplication *)app
    didFinishLaunchingWithOptions:(NSDictionary *)options {
  self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
  self.window.rootViewController = [[WataHelloController alloc] init];
  [self.window makeKeyAndVisible];
  // The framework insists a channel manager exists early in the launch, or it
  // tears down channels and their ability to receive pushes.
  HelloBoot();
  return YES;
}
@end

int main(int argc, char *argv[]) {
  @autoreleasepool {
    return UIApplicationMain(argc, argv, nil,
                             NSStringFromClass(WataHelloDelegate.class));
  }
}
