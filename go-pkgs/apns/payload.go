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

// PTTPayload is a PushToTalk push's whole body, and it shares nothing with
// Payload: there is no aps dictionary, because the system never presents this
// push — it hands it to the PushToTalk framework, which wakes the app and
// asks it to report the active speaker back. `activeSpeaker` is what the
// framework requires the payload to name; an empty one means the speaker
// stopped.
//
// The room and event ids ride along as wata's own keys, exactly as they do on
// an alert, so the woken app knows which clip to fetch and play.
type PTTPayload struct {
	ActiveSpeaker string `json:"activeSpeaker"`
	RoomID        string `json:"room_id,omitempty"`
	EventID       string `json:"event_id,omitempty"`
}

// ChannelPayload builds the PushToTalk push for one arriving message:
// speaker is the display name the framework shows as the active speaker.
func ChannelPayload(speaker, roomID, eventID string) PTTPayload {
	return PTTPayload{ActiveSpeaker: speaker, RoomID: roomID, EventID: eventID}
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
