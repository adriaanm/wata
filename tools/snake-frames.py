#!/usr/bin/env python3
"""Frame-count designer for the snake uitest scenario.

Mirrors wata-fb's SnakeLogic (snake.scala) frame for frame — the minstd food
PRNG, placeFood's draw order, step/update semantics, and the uitest virtual
clock's dt of exactly 33/1000 seconds — and replays the gameplay part of
tools/fb-ui-scripts/alice-snake.txt. Run it after changing the game logic,
the seed, or the script's moves: it prints the game state at each checkpoint
and the exact frame counts the script's `idle` directives must use.

The mirror is exact because both sides use the same IEEE-754 doubles and an
all-64-bit-integer LCG; any drift between this and the Scala means one of the
two changed and the other must follow.
"""

GRID_W, GRID_H = 26, 14
TICK_START, TICK_MIN, TICK_DECR = 0.15, 0.06, 0.005
DT = 33 / 1000.0
SEED = 88

UP, DOWN, LEFT, RIGHT = range(4)
DX = {UP: 0, DOWN: 0, LEFT: -1, RIGHT: 1}
DY = {UP: -1, DOWN: 1, LEFT: 0, RIGHT: 0}
OPP = {UP: DOWN, DOWN: UP, LEFT: RIGHT, RIGHT: LEFT}


def nxt(r):
    return (r * 48271) % 2147483647


def pack(x, y):
    return y * GRID_W + x


def place_food(body, r):
    for _ in range(100):
        r = nxt(r)
        fx = r % GRID_W
        r = nxt(r)
        fy = r % GRID_H
        p = pack(fx, fy)
        if p not in body:
            return p, r
    for p in range(GRID_W * GRID_H):
        if p not in body:
            return p, r
    return -1, r


class Snake:
    def __init__(self, seed=SEED):
        self.rng = seed
        self.reset()

    def reset(self):
        cx, cy = GRID_W // 2, GRID_H // 2
        self.body = [pack(cx, cy), pack(cx - 1, cy), pack(cx - 2, cy)]
        self.dir = self.next_dir = RIGHT
        self.alive = True
        self.score = 0
        self.tick_timer = 0.0
        self.tick_rate = TICK_START
        self.food, self.rng = place_food(self.body, self.rng)

    def step(self):
        if not self.alive:
            return
        self.dir = self.next_dir
        hx = self.body[0] % GRID_W + DX[self.dir]
        hy = self.body[0] // GRID_W + DY[self.dir]
        if not (0 <= hx < GRID_W and 0 <= hy < GRID_H):
            self.alive = False
            return
        nh = pack(hx, hy)
        if nh in self.body:
            self.alive = False
            return
        if nh == self.food:
            self.food, self.rng = place_food(self.body, self.rng)
            self.score += 10
            if self.tick_rate > TICK_MIN:
                self.tick_rate -= TICK_DECR
            self.body = [nh] + self.body
        else:
            self.body = [nh] + self.body[:-1]

    def frame(self, key=None):
        # Ui.frameStep order: input first, then the tick.
        if key == "enter":
            if not self.alive:
                self.reset()
        elif key is not None and self.alive and OPP[key] != self.dir:
            self.next_dir = key
        if self.alive:
            self.tick_timer += DT
            while self.tick_timer >= self.tick_rate:
                self.tick_timer -= self.tick_rate
                self.step()

    def state(self):
        return dict(head=(self.body[0] % GRID_W, self.body[0] // GRID_W),
                    food=(self.food % GRID_W, self.food // GRID_W),
                    score=self.score, alive=self.alive, len=len(self.body),
                    rate=round(self.tick_rate, 3))


def main():
    s = Snake()

    def cp(name):
        print(f"{name:16} {s.state()}")

    s.frame(), s.frame()                      # tap dot1 (press activates + ticks)
    cp("snake-open")
    n = 0
    while s.score == 0 and n < 200:           # -> the script's first `idle`
        s.frame()
        n += 1
    print(f"{'':16} frames to the eat: {n}")
    cp("snake-eat")
    s.frame(UP), s.frame()                    # tap up
    n = 0
    while s.alive and n < 200:                # -> the second `idle` (margin ok:
        s.frame()                             #    a dead board is frozen)
        n += 1
    print(f"{'':16} frames to the wall: {n}")
    cp("snake-gameover")
    s.frame("enter"), s.frame()               # tap enter
    cp("snake-restart")


if __name__ == "__main__":
    main()
