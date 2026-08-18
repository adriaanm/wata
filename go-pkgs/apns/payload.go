package apns

// Alert is the aps.alert dictionary: what the system shows in the banner.
type Alert struct {
	Title string `json:"title,omitempty"`
	Body  string `json:"body,omitempty"`
}

// Aps is the reserved "aps" dictionary APNs itself reads. Sound and Badge
// are optional — omitted, the system uses no sound and leaves the badge
// count alone.
type Aps struct {
	Alert Alert `json:"alert"`

	// Sound names a bundled sound file, or "default" for the system sound.
	// Empty means silent.
	Sound string `json:"sound,omitempty"`

	// Badge sets the app icon's badge count. Nil leaves it unchanged; a
	// present value (including 0, to clear it) sets it.
	Badge *int `json:"badge,omitempty"`

	// InterruptionLevel is "time-sensitive" for a message notification: it
	// is allowed to break through Focus and a mute switch, which a
	// walkie-talkie message warrants and a routine notification does not.
	InterruptionLevel string `json:"interruption-level,omitempty"`
}

// Payload is one push's whole JSON body: the reserved "aps" dictionary plus
// wata's own top-level keys identifying what arrived, so a tap can open the
// right conversation without a round trip to the server first.
type Payload struct {
	Aps     Aps    `json:"aps"`
	RoomID  string `json:"room_id,omitempty"`
	EventID string `json:"event_id,omitempty"`
}

// AlertPayload builds the time-sensitive message-arrived notification tier
// 2 sends: a title/body alert with the default sound, marked
// time-sensitive, carrying the room and event id a tap needs to open the
// right conversation. badge is optional — pass nil to leave the app's badge
// count unchanged.
func AlertPayload(title, body, roomID, eventID string, badge *int) Payload {
	return Payload{
		Aps: Aps{
			Alert:             Alert{Title: title, Body: body},
			Sound:             "default",
			Badge:             badge,
			InterruptionLevel: "time-sensitive",
		},
		RoomID:  roomID,
		EventID: eventID,
	}
}
