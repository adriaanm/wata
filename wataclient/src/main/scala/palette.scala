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
 *    rule into every body. All eight are light and saturated.
 *  - **No two are confusable as a blur going past.** They are spread around
 *    the wheel rather than picked for prettiness — roughly 20 / 45 / 75 / 120 /
 *    210 / 260 / 300 / 335 degrees. The gap between 120 and 210 is deliberate:
 *    it is where CYAN sits, and cyan is the FAMILY thread's, which must not
 *    collide with a person's.
 *
 *  Eight is plan 0070's own guess, bounded by that second constraint, and it
 *  wants checking against real hues on the real panel — which is dimmer and
 *  cooler than any mock.
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
    if i == 0 then 0xfc0d      // rgb(255,130,110)  salmon      ~20deg
    else if i == 1 then 0xfda7 // rgb(255,180, 60)  amber       ~45deg
    else if i == 2 then 0xdf4b // rgb(220,235, 90)  lime        ~75deg
    else if i == 3 then 0x7f2f // rgb(120,230,120)  green      ~120deg
    else if i == 4 then 0x6dff // rgb(110,190,255)  sky        ~210deg
    else if i == 5 then 0xad1f // rgb(175,160,255)  lilac      ~260deg
    else if i == 6 then 0xe4be // rgb(230,150,245)  orchid     ~300deg
    else 0xfc76                // rgb(255,140,180)  pink       ~335deg

  def hueName(i0: Int): String =
    var i = i0 % COUNT
    if i < 0 then i = i + COUNT
    if i == 0 then "salmon"
    else if i == 1 then "amber"
    else if i == 2 then "lime"
    else if i == 3 then "green"
    else if i == 4 then "sky"
    else if i == 5 then "lilac"
    else if i == 6 then "orchid"
    else "pink"

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
   *  Eight buckets and a handful of family members COLLIDE, and that is
   *  arithmetic rather than a defect: five people in eight hues share one about
   *  four times in five. The fix is the profile field this stands in for — a
   *  colour someone chose — not a cleverer hash. */
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

  /** the colour a user id falls back to. Stable, client-independent, and the
   *  only thing any client should call until the profile field ships. */
  def forUser(userId: String): Int = hue(hashOf(userId))

  /** the colour a whole conversation shows. A DM is its contact; the family
   *  thread is cyan by rule; a group is derived from its own room id, so two
   *  groups do not read alike. */
  def forConversation(convType: ConversationType, hasContact: Boolean,
      contactId: String, roomId: String): Int = convType match
    case _: FamilyConv => FAMILY
    case _             =>
      if hasContact && contactId != "" then forUser(contactId)
      else if roomId != "" then forUser(roomId)
      else FAMILY
