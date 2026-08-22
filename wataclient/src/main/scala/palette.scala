/** THE PERSON'S COLOUR — plan 0070's rolodex is identity before text, and the
 *  identity is a hue.
 *
 *  Colour is meant to be a property of the PERSON, stored server-side and
 *  fanned out the way display names already are, so that every client shows
 *  the same person the same colour and the user gets to pick it. That field
 *  does not exist yet. What exists is the FALLBACK the plan specifies for a
 *  profile that has never set one: a deterministic derivation from the user
 *  id, so every screen is colourful from the first sync rather than waiting on
 *  a setup gate.
 *
 *  It lives HERE, in the portable client core, for the reason the plan gives:
 *  each client must not invent its own. A handset and a wrist showing the same
 *  kid two different colours would break the one thing the design is built on
 *  ("roll to my colour and hold the button"), and the only way to prevent that
 *  is one function every client calls.
 *
 *  ## The palette's constraints
 *
 *  Eight hues, and both constraints are the plan's:
 *
 *  - **Every one carries BLACK text.** The card is full bleed and the name is
 *    drawn on it, so a hue that needs white text is a hue that forces a second
 *    rule into every body. Every hue below sits at 7.5:1 or better against
 *    black, which is a large-bold-text margin with room to spare for a panel
 *    dimmer than any mock.
 *  - **No two are confusable as a blur going past.** Hue alone does not buy
 *    that: eight LIGHT saturated colours spread evenly around the wheel put two
 *    yellow-greens and three warm pinks next to each other, which is exactly
 *    what a first pass at this produced (a `lime` at 75deg beside a `green` at
 *    120deg read as one colour on the panel). So the palette is spread on
 *    THREE axes, not one:
 *
 *      - **hue**, ~8 / 34 / 55 / 120 / 210 / 262 / 315 degrees, with a
 *        deliberate hole at 180: that is CYAN, the FAMILY thread's, which must
 *        not collide with a person's;
 *      - **lightness**, so neighbours on the wheel differ in value too — the
 *        yellow is the brightest thing here and the green next to it is a third
 *        darker, which is what separates them when they are 22 px tall and
 *        moving;
 *      - **chroma**: the eighth is a low-saturation SAND rather than a second
 *        pink. A near-neutral is instantly not-a-hue, and it buys more
 *        separation than an eighth saturated colour crammed between two others.
 *
 *  Eight is plan 0070's own guess, bounded by that second constraint, and it
 *  wants checking against real hues on the real panel — which is dimmer and
 *  cooler than any mock.
 *
 *  ## Why the assignment is over the SET
 *
 *  Eight buckets and a hash per id means the birthday problem: five people in
 *  eight hues share one about four times in five, and a roster where two of
 *  five look identical defeats the whole design ("roll to my colour"). So the
 *  derived colour is a pure function of the **roster**, not of each id alone —
 *  `forRoster`. Sort the ids, walk them in that order, and give each one its
 *  hashed preference or, if that is taken, the next free hue. Every client
 *  computes the same mapping from the same roster, which is the property that
 *  made a derived colour acceptable at all; and it degrades gracefully, because
 *  an id keeps its preferred hue whenever nothing else wanted it.
 *
 *  A roster larger than the palette still collides — it must — but only after
 *  all eight are spent, and then evenly rather than at random.
 *
 *  This is a stand-in with a shorter life than it looks: the real answer is the
 *  server-side profile colour plan 0070 specifies, a colour someone CHOSE. What
 *  the set-based assignment buys is that the months before that ships do not
 *  demo two identical greens.
 *
 *  Colours are RGB565, the algebra's currency.
 */
object Palette:
  /** how many hues a person can be. */
  val COUNT = 8

  /** the family thread's colour, which is not in the rotation: the family is
   *  not a person, and every client has drawn it cyan since the beginning. */
  val FAMILY = 0x07ff

  /** every hue in this palette carries black text — that IS the constraint, so
   *  the ink is a property of the palette rather than a decision each body
   *  makes. */
  val INK = 0x0000

  /** the `i`th hue, wrapped. RGB565 literals with their 8-bit source alongside,
   *  because a 565 word is unreadable and the next person to re-tune these on a
   *  real panel needs the numbers they will actually edit. */
  def hue(i0: Int): Int =
    var i = i0 % COUNT
    if i < 0 then i = i + COUNT
    if i == 0 then 0xfb8b      // rgb(255,112, 88)  coral     ~8deg   7.7:1
    else if i == 1 then 0xfd86 // rgb(255,176, 48)  amber     ~34deg 11.4:1
    else if i == 2 then 0xf729 // rgb(244,228, 72)  yellow    ~55deg 15.8:1
    else if i == 3 then 0x6ead // rgb(104,214,104)  green    ~120deg 11.2:1
    else if i == 4 then 0x75df // rgb(116,186,255)  sky      ~210deg 10.0:1
    else if i == 5 then 0xbcbf // rgb(186,150,255)  violet   ~262deg  8.7:1
    else if i == 6 then 0xfc1a // rgb(250,128,214)  magenta  ~315deg  9.1:1
    else 0xd5f2                // rgb(214,188,150)  sand      (low chroma)

  def hueName(i0: Int): String =
    var i = i0 % COUNT
    if i < 0 then i = i + COUNT
    if i == 0 then "coral"
    else if i == 1 then "amber"
    else if i == 2 then "yellow"
    else if i == 3 then "green"
    else if i == 4 then "sky"
    else if i == 5 then "violet"
    else if i == 6 then "magenta"
    else "sand"

  /** the derivation: a small polynomial hash over the id's UTF-8 bytes, taken
   *  modulo a prime at every step so nothing overflows and the answer does not
   *  depend on this platform's integer width. Two clients on two architectures
   *  must agree, and "it happens to fit in an Int here" is not an agreement.
   *
   *  The accumulator is then FOLDED before anyone takes it modulo eight. A hue
   *  index is three bits, and without the fold those three bits are all that
   *  ever mattered — which makes the answer a function of the id's last few
   *  bytes far more than of the id. The shifts are what make the whole
   *  accumulator contribute.
   *
   *  A hash alone COLLIDES on a small roster, which is arithmetic rather than a
   *  defect in the hash — and it is why nothing should call `forUser` to paint
   *  a roster. This is the PREFERENCE an id brings to `forRoster`, which
   *  resolves the collisions over the whole set. */
  def hashOf(id: String): Int =
    val bs = id.bytes
    // the seed is small on purpose: every intermediate stays well inside 32
    // bits, so this cannot depend on how wide an `Int` happens to be.
    var h = 5381
    var i = 0
    val n = bs.size
    while i < n do
      h = (h * 131 + bs(i)) % PRIME
      i += 1
    h ^ (h >> 3) ^ (h >> 7) ^ (h >> 13)

  val PRIME = 1000003

  /** the hue one id PREFERS, ignoring everyone else. Stable and
   *  client-independent, but on its own it is the birthday problem — paint a
   *  roster with `forRoster`, not with this. */
  def forUser(userId: String): Int = hue(hashOf(userId))

  /** what a conversation takes its colour from: a DM is its CONTACT (so the
   *  same person is the same colour in every thread they appear in), a group is
   *  its own room id (so two groups do not read alike), and the family thread
   *  answers `""` — it is cyan by rule and takes no hue out of the rotation. */
  def subjectOf(convType: ConversationType, hasContact: Boolean,
      contactId: String, roomId: String): String = convType match
    case _: FamilyConv => ""
    case _             =>
      if hasContact && contactId != "" then contactId
      else if roomId != "" then roomId
      else ""

  /** the colour a whole conversation shows, judged ALONE. Kept for a caller
   *  that genuinely has one conversation and no roster; a screen showing
   *  several must use `forRoster`, or two of them can come out identical. */
  def forConversation(convType: ConversationType, hasContact: Boolean,
      contactId: String, roomId: String): Int =
    val s = subjectOf(convType, hasContact, contactId, roomId)
    if s == "" then FAMILY else forUser(s)

  // ---- the set-based assignment ---------------------------------------------

  /** one id and the hue index it ended up with. */
  case class HueSlot(id: String, idx: Int)

  /** THE ROSTER'S COLOURS, in the order the subjects were given. `""` is the
   *  family thread and comes back as `FAMILY` without consuming a hue.
   *
   *  A pure function of the SET: the ids are sorted, so the answer does not
   *  depend on what order a client's sync happened to build its list in, and
   *  two clients showing the same roster agree without talking to each other. */
  def forRoster(subjects: List[String]): List[Int] =
    val slots = assign(sortIds(distinctIds(subjects)))
    var acc: List[Int] = Nil
    var cur = subjects
    var going = true
    while going do
      cur match
        case h :: t =>
          acc = (if h == "" then FAMILY else hue(slotOf(slots, h))) :: acc
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  /** the greedy walk: each id takes its hashed preference, or the next free hue
   *  after it. `used` is a bitmask because the palette is eight wide — when all
   *  eight are spent the mask resets and the roster starts a second lap, so a
   *  large family collides evenly instead of stalling on a full palette. */
  def assign(ids: List[String]): List[HueSlot] =
    var acc: List[HueSlot] = Nil
    var used = 0
    var cur = ids
    var going = true
    while going do
      cur match
        case h :: t =>
          if used == (1 << COUNT) - 1 then used = 0
          var k = hashOf(h) % COUNT
          if k < 0 then k = k + COUNT
          var tries = 0
          while tries < COUNT && (used & (1 << k)) != 0 do
            k = (k + 1) % COUNT
            tries += 1
          used = used | (1 << k)
          acc = HueSlot(h, k) :: acc
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  def slotOf(slots: List[HueSlot], id: String): Int =
    var out = 0
    var cur = slots
    var going = true
    while going do
      cur match
        case h :: t =>
          if h.id == id then
            out = h.idx
            going = false
          else cur = t
        case Nil => going = false
    out

  /** the ids, once each, in the order given — a roster can name the same person
   *  twice (a DM and a group they are in) and that must not eat two hues. The
   *  empty subject (the family thread) is not an id and is dropped. */
  def distinctIds(subjects: List[String]): List[String] =
    var acc: List[String] = Nil
    var cur = subjects
    var going = true
    while going do
      cur match
        case h :: t =>
          if h != "" && !containsId(acc, h) then acc = h :: acc
          cur = t
        case Nil => going = false
    ListOps.reverse(acc)

  def containsId(xs: List[String], id: String): Boolean =
    var out = false
    var cur = xs
    var going = true
    while going do
      cur match
        case h :: t =>
          if h == id then
            out = true
            going = false
          else cur = t
        case Nil => going = false
    out

  /** insertion sort — a roster is a family, so `n` is single digits and the
   *  point is that the order is the SAME everywhere, not that it is fast. */
  def sortIds(ids: List[String]): List[String] =
    var out: List[String] = Nil
    var cur = ids
    var going = true
    while going do
      cur match
        case h :: t =>
          out = insertId(out, h)
          cur = t
        case Nil => going = false
    out

  def insertId(sorted: List[String], id: String): List[String] =
    var head: List[String] = Nil // the part before `id`, reversed
    var cur = sorted
    var going = true
    while going do
      cur match
        case h :: t =>
          if lessId(id, h) then going = false
          else
            head = h :: head
            cur = t
        case Nil => going = false
    var out = id :: cur
    var going2 = true
    while going2 do
      head match
        case h :: t =>
          out = h :: out
          head = t
        case Nil => going2 = false
    out

  /** byte-lexicographic `<`. Spelled out over the UTF-8 bytes because that is
   *  the one ordering every platform this runs on agrees about — a locale-aware
   *  string compare is exactly the kind of thing two clients could disagree on,
   *  and disagreeing here means two devices painting a family two ways. */
  def lessId(a: String, b: String): Boolean =
    val x = a.bytes
    val y = b.bytes
    val n = if x.size < y.size then x.size else y.size
    var i = 0
    var out = x.size < y.size
    var going = true
    while going && i < n do
      if x(i) != y(i) then
        out = x(i) < y(i)
        going = false
      else i += 1
    out
