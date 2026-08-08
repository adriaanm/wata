# 0040 — the exit is a menu, not a trapdoor

Status: done

## The problem

Back from the contacts view, twice, quits the app. That edge exists for a
dev/ssh run: the handset boots straight into wata and inittab respawns
it, so quitting is not a user-facing action, and the two-step arm
(`QUIT_ARM_S`) is there so a stray red key does not leave a black screen.

It works, and it answers the wrong question. What someone holding the
handset actually wants at that moment is one of a small set of things —
restart the app, restart the machine, turn it off, or get it into a mode
where a cable can reach it — and today exactly one of those is on the
exit path while three others are buried in Settings, eleven rows down a
scrolling menu, next to the brightness slider. The exit is the natural
place to ask "what do you want to do?", and it currently answers "you
wanted to quit" without asking.

Two concrete consequences. Quitting looks identical to a crash: the
screen goes black and the app comes back, with nothing saying that was
deliberate. And the recovery modes — fastboot and EDL — are the ones a
person needs precisely when the device is misbehaving, which is when
they are least able to navigate a scrolling settings menu to find them.

## The decision

**The confirmed exit edge opens a menu instead of returning true.**

Five actions, in the order a person escalates:

| action | what it does | confirm |
|---|---|---|
| Restart app | quit — inittab respawns it | one |
| Reboot | `reboot` | one |
| Power off | `Diag.powerOff()` | one |
| Reboot to fastboot | `Diag.rebootBootloader()` | **two** |
| Reboot to EDL | `Diag.rebootEdl()` | **two** |

The split is not about how destructive the action is — a reboot and a
power-off are both fine, the handset comes back. It is about **who can
undo it**. Reboot, power-off and restart-app all end with a device its
owner can use again by pressing a button. Fastboot and EDL end with a
device that shows nothing, responds to nothing, and stays that way until
somebody with a cable and a host machine intervenes. A kid who explores
the menu must not be able to reach that state by pressing OK twice, and
the second confirmation is what makes the difference legible: it says
this one is different, in the same breath as asking.

**Reuse Settings' machinery rather than inventing any.** The three power
rows already live in `SettingsLogic` with the `armed` latch, and
`Diag.powerOff`/`rebootBootloader`/`rebootEdl` already exist and already
degrade to a logged no-op off-device. The exit menu is a second surface
over the same actions, not a second implementation of them. Settings
keeps its rows: someone already there should not have to leave to power
off.

### What "two confirmations" means concretely

`armed` is a Boolean latch, and a second step needs a third state, so the
confirmation becomes a small counter rather than a flag: 0 unarmed, 1
armed, 2 armed-again. A one-confirm action fires at 1→OK; a two-confirm
action fires at 2→OK. Every other key drops it back to 0, which is the
rule Settings already applies (`disarmed`), and the prompt says what the
NEXT OK does, which is the shape Settings' action rows already use.

The arming also **times out**, like the quit arm it replaces: an armed
menu left alone returns to unarmed rather than sitting one keypress away
from EDL until the screensaver takes the panel.

### Where the state lives

The exit menu is a mode of the shell, not an applet: it is modal (it
takes every key while open), it outlives no session, and making it an
applet would put it in the left/right applet rotation, where it must not
be. So `Ui` grows an exit-menu cell beside `quitArmC` — the same
single-goroutine-per-cell discipline, since only the UI loop touches it
— and `frameStep` routes input to it while it is open. `quitArmC` and
`QUIT_ARM_S` are **replaced**, not kept: the two-step quit existed to
stop a stray key from quitting, and a menu that needs a deliberate
selection does that better.

Rendering follows Settings' menu drawing (`VISIBLE` window, selected row,
detail line as the confirmation prompt). Five rows fit the 160×128 panel
without scrolling, which is why the action list stays at five.

## What changes

- `wata-fb/src/main/scala/ui.scala` — the exit-menu cell and its arming
  timeout; `frameStep` routes to it while open; the confirmed edge opens
  it instead of returning true. `quitArmC`/`QUIT_ARM_S`/`quitArmed` go.
- `wata-fb/src/main/scala/applets.scala` — `ExitMenuState` + the pure
  transition/render functions, in the same `*Logic` style as
  `SettingsLogic`. `Restart app` is the only new action; the rest call
  the existing `Diag` entry points, plus a `Diag.reboot()` for the plain
  reboot Settings does not have.
- `wata-fb/src/main/scala/uiscript.scala` — the `quitarm` probe becomes
  an exit-menu probe (open, selected row, confirm level), so the scripted
  runs can drive and pin it.
- `docs/design/wata-fb.md` — the QUIT section describes the menu.

## How it is verified

- **`just fb-ui-tests`** is the oracle: a scripted run opens the menu,
  walks the rows, and pins a golden frame per checkpoint. This is what
  makes the confirm counter's behaviour testable without a handset — in
  particular the two-confirm rows, where the check is that ONE OK on
  "Reboot to EDL" fires nothing and only changes the prompt.
- **A negative check in the same run**: any key other than OK on an armed
  row returns the confirm level to 0. The failure this guards against is
  a menu that silently stays armed while the person thinks they left.
- **`just golden`** stays byte-exact for the frames that do not involve
  the menu — the exit path is the only thing moving.
- **On the handset**: open the menu and take `Restart app` and `Reboot`.
  Fastboot and EDL are exercised only as far as the second prompt — the
  actions themselves are already proven (Settings has been running them),
  and confirming them for real costs a cable and a person.

## Out of scope

- Removing the power rows from Settings. Two surfaces over the same
  actions is the intent, not duplication to be cleaned up later.
- A password or parental gate on the recovery modes. The second
  confirmation is the guard this plan is making; whether the handset
  needs a real lock is a product question that has not been asked yet.
- Anything about what fastboot or EDL then do. The device-side tools
  (`reboot-bootloader`, `reboot-edl`) exist in bq268-alpine and are
  unchanged here.
